package com.example.byokchat.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
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

    // ---------- Audio focus (Đợt 2) ----------
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    /** Có cuộc gọi / app khác phát thì dừng tạm; hết thì phát tiếp nếu trước đó đang phát. */
    private var resumeOnFocusGain = false

    /** Đang bị "duck" (app khác phát tiếng ngắn): giảm âm lượng, không dừng. */
    private var ducked = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (ducked) {
                        setVolume(1f)
                        ducked = false
                    }
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        resumeInternal()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocusGain = _state.value.playing
                    pauseInternal()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    setVolume(DUCK_VOLUME)
                    ducked = true
                }
            }
        }
    }

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
                    if (!requestFocus()) {
                        throw MediaPlayException(ModelFacingText.mediaFocusBusy())
                    }
                    _state.value = MediaState(queue = tracks, index = 0)
                    if (!startTrack(0)) {
                        stopInternal()
                        throw MediaPlayException(ModelFacingText.mediaPlayFailed(tracks[0].name))
                    }
                    startService()
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
            player.setVolume(if (ducked) DUCK_VOLUME else 1f, if (ducked) DUCK_VOLUME else 1f)
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
        if (ducked) {
            setVolume(1f)
            ducked = false
        }
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
        abandonFocus()
        stopService()
        _state.value = MediaState()
    }

    // ---------- Audio focus + vòng đời service (Đợt 2) ----------

    /** Xin quyền dùng âm thanh trước khi phát; không được thì báo lỗi cho AI. */
    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        return if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
            true
        } else {
            false
        }
    }

    private fun abandonFocus() {
        audioFocusRequest?.let { request -> runCatching { audioManager.abandonAudioFocusRequest(request) } }
        audioFocusRequest = null
        resumeOnFocusGain = false
        ducked = false
    }

    private fun setVolume(volume: Float) {
        runCatching { player?.setVolume(volume, volume) }
    }

    /** Bật service nền để nhạc chạy khi tắt màn hình / chuyển app. */
    private fun startService() {
        val intent = Intent(appContext, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_START)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: IllegalStateException) {
            // Hệ thống không cho bật dịch vụ nền lúc này (vd app đang chạy
            // nền mà không có dịch vụ nào khác): nhạc vẫn phát trong tiến
            // trình, tắt màn hình có thể dừng.
            Log.w("Tov", "Could not start the music service: ${e.message}")
        }
    }

    private fun stopService() {
        runCatching { appContext.stopService(Intent(appContext, MediaPlaybackService::class.java)) }
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
        private const val DUCK_VOLUME = 0.2f
    }
}
