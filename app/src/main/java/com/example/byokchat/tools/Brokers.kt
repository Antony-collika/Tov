package com.example.byokchat.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Yêu cầu người dùng xác nhận trước khi công cụ chạy. */
class ConfirmRequest(
    val toolName: String,
    val uid: String,
    val risk: Risk,
    val detail: String,
    /** The user turn (question + answer) this request belongs to. */
    val turnId: String = "",
    /** Who is asking: "Main" or the sub-agent's name — shown in the dialog and the notification (25/11/2026). */
    val agentName: String = "Main",
    /**
     * The run ("round") this request belongs to; the scope of "Allow
     * all in this round". Main's round is "main:<turnId>"; a sub's round
     * is a fresh id per delegation, so Main calling the same sub twice
     * in one turn is two rounds. Blank = fall back to [turnId].
     */
    val roundId: String = "",
) {
    /** The scope key "Allow all in this round" covers. */
    val scopeKey: String get() = roundId.ifBlank { turnId }
    val result = CompletableDeferred<Boolean>()
    val id: Int = nextId.incrementAndGet()

    private companion object {
        val nextId = AtomicInteger(0)
    }
}

class ConfirmationBroker {
    private val _pending = MutableStateFlow<List<ConfirmRequest>>(emptyList())
    val pending: StateFlow<List<ConfirmRequest>> = _pending.asStateFlow()

    /** Called when a request starts waiting (used to post a notification if the app is in the background). */
    @Volatile
    var onRequested: ((ConfirmRequest) -> Unit)? = null

    /** Called when a request has been answered or cancelled (used to remove its notification). */
    @Volatile
    var onFinished: ((ConfirmRequest) -> Unit)? = null

    // Runs ("rounds") for which the user chose "Allow all in this round".
    // One entry per agent per run — never applies to Risk.DELETE.
    private val roundAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun ask(req: ConfirmRequest): Boolean {
        if (req.risk != Risk.DELETE && req.scopeKey.isNotEmpty() && req.scopeKey in roundAllowed) return true
        _pending.update { it + req }
        onRequested?.invoke(req)
        try {
            return req.result.await()
        } finally {
            _pending.update { it - req }
            onFinished?.invoke(req)
        }
    }

    fun resolve(req: ConfirmRequest, allowed: Boolean) {
        req.result.complete(allowed)
    }

    /**
     * "Allow all in this round": allow this request and every later (or already waiting) non-delete request of the
     * same agent in the same run. Deleting is never covered: a delete request is just allowed once. A new run of the
     * same agent in the same turn is a different round and is asked again — the next run may ask for very different
     * permissions.
     */
    fun allowAllInRound(req: ConfirmRequest) {
        if (req.risk == Risk.DELETE || req.scopeKey.isEmpty()) {
            resolve(req, true)
            return
        }
        roundAllowed.add(req.scopeKey)
        resolve(req, true)
        _pending.value.filter { it.scopeKey == req.scopeKey && it.risk != Risk.DELETE }.forEach { resolve(it, true) }
    }

    /** Called by the chat engine when a turn's run is over, so "Allow all in this round" does not outlive Main's turn. */
    fun endTurn(turnId: String) {
        roundAllowed.remove(turnId)
        roundAllowed.remove("main:$turnId")
    }

    /** Called when one sub-agent run is over, so its "Allow all in this round" does not outlive the run. */
    fun endRun(roundId: String) {
        roundAllowed.remove(roundId)
    }

    /** Used by the notification buttons. Does nothing if the request is already gone. */
    fun resolveById(id: Int, allowed: Boolean) {
        _pending.value.firstOrNull { it.id == id }?.let { resolve(it, allowed) }
    }
}

/** Xin quyền Android (lịch...) từ bên trong luồng gọi công cụ. MainActivity gắn "requester". */
class PermissionBroker(private val ctx: Context) {
    @Volatile
    var requester: (suspend (Array<String>) -> Unit)? = null

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    suspend fun ensure(perms: List<String>): Boolean {
        if (perms.all(::granted)) return true
        val r = requester ?: return false
        r(perms.toTypedArray())
        return perms.all(::granted)
    }
}
