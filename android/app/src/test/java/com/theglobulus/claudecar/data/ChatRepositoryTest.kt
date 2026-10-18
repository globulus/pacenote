package com.theglobulus.claudecar.data

import com.theglobulus.claudecar.net.BridgeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {

    private class MemoryStore(var snapshot: Snapshot? = null) : ChatStore {
        override fun load() = snapshot
        override fun save(snapshot: Snapshot) {
            this.snapshot = snapshot
        }
    }

    private val s1 = "11111111-0000-0000-0000-000000000001"
    private val s2 = "22222222-0000-0000-0000-000000000002"
    private var now = 1_000_000L
    private val store = MemoryStore()
    private val repo = ChatRepository(store, clock = { now })

    private fun claude(id: String, session: String = s1, text: String = "hi", speak: Boolean = true, title: String? = null) =
        BridgeEvent.Message(id = id, sessionId = session, role = "claude", text = text, ts = now, speak = speak, title = title)

    @Test
    fun `turn markers move the cursor but aren't messages`() {
        val marker = BridgeEvent.Message(id = "b.9", sessionId = s1, role = "system", text = "", ts = now, speak = false, kind = "turn_end")
        assertEquals(ApplyResult.STATUS, repo.applyEvent(marker))
        assertTrue(repo.messages(s1).isEmpty())
        assertEquals("b.9", repo.lastEventId)
        assertEquals("b.9", store.snapshot?.lastEventId)
    }

    @Test
    fun `messages are kept per session`() {
        repo.applyEvent(claude("b.1", s1, "one"))
        repo.applyEvent(claude("b.2", s2, "two"))
        assertEquals(listOf("one"), repo.messages(s1).map { it.text })
        assertEquals(listOf("two"), repo.messages(s2).map { it.text })
        assertEquals("b.2", repo.lastEventId)
    }

    @Test
    fun `claude message is new and unread in its session only`() {
        assertEquals(ApplyResult.NEW_INCOMING, repo.applyEvent(claude("b.1")))
        assertEquals(1, repo.unreadIncoming(s1).size)
        assertTrue(repo.unreadIncoming(s2).isEmpty())
        repo.markRead(s1)
        assertTrue(repo.unreadIncoming(s1).isEmpty())
    }

    @Test
    fun `replayed event is a duplicate`() {
        repo.applyEvent(claude("b.1"))
        assertEquals(ApplyResult.DUPLICATE, repo.applyEvent(claude("b.1")))
        assertEquals(1, repo.messages(s1).size)
    }

    @Test
    fun `silent and history events do not alert`() {
        assertEquals(ApplyResult.QUIET, repo.applyEvent(claude("b.1", speak = false)))
        assertEquals(ApplyResult.QUIET, repo.applyEvent(claude("b.2"), history = true))
        assertTrue(repo.unreadIncoming(s1).isEmpty())
    }

    @Test
    fun `echo marks our outgoing message as sent`() {
        repo.addOutgoing(s1, "run the tests", id = "client-1")
        val echo = BridgeEvent.Message("b.5", s1, "user", "run the tests", now, speak = false, clientId = "client-1")
        assertEquals(ApplyResult.ECHO, repo.applyEvent(echo))
        val msg = repo.messages(s1).single()
        assertEquals(Status.SENT, msg.status)
        assertEquals("b.5", msg.eventId)
        assertEquals(ApplyResult.DUPLICATE, repo.applyEvent(echo))
    }

    @Test
    fun `conversations take titles from events and sort by activity, with the hub always present`() {
        repo.applyEvent(claude("b.1", s1, title = "Refund bug"))
        now += 1_000
        repo.applyEvent(claude("b.2", s2, title = "Docs"))
        assertEquals(listOf(s2, s1, HUB), repo.conversations().map { it.sessionId })
        assertEquals("Refund bug", repo.title(s1))
        assertEquals(HUB_TITLE, repo.title(HUB))
        // A later event without a title keeps the name.
        repo.applyEvent(claude("b.3", s1))
        assertEquals("Refund bug", repo.title(s1))
    }

    @Test
    fun `opening a conversation from the phone names it`() {
        repo.openConversation(s2, "Setup guide")
        assertEquals("Setup guide", repo.title(s2))
        assertTrue(repo.conversations().any { it.sessionId == s2 })
    }

    @Test
    fun `only recent failed messages are retryable`() {
        val old = repo.addOutgoing(s1, "old", id = "c-old")
        repo.setStatus(old.id, Status.FAILED)
        now += 20 * 60_000
        val recent = repo.addOutgoing(s2, "recent", id = "c-new")
        repo.setStatus(recent.id, Status.FAILED)
        repo.addOutgoing(s1, "in flight", id = "c-flight")
        assertEquals(listOf("c-new"), repo.retryable(maxAgeMs = 10 * 60_000).map { it.id })
    }

    @Test
    fun `history is capped and persisted with conversations`() {
        val small = ChatRepository(store, clock = { now }, maxMessages = 3)
        (1..5).forEach { small.applyEvent(claude("b.$it", text = "m$it", title = "Refund bug")) }
        assertEquals(listOf("m3", "m4", "m5"), small.messages(s1).map { it.text })

        val reloaded = ChatRepository(store, clock = { now })
        assertEquals("b.5", reloaded.lastEventId)
        assertEquals("Refund bug", reloaded.title(s1))
        assertEquals(3, reloaded.messages(s1).size)
    }

    @Test
    fun `listeners hear about changes`() {
        var calls = 0
        val listener = { calls++; Unit }
        repo.addListener(listener)
        repo.applyEvent(claude("b.1"))
        repo.removeListener(listener)
        repo.applyEvent(claude("b.2"))
        assertEquals(1, calls)
    }

    @Test
    fun `fresh repository has only the hub`() {
        assertNull(repo.lastEventId)
        assertEquals(listOf(HUB), repo.conversations().map { it.sessionId })
    }
}
