package com.theglobulus.claudecar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.theglobulus.claudecar.app

/** Restarts the connection after a reboot or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.app.settings.enabled) BridgeService.start(context)
    }
}
