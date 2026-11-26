package com.example

import com.example.hardware.ChargerRadioController
import com.example.model.BackgroundMonitoringMode
import com.example.model.UserSettings
import com.example.service.SilentBackgroundMonitorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for Battery Boss core configuration invariants.
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
    }
}

