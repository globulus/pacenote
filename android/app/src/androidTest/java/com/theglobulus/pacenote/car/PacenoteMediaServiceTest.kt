package com.theglobulus.pacenote.car

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connects to the audio app like Android Auto: browse the tabs, list sessions
 * from the Mac, open one, and watch the player. Needs the Mac service reachable.
 * Opening a session sends nothing to it; only speech would.
 */
@RunWith(AndroidJUnit4::class)
class PacenoteMediaServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun browseAndOpenASession() {
        // As in real life: the app was opened once, which turns on the microphone.
        context.startActivity(
            android.content.Intent(context, com.theglobulus.pacenote.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(3_000)
        Log.i(TAG, "microphone ready: ${com.theglobulus.pacenote.service.BridgeService.micActive}")
        // Then leave it, as when driving: the app is in the background.
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(3_000)
        val connected = CountDownLatch(1)
        lateinit var browser: MediaBrowserCompat
        main.post {
            browser = MediaBrowserCompat(
                context, ComponentName(context, PacenoteMediaService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                }, null,
            )
            browser.connect()
        }
        assertTrue("connects", connected.await(10, TimeUnit.SECONDS))
        assertEquals("root", browser.root)

        val tabs = children(browser, "root")
        assertEquals(listOf("Sessions", "New session"), tabs.map { it.description.title.toString() })
        val sessions = children(browser, "sessions")
        sessions.forEach { Log.i(TAG, "session: ${it.description.title} | ${it.description.subtitle}") }
        assertTrue("lists sessions from the Mac", sessions.isNotEmpty() && sessions[0].isPlayable)
        val folders = children(browser, "new")
        folders.forEach { Log.i(TAG, "folder: ${it.description.title} | ${it.description.subtitle}") }

        val controller = MediaControllerCompat(context, browser.sessionToken)
        val states = mutableListOf<String>()
        val callback = object : MediaControllerCompat.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                val line = controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
                states += "${state?.state}:$line${state?.errorMessage?.let { " !$it" } ?: ""}"
            }
        }
        main.post { controller.registerCallback(callback, main) }
        val target = sessions[0]
        main.post { controller.transportControls.playFromMediaId(target.mediaId, null) }
        Thread.sleep(40_000) // speak the name, then listen (or explain why it can't)
        main.post { controller.transportControls.pause() }
        Thread.sleep(1_000)
        states.forEach { Log.i(TAG, "state: $it") }
        val title = controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        Log.i(TAG, "title: $title")
        assertEquals(target.description.title.toString(), title)
        assertTrue("played", states.any { it.startsWith("${PlaybackStateCompat.STATE_PLAYING}:") })
        assertEquals(PlaybackStateCompat.STATE_PAUSED, controller.playbackState.state)
        main.post { browser.disconnect() }
    }

    private fun children(browser: MediaBrowserCompat, id: String): List<MediaBrowserCompat.MediaItem> {
        val done = CountDownLatch(1)
        var items = emptyList<MediaBrowserCompat.MediaItem>()
        main.post {
            browser.subscribe(id, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                    items = children
                    browser.unsubscribe(id)
                    done.countDown()
                }
            })
        }
        assertTrue("loads $id", done.await(20, TimeUnit.SECONDS))
        return items
    }

    private companion object {
        const val TAG = "PacenoteMediaServiceTest"
    }
}
