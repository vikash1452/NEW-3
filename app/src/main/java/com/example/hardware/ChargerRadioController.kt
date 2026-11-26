package com.example.hardware

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.data.preferences.SettingsDataStore
import com.example.model.UserSettings
import com.example.service.BatteryTelemetryHub
import com.example.service.RadioToggleAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Snapshot of active connectivity radios captured right before charging modifications.
 */
data class RadioStatesSnapshot(
    val bluetoothOn: Boolean = false,
    val wifiOn: Boolean = false,
    val mobileDataOn: Boolean = false
) {
    fun anyActive(): Boolean = bluetoothOn || wifiOn || mobileDataOn

    fun formatActiveList(): String = buildList {
        if (wifiOn) add("Wi-Fi")
        if (bluetoothOn) add("Bluetooth")
        if (mobileDataOn) add("Mobile Data")
    }.joinToString(", ").ifEmpty { "None" }
}

/**
 * Pure selective restore plan computed from persisted pre-charge radio state and user toggles.
 * Guarantees a radio that was already OFF prior to charging is NEVER turned ON on unplug.
 */
data class RestoreRadioPlan(
    val shouldRestoreAny: Boolean,
    val restoreBluetooth: Boolean,
    val restoreWifi: Boolean,
    val restoreMobileData: Boolean
) {
    fun formatRestoreList(): String = buildList {
        if (restoreWifi) add("Wi-Fi")
        if (restoreBluetooth) add("Bluetooth")
        if (restoreMobileData) add("Mobile Data")
    }.joinToString(", ").ifEmpty { "None" }
}

/**
 * Result of handling Feature 1 ("Auto-Off Data, Wi-Fi & BT on Charge") when a charger is plugged in.
 */
data class ChargerRadioOffResult(
    val featureEnabled: Boolean = false,
    val debounced: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val bluetoothDisableAttempted: Boolean = false,
    val bluetoothDisabledProgrammatically: Boolean = false,
    val wifiDisabledProgrammatically: Boolean = false,
    val accessibilityAutomationQueued: Boolean = false,
    val internetPanelNotificationPosted: Boolean = false,
    val internetPanelLaunchedDirectly: Boolean = false,
    val capturedSnapshot: RadioStatesSnapshot? = null,
    val summaryMessage: String = "Disabled"
)

/**
 * Result of restoring previously active radios when the charger is unplugged.
 */
data class ChargerRadioRestoreResult(
    val restoreTriggered: Boolean = false,
    val debounced: Boolean = false,
    val nothingWasOnBeforeCharging: Boolean = false,
    val plan: RestoreRadioPlan = RestoreRadioPlan(false, false, false, false),
    val bluetoothEnabledProgrammatically: Boolean = false,
    val wifiEnabledProgrammatically: Boolean = false,
    val accessibilityAutomationQueued: Boolean = false,
    val restoreNotificationPosted: Boolean = false,
    val summaryMessage: String = "No pending restore"
)

/**
 * Modern Android (targetSdk 36) compliant dual-layer controller for turning OFF Bluetooth, Wi-Fi,
 * and Mobile Data on charger connect, and restoring ONLY whichever radios were previously ON upon unplug:
 *
 * Layer 1 (Direct OS APIs):
 * - Bluetooth: `BluetoothAdapter.disable()` / `enable()` when `BLUETOOTH_CONNECT` is granted (Android <= 12).
 * - Wi-Fi: `WifiManager.setWifiEnabled(false/true)` on Android <= 9 (API <= 28).
 *
 * Layer 2 (Optional User-Enabled Accessibility Auto-Toggle Helper):
 * - On Android 10–16 (where OS blocks silent third-party Wi-Fi, Mobile Data, and Android 13+ Bluetooth toggles),
 *   if the user enables `Battery Boss Radio Auto-Toggle` in Accessibility Settings, Quick Settings
 *   automatically switches target radios OFF on plug-in and restores previously active radios on unplug.
 *
 * Layer 3 (Actionable Notification / Internet Panel Fallback):
 * - If Accessibility Helper is not enabled (or when an action still requires user confirmation),
 *   posts an auto-cancel high-priority notification with 1-tap Quick Settings / Internet Panel actions.
 */
object ChargerRadioController {

    const val CHANNEL_ID_CHARGER_RADIOS = "battery_boss_charger_radios_channel"
    const val NOTIFICATION_ID_CHARGER_RADIOS = 4031
    const val NOTIFICATION_ID_CHARGER_RESTORE = 4034
    private const val DEBOUNCE_WINDOW_MS = 2_000L

    private val debounceLock = Any()
    private var lastHandledConnectElapsedMs: Long = 0L
    private var lastHandledDisconnectElapsedMs: Long = 0L

    fun computeRestorePlan(settings: UserSettings): RestoreRadioPlan {
        if (!settings.autoDisableRadiosOnChargeEnabled ||
            !settings.autoRestoreRadiosOnUnplugEnabled ||
            !settings.hasPendingRadioRestoreOnUnplug
        ) {
            return RestoreRadioPlan(
                shouldRestoreAny = false,
                restoreBluetooth = false,
                restoreWifi = false,
                restoreMobileData = false
            )
        }
        val restoreBt = settings.autoDisableBluetoothOnCharge && settings.savedPreChargeBluetoothOn
        val restoreWifi = settings.autoDisableWifiAndDataOnCharge && settings.savedPreChargeWifiOn
        val restoreData = settings.autoDisableWifiAndDataOnCharge && settings.savedPreChargeMobileDataOn
        return RestoreRadioPlan(
            shouldRestoreAny = restoreBt || restoreWifi || restoreData,
            restoreBluetooth = restoreBt,
            restoreWifi = restoreWifi,
            restoreMobileData = restoreData
        )
    }

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

    fun isAccessibilityAutoToggleEnabled(context: Context): Boolean {
        return RadioToggleAccessibilityService.isServiceConnected(context.applicationContext)
    }

    @Suppress("MissingPermission")
    fun readCurrentRadioStates(context: Context): RadioStatesSnapshot {
        val appContext = context.applicationContext

        val bluetoothOn = runCatching {
            val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (hasBluetoothConnectPermission(appContext)) {
                btManager?.adapter?.isEnabled
            } else {
                null
            }
        }.getOrNull() ?: runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BLUETOOTH_ON, 0) == 1
        }.getOrDefault(false)

        val wifiOn = runCatching {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled ?: false
        }.getOrDefault(false)

        val mobileDataSetting = runCatching {
            val cr = appContext.contentResolver
            val globalVal = Settings.Global.getInt(cr, "mobile_data", -1)
            when (globalVal) {
                1 -> true
                0 -> false
                else -> {
                    val sim0 = Settings.Global.getInt(cr, "mobile_data0", -1)
                    val sim1 = Settings.Global.getInt(cr, "mobile_data1", -1)
                    when {
                        sim0 == 1 || sim1 == 1 -> true
                        sim0 == 0 && sim1 != 1 -> false
                        else -> null
                    }
                }
            }
        }.getOrNull()

        val mobileDataOn = mobileDataSetting ?: runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = if (network != null) cm.getNetworkCapabilities(network) else null
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }.getOrDefault(false)

        return RadioStatesSnapshot(
            bluetoothOn = bluetoothOn,
            wifiOn = wifiOn,
            mobileDataOn = mobileDataOn
        )
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

    fun buildAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
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

    fun openAccessibilitySettings(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildAccessibilitySettingsIntent())
            true
        }.getOrDefault(false)
    }

    fun openAppDetailsSettings(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildAppDetailsSettingsIntent(context))
            true
        }.getOrDefault(false)
    }

    fun resetDebounceForTest() {
        synchronized(debounceLock) {
            lastHandledConnectElapsedMs = 0L
            lastHandledDisconnectElapsedMs = 0L
        }
    }

    fun cancelChargerRadioNotification(context: Context) {
        runCatching {
            val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_CHARGER_RADIOS)
        }
    }

    fun cancelAllRadioNotifications(context: Context) {
        runCatching {
            val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_CHARGER_RADIOS)
            nm?.cancel(NOTIFICATION_ID_CHARGER_RESTORE)
        }
    }

    /**
     * Suspend entry point for services/receivers: ensures we read fresh persisted settings before
     * capturing & persisting the pre-charge radio snapshot in DataStore so duplicate events never
     * clobber an active charging session's saved snapshot.
     */
    suspend fun onChargerConnectedSuspending(
        context: Context,
        settings: UserSettings,
        dataStore: SettingsDataStore,
        isAppInForeground: Boolean = false,
        bypassDebounce: Boolean = false
    ): ChargerRadioOffResult {
        val freshSettings = withContext(Dispatchers.IO) {
            runCatching { dataStore.getSettingsSnapshot() }.getOrDefault(settings)
        }
        val result = onChargerConnected(
            context = context,
            settings = freshSettings,
            isAppInForeground = isAppInForeground,
            bypassDebounce = bypassDebounce
        )
        if (!result.debounced &&
            result.featureEnabled &&
            result.capturedSnapshot != null &&
            !freshSettings.hasPendingRadioRestoreOnUnplug
        ) {
            withContext(Dispatchers.IO) {
                dataStore.savePreChargeRadioSnapshot(
                    bluetoothOn = result.capturedSnapshot.bluetoothOn,
                    wifiOn = result.capturedSnapshot.wifiOn,
                    mobileDataOn = result.capturedSnapshot.mobileDataOn
                )
            }
        }
        return result
    }

    /**
     * Suspend entry point for charger unplug: reads fresh DataStore snapshot (so previously-ON
     * radio states are never stale), runs selective restore, and clears the session snapshot.
     */
    suspend fun onChargerDisconnectedSuspending(
        context: Context,
        settings: UserSettings,
        dataStore: SettingsDataStore,
        bypassDebounce: Boolean = false
    ): ChargerRadioRestoreResult {
        val freshSettings = withContext(Dispatchers.IO) {
            runCatching { dataStore.getSettingsSnapshot() }.getOrDefault(settings)
        }
        val result = onChargerDisconnected(
            context = context,
            settings = freshSettings,
            bypassDebounce = bypassDebounce
        )
        if (!result.debounced && freshSettings.hasPendingRadioRestoreOnUnplug) {
            withContext(Dispatchers.IO) {
                dataStore.clearPreChargeRadioSnapshot()
            }
        }
        return result
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun onChargerConnected(
        context: Context,
        settings: UserSettings,
        isAppInForeground: Boolean = false,
        bypassDebounce: Boolean = false
    ): ChargerRadioOffResult {
        val appContext = context.applicationContext
        cancelRestoreNotificationOnly(appContext)

        if (!settings.autoDisableRadiosOnChargeEnabled) {
            return ChargerRadioOffResult(featureEnabled = false, summaryMessage = "Auto-Off on Charge is OFF")
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (!bypassDebounce) {
            synchronized(debounceLock) {
                if (lastHandledConnectElapsedMs > 0L &&
                    (nowElapsed - lastHandledConnectElapsedMs) in 0 until DEBOUNCE_WINDOW_MS
                ) {
                    return ChargerRadioOffResult(
                        featureEnabled = true,
                        debounced = true,
                        summaryMessage = "Debounced duplicate charger connect"
                    )
                }
                lastHandledConnectElapsedMs = nowElapsed
            }
        }

        // Capture current radio states BEFORE turning anything off
        val preChargeSnapshot = readCurrentRadioStates(appContext)

        val hasBtPerm = hasBluetoothConnectPermission(appContext)
        var btAttempted = false
        var btDisabled = false
        var btStillOn = settings.autoDisableBluetoothOnCharge && preChargeSnapshot.bluetoothOn

        // Layer 1A: Direct Bluetooth disable (succeeds on Android <= 12 when BLUETOOTH_CONNECT granted)
        if (settings.autoDisableBluetoothOnCharge && preChargeSnapshot.bluetoothOn && hasBtPerm) {
            runCatching {
                val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = btManager?.adapter
                if (adapter != null && adapter.isEnabled) {
                    btAttempted = true
                    btDisabled = runCatching { adapter.disable() }.getOrDefault(false)
                    if (btDisabled) {
                        btStillOn = false
                    }
                }
            }
        }

        // Layer 1B: Direct Wi-Fi disable (only supported by OS on Android <= 9 / API <= 28)
        var wifiDisabledDirectly = false
        var wifiStillOn = settings.autoDisableWifiAndDataOnCharge && preChargeSnapshot.wifiOn
        if (settings.autoDisableWifiAndDataOnCharge &&
            preChargeSnapshot.wifiOn &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        ) {
            runCatching {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null && wifiManager.isWifiEnabled) {
                    wifiDisabledDirectly = wifiManager.setWifiEnabled(false)
                    if (wifiDisabledDirectly) {
                        wifiStillOn = false
                    }
                }
            }
        }

        val dataStillOn = settings.autoDisableWifiAndDataOnCharge && preChargeSnapshot.mobileDataOn

        // Layer 2: Optional Accessibility Auto-Toggle Helper (Android 10–16 Quick Settings automation)
        val helperEnabled = isAccessibilityAutoToggleEnabled(appContext)
        BatteryTelemetryHub.setAccessibilityRadioHelperEnabled(helperEnabled)

        var accessibilityQueued = false
        if (helperEnabled && (btStillOn || wifiStillOn || dataStillOn)) {
            accessibilityQueued = RadioToggleAccessibilityService.enqueueChargerConnectDisable(
                disableBluetooth = btStillOn,
                disableWifi = wifiStillOn,
                disableMobileData = dataStillOn,
                initialOsStates = preChargeSnapshot
            )
        }

        val shouldShowFallbackNotification = !accessibilityQueued &&
            (settings.autoDisableWifiAndDataOnCharge || (btStillOn && settings.autoDisableBluetoothOnCharge))

        var notificationPosted = false
        var panelLaunchedDirectly = false

        if (shouldShowFallbackNotification) {
            notificationPosted = postInternetQuickOffNotification(
                context = appContext,
                btDisabled = btDisabled,
                includeBluetoothSettingsAction = btStillOn && settings.autoDisableBluetoothOnCharge,
                includeEnableHelperAction = !helperEnabled
            )
            if (isAppInForeground && settings.autoDisableWifiAndDataOnCharge) {
                panelLaunchedDirectly = openInternetConnectivityPanel(appContext)
            }
        }

        val summary = buildString {
            append("Saved state [${preChargeSnapshot.formatActiveList()}]. ")
            if (accessibilityQueued) {
                append("Auto-toggling off via Accessibility Helper.")
            } else {
                if (btDisabled) append("Bluetooth disabled. ")
                if (wifiDisabledDirectly) append("Wi-Fi disabled. ")
                if (notificationPosted) append("Posted 1-tap Internet Panel prompt (enable Auto-Toggle Helper for hands-free).")
            }
        }.ifBlank { "All selected radios already OFF" }

        BatteryTelemetryHub.updateLastRadioAutomationStatus(summary)

        return ChargerRadioOffResult(
            featureEnabled = true,
            debounced = false,
            bluetoothPermissionGranted = hasBtPerm,
            bluetoothDisableAttempted = btAttempted,
            bluetoothDisabledProgrammatically = btDisabled,
            wifiDisabledProgrammatically = wifiDisabledDirectly,
            accessibilityAutomationQueued = accessibilityQueued,
            internetPanelNotificationPosted = notificationPosted,
            internetPanelLaunchedDirectly = panelLaunchedDirectly,
            capturedSnapshot = preChargeSnapshot,
            summaryMessage = summary
        )
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun onChargerDisconnected(
        context: Context,
        settings: UserSettings,
        bypassDebounce: Boolean = false
    ): ChargerRadioRestoreResult {
        val appContext = context.applicationContext
        cancelChargerRadioNotification(appContext)

        val nowElapsed = SystemClock.elapsedRealtime()
        if (!bypassDebounce) {
            synchronized(debounceLock) {
                if (lastHandledDisconnectElapsedMs > 0L &&
                    (nowElapsed - lastHandledDisconnectElapsedMs) in 0 until DEBOUNCE_WINDOW_MS
                ) {
                    return ChargerRadioRestoreResult(
                        restoreTriggered = false,
                        debounced = true,
                        summaryMessage = "Debounced duplicate charger disconnect"
                    )
                }
                lastHandledDisconnectElapsedMs = nowElapsed
            }
        }

        val plan = computeRestorePlan(settings)
        if (!plan.shouldRestoreAny) {
            val noOpReason = when {
                !settings.autoDisableRadiosOnChargeEnabled -> "Auto-Off on Charge is OFF"
                !settings.autoRestoreRadiosOnUnplugEnabled -> "Auto-Restore on Unplug is OFF"
                !settings.hasPendingRadioRestoreOnUnplug -> "No pending pre-charge radio snapshot"
                else -> "All radios were already OFF before charging"
            }
            if (settings.hasPendingRadioRestoreOnUnplug) {
                BatteryTelemetryHub.updateLastRadioAutomationStatus("Unplugged: Nothing to restore (radios were OFF before charging)")
            }
            return ChargerRadioRestoreResult(
                restoreTriggered = false,
                nothingWasOnBeforeCharging = settings.hasPendingRadioRestoreOnUnplug,
                plan = plan,
                summaryMessage = noOpReason
            )
        }

        val currentOs = readCurrentRadioStates(appContext)
        val hasBtPerm = hasBluetoothConnectPermission(appContext)

        var needRestoreBt = plan.restoreBluetooth && !currentOs.bluetoothOn
        var needRestoreWifi = plan.restoreWifi && !currentOs.wifiOn
        val needRestoreData = plan.restoreMobileData && !currentOs.mobileDataOn

        var btEnabledDirectly = false
        if (needRestoreBt && hasBtPerm) {
            runCatching {
                val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = btManager?.adapter
                if (adapter != null && !adapter.isEnabled) {
                    btEnabledDirectly = runCatching { adapter.enable() }.getOrDefault(false)
                    if (btEnabledDirectly) {
                        needRestoreBt = false
                    }
                }
            }
        }

        var wifiEnabledDirectly = false
        if (needRestoreWifi && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null && !wifiManager.isWifiEnabled) {
                    wifiEnabledDirectly = wifiManager.setWifiEnabled(true)
                    if (wifiEnabledDirectly) {
                        needRestoreWifi = false
                    }
                }
            }
        }

        if (!needRestoreBt && !needRestoreWifi && !needRestoreData) {
            val doneMsg = "Unplugged: Restored ${plan.formatRestoreList()}"
            BatteryTelemetryHub.updateLastRadioAutomationStatus(doneMsg)
            return ChargerRadioRestoreResult(
                restoreTriggered = true,
                plan = plan,
                bluetoothEnabledProgrammatically = btEnabledDirectly,
                wifiEnabledProgrammatically = wifiEnabledDirectly,
                summaryMessage = doneMsg
            )
        }

        val helperEnabled = isAccessibilityAutoToggleEnabled(appContext)
        BatteryTelemetryHub.setAccessibilityRadioHelperEnabled(helperEnabled)

        var accessibilityQueued = false
        if (helperEnabled) {
            accessibilityQueued = RadioToggleAccessibilityService.enqueueChargerDisconnectRestore(
                restoreBluetooth = needRestoreBt,
                restoreWifi = needRestoreWifi,
                restoreMobileData = needRestoreData,
                initialOsStates = currentOs
            )
        }

        var restoreNotifPosted = false
        if (!accessibilityQueued) {
            restoreNotifPosted = postRestoreRadiosNotification(
                context = appContext,
                plan = plan,
                needBluetoothAction = needRestoreBt,
                needInternetAction = needRestoreWifi || needRestoreData
            )
        }

        val summary = if (accessibilityQueued) {
            "Unplugged: Auto-restoring [${plan.formatRestoreList()}] via Accessibility Helper"
        } else {
            "Unplugged: Tap notification to restore [${plan.formatRestoreList()}]"
        }
        BatteryTelemetryHub.updateLastRadioAutomationStatus(summary)

        return ChargerRadioRestoreResult(
            restoreTriggered = true,
            plan = plan,
            bluetoothEnabledProgrammatically = btEnabledDirectly,
            wifiEnabledProgrammatically = wifiEnabledDirectly,
            accessibilityAutomationQueued = accessibilityQueued,
            restoreNotificationPosted = restoreNotifPosted,
            summaryMessage = summary
        )
    }

    private fun cancelRestoreNotificationOnly(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_CHARGER_RESTORE)
        }
    }

    private fun postInternetQuickOffNotification(
        context: Context,
        btDisabled: Boolean,
        includeBluetoothSettingsAction: Boolean,
        includeEnableHelperAction: Boolean
    ): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        ensureRadioNotificationChannel(nm)

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
                "Tap Open Internet Panel to turn off Wi-Fi & Mobile Data (or enable Auto-Toggle Helper for hands-free)."
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

        if (includeEnableHelperAction) {
            val a11yPendingIntent = PendingIntent.getActivity(
                context,
                4035,
                buildAccessibilitySettingsIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_preferences,
                "Auto-Toggle Setup",
                a11yPendingIntent
            )
        }

        return runCatching {
            nm.notify(NOTIFICATION_ID_CHARGER_RADIOS, builder.build())
            true
        }.getOrDefault(false)
    }

    private fun postRestoreRadiosNotification(
        context: Context,
        plan: RestoreRadioPlan,
        needBluetoothAction: Boolean,
        needInternetAction: Boolean
    ): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        ensureRadioNotificationChannel(nm)

        val restoreList = plan.formatRestoreList()
        val primaryIntent = if (needInternetAction) {
            buildInternetConnectivityPanelIntent()
        } else {
            buildBluetoothSettingsIntent()
        }
        val primaryPendingIntent = PendingIntent.getActivity(
            context,
            4034,
            primaryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = "Before charging you had ON: $restoreList. Tap below to turn them back ON (or enable Auto-Toggle Helper for automatic restore)."
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CHARGER_RADIOS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Charger Unplugged — Restore $restoreList")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(primaryPendingIntent)

        if (needInternetAction) {
            builder.addAction(
                android.R.drawable.ic_menu_manage,
                "Open Internet Panel",
                primaryPendingIntent
            )
        }

        if (needBluetoothAction) {
            val btPending = PendingIntent.getActivity(
                context,
                4036,
                buildBluetoothSettingsIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.stat_sys_data_bluetooth,
                "Bluetooth Settings",
                btPending
            )
        }

        return runCatching {
            nm.notify(NOTIFICATION_ID_CHARGER_RESTORE, builder.build())
            true
        }.getOrDefault(false)
    }

    private fun ensureRadioNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_CHARGER_RADIOS,
                "Charger Quick Radio Controls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority 1-tap Internet Connectivity & Radio Restore controls when charger connects or disconnects."
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
