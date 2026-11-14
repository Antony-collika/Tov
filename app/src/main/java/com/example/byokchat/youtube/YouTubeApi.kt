package com.example.byokchat.youtube

import com.example.byokchat.ai.Http
import com.example.byokchat.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * YouTube Data API v3 client (YouTube tools, 2026-11-13).
 * Search and comments only — the official API. Subtitles, audio/video
 * streams and downloads go through NewPipeExtractor instead (see
 * YouTubeExtractor), because the API does not give them.
 *
 * Quota (Google docs, 2026-11-13): search.list costs 1 unit in a separate
 * bucket of 100 calls/day; every other endpoint costs 1 unit in the shared
 * 10,000 units/day bucket. Enrichment (videos.list / channels.list /
 * playlists.list) costs 1 unit per batch of up to 50 IDs, inside the same
 * single tool call — the AI calls search once, the app does the rest.
 * No internal pagination loop: more results only when the user asks, via
 * page_token, one API call per extra page.
 */

// ---------- Results ----------

data class SearchVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelTitle: String,
    val description: String,
    val publishedAt: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long?,
    val likeCount: Long?,
    val commentCount: Long?,
)

data class SearchChannel(
    val channelId: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val subscriberCount: Long?,
    val videoCount: Long?,
    val viewCount: Long?,
)

data class SearchPlaylist(
    val playlistId: String,
    val title: String,
    val channelTitle: String,
    val description: String,
    val thumbnailUrl: String?,
    val itemCount: Long?,
)

sealed interface SearchItem {
    data class Video(val video: SearchVideo) : SearchItem
    data class Channel(val channel: SearchChannel) : SearchItem
    data class Playlist(val playlist: SearchPlaylist) : SearchItem
}

data class SearchResults(val items: List<SearchItem>, val nextPageToken: String?)

data class Comment(val author: String, val text: String, val likeCount: Long?, val publishedAt: String)
data class CommentThread(val top: Comment, val replies: List<Comment>, val replyCount: Int)
data class CommentsResult(val threads: List<CommentThread>, val totalResults: Int?, val nextPageToken: String?)

// ---------- Pure JSON parsing (testable without network) ----------

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.longStr(key: String): Long? = str(key)?.toLongOrNull()

private fun thumbnailUrl(snippet: JsonObject): String? {
    val thumbs = snippet.obj("thumbnails") ?: return null
    return thumbs.obj("medium")?.str("url") ?: thumbs.obj("default")?.str("url") ?: thumbs.obj("high")?.str("url")
}

fun parseVideosList(root: JsonObject): List<SearchVideo> =
    root["items"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val id = o.str("id") ?: return@mapNotNull null
        val snippet = o.obj("snippet") ?: return@mapNotNull null
        val details = o.obj("contentDetails")
        val stats = o.obj("statistics")
        SearchVideo(
            videoId = id,
            title = snippet.str("title").orEmpty(),
            channelId = snippet.str("channelId").orEmpty(),
            channelTitle = snippet.str("channelTitle").orEmpty(),
            description = snippet.str("description").orEmpty(),
            publishedAt = snippet.str("publishedAt").orEmpty(),
            thumbnailUrl = thumbnailUrl(snippet),
            durationSeconds = parseIso8601Duration(details?.str("duration")),
            viewCount = stats?.longStr("viewCount"),
            likeCount = stats?.longStr("likeCount"),
            commentCount = stats?.longStr("commentCount"),
        )
    } ?: emptyList()

fun parseChannelsList(root: JsonObject): List<SearchChannel> =
    root["items"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val id = o.str("id") ?: return@mapNotNull null
        val snippet = o.obj("snippet") ?: return@mapNotNull null
        val stats = o.obj("statistics")
        SearchChannel(
            channelId = id,
            title = snippet.str("title").orEmpty(),
            description = snippet.str("description").orEmpty(),
            thumbnailUrl = thumbnailUrl(snippet),
            subscriberCount = stats?.longStr("subscriberCount"),
            videoCount = stats?.longStr("videoCount"),
            viewCount = stats?.longStr("viewCount"),
        )
    } ?: emptyList()

fun parsePlaylistsList(root: JsonObject): List<SearchPlaylist> =
    root["items"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val id = o.str("id") ?: return@mapNotNull null
        val snippet = o.obj("snippet") ?: return@mapNotNull null
        val details = o.obj("contentDetails")
        SearchPlaylist(
            playlistId = id,
            title = snippet.str("title").orEmpty(),
            channelTitle = snippet.str("channelTitle").orEmpty(),
            description = snippet.str("description").orEmpty(),
            thumbnailUrl = thumbnailUrl(snippet),
            itemCount = details?.longStr("itemCount"),
        )
    } ?: emptyList()

/** search.list response -> bare items (no enrichment yet) + next page token. */
fun parseSearchList(root: JsonObject): SearchResults {
    val items = root["items"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val idObj = o.obj("id") ?: return@mapNotNull null
        val snippet = o.obj("snippet") ?: return@mapNotNull null
        when (idObj.str("kind")) {
            "youtube#video" -> SearchItem.Video(
                SearchVideo(
                    videoId = idObj.str("videoId").orEmpty(),
                    title = snippet.str("title").orEmpty(),
                    channelId = snippet.str("channelId").orEmpty(),
                    channelTitle = snippet.str("channelTitle").orEmpty(),
                    description = snippet.str("description").orEmpty(),
                    publishedAt = snippet.str("publishedAt").orEmpty(),
                    thumbnailUrl = thumbnailUrl(snippet),
                    durationSeconds = 0, viewCount = null, likeCount = null, commentCount = null,
                ),
            )
            "youtube#channel" -> SearchItem.Channel(
                SearchChannel(
                    channelId = idObj.str("channelId").orEmpty(),
                    title = snippet.str("title").orEmpty(),
                    description = snippet.str("description").orEmpty(),
                    thumbnailUrl = thumbnailUrl(snippet),
                    subscriberCount = null, videoCount = null, viewCount = null,
                ),
            )
            "youtube#playlist" -> SearchItem.Playlist(
                SearchPlaylist(
                    playlistId = idObj.str("playlistId").orEmpty(),
                    title = snippet.str("title").orEmpty(),
                    channelTitle = snippet.str("channelTitle").orEmpty(),
                    description = snippet.str("description").orEmpty(),
                    thumbnailUrl = thumbnailUrl(snippet),
                    itemCount = null,
                ),
            )
            else -> null
        }
    } ?: emptyList()
    return SearchResults(items, root.str("nextPageToken"))
}

fun parseCommentThreads(root: JsonObject): CommentsResult {
    fun commentOf(o: JsonObject): Comment = Comment(
        author = o.str("authorDisplayName").orEmpty(),
        text = o.str("textDisplay").orEmpty(),
        likeCount = o.longStr("likeCount"),
        publishedAt = o.str("publishedAt").orEmpty(),
    )
    val threads = root["items"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val snippet = o.obj("snippet") ?: return@mapNotNull null
        val top = snippet.obj("topLevelComment")?.obj("snippet")?.let { commentOf(it) }
            ?: return@mapNotNull null
        val replies = snippet.obj("replies")?.get("comments")?.jsonArray?.mapNotNull { r ->
            r.jsonObject.obj("snippet")?.let { commentOf(it) }
        } ?: emptyList()
        CommentThread(top, replies, snippet["totalReplyCount"]?.jsonPrimitive?.intOrNull ?: 0)
    } ?: emptyList()
    val total = root.obj("pageInfo")?.let { it["totalResults"]?.jsonPrimitive?.intOrNull }
    return CommentsResult(threads, total, root.str("nextPageToken"))
}

/** Error reason inside a Google API error body, e.g. "quotaExceeded", "commentsDisabled". */
fun apiErrorReason(body: String): String? = runCatching {
    json.parseToJsonElement(body).jsonObject
        .obj("error")?.get("errors")?.jsonArray?.firstOrNull()
        ?.jsonObject?.str("reason")
}.getOrNull()

fun apiErrorMessage(body: String): String? = runCatching {
    json.parseToJsonElement(body).jsonObject.obj("error")?.str("message")
}.getOrNull()

// ---------- Model-facing text ----------

fun formatCount(n: Long?): String = n?.let { "%,d".format(it) } ?: "—"

fun formatSearchResults(results: SearchResults): String {
    if (results.items.isEmpty()) return "No results found."
    val lines = mutableListOf<String>()
    results.items.forEachIndexed { i, item ->
        when (item) {
            is SearchItem.Video -> {
                val v = item.video
                lines += "${i + 1}. [video] ${v.title}\n" +
                    "   https://www.youtube.com/watch?v=${v.videoId}\n" +
                    "   Channel: ${v.channelTitle} | Published: ${v.publishedAt.substringBefore('T')} | " +
                    "Duration: ${formatDuration(v.durationSeconds)} | Views: ${formatCount(v.viewCount)} | " +
                    "Likes: ${formatCount(v.likeCount)} | Comments: ${formatCount(v.commentCount)}\n" +
                    "   ${v.description.replace("\n", " ").take(300)}"
            }
            is SearchItem.Channel -> {
                val c = item.channel
                lines += "${i + 1}. [channel] ${c.title}\n" +
                    "   https://www.youtube.com/channel/${c.channelId}\n" +
                    "   Subscribers: ${formatCount(c.subscriberCount)} | Videos: ${formatCount(c.videoCount)} | " +
                    "Views: ${formatCount(c.viewCount)}\n" +
                    "   ${c.description.replace("\n", " ").take(300)}"
            }
            is SearchItem.Playlist -> {
                val p = item.playlist
                lines += "${i + 1}. [playlist] ${p.title}\n" +
                    "   https://www.youtube.com/playlist?list=${p.playlistId}\n" +
                    "   Channel: ${p.channelTitle} | Videos: ${formatCount(p.itemCount)}\n" +
                    "   ${p.description.replace("\n", " ").take(300)}"
            }
        }
    }
    if (results.nextPageToken != null) {
        lines += "There are more results. Only fetch the next page if the user asks for more (page_token: ${results.nextPageToken})."
    }
    return lines.joinToString("\n")
}

fun formatComments(result: CommentsResult): String {
    if (result.threads.isEmpty()) return "No comments found for this video."
    val lines = mutableListOf<String>()
    if (result.totalResults != null) lines += "About ${formatCount(result.totalResults.toLong())} comments (showing ${result.threads.size}):"
    result.threads.forEachIndexed { i, t ->
        lines += "${i + 1}. ${t.top.author} (${t.top.publishedAt.substringBefore('T')}, ${formatCount(t.top.likeCount)} likes): ${t.top.text}"
        t.replies.forEach { r -> lines += "   ↳ ${r.author}: ${r.text}" }
        if (t.replyCount > t.replies.size) lines += "   ↳ (${t.replyCount - t.replies.size} more replies)"
    }
    if (result.nextPageToken != null) {
        lines += "There are more comments. Only fetch the next page if the user asks for more (page_token: ${result.nextPageToken})."
    }
    return lines.joinToString("\n")
}

// ---------- HTTP client ----------

class YouTubeApi(
    private val apiKey: String,
    private val http: OkHttpClient = Http.client,
) {
    private fun base(path: String): okhttp3.HttpUrl.Builder =
        "https://www.googleapis.com/youtube/v3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)

    private suspend fun get(url: okhttp3.HttpUrl, what: String): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        val (code, body) = try {
            http.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw ToolException("Could not reach YouTube: ${e.message}. Check the network and try again.")
        }
        if (code !in 200..299) throw ToolException(mapApiError(code, body, what))
        runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ToolException("YouTube returned a response that could not be read. Try again.") }
    }

    /** HTTP error -> a plain message for the AI, with a Retry hint. Never switch sources silently. */
    fun mapApiError(code: Int, body: String, what: String): String {
        val reason = apiErrorReason(body)
        val detail = apiErrorMessage(body)?.take(200).orEmpty()
        return when {
            reason == "quotaExceeded" || reason == "dailyLimitExceeded" ->
                "YouTube's daily quota for $what is used up. Wait until tomorrow, or check the quota in Google Cloud Console. Do not switch to another source; tell the user and offer to retry tomorrow."
            reason == "commentsDisabled" ->
                "This video has comments disabled, so there are no comments to show."
            reason == "forbidden" || code == 403 ->
                "YouTube refused the request (403) for $what: $detail. The API key may be wrong or the YouTube Data API may not be enabled for it. Check the key in Settings → Web search, then retry."
            code == 429 ->
                "YouTube is rate-limiting requests (429) for $what. Wait a moment and try again."
            code == 400 ->
                "YouTube rejected the request (400) for $what: $detail"
            code == 404 ->
                "YouTube could not find this $what (404). The video, channel or playlist may have been removed."
            else -> "YouTube returned error $code for $what: $detail. Try again."
        }
    }

    // ----- search -----

    suspend fun search(
        q: String,
        type: String? = null,
        channelId: String? = null,
        maxResults: Int = 20,
        order: String = "relevance",
        pageToken: String? = null,
    ): SearchResults {
        val url = base("search")
            .addQueryParameter("part", "snippet")
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 50).toString())
        // q may be empty when listing a channel's videos (channelId only).
        // order is always sent: it used to ride on the q call, so a channel
        // listing (empty q) silently lost order=date and came back in
        // relevance order instead of newest-first.
        if (q.isNotBlank()) url.addQueryParameter("q", q)
        url.addQueryParameter("order", order)
        if (!channelId.isNullOrBlank()) {
            // The API only allows channelId together with type=video.
            url.addQueryParameter("type", "video")
            url.addQueryParameter("channelId", channelId)
        } else if (!type.isNullOrBlank()) {
            url.addQueryParameter("type", type)
        }
        if (!pageToken.isNullOrBlank()) url.addQueryParameter("pageToken", pageToken)
        val bare = parseSearchList(get(url.build(), "search"))
        return enrich(bare)
    }

    /** One search.list call + enrichment in the same tool call (SubGrab pattern). */
    suspend fun enrich(bare: SearchResults): SearchResults {
        val videos = bare.items.filterIsInstance<SearchItem.Video>()
        val channels = bare.items.filterIsInstance<SearchItem.Channel>()
        val playlists = bare.items.filterIsInstance<SearchItem.Playlist>()
        val videoMap = if (videos.isNotEmpty()) {
            val url = base("videos")
                .addQueryParameter("part", "snippet,contentDetails,statistics")
                .addQueryParameter("id", videos.joinToString(",") { it.video.videoId })
            parseVideosList(get(url.build(), "video details")).associateBy { it.videoId }
        } else emptyMap()
        val channelMap = if (channels.isNotEmpty()) {
            val url = base("channels")
                .addQueryParameter("part", "snippet,statistics")
                .addQueryParameter("id", channels.joinToString(",") { it.channel.channelId })
            parseChannelsList(get(url.build(), "channel details")).associateBy { it.channelId }
        } else emptyMap()
        val playlistMap = if (playlists.isNotEmpty()) {
            val url = base("playlists")
                .addQueryParameter("part", "snippet,contentDetails")
                .addQueryParameter("id", playlists.joinToString(",") { it.playlist.playlistId })
            parsePlaylistsList(get(url.build(), "playlist details")).associateBy { it.playlistId }
        } else emptyMap()
        val enriched = bare.items.map { item ->
            when (item) {
                is SearchItem.Video -> videoMap[item.video.videoId]?.let { SearchItem.Video(it) } ?: item
                is SearchItem.Channel -> channelMap[item.channel.channelId]?.let { SearchItem.Channel(it) } ?: item
                is SearchItem.Playlist -> playlistMap[item.playlist.playlistId]?.let { SearchItem.Playlist(it) } ?: item
            }
        }
        return SearchResults(enriched, bare.nextPageToken)
    }

    /** A direct video link: one videos.list call, no search.list spent. */
    suspend fun videoById(videoId: String): SearchVideo? {
        val url = base("videos")
            .addQueryParameter("part", "snippet,contentDetails,statistics")
            .addQueryParameter("id", videoId)
        return parseVideosList(get(url.build(), "video details")).firstOrNull()
    }

    suspend fun channelById(channelId: String): SearchChannel? {
        val url = base("channels")
            .addQueryParameter("part", "snippet,statistics")
            .addQueryParameter("id", channelId)
        return parseChannelsList(get(url.build(), "channel details")).firstOrNull()
    }

    suspend fun channelByHandle(handle: String): SearchChannel? {
        val withAt = if (handle.startsWith("@")) handle else "@$handle"
        val url = base("channels")
            .addQueryParameter("part", "snippet,statistics")
            .addQueryParameter("forHandle", withAt)
        val found = parseChannelsList(get(url.build(), "channel details")).firstOrNull()
        if (found != null) return found
        // Fall back to a keyword search for the handle name.
        return search(q = handle, type = "channel", maxResults = 5).items
            .filterIsInstance<SearchItem.Channel>().firstOrNull()?.channel
    }

    suspend fun playlistById(playlistId: String): SearchPlaylist? {
        val url = base("playlists")
            .addQueryParameter("part", "snippet,contentDetails")
            .addQueryParameter("id", playlistId)
        return parsePlaylistsList(get(url.build(), "playlist details")).firstOrNull()
    }

    /** First page of a playlist's videos, enriched like search results. */
    suspend fun playlistVideos(playlistId: String, maxResults: Int = 20, pageToken: String? = null): SearchResults {
        val url = base("playlistItems")
            .addQueryParameter("part", "snippet,contentDetails")
            .addQueryParameter("playlistId", playlistId)
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 50).toString())
        if (!pageToken.isNullOrBlank()) url.addQueryParameter("pageToken", pageToken)
        val root = get(url.build(), "playlist videos")
        val videoIds = root["items"]?.jsonArray?.mapNotNull { el ->
            el.jsonObject.obj("contentDetails")?.str("videoId")
        } ?: emptyList()
        val next = root.str("nextPageToken")
        if (videoIds.isEmpty()) return SearchResults(emptyList(), next)
        val videosUrl = base("videos")
            .addQueryParameter("part", "snippet,contentDetails,statistics")
            .addQueryParameter("id", videoIds.joinToString(","))
        val videos = parseVideosList(get(videosUrl.build(), "video details"))
        return SearchResults(videos.map { SearchItem.Video(it) }, next)
    }

    // ----- comments -----

    suspend fun comments(
        videoId: String,
        maxResults: Int = 20,
        order: String = "relevance",
        pageToken: String? = null,
        searchTerms: String? = null,
    ): CommentsResult {
        val url = base("commentThreads")
            .addQueryParameter("part", "snippet,replies")
            .addQueryParameter("videoId", videoId)
            .addQueryParameter("textFormat", "plainText")
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 100).toString())
            .addQueryParameter("order", order)
        if (!pageToken.isNullOrBlank()) url.addQueryParameter("pageToken", pageToken)
        if (!searchTerms.isNullOrBlank()) url.addQueryParameter("searchTerms", searchTerms)
        return parseCommentThreads(get(url.build(), "comments"))
    }
}
