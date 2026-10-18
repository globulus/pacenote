package com.theglobulus.pacenote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.theglobulus.pacenote.app
import com.theglobulus.pacenote.data.HUB
import com.theglobulus.pacenote.data.Pairing
import com.theglobulus.pacenote.net.Folder
import com.theglobulus.pacenote.net.PostResult
import com.theglobulus.pacenote.net.RemoteSession
import com.theglobulus.pacenote.service.BridgeService
import com.theglobulus.pacenote.ui.theme.PacenoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What still needs doing on the phone before the car can use Pacenote. */
data class Checklist(val notifications: Boolean = true, val microphone: Boolean = true, val battery: Boolean = true) {
    val done: Boolean get() = notifications && microphone && battery
}

/**
 * The home screen: pairing, setup, and the sessions on your Mac. Opening it also
 * turns on the microphone for the car (Android only allows that from the app on screen).
 */
class MainActivity : ComponentActivity() {

    private var sessions by mutableStateOf<List<RemoteSession>>(emptyList())
    private var folders by mutableStateOf<List<Folder>>(emptyList())
    private var loading by mutableStateOf(false)
    private var loadError by mutableStateOf<String?>(null)
    private var problem by mutableStateOf<String?>(null)
    private var paired by mutableStateOf(false)
    private var checklist by mutableStateOf(Checklist())
    private var showCarTips by mutableStateOf(false)
    private var offered by mutableStateOf<Pairing?>(null)
    private var message by mutableStateOf<String?>(null)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updateChecklist()
        if (app.settings.enabled) BridgeService.start(this, withMic = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PacenoteTheme {
                HomeScreen(
                    state = HomeState(
                        paired = paired,
                        macName = app.settings.macName.ifBlank { hostOf(app.settings.bridgeUrl) },
                        connection = app.connection,
                        micReady = app.micReady,
                        enabled = app.settings.enabled,
                        problem = problem,
                        checklist = checklist,
                        showCarTips = showCarTips,
                        sessions = sessions,
                        folders = folders,
                        loading = loading,
                        loadError = loadError,
                        offered = offered,
                        message = message,
                    ),
                    actions = HomeActions(
                        scan = ::scanPairingCode,
                        confirmPairing = { pair(it) },
                        dismissPairing = { offered = null },
                        refresh = ::refresh,
                        test = ::test,
                        connect = {
                            app.settings.enabled = true
                            BridgeService.start(this, withMic = true)
                        },
                        openSession = { s ->
                            app.repo.openConversation(s.id, s.title)
                            startActivity(ChatActivity.intent(this, s.id, s.folder))
                        },
                        openCommands = { startActivity(ChatActivity.intent(this, HUB)) },
                        newSession = ::startSession,
                        openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        askPermissions = { askPermissions.launch(PERMISSIONS) },
                        openBattery = ::requestUnrestrictedBattery,
                        dismissCarTips = {
                            app.settings.carTipsDismissed = true
                            showCarTips = false
                        },
                        messageShown = { message = null },
                    ),
                )
            }
        }
        if (PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) askPermissions.launch(PERMISSIONS)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        paired = app.settings.paired
        showCarTips = !app.settings.carTipsDismissed
        // On screen: the one moment Android lets the service take the microphone for the car.
        if (paired && app.settings.enabled) BridgeService.start(this, withMic = true)
        updateChecklist()
        if (paired) refresh()
    }

    private fun handleIntent(intent: Intent?) {
        when {
            // Opened from the pairing QR code with the phone's camera.
            intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "pacenote" -> {
                offerPairing(intent.dataString)
                setIntent(Intent(this, MainActivity::class.java)) // don't offer it again on rotation
            }
            intent?.action == ACTION_SCAN -> {
                setIntent(Intent(this, MainActivity::class.java))
                scanPairingCode()
            }
        }
    }

    /** Scans the QR code that scripts/setup.sh (or scripts/pair.sh) prints on the Mac. */
    private fun scanPairingCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { offerPairing(it.rawValue) }
            .addOnFailureListener { message = "Couldn't scan: ${it.message}" }
    }

    private fun offerPairing(link: String?) {
        val pairing = Pairing.parse(link)
        if (pairing == null) {
            message = "That isn't a Pacenote pairing code. On the Mac, run scripts/pair.sh to show it."
            return
        }
        offered = pairing
    }

    private fun pair(pairing: Pairing) {
        offered = null
        app.settings.bridgeUrl = pairing.url
        app.settings.token = pairing.token
        app.settings.macName = pairing.name ?: pairing.host
        app.settings.enabled = true
        paired = true
        // Reconnects to the new address (and takes the microphone, since we're on screen).
        BridgeService.start(this, withMic = true)
        message = "Paired with ${app.settings.macName}."
        refresh()
    }

    private fun refresh() {
        if (!app.settings.paired) return
        loading = true
        app.scope.launch {
            val result = app.client.sessions()
            val health = app.client.healthProblem()
            withContext(Dispatchers.Main) {
                loading = false
                result.onSuccess {
                    sessions = it.sessions
                    folders = it.folders
                    loadError = null
                }.onFailure { loadError = it.message }
                problem = health
            }
        }
    }

    private fun test() {
        app.scope.launch {
            val result = app.client.health()
            withContext(Dispatchers.Main) { message = result }
        }
    }

    private fun startSession(folder: Folder) {
        app.scope.launch {
            val r = app.client.createSession(folder.cwd)
            withContext(Dispatchers.Main) {
                message = if (r == PostResult.OK) {
                    "Starting a session in ${folder.name}. It appears here in a moment."
                } else {
                    "Couldn't start it ($r)."
                }
            }
        }
    }

    private fun updateChecklist() {
        val pm = getSystemService(PowerManager::class.java)
        checklist = Checklist(
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            battery = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("BatteryLife")
    private fun requestUnrestrictedBattery() {
        startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    companion object {
        private const val ACTION_SCAN = "com.theglobulus.pacenote.SCAN"
        private val PERMISSIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)

        /** Opens the home screen and starts scanning a pairing code (from Settings → Pair again). */
        fun scanIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_SCAN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
    }
}
