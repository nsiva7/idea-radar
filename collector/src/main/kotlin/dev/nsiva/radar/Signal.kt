package dev.nsiva.radar

import java.security.MessageDigest

/**
 * One observation from one source. Re-observing the same thing later bumps seenCount,
 * which is how persistent demand shows up (vs. one-day spikes).
 */
data class Signal(
    val source: String,           // trends_daily | google_suggest | play_suggest | play_search | play_competitor | play_review_negative | reddit | news
    val region: String,           // IN | US | GLOBAL ...
    val seed: String?,            // which seed keyword / app / query produced it
    val title: String,
    val text: String? = null,
    val url: String? = null,
    val metrics: Map<String, Double> = emptyMap(),
    val tags: List<String> = emptyList(),
    val key: String? = null,      // stable identity when title isn't unique (e.g. review id)
) {
    val id: String by lazy {
        val raw = "$source|$region|${key ?: "$title|${url.orEmpty()}"}"
        MessageDigest.getInstance("SHA-256").digest(raw.lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
}

interface Collector {
    val name: String
    suspend fun collect(cfg: RadarConfig): List<Signal>
}
