package org.xtimms.tokusho.core.model

import org.koitharu.kotatsu.parsers.model.MangaSource

fun MangaSource(name: String): MangaSource {
    MangaSource.entries.forEach {
        if (it.name == name) return it
    }
    return MangaSource.DUMMY
}