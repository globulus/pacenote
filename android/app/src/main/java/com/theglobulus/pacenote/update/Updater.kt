package com.theglobulus.pacenote.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.theglobulus.pacenote.net.BridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Updates the app from the APK the Mac publishes (scripts/publish-app.sh),
 * over the same Tailscale connection, so no cable or adb is needed.
 */
class Updater(private val context: Context, private val client: BridgeClient) {

    data class Release(val versionCode: Long, val versionName: String)

    sealed interface Check {
        data class Newer(val release: Release) : Check
        data object UpToDate : Check
        /** Nothing published on the Mac (scripts/publish-app.sh): the app came from GitHub. */
        data object NotPublished : Check
    }

    val installed: Release
        get() {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return Release(info.longVersionCode, info.versionName ?: "?")
        }

    /** Whether the Mac has published a newer app than this one. */
    suspend fun check(): Result<Check> = withContext(Dispatchers.IO) {
        runCatching {
            client.http.newCall(client.request("/app").get().build()).execute().use { res ->
                when {
                    res.code == 404 -> Check.NotPublished
                    !res.isSuccessful -> error("Mac error ${res.code}")
                    else -> {
                        val o = JSONObject(res.body.string())
                        val release = Release(o.getLong("version_code"), o.optString("version_name", "?"))
                        if (release.versionCode > installed.versionCode) Check.Newer(release) else Check.UpToDate
                    }
                }
            }
        }
    }

    /** Whether Android lets this app install updates (Settings → Install unknown apps). */
    fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Downloads the APK and hands it to Android's installer, which asks to confirm. */
    suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apk = File(context.cacheDir, "update.apk")
            client.http.newCall(client.request("/app/apk").get().build()).execute().use { res ->
                if (!res.isSuccessful) error("Mac error ${res.code}")
                apk.outputStream().use { res.body.byteStream().copyTo(it) }
            }
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                // Once this app installed itself, later updates may not need a tap.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("pacenote.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val done = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallReceiver::class.java),
                    // Mutable: the installer adds the status and the confirm screen.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(done.intentSender)
            }
            apk.delete()
            Unit
        }
    }
}

/** Shows Android's install confirmation, or why the install failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("Updater", "install failed: $why")
                Toast.makeText(context, "Update failed: $why", Toast.LENGTH_LONG).show()
            }
        }
    }
}
