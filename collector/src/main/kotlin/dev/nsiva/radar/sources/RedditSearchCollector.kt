package dev.nsiva.radar.sources

import dev.nsiva.radar.*
import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("reddit_search")

/**
 * Reddit posts found through the Brave Search API (official API, no Reddit access needed).
 * One search per redditQuery, limited to reddit.com and the past month:
 * 4 queries/day ≈ 120/month, well inside Brave's monthly free credit.
 * Enabled when BRAVE_API_KEY is set.
 */
class RedditSearchCollector(private val http: Http, private val apiKey: String?) : Collector {
    override val name = "reddit_search"

    private val postUrl = Regex("""reddit\.com/r/([A-Za-z0-9_]+)/comments/([a-z0-9]+)""")
    private val titleNoise = listOf(Regex("""\s*:\s*r/[A-Za-z0-9_]+\s*$"""), Regex("""^r/[A-Za-z0-9_]+ on Reddit:\s*"""), Regex("""\s*-\s*Reddit\s*$"""))

    override suspend fun collect(cfg: RadarConfig): List<Signal> {
        if (apiKey == null) { log.info("BRAVE_API_KEY not set; skipping"); return emptyList() }
        val out = linkedMapOf<String, Signal>()
        for (q in cfg.redditQueries) {
            val r = http.fetch(
                "https://api.search.brave.com/res/v1/web/search?q=${enc("site:reddit.com $q")}&freshness=pm&count=20",
                headers = mapOf("X-Subscription-Token" to apiKey, "Accept" to "application/json"),
            )
            if (r.status == 401 || r.status == 403 || r.status == 429) {
                log.warn("Brave returned {}; check BRAVE_API_KEY / monthly credit", r.status); break
            }
            val results = r.body?.let {
                runCatching { json.parseToJsonElement(it).asObj()?.get("web").asObj()?.get("results") as? JsonArray }.getOrNull()
            } ?: continue
            for (el in results) {
                val o = el.asObj() ?: continue
                val url = o.str("url") ?: continue
                val m = postUrl.find(url) ?: continue // skip subreddit/user pages, keep posts only
                val (sub, postId) = m.destructured
                var title = (o.str("title") ?: continue).stripHtml()
                titleNoise.forEach { title = title.replace(it, "") }
                out[postId] = Signal(
                    "reddit_search", "GLOBAL", q, title,
                    text = o.str("description")?.stripHtml()?.take(600),
                    url = "https://www.reddit.com/r/$sub/comments/$postId/",
                    tags = listOf("r/$sub"),
                    key = "t3_$postId",
                )
            }
        }
        return out.values.toList()
    }
}
