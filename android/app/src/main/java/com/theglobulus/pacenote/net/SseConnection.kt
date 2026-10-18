package com.theglobulus.pacenote.net

import com.theglobulus.pacenote.data.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Keeps GET /events open, resuming with Last-Event-ID after drops so replies
 * sent while the phone was offline still arrive (exactly once).
 */
class SseConnection(
    private val client: BridgeClient,
    private val repo: ChatRepository,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    enum class State { CONNECTING, CONNECTED, RECONNECTING, AUTH_ERROR, STOPPED }

    interface Listener {
        fun onState(state: State)
        fun onOpen()
        /** @param history true for backlog replayed to a phone that never connected before. */
        fun onMessage(event: BridgeEvent.Message, history: Boolean)
    }

    // Read timeout ~3x the bridge's 25 s heartbeat catches half-open sockets
    // (e.g. after switching from Wi-Fi to mobile data).
    private val http = client.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    private val backoff = Backoff()

    @Volatile private var source: EventSource? = null
    @Volatile private var retryJob: Job? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile var state: State = State.STOPPED
        private set

    @Synchronized
    fun start() {
        if (running) return
        running = true
        connect()
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        retryJob?.cancel()
        source?.cancel()
        source = null
        setState(State.STOPPED)
    }

    /** Skip the backoff, e.g. when the network comes back. */
    @Synchronized
    fun reconnectNow() {
        if (!running || state == State.CONNECTED) return
        backoff.reset()
        retryJob?.cancel()
        source?.cancel()
        connect()
    }

    @Synchronized
    private fun connect() {
        val gen = ++generation
        setState(if (state == State.CONNECTED || state == State.RECONNECTING) State.RECONNECTING else State.CONNECTING)
        val freshClient = repo.lastEventId == null
        val req = client.request("/events")
            .header("Accept", "text/event-stream")
            .apply { repo.lastEventId?.let { header("Last-Event-ID", it) } }
            .build()
        source = EventSources.createFactory(http).newEventSource(req, object : EventSourceListener() {
            private var openedAt = 0L

            override fun onOpen(eventSource: EventSource, response: Response) {
                if (gen != generation) return
                openedAt = System.currentTimeMillis()
                backoff.reset()
                setState(State.CONNECTED)
                listener.onOpen()
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (gen != generation) return
                when (val e = BridgeEventParser.parse(type, data)) {
                    is BridgeEvent.Message -> listener.onMessage(e, history = freshClient && e.ts < openedAt)
                    is BridgeEvent.Reset, null -> Unit
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (gen == generation) scheduleRetry()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (gen != generation) return
                if (response?.code == 401) {
                    setState(State.AUTH_ERROR)
                    return
                }
                scheduleRetry()
            }
        })
    }

    @Synchronized
    private fun scheduleRetry() {
        if (!running) return
        setState(State.RECONNECTING)
        val wait = backoff.next()
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            synchronized(this@SseConnection) { if (running) connect() }
        }
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        listener.onState(s)
    }
}
