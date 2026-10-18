package com.theglobulus.claudecar.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.theglobulus.claudecar.app
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Handles a conversation notification's reply (voice, in the car) and mark-as-read actions. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
                if (text.isNullOrEmpty()) {
                    app.notifier.showConversation(sessionId, alert = false)
                    return
                }
                val pending = goAsync()
                app.scope.launch {
                    try {
                        withTimeoutOrNull(9_000) { app.sender.send(sessionId, text).join() }
                    } finally {
                        pending.finish()
                    }
                }
            }
            // Only clears the unread state: the notification stays so there's
            // always something to reply to.
            ACTION_MARK_READ -> {
                app.repo.markRead(sessionId)
                app.notifier.showConversation(sessionId, alert = false)
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.theglobulus.claudecar.REPLY"
        const val ACTION_MARK_READ = "com.theglobulus.claudecar.MARK_READ"
        const val EXTRA_SESSION_ID = "session_id"
        const val KEY_TEXT = "reply_text"
    }
}
