package org.xtimms.shirizu.core.scrobbling.services.kitsu.data

import okhttp3.Interceptor
import okhttp3.Response
import org.xtimms.shirizu.core.network.CommonHeaders
import org.xtimms.shirizu.core.scrobbling.data.ScrobblerStorage

class KitsuInterceptor(private val storage: ScrobblerStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val sourceRequest = chain.request()
        val request = sourceRequest.newBuilder()
        request.header(CommonHeaders.CONTENT_TYPE, VND_JSON)
        request.header(CommonHeaders.ACCEPT, VND_JSON)
        if (!sourceRequest.url.pathSegments.contains("oauth")) {
            storage.accessToken?.let {
                request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
            }
        }
        return chain.proceed(request.build())
    }

    companion object {

        const val VND_JSON = "application/vnd.api+json"
    }
}