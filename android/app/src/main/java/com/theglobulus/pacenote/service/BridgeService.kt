package com.theglobulus.pacenote.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.theglobulus.pacenote.app
import com.theglobulus.pacenote.data.ApplyResult
import com.theglobulus.pacenote.data.HUB
import com.theglobulus.pacenote.net.BridgeEvent
import com.theglobulus.pacenote.net.SseConnection
import com.theglobulus.pacenote.notify.ConversationNotifier

/**
 * Keeps the phone connected to the service on the Mac. Claude's replies go to
 * the audio app's spoken conversation when it's on that session, and become
 * conversation notifications otherwise.
 *
 * It also holds the microphone for the audio app: Android only lets a
 * background app listen if it runs a microphone foreground service that was
 * started while the app was on screen, so opening the app turns it on.
 */
class BridgeService : Service(), SseConnection.Listener {

    private lateinit var sse: SseConnection
    private lateinit var carConnection: CarConnection
    private var carType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED

    private val carObserver = Observer<Int> { type ->
        val wasProjecting = carType == CarConnection.CONNECTION_TYPE_PROJECTION
        carType = type
        if (type == CarConnection.CONNECTION_TYPE_PROJECTION && !wasProjecting) onCarConnected()
        if (type != CarConnection.CONNECTION_TYPE_PROJECTION && wasProjecting) app.voiceOrNull()?.close()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = sse.reconnectNow()
    }

    override fun onCreate() {
        super.onCreate()
        goForeground(withMic = false)
        sse = SseConnection(app.client, app.repo, app.scope, this)
        sse.start()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        carConnection = CarConnection(this)
        carConnection.type.observeForever(carObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_MIC, false) == true && !micActive) goForeground(withMic = true)
        sse.reconnectNow()
        return START_STICKY
    }

    /** Microphone only works when asked for from the app on screen; otherwise Android refuses it. */
    private fun goForeground(withMic: Boolean) {
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        val mic = withMic &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = app.notifier.statusNotification(statusText)
        micActive = try {
            startForeground(ConversationNotifier.STATUS_ID, notification, if (mic) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else base)
            mic
        } catch (e: Exception) {
            // SecurityException / ForegroundServiceStartNotAllowedException: not allowed from the background.
            Log.w("BridgeService", "microphone foreground service refused: $e")
            startForeground(ConversationNotifier.STATUS_ID, notification, base)
            false
        }
        app.notifier.updateStatus(statusText)
    }

    override fun onDestroy() {
        micActive = false
        carConnection.type.removeObserver(carObserver)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        sse.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android Auto only lists notifications that arrive during the drive, so
    // post some when the car connects: that gives the driver something to reply to.
    private fun onCarConnected() {
        sse.reconnectNow()
        val text = if (sse.state == SseConnection.State.CONNECTED) {
            "Pacenote is ready. Open Pacenote in your car's apps to talk, or reply list to hear your sessions."
        } else {
            "I can't reach your Mac right now. I'll keep trying."
        }
        app.repo.addLocal(HUB, text, kind = "car_connected")
        app.notifier.showConversation(HUB, alert = true)

        // Bring back the session you talked to last, if it was recent.
        val now = System.currentTimeMillis()
        app.repo.conversations().firstOrNull { it.sessionId != HUB && now - it.lastActive < RECENT_MS }?.let { c ->
            app.repo.addLocal(c.sessionId, "${c.title}. Reply to continue.", kind = "car_connected")
            app.notifier.showConversation(c.sessionId, alert = false)
        }
    }

    private var connection = "Connecting to your Mac…"
    private val statusText: String
        get() = if (micActive) "$connection · microphone ready" else connection

    override fun onState(state: SseConnection.State) {
        connection = when (state) {
            SseConnection.State.CONNECTED -> "Connected to your Mac"
            SseConnection.State.CONNECTING -> "Connecting to your Mac…"
            SseConnection.State.RECONNECTING -> "Reconnecting… (is Tailscale on, and the Mac awake?)"
            SseConnection.State.AUTH_ERROR -> "Wrong token: open the app to fix it"
            SseConnection.State.STOPPED -> "Stopped"
        }
        app.notifier.updateStatus(statusText)
    }

    override fun onOpen() = app.sender.flushFailed()

    override fun onMessage(event: BridgeEvent.Message, history: Boolean) {
        val result = app.repo.applyEvent(event, history)
        if (history) return
        // The voice loop lives on the main thread; keep events in order by posting them all.
        app.main.post {
            val voice = app.voiceOrNull()
            if (event.kind == "open") voice?.onOpened(event.sessionId, event.title)
            when (result) {
                ApplyResult.STATUS -> voice?.onTurn(event.sessionId, running = event.kind == "turn_start")
                ApplyResult.NEW_INCOMING -> if (voice != null && voice.handles(event.sessionId)) {
                    app.repo.markRead(event.sessionId)
                    voice.onIncoming(event.sessionId, event.text, fromClaude = event.role == "claude")
                } else {
                    app.notifier.showConversation(event.sessionId, alert = true)
                }
                else -> {}
            }
        }
    }

    companion object {
        private const val ACTION_STOP = "com.theglobulus.pacenote.STOP"
        private const val EXTRA_MIC = "mic"
        private const val RECENT_MS = 12 * 60 * 60_000L

        /** Whether the service holds the microphone, so the audio app can listen. */
        @Volatile
        var micActive = false
            private set

        /** @param withMic only from the app on screen: also take the microphone for the car. */
        fun start(context: Context, withMic: Boolean = false) {
            ContextCompat.startForegroundService(
                context, Intent(context, BridgeService::class.java).putExtra(EXTRA_MIC, withMic),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
