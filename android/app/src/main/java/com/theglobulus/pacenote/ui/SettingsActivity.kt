package com.theglobulus.pacenote.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theglobulus.pacenote.BuildConfig
import com.theglobulus.pacenote.app
import com.theglobulus.pacenote.service.BridgeService
import com.theglobulus.pacenote.ui.theme.PacenoteTheme
import com.theglobulus.pacenote.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything you rarely need: the Mac, voice, battery, updates, and the raw address and token. */
class SettingsActivity : ComponentActivity() {

    private val updater by lazy { Updater(this, app.client) }
    private var message by mutableStateOf<String?>(null)
    private var batteryOk by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PacenoteTheme { Settings() } }
    }

    override fun onResume() {
        super.onResume()
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Settings() {
        val settings = app.settings
        var enabled by remember { mutableStateOf(settings.enabled) }
        var autoListen by remember { mutableStateOf(settings.autoListen) }
        var language by remember { mutableStateOf(settings.speechLanguage) }
        var url by remember { mutableStateOf(settings.bridgeUrl) }
        var token by remember { mutableStateOf(settings.token) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                message = null
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            ) {
                item {
                    Section("Mac") {
                        Row(
                            title = settings.macName.ifBlank { "Not paired" },
                            detail = settings.bridgeUrl.ifBlank { "Scan the QR code from scripts/setup.sh" },
                        ) {
                            TextButton(onClick = { startActivity(MainActivity.scanIntent(this@SettingsActivity)) }) {
                                Text(if (settings.paired) "Pair again" else "Pair")
                            }
                        }
                        Divider()
                        Row("Stay connected", "Keeps the connection to your Mac open, so replies reach the car.") {
                            Switch(checked = enabled, onCheckedChange = {
                                enabled = it
                                settings.enabled = it
                                if (it) BridgeService.start(this@SettingsActivity, withMic = true) else BridgeService.stop(this@SettingsActivity)
                            })
                        }
                        Divider()
                        Row("Test connection", null) {
                            TextButton(onClick = ::test) { Text("Test") }
                        }
                    }
                }
                item {
                    Section("Voice in the car") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            OutlinedTextField(
                                value = language,
                                onValueChange = {
                                    language = it
                                    settings.speechLanguage = it
                                },
                                label = { Text("Speech language") },
                                supportingText = { Text("What you speak and replies are read in, e.g. en-US, en-GB, de-DE") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Divider()
                        Row("Listen for a follow-up", "After each reply, listen once more. Silence ends it.") {
                            Switch(checked = autoListen, onCheckedChange = {
                                autoListen = it
                                settings.autoListen = it
                            })
                        }
                    }
                }
                item {
                    Section("Phone") {
                        Row(
                            "Battery",
                            if (batteryOk) "Unrestricted: the connection survives the screen being off." else "Restricted: Android may cut the connection while you drive.",
                        ) {
                            if (!batteryOk) OutlinedButton(onClick = ::openBattery) { Text("Open") }
                        }
                        Divider()
                        Row("Notifications", "Message conversations in Android Auto, and the connection status.") {
                            TextButton(onClick = {
                                startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
                            }) { Text("Open") }
                        }
                    }
                }
                item {
                    Section("App") {
                        val installed = updater.installed
                        Row("Pacenote ${installed.versionName}", "Build ${installed.versionCode}") {
                            TextButton(onClick = ::update) { Text("Update") }
                        }
                        Divider()
                        Row("Source code and releases", "github.com/globulus/pacenote") {
                            TextButton(onClick = {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELEASES_URL)))
                            }) { Text("Open") }
                        }
                    }
                }
                item {
                    Section("Advanced") {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Set by pairing. Change these only if you know you need to.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = url, onValueChange = { url = it },
                                label = { Text("Mac address") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = token, onValueChange = { token = it },
                                label = { Text("Token") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                settings.bridgeUrl = url
                                settings.token = token
                                if (settings.enabled) BridgeService.start(this@SettingsActivity, withMic = true)
                                message = "Saved."
                            }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Text(
            title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.large,
        ) { content() }
    }

    @Composable
    private fun Row(title: String, detail: String?, trailing: @Composable () -> Unit) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = detail?.let { { Text(it) } },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable
    private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

    private fun test() {
        app.scope.launch {
            val result = app.client.health()
            withContext(Dispatchers.Main) { message = result }
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBattery() {
        startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    /** Installs the newest app the Mac has published, or opens GitHub Releases. */
    private fun update() {
        message = "Checking for an update…"
        app.scope.launch {
            val found = updater.check()
            withContext(Dispatchers.Main) {
                found.onFailure { message = "Couldn't check: ${it.message}" }
                found.onSuccess { check ->
                    when (check) {
                        Updater.Check.UpToDate -> message = "Pacenote is up to date."
                        Updater.Check.NotPublished -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELEASES_URL)))
                        is Updater.Check.Newer -> {
                            if (!updater.allowed()) {
                                message = "Allow Pacenote to install apps, then tap Update again."
                                startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                                return@onSuccess
                            }
                            message = "Downloading ${check.release.versionName}…"
                            app.scope.launch {
                                val r = updater.install()
                                withContext(Dispatchers.Main) {
                                    message = r.fold({ "Installing…" }, { "Update failed: ${it.message}" })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
