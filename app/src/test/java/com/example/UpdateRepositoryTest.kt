package com.example

import com.example.update.UpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateRepositoryTest {

    @Test
    fun `isVersionMismatch triggers whenever server version differs from current version`() {
        assertTrue(UpdateRepository.isVersionMismatch("1.0", "2.0"))
        assertTrue(UpdateRepository.isVersionMismatch("1.0", "1.0.1"))
        assertTrue(UpdateRepository.isVersionMismatch("2.0", "1.9"))
        assertTrue(UpdateRepository.isVersionMismatch("1.0", "v2.0"))
        assertFalse(UpdateRepository.isVersionMismatch("2.0", "2.0"))
        assertFalse(UpdateRepository.isVersionMismatch("1.0", "v1.0"))
        assertFalse(UpdateRepository.isVersionMismatch("1.0", ""))
    }

    @Test
    fun `parseUpdateJson parses Admin App snake_case keys and ignores future release_date`() {
        val raw = """
            {
              "apk_url": "https://www.instagram.com/bhavesh_z_10x?evlk=amRndno5MmxxNDQy",
              "description": "update please",
              "is_enabled": true,
              "is_mandatory": true,
              "release_date": "31 Dec 2099",
              "title": "UPDATE PLEASE",
              "updated_at": 1795180793919,
              "version": "2.0"
            }
        """.trimIndent()

        val parsed = UpdateRepository.parseUpdateJson(raw)
        assertNotNull(parsed)
        assertTrue(parsed!!.enabled)
        assertTrue(parsed.mandatory)
        assertEquals("2.0", parsed.version)
        assertEquals("v2.0", parsed.displayVersion)
        assertEquals("UPDATE PLEASE", parsed.title)
        assertEquals(listOf("update please"), parsed.bulletPoints)
        assertEquals("https://www.instagram.com/bhavesh_z_10x?evlk=amRndno5MmxxNDQy", parsed.apkUrl)
        assertTrue(UpdateRepository.isValidDownloadUrl(parsed.apkUrl))
        assertTrue(UpdateRepository.isVersionMismatch("1.0", parsed.version))
    }

    @Test
    fun `parseUpdateJson also parses camelCase keys cleanly`() {
        val raw = """
            {
              "enabled": true,
              "version": "2.0",
              "title": "ABOUT UPDATE",
              "description": "• Faster telemetry\n- Better voice alerts\nBug fixes",
              "apkUrl": "https://example.com/battery-boss-2.0.apk",
              "mandatory": false
            }
        """.trimIndent()

        val parsed = UpdateRepository.parseUpdateJson(raw)
        assertNotNull(parsed)
        assertTrue(parsed!!.enabled)
        assertFalse(parsed.mandatory)
        assertEquals("2.0", parsed.version)
        assertEquals("v2.0", parsed.displayVersion)
        assertEquals("ABOUT UPDATE", parsed.title)
        assertEquals(listOf("Faster telemetry", "Better voice alerts", "Bug fixes"), parsed.bulletPoints)
        assertEquals("https://example.com/battery-boss-2.0.apk", parsed.apkUrl)
    }

    @Test
    fun `parseUpdateJson returns null on null or invalid json`() {
        assertNull(UpdateRepository.parseUpdateJson(null))
        assertNull(UpdateRepository.parseUpdateJson("null"))
        assertNull(UpdateRepository.parseUpdateJson("{invalid"))
    }

    @Test
    fun `update lock blocks normal battery voice alerts and speaks hidden update alert only on charger connect`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dataStore = com.example.data.preferences.SettingsDataStore(context)
        val db = com.example.data.local.BatteryBossDatabase.getInstance(context)
        val repo = com.example.data.local.BatteryBossRepository(db.batteryBossDao())
        val voice = com.example.voice.OfflineVoiceAssistant(context, dataStore, repo)

        val settings = com.example.model.UserSettings(
            language = com.example.model.VoiceLanguage.HINDI,
            voiceAnnouncementsEnabled = true
        )
        val pluggedSnapshot = com.example.model.BatteryHardwareSnapshot(
            percentage = 67,
            isPercentageValid = true,
            isCharging = true,
            temperatureCelsius = 48.0f
        )
        val unpluggedSnapshot = com.example.model.BatteryHardwareSnapshot(
            percentage = 15,
            isPercentageValid = true,
            isCharging = false
        )

        try {
            kotlinx.coroutines.runBlocking {
                // 1. Normal state (no update): charger connect speaks battery percentage, NEVER update message
                com.example.update.UpdateLockManager.clearLock(context)
                voice.resetAnnouncementTrackingState()
                val normalMsg = voice.evaluateSnapshotAndAnnounceNow(
                    snapshot = pluggedSnapshot.copy(temperatureCelsius = 34.0f),
                    explicitTriggerEvent = android.content.Intent.ACTION_POWER_CONNECTED,
                    settingsOverride = settings,
                    currentTimeMillis = 10_000L
                )
                assertNotNull(normalMsg)
                assertTrue(normalMsg!!.contains("67"))
                assertFalse(normalMsg.contains("अपडेट"))

                // 2. Update active: charger connect speaks ONLY hidden update voice (no battery percentage)
                com.example.update.UpdateLockManager.setUpdateLockForTest(context, active = true, version = "2.0")
                voice.resetAnnouncementTrackingState()
                val lockedConnectMsg = voice.evaluateSnapshotAndAnnounceNow(
                    snapshot = pluggedSnapshot,
                    explicitTriggerEvent = android.content.Intent.ACTION_POWER_CONNECTED,
                    settingsOverride = settings,
                    currentTimeMillis = 20_000L
                )
                assertEquals(
                    com.example.update.UpdateLockManager.buildHiddenUpdateVoiceMessage(com.example.model.VoiceLanguage.HINDI),
                    lockedConnectMsg
                )
                assertFalse(lockedConnectMsg!!.contains("67"))

                // 3. Update active: disconnect, low battery, and high temp are all blocked (return null)
                val lockedDisconnectMsg = voice.evaluateSnapshotAndAnnounceNow(
                    snapshot = unpluggedSnapshot,
                    explicitTriggerEvent = android.content.Intent.ACTION_POWER_DISCONNECTED,
                    settingsOverride = settings,
                    currentTimeMillis = 30_000L
                )
                assertNull(lockedDisconnectMsg)

                val lockedLowBatteryMsg = voice.evaluateSnapshotAndAnnounceNow(
                    snapshot = unpluggedSnapshot,
                    explicitTriggerEvent = android.content.Intent.ACTION_BATTERY_LOW,
                    settingsOverride = settings,
                    currentTimeMillis = 40_000L
                )
                assertNull(lockedLowBatteryMsg)
            }
        } finally {
            com.example.update.UpdateLockManager.clearLock(context)
        }
    }
}
