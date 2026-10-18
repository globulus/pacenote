package com.theglobulus.claudecar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BridgeEventParserTest {

    @Test
    fun `parses a claude message in a session`() {
        val e = BridgeEventParser.parse(
            "message",
            """{"id":"abc123.7","session_id":"s1","role":"claude","text":"Tests pass.","ts":1792262529369,"speak":true}""",
        )
        assertEquals(BridgeEvent.Message("abc123.7", "s1", "claude", "Tests pass.", 1792262529369, speak = true), e)
    }

    @Test
    fun `parses echoes and open events with titles`() {
        val echo = BridgeEventParser.parse(
            "message",
            """{"id":"b.2","session_id":"hub","role":"user","text":"list","ts":1,"speak":false,"client_id":"c-1","title":"Claude Car"}""",
        ) as BridgeEvent.Message
        assertEquals("c-1", echo.clientId)
        assertEquals("Claude Car", echo.title)
        val open = BridgeEventParser.parse(
            "message",
            """{"id":"b.3","session_id":"s1","role":"system","kind":"open","title":"Refund bug","text":"Refund bug. Reply to talk.","ts":2,"speak":true}""",
        ) as BridgeEvent.Message
        assertEquals("open", open.kind)
        assertEquals("Refund bug", open.title)
        assertNull(open.clientId)
    }

    @Test
    fun `parses reset and ignores junk`() {
        assertEquals(BridgeEvent.Reset("b", gap = true), BridgeEventParser.parse("reset", """{"boot":"b","gap":true}"""))
        assertNull(BridgeEventParser.parse("message", "not json"))
        assertNull(BridgeEventParser.parse("message", """{"id":"x","role":"claude","text":"no session"}"""))
        assertNull(BridgeEventParser.parse("other", """{"id":"x"}"""))
    }
}

class SessionListParserTest {

    @Test
    fun `parses sessions and folders`() {
        val list = SessionListParser.parse(
            """{"sessions":[{"id":"s1","title":"Refund bug","cwd":"/w/pay","folder":"pay","updated_at":5,"live":true,"running":false}],
               "folders":[{"cwd":"/w/pay","folder":"pay"}]}""",
        )
        assertEquals(RemoteSession("s1", "Refund bug", "/w/pay", "pay", 5, live = true, running = false), list.sessions.single())
        assertEquals(Folder("/w/pay", "pay"), list.folders.single())
    }
}

class BackoffTest {

    @Test
    fun `doubles up to the cap with bounded jitter`() {
        val b = Backoff(minMs = 1_000, maxMs = 60_000, random = Random(42))
        val delays = List(10) { b.next() }
        delays.forEachIndexed { i, d ->
            val base = minOf(60_000L, 1_000L shl i)
            assertTrue("attempt $i: $d", d in (base * 0.8).toLong()..minOf(60_000L, (base * 1.2).toLong()))
        }
        b.reset()
        assertTrue(b.next() <= 1_200)
    }
}
