package org.xtimms.shirizu.core.scrobbling.domain

import androidx.annotation.FloatRange
import androidx.collection.LongSparseArray
import androidx.collection.getOrElse
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.xtimms.shirizu.core.database.ShirizuDatabase
import org.xtimms.shirizu.core.model.findById
import org.xtimms.shirizu.core.parser.MangaRepository
import org.xtimms.shirizu.core.scrobbling.data.ScrobblerRepository
import org.xtimms.shirizu.core.scrobbling.data.ScrobblingEntity
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblerManga
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblerMangaInfo
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblerService
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblerUser
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblingInfo
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblingStatus
import org.xtimms.shirizu.utils.lang.findKeyByValue
import org.xtimms.shirizu.utils.lang.sanitize
import java.util.EnumMap

abstract class Scrobbler(
    protected val db: ShirizuDatabase,
    val scrobblerService: ScrobblerService,
    private val repository: ScrobblerRepository,
    private val mangaRepositoryFactory: MangaRepository.Factory,
) {

    private val infoCache = LongSparseArray<ScrobblerMangaInfo>()
    protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

    val user: Flow<ScrobblerUser> = flow {
        repository.cachedUser?.let {
            emit(it)
        }
        runCatchingCancellable {
            repository.loadUser()
        }.onSuccess {
            emit(it)
        }.onFailure {
            it.printStackTrace()
        }
    }

    val isAvailable: Boolean
        get() = repository.isAuthorized

    suspend fun authorize(authCode: String): ScrobblerUser {
        repository.authorize(authCode)
        return repository.loadUser()
    }

    fun logout() {
        repository.logout()
    }

    suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
        return repository.findManga(query, offset)
    }

    suspend fun linkManga(mangaId: Long, targetId: Long) {
        repository.createRate(mangaId, targetId)
    }

    suspend fun scrobble(manga: Manga, chapterId: Long) {
        var chapters = manga.chapters
        if (chapters.isNullOrEmpty()) {
            chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
        }
        requireNotNull(chapters)
        val chapter = checkNotNull(chapters.findById(chapterId)) {
            "Chapter $chapterId not found in this manga"
        }
        val number = if (chapter.number > 0f) {
            chapter.number.toInt()
        } else {
            chapters = chapters.filter { x -> x.branch == chapter.branch }
            chapters.indexOf(chapter) + 1
        }
        val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id) ?: return
        repository.updateRate(entity.id, entity.mangaId, number)
    }

    suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
        val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
        return entity.toScrobblingInfo()
    }

    abstract suspend fun updateScrobblingInfo(
        mangaId: Long,
        @FloatRange(from = 0.0, to = 1.0) rating: Float,
        status: ScrobblingStatus?,
        comment: String?,
    )

    fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> {
        return db.getScrobblingDao().observe(scrobblerService.id, mangaId)
            .map { it?.toScrobblingInfo() }
    }

    fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> {
        return db.getScrobblingDao().observe(scrobblerService.id)
            .map { entities ->
                coroutineScope {
                    entities.map {
                        async {
                            it.toScrobblingInfo()
                        }
                    }.awaitAll()
                }.filterNotNull()
            }
    }

    suspend fun unregisterScrobbling(mangaId: Long) {
        repository.unregister(mangaId)
    }

    protected suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
        return repository.getMangaInfo(id)
    }

    private suspend fun ScrobblingEntity.toScrobblingInfo(): ScrobblingInfo? {
        val mangaInfo = infoCache.getOrElse(targetId) {
            runCatchingCancellable {
                getMangaInfo(targetId)
            }.onFailure {
                it.printStackTrace()
            }.onSuccess {
                infoCache.put(targetId, it)
            }.getOrNull() ?: return null
        }
        return ScrobblingInfo(
            scrobbler = scrobblerService,
            mangaId = mangaId,
            targetId = targetId,
            status = statuses.findKeyByValue(status),
            chapter = chapter,
            comment = comment,
            rating = rating,
            title = mangaInfo.name,
            coverUrl = mangaInfo.cover,
            description = mangaInfo.descriptionHtml.parseAsHtml().sanitize(),
            externalUrl = mangaInfo.url,
        )
    }
}

suspend fun Scrobbler.tryScrobble(manga: Manga, chapterId: Long): Boolean {
    return runCatchingCancellable {
        scrobble(manga, chapterId)
    }.onFailure {
        it.printStackTrace()
    }.isSuccess
}