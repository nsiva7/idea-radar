package dev.nsiva.radar.sources

import dev.nsiva.radar.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Daily trending searches per region (Google Trends RSS). Mostly "what's hot" — useful for news-driven spikes. */
class TrendsDailyCollector(private val http: Http) : Collector {
    override val name = "trends_daily"
    override suspend fun collect(cfg: RadarConfig): List<Signal> = cfg.regions.flatMap { r ->
        val xml = http.get("https://trends.google.com/trending/rss?geo=${r.code.uppercase()}") ?: return@flatMap emptyList()
        parseRssItems(xml).mapNotNull { item ->
            val title = item["title"] ?: return@mapNotNull null
            Signal(
                source = name, region = r.code, seed = null, title = title,
                text = item["ht:news_item_title"],
                url = "https://trends.google.com/trends/explore?geo=${r.code}&q=${enc(title)}",
                metrics = mapOf("approxTraffic" to parseTraffic(item["ht:approx_traffic"])),
                key = "$title|${item["pubDate"]?.take(16)}", // one row per day it trends
            )
        }
    }
}

/**
 * Google search autocomplete for each seed. These are real queries people type,
 * ranked by popularity — the best cheap proxy for "tool intent" demand.
 */
class GoogleSuggestCollector(private val http: Http) : Collector {
    override val name = "google_suggest"
    override suspend fun collect(cfg: RadarConfig): List<Signal> = cfg.regions.flatMap { r ->
        cfg.seedKeywords.flatMap { seed ->
            val body = http.get(
                "https://suggestqueries.google.com/complete/search?client=firefox" +
                    "&hl=${r.lang}&gl=${r.play}&q=${enc(seed)}"
            ) ?: return@flatMap emptyList()
            val list = runCatching { json.parseToJsonElement(body).jsonArray[1].jsonArray }.getOrNull()
                ?: return@flatMap emptyList()
            list.mapIndexed { i, el ->
                Signal(name, r.code, seed, el.jsonPrimitive.content, metrics = mapOf("rank" to (i + 1).toDouble()))
            }
        }
    }
}

/** Google News for "new rule / deadline / scheme" style queries — regulation creates sudden, durable tool demand. */
class NewsCollector(private val http: Http) : Collector {
    override val name = "news"
    override suspend fun collect(cfg: RadarConfig): List<Signal> = cfg.regions.flatMap { r ->
        cfg.newsQueries.flatMap { q ->
            val xml = http.get(
                "https://news.google.com/rss/search?q=${enc("$q when:7d")}" +
                    "&hl=${r.newsHl}&gl=${r.code.uppercase()}&ceid=${r.ceid}"
            ) ?: return@flatMap emptyList()
            parseRssItems(xml).take(25).mapNotNull { item ->
                val title = item["title"] ?: return@mapNotNull null
                Signal(name, r.code, q, title, text = item["description"]?.stripHtml()?.take(400), url = item["link"])
            }
        }
    }
}
