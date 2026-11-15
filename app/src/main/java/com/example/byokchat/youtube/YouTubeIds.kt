package com.example.byokchat.youtube

/**
 * Pure YouTube link / ID helpers (YouTube tools, 2026-11-13).
 * No Android, no network — the app, not the AI, parses IDs out of links.
 * Whatever the app can do, the AI must not do.
 */

/** What a YouTube link (or bare ID) points at. */
sealed interface YouTubeLink {
    data class Video(val videoId: String) : YouTubeLink
    data class Channel(val channelId: String) : YouTubeLink
    data class ChannelHandle(val handle: String) : YouTubeLink
    data class Playlist(val playlistId: String) : YouTubeLink
    data object Unknown : YouTubeLink
}

private val VIDEO_ID_RX = Regex("^[A-Za-z0-9_-]{11}$")
private val CHANNEL_ID_RX = Regex("^UC[A-Za-z0-9_-]{22}$")

private fun queryParam(url: String, name: String): String? {
    val q = url.substringAfter('?', "").substringBefore('#')
    if (q.isEmpty()) return null
    return q.split('&').firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: it }
}

/** Video ID from a bare ID or any YouTube / youtu.be / music.youtube.com link. Null if none. */
fun parseVideoId(input: String): String? {
    val s = input.trim()
    if (VIDEO_ID_RX.matches(s)) return s
    if (!s.contains("youtu")) return null
    queryParam(s, "v")?.let { if (VIDEO_ID_RX.matches(it)) return it }
    // youtu.be/<id>, /shorts/<id>, /live/<id>, /embed/<id>, /v/<id>
    val path = s.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
    val segments = path.split('/').filter { it.isNotEmpty() }
    val host = s.substringAfter("://", "").substringBefore('/')
    if (host == "youtu.be" && segments.isNotEmpty() && VIDEO_ID_RX.matches(segments[0])) return segments[0]
    val idSeg = when {
        segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed", "v") -> segments[1]
        else -> null
    }
    if (idSeg != null && VIDEO_ID_RX.matches(idSeg)) return idSeg
    return null
}

/** Channel ID (UC…) from a /channel/UC… link. Null if the link uses a handle/name instead. */
fun parseChannelId(input: String): String? {
    val s = input.trim()
    if (CHANNEL_ID_RX.matches(s)) return s
    if (!s.contains("youtu")) return null
    val path = s.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size >= 2 && segments[0] == "channel" && CHANNEL_ID_RX.matches(segments[1])) return segments[1]
    return null
}

/** Channel handle or custom name from /@handle, /c/name or /user/name. Null if none. */
fun parseChannelHandle(input: String): String? {
    val s = input.trim()
    if (!s.contains("youtu")) return null
    val path = s.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null
    return when (segments[0]) {
        "@" -> null // "@handle" arrives as one segment below, not as "@" + name
        else -> when {
            segments[0].startsWith("@") -> segments[0].removePrefix("@")
            segments[0] in setOf("c", "user") && segments.size >= 2 -> segments[1]
            else -> null
        }
    }
}

/** Playlist ID from a link with ?list=… Null if none. */
fun parsePlaylistId(input: String): String? {
    val s = input.trim()
    val list = queryParam(s, "list") ?: return null
    return list.takeIf { it.length >= 2 }
}

/** Classify a user-supplied string: video link/ID, channel link/ID/handle, playlist link, or unknown. */
fun parseYouTubeLink(input: String): YouTubeLink {
    val s = input.trim()
    parseVideoId(s)?.let { return YouTubeLink.Video(it) }
    parsePlaylistId(s)?.let { return YouTubeLink.Playlist(it) }
    parseChannelId(s)?.let { return YouTubeLink.Channel(it) }
    parseChannelHandle(s)?.let { return YouTubeLink.ChannelHandle(it) }
    return YouTubeLink.Unknown
}

/** ISO 8601 duration from the API (PT1H2M3S, PT3M24S, PT45S, P1DT2H…) -> seconds. 0 if unparseable. */
fun parseIso8601Duration(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0
    val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(raw.trim()) ?: return 0
    val (d, h, min, sec) = m.destructured
    return (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 +
        (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
}

/** Seconds -> "3:24" or "1:02:03". */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    else "$m:${sec.toString().padStart(2, '0')}"
}

/** File name safe for a Playground file: strip characters SAF/file systems reject, cap length. */
fun sanitizeFileName(title: String, maxLength: Int = 80): String {
    val cleaned = title.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
    val cut = if (cleaned.length > maxLength) cleaned.take(maxLength).trimEnd(' ', '.') else cleaned
    return cut.ifBlank { "video" }
}
