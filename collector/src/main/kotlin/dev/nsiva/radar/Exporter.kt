package dev.nsiva.radar

import org.bson.Document
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.IsoFields

/**
 * Writes the last 7 days of signals as JSON for the weekly Claude Cowork analysis:
 *   exports/radar-latest.json      (always the newest)
 *   exports/radar-2026-W44.json    (weekly snapshot, overwritten during that week)
 */
class Exporter(private val store: SignalStore, private val dir: String) {
    private val log = LoggerFactory.getLogger("export")
    private val settings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

    suspend fun export(): File {
        val docs = store.recent(7)
        val counts = docs.groupingBy { "${it.getString("source")}:${it.getString("region")}" }.eachCount()

        val body = buildString {
            append("{\"generatedAt\":\"").append(OffsetDateTime.now()).append("\",")
            append("\"windowDays\":7,")
            append("\"counts\":").append(Document(counts.toSortedMap()).toJson(settings)).append(",")
            append("\"signals\":[")
            docs.forEachIndexed { i, d -> if (i > 0) append(",\n"); append(d.toJson(settings)) }
            append("]}")
        }

        val out = File(dir).apply { mkdirs() }
        val today = LocalDate.now()
        val week = "%d-W%02d".format(today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        File(out, "radar-$week.json").writeText(body)
        val latest = File(out, "radar-latest.json").apply { writeText(body) }
        log.info("Exported {} signals -> {}", docs.size, latest.absolutePath)
        return latest
    }
}
