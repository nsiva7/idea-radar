package dev.nsiva.radar

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.flow.toList
import org.bson.Document
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Collection radar.signals — one document per unique observation:
 * { _id, source, region, seed, title, text, url, tags, metrics, firstSeen, lastSeen, seenCount }
 */
interface SignalStore {
    suspend fun init()
    suspend fun upsert(s: Signal)
    suspend fun recent(days: Int): List<Document>
    fun close()

    companion object {
        /** MONGO_URI=memory keeps everything in RAM (local testing only; lost on restart). */
        fun create(uri: String): SignalStore = if (uri == "memory") MemoryStore() else MongoStore(uri)
    }
}

private fun cutoff(days: Int) = Date(System.currentTimeMillis() - days * 86_400_000L)
private val exportOrder = compareBy<Document>({ it.getString("source") }, { it.getString("region") }, { it.getString("seed") })

class MongoStore(uri: String) : SignalStore {
    private val client = MongoClient.create(uri)
    private val col = client.getDatabase("radar").getCollection<Document>("signals")

    override suspend fun init() {
        col.createIndex(Indexes.descending("lastSeen"))
        col.createIndex(Indexes.ascending("source", "region"))
    }

    override suspend fun upsert(s: Signal) {
        val now = Date()
        col.updateOne(
            Filters.eq("_id", s.id),
            Updates.combine(
                Updates.setOnInsert("source", s.source),
                Updates.setOnInsert("region", s.region),
                Updates.setOnInsert("seed", s.seed ?: ""),
                Updates.setOnInsert("title", s.title),
                Updates.setOnInsert("text", s.text ?: ""),
                Updates.setOnInsert("url", s.url ?: ""),
                Updates.setOnInsert("firstSeen", now),
                Updates.set("tags", s.tags),
                Updates.set("metrics", Document(s.metrics)),
                Updates.set("lastSeen", now),
                Updates.inc("seenCount", 1),
            ),
            UpdateOptions().upsert(true),
        )
    }

    override suspend fun recent(days: Int): List<Document> =
        col.find(Filters.gte("lastSeen", cutoff(days)))
            .sort(Sorts.ascending("source", "region", "seed"))
            .toList()

    override fun close() = client.close()
}

class MemoryStore : SignalStore {
    private val docs = ConcurrentHashMap<String, Document>()

    override suspend fun init() {}

    override suspend fun upsert(s: Signal) {
        val now = Date()
        docs.compute(s.id) { id, old ->
            (old ?: Document("_id", id)
                .append("source", s.source).append("region", s.region).append("seed", s.seed ?: "")
                .append("title", s.title).append("text", s.text ?: "").append("url", s.url ?: "")
                .append("firstSeen", now).append("seenCount", 0))
                .append("tags", s.tags).append("metrics", Document(s.metrics)).append("lastSeen", now)
                .also { it["seenCount"] = it.getInteger("seenCount") + 1 }
        }
    }

    override suspend fun recent(days: Int): List<Document> {
        val from = cutoff(days)
        return docs.values.filter { !it.getDate("lastSeen").before(from) }.sortedWith(exportOrder)
    }

    override fun close() {}
}
