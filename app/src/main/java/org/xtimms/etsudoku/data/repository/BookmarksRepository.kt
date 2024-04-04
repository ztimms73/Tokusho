package org.xtimms.etsudoku.data.repository

import android.database.SQLException
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.parsers.model.Manga
import org.xtimms.etsudoku.core.database.EtsudokuDatabase
import org.xtimms.etsudoku.core.database.entity.BookmarkEntity
import org.xtimms.etsudoku.core.database.entity.toBookmark
import org.xtimms.etsudoku.core.database.entity.toBookmarks
import org.xtimms.etsudoku.core.database.entity.toEntities
import org.xtimms.etsudoku.core.database.entity.toEntity
import org.xtimms.etsudoku.core.database.entity.toManga
import org.xtimms.etsudoku.core.model.Bookmark
import org.xtimms.etsudoku.utils.ReversibleHandle
import org.xtimms.etsudoku.utils.lang.mapItems
import javax.inject.Inject

@Reusable
class BookmarksRepository @Inject constructor(
    private val db: EtsudokuDatabase,
) {

    fun observeBookmark(manga: Manga, chapterId: Long, page: Int): Flow<Bookmark?> {
        return db.getBookmarksDao().observe(manga.id, chapterId, page).map { it?.toBookmark(manga) }
    }

    fun observeBookmarks(manga: Manga): Flow<List<Bookmark>> {
        return db.getBookmarksDao().observe(manga.id).mapItems { it.toBookmark(manga) }
    }

    fun observeBookmarks(): Flow<Map<Manga, List<Bookmark>>> {
        return db.getBookmarksDao().observe().map { map ->
            val res = LinkedHashMap<Manga, List<Bookmark>>(map.size)
            for ((k, v) in map) {
                val manga = k.toManga()
                res[manga] = v.toBookmarks(manga)
            }
            res
        }
    }

    suspend fun addBookmark(bookmark: Bookmark) {
        db.withTransaction {
            val tags = bookmark.manga.tags.toEntities()
            db.getTagsDao().upsert(tags)
            db.getMangaDao().upsert(bookmark.manga.toEntity(), tags)
            db.getBookmarksDao().insert(bookmark.toEntity())
        }
    }

    suspend fun updateBookmark(bookmark: Bookmark, imageUrl: String) {
        val entity = bookmark.toEntity().copy(
            imageUrl = imageUrl,
        )
        db.getBookmarksDao().upsert(listOf(entity))
    }

    suspend fun removeBookmark(mangaId: Long, chapterId: Long, page: Int) {
        check(db.getBookmarksDao().delete(mangaId, chapterId, page) != 0) {
            "Bookmark not found"
        }
    }

    suspend fun removeBookmark(bookmark: Bookmark) {
        removeBookmark(bookmark.manga.id, bookmark.chapterId, bookmark.page)
    }

    suspend fun removeBookmarks(ids: Set<Long>): ReversibleHandle {
        val entities = ArrayList<BookmarkEntity>(ids.size)
        db.withTransaction {
            val dao = db.getBookmarksDao()
            for (pageId in ids) {
                val e = dao.find(pageId)
                if (e != null) {
                    entities.add(e)
                }
                dao.delete(pageId)
            }
        }
        return BookmarksRestorer(entities)
    }

    private inner class BookmarksRestorer(
        private val entities: Collection<BookmarkEntity>,
    ) : ReversibleHandle {

        override suspend fun reverse() {
            db.withTransaction {
                for (e in entities) {
                    try {
                        db.getBookmarksDao().insert(e)
                    } catch (e: SQLException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}