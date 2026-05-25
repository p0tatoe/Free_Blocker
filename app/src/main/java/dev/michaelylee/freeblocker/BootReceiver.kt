package dev.michaelylee.freeblocker.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import dev.michaelylee.freeblocker.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receives ACTION_BOOT_COMPLETED and starts [MyVpnService] if the user had
 * "Start on boot" enabled.
 *
 * Requires in AndroidManifest.xml:
 *   1. Permission:
 *      <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 *   2. Receiver registration inside <application>:
 *      <receiver
 *          android:name=".core.BootReceiver"
 *          android:exported="true">
 *          <intent-filter>
 *              <action android:name="android.intent.action.BOOT_COMPLETED" />
 *          </intent-filter>
 *      </receiver>
 *
 * NOTE: VPN permission must have been granted by the user at least once before
 * boot autostart will work. VpnService.prepare() returns null (already granted)
 * in that case. If it returns a non-null intent, the user hasn't granted
 * permission yet and we can't show UI from a BroadcastReceiver, so we skip.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — checking start-on-boot preference")

        // BroadcastReceivers must not do async work after onReceive returns,
        // so we use goAsync() to keep the receiver alive while we read prefs.
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = UserPreferences(context)
                val startOnBoot = prefs.isStartOnBootFlow.first()

                if (!startOnBoot) {
                    Log.d(TAG, "Start-on-boot disabled — skipping")
                    return@launch
                }

                // Check VPN permission — can't prompt from a BroadcastReceiver
                val permissionIntent = VpnService.prepare(context)
                if (permissionIntent != null) {
                    Log.w(TAG, "VPN permission not yet granted — skipping boot start")
                    return@launch
                }

                Log.i(TAG, "Starting VPN service on boot")
                val serviceIntent = Intent(context, MyVpnService::class.java).apply {
                    action = MyVpnService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}