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
    /** The user turn (question + answer) this request belongs to; used by "Allow all in this turn". */
    val turnId: String = "",
) {
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

    // Turns for which the user chose "Allow all in this turn". Never applies to Risk.DELETE.
    private val turnAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun ask(req: ConfirmRequest): Boolean {
        if (req.risk != Risk.DELETE && req.turnId.isNotEmpty() && req.turnId in turnAllowed) return true
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
     * "Allow all in this turn": allow this request and every later (or already waiting) non-delete request of the
     * same turn. Deleting is never covered: a delete request is just allowed once.
     */
    fun allowAllInTurn(req: ConfirmRequest) {
        if (req.risk == Risk.DELETE || req.turnId.isEmpty()) {
            resolve(req, true)
            return
        }
        turnAllowed.add(req.turnId)
        resolve(req, true)
        _pending.value.filter { it.turnId == req.turnId && it.risk != Risk.DELETE }.forEach { resolve(it, true) }
    }

    /** Called by the chat engine when a turn's run is over, so "Allow all in this turn" does not outlive it. */
    fun endTurn(turnId: String) {
        turnAllowed.remove(turnId)
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
