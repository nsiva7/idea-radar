package dev.nsiva.radar.sources

import dev.nsiva.radar.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * App/tool requests from Stack Exchange via its free public API (no key: 300 requests/day per IP;
 * this uses one per site per run).
 *  - softwarerecs: every question is a software request, so all are kept.
 *  - other sites (e.g. android): only titles that read like a request.
 * Questions from the last 30 days. Many views + no answer = unmet demand (tagged "unanswered").
 */
class StackExchangeCollector(private val http: Http) : Collector {
    override val name = "stackexchange"

    private val requestTitle = Regex(
        """\b(is there an?|looking for an?|any|need an?|recommend\w*|suggest\w*)\b.*\b(app|apps|application|tool|program|software|launcher|widget|keyboard)\b""" +
            """|\b(app|application|tool|program)\s+(to|that|for|which)\b""",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun collect(cfg: RadarConfig): List<Signal> {
        val since = System.currentTimeMillis() / 1000 - 30 * 86_400
        return cfg.stackExchangeSites.flatMap { site ->
            val body = http.get(
                "https://api.stackexchange.com/2.3/questions?site=${enc(site)}&sort=creation&order=desc" +
                    "&fromdate=$since&pagesize=100&filter=withbody"
            ) ?: return@flatMap emptyList()
            val items = runCatching { json.parseToJsonElement(body).asObj()?.get("items") as? JsonArray }.getOrNull()
                ?: return@flatMap emptyList()
            items.mapNotNull { el ->
                val q = el.asObj() ?: return@mapNotNull null
                val title = q.str("title")?.stripHtml() ?: return@mapNotNull null
                if (site != "softwarerecs" && !requestTitle.containsMatchIn(title)) return@mapNotNull null
                val answers = q.num("answer_count") ?: 0.0
                val soTags = (q["tags"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                Signal(
                    "stackexchange", "GLOBAL", site, title,
                    text = q.str("body")?.stripHtml()?.take(600),
                    url = q.str("link"),
                    metrics = mapOf("score" to (q.num("score") ?: 0.0), "views" to (q.num("view_count") ?: 0.0), "answers" to answers),
                    tags = soTags + if (answers == 0.0) listOf("unanswered") else emptyList(),
                    key = "$site:${q.str("question_id")}",
                )
            }
        }
    }
}
