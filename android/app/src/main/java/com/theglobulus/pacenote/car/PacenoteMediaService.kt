package com.theglobulus.pacenote.car

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.format.DateUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.MediaBrowserServiceCompat
import com.theglobulus.pacenote.R
import com.theglobulus.pacenote.app
import com.theglobulus.pacenote.data.HUB
import com.theglobulus.pacenote.data.Role
import com.theglobulus.pacenote.net.PostResult
import com.theglobulus.pacenote.net.RemoteSession
import com.theglobulus.pacenote.ui.MainActivity
import com.theglobulus.pacenote.voice.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pacenote as an Android Auto audio app. The car lists your sessions (and
 * folders for new ones); picking one starts a spoken conversation run by
 * [com.theglobulus.pacenote.voice.VoiceController]. Play (or the steering
 * wheel's next button) talks, previous repeats Claude's last reply, pause stops.
 *
 * Android Auto lists sideloaded audio apps once Unknown sources is on, and the
 * whole loop uses the phone's own speech recognition, not Gemini.
 */
class PacenoteMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val main = Handler(Looper.getMainLooper())
    private val voice get() = app.voice
    private var sessionsById = mapOf<String, RemoteSession>()
    private var queueTexts = listOf<String>()
    private var art: Bitmap? = null

    private val voiceListener = { update() }
    private val repoListener = { main.post { updateQueue() }; Unit }

    override fun onCreate() {
        super.onCreate()
        art = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.toBitmap(192, 192)
        session = MediaSessionCompat(this, "Pacenote").apply {
            setCallback(callback)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PacenoteMediaService, 0, Intent(this@PacenoteMediaService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            isActive = true
        }
        sessionToken = session.sessionToken
        voice.addListener(voiceListener)
        app.repo.addListener(repoListener)
        update()
        updateQueue()
    }

    override fun onDestroy() {
        voice.removeListener(voiceListener)
        app.repo.removeListener(repoListener)
        voice.close()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Session titles are private: only Android Auto, the system and this app may browse.
        if (clientUid != Process.SYSTEM_UID && clientUid != Process.myUid() && clientPackageName !in TRUSTED) {
            return BrowserRoot(EMPTY, null)
        }
        return BrowserRoot(
            ROOT,
            Bundle().apply {
                putBoolean(CONTENT_STYLE_SUPPORTED, true)
                putInt(CONTENT_STYLE_BROWSABLE, STYLE_LIST)
                putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
            },
        )
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        when (parentId) {
            // Two tabs in the car.
            ROOT -> result.sendResult(
                mutableListOf(
                    browsable(SESSIONS, "Sessions", R.drawable.ic_list),
                    browsable(NEW, "New session", R.drawable.ic_add),
                )
            )
            SESSIONS, NEW -> {
                result.detach()
                app.scope.launch {
                    val list = app.client.sessions()
                    val items = list.fold(
                        onSuccess = { l ->
                            sessionsById = l.sessions.associateBy { it.id }
                            if (parentId == SESSIONS) {
                                l.sessions.take(MAX_ITEMS).map { s -> playable("$SESSION_PREFIX${s.id}", s.title, describe(s)) }
                            } else {
                                l.folders.take(MAX_ITEMS).map { f -> playable("$FOLDER_PREFIX${f.cwd}", f.name, f.cwd.replace(HOME_RE, "~")) }
                            }
                        },
                        onFailure = { listOf(playable(RETRY, "Can't reach your Mac", it.message ?: "Tap to try again")) },
                    )
                    withContext(Dispatchers.Main) { result.sendResult(items.toMutableList()) }
                }
            }
            else -> result.sendResult(mutableListOf())
        }
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            when {
                mediaId.startsWith(SESSION_PREFIX) -> openSession(mediaId.removePrefix(SESSION_PREFIX))
                mediaId.startsWith(FOLDER_PREFIX) -> createIn(mediaId.removePrefix(FOLDER_PREFIX))
                mediaId == RETRY -> notifyChildrenChanged(SESSIONS)
            }
        }

        override fun onPlay() {
            if (voice.sessionId != null) voice.talk() else openMostRecent()
        }

        override fun onPause() = voice.stop()
        override fun onStop() = voice.stop()
        override fun onSkipToNext() = voice.talk()
        override fun onSkipToPrevious() = voice.repeat()

        override fun onSkipToQueueItem(id: Long) {
            queueTexts.getOrNull(id.toInt())?.let { voice.read(it) }
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_TALK -> onPlay()
                ACTION_REPEAT -> voice.repeat()
                ACTION_STOP_CLAUDE -> voice.cancelTurn()
            }
        }
    }

    private fun openSession(id: String) {
        val s = sessionsById[id]
        val title = s?.title ?: app.repo.title(id)
        app.repo.openConversation(id, title)
        app.repo.markRead(id)
        val lastReply = app.repo.messages(id).takeLastWhile { it.role != Role.USER }
            .filter { it.role == Role.CLAUDE }.map { it.text }
        voice.open(id, title, running = s?.running == true, lastReply = lastReply)
    }

    private fun openMostRecent() {
        val recent = app.repo.conversations().firstOrNull { it.sessionId != HUB }
        if (recent != null) openSession(recent.sessionId) else voice.read("Pick a session from the list first.")
    }

    private fun createIn(cwd: String) {
        val folder = cwd.substringAfterLast('/')
        voice.create(folder)
        app.scope.launch {
            val r = app.client.createSession(cwd)
            if (r != PostResult.OK) withContext(Dispatchers.Main) { voice.read("I couldn't start a session in $folder.") }
        }
    }

    /** Car screen: the play button and spinner follow the conversation; the title is the session. */
    private fun update() {
        val v = voice
        Log.d("PacenoteVoice", "${v.state}: ${v.line}")
        val state = when (v.state) {
            VoiceState.IDLE -> PlaybackStateCompat.STATE_PAUSED
            VoiceState.SPEAKING, VoiceState.LISTENING -> PlaybackStateCompat.STATE_PLAYING
            VoiceState.WAITING -> PlaybackStateCompat.STATE_BUFFERING
        }
        val playback = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .addCustomAction(ACTION_TALK, "Talk", R.drawable.ic_mic)
            .addCustomAction(ACTION_REPEAT, "Repeat", R.drawable.ic_replay)
            .apply { if (v.turnRunning) addCustomAction(ACTION_STOP_CLAUDE, "Stop Claude", R.drawable.ic_stop) }
            // A non-fatal error: Android Auto shows it without leaving the player.
            .apply { v.problem?.let { setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it) } }
            .setActiveQueueItemId(queueTexts.size.toLong() - 1)
            .build()
        session.setPlaybackState(playback)

        val title = v.title.ifEmpty { "Pacenote" }
        val folder = v.sessionId?.let { sessionsById[it]?.folder }.orEmpty()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, v.sessionId ?: "none")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, v.line.ifEmpty { "Pick a session to talk" })
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, v.line.ifEmpty { "Pick a session to talk" })
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, folder)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .build()
        )
        updateQueue()
    }

    /**
     * The conversation as the player's queue, a sentence or so per row, so the
     * whole reply can be read on the car screen (when parked); tap a row to hear it.
     */
    private fun updateQueue() {
        val id = voice.sessionId
        if (id == null) {
            if (queueTexts.isNotEmpty()) session.setQueue(null)
            queueTexts = emptyList()
            return
        }
        val rows = app.repo.messages(id).filter { it.text.isNotBlank() }.takeLast(QUEUE_MESSAGES).flatMap { m ->
            val who = when (m.role) {
                Role.USER -> "You"
                Role.CLAUDE -> "Claude"
                Role.SYSTEM -> "Pacenote"
            }
            QueueRows.split(m.text).map { who to it }
        }
        val texts = rows.map { it.second }
        if (texts == queueTexts) return
        queueTexts = texts
        session.setQueueTitle(voice.title)
        session.setQueue(
            rows.mapIndexed { i, (who, text) ->
                MediaSessionCompat.QueueItem(
                    MediaDescriptionCompat.Builder().setMediaId("q:$i").setTitle(text).setSubtitle(who).build(),
                    i.toLong(),
                )
            }
        )
    }

    private fun describe(s: RemoteSession): String = buildString {
        append(s.folder)
        append(" · ")
        append(DateUtils.getRelativeTimeSpanString(s.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        if (s.running) append(" · working")
    }

    private fun browsable(id: String, title: String, icon: Int) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setIconUri(resourceUri(icon)).build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private fun playable(id: String, title: String, subtitle: String) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaItem.FLAG_PLAYABLE,
    )

    private fun resourceUri(res: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(packageName)
        .appendPath(resources.getResourceTypeName(res))
        .appendPath(resources.getResourceEntryName(res))
        .build()

    companion object {
        private const val ROOT = "root"
        private const val EMPTY = "empty"
        private const val SESSIONS = "sessions"
        private const val NEW = "new"
        private const val RETRY = "retry"
        private const val SESSION_PREFIX = "s:"
        private const val FOLDER_PREFIX = "f:"
        private const val MAX_ITEMS = 20
        private const val QUEUE_MESSAGES = 12

        private const val ACTION_TALK = "talk"
        private const val ACTION_REPEAT = "repeat"
        private const val ACTION_STOP_CLAUDE = "stop_claude"

        // Android Auto's content-style hints: plain lists rather than grids.
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_LIST = 1

        private val HOME_RE = Regex("^/Users/[^/]+")

        private val TRUSTED = setOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
            "com.android.systemui",
        )
    }
}

/** Splits a message into rows short enough for one line each on a car screen. */
object QueueRows {
    fun split(text: String, max: Int = 80): List<String> {
        val rows = mutableListOf<String>()
        for (sentence in text.trim().split(Regex("(?<=[.!?])\\s+|\\n+"))) {
            var rest = sentence.trim()
            while (rest.length > max) {
                val cut = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
                rows += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) rows += rest
        }
        return rows
    }
}
