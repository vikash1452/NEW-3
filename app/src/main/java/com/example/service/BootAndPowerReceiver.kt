package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.preferences.SettingsDataStore
import com.example.model.BackgroundMonitoringMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores background monitoring on device reboot (`BOOT_COMPLETED`) when permitted
 * by user settings, and handles power connect/disconnect & battery low/okay broadcasts:
 * - Ensures `BatteryMonitorService` is running as a Foreground Service (with Compact Silent
 *   `IMPORTANCE_MIN` notification when `silentNotificationEnabled = true`, or `IMPORTANCE_LOW`
 *   when `silentNotificationEnabled = false`).
 * - Evaluates and speaks Charger Connect/Disconnect or Low Battery announcements immediately
 *   under `goAsync()` + bounded `PARTIAL_WAKE_LOCK` so Screen-Off announcements never drop.
 */
class BootAndPowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                BatteryTelemetryHub.recordBroadcastEventHandled()
                val settings = SettingsDataStore(appContext).getSettingsSnapshot()
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    "android.intent.action.QUICKBOOT_POWERON",
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        if (settings.startOnBootEnabled && settings.backgroundMonitoringEnabled) {
                            SilentBackgroundMonitorEngine.applyMonitoringMode(
                                context = appContext,
                                enabled = true,
                                mode = settings.backgroundMonitoringMode,
                                triggerEvent = null
                            )
                        }
                    }
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED,
                    Intent.ACTION_BATTERY_LOW,
                    Intent.ACTION_BATTERY_OKAY -> {
                        if (settings.backgroundMonitoringEnabled) {
                            BatteryMonitorService.startService(
                                context = appContext,
                                triggerEvent = action
                            )
                            SilentBackgroundMonitorEngine.evaluateSilentBackgroundEventNow(
                                context = appContext,
                                triggerAction = action,
                                batteryIntent = null
                            )
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

