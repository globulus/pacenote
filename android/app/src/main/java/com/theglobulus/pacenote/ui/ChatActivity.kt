package com.theglobulus.pacenote.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theglobulus.pacenote.R
import com.theglobulus.pacenote.app
import com.theglobulus.pacenote.data.ChatMessage
import com.theglobulus.pacenote.data.HUB
import com.theglobulus.pacenote.data.Role
import com.theglobulus.pacenote.data.Status
import com.theglobulus.pacenote.ui.theme.PacenoteTheme
import kotlinx.coroutines.launch

/** One session's conversation on the phone: read it, or type to it when you're not driving. */
class ChatActivity : ComponentActivity() {

    private lateinit var sessionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return finish()
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        setContent {
            PacenoteTheme {
                ChatScreen(
                    sessionId = sessionId,
                    subtitle = if (sessionId == HUB) "Voice commands" else folder,
                    back = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.visibleChat = sessionId
        app.repo.markRead(sessionId)
    }

    override fun onPause() {
        if (app.visibleChat == sessionId) app.visibleChat = null
        super.onPause()
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_FOLDER = "folder"

        fun intent(context: Context, sessionId: String, folder: String? = null): Intent =
            Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_FOLDER, folder)
                // One task per conversation, so notifications open the right one.
                .setData(Uri.parse("pacenote://chat/$sessionId"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(sessionId: String, subtitle: String?, back: () -> Unit) {
    val repo = app().repo
    var messages by remember { mutableStateOf(repo.messages(sessionId)) }
    var title by remember { mutableStateOf(repo.title(sessionId)) }
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(sessionId) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener: () -> Unit = {
            main.post {
                messages = repo.messages(sessionId)
                title = repo.title(sessionId)
                // Only when needed: marking read notifies listeners again.
                if (repo.unreadIncoming(sessionId).isNotEmpty()) repo.markRead(sessionId)
            }
        }
        repo.addListener(listener)
        onDispose { repo.removeListener(listener) }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    IconButton(onClick = {
                        app(ctx).repo.addLocal(sessionId, "${repo.title(sessionId)}. Reply to talk.", kind = "open")
                        app(ctx).notifier.showConversation(sessionId, alert = true)
                        scope.launch { snackbar.showSnackbar("Posted to Android Auto as a message conversation.") }
                    }) { Icon(painterResource(R.drawable.ic_car), "Show in car") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text(if (sessionId == HUB) "list, open 2, new payments…" else "Message Claude") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    FilledIconButton(
                        onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                app(ctx).sender.send(sessionId, text)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    if (sessionId == HUB) {
                        "Commands for the car: say list to hear your sessions, open and a number or name, or new and a project name."
                    } else {
                        "What you send here runs in this session on your Mac. In the car, open Pacenote from Android Auto's apps."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(messages, key = { it.id }) { Bubble(it) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val time = DateFormat.format("HH:mm", m.ts).toString()
    when (m.role) {
        Role.SYSTEM -> Text(
            m.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 2.dp),
        )
        Role.USER, Role.CLAUDE -> {
            val mine = m.role == Role.USER
            Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Surface(
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(
                            topStart = 20.dp, topEnd = 20.dp,
                            bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp,
                        ),
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    val note = when (m.status) {
                        Status.SENDING -> "Sending…"
                        Status.FAILED -> "Not delivered"
                        else -> time
                    }
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (m.status == Status.FAILED) {
                            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun app() = app(androidx.compose.ui.platform.LocalContext.current)

private fun app(context: Context) = context.applicationContext as com.theglobulus.pacenote.App
