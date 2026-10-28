package dev.nsiva.radar

import dev.nsiva.radar.sources.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

private val log = LoggerFactory.getLogger("radar")

class Radar(private val env: Env) {
    val store = SignalStore.create(env.mongoUri)
    private val http = Http(env.requestDelayMs)
    private val exporter = Exporter(store, env.exportDir)
    private val running = Mutex()

    private val allCollectors: List<Collector> = listOf(
        TrendsDailyCollector(http),
        GoogleSuggestCollector(http),
        PlayCollector(http, env.playSidecar),
        RedditCollector(http, env.reddit),
        RedditSearchCollector(http, env.braveApiKey),
        HackerNewsCollector(http),
        StackExchangeCollector(http),
        NewsCollector(http),
    )

    /** COLLECTORS=news,reddit runs only those (handy for testing). Empty = all. */
    private val collectors = System.getenv("COLLECTORS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?.let { only -> allCollectors.filter { it.name in only } } ?: allCollectors

    val latestExport get() = File(env.exportDir, "radar-latest.json")

    // Last-run summary for GET /status
    @Volatile var lastStarted: Instant? = null; private set
    @Volatile var lastFinished: Instant? = null; private set
    @Volatile var lastCounts: Map<String, Int> = emptyMap(); private set
    @Volatile var lastError: String? = null; private set
    val isRunning get() = running.isLocked

    /** Mongo may still be starting when the collector boots (depends_on doesn't wait), so retry. */
    suspend fun initStore() {
        var wait = 5_000L
        while (true) {
            try { store.init(); return } catch (e: Exception) {
                log.warn("Store not ready ({}); retrying in {}s", e.message, wait / 1000)
                delay(wait); wait = (wait * 2).coerceAtMost(120_000)
            }
        }
    }

    /** Returns false if a run is already in progress. Never throws. */
    suspend fun runOnce(): Boolean {
        if (!running.tryLock()) return false
        try {
            lastStarted = Instant.now(); lastError = null
            val cfg = loadConfig(env.configPath)
            val counts = linkedMapOf<String, Int>()
            for (c in collectors) {
                val signals = runCatching { c.collect(cfg) }
                    .onFailure { log.error("{} crashed: {}", c.name, it.message); lastError = "${c.name}: ${it.message}" }
                    .getOrDefault(emptyList())
                var saved = 0
                signals.forEach { s -> runCatching { store.upsert(s); saved++ }.onFailure { e -> log.warn("upsert: {}", e.message) } }
                counts[c.name] = saved
                log.info("{}: {} signals ({} saved)", c.name, signals.size, saved)
            }
            exporter.export()
            lastCounts = counts
            lastFinished = Instant.now()
            log.info("Run finished in {} min", (lastFinished!!.toEpochMilli() - lastStarted!!.toEpochMilli()) / 60_000)
        } catch (e: Exception) {
            // e.g. a typo in seeds.json: log it and keep the schedule alive
            lastError = e.message
            log.error("Run failed: {}", e.message)
        } finally {
            running.unlock()
        }
        return true
    }

    /**
     * How long to wait before the first scheduled run. If the latest export is younger than the
     * interval (e.g. the container just restarted), wait out the rest instead of re-running at once,
     * so seenCount keeps meaning "days observed" rather than "restarts".
     */
    fun initialDelayMs(): Long {
        val interval = env.runEveryHours * 3_600_000
        val f = latestExport
        if (!f.exists()) return 0
        val age = System.currentTimeMillis() - f.lastModified()
        return (interval - age).coerceAtLeast(0)
    }
}

fun main(args: Array<String>) {
    val env = Env()
    val radar = Radar(env)

    // `java -jar idea-radar.jar --once`: one run, write the export, exit.
    if ("--once" in args) {
        runBlocking { radar.initStore(); radar.runOnce() }
        radar.store.close()
        return
    }
    embeddedServer(CIO, port = env.port) { module(radar, env) }.start(wait = true)
}

fun Application.module(radar: Radar, env: Env) {
    launch {
        radar.initStore()
        val first = radar.initialDelayMs()
        if (first > 0) log.info("Recent export found; next run in {} min (POST /run to run now)", first / 60_000)
        delay(first)
        while (isActive) {
            radar.runOnce()
            delay(env.runEveryHours * 3_600_000)
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        get("/status") {
            val body = buildJsonObject {
                put("running", radar.isRunning)
                put("lastStarted", radar.lastStarted?.toString())
                put("lastFinished", radar.lastFinished?.toString())
                put("lastError", radar.lastError)
                put("lastCounts", buildJsonObject { radar.lastCounts.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
                put("redditOAuth", env.reddit != null)
                put("braveSearch", env.braveApiKey != null)
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
        post("/run") {
            val busy = radar.isRunning
            launch { radar.runOnce() }
            call.respondText(
                if (busy) "a run is already in progress" else "started",
                status = if (busy) HttpStatusCode.Conflict else HttpStatusCode.Accepted,
            )
        }
        get("/export/latest") {
            val f = radar.latestExport
            if (f.exists()) call.respondFile(f) else call.respondText("no export yet", status = HttpStatusCode.NotFound)
        }
    }
}
