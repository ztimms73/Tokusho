package org.xtimms.shirizu.core.tracker

import androidx.annotation.VisibleForTesting
import coil.request.CachePolicy
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.xtimms.shirizu.core.model.getPreferredBranch
import org.xtimms.shirizu.core.parser.MangaRepository
import org.xtimms.shirizu.core.parser.ParserMangaRepository
import org.xtimms.shirizu.core.tracker.model.MangaTracking
import org.xtimms.shirizu.core.tracker.model.MangaUpdates
import org.xtimms.shirizu.data.repository.HistoryRepository
import org.xtimms.shirizu.data.repository.TrackingRepository
import org.xtimms.shirizu.utils.MultiMutex
import org.xtimms.shirizu.work.tracker.TrackerNotificationChannels
import org.xtimms.shirizu.work.tracker.TrackingItem
import javax.inject.Inject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class Tracker @Inject constructor(
    private val repository: TrackingRepository,
    private val historyRepository: HistoryRepository,
    private val channels: TrackerNotificationChannels,
    private val mangaRepositoryFactory: MangaRepository.Factory,
) {

    suspend fun getTracks(limit: Int): List<TrackingItem> {
        repository.updateTracks()
        return repository.getTracks(0, limit).map {
            val categoryId = repository.getCategoryId(it.manga.id)
            TrackingItem(
                tracking = it,
                channelId = if (categoryId == 0L) {
                    channels.getHistoryChannelId()
                } else {
                    channels.getFavouritesChannelId(categoryId)
                },
            )
        }
    }

    suspend fun gc() {
        repository.gc()
    }

    suspend fun fetchUpdates(
        track: MangaTracking,
        commit: Boolean
    ): MangaUpdates = withMangaLock(track.manga.id) {
        val updates = runCatchingCancellable {
            val repo = mangaRepositoryFactory.create(track.manga.source)
            require(repo is ParserMangaRepository) { "Repository ${repo.javaClass.simpleName} is not supported" }
            val manga = repo.getDetails(track.manga, CachePolicy.WRITE_ONLY)
            compare(track, manga, getBranch(manga))
        }.getOrElse { error ->
            MangaUpdates.Failure(
                manga = track.manga,
                error = error
            )
        }
        if (commit) {
            repository.saveUpdates(updates)
        }
        return updates
    }

    @VisibleForTesting
    suspend fun checkUpdates(manga: Manga, commit: Boolean): MangaUpdates.Success {
        val track = repository.getTrack(manga)
        val updates = compare(track, manga, getBranch(manga))
        if (commit) {
            repository.saveUpdates(updates)
        }
        return updates
    }

    @VisibleForTesting
    suspend fun deleteTrack(mangaId: Long) = withMangaLock(mangaId) {
        repository.deleteTrack(mangaId)
    }

    private suspend fun getBranch(manga: Manga): String? {
        val history = historyRepository.getOne(manga)
        return manga.getPreferredBranch(history)
    }

    /**
     * The main functionality of tracker: check new chapters in [manga] comparing to the [track]
     */
    private fun compare(track: MangaTracking, manga: Manga, branch: String?): MangaUpdates.Success {
        if (track.isEmpty()) {
            // first check or manga was empty on last check
            return MangaUpdates.Success(manga, emptyList(), isValid = false, channelId = null)
        }
        val chapters = requireNotNull(manga.getChapters(branch))
        val newChapters = chapters.takeLastWhile { x -> x.id != track.lastChapterId }
        return when {
            newChapters.isEmpty() -> {
                MangaUpdates.Success(
                    manga = manga,
                    newChapters = emptyList(),
                    isValid = chapters.lastOrNull()?.id == track.lastChapterId,
                    channelId = null,
                )
            }

            newChapters.size == chapters.size -> {
                MangaUpdates.Success(manga, emptyList(), isValid = false, channelId = null)
            }

            else -> {
                MangaUpdates.Success(manga, newChapters, isValid = true, channelId = null)
            }
        }
    }

    private companion object {

        private val mangaMutex = MultiMutex<Long>()

        @OptIn(ExperimentalContracts::class)
        suspend inline fun <T> withMangaLock(id: Long, action: () -> T): T {
            contract {
                callsInPlace(action, InvocationKind.EXACTLY_ONCE)
            }
            mangaMutex.lock(id)
            try {
                return action()
            } finally {
                mangaMutex.unlock(id)
            }
        }
    }
}