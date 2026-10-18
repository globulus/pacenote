package com.theglobulus.pacenote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theglobulus.pacenote.R
import com.theglobulus.pacenote.data.Pairing
import com.theglobulus.pacenote.net.Folder
import com.theglobulus.pacenote.net.RemoteSession
import com.theglobulus.pacenote.net.SseConnection
import com.theglobulus.pacenote.ui.theme.Amber
import com.theglobulus.pacenote.ui.theme.Bad
import com.theglobulus.pacenote.ui.theme.Good
import kotlinx.coroutines.flow.StateFlow

data class HomeState(
    val paired: Boolean,
    val macName: String,
    val connection: StateFlow<SseConnection.State>,
    val micReady: StateFlow<Boolean>,
    val enabled: Boolean,
    /** Something wrong at the Mac, e.g. macOS blocking ~/Documents. */
    val problem: String?,
    val checklist: Checklist,
    val showCarTips: Boolean,
    val sessions: List<RemoteSession>,
    val folders: List<Folder>,
    val loading: Boolean,
    val loadError: String?,
    val offered: Pairing?,
    val message: String?,
)

class HomeActions(
    val scan: () -> Unit,
    val confirmPairing: (Pairing) -> Unit,
    val dismissPairing: () -> Unit,
    val refresh: () -> Unit,
    val test: () -> Unit,
    val connect: () -> Unit,
    val openSession: (RemoteSession) -> Unit,
    val openCommands: () -> Unit,
    val newSession: (Folder) -> Unit,
    val openSettings: () -> Unit,
    val askPermissions: () -> Unit,
    val openBattery: () -> Unit,
    val dismissCarTips: () -> Unit,
    val messageShown: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val snackbar = remember { SnackbarHostState() }
    var pickFolder by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.messageShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_stat_claude), contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Pacenote", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (state.paired) {
                        IconButton(onClick = actions.refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    }
                    IconButton(onClick = actions.openSettings) { Icon(Icons.Default.Settings, "Settings") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Voice commands chat") }, onClick = { menu = false; actions.openCommands() })
                            DropdownMenuItem(text = { Text("Pair with a Mac") }, onClick = { menu = false; actions.scan() })
                            DropdownMenuItem(text = { Text("Test connection") }, onClick = { menu = false; actions.test() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.paired && state.folders.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { pickFolder = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New session") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = actions.refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { if (state.paired) ConnectionCard(state, actions) else PairCard(actions.scan) }
                if (!state.checklist.done) item { ChecklistCard(state.checklist, actions) }
                if (state.paired && state.showCarTips) item { CarTipsCard(actions.dismissCarTips) }
                if (state.paired) {
                    item {
                        Text(
                            "Sessions", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    when {
                        state.loadError != null && state.sessions.isEmpty() -> item {
                            Notice(state.loadError, action = "Try again", onAction = actions.refresh)
                        }
                        state.sessions.isEmpty() && !state.loading -> item {
                            Notice("No sessions yet. Start Claude Code in a project on your Mac, then pull to refresh.")
                        }
                        else -> items(state.sessions, key = { it.id }) { SessionCard(it) { actions.openSession(it) } }
                    }
                }
            }
        }
    }

    state.offered?.let { pairing ->
        AlertDialog(
            onDismissRequest = actions.dismissPairing,
            icon = { Icon(painterResource(R.drawable.ic_qr), null) },
            title = { Text("Pair with ${pairing.name ?: pairing.host}?") },
            text = {
                Text("Pacenote will send what you say to the Claude Code sessions on this Mac (${pairing.host}).")
            },
            confirmButton = { Button(onClick = { actions.confirmPairing(pairing) }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = actions.dismissPairing) { Text("Cancel") } },
        )
    }

    if (pickFolder) {
        ModalBottomSheet(onDismissRequest = { pickFolder = false }) {
            Text(
                "New session in…", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(state.folders, key = { it.cwd }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        supportingContent = {
                            Text(f.cwd.replace(Regex("^/Users/[^/]+"), "~"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            pickFolder = false
                            actions.newSession(f)
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PairCard(scan: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(24.dp)) {
            Icon(
                painterResource(R.drawable.ic_qr), null, modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(16.dp))
            Text("Pair with your Mac", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "On your Mac, run scripts/setup.sh from the Pacenote repo. It ends with a QR code: scan it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = scan, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_qr), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code")
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: HomeState, actions: HomeActions) {
    val connection by state.connection.collectAsState()
    val mic by state.micReady.collectAsState()
    val (color, title, detail) = when {
        !state.enabled -> Triple(MaterialTheme.colorScheme.outline, "Not connected", "Pacenote is switched off.")
        connection == SseConnection.State.CONNECTED -> Triple(Good, "Connected", null)
        connection == SseConnection.State.AUTH_ERROR -> Triple(Bad, "Wrong token", "Pair again with the QR code from scripts/pair.sh.")
        connection == SseConnection.State.RECONNECTING -> Triple(Bad, "Can't reach your Mac", "Is Tailscale on, here and on the Mac? Is the Mac awake?")
        else -> Triple(Amber, "Connecting…", null)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                state.macName.ifBlank { "Your Mac" }, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.problem?.let {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    icon = R.drawable.ic_mic,
                    text = if (mic) "Microphone ready" else "Microphone off",
                    on = mic,
                )
                Spacer(Modifier.weight(1f))
                if (!state.enabled) {
                    Button(onClick = actions.connect) { Text("Connect") }
                } else {
                    TextButton(onClick = actions.test) { Text("Test") }
                }
            }
        }
    }
}

@Composable
private fun Pill(icon: Int, text: String, on: Boolean) {
    val fg = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, tint = if (on) Good else fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

@Composable
private fun ChecklistCard(checklist: Checklist, actions: HomeActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Finish setting up", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (!checklist.microphone || !checklist.notifications) {
                val what = listOfNotNull(
                    "the microphone".takeIf { !checklist.microphone },
                    "notifications".takeIf { !checklist.notifications },
                ).joinToString(" and ")
                ChecklistRow("Allow $what", "So you can talk in the car and hear replies.", "Allow", actions.askPermissions)
            }
            if (!checklist.battery) {
                ChecklistRow(
                    "Unrestricted battery use", "Otherwise Android may cut the connection while you drive.",
                    "Open", actions.openBattery,
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(title: String, detail: String, action: String, onAction: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { OutlinedButton(onClick = onAction) { Text(action) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun CarTipsCard(dismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_car), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Using it in the car", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            listOf(
                "In Android Auto's settings, tap Version and permission info 10 times, then turn on Unknown sources in Developer settings.",
                "Under Customize launcher, tick Pacenote.",
                "In the car, open Pacenote, pick a session and talk after the beep.",
            ).forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss) { Text("Got it") }
            }
        }
    }
}

@Composable
private fun SessionCard(s: RemoteSession, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${s.folder} · ${DateUtils.getRelativeTimeSpanString(s.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (s.running || s.live) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.running) Tag("Working", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                        if (s.live) Tag("Open on Mac", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = CircleShape) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
