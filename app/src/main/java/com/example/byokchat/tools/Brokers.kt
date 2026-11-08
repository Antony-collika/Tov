package com.example.byokchat.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** Yêu cầu người dùng xác nhận trước khi công cụ chạy. */
class ConfirmRequest(
    val toolName: String,
    val uid: String,
    val risk: Risk,
    val detail: String,
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

    suspend fun ask(req: ConfirmRequest): Boolean {
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
