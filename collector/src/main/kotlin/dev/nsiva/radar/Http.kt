package dev.nsiva.radar

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

private val log = LoggerFactory.getLogger("http")

class Http(private val delayMs: Long) {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
        expectSuccess = false
    }
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) idea-radar/0.1 (personal research)"

    class Response(val status: Int, val body: String?)

    /** Polite GET: fixed delay before every call, never throws, null on failure. */
    suspend fun get(url: String, throttle: Boolean = true): String? = fetch(url, throttle = throttle).body

    /**
     * Like [get] but also returns the status code, and supports POST form bodies and extra headers.
     * body is null unless the status is 2xx. status is -1 on network errors.
     */
    suspend fun fetch(
        url: String,
        method: String = "GET",
        form: String? = null,
        headers: Map<String, String> = emptyMap(),
        throttle: Boolean = true,
    ): Response {
        if (throttle) delay(delayMs)
        return try {
            val r = client.request(url) {
                this.method = HttpMethod.parse(method)
                header(HttpHeaders.UserAgent, ua)
                header(HttpHeaders.AcceptLanguage, "en")
                headers.forEach { (k, v) -> this.headers[k] = v }
                if (form != null) setBody(TextContent(form, ContentType.Application.FormUrlEncoded))
            }
            if (r.status.isSuccess()) Response(r.status.value, r.bodyAsText())
            else { log.warn("{} -> {}", url.take(120), r.status.value); Response(r.status.value, null) }
        } catch (e: Exception) {
            log.warn("{} failed: {}", url.take(120), e.message); Response(-1, null)
        }
    }

    fun close() = client.close()
}

fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

/**
 * Minimal RSS parser: each <item> becomes a map of tag name -> text, including nested tags
 * (e.g. Trends' <ht:news_item><ht:news_item_title>). The first occurrence of a tag wins.
 */
fun parseRssItems(xml: String): List<Map<String, String>> = try {
    val f = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isNamespaceAware = false
    }
    val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
    val items = doc.getElementsByTagName("item")
    (0 until items.length).map { i ->
        val all = (items.item(i) as Element).getElementsByTagName("*")
        buildMap {
            for (j in 0 until all.length) {
                val n = all.item(j) as Element
                if (!containsKey(n.nodeName)) put(n.nodeName, n.textContent.trim())
            }
        }
    }
} catch (e: Exception) {
    log.warn("RSS parse failed: {}", e.message); emptyList()
}

/** "20,000+" -> 20000.0 */
fun parseTraffic(s: String?): Double = s?.filter { it.isDigit() }?.toDoubleOrNull() ?: 0.0

private val entities = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'")

/** Drops tags, decodes common HTML entities, collapses whitespace. */
fun String.stripHtml(): String {
    var s = replace(Regex("<[^>]*>"), " ")
    entities.forEach { (k, v) -> s = s.replace(k, v) }
    s = s.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
    return s.replace(Regex("\\s+"), " ").trim()
}
