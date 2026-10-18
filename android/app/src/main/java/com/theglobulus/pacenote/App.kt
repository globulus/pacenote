package com.theglobulus.pacenote

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.theglobulus.pacenote.chat.ChatSender
import com.theglobulus.pacenote.data.ChatRepository
import com.theglobulus.pacenote.data.FileChatStore
import com.theglobulus.pacenote.data.Settings
import com.theglobulus.pacenote.net.BridgeClient
import com.theglobulus.pacenote.notify.ConversationNotifier
import com.theglobulus.pacenote.service.BridgeService
import com.theglobulus.pacenote.voice.AudioFocusHolder
import com.theglobulus.pacenote.voice.SpeechIn
import com.theglobulus.pacenote.voice.SpeechOut
import com.theglobulus.pacenote.voice.ToneCues
import com.theglobulus.pacenote.voice.VoiceController
import com.theglobulus.pacenote.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import com.theglobulus.pacenote.net.SseConnection
import java.io.File

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val main = Handler(Looper.getMainLooper())

    lateinit var settings: Settings private set
    lateinit var repo: ChatRepository private set
    lateinit var client: BridgeClient private set
    lateinit var notifier: ConversationNotifier private set
    lateinit var sender: ChatSender private set

    /** The live connection to the Mac, for the phone screens. */
    val connection = MutableStateFlow(SseConnection.State.STOPPED)
    /** Whether the background service holds the microphone (so the car can listen). */
    val micReady = MutableStateFlow(false)
    /** The conversation open on the phone's screen: its replies need no notification. */
    @Volatile var visibleChat: String? = null

    private val voiceLazy = lazy {
        lateinit var controller: VoiceController
        controller = VoiceController(
            ears = SpeechIn(this) { settings.speechLanguage },
            mouth = SpeechOut(this) { settings.speechLanguage },
            cues = ToneCues(),
            // Speech recognizers can take focus while listening; only a real interruption (a call) stops us.
            focus = AudioFocusHolder(this) { if (controller.state != VoiceState.LISTENING) controller.stop() },
            send = { sessionId, text -> sender.send(sessionId, text, quiet = true) },
            micReady = { BridgeService.micActive },
            autoListen = { settings.autoListen },
            schedule = { delay, block -> main.postDelayed(block, delay) },
        )
        controller
    }

    /** The spoken conversation in the car (the audio app). Main thread only. */
    val voice: VoiceController by voiceLazy

    /** The voice loop if the audio app has started it (no speech engine otherwise). */
    fun voiceOrNull(): VoiceController? = if (voiceLazy.isInitialized()) voice else null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        repo = ChatRepository(FileChatStore(File(filesDir, "chat.json")))
        client = BridgeClient(settings)
        notifier = ConversationNotifier(this, repo).also { it.createChannels() }
        sender = ChatSender(repo, client, notifier, scope)
        sender.onFailure = { id, text ->
            main.post {
                val voice = voiceOrNull()
                if (voice?.handles(id) == true) voice.onSendFailed(id, text)
                else notifier.showConversation(id, alert = true)
            }
        }
    }
}

val Context.app: App get() = applicationContext as App
