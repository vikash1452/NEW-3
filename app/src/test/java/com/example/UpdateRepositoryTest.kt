package com.example

import com.example.update.UpdateFetchResult
import com.example.update.UpdateLockManager
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
    fun `isNewerVersion returns true only when server version is strictly newer`() {
        assertTrue(UpdateRepository.isNewerVersion("1.0", "2.0"))
        assertTrue(UpdateRepository.isNewerVersion("1.0", "1.0.1"))
        assertTrue(UpdateRepository.isNewerVersion("1.2", "1.10"))
        assertTrue(UpdateRepository.isNewerVersion("1.0", "v2.0"))
        assertFalse(UpdateRepository.isNewerVersion("2.0", "2.0"))
        assertFalse(UpdateRepository.isNewerVersion("2.0", "1.9"))
        assertFalse(UpdateRepository.isNewerVersion("1.0", "v1.0"))
        assertFalse(UpdateRepository.isNewerVersion("1.0", ""))
    }

    @Test
    fun `newer version with future releaseDateMillis does NOT show update dialog`() {
        val nowMillis = 1_795_000_000_000L
        val futureReleaseMillis = nowMillis + 86_400_000L // +1 day in the future

        val raw = """
            {
              "is_enabled": true,
              "is_mandatory": true,
              "version": "2.0",
              "title": "ABOUT UPDATE",
              "description": "Scheduled update",
              "apk_url": "https://example.com/battery-boss-2.0.apk",
              "releaseDateMillis": $futureReleaseMillis
            }
        """.trimIndent()

        val parsed = UpdateRepository.parseUpdateJson(raw)
        assertNotNull(parsed)
        assertEquals(futureReleaseMillis, parsed!!.releaseDateMillis)

        val result = UpdateRepository.evaluateUpdate(
            info = parsed,
            currentVersion = "1.0",
            nowMillis = nowMillis
        )
        assertEquals(UpdateFetchResult.UpToDate, result)
    }

    @Test
    fun `newer version with past, current, or zero releaseDateMillis DOES show update dialog`() {
        val nowMillis = 1_795_000_000_000L
        val pastReleaseMillis = nowMillis - 86_400_000L

        for (releaseMillis in listOf(pastReleaseMillis, nowMillis, 0L)) {
            val raw = """
                {
                  "enabled": true,
                  "mandatory": false,
                  "version": "2.0",
                  "title": "ABOUT UPDATE",
                  "description": "• Faster telemetry\n- Better voice alerts\nBug fixes",
                  "apkUrl": "https://example.com/battery-boss-2.0.apk",
                  "releaseDateMillis": $releaseMillis
                }
            """.trimIndent()

            val parsed = UpdateRepository.parseUpdateJson(raw)
            assertNotNull(parsed)
            assertEquals(releaseMillis, parsed!!.releaseDateMillis)

            val result = UpdateRepository.evaluateUpdate(
                info = parsed,
                currentVersion = "1.0",
                nowMillis = nowMillis
            )
            assertTrue(
                "Expected UpdateAvailable for releaseDateMillis=$releaseMillis, got $result",
                result is UpdateFetchResult.UpdateAvailable
            )
            val info = (result as UpdateFetchResult.UpdateAvailable).info
            assertEquals("2.0", info.version)
            assertEquals("v2.0", info.displayVersion)
            assertEquals(listOf("Faster telemetry", "Better voice alerts", "Bug fixes"), info.bulletPoints)
        }
    }

    @Test
    fun `releaseDateMillis takes priority over release_date string`() {
        val nowMillis = 1_795_000_000_000L
        val futureReleaseMillis = nowMillis + 3_600_000L
        val pastReleaseMillis = nowMillis - 3_600_000L

        // Past string date, but future releaseDateMillis -> must NOT show dialog
        val blockedJson = """
            {
              "is_enabled": true,
              "version": "2.0",
              "apk_url": "https://example.com/update.apk",
              "release_date": "01/01/2020",
              "releaseDateMillis": $futureReleaseMillis
            }
        """.trimIndent()
        val blockedInfo = UpdateRepository.parseUpdateJson(blockedJson)
        assertEquals(futureReleaseMillis, blockedInfo!!.releaseDateMillis)
        assertEquals(
            UpdateFetchResult.UpToDate,
            UpdateRepository.evaluateUpdate(blockedInfo, currentVersion = "1.0", nowMillis = nowMillis)
        )

        // Future string date, but past releaseDateMillis -> MUST show dialog
        val allowedJson = """
            {
              "is_enabled": true,
              "version": "2.0",
              "apk_url": "https://example.com/update.apk",
              "release_date": "01/12/2099",
              "release_date_millis": $pastReleaseMillis
            }
        """.trimIndent()
        val allowedInfo = UpdateRepository.parseUpdateJson(allowedJson)
        assertEquals(pastReleaseMillis, allowedInfo!!.releaseDateMillis)
        assertTrue(
            UpdateRepository.evaluateUpdate(allowedInfo, currentVersion = "1.0", nowMillis = nowMillis)
                is UpdateFetchResult.UpdateAvailable
        )
    }

    @Test
    fun `fallback date string parsing works safely when releaseDateMillis is missing`() {
        val nowMillis = 1_795_000_000_000L // Nov 2026

        // Future dd/MM/yyyy string -> blocks dialog
        val futureSlashJson = """
            {
              "is_enabled": true,
              "version": "2.0",
              "apk_url": "https://example.com/update.apk",
              "release_date": "01/12/2099"
            }
        """.trimIndent()
        val futureSlashInfo = UpdateRepository.parseUpdateJson(futureSlashJson)
        assertTrue(futureSlashInfo!!.releaseDateMillis > nowMillis)
        assertEquals(
            UpdateFetchResult.UpToDate,
            UpdateRepository.evaluateUpdate(futureSlashInfo, currentVersion = "1.0", nowMillis = nowMillis)
        )

        // Future "dd MMM yyyy" string (as written by Admin app) -> blocks dialog
        val futureTextJson = """
            {
              "is_enabled": true,
              "version": "2.0",
              "apk_url": "https://example.com/update.apk",
              "release_date": "01 Dec 2099"
            }
        """.trimIndent()
        val futureTextInfo = UpdateRepository.parseUpdateJson(futureTextJson)
        assertTrue(futureTextInfo!!.releaseDateMillis > nowMillis)
        assertEquals(
            UpdateFetchResult.UpToDate,
            UpdateRepository.evaluateUpdate(futureTextInfo, currentVersion = "1.0", nowMillis = nowMillis)
        )

        // Past string date -> shows dialog
        val pastDateJson = """
            {
              "is_enabled": true,
              "version": "2.0",
              "apk_url": "https://example.com/update.apk",
              "release_date": "01/01/2020"
            }
        """.trimIndent()
        val pastDateInfo = UpdateRepository.parseUpdateJson(pastDateJson)
        assertTrue(pastDateInfo!!.releaseDateMillis in 1L..nowMillis)
        assertTrue(
            UpdateRepository.evaluateUpdate(pastDateInfo, currentVersion = "1.0", nowMillis = nowMillis)
                is UpdateFetchResult.UpdateAvailable
        )

        // Invalid date string -> safely defaults to 0L and does not crash
        val invalidDateJson = """
            {
              "is_enabled": true,
              "version": "2.0",
              "apk_url": "https://example.com/update.apk",
              "release_date": "not-a-valid-date"
            }
        """.trimIndent()
        val invalidDateInfo = UpdateRepository.parseUpdateJson(invalidDateJson)
        assertEquals(0L, invalidDateInfo!!.releaseDateMillis)
        assertTrue(
            UpdateRepository.evaluateUpdate(invalidDateInfo, currentVersion = "1.0", nowMillis = nowMillis)
                is UpdateFetchResult.UpdateAvailable
        )
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
                UpdateLockManager.clearLock(context)
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

                // 2. Scheduled future releaseDateMillis does NOT activate lock
                UpdateLockManager.setUpdateLockForTest(
                    context = context,
                    active = true,
                    version = "3.0",
                    releaseDateMillis = System.currentTimeMillis() + 86_400_000L
                )
                assertFalse(UpdateLockManager.isUpdateLockActive(context))

                // 3. Update active (releaseDateMillis == 0L): charger connect speaks ONLY hidden update voice
                UpdateLockManager.setUpdateLockForTest(context, active = true, version = "3.0", releaseDateMillis = 0L)
                voice.resetAnnouncementTrackingState()
                val lockedConnectMsg = voice.evaluateSnapshotAndAnnounceNow(
                    snapshot = pluggedSnapshot,
                    explicitTriggerEvent = android.content.Intent.ACTION_POWER_CONNECTED,
                    settingsOverride = settings,
                    currentTimeMillis = 20_000L
                )
                assertEquals(
                    UpdateLockManager.buildHiddenUpdateVoiceMessage(com.example.model.VoiceLanguage.HINDI),
                    lockedConnectMsg
                )
                assertFalse(lockedConnectMsg!!.contains("67"))

                // 4. Update active: disconnect and low battery are blocked (return null)
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
            UpdateLockManager.clearLock(context)
        }
    }

    @Test
    fun `admin custom ui announcement parses media gif video animations buttons and blocks without locking app`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        val raw = """
            {
              "is_enabled": true,
              "dialog_mode": "announcement",
              "dialog_id": "festive_promo_01",
              "show_once": true,
              "mandatory": false,
              "version": "2.2",
              "title": "Special Feature Live!",
              "description": ["New voice studio pack", "Watch quick video guide"],
              "ui_config": {
                "layout_style": "hero_media",
                "entrance_animation": "slide_up",
                "animation_type": "confetti",
                "show_confetti": true,
                "backdrop_color": "#101626",
                "card_color": "#1B2338",
                "card_corner_radius": 28,
                "accent_color": "#00E5FF",
                "badge_text": "NEW",
                "media_type": "auto",
                "video_url": "https://cdn.example.com/demo_guide.mp4?token=123",
                "thumbnail_url": "https://cdn.example.com/thumb.jpg",
                "video_autoplay": true,
                "video_loop": false,
                "video_muted": true,
                "primary_button_text": "Watch Full Video",
                "primary_button_url": "https://youtube.com/watch?v=123",
                "primary_button_icon": "play",
                "buttons": [
                  {
                    "id": "tg",
                    "text": "Join Telegram",
                    "url": "https://t.me/batteryboss",
                    "action": "open_url",
                    "style": "outlined",
                    "icon": "telegram",
                    "row_group": "social"
                  },
                  {
                    "id": "voice",
                    "text": "Voice Studio",
                    "url": "voice_alerts",
                    "action": "open_tab",
                    "style": "tonal",
                    "icon": "sparkles",
                    "row_group": "social"
                  }
                ],
                "ui_blocks": [
                  { "type": "badge", "text": "ADMIN NOTICE" },
                  { "type": "media", "media_url": "https://cdn.example.com/banner.gif" },
                  { "type": "highlight_box", "title": "Tip", "text": "Plug in charger to test" }
                ]
              }
            }
        """.trimIndent()

        val parsed = UpdateRepository.parseUpdateJson(raw)
        assertNotNull(parsed)
        assertEquals(com.example.update.AdminDialogMode.ANNOUNCEMENT, parsed!!.dialogMode)
        assertEquals("festive_promo_01", parsed.dialogId)
        assertTrue(parsed.showOnce)
        assertFalse(parsed.isUpdateLockRequired)

        val ui = parsed.uiConfig
        assertEquals(com.example.update.AdminDialogLayoutStyle.HERO_MEDIA, ui.resolveEffectiveLayoutStyle())
        assertEquals(com.example.update.AdminEntranceAnimation.SLIDE_UP, ui.entranceAnimation)
        assertEquals(com.example.update.AdminAnimationType.CONFETTI, ui.animationType)
        assertTrue(ui.showConfetti)
        assertEquals(com.example.update.AdminMediaType.VIDEO, ui.resolveEffectiveMediaType())
        assertEquals("https://cdn.example.com/demo_guide.mp4?token=123", ui.mediaUrl)
        assertEquals(false, ui.videoLoop)
        assertEquals(true, ui.videoMuted)
        assertEquals(2, ui.buttons.size)
        assertEquals(com.example.update.AdminButtonAction.OPEN_TAB, ui.buttons[1].action)
        assertEquals(3, ui.blocks.size)
        assertEquals(
            com.example.update.AdminMediaType.GIF,
            com.example.update.AdminDialogUiConfig.inferMediaTypeFromUrl(ui.blocks[1].mediaUrl)
        )

        // Evaluates as UpdateAvailable even when version == currentVersion ("2.2") and apk_url is empty
        val evalResult = UpdateRepository.evaluateUpdate(parsed, currentVersion = "2.2")
        assertTrue(evalResult is UpdateFetchResult.UpdateAvailable)

        // Syncing announcement mode must NOT lock the app or voice engine
        UpdateLockManager.syncFromFetchResult(context, evalResult)
        assertFalse(UpdateLockManager.isUpdateLockActive(context))

        // show_once dismissal tracking
        assertFalse(UpdateLockManager.isDialogDismissed(context, parsed))
        UpdateLockManager.markDialogDismissed(context, parsed)
        assertTrue(UpdateLockManager.isDialogDismissed(context, parsed))
    }

    @Test
    fun `integrity guard decodes masked endpoint detects altered firebase url and forces mandatory update when tampered`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        try {
            assertEquals(
                "com.aistudio.batteryboss.kxzpwn",
                com.example.security.AppIntegrityGuard.resolveExpectedPackageName()
            )
            assertEquals(
                "99D13B137520F8D13E3B2A51F4E51219E4DC2E1486595EAEA8BDD423D6782F11",
                com.example.security.AppIntegrityGuard.resolveExpectedDebugCertSha256()
            )
            assertTrue(com.example.security.AppIntegrityGuard.isEndpointHashValid())
            assertFalse(
                com.example.security.AppIntegrityGuard.isEndpointHashValid(
                    baseUrl = "https://attacker-fake-db.firebaseio.com",
                    nodePath = "battery_boss_update"
                )
            )
            assertFalse(com.example.security.AppIntegrityGuard.isTampered(context))

            // Simulate APK code/signature tampering
            com.example.security.AppIntegrityGuard.setTamperOverrideForTest(true)
            assertTrue(com.example.security.AppIntegrityGuard.isTampered(context))
            assertTrue(UpdateLockManager.isUpdateLockActive(context))

            val forcedUpdate = UpdateLockManager.getCachedUpdateInfo(context)
            assertNotNull(forcedUpdate)
            assertTrue(forcedUpdate!!.mandatory)
            assertEquals("OFFICIAL UPDATE REQUIRED", forcedUpdate.title)
        } finally {
            com.example.security.AppIntegrityGuard.setTamperOverrideForTest(null)
            UpdateLockManager.clearLock(context)
        }
    }
}
