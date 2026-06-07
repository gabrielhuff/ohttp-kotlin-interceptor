package io.github.gabrielhuff.ohttp.cronet.internal

import io.github.gabrielhuff.ohttp.internal.BhttpResponse
import org.chromium.net.UrlResponseInfo
import java.util.AbstractMap

// UrlResponseInfo subclass synthesized from the BHTTP-decoded inner response.
internal class SynthesizedUrlResponseInfo(
    private val url: String,
    private val statusCode: Int,
    private val statusText: String,
    private val headersList: List<Map.Entry<String, String>>,
    private val headersMap: Map<String, List<String>>,
    private val negotiatedProtocol: String,
    @Volatile private var receivedByteCount: Long = 0L,
) : UrlResponseInfo() {
    override fun getUrl(): String = url
    override fun getUrlChain(): List<String> = listOf(url)
    override fun getHttpStatusCode(): Int = statusCode
    override fun getHttpStatusText(): String = statusText
    override fun getAllHeadersAsList(): List<Map.Entry<String, String>> = headersList
    override fun getAllHeaders(): Map<String, List<String>> = headersMap
    override fun wasCached(): Boolean = false
    override fun getNegotiatedProtocol(): String = negotiatedProtocol
    override fun getProxyServer(): String = ""
    override fun getReceivedByteCount(): Long = receivedByteCount

    fun addReceivedBytes(n: Long) { receivedByteCount += n }

    companion object {
        fun fromBhttpResponse(targetUrl: String, response: BhttpResponse): SynthesizedUrlResponseInfo {
            val list = ArrayList<Map.Entry<String, String>>(response.headers.size)
            val map = LinkedHashMap<String, MutableList<String>>()
            for ((name, value) in response.headers) {
                list.add(AbstractMap.SimpleImmutableEntry(name, value))
                map.getOrPut(name) { mutableListOf() }.add(value)
            }
            return SynthesizedUrlResponseInfo(
                url = targetUrl,
                statusCode = response.statusCode,
                statusText = reasonPhrase(response.statusCode),
                headersList = list,
                headersMap = map.mapValues { it.value.toList() },
                negotiatedProtocol = "ohttp",
            )
        }

        private fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> ""
        }
    }
}
