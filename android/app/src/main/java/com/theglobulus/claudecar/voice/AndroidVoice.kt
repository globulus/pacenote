package com.theglobulus.claudecar.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ClaudeCarVoice"

/** Played through the car like any media app's audio. */
val SPEECH_AUDIO: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** Android's text to speech. */
class SpeechOut(context: Context, private val language: () -> String) : Mouth {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val pending = mutableListOf<Pair<String, () -> Unit>>()
    private var ready: Boolean? = null // null until the engine has started
    private var languageSet: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready == true) {
                tts.setAudioAttributes(SPEECH_AUDIO)
                tts.setOnUtteranceProgressListener(progress)
            } else {
                Log.w(TAG, "text to speech failed to start: $status")
            }
            pending.toList().also { pending.clear() }.forEach { (text, done) -> speak(text, done) }
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            Log.d(TAG, "speaking $utteranceId")
        }
        override fun onDone(utteranceId: String) = finished(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = finished(utteranceId)
        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "text to speech error $errorCode")
            finished(utteranceId)
        }
        // Stopped on purpose: no callback (see Mouth).
        override fun onStop(utteranceId: String, interrupted: Boolean) {
            callbacks.remove(utteranceId)
        }
    }

    private fun finished(id: String) {
        callbacks.remove(id)?.let { main.post(it) }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        when (ready) {
            null -> pending += text to onDone
            false -> main.post(onDone) // no voice: move on silently
            true -> {
                val lang = language()
                if (lang != languageSet) {
                    tts.language = Locale.forLanguageTag(lang)
                    languageSet = lang
                }
                val id = UUID.randomUUID().toString()
                callbacks[id] = onDone
                val r = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
                Log.d(TAG, "speak $id (${text.length} chars) -> $r")
                if (r != TextToSpeech.SUCCESS) return finished(id)
                // If the speech engine dies mid-sentence, no callback ever comes: move on anyway.
                main.postDelayed({
                    if (callbacks.containsKey(id)) {
                        Log.w(TAG, "no word from text to speech for $id; moving on")
                        finished(id)
                    }
                }, 5_000L + text.length * 120L)
            }
        }
    }

    override fun stop() {
        pending.clear()
        callbacks.clear()
        if (ready == true) tts.stop()
    }
}

/**
 * Android's speech recognizer. Prefers the phone's default recognition
 * service; falls back to on-device recognition if that one keeps failing.
 * Needs the microphone, which in the background means [com.theglobulus.claudecar.service.BridgeService]
 * must be running as a microphone foreground service.
 */
class SpeechIn(private val context: Context, private val language: () -> String) : Ears {
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false

    override fun listen(onHeard: (String?) -> Unit, onError: (EarError) -> Unit) {
        cancel()
        val r = create() ?: return onError(EarError.NO_RECOGNIZER)
        recognizer = r
        var finished = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                if (finished) return
                finished = true
                release()
                onHeard(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                release()
                Log.i(TAG, "recognizer error $error (on device: $onDevice)")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onHeard(null)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError(EarError.NO_PERMISSION)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> onError(EarError.NETWORK)
                    else -> {
                        // The default service can't do it (busy, language, disconnected): try on-device next time.
                        if (!onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) onDevice = true
                        onError(EarError.OTHER)
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "listening (on device: $onDevice)")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Give a driver room to pause mid-sentence.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        )
    }

    private fun create(): SpeechRecognizer? = when {
        onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> {
            onDevice = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        else -> null
    }

    override fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}

class ToneCues : Cues {
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

    override fun listening() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    override fun heard() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
    }
}

/** Holds audio focus while Claude Car is talking or listening, so music pauses. */
class AudioFocusHolder(context: Context, private val onLost: () -> Unit) : Focus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var held = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(SPEECH_AUDIO)
        .setOnAudioFocusChangeListener { change ->
            // A call or another app took over. Ducking (navigation prompts) is fine.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                held = change != AudioManager.AUDIOFOCUS_LOSS && held
                onLost()
            }
        }
        .build()

    override fun acquire() {
        if (!held) held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun release() {
        if (held) audio.abandonAudioFocusRequest(request)
        held = false
    }
}
