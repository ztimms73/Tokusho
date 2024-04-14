package org.xtimms.shirizu.data.repository

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.parsers.model.Manga
import org.xtimms.shirizu.core.database.ShirizuDatabase
import org.xtimms.shirizu.core.database.entity.MangaEntity
import org.xtimms.shirizu.core.database.entity.TrackEntity
import org.xtimms.shirizu.core.database.entity.TrackLogEntity
import org.xtimms.shirizu.core.database.entity.toFavouriteCategory
import org.xtimms.shirizu.core.database.entity.toManga
import org.xtimms.shirizu.core.database.entity.toTrackingLogItem
import org.xtimms.shirizu.core.model.FavouriteCategory
import org.xtimms.shirizu.core.parser.local.LocalMangaRepository
import org.xtimms.shirizu.core.tracker.model.MangaTracking
import org.xtimms.shirizu.core.tracker.model.MangaUpdates
import org.xtimms.shirizu.core.tracker.model.TrackingLogItem
import org.xtimms.shirizu.utils.lang.ifZero
import org.xtimms.shirizu.utils.lang.mapItems
import org.xtimms.shirizu.utils.lang.toInstantOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider

private const val NO_ID = 0L

@Deprecated("Use buckets")
private const val MAX_QUERY_IDS = 100
private const val MAX_BUCKET_SIZE = 20
private const val MAX_LOG_SIZE = 120

@Reusable
class TrackingRepository @Inject constructor(
    private val db: ShirizuDatabase,
    private val localMangaRepositoryProvider: Provider<LocalMangaRepository>,
) {

    private var isGcCalled = AtomicBoolean(false)

    suspend fun getNewChaptersCount(mangaId: Long): Int {
        return db.getTracksDao().findNewChapters(mangaId) ?: 0
    }

    fun observeNewChaptersCount(mangaId: Long): Flow<Int> {
        return db.getTracksDao().observeNewChapters(mangaId).map { it ?: 0 }
    }

    fun observeUpdatedMangaCount(): Flow<Int> {
        return db.getTracksDao().observeNewChapters().map { list -> list.count { it > 0 } }
            .onStart { gcIfNotCalled() }
    }

    fun observeUpdatedManga(limit: Int = 0): Flow<List<Manga>> {
        return if (limit == 0) {
            db.getTracksDao().observeUpdatedManga()
        } else {
            db.getTracksDao().observeUpdatedManga(limit)
        }.mapItems { it.toManga() }
            .distinctUntilChanged()
            .onStart { gcIfNotCalled() }
    }

    suspend fun getCategoryId(mangaId: Long): Long {
        return db.getFavouritesDao().findCategoriesIdsWithTrack(mangaId).firstOrNull() ?: NO_ID
    }

    suspend fun getTracks(offset: Int, limit: Int): List<MangaTracking> {
        return db.getTracksDao().findAll(offset, limit).map {
            MangaTracking(
                manga = it.manga.toManga(emptySet()),
                lastChapterId = it.track.lastChapterId,
                lastCheck = it.track.lastCheckTime.toInstantOrNull(),
            )
        }
    }

    @VisibleForTesting
    suspend fun getTrack(manga: Manga): MangaTracking {
        val track = db.getTracksDao().find(manga.id)
        return MangaTracking(
            manga = manga,
            lastChapterId = track?.lastChapterId ?: NO_ID,
            lastCheck = track?.lastCheckTime?.toInstantOrNull(),
        )
    }

    @VisibleForTesting
    suspend fun deleteTrack(mangaId: Long) {
        db.getTracksDao().delete(mangaId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTrackingLog(limit: Flow<Int>): Flow<List<TrackingLogItem>> {
        return limit.flatMapLatest { limitValue ->
            combine(
                db.getTracksDao().observeNewChaptersMap(),
                db.getTrackLogsDao().observeAll(limitValue),
            ) { counters, entities ->
                val countersMap = counters.toMutableMap()
                entities.map { x -> x.toTrackingLogItem(countersMap) }
            }
        }.onStart {
            gcIfNotCalled()
        }
    }

    suspend fun getLogsCount() = db.getTrackLogsDao().count()

    suspend fun clearLogs() = db.getTrackLogsDao().clear()

    suspend fun clearCounters() = db.getTracksDao().clearCounters()

    suspend fun gc() = db.withTransaction {
        db.getTracksDao().gc()
        db.getTrackLogsDao().run {
            gc()
            trim(MAX_LOG_SIZE)
        }
    }

    suspend fun saveUpdates(updates: MangaUpdates) {
        db.withTransaction {
            val track = getOrCreateTrack(updates.manga.id).mergeWith(updates)
            db.getTracksDao().upsert(track)
            if (updates is MangaUpdates.Success && updates.isValid && updates.newChapters.isNotEmpty()) {
                updatePercent(updates)
                val logEntity = TrackLogEntity(
                    mangaId = updates.manga.id,
                    chapters = updates.newChapters.joinToString("\n") { x -> x.name },
                    createdAt = System.currentTimeMillis(),
                )
                db.getTrackLogsDao().insert(logEntity)
            }
        }
    }

    suspend fun clearUpdates(ids: Collection<Long>) {
        when {
            ids.isEmpty() -> return
            ids.size == 1 -> db.getTracksDao().clearCounter(ids.single())
            else -> db.withTransaction {
                for (id in ids) {
                    db.getTracksDao().clearCounter(id)
                }
            }
        }
    }

    suspend fun syncWithHistory(manga: Manga, chapterId: Long) {
        val chapters = manga.chapters ?: return
        val chapterIndex = chapters.indexOfFirst { x -> x.id == chapterId }
        val track = getOrCreateTrack(manga.id)
        val lastNewChapterIndex = chapters.size - track.newChapters
        val lastChapterId = chapters.lastOrNull()?.id ?: NO_ID
        val entity = TrackEntity(
            mangaId = manga.id,
            lastChapterId = lastChapterId,
            newChapters = when {
                track.newChapters == 0 -> 0
                chapterIndex < 0 -> track.newChapters
                chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
                else -> track.newChapters
            },
            lastCheckTime = System.currentTimeMillis(),
            lastChapterDate = maxOf(track.lastChapterDate, chapters.lastOrNull()?.uploadDate ?: 0L),
            lastResult = track.lastResult,
        )
        db.getTracksDao().upsert(entity)
    }

    suspend fun getCategoriesCount(): IntArray {
        val categories = db.getFavouriteCategoriesDao().findAll()
        return intArrayOf(
            categories.count { it.track },
            categories.size,
        )
    }

    suspend fun getAllFavouritesManga(): Map<FavouriteCategory, List<Manga>> {
        val categories = db.getFavouriteCategoriesDao().findAll()
        return categories.associateTo(LinkedHashMap(categories.size)) { categoryEntity ->
            categoryEntity.toFavouriteCategory() to
                    db.getFavouritesDao().findAllManga(categoryEntity.categoryId).toMangaList()
        }
    }

    suspend fun updateTracks() = db.withTransaction {
        val dao = db.getTracksDao()
        dao.gc()
        val ids = dao.findAllIds().toMutableSet()
        val size = ids.size
        // history
        val historyIds = db.getHistoryDao().findAllIds()
        for (mangaId in historyIds) {
            if (!ids.remove(mangaId)) {
                dao.upsert(TrackEntity.create(mangaId))
            }
        }

        // favourites
        val favoritesIds = db.getFavouritesDao().findIdsWithTrack()
        for (mangaId in favoritesIds) {
            if (!ids.remove(mangaId)) {
                dao.upsert(TrackEntity.create(mangaId))
            }
        }

        // remove unused
        for (mangaId in ids) {
            dao.delete(mangaId)
        }
        size - ids.size
    }

    private suspend fun getOrCreateTrack(mangaId: Long): TrackEntity {
        return db.getTracksDao().find(mangaId) ?: TrackEntity.create(mangaId)
    }

    private suspend fun updatePercent(updates: MangaUpdates.Success) {
        val history = db.getHistoryDao().find(updates.manga.id) ?: return
        val chapters = updates.manga.chapters
        if (chapters.isNullOrEmpty()) {
            return
        }
        val chapterIndex = chapters.indexOfFirst { it.id == history.chapterId }
        if (chapterIndex < 0) {
            return
        }
        val position = (chapters.size - updates.newChapters.size) * history.percent
        val newPercent = position / chapters.size.toFloat()
        db.getHistoryDao().update(history.copy(percent = newPercent))
    }

    private fun TrackEntity.mergeWith(updates: MangaUpdates): TrackEntity {
        val chapters = updates.manga.chapters.orEmpty()
        return when (updates) {
            is MangaUpdates.Failure -> TrackEntity(
                mangaId = mangaId,
                lastChapterId = lastChapterId,
                newChapters = newChapters,
                lastCheckTime = System.currentTimeMillis(),
                lastChapterDate = lastChapterDate,
                lastResult = TrackEntity.RESULT_FAILED,
            )

            is MangaUpdates.Success -> TrackEntity(
                mangaId = mangaId,
                lastChapterId = chapters.lastOrNull()?.id ?: NO_ID,
                newChapters = if (updates.isValid) newChapters + updates.newChapters.size else 0,
                lastCheckTime = System.currentTimeMillis(),
                lastChapterDate = updates.lastChapterDate().ifZero { lastChapterDate },
                lastResult = if (updates.isNotEmpty()) TrackEntity.RESULT_HAS_UPDATE else TrackEntity.RESULT_NO_UPDATE,
            )
        }
    }

    private suspend fun gcIfNotCalled() {
        if (isGcCalled.compareAndSet(false, true)) {
            gc()
        }
    }

    private fun Collection<MangaEntity>.toMangaList() = map { it.toManga(emptySet()) }
}