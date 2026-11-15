package com.example.byokchat.tools

import com.example.byokchat.util.humanSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext

/**
 * Live progress of a running tool call, shown in its chat card
 * ("Running. Progress 42%") while it runs. UI-only: the text is never
 * part of the tool RESULT sent to the model.
 *
 * A download reports downloaded bytes and the total when the server sent
 * a Content-Length. When the total is unknown (chunked / no length) there
 * is no % to show — the card shows how much was downloaded instead, and
 * a % is never invented.
 */
data class ToolProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    /** Whole percent, or null when the total is unknown. */
    val percent: Int?
        get() = if (totalBytes != null && totalBytes > 0) {
            (downloadedBytes * 100L / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }

    fun label(): String = when {
        percent != null -> "Running. Progress $percent%"
        downloadedBytes > 0 -> "Running. Downloaded ${humanSize(downloadedBytes)}…"
        else -> "Running…"
    }
}

/** Holds the live progress of every running tool call, keyed by tool-call id. */
class ToolProgressBroker {
    private val _progress = MutableStateFlow<Map<String, ToolProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ToolProgress>> = _progress.asStateFlow()

    fun report(callId: String, downloadedBytes: Long, totalBytes: Long?) {
        _progress.update { it + (callId to ToolProgress(downloadedBytes, totalBytes)) }
    }

    /** A call ended (done / failed / cancelled): its progress must not linger. */
    fun clear(callId: String) {
        _progress.update { it - callId }
    }
}

/**
 * Coroutine-context element carrying the tool-call id of the tool
 * currently running in this coroutine. [ToolRegistry.execute] sets it
 * around the tool's run; a tool reports progress through [ToolEnv.reportProgress]
 * without its run signature having to carry the id.
 */
class ToolProgressScope(val callId: String) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ToolProgressScope>

    override val key: CoroutineContext.Key<*> get() = Key
}

/** Report download progress for the tool call running in this coroutine; a no-op outside one. */
suspend fun ToolEnv.reportProgress(downloadedBytes: Long, totalBytes: Long?) {
    val scope = currentCoroutineContext()[ToolProgressScope] ?: return
    progress.report(scope.callId, downloadedBytes, totalBytes)
}
