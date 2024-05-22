package org.xtimms.shirizu.core.base.viewmodel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xtimms.shirizu.utils.lang.EventFlow
import org.xtimms.shirizu.utils.lang.MutableEventFlow
import org.xtimms.shirizu.utils.lang.call
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

abstract class BaseStateScreenModel<S>(initialState: S) : ScreenModel {

    protected val mutableState: MutableStateFlow<S> = MutableStateFlow(initialState)
    public val state: StateFlow<S> = mutableState.asStateFlow()

    @JvmField
    protected val loadingCounter = MutableStateFlow(0)

    @JvmField
    protected val errorEvent = MutableEventFlow<Throwable>()

    val onError: EventFlow<Throwable>
        get() = errorEvent

    val isLoading: StateFlow<Boolean> = loadingCounter.map { it > 0 }
        .stateIn(screenModelScope, SharingStarted.Lazily, loadingCounter.value > 0)

    protected fun launchJob(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job = screenModelScope.launch(context + createErrorHandler(), start, block)

    protected fun launchLoadingJob(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job = screenModelScope.launch(context + createErrorHandler(), start) {
        loadingCounter.increment()
        try {
            block()
        } finally {
            loadingCounter.decrement()
        }
    }

    protected fun <T> Flow<T>.withLoading() = onStart {
        loadingCounter.increment()
    }.onCompletion {
        loadingCounter.decrement()
    }

    protected suspend inline fun <T> withLoading(block: () -> T): T = try {
        loadingCounter.increment()
        block()
    } finally {
        loadingCounter.decrement()
    }

    protected fun <T> Flow<T>.withErrorHandling() = catch { error ->
        errorEvent.call(error)
    }

    protected fun MutableStateFlow<Int>.increment() = update { it + 1 }

    protected fun MutableStateFlow<Int>.decrement() = update { it - 1 }

    private fun createErrorHandler() = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            errorEvent.call(throwable)
        }
    }
}