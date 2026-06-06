package io.github.gabrielhuff.ohttp.cronet.internal

import okhttp3.Response
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
        fun fromOkHttpResponse(targetUrl: String, response: Response): SynthesizedUrlResponseInfo {
            val list = ArrayList<Map.Entry<String, String>>(response.headers.size)
            val map = LinkedHashMap<String, MutableList<String>>()
            for (i in 0 until response.headers.size) {
                val name = response.headers.name(i)
                val value = response.headers.value(i)
                list.add(AbstractMap.SimpleImmutableEntry(name, value))
                map.getOrPut(name) { mutableListOf() }.add(value)
            }
            return SynthesizedUrlResponseInfo(
                url = targetUrl,
                statusCode = response.code,
                statusText = response.message,
                headersList = list,
                headersMap = map.mapValues { it.value.toList() },
                negotiatedProtocol = "ohttp",
            )
        }
    }
}
