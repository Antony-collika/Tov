package com.example.byokchat.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Yêu cầu người dùng xác nhận trước khi công cụ chạy. */
class ConfirmRequest(
    val toolName: String,
    val uid: String,
    val risk: Risk,
    val detail: String,
) {
    val result = CompletableDeferred<Boolean>()
}

class ConfirmationBroker {
    private val _pending = MutableStateFlow<List<ConfirmRequest>>(emptyList())
    val pending: StateFlow<List<ConfirmRequest>> = _pending.asStateFlow()

    suspend fun ask(req: ConfirmRequest): Boolean {
        _pending.update { it + req }
        try {
            return req.result.await()
        } finally {
            _pending.update { it - req }
        }
    }

    fun resolve(req: ConfirmRequest, allowed: Boolean) {
        req.result.complete(allowed)
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
