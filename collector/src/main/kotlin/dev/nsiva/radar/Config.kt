package dev.nsiva.radar

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Region(val code: String, val lang: String = "en") {
    val play get() = code.lowercase()                 // Play country, e.g. "in"
    val newsHl get() = "$lang-${code.uppercase()}"     // Google News hl, e.g. "en-IN"
    val ceid get() = "${code.uppercase()}:$lang"
}

@Serializable
data class RadarConfig(
    val regions: List<Region>,
    val seedKeywords: List<String>,
    val competitorApps: List<String> = emptyList(),
    val subreddits: List<String> = emptyList(),
    val redditQueries: List<String> = emptyList(),
    val newsQueries: List<String> = emptyList(),
)

data class Env(
    val mongoUri: String = System.getenv("MONGO_URI") ?: "mongodb://localhost:27017",
    val playSidecar: String = System.getenv("PLAY_SIDECAR_URL") ?: "http://localhost:3000",
    val configPath: String = System.getenv("CONFIG_PATH") ?: "config/seeds.json",
    val exportDir: String = System.getenv("EXPORT_DIR") ?: "exports",
    val runEveryHours: Long = System.getenv("RUN_EVERY_HOURS")?.toLongOrNull() ?: 24,
    val requestDelayMs: Long = System.getenv("REQUEST_DELAY_MS")?.toLongOrNull() ?: 2500,
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val reddit: RedditCreds? = RedditCreds.fromEnv(),
)

/** Optional Reddit OAuth ("script" app). Both id and secret must be set to enable it. */
data class RedditCreds(val clientId: String, val clientSecret: String, val username: String?) {
    val userAgent get() = "server:idea-radar:0.2 (by /u/${username ?: "unknown"})"

    companion object {
        fun fromEnv(): RedditCreds? {
            val id = System.getenv("REDDIT_CLIENT_ID")?.takeIf { it.isNotBlank() } ?: return null
            val secret = System.getenv("REDDIT_CLIENT_SECRET")?.takeIf { it.isNotBlank() } ?: return null
            return RedditCreds(id, secret, System.getenv("REDDIT_USERNAME")?.takeIf { it.isNotBlank() })
        }
    }
}

val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

/** Re-read on every run, so you can edit seeds.json without restarting. */
fun loadConfig(path: String): RadarConfig = json.decodeFromString(File(path).readText())
