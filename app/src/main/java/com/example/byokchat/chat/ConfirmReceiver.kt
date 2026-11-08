package com.example.byokchat.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.byokchat.container

/** Handles the Allow / Deny buttons on the "AI is waiting for your permission" notification. */
class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getIntExtra(ChatNotifier.EXTRA_REQUEST_ID, -1)
        if (id < 0) return
        val allow = intent.getBooleanExtra(ChatNotifier.EXTRA_ALLOW, false)
        val app = context.container
        app.confirmations.resolveById(id, allow)
        app.notifier.cancelConfirmation(id)
    }

    companion object {
        const val ACTION = "com.example.byokchat.CONFIRM_TOOL"
    }
}
