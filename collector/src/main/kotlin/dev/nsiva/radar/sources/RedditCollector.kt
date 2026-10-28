package dev.nsiva.radar.sources

import dev.nsiva.radar.*
import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory
import java.util.Base64

private val log = LoggerFactory.getLogger("reddit")

/**
 * "Is there an app that..." posts.
 *
 * With REDDIT_CLIENT_ID + REDDIT_CLIENT_SECRET set (a free "script" app from reddit.com/prefs/apps)
 * it uses the official OAuth API (app-only token). Without them it tries the public JSON once and
 * stops for this run on the first 403/429, so a blocked IP doesn't burn time on every query.
 */
class RedditCollector(private val http: Http, private val creds: RedditCreds?) : Collector {
    override val name = "reddit"

    private suspend fun token(c: RedditCreds): String? {
        val basic = Base64.getEncoder().encodeToString("${c.clientId}:${c.clientSecret}".toByteArray())
        val r = http.fetch(
            "https://www.reddit.com/api/v1/access_token",
            method = "POST", form = "grant_type=client_credentials",
            headers = mapOf("Authorization" to "Basic $basic", "User-Agent" to c.userAgent),
        )
        val tok = r.body?.let { runCatching { json.parseToJsonElement(it).asObj()?.str("access_token") }.getOrNull() }
        if (tok == null) log.warn("OAuth token request failed ({}). Check REDDIT_CLIENT_ID / REDDIT_CLIENT_SECRET.", r.status)
        return tok
    }

    override suspend fun collect(cfg: RadarConfig): List<Signal> {
        val bearer = creds?.let { token(it) ?: return emptyList() }
        if (bearer == null) log.info("No Reddit OAuth credentials; trying public JSON")
        val headers = if (bearer != null) mapOf("Authorization" to "Bearer $bearer", "User-Agent" to creds!!.userAgent) else emptyMap()
        val base = if (bearer != null) "https://oauth.reddit.com" else "https://www.reddit.com"
        val suffix = if (bearer != null) "" else ".json"

        val out = mutableListOf<Signal>()
        for (sub in cfg.subreddits) for (q in cfg.redditQueries) {
            val r = http.fetch(
                "$base/r/$sub/search$suffix?q=${enc(q)}&restrict_sr=on&sort=new&t=month&limit=50&raw_json=1",
                headers = headers,
            )
            if (r.status == 403 || r.status == 429) {
                log.warn("Reddit returned {}; skipping the rest of Reddit this run (set up OAuth, see README)", r.status)
                return out
            }
            val children = r.body?.let {
                runCatching { json.parseToJsonElement(it).asObj()?.get("data").asObj()?.get("children") as? JsonArray }.getOrNull()
            } ?: continue
            children.mapNotNullTo(out) { c ->
                val d = c.asObj()?.get("data").asObj() ?: return@mapNotNullTo null
                Signal(
                    "reddit", "GLOBAL", "r/$sub $q", d.str("title") ?: return@mapNotNullTo null,
                    text = d.str("selftext")?.take(800), url = "https://www.reddit.com${d.str("permalink").orEmpty()}",
                    metrics = mapOf("upvotes" to (d.num("ups") ?: 0.0), "comments" to (d.num("num_comments") ?: 0.0)),
                    key = d.str("name"),
                )
            }
        }
        return out
    }
}
