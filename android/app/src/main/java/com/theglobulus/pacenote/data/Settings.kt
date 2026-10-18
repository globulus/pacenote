package com.theglobulus.pacenote.data

import android.content.Context
import androidx.core.content.edit
import com.theglobulus.pacenote.BuildConfig

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var bridgeUrl: String
        get() = prefs.getString("bridge_url", null)?.ifBlank { null } ?: BuildConfig.DEFAULT_SERVICE_URL
        set(value) = prefs.edit { putString("bridge_url", value.trim().trimEnd('/')) }

    var token: String
        get() = prefs.getString("token", null)?.ifBlank { null } ?: BuildConfig.DEFAULT_TOKEN
        set(value) = prefs.edit { putString("token", value.trim()) }

    /** What the car's speech recognition listens for and replies are read in (BCP 47, e.g. en-US). */
    var speechLanguage: String
        get() = prefs.getString("speech_language", null)?.ifBlank { null } ?: "en-US"
        set(value) = prefs.edit { putString("speech_language", value.trim()) }

    /** Listen for a follow-up as soon as Claude has finished. */
    var autoListen: Boolean
        get() = prefs.getBoolean("auto_listen", true)
        set(value) = prefs.edit { putBoolean("auto_listen", value) }

    /** Whether the background connection should run (and restart after reboot). */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit { putBoolean("enabled", value) }
}
