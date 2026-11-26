package com.example

import com.example.hardware.ChargerRadioController
import com.example.model.BackgroundMonitoringMode
import com.example.model.UserSettings
import com.example.service.RadioAccessibilityNodeHelper
import com.example.service.RadioTargetKind
import com.example.service.SilentBackgroundMonitorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for Battery Boss core configuration invariants and selective radio restore logic.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun userSettings_autoDisableRadiosAndHourlyDefaults_areSafe() {
        val defaults = UserSettings()
        assertFalse(
            "Auto-off radios on charge should default to OFF until user enables it",
            defaults.autoDisableRadiosOnChargeEnabled
        )
        assertTrue(defaults.autoDisableBluetoothOnCharge)
        assertTrue(defaults.autoDisableWifiAndDataOnCharge)
        assertTrue(defaults.autoRestoreRadiosOnUnplugEnabled)
        assertFalse(defaults.savedPreChargeBluetoothOn)
        assertFalse(defaults.savedPreChargeWifiOn)
        assertFalse(defaults.savedPreChargeMobileDataOn)
        assertFalse(defaults.hasPendingRadioRestoreOnUnplug)
        assertTrue(defaults.hourlyStatusNotificationEnabled)
    }

    @Test
    fun noPersistentHourlyMode_disablesPersistentForegroundServiceAndUsesOneHourInterval() {
        val mode = BackgroundMonitoringMode.NO_PERSISTENT_HOURLY
        assertFalse("NO_PERSISTENT_HOURLY must not require a persistent notification", mode.requiresPersistentNotification)
        assertFalse("NO_PERSISTENT_HOURLY must not start a Foreground Service", mode.usesForegroundService)
        assertEquals(SilentBackgroundMonitorEngine.CHANNEL_ID_HOURLY_STATUS, mode.channelId)
        assertEquals(
            "Hourly check JobScheduler interval must be 60 minutes (3,600,000 ms)",
            60 * 60 * 1000L,
            SilentBackgroundMonitorEngine.JOB_PERIODIC_HOURLY_INTERVAL_MS
        )
        assertTrue(
            "Charger radio notification ID must be distinct from FGS notification ID",
            ChargerRadioController.NOTIFICATION_ID_CHARGER_RADIOS != SilentBackgroundMonitorEngine.NOTIFICATION_ID_HOURLY_STATUS
        )
        assertTrue(
            "Restore notification ID must be distinct from connect notification ID",
            ChargerRadioController.NOTIFICATION_ID_CHARGER_RESTORE != ChargerRadioController.NOTIFICATION_ID_CHARGER_RADIOS
        )
    }

    @Test
    fun computeRestorePlan_restoresOnlyRadiosThatWereOnBeforeCharging() {
        // Suppose before charging Wi-Fi & Mobile Data were ON, Bluetooth was OFF
        val planWifiAndDataOnly = ChargerRadioController.computeRestorePlan(
            UserSettings(
                autoDisableRadiosOnChargeEnabled = true,
                autoRestoreRadiosOnUnplugEnabled = true,
                autoDisableBluetoothOnCharge = true,
                autoDisableWifiAndDataOnCharge = true,
                savedPreChargeBluetoothOn = false,
                savedPreChargeWifiOn = true,
                savedPreChargeMobileDataOn = true,
                hasPendingRadioRestoreOnUnplug = true
            )
        )
        assertTrue(planWifiAndDataOnly.shouldRestoreAny)
        assertFalse("Bluetooth was OFF before charge -> must NOT be restored", planWifiAndDataOnly.restoreBluetooth)
        assertTrue("Wi-Fi was ON before charge -> must be restored", planWifiAndDataOnly.restoreWifi)
        assertTrue("Mobile Data was ON before charge -> must be restored", planWifiAndDataOnly.restoreMobileData)
        assertEquals("Wi-Fi, Mobile Data", planWifiAndDataOnly.formatRestoreList())

        // Suppose nothing was ON before charging
        val planNothingOn = ChargerRadioController.computeRestorePlan(
            UserSettings(
                autoDisableRadiosOnChargeEnabled = true,
                autoRestoreRadiosOnUnplugEnabled = true,
                savedPreChargeBluetoothOn = false,
                savedPreChargeWifiOn = false,
                savedPreChargeMobileDataOn = false,
                hasPendingRadioRestoreOnUnplug = true
            )
        )
        assertFalse(planNothingOn.shouldRestoreAny)

        // Suppose auto-restore toggle is OFF -> never restore
        val planRestoreDisabled = ChargerRadioController.computeRestorePlan(
            UserSettings(
                autoDisableRadiosOnChargeEnabled = true,
                autoRestoreRadiosOnUnplugEnabled = false,
                savedPreChargeBluetoothOn = true,
                savedPreChargeWifiOn = true,
                savedPreChargeMobileDataOn = true,
                hasPendingRadioRestoreOnUnplug = true
            )
        )
        assertFalse(planRestoreDisabled.shouldRestoreAny)
    }

    @Test
    fun accessibilityNodeHelper_matchesCorrectTilesAndExcludesNonRadioTiles() {
        assertTrue(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.WIFI, listOf("Wi-Fi", "HomeWifi")))
        assertTrue(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.WIFI, listOf("WLAN")))
        assertTrue(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.BLUETOOTH, listOf("Bluetooth", "On")))
        assertTrue(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.MOBILE_DATA, listOf("Mobile data", "Jio 5G")))
        assertTrue(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.INTERNET_COMBINED, listOf("Internet", "HomeWifi")))

        // Must exclude related but non-target QS tiles
        assertFalse(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.MOBILE_DATA, listOf("Data Saver", "Off")))
        assertFalse(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.WIFI, listOf("Personal Hotspot", "Wi-Fi")))
        assertFalse(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.WIFI, listOf("Wi-Fi calling")))
        assertFalse(RadioAccessibilityNodeHelper.matchesRadioLabel(RadioTargetKind.BLUETOOTH, listOf("Nearby Share")))
    }

    @Test
    fun accessibilityNodeHelper_infersOnOffStateSafely() {
        // Checkable Switch takes priority
        assertEquals(
            true,
            RadioAccessibilityNodeHelper.inferNodeEnabledState(
                isCheckable = true,
                isChecked = true,
                isSelected = false,
                stateDescription = "Off",
                contentDescription = null,
                descendantTexts = emptyList()
            )
        )

        // Negative phrases must be detected BEFORE "connected" substring match!
        assertEquals(
            false,
            RadioAccessibilityNodeHelper.inferNodeEnabledState(
                isCheckable = false,
                isChecked = false,
                isSelected = false,
                stateDescription = "Not connected",
                contentDescription = "Wi-Fi, Not connected",
                descendantTexts = listOf("Wi-Fi", "Not connected")
            )
        )
        assertEquals(
            false,
            RadioAccessibilityNodeHelper.inferNodeEnabledState(
                isCheckable = false,
                isChecked = false,
                isSelected = false,
                stateDescription = "Off",
                contentDescription = "Mobile data, Off",
                descendantTexts = listOf("Mobile data", "Off")
            )
        )
        assertEquals(
            true,
            RadioAccessibilityNodeHelper.inferNodeEnabledState(
                isCheckable = false,
                isChecked = false,
                isSelected = true,
                stateDescription = "On",
                contentDescription = "Bluetooth, On, Buds",
                descendantTexts = listOf("Bluetooth", "On")
            )
        )
        assertNull(
            RadioAccessibilityNodeHelper.inferNodeEnabledState(
                isCheckable = false,
                isChecked = false,
                isSelected = false,
                stateDescription = null,
                contentDescription = "Wi-Fi",
                descendantTexts = listOf("Wi-Fi")
            )
        )
    }
}
