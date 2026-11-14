package com.example.byokchat.nct

import com.example.byokchat.ai.Http
import com.example.byokchat.tools.ToolException
import com.example.byokchat.youtube.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * NhacCuaTui (NCT) client (2026-11-14).
 *
 * There is no working public NCT API: the old beta API
 * (beta.nhaccuatui.com/api, HMAC keys from 2022) is dead — beta now
 * redirects to www.nhaccuatui.com and /api/search/all returns 404.
 * Instead this client scrapes the SSR search / song pages, whose
 * __NUXT_DATA__ payload embeds per-song stream objects. The layout was
 * verified against a live fetch on 2026-11-14 (~10:43 +07):
 *
 * - GET https://www.nhaccuatui.com/search?key=<keywords> — payload path
 *   data -> search-category-result -> data -> songs[] (each song: key,
 *   name, artistName, duration in seconds, linkShare, streamURL[]).
 * - GET https://www.nhaccuatui.com/song/<key> — payload path
 *   data -> dataDetail:<key> (same song object shape).
 * - Each streamURL entry: type ("128", "320", "flac", ...), typeUI
 *   ("128kbps", "320kbps", "Lossless", ...), stream (signed URL),
 *   download (signed URL + &download=true), onlyVIP.
 * - Do NOT map quality by host: the same quality has been seen on
 *   stream.nct.vn and on a01.nct.vn, with or without a _hq suffix.
 *   The `type` field is the only reliable quality marker.
 *
 * Signed URLs: query params st=<token>&e=<unix expiry>&a=6&p=0&r=<uuid>.
 * Expiry is ~7 days from fetch. The app must fetch a FRESH signed URL at
 * play/download time (re-fetch the song page for the song key), never store
 * or reuse an old URL, and never present the raw signed URL to the model
 * as a durable link — search results carry the song key + page link only.
 *
 * Known limits, stated plainly instead of hidden behind a fallback:
 * - Scraping depends on NCT's page structure and can break if they change
 *   it; then search/play/download fail with a clear message.
 * - Some songs are VIP-only (onlyVIP): they are reported as VIP-only,
 *   never silently replaced by another song.
 * - FLAC exists only for some songs and is heavy (~34 MB/song); it is used
 *   only when the user explicitly asks for FLAC.
 */

private val json = Json { ignoreUnknownKeys = true }

/** A browser UA: the site serves its SSR page to browsers. */
private const val NCT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

data class NctStream(
    /** Quality marker from the page: "128", "320", "flac", ... */
    val type: String,
    /** Display label from the page: "128kbps", "320kbps", "Lossless", ... */
    val typeUi: String,
    /** Signed stream URL — fresh only at fetch time, expires in ~7 days. */
    val streamUrl: String,
    /** Signed download URL (stream URL + &download=true), when the page gives one. */
    val downloadUrl: String?,
    val onlyVip: Boolean,
) {
    val is128: Boolean get() = type == "128"
    val isHq: Boolean get() = type == "320"
    val isFlac: Boolean
        get() = type.equals("flac", ignoreCase = true) ||
            type.equals("lossless", ignoreCase = true) ||
            typeUi.contains("flac", ignoreCase = true) ||
            typeUi.contains("lossless", ignoreCase = true) ||
            streamUrl.contains("flac", ignoreCase = true)
}

data class NctSong(
    /** Durable reference: the key from search results, e.g. "PgrxRcNASk". */
    val key: String,
    val title: String,
    val artistName: String,
    val durationSeconds: Long,
    /** Durable page link, e.g. https://www.nhaccuatui.com/song/PgrxRcNASk */
    val linkShare: String,
    val streams: List<NctStream>,
) {
    /** True when every stream the page lists is VIP-only (nothing playable). */
    val onlyVip: Boolean get() = streams.isNotEmpty() && streams.all { it.onlyVip }

    /** Quality labels the page lists, e.g. ["128kbps", "320kbps"]. */
    fun qualityLabels(): List<String> = streams.map { it.typeUi.ifBlank { it.type } }.distinct()
}

// ---------- Pure parsing (testable without network) ----------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

/** The __NUXT_DATA__ JSON payload embedded in an NCT SSR page, or null. */
fun extractNuxtPayload(html: String): JsonElement? {
    val m = Regex(
        """<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    ).find(html) ?: return null
    return runCatching { json.parseToJsonElement(m.groupValues[1]) }.getOrNull()
}

/**
 * Resolve a Nuxt 3 payload (devalue-style: a flat node array where
 * integers inside objects/arrays are references to other nodes, and
 * ["ShallowReactive", n] / ["Ref", n] wrap a reference). Returns the
 * fully resolved tree. Unknown/cyclic nodes become JsonNull, never a crash.
 */
fun resolveNuxtPayload(payload: JsonElement): JsonElement {
    val nodes = payload.jsonArray
    val memo = mutableMapOf<Int, JsonElement>()
    fun resolve(index: Int, visiting: Set<Int>): JsonElement {
        memo[index]?.let { return it }
        if (index in visiting) return JsonNull
        val node = nodes.getOrNull(index) ?: return JsonNull
        val resolved: JsonElement = when (node) {
            is JsonObject -> JsonObject(
                node.entries.associate { (k, v) ->
                    k to if (v is JsonPrimitive && v.intOrNull != null) resolve(v.intOrNull!!, visiting + index)
                    else v
                },
            )
            is JsonArray -> {
                val first = node.firstOrNull() as? JsonPrimitive
                val tag = first?.contentOrNull
                if (tag in setOf("ShallowReactive", "Reactive", "Ref", "ShallowRef", "Computed") && node.size == 2) {
                    val ref = (node[1] as? JsonPrimitive)?.intOrNull
                    if (ref != null) resolve(ref, visiting + index) else JsonNull
                } else {
                    JsonArray(
                        node.map { el ->
                            if (el is JsonPrimitive && el.intOrNull != null) resolve(el.intOrNull!!, visiting + index)
                            else el
                        },
                    )
                }
            }
            else -> node
        }
        memo[index] = resolved
        return resolved
    }
    return resolve(0, emptySet())
}

private fun parseSongObject(o: JsonObject): NctSong? {
    val key = o.str("key") ?: return null
    val streams = (o["streamURL"] as? JsonArray)?.mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        val stream = s.str("stream") ?: return@mapNotNull null
        NctStream(
            type = s.str("type").orEmpty(),
            typeUi = s.str("typeUI").orEmpty(),
            streamUrl = stream,
            downloadUrl = s.str("download"),
            onlyVip = s.bool("onlyVIP"),
        )
    } ?: emptyList()
    return NctSong(
        key = key,
        title = o.str("name").orEmpty(),
        artistName = o.str("artistName").orEmpty(),
        durationSeconds = o.long("duration") ?: 0,
        linkShare = o.str("linkShare").orEmpty(),
        streams = streams,
    )
}

/** First "songs" array anywhere in the resolved tree, parsed as songs. */
fun findSongsInTree(node: JsonElement): List<NctSong> {
    when (node) {
        is JsonObject -> {
            val songs = node["songs"]
            if (songs is JsonArray) {
                return songs.mapNotNull { runCatching { parseSongObject(it.jsonObject) }.getOrNull() }
            }
            for (v in node.values) {
                val found = findSongsInTree(v)
                if (found.isNotEmpty()) return found
            }
        }
        is JsonArray -> {
            for (el in node) {
                val found = findSongsInTree(el)
                if (found.isNotEmpty()) return found
            }
        }
        else -> Unit
    }
    return emptyList()
}

/** The song object with this key anywhere in the resolved tree, or null. */
fun findSongInTree(node: JsonElement, key: String): NctSong? {
    when (node) {
        is JsonObject -> {
            if (node.str("key") == key && node.containsKey("streamURL")) {
                return parseSongObject(node)
            }
            for (v in node.values) {
                findSongInTree(v, key)?.let { return it }
            }
        }
        is JsonArray -> {
            for (el in node) {
                findSongInTree(el, key)?.let { return it }
            }
        }
        else -> Unit
    }
    return null
}

/** Search page HTML -> the songs it lists (empty when the page has none). */
fun parseSearchSongs(html: String): List<NctSong> {
    val payload = extractNuxtPayload(html) ?: return emptyList()
    return findSongsInTree(resolveNuxtPayload(payload))
}

/** Song page HTML -> the song with this key (null when the page has none). */
fun parseSongPage(html: String, key: String): NctSong? {
    val payload = extractNuxtPayload(html) ?: return null
    return findSongInTree(resolveNuxtPayload(payload), key)
}

/** Song key from an NCT song link (nhaccuatui.com/song/<key>) or a bare key. Null if none. */
fun parseNctSongKey(input: String): String? {
    val s = input.trim()
    val path = s.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (s.contains("nhaccuatui.com") && segments.size >= 2 && segments[0] == "song") {
        return segments[1].takeIf { it.isNotEmpty() }
    }
    // Bare key as returned by nct_search: 10-12 letters/digits (real keys seen:
    // PgrxRcNASk, raWH8eB8FsNw, GFY5JjU8Ualq). A failed lookup errors clearly.
    return s.takeIf { Regex("^[A-Za-z0-9]{10,12}$").matches(it) }
}

/** Unix expiry (seconds) of a signed NCT URL's e= param, or null when absent. */
fun signedUrlExpiry(url: String): Long? {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    return query.split('&')
        .firstOrNull { it.substringBefore('=') == "e" }
        ?.substringAfter('=', "")
        ?.toLongOrNull()
}

/** True when a signed NCT URL carries an expiry at or before now. No e= means unknown, not expired. */
fun isSignedUrlExpired(url: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
    val expiry = signedUrlExpiry(url) ?: return false
    return expiry <= nowEpochSeconds
}

/**
 * Pick the stream for a requested quality. Default (no quality given) is
 * the best stream that is NOT VIP-only: 320kbps, then 128kbps — never
 * FLAC, never a VIP-only stream, and null when every 320/128 stream is
 * VIP-only (the caller then reports VIP-only). An explicitly requested
 * quality is picked by type even when it is VIP-only (the caller then
 * reports VIP-only instead of playing it): "hq"/"best" means the best
 * available, 320 then 128; "320" means 320 only, no silent downgrade.
 * A requested quality the song does not have returns null: the caller
 * reports the available qualities instead of substituting another song.
 */
fun selectStream(song: NctSong, quality: String?): NctStream? {
    return when (quality?.trim()?.lowercase()) {
        null, "" -> {
            val free = song.streams.filter { !it.onlyVip }
            free.firstOrNull { it.isHq } ?: free.firstOrNull { it.is128 }
        }
        "hq", "best" -> song.streams.firstOrNull { it.isHq } ?: song.streams.firstOrNull { it.is128 }
        "320", "320kbps" -> song.streams.firstOrNull { it.isHq }
        "128", "128kbps" -> song.streams.firstOrNull { it.is128 }
        "flac", "lossless" -> song.streams.firstOrNull { it.isFlac }
        else -> null
    }
}

/** Model-facing search results: durable key + page link only, never a signed stream URL. */
fun formatNctResults(songs: List<NctSong>): String {
    if (songs.isEmpty()) return "No results found."
    val lines = mutableListOf<String>()
    songs.forEachIndexed { i, s ->
        val qualities = s.qualityLabels().joinToString(", ").ifBlank { "no audio stream listed" }
        val vip = when {
            s.onlyVip -> " (VIP only)"
            s.streams.any { it.onlyVip } -> " (some qualities VIP only)"
            else -> ""
        }
        lines += "${i + 1}. [song] ${s.title}\n" +
            "   ${s.linkShare.ifBlank { "https://www.nhaccuatui.com/song/${s.key}" }}\n" +
            "   Artist: ${s.artistName.ifBlank { "—" }} | Duration: ${if (s.durationSeconds > 0) formatDuration(s.durationSeconds) else "—"} | " +
            "Key: ${s.key} | Qualities: $qualities$vip"
    }
    return lines.joinToString("\n")
}

// ---------- HTTP client ----------

class NctApi(
    private val http: okhttp3.OkHttpClient = Http.fetchClient,
) {
    private suspend fun get(url: String, what: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", NCT_USER_AGENT).build()
        val (code, body) = try {
            http.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw ToolException("Could not reach NhacCuaTui: ${e.message}. Check the network and try again.")
        }
        if (code !in 200..299) {
            throw ToolException(
                "NhacCuaTui returned error $code for $what. " +
                    "The site may have changed its pages — try again, and if it keeps failing, tell the user.",
            )
        }
        body
    }

    /** Keyword search, at most [maxResults] songs. */
    suspend fun search(keywords: String, maxResults: Int = 20): List<NctSong> {
        val url = "https://www.nhaccuatui.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("key", keywords)
            .build().toString()
        val html = get(url, "search")
        if (extractNuxtPayload(html) == null) {
            throw ToolException(
                "NhacCuaTui's search page could not be read — the site may have changed its page layout. " +
                    "Try again, and if it keeps failing, tell the user.",
            )
        }
        return parseSearchSongs(html).take(maxResults.coerceIn(1, 50))
    }

    /** Fresh song detail (fresh signed stream URLs) for one song key. */
    suspend fun songByKey(key: String): NctSong {
        val html = get("https://www.nhaccuatui.com/song/$key", "song detail")
        val song = parseSongPage(html, key)
            ?: throw ToolException(
                "No song was found on NhacCuaTui with key '$key'. It may have been removed.",
            )
        if (song.streams.isEmpty()) {
            throw ToolException("Song '${song.title}' has no audio stream listed on NhacCuaTui.")
        }
        return song
    }
}
