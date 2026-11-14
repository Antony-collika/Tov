package com.example.byokchat.nct

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NCT page parser tests (2026-11-14). The payloads below are built in the
 * exact Nuxt 3 encoding seen in a live fetch of
 * www.nhaccuatui.com/search?key=haru%20haru and /song/PgrxRcNASk on
 * 2026-11-14 ~10:43 +07 (flat node array, integers are references,
 * ["ShallowReactive", n] wraps the root, songs under
 * data -> search-category-result -> data -> songs, song detail under
 * data -> dataDetail:<key>), with real keys, real signed-URL shapes and
 * the real expiry e=1795229603. No live network in tests.
 */
class NctApiTest {

    /** Builds a Nuxt payload node by node, so indices are always right. */
    private class NuxtBuilder {
        val nodes = mutableListOf<JsonElement>(JsonNull) // index 0 = root wrapper, set last
        fun str(s: String): Int = nodes.size.also { nodes += JsonPrimitive(s) }
        fun num(n: Long): Int = nodes.size.also { nodes += JsonPrimitive(n) }
        fun bool(b: Boolean): Int = nodes.size.also { nodes += JsonPrimitive(b) }
        fun obj(vararg pairs: Pair<String, Int>): Int = nodes.size.also {
            nodes += JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
        }

        fun arr(vararg refs: Int): Int = nodes.size.also {
            nodes += JsonArray(refs.map { JsonPrimitive(it) })
        }

        fun root(i: Int) {
            nodes[0] = JsonArray(listOf(JsonPrimitive("ShallowReactive"), JsonPrimitive(i)))
        }

        fun build(): JsonArray = JsonArray(nodes)
    }

    private fun stream(b: NuxtBuilder, type: String, typeUi: String, onlyVip: Boolean, e: Long = 1795229603L): Int {
        val url = "https://stream.nct.vn/YG_Audio1/HaruHaru-BIGBANG-6291516.mp3" +
            "?st=0xqg09VUmsCpoBkB4VT6YA&e=$e&a=6&p=0&r=7eb234f3a87a16f7f30fb5b3d7cb2067"
        return b.obj(
            "type" to b.str(type),
            "typeUI" to b.str(typeUi),
            "stream" to b.str(url),
            "download" to b.str("$url&download=true"),
            "onlyVIP" to b.bool(onlyVip),
        )
    }

    private fun song(
        b: NuxtBuilder,
        key: String,
        name: String,
        artist: String,
        duration: Long,
        streamIdx: List<Int>,
    ): Int = b.obj(
        "key" to b.str(key),
        "name" to b.str(name),
        "artistName" to b.str(artist),
        "duration" to b.num(duration),
        "linkShare" to b.str("https://www.nhaccuatui.com/song/$key?source=app"),
        "streamURL" to b.arr(*streamIdx.toIntArray()),
    )

    private fun searchPageHtml(): String {
        val b = NuxtBuilder()
        val s1 = song(
            b, "PgrxRcNASk", "Haru Haru", "BIGBANG", 256,
            listOf(
                stream(b, "128", "128kbps", false),
                stream(b, "320", "320kbps", false),
            ),
        )
        val s2 = song(
            b, "raWH8eB8FsNw", "Haru Haru (Piano Cover)", "An Coong", 303,
            listOf(stream(b, "128", "128kbps", false)),
        )
        val s3 = song(
            b, "GFY5JjU8Ualq", "Haru Haru (Lo-Fi beat)", "BIGBANG", 185,
            listOf(
                stream(b, "320", "320kbps", true),
                stream(b, "flac", "Lossless", false),
            ),
        )
        val songsArr = b.arr(s1, s2, s3)
        val catData = b.obj("songs" to songsArr)
        val cat = b.obj("tab" to b.str("all"), "data" to catData)
        val data = b.obj("search-category-result" to cat)
        val root = b.obj("data" to data)
        b.root(root)
        return pageHtml(b.build())
    }

    private fun songPageHtml(key: String): String {
        val b = NuxtBuilder()
        val s = song(
            b, key, "Haru Haru", "BIGBANG", 256,
            listOf(
                stream(b, "128", "128kbps", false),
                stream(b, "320", "320kbps", false),
            ),
        )
        val data = b.obj("dataDetail:$key" to s)
        val root = b.obj("data" to data)
        b.root(root)
        return pageHtml(b.build())
    }

    private fun pageHtml(payload: JsonArray): String =
        "<html><body><script type=\"application/json\" data-nuxt-data=\"nuxt-app\" " +
            "data-ssr=\"true\" id=\"__NUXT_DATA__\">$payload</script></body></html>"

    @Test
    fun searchPageParsesSongsWithStreamsAndVipFlags() {
        val songs = parseSearchSongs(searchPageHtml())
        assertEquals(3, songs.size)
        val first = songs[0]
        assertEquals("PgrxRcNASk", first.key)
        assertEquals("Haru Haru", first.title)
        assertEquals("BIGBANG", first.artistName)
        assertEquals(256, first.durationSeconds)
        assertEquals("https://www.nhaccuatui.com/song/PgrxRcNASk?source=app", first.linkShare)
        assertEquals(listOf("128kbps", "320kbps"), first.qualityLabels())
        assertFalse(first.onlyVip)
        assertEquals(2, first.streams.size)
        assertEquals("128", first.streams[0].type)
        assertEquals("320", first.streams[1].type)
        assertFalse(first.streams[0].onlyVip)
        // 128-only song, and the VIP-flagged 320 stream.
        assertEquals(listOf("128kbps"), songs[1].qualityLabels())
        assertTrue(songs[2].streams[0].onlyVip)
        assertFalse(songs[2].onlyVip) // its FLAC stream is not VIP
    }

    @Test
    fun songPageParsesDetailByKey() {
        val song = parseSongPage(songPageHtml("PgrxRcNASk"), "PgrxRcNASk")
        assertEquals("Haru Haru", song?.title)
        assertEquals(2, song?.streams?.size)
        assertNull(parseSongPage(songPageHtml("PgrxRcNASk"), "OtherKey1234"))
    }

    @Test
    fun htmlWithoutPayloadParsesToNothing() {
        assertEquals(emptyList<NctSong>(), parseSearchSongs("<html><body>no data here</body></html>"))
        assertNull(parseSongPage("<html></html>", "PgrxRcNASk"))
    }

    @Test
    fun selectStreamDefaultsToHqNeverFlac() {
        val songs = parseSearchSongs(searchPageHtml())
        val withBoth = songs[0]
        assertEquals("320", selectStream(withBoth, null)?.type)
        assertEquals("320", selectStream(withBoth, "hq")?.type)
        assertEquals("128", selectStream(withBoth, "128")?.type)
        assertNull(selectStream(withBoth, "flac")) // no FLAC here: no silent downgrade
        assertNull(selectStream(withBoth, "whatever"))
        // 128-only song falls back to 128 by default.
        assertEquals("128", selectStream(songs[1], null)?.type)
        assertNull(selectStream(songs[1], "320"))
        // FLAC only when explicitly asked, and only when the song has it.
        assertEquals("flac", selectStream(songs[2], "flac")?.type)
        assertEquals("320", selectStream(songs[2], null)?.type) // default still picks 320, even VIP
    }

    @Test
    fun signedUrlExpiryIsDetected() {
        val url = "https://stream.nct.vn/YG_Audio1/HaruHaru-BIGBANG-6291516.mp3" +
            "?st=0xqg09VUmsCpoBkB4VT6YA&e=1795229603&a=6&p=0&r=7eb234f3a87a16f7f30fb5b3d7cb2067"
        assertEquals(1795229603L, signedUrlExpiry(url))
        assertFalse(isSignedUrlExpired(url, 1795229602L))
        assertTrue(isSignedUrlExpired(url, 1795229603L)) // expiry itself is already expired
        assertTrue(isSignedUrlExpired(url, 1795229604L))
        assertNull(signedUrlExpiry("https://stream.nct.vn/x.mp3"))
        assertFalse(isSignedUrlExpired("https://stream.nct.vn/x.mp3", 1795229604L))
    }

    @Test
    fun parseNctSongKeyAcceptsLinksAndBareKeys() {
        assertEquals("PgrxRcNASk", parseNctSongKey("https://www.nhaccuatui.com/song/PgrxRcNASk?source=app"))
        assertEquals("PgrxRcNASk", parseNctSongKey("PgrxRcNASk"))
        assertEquals("raWH8eB8FsNw", parseNctSongKey("raWH8eB8FsNw"))
        assertNull(parseNctSongKey("haru haru"))
        assertNull(parseNctSongKey("hello"))
        assertNull(parseNctSongKey("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun formatResultsShowKeysAndQualitiesButNeverSignedUrls() {
        val text = formatNctResults(parseSearchSongs(searchPageHtml()))
        assertTrue(text.contains("Haru Haru"))
        assertTrue(text.contains("BIGBANG"))
        assertTrue(text.contains("PgrxRcNASk"))
        assertTrue(text.contains("4:16"))
        assertTrue(text.contains("128kbps, 320kbps"))
        assertTrue(text.contains("VIP only"))
        assertFalse(text.contains("st="))
        assertFalse(text.contains("stream.nct.vn"))
        assertFalse(text.contains("a01.nct.vn"))
        assertEquals("No results found.", formatNctResults(emptyList()))
    }
}
