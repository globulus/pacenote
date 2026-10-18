package com.theglobulus.pacenote.voice

import com.theglobulus.pacenote.car.QueueRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceControllerTest {

    /** Speech that finishes when the test says so. */
    private class FakeMouth : Mouth {
        val said = mutableListOf<String>()
        var pending: (() -> Unit)? = null
        override fun speak(text: String, onDone: () -> Unit) {
            said += text
            pending = onDone
        }
        override fun stop() {
            pending = null
        }
        fun finish() = pending!!.also { pending = null }.invoke()
        fun finishAll() {
            while (pending != null) finish()
        }
    }

    private class FakeEars : Ears {
        var heard: ((String?) -> Unit)? = null
        var failed: ((EarError) -> Unit)? = null
        var cancels = 0
        override fun listen(onHeard: (String?) -> Unit, onError: (EarError) -> Unit) {
            heard = onHeard
            failed = onError
        }
        override fun cancel() {
            cancels++
            heard = null
        }
        val listening get() = heard != null
        fun hear(text: String?) = heard!!.also { heard = null }.invoke(text)
    }

    private lateinit var mouth: FakeMouth
    private lateinit var ears: FakeEars
    private val sent = mutableListOf<Pair<String, String>>()
    private var mic = true
    private var focusHeld = false
    private lateinit var voice: VoiceController

    @Before
    fun setUp() {
        mouth = FakeMouth()
        ears = FakeEars()
        sent.clear()
        mic = true
        voice = VoiceController(
            ears = ears,
            mouth = mouth,
            cues = object : Cues {
                override fun listening() {}
                override fun heard() {}
            },
            focus = object : Focus {
                override fun acquire() {
                    focusHeld = true
                }
                override fun release() {
                    focusHeld = false
                }
            },
            send = { id, text -> sent += id to text },
            micReady = { mic },
            autoListen = { true },
            schedule = { _, block -> block() },
        )
    }

    @Test
    fun `a full exchange - name, listen, send, reply read out, then listen for a follow-up`() {
        voice.open("s1", "Refund bug.", running = false)
        assertEquals(listOf("Refund bug."), mouth.said) // a sentence at a time
        assertEquals(VoiceState.SPEAKING, voice.state)
        mouth.finishAll()
        assertEquals(VoiceState.LISTENING, voice.state)

        ears.hear("run the tests")
        assertEquals(listOf("s1" to "run the tests"), sent)
        assertEquals(VoiceState.WAITING, voice.state)
        assertEquals("You: run the tests", voice.line)
        assertFalse(focusHeld) // music may play while Claude works

        voice.onTurn("s1", running = true)
        voice.onIncoming("s1", "Running them now. It takes a minute.", fromClaude = true)
        mouth.finishAll()
        assertEquals(listOf("Running them now.", "It takes a minute."), mouth.said.drop(2))
        assertEquals(VoiceState.WAITING, voice.state) // still working: don't listen yet

        voice.onIncoming("s1", "All 40 tests pass.", fromClaude = true)
        voice.onTurn("s1", running = false) // arrives while the reply is being read
        assertEquals(VoiceState.SPEAKING, voice.state)
        mouth.finishAll()
        assertEquals(VoiceState.LISTENING, voice.state)

        ears.hear(null) // nothing said: go quiet
        assertEquals(VoiceState.IDLE, voice.state)
        assertEquals("All 40 tests pass.", voice.line)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a reply that arrives while the driver is talking waits until they're done`() {
        voice.open("s1", "Docs", running = false)
        mouth.finishAll()
        voice.onIncoming("s1", "Earlier result.", fromClaude = true)
        assertEquals(VoiceState.LISTENING, voice.state)
        assertEquals(2, mouth.said.size)
        ears.hear("thanks")
        assertEquals("Earlier result.", mouth.said.last())
    }

    @Test
    fun `talk interrupts speech, pause stops everything, and stale callbacks are ignored`() {
        voice.open("s1", "Docs", running = false)
        val introDone = mouth.pending!!
        voice.talk()
        assertEquals(VoiceState.LISTENING, voice.state)
        introDone() // the cut-off intro finishing late must not start anything
        assertEquals(VoiceState.LISTENING, voice.state)
        val heard = ears.heard!!
        voice.stop()
        assertEquals(VoiceState.IDLE, voice.state)
        heard("too late")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a session that's busy is announced, not listened to`() {
        voice.open("s1", "Refund bug", running = true)
        mouth.finishAll()
        assertEquals(VoiceState.WAITING, voice.state)
        assertFalse(ears.listening)
        voice.onIncoming("s1", "Done, all green.", fromClaude = true)
        mouth.finishAll()
        voice.onTurn("s1", running = false)
        assertTrue(ears.listening)
    }

    @Test
    fun `turn end after a pause doesn't start listening`() {
        voice.open("s1", "Docs", running = false)
        mouth.finishAll()
        ears.hear("go")
        voice.stop()
        voice.onTurn("s1", running = false)
        assertFalse(ears.listening)
        assertEquals(VoiceState.IDLE, voice.state)
    }

    @Test
    fun `other sessions are not ours`() {
        voice.open("s1", "Docs", running = false)
        assertTrue(voice.handles("s1"))
        assertFalse(voice.handles("s2"))
        voice.onIncoming("s2", "Not for us.", fromClaude = true)
        assertFalse(mouth.said.contains("Not for us."))
    }

    @Test
    fun `without the microphone it explains once when asked, and stays quiet after replies`() {
        mic = false
        voice.open("s1", "Docs", running = false)
        mouth.finishAll()
        assertTrue(mouth.said[1].startsWith("The microphone is off"))
        assertEquals(VoiceController.MIC_OFF, voice.problem)
        assertFalse(ears.listening)
        // After a reply it stays quiet about it.
        voice.talk()
        mouth.finishAll()
        val count = mouth.said.size
        voice.onIncoming("s1", "Reply.", fromClaude = true)
        voice.onTurn("s1", running = false)
        mouth.finishAll()
        assertEquals(count + 1, mouth.said.size)
        voice.talk()
        assertTrue(mouth.said.last().startsWith("The microphone is off"))
        assertFalse(ears.listening)
    }

    @Test
    fun `recognizer errors are spoken`() {
        voice.open("s1", "Docs", running = false)
        mouth.finishAll()
        ears.failed!!(EarError.NETWORK)
        assertEquals("I couldn't reach speech recognition.", mouth.said.last())
        assertEquals("Speech recognition needs a connection", voice.problem)
    }

    @Test
    fun `repeat reads the last reply again`() {
        voice.open("s1", "Docs", running = false, lastReply = listOf("Old answer."))
        voice.repeat()
        assertEquals("Old answer.", mouth.said.last())
        mouth.finishAll()
        assertEquals(VoiceState.IDLE, voice.state)
    }

    @Test
    fun `a new session switches over once the Mac has created it`() {
        voice.create("payments")
        assertNull(voice.sessionId)
        mouth.finishAll()
        voice.talk()
        assertEquals("The new session isn't ready yet.", mouth.said.last())
        voice.onOpened("new-id", "Car: other")
        assertNull(voice.sessionId)
        voice.onOpened("new-id", "Car: payments")
        assertEquals("new-id", voice.sessionId)
        mouth.finishAll()
        voice.onIncoming("new-id", "Ready to work in payments.", fromClaude = true)
        mouth.finishAll()
        voice.onTurn("new-id", running = false)
        assertTrue(ears.listening)
    }

    @Test
    fun `a failed send ends the wait`() {
        voice.open("s1", "Docs", running = false)
        mouth.finishAll()
        ears.hear("hello")
        voice.onSendFailed("s1", "I couldn't reach your Mac.")
        mouth.finishAll()
        assertEquals(VoiceState.IDLE, voice.state)
        assertFalse(voice.turnRunning)
    }

    @Test
    fun `close forgets the session`() {
        voice.open("s1", "Docs", running = false)
        voice.close()
        assertNull(voice.sessionId)
        assertEquals(VoiceState.IDLE, voice.state)
        assertFalse(focusHeld)
    }

    @Test
    fun `sentences and queue rows`() {
        assertEquals(listOf("One.", "Two?", "Three"), VoiceController.sentences("One. Two?\nThree"))
        val rows = QueueRows.split("Short one. " + "word ".repeat(30).trim())
        assertEquals("Short one.", rows[0])
        assertTrue(rows.drop(1).all { it.length <= 80 })
        assertEquals("word ".repeat(30).trim(), rows.drop(1).joinToString(" "))
    }
}
