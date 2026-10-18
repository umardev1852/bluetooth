package com.btwalkie.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Starts the "ready to receive calls" service again after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!CallHub.isEnabled(context)) return
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).setAction(CallService.ACTION_START)
            )
        } catch (e: Exception) {
            // The system did not allow it; the app will start listening next time it is opened.
        }
    }
}
