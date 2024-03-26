package org.xtimms.tokusho.core.tracker.model

import org.koitharu.kotatsu.parsers.model.Manga
import java.time.Instant

data class TrackingLogItem(
    val id: Long,
    val manga: Manga,
    val chapters: List<String>,
    val createdAt: Instant,
    val isNew: Boolean,
)