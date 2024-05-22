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
import org.xtimms.shirizu.core.database.entity.MangaSourceEntity
import org.xtimms.shirizu.sections.explore.data.SourcesSortOrder

@Dao
abstract class MangaSourcesDao {

    @Query("SELECT * FROM sources ORDER BY sort_key")
    abstract suspend fun findAll(): List<MangaSourceEntity>

    @Query("SELECT * FROM sources WHERE enabled = 0 ORDER BY sort_key")
    abstract suspend fun findAllDisabled(): List<MangaSourceEntity>

    @Query("SELECT * FROM sources WHERE enabled = 0")
    abstract fun observeDisabled(): Flow<List<MangaSourceEntity>>

    @Query("SELECT * FROM sources ORDER BY sort_key")
    abstract fun observeAll(): Flow<List<MangaSourceEntity>>

    @Query("SELECT IFNULL(MAX(sort_key),0) FROM sources")
    abstract suspend fun getMaxSortKey(): Int

    @Query("UPDATE sources SET enabled = 0")
    abstract suspend fun disableAllSources()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    abstract suspend fun insertIfAbsent(entries: Collection<MangaSourceEntity>)

    @Upsert
    abstract suspend fun upsert(entry: MangaSourceEntity)

    fun observeEnabled(order: SourcesSortOrder): Flow<List<MangaSourceEntity>> {
        val orderBy = getOrderBy(order)

        @Language("RoomSql")
        val query = SimpleSQLiteQuery("SELECT * FROM sources WHERE enabled = 1 ORDER BY $orderBy")
        return observeImpl(query)
    }

    suspend fun findAllEnabled(order: SourcesSortOrder): List<MangaSourceEntity> {
        val orderBy = getOrderBy(order)

        @Language("RoomSql")
        val query = SimpleSQLiteQuery("SELECT * FROM sources WHERE enabled = 1 ORDER BY $orderBy")
        return findAllImpl(query)
    }

    @Transaction
    open suspend fun setEnabled(source: String, isEnabled: Boolean) {
        if (updateIsEnabled(source, isEnabled) == 0) {
            val entity = MangaSourceEntity(
                source = source,
                isEnabled = isEnabled,
                sortKey = getMaxSortKey() + 1,
            )
            upsert(entity)
        }
    }

    @Query("UPDATE sources SET enabled = :isEnabled WHERE source = :source")
    protected abstract suspend fun updateIsEnabled(source: String, isEnabled: Boolean): Int

    @RawQuery(observedEntities = [MangaSourceEntity::class])
    protected abstract fun observeImpl(query: SupportSQLiteQuery): Flow<List<MangaSourceEntity>>

    @RawQuery
    protected abstract suspend fun findAllImpl(query: SupportSQLiteQuery): List<MangaSourceEntity>

    private fun getOrderBy(order: SourcesSortOrder) = when (order) {
        SourcesSortOrder.ALPHABETIC -> "source ASC"
        SourcesSortOrder.POPULARITY -> "(SELECT COUNT(*) FROM manga WHERE source = sources.source) DESC"
        SourcesSortOrder.MANUAL -> "sort_key ASC"
    }
}