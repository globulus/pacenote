package com.theglobulus.claudecar.ui

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
import com.theglobulus.claudecar.R
import com.theglobulus.claudecar.app
import com.theglobulus.claudecar.data.HUB
import com.theglobulus.claudecar.data.HUB_TITLE
import com.theglobulus.claudecar.net.Folder
import com.theglobulus.claudecar.net.PostResult
import com.theglobulus.claudecar.net.RemoteSession
import com.theglobulus.claudecar.service.BridgeService
import com.theglobulus.claudecar.update.Updater
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
            !app.settings.enabled -> "Turn on Stay connected to use Claude Car in the car."
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Allow the microphone so you can talk to Claude in the car."
            !pm.isIgnoringBatteryOptimizations(packageName) ->
                "Tip: tap Battery and allow unrestricted use, or Android may cut the connection."
            else -> "Ready for the car: open Claude Car in Android Auto's apps."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (app.settings.enabled) BridgeService.start(this, withMic = true)
    }

    /** Installs the newest app the Mac has published (scripts/publish-app.sh). */
    private fun update() {
        if (!updater.allowed()) {
            Toast.makeText(this, "Allow Claude Car to install apps, then tap Update app again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val current = updater.installed
        status.text = "Checking for an update…"
        app.scope.launch {
            val found = updater.check()
            withContext(Dispatchers.Main) {
                found.onFailure { status.text = "Couldn't check: ${it.message}" }
                found.onSuccess { release ->
                    if (release == null) {
                        status.text = "Up to date (${current.versionName}, build ${current.versionCode})."
                        return@onSuccess
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
