package com.theglobulus.claudecar.chat

import com.theglobulus.claudecar.data.ChatMessage
import com.theglobulus.claudecar.data.ChatRepository
import com.theglobulus.claudecar.data.Status
import com.theglobulus.claudecar.net.BridgeClient
import com.theglobulus.claudecar.net.PostResult
import com.theglobulus.claudecar.notify.ConversationNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The one path for sending what the driver said: car reply, car screen or phone UI. */
class ChatSender(
    private val repo: ChatRepository,
    private val client: BridgeClient,
    private val notifier: ConversationNotifier,
    private val scope: CoroutineScope,
    /** Tells the driver a message didn't go through: spoken in the audio app, else a notification. */
    var onFailure: (sessionId: String, text: String) -> Unit = { id, _ -> notifier.showConversation(id, alert = true) },
) {
    /** @param quiet sent by voice from the audio app: no notification to update. */
    fun send(sessionId: String, text: String, quiet: Boolean = false): Job {
        val msg = repo.addOutgoing(sessionId, text)
        repo.markRead(sessionId)
        // Re-post right away: this is what clears Android Auto's "sending" state.
        if (!quiet) notifier.showConversation(sessionId, alert = false)
        return scope.launch { deliver(msg, announceFailure = true) }
    }

    /** Resend recent failed messages once the Mac is reachable again. */
    fun flushFailed() {
        repo.retryable(maxAgeMs = RETRY_WINDOW_MS).forEach { msg ->
            repo.setStatus(msg.id, Status.SENDING)
            scope.launch { deliver(msg, announceFailure = false) }
        }
    }

    private suspend fun deliver(msg: ChatMessage, announceFailure: Boolean) {
        for (attempt in 1..3) {
            when (client.postMessage(msg.id, msg.sessionId, msg.text)) {
                PostResult.OK -> {
                    repo.setStatus(msg.id, Status.SENT)
                    return
                }
                PostResult.UNAUTHORIZED, PostResult.REJECTED -> break
                PostResult.NETWORK_ERROR -> if (attempt < 3) delay(attempt * 2_000L)
            }
        }
        repo.setStatus(msg.id, Status.FAILED)
        if (announceFailure) {
            val note = "I couldn't reach your Mac. I'll send that when the connection is back."
            repo.addLocal(msg.sessionId, note)
            onFailure(msg.sessionId, note)
        }
    }

    companion object {
        // Don't fire off a stale request long after the driver moved on.
        const val RETRY_WINDOW_MS = 10 * 60_000L
    }
}
