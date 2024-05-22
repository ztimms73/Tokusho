package org.xtimms.shirizu.sections.search.global

import androidx.annotation.StringRes
import org.xtimms.shirizu.R

enum class SearchSuggestionType(
    @StringRes val titleResId: Int,
) {

    GENRES(R.string.genres),
    QUERIES_RECENT(R.string.recent_queries),
    QUERIES_SUGGEST(R.string.suggested_queries),
    MANGA(R.string.content_type_manga),
    SOURCES(R.string.remote_sources),
    AUTHORS(R.string.authors),
}