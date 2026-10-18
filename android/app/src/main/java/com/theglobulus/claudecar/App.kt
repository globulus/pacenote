package com.theglobulus.claudecar

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.theglobulus.claudecar.chat.ChatSender
import com.theglobulus.claudecar.data.ChatRepository
import com.theglobulus.claudecar.data.FileChatStore
import com.theglobulus.claudecar.data.Settings
import com.theglobulus.claudecar.net.BridgeClient
import com.theglobulus.claudecar.notify.ConversationNotifier
import com.theglobulus.claudecar.service.BridgeService
import com.theglobulus.claudecar.voice.AudioFocusHolder
import com.theglobulus.claudecar.voice.SpeechIn
import com.theglobulus.claudecar.voice.SpeechOut
import com.theglobulus.claudecar.voice.ToneCues
import com.theglobulus.claudecar.voice.VoiceController
import com.theglobulus.claudecar.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val main = Handler(Looper.getMainLooper())

    lateinit var settings: Settings private set
    lateinit var repo: ChatRepository private set
    lateinit var client: BridgeClient private set
    lateinit var notifier: ConversationNotifier private set
    lateinit var sender: ChatSender private set

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
