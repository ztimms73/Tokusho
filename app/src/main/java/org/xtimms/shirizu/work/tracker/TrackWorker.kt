package org.xtimms.shirizu.work.tracker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
import androidx.core.app.NotificationCompat.VISIBILITY_SECRET
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.await
import coil.ImageLoader
import coil.request.ImageRequest
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.xtimms.shirizu.R
import org.xtimms.shirizu.core.exceptions.CloudflareProtectedException
import org.xtimms.shirizu.core.logs.FileLogger
import org.xtimms.shirizu.core.logs.TrackerLogger
import org.xtimms.shirizu.core.prefs.AppSettings
import org.xtimms.shirizu.core.tracker.Tracker
import org.xtimms.shirizu.core.tracker.model.MangaUpdates
import org.xtimms.shirizu.utils.lang.awaitUniqueWorkInfoByName
import org.xtimms.shirizu.utils.lang.onEachIndexed
import org.xtimms.shirizu.utils.lang.toBitmapOrNull
import org.xtimms.shirizu.utils.system.checkNotificationPermission
import org.xtimms.shirizu.utils.system.trySetForeground
import org.xtimms.shirizu.work.PeriodicWorkScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class TrackWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val coil: ImageLoader,
    private val tracker: Tracker,
    private val workManager: WorkManager,
    @TrackerLogger private val logger: FileLogger,
) : CoroutineWorker(context, workerParams) {

    private val notificationManager by lazy { NotificationManagerCompat.from(applicationContext) }

    override suspend fun doWork(): Result {
        trySetForeground()
        logger.log("doWork(): attempt $runAttemptCount")
        return try {
            doWorkImpl()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.log("fatal", e)
            Result.failure()
        } finally {
            withContext(NonCancellable) {
                logger.flush()
                notificationManager.cancel(WORKER_NOTIFICATION_ID)
            }
        }
    }

    private suspend fun doWorkImpl(): Result {
        if (!AppSettings.isTrackerEnabled()) {
            return Result.success(workDataOf(0, 0))
        }
        val retryIds = getRetryIds()
        val tracks = if (retryIds.isNotEmpty()) {
            tracker.getTracks(retryIds)
        } else {
            tracker.getAllTracks()
        }
        logger.log("Total ${tracks.size} tracks")
        if (tracks.isEmpty()) {
            return Result.success(workDataOf(0, 0))
        }

        val results = checkUpdatesAsync(tracks)
        tracker.gc()

        var success = 0
        var failed = 0
        val retry = HashSet<Long>()
        results.forEach { x ->
            when (x) {
                is MangaUpdates.Success -> success++
                is MangaUpdates.Failure -> {
                    failed++
                    if (x.shouldRetry()) {
                        retry += x.manga.id
                    }
                }
            }
        }
        if (runAttemptCount > MAX_ATTEMPTS) {
            retry.clear()
        }
        setRetryIds(retry)
        logger.log("Result: success: $success, failed: $failed, retry: ${retry.size}")
        val resultData = workDataOf(success, failed)
        return when {
            retry.isNotEmpty() -> Result.retry()
            success == 0 && failed != 0 -> Result.failure(resultData)
            else -> Result.success(resultData)
        }
    }

    private suspend fun checkUpdatesAsync(tracks: List<TrackingItem>): List<MangaUpdates> {
        val semaphore = Semaphore(MAX_PARALLELISM)
        return channelFlow {
            for ((track, channelId) in tracks) {
                launch {
                    semaphore.withPermit {
                        send(
                            runCatchingCancellable {
                                tracker.fetchUpdates(track, commit = true)
                                    .copy(channelId = channelId)
                            }.onFailure { e ->
                                logger.log("checkUpdatesAsync", e)
                            }.getOrElse { error ->
                                MangaUpdates.Failure(
                                    manga = track.manga,
                                    error = error,
                                )
                            },
                        )
                    }
                }
            }
        }.onEachIndexed { index, it ->
            if (applicationContext.checkNotificationPermission()) {
                notificationManager.notify(WORKER_NOTIFICATION_ID, createWorkerNotification(tracks.size, index + 1))
            }
            when (it) {
                is MangaUpdates.Failure -> {
                    val e = it.error
                    if (e is CloudflareProtectedException) {
                        e.printStackTrace()
                    }
                }

                is MangaUpdates.Success -> {
                    if (it.isValid && it.isNotEmpty()) {
                        showNotification(
                            manga = it.manga,
                            channelId = it.channelId,
                            newChapters = it.newChapters,
                        )
                    }
                }
            }
        }.toList(ArrayList(tracks.size))
    }

    private suspend fun showNotification(
        manga: Manga,
        channelId: String?,
        newChapters: List<MangaChapter>,
    ) {
        if (newChapters.isEmpty() || channelId == null || !applicationContext.checkNotificationPermission()) {
            return
        }
        val id = manga.url.hashCode()
        val builder = NotificationCompat.Builder(applicationContext, channelId)
        val summary = applicationContext.resources.getQuantityString(
            R.plurals.new_chapters,
            newChapters.size,
            newChapters.size,
        )
        with(builder) {
            setContentText(summary)
            setContentTitle(manga.title)
            setNumber(newChapters.size)
            setLargeIcon(
                coil.execute(
                    ImageRequest.Builder(applicationContext)
                        .data(manga.coverUrl)
                        .tag(manga.source)
                        .build(),
                ).toBitmapOrNull(),
            )
            setSmallIcon(R.drawable.ic_stat_shirizu)
            val style = NotificationCompat.InboxStyle(this)
            for (chapter in newChapters) {
                style.addLine(chapter.name)
            }
            style.setSummaryText(manga.title)
            style.setBigContentTitle(summary)
            setStyle(style)
            setAutoCancel(true)
            setCategory(NotificationCompat.CATEGORY_PROMO)
            setVisibility(if (manga.isNsfw) VISIBILITY_SECRET else VISIBILITY_PUBLIC)
            setShortcutId(manga.id.toString())
            priority = NotificationCompat.PRIORITY_DEFAULT
        }
        notificationManager.notify(TAG, id, builder.build())
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = NotificationChannelCompat.Builder(
            WORKER_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(applicationContext.getString(R.string.check_for_new_chapters))
            .setShowBadge(false)
            .setVibrationEnabled(false)
            .setSound(null, null)
            .setLightsEnabled(false)
            .build()
        notificationManager.createNotificationChannel(channel)

        val notification = createWorkerNotification(0, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                WORKER_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
        }
    }

    private fun createWorkerNotification(max: Int, progress: Int) = NotificationCompat.Builder(
        applicationContext,
        WORKER_CHANNEL_ID,
    ).apply {
        setContentTitle(applicationContext.getString(R.string.check_for_new_chapters))
        priority = NotificationCompat.PRIORITY_MIN
        setCategory(NotificationCompat.CATEGORY_SERVICE)
        setDefaults(0)
        setOngoing(false)
        setOnlyAlertOnce(true)
        setSilent(true)
        addAction(
            com.google.android.material.R.drawable.material_ic_clear_black_24dp,
            applicationContext.getString(android.R.string.cancel),
            workManager.createCancelPendingIntent(id),
        )
        if (max > 0) {
            setSubText(applicationContext.getString(R.string.fraction_pattern, progress, max))
        }
        setProgress(max, progress, max == 0)
        setSmallIcon(android.R.drawable.stat_notify_sync)
        foregroundServiceBehavior = if (TAG_ONESHOT in tags) {
            NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
        } else {
            NotificationCompat.FOREGROUND_SERVICE_DEFERRED
        }
    }.build()

    private suspend fun setRetryIds(ids: Set<Long>) = runInterruptible(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            if (ids.isEmpty()) {
                remove(KEY_RETRY_IDS)
            } else {
                putStringSet(KEY_RETRY_IDS, ids.mapToSet { it.toString() })
            }
        }
    }

    private fun getRetryIds(): Set<Long> {
        val prefs = applicationContext.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_RETRY_IDS, null)?.mapToSet { it.toLong() }.orEmpty()
    }

    private fun workDataOf(success: Int, failed: Int): Data {
        return Data.Builder()
            .putInt(DATA_KEY_SUCCESS, success)
            .putInt(DATA_KEY_FAILED, failed)
            .build()
    }

    @Reusable
    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
    ) : PeriodicWorkScheduler {

        override suspend fun schedule() {
            val constraints = createConstraints()
            val request = PeriodicWorkRequestBuilder<TrackWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            workManager
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
                .await()
        }

        override suspend fun unschedule() {
            workManager
                .cancelUniqueWork(TAG)
                .await()
        }

        override suspend fun isScheduled(): Boolean {
            return workManager
                .awaitUniqueWorkInfoByName(TAG)
                .any { !it.state.isFinished }
        }

        fun startNow() {
            val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<TrackWorker>()
                .setConstraints(constraints)
                .addTag(TAG_ONESHOT)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            workManager.enqueue(request)
        }

        fun observeIsRunning(): Flow<Boolean> {
            val query = WorkQuery.Builder.fromTags(listOf(TAG, TAG_ONESHOT)).build()
            return workManager.getWorkInfosFlow(query)
                .map { works ->
                    works.any { x -> x.state == WorkInfo.State.RUNNING }
                }
        }

        private fun createConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    private companion object {

        const val WORKER_CHANNEL_ID = "track_worker"
        const val WORKER_NOTIFICATION_ID = 35
        const val TAG = "tracking"
        const val TAG_ONESHOT = "tracking_oneshot"
        const val MAX_PARALLELISM = 3
        const val MAX_ATTEMPTS = 3
        const val DATA_KEY_SUCCESS = "success"
        const val DATA_KEY_FAILED = "failed"
        const val KEY_RETRY_IDS = "retry"
    }
}