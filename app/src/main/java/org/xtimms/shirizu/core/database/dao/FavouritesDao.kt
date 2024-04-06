package org.xtimms.shirizu.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.intellij.lang.annotations.Language
import org.xtimms.shirizu.core.database.entity.FavouriteEntity
import org.xtimms.shirizu.core.database.entity.MangaEntity
import org.xtimms.shirizu.core.model.ListSortOrder
import org.xtimms.shirizu.sections.shelf.FavouriteManga

@Dao
abstract class FavouritesDao {

    /** SELECT **/

    @Transaction
    @Query("SELECT * FROM favourites WHERE deleted_at = 0 GROUP BY manga_id ORDER BY created_at DESC")
    abstract suspend fun findAll(): List<FavouriteManga>

    @Transaction
    @Query("SELECT * FROM favourites WHERE deleted_at = 0 GROUP BY manga_id ORDER BY created_at DESC LIMIT :limit")
    abstract suspend fun findLast(limit: Int): List<FavouriteManga>

    fun observeAll(order: ListSortOrder): Flow<List<FavouriteManga>> {
        val orderBy = getOrderBy(order)

        @Language("RoomSql")
        val query = SimpleSQLiteQuery(
            "SELECT * FROM favourites LEFT JOIN manga ON favourites.manga_id = manga.manga_id " +
                    "WHERE favourites.deleted_at = 0 GROUP BY favourites.manga_id ORDER BY $orderBy",
        )
        return observeAllImpl(query)
    }

    @Transaction
    @Query(
        "SELECT * FROM favourites WHERE deleted_at = 0 " +
                "GROUP BY manga_id ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findAll(offset: Int, limit: Int): List<FavouriteManga>

    @Transaction
    @Query("SELECT * FROM favourites WHERE deleted_at = 0 ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun findAllRaw(offset: Int, limit: Int): List<FavouriteManga>

    @Transaction
    @Query(
        "SELECT * FROM favourites WHERE category_id = :categoryId AND deleted_at = 0 " +
                "GROUP BY manga_id ORDER BY created_at DESC",
    )
    abstract suspend fun findAll(categoryId: Long): List<FavouriteManga>

    fun observeAll(categoryId: Long, order: ListSortOrder): Flow<List<FavouriteManga>> {
        val orderBy = getOrderBy(order)

        @Language("RoomSql")
        val query = SimpleSQLiteQuery(
            "SELECT * FROM favourites LEFT JOIN manga ON favourites.manga_id = manga.manga_id " +
                    "WHERE category_id = ? AND deleted_at = 0 GROUP BY favourites.manga_id ORDER BY $orderBy",
            arrayOf<Any>(categoryId),
        )
        return observeAllImpl(query)
    }

    @Transaction
    @Query(
        "SELECT * FROM favourites WHERE category_id = :categoryId AND deleted_at = 0 " +
                "GROUP BY manga_id ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findAll(categoryId: Long, offset: Int, limit: Int): List<FavouriteManga>

    @Query(
        "SELECT * FROM manga WHERE manga_id IN " +
                "(SELECT manga_id FROM favourites WHERE category_id = :categoryId AND deleted_at = 0)",
    )
    abstract suspend fun findAllManga(categoryId: Int): List<MangaEntity>

    @Query("SELECT COUNT(DISTINCT manga_id) FROM favourites WHERE deleted_at = 0")
    abstract fun observeMangaCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT manga_id) FROM favourites WHERE deleted_at = 0 AND category_id = :categoryId")
    abstract fun observeMangaCountInCategory(categoryId: Long): Flow<Int>

    @Query("SELECT * FROM manga WHERE manga_id IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)")
    abstract suspend fun findAllManga(): List<MangaEntity>

    @Transaction
    @Query("SELECT * FROM favourites WHERE manga_id = :id AND deleted_at = 0 GROUP BY manga_id")
    abstract suspend fun find(id: Long): FavouriteManga?

    @Transaction
    @Deprecated("Ignores order")
    @Query("SELECT * FROM favourites WHERE manga_id = :id AND deleted_at = 0 GROUP BY manga_id")
    abstract fun observe(id: Long): Flow<FavouriteManga?>

    @Query("SELECT DISTINCT category_id FROM favourites WHERE manga_id = :id AND deleted_at = 0")
    abstract fun observeIds(id: Long): Flow<List<Long>>

    @Query("SELECT DISTINCT category_id FROM favourites WHERE manga_id IN (:mangaIds) AND deleted_at = 0")
    abstract suspend fun findCategoriesIds(mangaIds: Collection<Long>): List<Long>

    /** INSERT **/

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(favourite: FavouriteEntity)

    /** DELETE **/

    suspend fun delete(mangaId: Long) = setDeletedAt(
        mangaId = mangaId,
        deletedAt = System.currentTimeMillis(),
    )

    suspend fun delete(mangaId: Long, categoryId: Long) = setDeletedAt(
        categoryId = categoryId,
        mangaId = mangaId,
        deletedAt = System.currentTimeMillis(),
    )

    suspend fun deleteAll(categoryId: Long) = setDeletedAtAll(
        categoryId = categoryId,
        deletedAt = System.currentTimeMillis(),
    )

    suspend fun recover(mangaId: Long) = setDeletedAt(
        mangaId = mangaId,
        deletedAt = 0L,
    )

    suspend fun recover(categoryId: Long, mangaId: Long) = setDeletedAt(
        categoryId = categoryId,
        mangaId = mangaId,
        deletedAt = 0L,
    )

    @Query("DELETE FROM favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
    abstract suspend fun gc(maxDeletionTime: Long)

    /** TOOLS **/

    @Upsert
    abstract suspend fun upsert(entity: FavouriteEntity)

    @Transaction
    @RawQuery(observedEntities = [FavouriteEntity::class])
    protected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<FavouriteManga>>

    @Query("UPDATE favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId")
    protected abstract suspend fun setDeletedAt(mangaId: Long, deletedAt: Long)

    @Query("UPDATE favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId AND category_id = :categoryId")
    abstract suspend fun setDeletedAt(categoryId: Long, mangaId: Long, deletedAt: Long)

    @Query("UPDATE favourites SET deleted_at = :deletedAt WHERE category_id = :categoryId AND deleted_at = 0")
    protected abstract suspend fun setDeletedAtAll(categoryId: Long, deletedAt: Long)

    private fun getOrderBy(sortOrder: ListSortOrder) = when (sortOrder) {
        ListSortOrder.NEWEST -> "favourites.created_at DESC"
        ListSortOrder.ALPHABETIC -> "manga.title ASC"
        ListSortOrder.PROGRESS -> "IFNULL((SELECT percent FROM history WHERE history.manga_id = manga.manga_id), 0) DESC"

        else -> throw IllegalArgumentException("Sort order $sortOrder is not supported")
    }
}