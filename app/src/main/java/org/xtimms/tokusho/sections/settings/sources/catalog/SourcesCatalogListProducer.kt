package org.xtimms.tokusho.sections.settings.sources.catalog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.room.InvalidationTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.parsers.model.ContentType
import org.xtimms.tokusho.R
import org.xtimms.tokusho.core.database.TABLE_SOURCES
import org.xtimms.tokusho.core.database.TokushoDatabase
import org.xtimms.tokusho.core.database.removeObserverAsync
import org.xtimms.tokusho.data.repository.MangaSourcesRepository
import org.xtimms.tokusho.utils.lang.lifecycleScope

class SourcesCatalogListProducer @AssistedInject constructor(
    @Assisted private val locale: String?,
    @Assisted private val contentType: ContentType,
    @Assisted lifecycle: ViewModelLifecycle,
    private val repository: MangaSourcesRepository,
    private val database: TokushoDatabase,
) : InvalidationTracker.Observer(TABLE_SOURCES), RetainedLifecycle.OnClearedListener {

    private val scope = lifecycle.lifecycleScope

    private var query: String? = null
    val list = MutableStateFlow(emptyList<SourceCatalogItemModel>())

    private var job = scope.launch(Dispatchers.Default) {
        list.value = buildList()
    }

    init {
        scope.launch(Dispatchers.Default) {
            database.invalidationTracker.addObserver(this@SourcesCatalogListProducer)
        }
        lifecycle.addOnClearedListener(this)
    }

    override fun onCleared() {
        database.invalidationTracker.removeObserverAsync(this)
    }

    override fun onInvalidated(tables: Set<String>) {
        val prevJob = job
        job = scope.launch(Dispatchers.Default) {
            prevJob.cancelAndJoin()
            list.update { buildList() }
        }
    }

    fun setQuery(value: String?) {
        this.query = value
        onInvalidated(emptySet())
    }

    private suspend fun buildList(): List<SourceCatalogItemModel> {
        val sources = repository.getDisabledSources().toMutableList()
        when (val q = query) {
            null -> sources.retainAll { it.contentType == contentType && it.locale == locale }
            "" -> return emptyList()
            else -> sources.retainAll { it.title.contains(q, ignoreCase = true) }
        }
        return if (sources.isEmpty()) {
            listOf(
                if (query == null) {
                    SourceCatalogItemModel.Hint(
                        icon = Icons.Outlined.SearchOff,
                        title = R.string.no_manga_sources,
                        text = R.string.no_manga_sources_catalog_text,
                    )
                } else {
                    SourceCatalogItemModel.Hint(
                        icon = Icons.Outlined.SearchOff,
                        title = R.string.nothing_found,
                        text = R.string.no_manga_sources_found,
                    )
                },
            )
        } else {
            sources.sortBy { it.title }
            sources.map {
                SourceCatalogItemModel.Source(
                    source = it,
                    showSummary = query != null,
                )
            }
        }
    }

    @AssistedFactory
    interface Factory {

        fun create(
            locale: String?,
            contentType: ContentType,
            lifecycle: ViewModelLifecycle,
        ): SourcesCatalogListProducer
    }
}