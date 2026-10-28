package dev.nsiva.radar

import kotlin.test.Test
import kotlin.test.assertEquals

class ParsingTest {
    @Test fun `stripHtml decodes named, decimal and hex entities`() {
        assertEquals("a & b / c ' d", "<p>a &amp; b &#x2F; c &#39; d</p>".stripHtml())
    }

    @Test fun `rss parser reads nested tags`() {
        val xml = """<rss><channel><item><title>t</title><ht:news_item><ht:news_item_title>n</ht:news_item_title></ht:news_item></item></channel></rss>"""
        assertEquals("n", parseRssItems(xml).single()["ht:news_item_title"])
    }

    @Test fun `mongo uri url-encodes credentials`() {
        val env = mapOf("MONGO_HOST" to "mongo:27017", "MONGO_USER" to "admin@x", "MONGO_PASSWORD" to "p@ss:w/rd %\$x")
        assertEquals("mongodb://admin%40x:p%40ss%3Aw%2Frd%20%25%24x@mongo:27017/?authSource=admin", mongoUriFromEnv(env::get))
    }

    @Test fun `mongo uri prefers MONGO_URI and falls back without password`() {
        assertEquals("memory", mongoUriFromEnv(mapOf("MONGO_URI" to "memory", "MONGO_PASSWORD" to "x")::get))
        assertEquals("mongodb://localhost:27017", mongoUriFromEnv(emptyMap<String, String>()::get))
    }
}
