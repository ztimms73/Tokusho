package org.xtimms.tokusho.utils

import okhttp3.internal.closeQuietly
import okio.Closeable
import okio.Source

private class ExtraCloseableSource(
    private val delegate: Source,
    private val extraCloseable: Closeable,
) : Source by delegate {

    override fun close() {
        try {
            delegate.close()
        } finally {
            extraCloseable.closeQuietly()
        }
    }
}

fun Source.withExtraCloseable(closeable: Closeable): Source = ExtraCloseableSource(this, closeable)