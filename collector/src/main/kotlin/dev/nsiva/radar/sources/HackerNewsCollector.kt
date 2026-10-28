package dev.nsiva.radar.sources

import dev.nsiva.radar.*
import kotlinx.serialization.json.JsonArray

/**
 * Hacker News "is there an app…" requests via the free Algolia HN API (no key needed).
 * Exact-phrase search over Ask HN posts, stories and comments from the last 30 days.
 * Low volume and developer-heavy, but every hit is an explicit request.
 */
class HackerNewsCollector(private val http: Http) : Collector {
    override val name = "hackernews"

    private val defaultQueries = listOf(
        "is there an app", "is there a tool", "looking for an app", "wish there was an app",
        "app recommendations", "what app do you use",
    )

    override suspend fun collect(cfg: RadarConfig): List<Signal> {
        val queries = cfg.hnQueries.ifEmpty { defaultQueries }
        val since = System.currentTimeMillis() / 1000 - 30 * 86_400
        val out = linkedMapOf<String, Signal>()
        for (q in queries) for (tags in listOf("story", "comment")) {
            val phrase = q.trim('"')
            val body = http.get(
                "https://hn.algolia.com/api/v1/search_by_date?query=${enc("\"$phrase\"")}&advancedSyntax=true" +
                    "&tags=$tags&numericFilters=${enc("created_at_i>$since")}&hitsPerPage=100"
            ) ?: continue
            val hits = runCatching { json.parseToJsonElement(body).asObj()?.get("hits") as? JsonArray }.getOrNull() ?: continue
            for (el in hits) {
                val h = el.asObj() ?: continue
                val id = h.str("objectID") ?: continue
                val isComment = tags == "comment"
                val text = (h.str(if (isComment) "comment_text" else "story_text") ?: "").stripHtml()
                // For comments, show the sentence around the phrase; the story title is just context.
                val i = text.lowercase().indexOf(phrase.lowercase())
                val snippet = if (i >= 0) text.substring(maxOf(0, i - 40)).take(160) else text.take(160)
                val title = if (isComment) snippet.take(110) else h.str("title") ?: continue
                out[id] = Signal(
                    "hackernews", "GLOBAL", q, title,
                    text = if (isComment) "On \"${h.str("story_title").orEmpty()}\": ${text.take(600)}" else text.take(600),
                    url = "https://news.ycombinator.com/item?id=$id",
                    metrics = mapOf("points" to (h.num("points") ?: 0.0), "comments" to (h.num("num_comments") ?: 0.0)),
                    tags = listOf(if (isComment) "comment" else "post"),
                    key = id,
                )
            }
        }
        return out.values.toList()
    }
}
