package dev.nsiva.radar.sources

import dev.nsiva.radar.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google Play via the sidecar:
 *  - play_suggest:   Play search autocomplete per seed
 *  - play_search:    top results per seed, tagged weak_* when the incumbents look beatable
 *  - play_competitor + play_review_negative: watched apps and their 1–2 star reviews
 */
class PlayCollector(private val http: Http, private val sidecar: String) : Collector {
    override val name = "play"
    private val dayMs = 86_400_000.0

    private suspend fun arr(path: String): JsonArray? =
        http.get("$sidecar$path", throttle = true)?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }

    override suspend fun collect(cfg: RadarConfig): List<Signal> {
        val out = mutableListOf<Signal>()
        val now = System.currentTimeMillis()

        for (r in cfg.regions) {
            val c = "country=${r.play}&lang=${r.lang}"
            for (seed in cfg.seedKeywords) {
                arr("/suggest?term=${enc(seed)}&$c")?.forEachIndexed { i, el ->
                    out += Signal("play_suggest", r.code, seed, el.jsonPrimitive.content, metrics = mapOf("rank" to i + 1.0))
                }

                val results = arr("/search?term=${enc(seed)}&num=8&$c") ?: continue
                results.forEachIndexed { i, el ->
                    val a = el.asObj() ?: return@forEachIndexed
                    val score = a.num("score") ?: 0.0
                    val updated = a.num("updated")
                    val daysOld = updated?.let { (now - it) / dayMs } ?: -1.0
                    val tags = buildList {
                        if (score in 0.1..3.99) add("weak_rating")
                        if (daysOld > 540) add("weak_stale")
                        if (a.str("adSupported") == "true") add("ads")
                        if ((a.num("ratings") ?: 0.0) < 500) add("weak_low_traction")
                    }
                    out += Signal(
                        "play_search", r.code, seed, a.str("title")?.stripHtml() ?: "?",
                        text = a.str("summary")?.stripHtml(), url = a.str("url"),
                        metrics = mapOf(
                            "rank" to i + 1.0, "score" to score, "ratings" to (a.num("ratings") ?: 0.0),
                            "minInstalls" to (a.num("minInstalls") ?: 0.0), "daysSinceUpdate" to daysOld,
                        ),
                        tags = tags, key = a.str("appId"),
                    )
                }
            }

            for (appId in cfg.competitorApps) {
                http.get("$sidecar/app?appId=${enc(appId)}&$c")?.let { body ->
                    val a = runCatching { json.parseToJsonElement(body).asObj() }.getOrNull() ?: return@let
                    out += Signal(
                        "play_competitor", r.code, appId, a.str("title") ?: appId, url = a.str("url"),
                        metrics = mapOf(
                            "score" to (a.num("score") ?: 0.0), "ratings" to (a.num("ratings") ?: 0.0),
                            "minInstalls" to (a.num("minInstalls") ?: 0.0),
                            "daysSinceUpdate" to (a.num("updated")?.let { (now - it) / dayMs } ?: -1.0),
                        ),
                        key = appId,
                    )
                }

                arr("/reviews?appId=${enc(appId)}&num=150&$c")?.forEach { el ->
                    val rv = el.asObj() ?: return@forEach
                    val stars = rv.num("score") ?: return@forEach
                    val text = rv.str("text")?.trim().orEmpty()
                    if (stars > 2 || text.length < 30) return@forEach
                    out += Signal(
                        "play_review_negative", r.code, appId, text.take(90), text = text.take(600),
                        url = "https://play.google.com/store/apps/details?id=$appId",
                        metrics = mapOf("stars" to stars, "thumbsUp" to (rv.num("thumbsUp") ?: 0.0)),
                        key = rv.str("id"),
                    )
                }
            }
        }
        return out
    }
}
