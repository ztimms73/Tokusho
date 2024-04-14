package org.xtimms.shirizu.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "stats",
    primaryKeys = ["manga_id", "started_at"],
    foreignKeys = [
        ForeignKey(
            entity = HistoryEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StatsEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "pages") val pages: Int,
)