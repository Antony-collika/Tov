package com.example.byokchat.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.byokchat.chat.ModelFacingText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Lỗi mở/phát file nhạc — câu báo đã viết sẵn cho AI (xem kế hoạch Đợt 1). */
class MediaPlayException(message: String) : Exception(message)

/** Trạng thái phát nhạc — một nguồn duy nhất cho tool, service và UI. */
data class MediaState(
    val queue: List<QueueTrack> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
) {
    val current: QueueTrack? get() = queue.getOrNull(index)

    /** Thanh mini-player chỉ hiện khi đang có nhạc (đang phát hoặc dừng tạm). */
    val visible: Boolean get() = queue.isNotEmpty()

    /** "Bài 1/3" trong batch. */
    val label: String get() = if (queue.isEmpty()) "" else "${index + 1}/${queue.size}"
}

/**
 * Điều khiển phát nhạc của app (một instance duy nhất, giữ trong AppContainer).
 * Giữ MediaPlayer + hàng đợi + vị trí phát; tool gọi play(), thanh mini-player
 * và thông báo đọc [state]; service (Đợt 2) giữ cho nhạc chạy nền.
 *
 * Mọi thao tác với MediaPlayer chạy trên luồng chính (scope Main.immediate):
 * callback của player (hết bài, lỗi) về đúng luồng đó. Tool gọi play() từ
 * luồng IO vẫn được — hàm tự chuyển luồng.
 */
class MediaPlayback(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val playMutex = Mutex()

    /** Tăng mỗi lần dừng/xóa hàng đợi — để lần prepare đang chờ không "hồi sinh" nhạc sau khi đã dừng. */
    private var generation = 0

    /**
     * Phát một batch mới, thay hàng đợi cũ. Trả về thời lượng (ms) của bài đầu.
     * Ném [MediaPlayException] khi không mở được bài đầu.
     */
    suspend fun play(tracks: List<QueueTrack>): Int {
        require(tracks.isNotEmpty())
        return playMutex.withLock {
            try {
                withContext(Dispatchers.Main.immediate) {
                    stopInternal()
                    _state.value = MediaState(queue = tracks, index = 0)
                    if (!startTrack(0)) {
                        stopInternal()
                        throw MediaPlayException(ModelFacingText.mediaPlayFailed(tracks[0].name))
                    }
                    _state.value.durationMs
                }
            } catch (e: CancellationException) {
                // Lượt chat bị hủy giữa lúc prepare: dọn trạng thái để thanh
                // không kẹt lại với hàng đợi không có player.
                withContext(NonCancellable + Dispatchers.Main.immediate) { stopInternal() }
                throw e
            }
        }
    }

    fun pause() = scope.launch { pauseInternal() }
    fun resume() = scope.launch { resumeInternal() }
    fun toggle() = scope.launch { if (_state.value.playing) pauseInternal() else resumeInternal() }

    /** Sang bài sau; đang ở bài cuối thì dừng (hết batch). */
    fun next() = scope.launch { advanceFrom(_state.value.index) }

    /** Về bài trước; đang ở bài đầu thì phát lại bài đó từ đầu. */
    fun previous() = scope.launch {
        val s = _state.value
        if (s.queue.isEmpty()) return@launch
        if (s.index == 0) seekToInternal(0) else backFrom(s.index)
    }

    fun seekTo(ms: Int) = scope.launch { seekToInternal(ms) }

    /** Dừng hẳn: thả player, xóa hàng đợi (thanh mini-player tự ẩn). */
    fun stop() = scope.launch { stopInternal() }

    /**
     * Mở và phát bài ở vị trí [index]. Trả về false khi mở lỗi (để bên gọi
     * thử bài khác hoặc dừng). Chỉ gọi trên luồng chính.
     */
    private suspend fun startTrack(index: Int): Boolean {
        releasePlayer()
        val track = _state.value.queue.getOrNull(index) ?: return false
        val gen = generation
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(appContext, Uri.parse(track.uri))
            player.setOnCompletionListener { scope.launch { onTrackCompleted() } }
            player.setOnErrorListener { _, what, extra ->
                Log.w("Tov", "MediaPlayer error what=$what extra=$extra track=${track.displayPath}")
                scope.launch { stopInternal() }
                true
            }
            val prepared = CompletableDeferred<Boolean>()
            player.setOnPreparedListener { if (!prepared.isCompleted) prepared.complete(true) }
            player.prepareAsync()
            val ok = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { prepared.await() } ?: false
            if (!ok || gen != generation) {
                runCatching { player.release() }
                return false
            }
            this.player = player
            _state.update { it.copy(index = index, positionMs = 0, durationMs = player.duration, playing = true) }
            player.start()
            startTicker()
            true
        } catch (e: CancellationException) {
            if (this.player === player) this.player = null
            runCatching { player.release() }
            throw e
        } catch (e: Exception) {
            Log.w("Tov", "Could not play ${track.displayPath}: ${e.message}")
            if (this.player === player) this.player = null
            runCatching { player.release() }
            false
        }
    }

    /** Hết bài: sang bài sau trong batch; hết batch thì dừng. */
    private suspend fun onTrackCompleted() {
        advanceFrom(_state.value.index)
    }

    /** Phát thử lần lượt các bài sau [fromIndex]; không bài nào mở được thì dừng. */
    private suspend fun advanceFrom(fromIndex: Int) {
        var i = fromIndex + 1
        while (i < _state.value.queue.size) {
            if (startTrack(i)) return
            i++
        }
        stopInternal()
    }

    /** Phát thử lần lượt các bài trước [fromIndex]; không bài nào mở được thì dừng. */
    private suspend fun backFrom(fromIndex: Int) {
        var i = fromIndex - 1
        while (i >= 0) {
            if (startTrack(i)) return
            i--
        }
        stopInternal()
    }

    private fun pauseInternal() {
        val p = player ?: return
        if (_state.value.playing) {
            runCatching { p.pause() }
            ticker?.cancel()
            _state.update { it.copy(playing = false, positionMs = runCatching { p.currentPosition }.getOrDefault(it.positionMs)) }
        }
    }

    private fun resumeInternal() {
        val p = player ?: return
        runCatching { p.start() }
        _state.update { it.copy(playing = true) }
        startTicker()
    }

    private fun seekToInternal(ms: Int) {
        val p = player ?: return
        val target = ms.coerceIn(0, _state.value.durationMs)
        runCatching { p.seekTo(target) }
        _state.update { it.copy(positionMs = target) }
    }

    private fun stopInternal() {
        generation++
        ticker?.cancel()
        ticker = null
        releasePlayer()
        _state.value = MediaState()
    }

    private fun releasePlayer() {
        player?.let { p -> runCatching { p.release() } }
        player = null
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(TICKER_MS)
                val p = player ?: break
                if (!_state.value.playing) break
                _state.update { s -> s.copy(positionMs = runCatching { p.currentPosition }.getOrDefault(s.positionMs)) }
            }
        }
    }

    companion object {
        private const val PREPARE_TIMEOUT_MS = 20_000L
        private const val TICKER_MS = 500L
    }
}
