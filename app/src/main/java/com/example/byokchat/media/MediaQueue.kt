package com.example.byokchat.media

/**
 * Logic thuần của hàng đợi phát nhạc — không phụ thuộc Android nên test
 * được bằng unit test thường trên JVM.
 *
 * Luật (kế hoạch media player, Đợt 1): một lần gọi tool đưa cả batch,
 * phát tuần tự theo đúng thứ tự; hết bài tự sang bài sau; hết batch thì
 * dừng. Previous ở bài đầu thì phát lại bài đó từ đầu (controller xử lý
 * bằng seek về 0, không đổi index).
 */

/** Một bài trong hàng đợi: đường dẫn AI dùng + URI để mở + tên hiển thị. */
data class QueueTrack(
    val displayPath: String,
    val uri: String,
    val name: String,
    /**
     * Khóa để phát lại bài này từ "Recently played", không cần URI cũ:
     * đường dẫn playground (local), video ID (YouTube), link track
     * (SoundCloud). Null = bài này không ghi vào lịch sử phát
     * lại được (chỉ các tool phát nhạc mới đặt hai trường này).
     */
    val replaySource: PlayedSource? = null,
    val replayKey: String? = null,
)

class MediaQueue(val tracks: List<QueueTrack>, val index: Int = 0) {
    val current: QueueTrack? get() = tracks.getOrNull(index)
    val isEmpty: Boolean get() = tracks.isEmpty()

    /** "Bài 1/3" — vị trí hiện tại trong batch. */
    val label: String get() = if (tracks.isEmpty()) "" else "${index + 1}/${tracks.size}"

    /** Hàng đợi ở bài tiếp theo, hoặc null khi đã hết batch. */
    fun next(): MediaQueue? =
        if (index + 1 < tracks.size) MediaQueue(tracks, index + 1) else null

    /** Hàng đợi ở bài trước; đang ở bài đầu thì giữ nguyên (phát lại từ đầu). */
    fun previous(): MediaQueue =
        if (index <= 0) this else MediaQueue(tracks, index - 1)
}

/** "m:ss" — 0 -> "0:00", 61000 -> "1:01", 3723000 -> "62:03". */
fun formatMediaTime(ms: Int): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
