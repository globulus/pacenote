package com.theglobulus.pacenote.data

import com.theglobulus.pacenote.net.BridgeEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

data class Snapshot(
    val messages: List<ChatMessage>,
    val lastEventId: String?,
    val conversations: List<Conversation> = emptyList(),
)

interface ChatStore {
    fun load(): Snapshot?
    fun save(snapshot: Snapshot)
}

enum class ApplyResult {
    /** A message the driver should hear. */
    NEW_INCOMING,
    /** New, but not worth an alert. */
    QUIET,
    /** The Mac confirming a message we sent (or one sent from elsewhere). */
    ECHO,
    DUPLICATE,
    /** A turn started or finished: not a message, nothing stored. */
    STATUS,
}

/** Silent markers around each of Claude's turns (see bridge/src/car.ts). */
val STATUS_KINDS = setOf("turn_start", "turn_end")

/**
 * All car conversations, shared by the service, notifications, the reply
 * receiver, the phone UI and the car screen. Plain Kotlin so it's unit-testable.
 */
class ChatRepository(
    private val store: ChatStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxMessages: Int = 300,
) {
    private val lock = Any()
    private val messages = mutableListOf<ChatMessage>()
    private val conversations = linkedMapOf<String, Conversation>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    var lastEventId: String? = null
        private set

    init {
        store.load()?.let { s ->
            messages += s.messages.takeLast(maxMessages)
            s.conversations.forEach { conversations[it.sessionId] = it }
            lastEventId = s.lastEventId
        }
    }

    fun messages(sessionId: String): List<ChatMessage> = synchronized(lock) { messages.filter { it.sessionId == sessionId } }

    fun unreadIncoming(sessionId: String): List<ChatMessage> =
        synchronized(lock) { messages.filter { it.sessionId == sessionId && it.incoming && !it.read } }

    /** Conversations, most recently active first. The hub is always there. */
    fun conversations(): List<Conversation> = synchronized(lock) {
        val list = conversations.values.toMutableList()
        if (list.none { it.sessionId == HUB }) list += Conversation(HUB, HUB_TITLE, 0)
        list.sortedByDescending { it.lastActive }
    }

    fun title(sessionId: String): String = synchronized(lock) {
        conversations[sessionId]?.title ?: if (sessionId == HUB) HUB_TITLE else "Claude"
    }

    /** Recent failed outgoing messages, still worth sending. */
    fun retryable(maxAgeMs: Long): List<ChatMessage> = synchronized(lock) {
        val cutoff = clock() - maxAgeMs
        messages.filter { it.role == Role.USER && it.status == Status.FAILED && it.ts >= cutoff }
    }

    /** Make sure a session has a conversation, e.g. when picked on the phone. */
    fun openConversation(sessionId: String, title: String) = mutate { touch(sessionId, title) }

    fun addOutgoing(sessionId: String, text: String, id: String = UUID.randomUUID().toString()): ChatMessage {
        val msg = ChatMessage(id = id, sessionId = sessionId, role = Role.USER, text = text, ts = clock(), status = Status.SENDING)
        mutate {
            messages += msg
            touch(sessionId, null)
        }
        return msg
    }

    /** A message generated on the phone itself, e.g. "can't reach your Mac". */
    fun addLocal(sessionId: String, text: String, kind: String = "local"): ChatMessage {
        val msg = ChatMessage(
            id = "local-${UUID.randomUUID()}", sessionId = sessionId, role = Role.SYSTEM, text = text, ts = clock(),
            read = false, kind = kind,
        )
        mutate {
            messages += msg
            touch(sessionId, null)
        }
        return msg
    }

    fun setStatus(id: String, status: Status) = mutate {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = messages[i].copy(status = status)
    }

    fun markRead(sessionId: String) = mutate {
        messages.replaceAll { if (it.sessionId == sessionId && it.incoming && !it.read) it.copy(read = true) else it }
    }

    /**
     * @param history true when replaying events from before this phone first
     *   connected; they're stored as read so a fresh install doesn't alert.
     */
    fun applyEvent(e: BridgeEvent.Message, history: Boolean = false): ApplyResult {
        if (e.kind in STATUS_KINDS) {
            mutate { lastEventId = e.id }
            return ApplyResult.STATUS
        }
        var result = ApplyResult.DUPLICATE
        mutate {
            lastEventId = e.id
            if (messages.any { it.eventId == e.id || it.id == e.id }) return@mutate
            touch(e.sessionId, e.title, at = e.ts)
            if (e.role == "user") {
                val i = e.clientId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
                if (i >= 0) {
                    messages[i] = messages[i].copy(status = Status.SENT, eventId = e.id)
                } else {
                    messages += ChatMessage(
                        id = e.clientId ?: e.id, sessionId = e.sessionId, role = Role.USER, text = e.text, ts = e.ts,
                        status = Status.SENT, eventId = e.id,
                    )
                }
                result = ApplyResult.ECHO
            } else {
                val loud = e.speak && !history
                messages += ChatMessage(
                    id = e.id, sessionId = e.sessionId, role = if (e.role == "claude") Role.CLAUDE else Role.SYSTEM,
                    text = e.text, ts = e.ts, read = !loud, eventId = e.id, kind = e.kind,
                )
                result = if (loud) ApplyResult.NEW_INCOMING else ApplyResult.QUIET
            }
        }
        return result
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    // Call with the lock held.
    private fun touch(sessionId: String, title: String?, at: Long = clock()) {
        val prev = conversations[sessionId]
        val name = title ?: prev?.title ?: if (sessionId == HUB) HUB_TITLE else "Claude"
        conversations[sessionId] = Conversation(sessionId, name, maxOf(at, prev?.lastActive ?: 0))
    }

    private inline fun mutate(block: () -> Unit) {
        val snapshot = synchronized(lock) {
            block()
            while (messages.size > maxMessages) messages.removeAt(0)
            // Forget conversations whose messages have all aged out (keep the hub).
            val live = messages.mapTo(HashSet()) { it.sessionId }
            conversations.keys.retainAll { it == HUB || it in live || conversations[it]!!.lastActive > clock() - DAY_MS }
            Snapshot(messages.toList(), lastEventId, conversations.values.toList())
        }
        store.save(snapshot)
        listeners.forEach { it() }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
