package com.example.hardware

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.model.UserSettings
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of handling Feature 1 ("Auto-Off Data, Wi-Fi & BT on Charge") when a charger is plugged in.
 */
data class ChargerRadioOffResult(
    val featureEnabled: Boolean = false,
    val debounced: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val bluetoothDisableAttempted: Boolean = false,
    val bluetoothDisabledProgrammatically: Boolean = false,
    val internetPanelNotificationPosted: Boolean = false,
    val internetPanelLaunchedDirectly: Boolean = false,
    val summaryMessage: String = "Disabled"
)

/**
 * Modern Android (targetSdk 36) compliant controller for turning off Bluetooth and presenting a
 * 1-tap Internet Connectivity Panel (Wi-Fi & Mobile Data) when the charger is connected.
 *
 * Strictly follows Android OS security boundaries with zero root hacks:
 * - Bluetooth: Uses `BluetoothAdapter.disable()` only when `BLUETOOTH_CONNECT` (API 31+) is
 *   granted, wrapped in safe `runCatching` with graceful Android 13+ fallback.
 * - Wi-Fi & Mobile Data: Android 10+ (API 29+) restricts third-party apps from programmatically
 *   toggling Wi-Fi or Mobile Data, so posts a high-priority auto-cancel notification with a 1-tap
 *   "Open Internet Panel" action launching `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (and
 *   launches the panel directly when the app is in foreground).
 */
object ChargerRadioController {

    const val CHANNEL_ID_CHARGER_RADIOS = "battery_boss_charger_radios_channel"
    const val NOTIFICATION_ID_CHARGER_RADIOS = 4031
    private const val DEBOUNCE_WINDOW_MS = 2_000L

    private val lastHandledConnectElapsedMs = AtomicLong(0L)

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun buildInternetConnectivityPanelIntent(): Intent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIRELESS_SETTINGS
        }
        return Intent(action).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun buildBluetoothSettingsIntent(): Intent {
        return Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun openInternetConnectivityPanel(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildInternetConnectivityPanelIntent())
            true
        }.getOrElse {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                true
            }.getOrDefault(false)
        }
    }

    fun resetDebounceForTest() {
        lastHandledConnectElapsedMs.set(0L)
    }

    fun cancelChargerRadioNotification(context: Context) {
        runCatching {
            val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_CHARGER_RADIOS)
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun onChargerConnected(
        context: Context,
        settings: UserSettings,
        isAppInForeground: Boolean = false,
        bypassDebounce: Boolean = false
    ): ChargerRadioOffResult {
        if (!settings.autoDisableRadiosOnChargeEnabled) {
            return ChargerRadioOffResult(featureEnabled = false, summaryMessage = "Auto-Off on Charge is OFF")
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (!bypassDebounce) {
            val previous = lastHandledConnectElapsedMs.get()
            if (previous > 0L && (nowElapsed - previous) in 0 until DEBOUNCE_WINDOW_MS) {
                return ChargerRadioOffResult(
                    featureEnabled = true,
                    debounced = true,
                    summaryMessage = "Debounced duplicate charger connect"
                )
            }
            lastHandledConnectElapsedMs.set(nowElapsed)
        }

        val appContext = context.applicationContext
        val hasBtPerm = hasBluetoothConnectPermission(appContext)
        var btAttempted = false
        var btDisabled = false
        var btStillNeedsManualFallback = false

        if (settings.autoDisableBluetoothOnCharge) {
            if (hasBtPerm) {
                runCatching {
                    val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = btManager?.adapter
                    if (adapter != null && adapter.isEnabled) {
                        btAttempted = true
                        btDisabled = runCatching { adapter.disable() }.getOrDefault(false)
                        if (!btDisabled) {
                            btStillNeedsManualFallback = true
                        }
                    }
                }.onFailure {
                    btStillNeedsManualFallback = true
                }
            } else {
                btStillNeedsManualFallback = true
            }
        }

        val shouldShowInternetPanelAction = settings.autoDisableWifiAndDataOnCharge || btStillNeedsManualFallback
        var notificationPosted = false
        var panelLaunchedDirectly = false

        if (shouldShowInternetPanelAction) {
            notificationPosted = postInternetQuickOffNotification(
                context = appContext,
                btDisabled = btDisabled,
                includeBluetoothSettingsAction = btStillNeedsManualFallback && settings.autoDisableBluetoothOnCharge
            )
            if (isAppInForeground && settings.autoDisableWifiAndDataOnCharge) {
                panelLaunchedDirectly = openInternetConnectivityPanel(appContext)
            }
        }

        val summary = buildString {
            if (btDisabled) append("Bluetooth disabled. ")
            else if (btStillNeedsManualFallback && settings.autoDisableBluetoothOnCharge) {
                append("Bluetooth requires manual toggle/permission on Android 13+. ")
            }
            if (notificationPosted) append("Posted 1-tap Internet Panel notification.")
        }.ifBlank { "No radio action needed" }

        return ChargerRadioOffResult(
            featureEnabled = true,
            debounced = false,
            bluetoothPermissionGranted = hasBtPerm,
            bluetoothDisableAttempted = btAttempted,
            bluetoothDisabledProgrammatically = btDisabled,
            internetPanelNotificationPosted = notificationPosted,
            internetPanelLaunchedDirectly = panelLaunchedDirectly,
            summaryMessage = summary
        )
    }

    private fun postInternetQuickOffNotification(
        context: Context,
        btDisabled: Boolean,
        includeBluetoothSettingsAction: Boolean
    ): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_CHARGER_RADIOS,
                "Charger Quick Radio Controls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority 1-tap Internet Connectivity Panel shortcut when charger is connected."
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val internetPanelPendingIntent = PendingIntent.getActivity(
            context,
            4031,
            buildInternetConnectivityPanelIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = when {
            btDisabled ->
                "Bluetooth turned off automatically. Tap Open Internet Panel to toggle Wi-Fi & Mobile Data off."
            includeBluetoothSettingsAction ->
                "Tap Open Internet Panel to turn off Wi-Fi & Mobile Data (or Bluetooth Settings on Android 13+)."
            else ->
                "Tap Open Internet Panel to turn off Wi-Fi & Mobile Data immediately while charging."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CHARGER_RADIOS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Charger Connected — Turn Off Wi-Fi & Mobile Data")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(internetPanelPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_manage,
                "Open Internet Panel",
                internetPanelPendingIntent
            )

        if (includeBluetoothSettingsAction) {
            val btPendingIntent = PendingIntent.getActivity(
                context,
                4032,
                buildBluetoothSettingsIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.stat_sys_data_bluetooth,
                "Bluetooth Settings",
                btPendingIntent
            )
        }

        return runCatching {
            nm.notify(NOTIFICATION_ID_CHARGER_RADIOS, builder.build())
            true
        }.getOrDefault(false)
    }
}
