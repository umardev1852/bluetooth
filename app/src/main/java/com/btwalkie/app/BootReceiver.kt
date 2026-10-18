package com.btwalkie.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Starts the "ready to receive calls" service again when:
 * - the phone restarts,
 * - the app is updated,
 * - Bluetooth is switched on,
 * - another phone connects over Bluetooth (so a call attempt can wake this phone up).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val interesting = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == BluetoothDevice.ACTION_ACL_CONNECTED ||
            action == BluetoothAdapter.ACTION_STATE_CHANGED
        if (!interesting) return
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED &&
            intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON
        ) return
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
