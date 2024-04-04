package org.xtimms.etsudoku.core.network.interceptors

import android.util.Log
import dagger.Lazy
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.mergeWith
import org.xtimms.etsudoku.BuildConfig
import org.xtimms.etsudoku.core.network.CommonHeaders
import org.xtimms.etsudoku.core.parser.MangaRepository
import org.xtimms.etsudoku.core.parser.RemoteMangaRepository
import java.net.IDN
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommonHeadersInterceptor @Inject constructor(
    private val mangaRepositoryFactoryLazy: Lazy<MangaRepository.Factory>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = request.tag(MangaSource::class.java)
        val repository = if (source != null) {
            mangaRepositoryFactoryLazy.get().create(source) as? RemoteMangaRepository
        } else {
            if (BuildConfig.DEBUG) {
                Log.w("Http", "Request without source tag: ${request.url}")
            }
            null
        }
        val headersBuilder = request.headers.newBuilder()
        repository?.headers?.let {
            headersBuilder.mergeWith(it, replaceExisting = false)
        }
        if (headersBuilder[CommonHeaders.USER_AGENT] == null) {
            headersBuilder[CommonHeaders.USER_AGENT] = UserAgents.CHROME_MOBILE
        }
        if (headersBuilder[CommonHeaders.REFERER] == null && repository != null) {
            val idn = IDN.toASCII(repository.domain)
            headersBuilder.trySet(CommonHeaders.REFERER, "https://$idn/")
        }
        val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
        return repository?.intercept(ProxyChain(chain, newRequest)) ?: chain.proceed(newRequest)
    }

    private fun Headers.Builder.trySet(name: String, value: String) = try {
        set(name, value)
    } catch (e: IllegalArgumentException) {
        e.printStackTrace()
    }

    private class ProxyChain(
        private val delegate: Interceptor.Chain,
        private val request: Request,
    ) : Interceptor.Chain by delegate {

        override fun request(): Request = request
    }
}