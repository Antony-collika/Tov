package com.example.byokchat.media

/**
 * "Recently played" (menu ⋮, dưới Search, 2026-11-14): những bài AI đã phát
 * trong một cuộc trò chuyện, để user bấm vào phát lại sau khi đã bấm X trên
 * thanh mini-player.
 *
 * Vấn đề nó giải quyết: nút X gọi MediaPlayback.stop(), và stopInternal()
 * reset toàn bộ MediaState về rỗng — hàng đợi mất luôn, không còn cách nào
 * phát lại ngoài nhờ AI gọi lại từ đầu. Lịch sử này sống RIÊNG khỏi trạng
 * thái phát nhạc hiện tại, nên bấm X không xóa nó.
 *
 * Luật:
 * - Một mục ghi: nguồn phát + khóa phát lại + tên hiển thị. KHÔNG BAO GIỜ
 *   lưu stream URL: link stream của YouTube có hạn dùng,
 *   nên phát lại phải resolve lại link mới từ khóa (xem MediaTools).
 * - Mới nhất lên đầu. Một batch ghi theo thứ tự phát (bài đang phát lên
 *   đầu, rồi tới các bài sau trong batch).
 * - Cùng một bài phát hai lần liên tiếp chỉ ghi một lần (so với mục mới
 *   nhất cùng nguồn + khóa).
 * - Tối đa 50 mục mỗi cuộc trò chuyện, cũ nhất rớt ra.
 * - Chỉ giữ trong bộ nhớ của phiên chạy app (không lưu DB): tắt app rồi
 *   mở lại thì danh sách trống. Đây là giới hạn đã biết, không phải lỗi.
 */
enum class PlayedSource {
    LOCAL, YOUTUBE, SOUNDCLOUD,
}

/** Một bài đã phát: đủ thông tin để phát lại mà không cần link stream cũ. */
data class PlayedTrack(
    val source: PlayedSource,
    /** Khóa phát lại: đường dẫn playground (local), video ID (YouTube), link track (SoundCloud). */
    val key: String,
    val name: String,
)

/** Lịch sử phát lại theo từng cuộc trò chuyện, thuần Kotlin, test được trên JVM. */
class RecentlyPlayedStore {
    private val lock = Any()
    private val histories = mutableMapOf<String, ArrayDeque<PlayedTrack>>()

    /** Ghi một bài. Cùng bài với mục mới nhất (cùng nguồn + khóa) thì bỏ qua. */
    fun record(conversationId: String, track: PlayedTrack) {
        synchronized(lock) {
            val history = histories.getOrPut(conversationId) { ArrayDeque() }
            if (history.firstOrNull()?.let { it.source == track.source && it.key == track.key } == true) return
            history.addFirst(track)
            while (history.size > MAX_ENTRIES) history.removeLast()
        }
    }

    /**
     * Ghi cả một batch theo thứ tự phát: bài đầu batch (đang phát) lên đầu
     * danh sách, rồi tới các bài sau. Ghi ngược từ cuối batch về đầu để
     * addFirst giữ đúng thứ tự đó.
     */
    fun recordAll(conversationId: String, tracks: List<PlayedTrack>) {
        for (track in tracks.asReversed()) record(conversationId, track)
    }

    /** Danh sách đã phát của cuộc trò chuyện, mới nhất trước. */
    fun list(conversationId: String): List<PlayedTrack> =
        synchronized(lock) { histories[conversationId]?.toList().orEmpty() }

    companion object {
        const val MAX_ENTRIES = 50
    }
}
