package com.theglobulus.pacenote.voice

enum class VoiceState {
    /** Nothing happening: press play to talk. */
    IDLE,
    SPEAKING,
    LISTENING,
    /** Claude is working on what you said. */
    WAITING,
}

enum class EarError { NO_PERMISSION, NO_RECOGNIZER, NETWORK, OTHER }

/** Speech recognition, one utterance at a time. */
interface Ears {
    /** [onHeard] gets what was said, or null when nothing was. */
    fun listen(onHeard: (String?) -> Unit, onError: (EarError) -> Unit)
    fun cancel()
}

/** Text to speech. [onDone] runs on the main thread, and not after [stop]. */
interface Mouth {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
}

/** Short tones, so the driver knows when to speak without looking. */
interface Cues {
    fun listening()
    fun heard()
}

interface Focus {
    fun acquire()
    fun release()
}

/**
 * The spoken conversation with one session, driven from the car's media
 * controls: pick a session, press play, talk. Claude's replies are read aloud
 * in full, and when Claude finishes it listens once more for a follow-up.
 *
 * Plain Kotlin; every call (and every callback) happens on the main thread.
 */
class VoiceController(
    private val ears: Ears,
    private val mouth: Mouth,
    private val cues: Cues,
    private val focus: Focus,
    private val send: (sessionId: String, text: String) -> Unit,
    private val micReady: () -> Boolean,
    private val autoListen: () -> Boolean,
    /** Runs [block] after [delayMs] on the main thread. */
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
) {
    var sessionId: String? = null
        private set
    var title: String = ""
        private set
    var state = VoiceState.IDLE
        private set
    /** One line for the car screen: what's happening, or what was just said. */
    var line: String = ""
        private set
    /** A problem worth showing on the car screen (cleared by the next action). */
    var problem: String? = null
        private set
    var turnRunning = false
        private set

    private val queue = ArrayDeque<String>()
    /** An utterance is being spoken and its callback is still to come. */
    private var speaking = false
    private var listenWhenQuiet = false
    /** The driver pressed pause: read replies, but don't start listening on our own. */
    private var paused = false
    private var lastHeard: String? = null
    private var lastReply = mutableListOf<String>()
    /** A new session we asked for, by folder, until its id arrives. */
    private var pendingFolder: String? = null
    // Bumped whenever we interrupt or switch, so stale callbacks are ignored.
    private var generation = 0
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** Whether replies for this session should be spoken here, not posted as notifications. */
    fun handles(id: String): Boolean = id == sessionId

    /** Start talking to a session: say its name, then listen (unless Claude is busy in it). */
    fun open(id: String, name: String, running: Boolean, lastReply: List<String> = emptyList()) {
        reset()
        sessionId = id
        title = name
        turnRunning = running
        this.lastReply = lastReply.toMutableList()
        when {
            running -> say("${spoken(name)}. Claude is still working on it.")
            !micReady() -> {
                // They picked a session to talk: say why they can't, once.
                problem = MIC_OFF
                say("${spoken(name)}. $MIC_OFF_SPOKEN")
            }
            else -> {
                listenWhenQuiet = true
                say("${spoken(name)}. Go ahead.")
            }
        }
    }

    /** Start a new session in a folder; the conversation switches to it once the Mac has created it. */
    fun create(folder: String) {
        reset()
        sessionId = null
        pendingFolder = folder
        title = "New session in $folder"
        turnRunning = true
        say("Starting a new session in $folder.")
    }

    /** The Mac created a session (or a phone command opened one). */
    fun onOpened(id: String, name: String?) {
        val folder = pendingFolder ?: return
        if (name != "Car: $folder") return
        pendingFolder = null
        sessionId = id
        title = name
        changed()
    }

    /** Press-to-talk: interrupts any speech and listens. */
    fun talk() {
        problem = null
        if (sessionId == null) {
            say(if (pendingFolder != null) "The new session isn't ready yet." else "Pick a session first.")
            return
        }
        interrupt()
        startListening(auto = false)
    }

    /** Pause: stop speaking or listening. Claude keeps working; its reply is still read out. */
    fun stop() {
        interrupt()
        paused = true
        settle()
    }

    /** The car went away. */
    fun close() {
        reset()
        sessionId = null
        title = ""
        turnRunning = false
        focus.release()
        set(VoiceState.IDLE, "")
    }

    /** Read Claude's last reply again. */
    fun repeat() {
        problem = null
        interrupt()
        say(if (lastReply.isEmpty()) "Nothing to repeat yet." else lastReply.joinToString("\n"))
    }

    /** Read one line out (e.g. picked from the queue on the car screen). */
    fun read(text: String) {
        interrupt()
        say(text)
    }

    /** Ask Claude to stop what it's doing. */
    fun cancelTurn() {
        val id = sessionId ?: return
        if (turnRunning) send(id, "stop")
    }

    /** A message from the Mac for the conversation. */
    fun onIncoming(id: String, text: String, fromClaude: Boolean) {
        if (id != sessionId) return
        if (fromClaude) lastReply += text
        say(text)
    }

    /** What the driver said never reached the Mac, so no turn is coming. */
    fun onSendFailed(id: String, text: String) {
        if (id != sessionId) return
        turnRunning = false
        say(text)
    }

    /** Claude started or finished a turn in [id]. */
    fun onTurn(id: String, running: Boolean) {
        if (id != sessionId) return
        turnRunning = running
        if (running) return changed()
        // Done: listen for a follow-up once everything has been read out
        // (not if the driver paused, or is already talking).
        if (state == VoiceState.LISTENING) return changed()
        if (!paused && autoListen()) listenWhenQuiet = true
        if (!speaking) speakNext()
    }

    private fun reset() {
        interrupt()
        pendingFolder = null
        problem = null
        lastHeard = null
        lastReply.clear()
    }

    /** Cut off whatever is being said or heard (any new action also ends a pause). */
    private fun interrupt() {
        paused = false
        generation++
        ears.cancel()
        mouth.stop()
        queue.clear()
        speaking = false
        listenWhenQuiet = false
        if (state == VoiceState.SPEAKING || state == VoiceState.LISTENING) state = VoiceState.IDLE
    }

    /** Read [text] out after anything already queued; never over the driver talking. */
    private fun say(text: String) {
        queue.addAll(sentences(text))
        if (!speaking && state != VoiceState.LISTENING) speakNext()
    }

    private fun speakNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            if (listenWhenQuiet && !turnRunning && sessionId != null) {
                listenWhenQuiet = false
                startListening(auto = true)
            } else {
                settle()
            }
            return
        }
        val gen = generation
        focus.acquire()
        speaking = true
        set(VoiceState.SPEAKING, next)
        mouth.speak(next) {
            if (gen == generation) {
                speaking = false
                speakNext()
            }
        }
    }

    /** [auto]: listening for a follow-up on our own, rather than because play was pressed. */
    private fun startListening(auto: Boolean) {
        if (!micReady()) {
            problem = MIC_OFF
            // Say so when asked to listen; don't nag after every reply.
            if (auto) settle() else say(MIC_OFF_SPOKEN)
            return
        }
        val gen = ++generation
        focus.acquire()
        set(VoiceState.LISTENING, "Listening…")
        cues.listening()
        // Let the tone finish so the recognizer doesn't hear it.
        schedule(CUE_MS) {
            if (gen != generation) return@schedule
            ears.listen(
                onHeard = { text -> if (gen == generation) heard(text) },
                onError = { error -> if (gen == generation) earError(error) },
            )
        }
    }

    private fun heard(text: String?) {
        val id = sessionId
        val said = text?.trim().orEmpty()
        state = VoiceState.IDLE
        if (id != null && said.isNotEmpty()) {
            cues.heard()
            lastReply.clear()
            lastHeard = said
            send(id, said)
            turnRunning = true
        }
        // Read anything that arrived while listening; otherwise wait quietly.
        if (queue.isNotEmpty()) speakNext() else settle()
    }

    private fun earError(error: EarError) {
        state = VoiceState.IDLE
        problem = when (error) {
            EarError.NO_PERMISSION -> MIC_OFF
            EarError.NO_RECOGNIZER -> "No speech recognition on this phone"
            EarError.NETWORK -> "Speech recognition needs a connection"
            EarError.OTHER -> "Speech recognition failed"
        }
        say(
            when (error) {
                EarError.NO_PERMISSION -> MIC_OFF_SPOKEN
                EarError.NO_RECOGNIZER -> "This phone has no speech recognition."
                EarError.NETWORK -> "I couldn't reach speech recognition. Try again."
                EarError.OTHER -> "Speech recognition failed. Try again."
            }
        )
    }

    /** Nothing to say or hear right now. */
    private fun settle() {
        focus.release()
        set(if (turnRunning) VoiceState.WAITING else VoiceState.IDLE, idleLine())
    }

    private fun idleLine(): String = when {
        problem != null -> problem!!
        turnRunning -> lastHeard?.let { "You: $it" } ?: "Claude is working…"
        lastReply.isNotEmpty() -> lastReply.last()
        else -> "Press play to talk"
    }

    private fun set(s: VoiceState, text: String) {
        state = s
        line = text
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }

    companion object {
        const val CUE_MS = 250L
        const val MIC_OFF = "Microphone is off: open Pacenote on the phone"
        private const val MIC_OFF_SPOKEN =
            "The microphone is off. When you're parked, open Pacenote on your phone once to turn it on."

        /** Speech in sentence-sized pieces, so pausing and skipping feel immediate. */
        fun sentences(text: String, max: Int = 300): List<String> {
            val parts = text.trim().split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
            return parts.flatMap { p -> if (p.length <= max) listOf(p) else p.chunked(max) }
        }

        private fun spoken(title: String) = title.trimEnd('.', '!', '?', '…', ':', ';', ',', ' ')
    }
}
