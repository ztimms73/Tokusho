package org.xtimms.etsudoku.core.database.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

class HistoryWithManga(
    @Embedded val history: HistoryEntity,
    @Relation(
        parentColumn = "manga_id",
        entityColumn = "manga_id"
    )
    val manga: MangaEntity,
    @Relation(
        parentColumn = "manga_id",
        entityColumn = "tag_id",
        associateBy = Junction(MangaTagsEntity::class)
    )
    val tags: List<TagEntity>,
)