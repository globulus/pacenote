package com.theglobulus.pacenote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.theglobulus.pacenote.BuildConfig
import com.theglobulus.pacenote.R
import com.theglobulus.pacenote.app
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.theglobulus.pacenote.data.HUB
import com.theglobulus.pacenote.data.HUB_TITLE
import com.theglobulus.pacenote.data.Pairing
import com.theglobulus.pacenote.net.Folder
import com.theglobulus.pacenote.net.PostResult
import com.theglobulus.pacenote.net.RemoteSession
import com.theglobulus.pacenote.service.BridgeService
import com.theglobulus.pacenote.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup, plus a picker for the sessions on your Mac. Opening it also turns on
 * the microphone for the car (Android only allows that from the app on screen).
 */
class MainActivity : Activity() {

    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var language: EditText
    private val updater by lazy { Updater(this, app.client) }
    private lateinit var status: TextView
    private val adapter = SessionAdapter()
    private var folders: List<Folder> = emptyList()

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val settings = app.settings

        url = findViewById(R.id.bridge_url)
        token = findViewById(R.id.token)
        language = findViewById(R.id.speech_language)
        status = findViewById(R.id.status)
        url.setText(settings.bridgeUrl)
        token.setText(settings.token)
        language.setText(settings.speechLanguage)

        findViewById<Switch>(R.id.connect).apply {
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, on ->
                saveSettings()
                settings.enabled = on
                if (on) BridgeService.start(this@MainActivity, withMic = true) else BridgeService.stop(this@MainActivity)
                showReadiness()
            }
        }
        findViewById<Button>(R.id.test).setOnClickListener {
            saveSettings()
            status.text = "Checking…"
            app.scope.launch {
                val result = app.client.health()
                withContext(Dispatchers.Main) { status.text = result }
            }
        }
        findViewById<Button>(R.id.battery).setOnClickListener { requestUnrestrictedBattery() }
        findViewById<Button>(R.id.update).setOnClickListener { update() }
        findViewById<Button>(R.id.pair).setOnClickListener { scanPairingCode() }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.new_session).setOnClickListener { pickFolderForNewSession() }

        findViewById<ListView>(R.id.sessions).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val s = this@MainActivity.adapter.getItem(position)
                if (s.id != HUB) app.repo.openConversation(s.id, s.title)
                startActivity(ChatActivity.intent(this@MainActivity, s.id))
            }
        }

        val missing = listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
    }

    /** Opened from the pairing QR code with the phone's camera. */
    private fun handlePairingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || intent.data?.scheme != "pacenote") return
        offerPairing(intent.dataString)
        setIntent(Intent(this, MainActivity::class.java)) // don't offer it again on rotation
    }

    /** Scans the QR code that scripts/setup.sh (or scripts/pair.sh) prints on the Mac. */
    private fun scanPairingCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { offerPairing(it.rawValue) }
            .addOnFailureListener { status.text = "Couldn't scan: ${it.message}" }
    }

    private fun offerPairing(link: String?) {
        val pairing = Pairing.parse(link)
        if (pairing == null) {
            // A toast: onResume rewrites the status line right after a link opens the app.
            Toast.makeText(this, "That isn't a Pacenote pairing code. On the Mac, run scripts/pair.sh to show it.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with this Mac?")
            .setMessage("${pairing.host}\n\nPacenote will send what you say to the Claude Code sessions on this Mac.")
            .setPositiveButton("Pair") { _, _ -> pair(pairing) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pair(pairing: Pairing) {
        app.settings.bridgeUrl = pairing.url
        app.settings.token = pairing.token
        app.settings.enabled = true
        url.setText(pairing.url)
        token.setText(pairing.token)
        findViewById<Switch>(R.id.connect).isChecked = true
        // Reconnects to the new address (and takes the microphone, since we're on screen).
        BridgeService.start(this, withMic = true)
        status.text = "Paired. Checking…"
        app.scope.launch {
            val result = app.client.health()
            withContext(Dispatchers.Main) {
                status.text = result
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // On screen: the one moment Android lets the service take the microphone for the car.
        if (app.settings.enabled) BridgeService.start(this, withMic = true)
        showReadiness()
        refresh()
    }

    private fun showReadiness() {
        val pm = getSystemService(PowerManager::class.java)
        status.text = when {
            !app.settings.enabled -> "Turn on Stay connected to use Pacenote in the car."
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Allow the microphone so you can talk to Claude in the car."
            !pm.isIgnoringBatteryOptimizations(packageName) ->
                "Tip: tap Battery and allow unrestricted use, or Android may cut the connection."
            else -> "Ready for the car: open Pacenote in Android Auto's apps."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (app.settings.enabled) BridgeService.start(this, withMic = true)
    }

    /** Installs the newest app the Mac has published (scripts/publish-app.sh). */
    private fun update() {
        if (!updater.allowed()) {
            Toast.makeText(this, "Allow Pacenote to install apps, then tap Update app again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val current = updater.installed
        status.text = "Checking for an update…"
        app.scope.launch {
            val found = updater.check()
            withContext(Dispatchers.Main) {
                found.onFailure { status.text = "Couldn't check: ${it.message}" }
                found.onSuccess { check ->
                    val release = when (check) {
                        is Updater.Check.Newer -> check.release
                        Updater.Check.UpToDate -> {
                            status.text = "Up to date (${current.versionName}, build ${current.versionCode})."
                            return@onSuccess
                        }
                        Updater.Check.NotPublished -> {
                            // Installed from GitHub: updates come from there.
                            status.text = "Your Mac has no app to install, so opening the releases page."
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELEASES_URL)))
                            return@onSuccess
                        }
                    }
                    status.text = "Downloading ${release.versionName} (build ${release.versionCode})…"
                    app.scope.launch {
                        val r = updater.install()
                        withContext(Dispatchers.Main) {
                            status.text = r.fold({ "Installing…" }, { "Update failed: ${it.message}" })
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    private fun saveSettings() {
        app.settings.bridgeUrl = url.text.toString()
        app.settings.token = token.text.toString()
        app.settings.speechLanguage = language.text.toString()
    }

    private fun refresh() {
        saveSettings()
        app.scope.launch {
            val result = app.client.sessions()
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    adapter.sessions = it.sessions
                    folders = it.folders
                }.onFailure {
                    status.text = it.message
                }
            }
        }
    }

    private fun pickFolderForNewSession() {
        if (folders.isEmpty()) {
            Toast.makeText(this, "Refresh first: new sessions start in a folder you've used before.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("New session in…")
            .setItems(folders.map { it.name }.toTypedArray()) { _, i ->
                val folder = folders[i]
                app.scope.launch {
                    val r = app.client.createSession(folder.cwd)
                    withContext(Dispatchers.Main) {
                        val msg = if (r == PostResult.OK) {
                            "Starting a session in ${folder.name}. It shows up in the car when it's ready."
                        } else {
                            "Couldn't start it ($r)."
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun requestUnrestrictedBattery() {
        startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    /** The hub first, then the Mac's sessions, newest first. */
    private inner class SessionAdapter : BaseAdapter() {
        private val hub = RemoteSession(HUB, "$HUB_TITLE (voice commands)", "", "say: list, open <name>, new <folder>", 0, false, false)

        var sessions: List<RemoteSession> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = sessions.size + 1
        override fun getItem(position: Int) = if (position == 0) hub else sessions[position - 1]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val s = getItem(position)
            view.findViewById<TextView>(android.R.id.text1).text = s.title
            view.findViewById<TextView>(android.R.id.text2).text = if (s.id == HUB) s.folder else buildString {
                append(s.folder)
                append(" · ")
                append(DateUtils.getRelativeTimeSpanString(s.updatedAt))
                if (s.running) append(" · working")
                if (s.live) append(" · open on Mac (car uses a copy)")
            }
            return view
        }
    }
}
