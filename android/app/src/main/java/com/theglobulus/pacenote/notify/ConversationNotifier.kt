package com.theglobulus.pacenote.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.theglobulus.pacenote.R
import com.theglobulus.pacenote.data.ChatMessage
import com.theglobulus.pacenote.data.ChatRepository
import com.theglobulus.pacenote.data.HUB
import com.theglobulus.pacenote.data.Role
import com.theglobulus.pacenote.ui.ChatActivity

/**
 * One conversation notification per session, each from its own "contact"
 * named after the session. Android Auto reads them aloud and offers a voice
 * reply, which comes back through [ReplyReceiver].
 */
class ConversationNotifier(private val context: Context, private val repo: ChatRepository) {

    val me: Person = Person.Builder().setName("You").setKey("me").build()
    private val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
    private val nm = NotificationManagerCompat.from(context)

    /** The session as a contact, so the car says "Refund bug says…". */
    fun contact(sessionId: String): Person = Person.Builder()
        .setName(repo.title(sessionId))
        .setKey(sessionId)
        .setBot(true)
        .setImportant(true)
        .setIcon(icon)
        .build()

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Claude messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Replies from your Claude sessions, read aloud in the car"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows whether the phone is connected to your Mac"
                setShowBadge(false)
            }
        )
    }

    /** A long-lived conversation shortcut makes this a proper conversation notification. */
    private fun publishShortcut(sessionId: String) {
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(sessionId))
            .setShortLabel(repo.title(sessionId).take(24))
            .setLongLived(true)
            .setPerson(contact(sessionId))
            .setLocusId(LocusIdCompat(shortcutId(sessionId)))
            .setIcon(icon)
            .setIntent(ChatActivity.intent(context, sessionId).setAction(Intent.ACTION_VIEW))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun showConversation(sessionId: String, alert: Boolean) {
        if (!canNotify()) return
        val shown = messagesToShow(sessionId)
        if (shown.isEmpty()) return
        publishShortcut(sessionId)

        val sender = contact(sessionId)
        // One-to-one: the sender's name (the session title) is the conversation name.
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(false)
        shown.forEach { m -> style.addMessage(m.text, m.ts, if (m.role == Role.USER) null else sender) }

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(shortcutId(sessionId))
            .setLocusId(LocusIdCompat(shortcutId(sessionId)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, notificationId(sessionId), ChatActivity.intent(context, sessionId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setAllowSystemGeneratedContextualActions(false)
            .addAction(replyAction(sessionId))
            .addInvisibleAction(markAsReadAction(sessionId))
        if (alert) builder.setOnlyAlertOnce(false) else builder.setSilent(true)

        @Suppress("MissingPermission")
        nm.notify(notificationId(sessionId), builder.build())
    }

    /**
     * Unread messages (what Android Auto should read), preceded by the
     * driver's last message for context. With nothing unread, the last exchange.
     */
    private fun messagesToShow(sessionId: String): List<ChatMessage> {
        val all = repo.messages(sessionId)
        val firstUnread = all.indexOfFirst { it.incoming && !it.read }
        if (firstUnread < 0) return all.takeLast(2)
        val lead = all.subList(0, firstUnread).lastOrNull { it.role == Role.USER }
        val unread = all.subList(firstUnread, all.size).filter { it.role == Role.USER || !it.read }
        return (listOfNotNull(lead) + unread).takeLast(MAX_SHOWN)
    }

    private fun actionIntent(action: String, sessionId: String) = Intent(context, ReplyReceiver::class.java)
        .setAction(action)
        // A distinct data URI keeps each conversation's PendingIntents apart.
        .setData(Uri.parse("pacenote://conversation/$sessionId"))
        .putExtra(ReplyReceiver.EXTRA_SESSION_ID, sessionId)

    private fun replyAction(sessionId: String): NotificationCompat.Action {
        // Must be mutable so Android Auto can fill in the transcribed reply.
        val pending = PendingIntent.getBroadcast(
            context, notificationId(sessionId), actionIntent(ReplyReceiver.ACTION_REPLY, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply").build()
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_claude, "Reply", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun markAsReadAction(sessionId: String): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context, notificationId(sessionId), actionIntent(ReplyReceiver.ACTION_MARK_READ, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_claude, "Mark as read", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle("Pacenote")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, STATUS_ID,
                    Intent(context, com.theglobulus.pacenote.ui.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    fun updateStatus(text: String) {
        if (!canNotify()) return
        @Suppress("MissingPermission")
        nm.notify(STATUS_ID, statusNotification(text))
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_STATUS = "status"
        const val STATUS_ID = 2
        private const val HUB_ID = 1
        private const val MAX_SHOWN = 5

        fun notificationId(sessionId: String): Int =
            if (sessionId == HUB) HUB_ID else 1_000 + (sessionId.hashCode() and 0x7fffffff) % 1_000_000

        fun shortcutId(sessionId: String): String = "conv-$sessionId"
    }
}
