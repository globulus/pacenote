package com.theglobulus.pacenote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.theglobulus.pacenote.R
import com.theglobulus.pacenote.app
import com.theglobulus.pacenote.data.HUB
import com.theglobulus.pacenote.data.Role
import com.theglobulus.pacenote.data.Status

/** One session's car conversation on the phone, for setup and testing. */
class ChatActivity : Activity() {

    private lateinit var sessionId: String
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val onChange: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return finish()
        setContentView(R.layout.activity_chat)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        val message = findViewById<EditText>(R.id.message)

        findViewById<TextView>(R.id.subtitle).text = if (sessionId == HUB) {
            "Say or type: list, open and a number or name, new and a project name."
        } else {
            "What you send here runs in this session on your Mac."
        }
        findViewById<Button>(R.id.show_in_car).setOnClickListener {
            app.repo.addLocal(sessionId, "${app.repo.title(sessionId)}. Reply to talk.", kind = "open")
            app.notifier.showConversation(sessionId, alert = true)
        }
        findViewById<Button>(R.id.send).setOnClickListener {
            val text = message.text.toString().trim()
            if (text.isNotEmpty()) {
                app.sender.send(sessionId, text)
                message.text.clear()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        title = app.repo.title(sessionId)
        app.repo.addListener(onChange)
        app.repo.markRead(sessionId)
        render()
    }

    override fun onPause() {
        app.repo.removeListener(onChange)
        super.onPause()
    }

    private fun render() {
        title = app.repo.title(sessionId)
        log.text = app.repo.messages(sessionId).takeLast(100).joinToString("\n\n") { m ->
            val time = DateFormat.format("HH:mm", m.ts)
            val state = when (m.status) {
                Status.SENDING -> " (sending)"
                Status.FAILED -> " (not delivered)"
                else -> ""
            }
            when (m.role) {
                Role.USER -> "$time  You$state: ${m.text}"
                Role.CLAUDE -> "$time  Claude: ${m.text}"
                Role.SYSTEM -> "$time  · ${m.text}"
            }
        }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                // One task per conversation, so notifications open the right one.
                .setData(android.net.Uri.parse("pacenote://chat/$sessionId"))
    }
}
