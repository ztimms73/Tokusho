package org.xtimms.etsudoku.core.parser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import org.koitharu.kotatsu.parsers.model.Manga
import org.xtimms.etsudoku.MainActivity
import org.xtimms.etsudoku.core.model.parcelable.ParcelableManga
import org.xtimms.etsudoku.utils.lang.getParcelableCompat
import org.xtimms.etsudoku.utils.lang.getParcelableExtraCompat

class MangaIntent private constructor(
    @JvmField val manga: Manga?,
    @JvmField val id: Long,
    @JvmField val uri: Uri?,
) {

    constructor(intent: Intent?) : this(
        manga = intent?.getParcelableExtraCompat<ParcelableManga>(KEY_MANGA)?.manga,
        id = intent?.getLongExtra(KEY_ID, ID_NONE) ?: ID_NONE,
        uri = intent?.data,
    )

    constructor(savedStateHandle: SavedStateHandle) : this(
        manga = savedStateHandle.get<ParcelableManga>(KEY_MANGA)?.manga,
        id = savedStateHandle[KEY_ID] ?: ID_NONE,
        uri = savedStateHandle[MainActivity.EXTRA_DATA],
    )

    constructor(args: Bundle?) : this(
        manga = args?.getParcelableCompat<ParcelableManga>(KEY_MANGA)?.manga,
        id = args?.getLong(KEY_ID, ID_NONE) ?: ID_NONE,
        uri = null,
    )

    val mangaId: Long
        get() = if (id != ID_NONE) id else manga?.id ?: uri?.lastPathSegment?.toLongOrNull() ?: ID_NONE

    companion object {

        const val ID_NONE = 0L

        const val KEY_MANGA = "manga"
        const val KEY_ID = "id"

        fun of(manga: Manga) = MangaIntent(manga, manga.id, null)
    }
}