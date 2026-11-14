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
 * - Một bài chỉ xuất hiện MỘT LẦN trong danh sách: ghi lại bài đã có thì
 *   bản cũ mất, bài đó lên đầu (kể cả phát lại từ hộp thoại). Đây là
 *   luật chống crash 2026-11-14: hộp thoại khóa mỗi dòng bằng nguồn+khóa,
 *   hai mục trùng khóa là Compose ném IllegalArgumentException
 *   ("Key ... was already used") và sập app.
 * - Tối đa 50 mục mỗi cuộc trò chuyện, cũ nhất rớt ra.
 * - Lưu bền vào DB (bảng recently_played, từ v8): kill app rồi mở lại
 *   vẫn còn. Store giữ một bản sao trong bộ nhớ để UI đọc ngay; mọi lần
 *   ghi đều lưu xuống DB trước khi trả về.
 */
enum class PlayedSource {
    LOCAL, YOUTUBE,
}

/** Một bài đã phát: đủ thông tin để phát lại mà không cần link stream cũ. */
data class PlayedTrack(
    val source: PlayedSource,
    /** Khóa phát lại: đường dẫn playground (local), video ID (YouTube). */
    val key: String,
    val name: String,
)

/**
 * Bộ nhớ lâu dài cho RecentlyPlayedStore — store giữ luật (mới nhất trước,
 * chống trùng liên tiếp, tối đa 50), đây chỉ đọc/ghi nguyên danh sách.
 * Room là bản thật (xem RoomRecentlyPlayedStorage); test dùng bản fake.
 */
interface RecentlyPlayedStorage {
    suspend fun load(conversationId: String): List<PlayedTrack>
    suspend fun save(conversationId: String, tracks: List<PlayedTrack>)
}

/** Lịch sử phát lại theo từng cuộc trò chuyện, thuần Kotlin, test được trên JVM. */
class RecentlyPlayedStore(private val storage: RecentlyPlayedStorage? = null) {
    private val lock = Any()
    private val histories = mutableMapOf<String, ArrayDeque<PlayedTrack>>()
    /** Những cuộc đã nạp từ bộ nhớ lâu dài trong phiên chạy này. */
    private val loaded = mutableSetOf<String>()

    /**
     * Nạp lịch sử của một cuộc từ bộ nhớ lâu dài (chỉ lần đầu trong phiên
     * chạy; những lần sau cache trong bộ nhớ đã là mới nhất). Không có
     * storage (test không cần lưu bền) thì không làm gì.
     */
    suspend fun load(conversationId: String) {
        val s = storage ?: return
        if (synchronized(lock) { conversationId in loaded }) return
        val tracks = s.load(conversationId)
        // DB cũ có thể đã có trùng (ghi trước bản sửa chống trùng): giữ bản
        // mới nhất của mỗi bài, bản cũ bỏ. DB trả theo position tăng dần
        // (0 = mới nhất) nên distinctBy giữ đúng bản mới nhất.
        val deduped = tracks.distinctBy { it.source to it.key }.take(MAX_ENTRIES)
        synchronized(lock) {
            if (conversationId !in loaded) {
                histories[conversationId] = ArrayDeque(deduped)
                loaded.add(conversationId)
            }
        }
        // Ghi lại danh sách đã lọc xuống DB để lần sau sạch hẳn — nhưng chỉ
        // khi từ lúc nạp tới giờ chưa có bài mới nào được ghi (không thì sẽ
        // ghi đè mất lịch sử mới hơn bằng bản cũ đã lọc).
        if (deduped.size != tracks.size) {
            val stillCurrent = synchronized(lock) { histories[conversationId]?.toList() == deduped }
            if (stillCurrent) s.save(conversationId, deduped)
        }
    }

    /** Ghi một bài. Cùng bài với mục mới nhất (cùng nguồn + khóa) thì bỏ qua. */
    suspend fun record(conversationId: String, track: PlayedTrack) {
        recordAll(conversationId, listOf(track))
    }

    /**
     * Ghi cả một batch theo thứ tự phát: bài đầu batch (đang phát) lên đầu
     * danh sách, rồi tới các bài sau. Ghi ngược từ cuối batch về đầu để
     * addFirst giữ đúng thứ tự đó. Ghi lại bài đã có thì bản cũ mất, bài
     * đó lên đầu — danh sách không bao giờ có hai mục trùng (nguồn + khóa),
     * vì hộp thoại khóa mỗi dòng bằng chính cặp đó. Không có gì đổi (toàn
     * trùng liên tiếp) thì không ghi xuống DB.
     */
    suspend fun recordAll(conversationId: String, tracks: List<PlayedTrack>) {
        val snapshot = synchronized(lock) {
            val history = histories.getOrPut(conversationId) { ArrayDeque() }
            var changed = false
            for (track in tracks.asReversed()) {
                if (history.firstOrNull()?.let { it.source == track.source && it.key == track.key } == true) continue
                history.removeAll { it.source == track.source && it.key == track.key }
                history.addFirst(track)
                while (history.size > MAX_ENTRIES) history.removeLast()
                changed = true
            }
            if (!changed) return
            loaded.add(conversationId)
            history.toList()
        }
        storage?.save(conversationId, snapshot)
    }

    /** Danh sách đã phát của cuộc trò chuyện, mới nhất trước. */
    fun list(conversationId: String): List<PlayedTrack> =
        synchronized(lock) { histories[conversationId]?.toList().orEmpty() }

    companion object {
        const val MAX_ENTRIES = 50
    }
}
