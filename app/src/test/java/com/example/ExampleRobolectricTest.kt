package com.example

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import com.example.audio.IndependentVoiceController
import com.example.audio.PcmLoudnessProcessor
import com.example.audio.QueuedVoiceAnnouncement
import com.example.audio.VoicePlaybackEngine
import com.example.hardware.BatteryHardwareReader
import com.example.model.AnnouncementPriority
import com.example.model.ChargingSource
import com.example.model.ChargingSpeedTier
import com.example.model.DefaultBossMessages
import com.example.model.MetricConfidence
import com.example.model.UserSettings
import com.example.model.VoiceLanguage
import com.example.model.VoiceProfilePreset
import com.example.model.VoiceVolumePreset
import com.example.voice.ProsodyAndPhoneticsEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app_name from context matches Battery Boss`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Battery Boss", appName)
    }

    @Test
    fun `all four independent voice profiles, exact neural model IDs and Smart Boss 2_0 phrases match spec`() {
        val presets = VoiceProfilePreset.entries
        assertEquals(4, presets.size)
        assertEquals("hi_IN-priyamvada-medium", VoiceProfilePreset.HINDI_FEMALE.piperModelId)
        assertEquals("hi_IN-rohan-medium", VoiceProfilePreset.HINDI_MALE.piperModelId)
        assertEquals("en_US-lessac-medium", VoiceProfilePreset.ENGLISH_FEMALE.piperModelId)
        assertEquals("en_US-ryan-medium", VoiceProfilePreset.ENGLISH_MALE.piperModelId)

        assertEquals("Priyamvada (प्रियम्वदा)", VoiceProfilePreset.HINDI_FEMALE.personaName)
        assertEquals("Rohan (रोहन)", VoiceProfilePreset.HINDI_MALE.personaName)
        assertTrue(VoiceProfilePreset.ENGLISH_FEMALE.personaName.contains("Lessac"))
        assertTrue(VoiceProfilePreset.ENGLISH_MALE.personaName.contains("Ryan"))

        // Verify exact Smart Boss 2.0 Hindi phrases (with live {level} percentage placeholders)
        assertEquals("बॉस, चार्जर लग गया है। अभी बैटरी {level} प्रतिशत है।", DefaultBossMessages.HI_CHARGER_CONNECTED)
        assertEquals("चार्जर लग गया है बॉस। अब बैटरी चार्ज हो रही है।", DefaultBossMessages.HI_CHARGER_CONNECTED_NO_PERCENT)
        assertEquals("बॉस, बैटरी 20 प्रतिशत हो गई है।", DefaultBossMessages.HI_BATTERY_20)
        assertEquals("बॉस, अब बैटरी आधी चार्ज हो चुकी है।", DefaultBossMessages.HI_BATTERY_50)
        assertEquals("बॉस, बैटरी 80 प्रतिशत तक पहुँच गई है।", DefaultBossMessages.HI_BATTERY_80)
        assertEquals("बॉस, बैटरी फुल हो गई है। अब चार्जर निकाल सकते हैं।", DefaultBossMessages.HI_BATTERY_FULL)
        assertEquals("बॉस, मोबाइल थोड़ा ज्यादा गर्म हो रहा है।", DefaultBossMessages.HI_HIGH_TEMP)
        assertEquals("बॉस, चार्जर निकाल दिया गया है। अभी बैटरी {level} प्रतिशत है।", DefaultBossMessages.HI_CHARGER_DISCONNECTED)
        assertEquals("बॉस, चार्जर निकाल दिया गया है।", DefaultBossMessages.HI_CHARGER_DISCONNECTED_NO_PERCENT)
    }

    @Test
    fun `independent voice volume controller defaults to 100 percent 1_0f, supports 100 to 200 percent boost presets, and locks AudioTrack at 1_0f unity`() {
        // 1 & 2. Fresh installation defaults in UserSettings, AudioDiagnosticsState, and IndependentVoiceController must be 1.0f (100%)
        val defaultSettings = UserSettings()
        assertEquals(1.0f, defaultSettings.voiceVolume, 0.001f)
        assertEquals(1.0f, defaultSettings.lastRememberedVoiceVolume, 0.001f)
        assertTrue(defaultSettings.independentVolumeEnabled)
        assertTrue(defaultSettings.autoLoudnessNormalizationEnabled)

        val defaultDiagnostics = com.example.model.AudioDiagnosticsState()
        assertEquals(100, defaultDiagnostics.independentVolumePercent)
        assertEquals(1.0f, defaultDiagnostics.effectiveGainValue, 0.001f)

        val controller = IndependentVoiceController()
        assertEquals(1.0f, controller.getEffectiveGain(isPreviewOrTest = true), 0.001f)
        assertEquals(1.0f, controller.getEffectiveGain(isPreviewOrTest = false), 0.001f)

        // 3. Verify standard and Boost presets: 20%, 40%, 60%, 80%, 100%, 125%, 150%, 175%, 200%
        assertEquals(0.20f, VoiceVolumePreset.VERY_LOW.volumeFraction, 0.001f)
        assertEquals(0.40f, VoiceVolumePreset.LOW.volumeFraction, 0.001f)
        assertEquals(0.60f, VoiceVolumePreset.MEDIUM.volumeFraction, 0.001f)
        assertEquals(0.80f, VoiceVolumePreset.HIGH.volumeFraction, 0.001f)
        assertEquals(1.00f, VoiceVolumePreset.MAXIMUM.volumeFraction, 0.001f)
        assertEquals("100%", VoiceVolumePreset.MAXIMUM.percentLabel)
        assertEquals(1.25f, VoiceVolumePreset.BOOST_125.volumeFraction, 0.001f)
        assertEquals("125%", VoiceVolumePreset.BOOST_125.percentLabel)
        assertEquals(1.50f, VoiceVolumePreset.BOOST_150.volumeFraction, 0.001f)
        assertEquals("150%", VoiceVolumePreset.BOOST_150.percentLabel)
        assertEquals(1.75f, VoiceVolumePreset.BOOST_175.volumeFraction, 0.001f)
        assertEquals("175%", VoiceVolumePreset.BOOST_175.percentLabel)
        assertEquals(2.00f, VoiceVolumePreset.BOOST_200.volumeFraction, 0.001f)
        assertEquals("200%", VoiceVolumePreset.BOOST_200.percentLabel)

        // Test 0% silent PCM scaling
        controller.updateConfiguration(volume = 0.0f, independentEnabled = true, applyToAll = true)
        val pcmSilence = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(20000.toShort())
            .putShort((-20000).toShort())
            .array()
        controller.applyPcm16VolumeInPlace(pcmSilence, pcmSilence.size, controller.getEffectiveGain(false))
        val silenceRead = ByteBuffer.wrap(pcmSilence).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, silenceRead.short.toInt())
        assertEquals(0, silenceRead.short.toInt())

        // Verify user-selected 50% (0.50f) volume is linear below knee and AudioTrack stream gain stays at 1.0f unity
        controller.updateConfiguration(volume = 0.50f, independentEnabled = true, applyToAll = true)
        val effectiveAnnouncementGain = controller.getEffectiveGain(isPreviewOrTest = false)
        val audioTrackHardwareGainPcm16 = controller.getAudioTrackStreamGain(isPcm16SoftwareScaled = true, isPreviewOrTest = false)
        assertEquals(0.50f, effectiveAnnouncementGain, 0.001f)
        assertEquals(1.0f, audioTrackHardwareGainPcm16, 0.001f)

        val pcmHalf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(20000.toShort())
            .putShort((-16000).toShort())
            .array()
        controller.applyPcm16VolumeInPlace(pcmHalf, pcmHalf.size, effectiveAnnouncementGain)
        val halfRead = ByteBuffer.wrap(pcmHalf).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10000, (halfRead.short.toInt() * audioTrackHardwareGainPcm16).toInt())
        assertEquals(-8000, (halfRead.short.toInt() * audioTrackHardwareGainPcm16).toInt())

        // Verify 125%, 150%, 175%, 200% Boost values are preserved and AudioTrack stays at 1.0f unity
        for (boost in listOf(1.25f, 1.50f, 1.75f, 2.00f)) {
            controller.updateConfiguration(volume = boost, independentEnabled = true, applyToAll = true)
            assertEquals(boost, controller.getEffectiveGain(false), 0.001f)
            assertEquals(1.0f, controller.getAudioTrackStreamGain(isPcm16SoftwareScaled = true, isPreviewOrTest = false), 0.001f)
        }

        // Verify clamping above 200% (2.0f)
        controller.updateConfiguration(volume = 2.5f, independentEnabled = true, applyToAll = true)
        assertEquals(2.0f, controller.getEffectiveGain(false), 0.001f)

        // Verify SettingsDataStore default is 1.0f (100%) and persists 150% (1.50f) and 200% (2.00f) boost
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore = com.example.data.preferences.SettingsDataStore(context)
        kotlinx.coroutines.runBlocking {
            val initialSnapshot = dataStore.getSettingsSnapshot()
            assertEquals(1.0f, initialSnapshot.voiceVolume, 0.001f)

            dataStore.updateIndependentVolumeSettings(volume = 1.75f, rememberLastVolume = true)
            val boostedSnapshot = dataStore.getSettingsSnapshot()
            assertEquals(1.75f, boostedSnapshot.voiceVolume, 0.001f)
            assertEquals(1.75f, boostedSnapshot.lastRememberedVoiceVolume, 0.001f)

            // Restore 1.0f for clean state
            dataStore.updateIndependentVolumeSettings(volume = 1.0f, rememberLastVolume = true)
        }
    }

    @Test
    fun `pcm loudness processor measures raw WAV RMS and Peak dBFS, auto-normalizes weak speech, boosts 100 to 200 percent, and soft-knee limits without clipping`() {
        // Generate a realistic weak TTS speech waveform (22050 Hz, 1000 samples, sine wave with peak 4000 / -18.26 dBFS, RMS ~2828 / -21.28 dBFS)
        val numSamples = 1000
        fun buildSinePcm(peakAmplitude: Int): ByteArray {
            val buf = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (n in 0 until numSamples) {
                val sample = (peakAmplitude * sin(2.0 * PI * 440.0 * n / 22050.0)).roundToInt().toShort()
                buf.putShort(sample)
            }
            return buf.array()
        }

        val rawQuietPcm = buildSinePcm(peakAmplitude = 4000)
        val analysis = PcmLoudnessProcessor.analyzeRawPcm16(rawQuietPcm, rawQuietPcm.size, 22050, 1)

        // 1. Verify raw TTS WAV loudness measurement (RMS, Peak dBFS, and sample amplitude)
        assertEquals(numSamples, analysis.sampleCount)
        assertEquals(4000, analysis.originalPeakSample)
        assertTrue(analysis.originalRmsSample in 2800..2860)
        assertEquals(-18.26f, analysis.originalPeakDbfs, 0.15f)
        assertEquals(-21.28f, analysis.originalRmsDbfs, 0.15f)

        // 2. Verify automatic loudness normalization computes makeup gain (> 2.4x) for weak WAV
        val normGain = PcmLoudnessProcessor.computeAutoNormalizationGain(analysis, autoNormalizeEnabled = true)
        assertTrue("Expected auto-normalization gain > 2.4x for quiet WAV, got $normGain", normGain > 2.4f)

        // 3. Verify 100%, 125%, 150%, 175%, 200% boost monotonically increases actual PCM RMS while limiter prevents clipping
        val rmsByBoost = mutableListOf<Int>()
        for (boost in listOf(1.00f, 1.25f, 1.50f, 1.75f, 2.00f)) {
            val testPcm = buildSinePcm(peakAmplitude = 4000)
            val metrics = PcmLoudnessProcessor.applyGainAndSoftKneeLimiterInPlace(
                pcmBytes = testPcm,
                bytesLength = testPcm.size,
                userGain = boost,
                normalizationGain = normGain,
                rawAnalysis = analysis,
                sampleRateHz = 22050,
                channels = 1
            )
            assertTrue("Final RMS (${metrics.finalRmsSample}) must be significantly louder than Original RMS (${metrics.originalRmsSample})",
                metrics.finalRmsSample > metrics.originalRmsSample * 2)
            assertEquals(0, metrics.hardClippedSamples)
            assertTrue("Final Peak (${metrics.finalPeakSample}) must stay within limiter ceiling (32275)",
                metrics.finalPeakSample <= 32275)
            rmsByBoost.add(metrics.finalRmsSample)
        }

        // Verify strict monotonic loudness increase from 100% -> 125% -> 150% -> 175% -> 200%
        assertTrue("125% RMS (${rmsByBoost[1]}) > 100% RMS (${rmsByBoost[0]})", rmsByBoost[1] > rmsByBoost[0])
        assertTrue("150% RMS (${rmsByBoost[2]}) > 125% RMS (${rmsByBoost[1]})", rmsByBoost[2] > rmsByBoost[1])
        assertTrue("175% RMS (${rmsByBoost[3]}) > 150% RMS (${rmsByBoost[2]})", rmsByBoost[3] > rmsByBoost[2])
        assertTrue("200% RMS (${rmsByBoost[4]}) > 175% RMS (${rmsByBoost[3]})", rmsByBoost[4] > rmsByBoost[3])

        // 4. Verify Soft-Knee Limiter engages on high-amplitude input at 200% boost with ZERO hard clipping
        val highInputPcm = buildSinePcm(peakAmplitude = 24000)
        val limiterMetrics = PcmLoudnessProcessor.applyGainAndSoftKneeLimiterInPlace(
            pcmBytes = highInputPcm,
            bytesLength = highInputPcm.size,
            userGain = 2.00f, // 200% boost on 24000 peak -> 48000 raw before limiter
            normalizationGain = 1.0f,
            sampleRateHz = 22050,
            channels = 1
        )
        assertTrue(limiterMetrics.limiterEngaged)
        assertTrue(limiterMetrics.limiterEngagedSamples > 0)
        assertEquals(0, limiterMetrics.hardClippedSamples)
        assertTrue(limiterMetrics.finalPeakSample in 26870..32275)
        assertTrue(limiterMetrics.finalRmsSample > limiterMetrics.originalRmsSample)
    }

    @Test
    fun `all playback entry points apply identical loudness normalization and boost at Media Volume 0 percent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempWav = File(context.cacheDir, "charger_connected_loudness_test.wav")
        try {
            // Create a valid 16-bit 22050 Hz Mono RIFF WAV file representing a quiet TTS synthesis (peak = 5000)
            val sampleCount = 1102 // ~50 ms at 22050 Hz
            val pcmPayload = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                val s = (5000.0 * sin(2.0 * PI * 300.0 * i / 22050.0)).roundToInt().toShort()
                pcmPayload.putShort(s)
            }
            val pcmBytes = pcmPayload.array()
            val totalRiffSize = 4 + (8 + 16) + (8 + pcmBytes.size)
            val wavBuf = ByteBuffer.allocate(8 + totalRiffSize).order(ByteOrder.LITTLE_ENDIAN)
            wavBuf.put("RIFF".toByteArray(Charsets.US_ASCII))
            wavBuf.putInt(totalRiffSize)
            wavBuf.put("WAVE".toByteArray(Charsets.US_ASCII))
            wavBuf.put("fmt ".toByteArray(Charsets.US_ASCII))
            wavBuf.putInt(16)
            wavBuf.putShort(1) // PCM
            wavBuf.putShort(1) // Mono
            wavBuf.putInt(22050)
            wavBuf.putInt(22050 * 2)
            wavBuf.putShort(2)
            wavBuf.putShort(16)
            wavBuf.put("data".toByteArray(Charsets.US_ASCII))
            wavBuf.putInt(pcmBytes.size)
            wavBuf.put(pcmBytes)
            tempWav.writeBytes(wavBuf.array())

            val decoded = VoicePlaybackEngine.decodeRiffWavHeader(tempWav)
            assertNotNull(decoded)

            val volController = IndependentVoiceController()
            // Configure 200% Boost (2.0f) + Automatic Loudness Normalization ON
            volController.updateConfiguration(
                volume = 2.0f,
                independentEnabled = true,
                applyToAll = true,
                autoLoudnessNormalization = true
            )
            val engine = VoicePlaybackEngine(context, volController)

            val entryPoints = listOf(
                "Charger Connected" to false,
                "Charger Disconnected" to false,
                "Smart Milestone (50%)" to false,
                "Battery Full (100%)" to false,
                "Low Battery (15%)" to false,
                "Thermal Alert (43.5°C)" to false,
                "Voice Test" to true,
                "Voice Preview (Hindi Female)" to true,
                "Custom Announcement" to false
            )

            kotlinx.coroutines.runBlocking {
                for ((entryPoint, isPreviewOrTest) in entryPoints) {
                    val played = engine.playWavFileThroughAudioTrack(
                        wavFile = tempWav,
                        decodedWav = decoded!!,
                        independentModeEnabled = true,
                        isPreviewOrTest = isPreviewOrTest,
                        entryPoint = entryPoint,
                        selectedEngineName = "com.google.android.tts",
                        selectedVoiceName = "hi-in-x-hid-local (hi-IN • Q=400)",
                        systemMediaVolumePercent = 0,     // Scenario 1: Media Volume = 0%
                        systemAlarmVolumePercent = 100    // Scenario 1: Alarm Volume = 100%
                    )
                    assertTrue("Expected AudioTrack playback to succeed for $entryPoint at Media=0%, Alarm=100%", played)

                    val trace = engine.lastTraceRecord.value
                    assertEquals(entryPoint, trace.entryPoint)
                    assertTrue(trace.audioTrackCreated)
                    assertFalse(trace.directTtsFallbackUsed)
                    assertEquals("USAGE_ALARM (4)", trace.actualAudioAttributesUsage)
                    assertEquals("CONTENT_TYPE_SPEECH (1)", trace.actualAudioAttributesContentType)
                    assertTrue(trace.reportedVolumeControlStream.contains("STREAM_ALARM"))
                    assertTrue(trace.effectiveOsPolicyStream.contains("STREAM_ALARM"))
                    assertTrue(trace.isControlledByAlarmVolume)
                    assertFalse(trace.doesMediaVolumeChangeLoudness)
                    assertEquals(1.0f, trace.audioTrackStreamGain, 0.001f) // Unity gain on AudioTrack
                    assertEquals(2.0f, trace.effectivePcmGain, 0.001f)     // 200% user boost in PCM
                    assertTrue(trace.normalizationGain > 1.5f)             // Auto-normalization applied
                    assertTrue(trace.loudnessMetrics.finalRmsSample > trace.loudnessMetrics.originalRmsSample * 2)
                    assertEquals(0, trace.loudnessMetrics.hardClippedSamples)
                    assertEquals(0, trace.systemMediaVolumePercent)
                    assertEquals(100, trace.systemAlarmVolumePercent)
                }
            }
        } finally {
            tempWav.delete()
        }
    }

    @Test
    fun `android audio policy auditor accurately audits volume groups, reports when USAGE_ASSISTANCE_ACCESSIBILITY maps to STREAM_MUSIC, and evaluates Media Volume 0, 50, and 100 percent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val auditor = com.example.audio.AndroidAudioPolicyAuditor(context)

        // 1. Audit USAGE_ASSISTANCE_ACCESSIBILITY when no AccessibilityService is active on the device
        val a11yReport = auditor.auditDeviceAudioPolicy(
            selectedOption = com.example.model.AudioStreamUsageOption.ACCESSIBILITY_SPEECH,
            independentModeEnabled = true
        )
        assertEquals("USAGE_ASSISTANCE_ACCESSIBILITY (11)", a11yReport.actualAudioAttributesUsageLabel)
        // AudioAttributes.volumeControlStream reports STREAM_ACCESSIBILITY (10),
        // but effective policy stream without an active AccessibilityService is STREAM_MUSIC (3)!
        assertEquals(android.media.AudioManager.STREAM_ACCESSIBILITY, a11yReport.reportedVolumeControlStream)
        assertEquals(android.media.AudioManager.STREAM_MUSIC, a11yReport.effectivePolicyStreamType)
        assertTrue(a11yReport.isMappedToStreamMusic)
        assertTrue(a11yReport.doesMediaVolumeChangeLoudness)
        assertFalse(a11yReport.isIndependentAppPrivateStreamSupported)
        assertEquals(
            "INDEPENDENT SYSTEM STREAM UNSUPPORTED (MAPPED TO STREAM_MUSIC)",
            a11yReport.streamIndependenceVerdictBadge
        )
        assertEquals(6, a11yReport.allStreamSnapshots.size)
        assertEquals(5, a11yReport.allUsageAudits.size)
        assertEquals(3, a11yReport.mediaVolumeStepObservations.size)
        assertEquals(listOf(0, 50, 100), a11yReport.mediaVolumeStepObservations.map { it.targetMediaPercent })

        // 2. Audit default USAGE_ALARM (STREAM_ALARM) and the 3 Media vs. Alarm Volume test scenarios
        val alarmReport = auditor.auditDeviceAudioPolicy(
            selectedOption = com.example.model.AudioStreamUsageOption.ALARM_SPEECH,
            independentModeEnabled = true
        )
        assertEquals("USAGE_ALARM (4)", alarmReport.actualAudioAttributesUsageLabel)
        assertEquals(android.media.AudioManager.STREAM_ALARM, alarmReport.reportedVolumeControlStream)
        assertEquals(android.media.AudioManager.STREAM_ALARM, alarmReport.effectivePolicyStreamType)
        assertTrue(alarmReport.isControlledByAlarmVolume)
        assertFalse(alarmReport.isMappedToStreamMusic)
        assertFalse(alarmReport.doesMediaVolumeChangeLoudness)
        assertTrue(alarmReport.isSeparateFromMediaStream)
        assertFalse(alarmReport.isIndependentAppPrivateStreamSupported) // Controlled by Alarm slider, not app-private
        assertNotNull(alarmReport.systemAlarmVolumePercent)
        assertEquals(3, alarmReport.alarmMediaTestMatrix.size)
        assertEquals(0, alarmReport.alarmMediaTestMatrix[0].targetMediaPercent)
        assertEquals(100, alarmReport.alarmMediaTestMatrix[0].targetAlarmPercent)
        assertEquals(100, alarmReport.alarmMediaTestMatrix[1].targetMediaPercent)
        assertEquals(0, alarmReport.alarmMediaTestMatrix[1].targetAlarmPercent)
        assertEquals(50, alarmReport.alarmMediaTestMatrix[2].targetMediaPercent)
        assertEquals(50, alarmReport.alarmMediaTestMatrix[2].targetAlarmPercent)

        // Verify recording live playback across all 3 Media vs. Alarm Volume scenarios:
        // (1) Media=0%, Alarm=100%, (2) Media=100%, Alarm=0%, (3) Media=50%, Alarm=50%
        val scenarios = listOf(
            0 to 100,
            100 to 0,
            50 to 50
        )
        for ((mediaPct, alarmPct) in scenarios) {
            auditor.recordPlaybackObservation(
                mediaVolumePercent = mediaPct,
                alarmVolumePercent = alarmPct,
                activeOption = com.example.model.AudioStreamUsageOption.ALARM_SPEECH,
                effectivePolicyStreamType = android.media.AudioManager.STREAM_ALARM,
                effectiveStreamPercent = alarmPct,
                effectiveStreamDb = when (alarmPct) {
                    0 -> -758.0f
                    50 -> -15.0f
                    else -> 0.0f
                },
                pcmFinalRmsDbfs = -11.2f
            )
        }
        val verifiedAlarmReport = auditor.auditDeviceAudioPolicy(
            selectedOption = com.example.model.AudioStreamUsageOption.ALARM_SPEECH,
            independentModeEnabled = true
        )
        assertTrue(verifiedAlarmReport.alarmMediaTestMatrix.all { it.verifiedLiveOnDevice })
        assertTrue(verifiedAlarmReport.alarmMediaTestMatrix[0].expectedAndObservedResult.contains("AUDIBLE"))
        assertTrue(verifiedAlarmReport.alarmMediaTestMatrix[1].expectedAndObservedResult.contains("mutes/attenuates"))
        assertTrue(verifiedAlarmReport.alarmMediaTestMatrix[2].expectedAndObservedResult.contains("50% Alarm Volume"))

        val notifReport = auditor.auditDeviceAudioPolicy(
            selectedOption = com.example.model.AudioStreamUsageOption.NOTIFICATION_SPEECH,
            independentModeEnabled = true
        )
        assertEquals("USAGE_NOTIFICATION_EVENT (10)", notifReport.actualAudioAttributesUsageLabel)
        assertEquals(android.media.AudioManager.STREAM_NOTIFICATION, notifReport.effectivePolicyStreamType)
        assertFalse(notifReport.isMappedToStreamMusic)
        assertFalse(notifReport.doesMediaVolumeChangeLoudness)

        val sonifReport = auditor.auditDeviceAudioPolicy(
            selectedOption = com.example.model.AudioStreamUsageOption.SONIFICATION_SPEECH,
            independentModeEnabled = true
        )
        assertEquals("USAGE_ASSISTANCE_SONIFICATION (13)", sonifReport.actualAudioAttributesUsageLabel)
        assertEquals(android.media.AudioManager.STREAM_SYSTEM, sonifReport.effectivePolicyStreamType)
        assertFalse(sonifReport.isMappedToStreamMusic)
        assertFalse(sonifReport.doesMediaVolumeChangeLoudness)

        // 3. Verify playback observations recorded at Media Volume = 0%, 50%, and 100%
        for (mediaStep in listOf(0, 50, 100)) {
            auditor.recordPlaybackObservation(
                mediaVolumePercent = mediaStep,
                activeOption = com.example.model.AudioStreamUsageOption.ALARM_SPEECH,
                effectivePolicyStreamType = android.media.AudioManager.STREAM_ALARM,
                effectiveStreamPercent = 100,
                effectiveStreamDb = 0.0f,
                pcmFinalRmsDbfs = -11.2f
            )
        }
        val verifiedReport = auditor.auditDeviceAudioPolicy(
            selectedOption = com.example.model.AudioStreamUsageOption.ALARM_SPEECH,
            independentModeEnabled = true
        )
        assertTrue(verifiedReport.mediaVolumeStepObservations.all { it.verifiedByPlaybackAtThisLevel })
        assertTrue(verifiedReport.mediaVolumeStepObservations[0].expectedAndObservedBehavior.contains("Media = 0%"))
        assertTrue(verifiedReport.mediaVolumeStepObservations[1].expectedAndObservedBehavior.contains("Media = 50%"))
        assertTrue(verifiedReport.mediaVolumeStepObservations[2].expectedAndObservedBehavior.contains("Media = 100%"))
    }

    @Test
    fun `voice priority queue orders Priority 1 Critical ahead of Priority 2 Important and Priority 3 Normal`() {
        val pq = PriorityBlockingQueue<QueuedVoiceAnnouncement>()
        val normalItem = QueuedVoiceAnnouncement(
            eventType = "Milestone 50%",
            rawMessage = "50%",
            clauses = listOf("50%"),
            priority = AnnouncementPriority.NORMAL,
            preset = VoiceProfilePreset.HINDI_FEMALE,
            speed = 1.0f,
            pitch = 1.0f
        )
        val importantItem = QueuedVoiceAnnouncement(
            eventType = "Charger Connected",
            rawMessage = "Connected",
            clauses = listOf("Connected"),
            priority = AnnouncementPriority.IMPORTANT,
            preset = VoiceProfilePreset.HINDI_FEMALE,
            speed = 1.0f,
            pitch = 1.0f
        )
        val criticalItem = QueuedVoiceAnnouncement(
            eventType = "Thermal Alert",
            rawMessage = "Overheating",
            clauses = listOf("Overheating"),
            priority = AnnouncementPriority.CRITICAL,
            preset = VoiceProfilePreset.HINDI_FEMALE,
            speed = 1.0f,
            pitch = 1.0f
        )

        pq.offer(normalItem)
        pq.offer(importantItem)
        pq.offer(criticalItem)

        assertEquals(AnnouncementPriority.CRITICAL, pq.poll()?.priority)
        assertEquals(AnnouncementPriority.IMPORTANT, pq.poll()?.priority)
        assertEquals(AnnouncementPriority.NORMAL, pq.poll()?.priority)
    }

    @Test
    fun `prosody and phonetics engine segments clauses and expands Hindi and English symbols naturally`() {
        val hindiClauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
            rawText = "चार्जर लग गया है बॉस। अब बैटरी 50% चार्ज हो रही है।",
            language = VoiceLanguage.HINDI,
            naturalProsodyEnabled = true
        )
        assertEquals(2, hindiClauses.size)
        assertEquals("चार्जर लग गया है बॉस।", hindiClauses[0])
        assertTrue(hindiClauses[1].contains("50 प्रतिशत"))

        val englishClauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
            rawText = "Charger connected, Boss. Temperature is 39°C at 1800 mA.",
            language = VoiceLanguage.ENGLISH,
            naturalProsodyEnabled = true
        )
        assertEquals(2, englishClauses.size)
        assertTrue(englishClauses[1].contains("degrees Celsius"))
        assertTrue(englishClauses[1].contains("milliamps"))
    }

    @Test
    fun `deep battery diagnostics parses cycle count, voltage, temp and keeps missing sensors null with explanation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reader = BatteryHardwareReader(context)

        val batteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 80)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4320) // 4.320 V
            putExtra(BatteryManager.EXTRA_TEMPERATURE, 368) // 36.8 °C
            putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            putExtra(BatteryManager.EXTRA_TECHNOLOGY, "Li-ion")
            putExtra("android.os.extra.CYCLE_COUNT", 142)
        }

        val snapshot = reader.readSnapshot(batteryIntent)
        assertEquals(80, snapshot.percentage)
        assertTrue(snapshot.isCharging)
        assertEquals(ChargingSource.AC, snapshot.chargingSource)
        assertEquals(4.32f, snapshot.voltageVolts ?: 0f, 0.01f)
        assertEquals(36.8f, snapshot.temperatureCelsius ?: 0f, 0.05f)
        assertEquals(142, snapshot.cycleCount)
        assertEquals("Good", snapshot.healthLabel)

        // Verify Android GOOD status is NEVER claimed as 100% battery health
        assertFalse(snapshot.healthReport.osHealthExplanation.contains("100% battery health"))
        assertTrue(snapshot.healthReport.osHealthExplanation.contains("does NOT mean 100% capacity retention"))

        // Missing current sensor in JVM test environment stays null and charging speed reports UNAVAILABLE
        assertNull(snapshot.currentMilliAmps)
        assertEquals(ChargingSpeedTier.UNAVAILABLE, snapshot.chargingSpeedTier)

        // Verify diagnostic metrics list has source & confidence metadata
        assertTrue(snapshot.diagnosticMetrics.isNotEmpty())
        val currentMetric = snapshot.diagnosticMetrics.first { it.title == "Instant Battery Current" }
        assertEquals("N/A", currentMetric.value)
        assertEquals(MetricConfidence.UNAVAILABLE, currentMetric.confidence)
    }

    @Test
    fun `wav decoder parses RIFF chunks and Independent Mode disables direct TTS fallback with explicit error`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempWav = File(context.cacheDir, "test_junk_fmt_data.wav")
        try {
            val pcmPayload = ByteArray(64) { (it % 10).toByte() }
            val totalRiffSize = 4 + (8 + 12) + (8 + 16) + (8 + pcmPayload.size)
            val buf = ByteBuffer.allocate(8 + totalRiffSize).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray(Charsets.US_ASCII))
            buf.putInt(totalRiffSize)
            buf.put("WAVE".toByteArray(Charsets.US_ASCII))
            // JUNK chunk
            buf.put("JUNK".toByteArray(Charsets.US_ASCII))
            buf.putInt(12)
            buf.put(ByteArray(12))
            // fmt chunk (PCM=1, Mono=1, 22050 Hz, 16-bit)
            buf.put("fmt ".toByteArray(Charsets.US_ASCII))
            buf.putInt(16)
            buf.putShort(1) // PCM
            buf.putShort(1) // Mono
            buf.putInt(22050)
            buf.putInt(22050 * 2)
            buf.putShort(2)
            buf.putShort(16)
            // data chunk
            buf.put("data".toByteArray(Charsets.US_ASCII))
            buf.putInt(pcmPayload.size)
            buf.put(pcmPayload)
            tempWav.writeBytes(buf.array())

            val decoded = VoicePlaybackEngine.decodeRiffWavHeader(tempWav)
            assertNotNull(decoded)
            assertEquals(1, decoded!!.audioFormatCode)
            assertEquals(1, decoded.channels)
            assertEquals(22050, decoded.sampleRateHz)
            assertEquals(16, decoded.bitsPerSample)
            assertEquals(64L, decoded.dataSizeBytes)

            val volController = IndependentVoiceController()
            volController.updateConfiguration(volume = 1.0f, independentEnabled = true, applyToAll = true)
            val engine = VoicePlaybackEngine(context, volController)
            val dummyTts = android.speech.tts.TextToSpeech(context) {}
            val testItem = QueuedVoiceAnnouncement(
                eventType = "Voice Test",
                rawMessage = "Hello Boss",
                clauses = listOf("Hello Boss"),
                priority = AnnouncementPriority.IMPORTANT,
                preset = VoiceProfilePreset.ENGLISH_FEMALE,
                speed = 1.0f,
                pitch = 1.0f,
                isPreviewOrTest = true
            )

            kotlinx.coroutines.runBlocking {
                val result = engine.synthesizeAndPlayItem(
                    tts = dummyTts,
                    item = testItem,
                    naturalProsodyEnabled = true,
                    systemMediaVolumePercent = 0,
                    synthesizeTimeoutMs = 150L
                )
                assertFalse(result)
                assertFalse(engine.lastTraceRecord.value.directTtsFallbackUsed)
                assertEquals(
                    "Independent Voice Playback Unavailable – TTS fallback is disabled.",
                    engine.lastErrorText.value
                )
                assertEquals(
                    "USAGE_ALARM (4)",
                    engine.lastTraceRecord.value.actualAudioAttributesUsage
                )
                assertEquals(
                    "CONTENT_TYPE_SPEECH (1)",
                    engine.lastTraceRecord.value.actualAudioAttributesContentType
                )
                assertTrue(engine.lastTraceRecord.value.isControlledByAlarmVolume)
            }
            dummyTts.shutdown()
        } finally {
            tempWav.delete()
        }
    }

    @Test
    fun `compose UI navigates across tabs and displays Independent Voice Volume Control and Audio Diagnostics`() {
        // 1. Verify Splash/Intro screen renders immediately on launch with required text
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("splash_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("splash_title_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("splash_creator_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("BATTERY BOSS 2.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Created by BHAVESH MOD").assertIsDisplayed()

        // 2. Advance past 2.5s splash delay + 450ms crossfade and verify automatic transition to Dashboard
        composeTestRule.mainClock.advanceTimeBy(3_100L)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(3_100L, java.util.concurrent.TimeUnit.MILLISECONDS)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("splash_screen").assertDoesNotExist()
        composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()

        // Verify PRO OFFLINE VOICE STUDIO (4 PROFILES) card is completely removed from Home Screen
        composeTestRule.onNodeWithText("PRO OFFLINE VOICE STUDIO (4 PROFILES)").assertDoesNotExist()

        // Scroll Home Screen to verify Charging History Graph and Deep Hardware Telemetry Matrix are properly positioned
        composeTestRule.onNodeWithTag("dashboard_screen")
            .performScrollToNode(hasTestTag("charging_history_graph_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("charging_history_graph_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("PRO OFFLINE VOICE STUDIO (4 PROFILES)").assertDoesNotExist()

        composeTestRule.onNodeWithTag("dashboard_screen")
            .performScrollToNode(hasTestTag("deep_hardware_telemetry_section"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("deep_hardware_telemetry_section").assertIsDisplayed()
        composeTestRule.onNodeWithText("PRO OFFLINE VOICE STUDIO (4 PROFILES)").assertDoesNotExist()

        // Capture a rendered screenshot of the updated Home Screen (showing Graph + Deep Hardware Telemetry Matrix without the Voice Studio card)
        runCatching {
            val decorView = composeTestRule.activity.window.decorView
            val width = decorView.width.coerceAtLeast(1080)
            val height = decorView.height.coerceAtLeast(2400)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            decorView.draw(canvas)
            val outFile = File("/.aistudio/artifacts/brain/eee7aa37-524c-4c59-8dea-89858b4eac35/home_screen_after_removing_voice_card.png")
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }

        // Navigate to Voice Studio tab and verify compact redesigned Voice Studio sections
        composeTestRule.onNodeWithTag("nav_tab_voice_alerts").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_voice_alerts").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("independent_voice_volume_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("slider_independent_volume").assertIsDisplayed()
        composeTestRule.onNodeWithTag("slider_voice_speed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("slider_voice_pitch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("switch_independent_volume").assertIsDisplayed()
        composeTestRule.onNodeWithTag("switch_auto_loudness_normalization").assertIsDisplayed()

        // Capture a rendered screenshot of the top of the redesigned Voice Studio Screen (Acoustic Controls + 2-Column 4 Voice Profiles Grid)
        runCatching {
            val decorView = composeTestRule.activity.window.decorView
            val width = decorView.width.coerceAtLeast(1080)
            val height = decorView.height.coerceAtLeast(2400)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            decorView.draw(canvas)
            val outFile = File("/.aistudio/artifacts/brain/eee7aa37-524c-4c59-8dea-89858b4eac35/voice_studio_redesign_top.png")
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }

        // Verify 2-column 4 Voice Profiles Grid selection & preview buttons exist
        composeTestRule.onNodeWithTag("voice_settings_screen")
            .performScrollToNode(hasTestTag("voice_studio_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("voice_studio_card").assertIsDisplayed()
        for (preset in VoiceProfilePreset.entries) {
            composeTestRule.onNodeWithTag("studio_preview_${preset.id}").assertIsDisplayed()
            composeTestRule.onNodeWithTag("studio_select_${preset.id}").assertIsDisplayed()
        }

        // Scroll to Custom Announcements & Save All Phrases and verify
        composeTestRule.onNodeWithTag("voice_settings_screen")
            .performScrollToNode(hasTestTag("save_custom_messages_button"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("save_custom_messages_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reset_custom_messages_button").assertIsDisplayed()

        // Scroll to Offline Model Registry, Collapsible Audio Stream Routing & Collapsible Audio Diagnostics
        composeTestRule.onNodeWithTag("voice_settings_screen")
            .performScrollToNode(hasTestTag("audio_diagnostics_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("audio_stream_routing_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_diagnostics_card").assertIsDisplayed()

        // Capture a rendered screenshot of the bottom of the redesigned Voice Studio Screen (Model Registry + Collapsible Routing & Diagnostics)
        runCatching {
            val decorView = composeTestRule.activity.window.decorView
            val width = decorView.width.coerceAtLeast(1080)
            val height = decorView.height.coerceAtLeast(2400)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            decorView.draw(canvas)
            val outFile = File("/.aistudio/artifacts/brain/eee7aa37-524c-4c59-8dea-89858b4eac35/voice_studio_redesign_bottom.png")
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }

        // Navigate to Dedicated Battery Health Analysis tab and verify the redesigned compact Health Dashboard
        composeTestRule.onNodeWithTag("nav_tab_battery_health").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("battery_health_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("battery_health_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("battery_health_metrics_grid").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_toggle_all_health_api_details").assertIsDisplayed()

        // Capture a rendered screenshot of the top of the redesigned Battery Health Screen
        runCatching {
            val decorView = composeTestRule.activity.window.decorView
            val width = decorView.width.coerceAtLeast(1080)
            val height = decorView.height.coerceAtLeast(2400)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            decorView.draw(canvas)
            val outFile = File("/.aistudio/artifacts/brain/eee7aa37-524c-4c59-8dea-89858b4eac35/battery_health_redesign_top.png")
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }

        // Verify all 9 core Battery Information Cards exist and are displayed properly
        val requiredHealthCardTags = listOf(
            "health_metric_card_temperature",
            "health_metric_card_voltage",
            "health_metric_card_charging_source",
            "health_metric_card_remaining_capacity",
            "health_metric_card_cycle_count",
            "health_metric_card_design_capacity",
            "health_metric_card_full_charge_capacity",
            "health_metric_card_energy_counter",
            "health_metric_card_estimated_time_to_full"
        )
        for (cardTag in requiredHealthCardTags) {
            composeTestRule.onNodeWithTag("battery_health_screen")
                .performScrollToNode(hasTestTag(cardTag))
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(cardTag).assertIsDisplayed()
        }

        // Toggle "Show API Info" button to verify expandable API & Technical details work cleanly
        composeTestRule.onNodeWithTag("battery_health_screen")
            .performScrollToNode(hasTestTag("btn_toggle_all_health_api_details"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_toggle_all_health_api_details").performClick()
        composeTestRule.waitForIdle()

        // Scroll to Thermal Stress Profile and Collapsible Complete Diagnostic Registers
        composeTestRule.onNodeWithTag("battery_health_screen")
            .performScrollToNode(hasTestTag("complete_diagnostic_registers_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("thermal_stress_profile_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("complete_diagnostic_registers_card").assertIsDisplayed()

        // Navigate to Hardware & OEM tab
        composeTestRule.onNodeWithTag("nav_tab_hardware_oem").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hardware_oem_screen").assertIsDisplayed()

        // Navigate to Smart Battery Tracker & App Power Manager tab
        composeTestRule.onNodeWithTag("nav_tab_smart_tracker").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("smart_battery_tracker_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("smart_tracker_dashboard_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("smart_battery_tracker_screen")
            .performScrollToNode(hasTestTag("smart_battery_saver_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("smart_battery_saver_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("smart_battery_tracker_screen")
            .performScrollToNode(hasTestTag("app_usage_tracker_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("app_usage_tracker_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("smart_battery_tracker_screen")
            .performScrollToNode(hasTestTag("usage_history_records_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("usage_history_records_card").assertIsDisplayed()

        // Navigate to Analytics & History tab
        composeTestRule.onNodeWithTag("nav_tab_history_about").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("history_about_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_charging_analytics_card").assertIsDisplayed()
    }

    @Test
    fun `technical bug fixes 1 to 8 verify strict unit conversions, unclamped capacity retention, confidence metadata, and health status colors`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Bug 2: BATTERY_PROPERTY_CHARGE_COUNTER converts strictly from documented µAh (/ 1000.0) without a 50,000 threshold
        assertEquals(30, BatteryHardwareReader.convertChargeCounterUahToMah(30_000))
        assertEquals(4500, BatteryHardwareReader.convertChargeCounterUahToMah(4_500_000))
        assertNull(BatteryHardwareReader.convertChargeCounterUahToMah(0))
        assertNull(BatteryHardwareReader.convertChargeCounterUahToMah(-5000))
        assertNull(BatteryHardwareReader.convertChargeCounterUahToMah(Int.MIN_VALUE))

        // Bug 3: BATTERY_PROPERTY_CURRENT_NOW and CURRENT_AVERAGE convert strictly from documented µA (/ 1000.0)
        assertEquals(12, BatteryHardwareReader.convertApiCurrentMicroAmpsToMilliAmps(12_000))
        assertEquals(-450, BatteryHardwareReader.convertApiCurrentMicroAmpsToMilliAmps(-450_000))
        assertEquals(15, BatteryHardwareReader.convertSysfsCurrentMicroAmpsToMilliAmps(15_000))
        assertNull(BatteryHardwareReader.convertApiCurrentMicroAmpsToMilliAmps(0))
        assertNull(BatteryHardwareReader.convertApiCurrentMicroAmpsToMilliAmps(Int.MIN_VALUE))

        // Bug 1: Capacity Retention is NOT clamped to 10-110%; impossible ratios (< 20% or > 110%) return null & isInconsistent = true
        val validRetention = BatteryHardwareReader.evaluateCapacityRetention(4400, 5000)
        assertFalse(validRetention.second)
        assertEquals(88.0f, validRetention.first ?: 0f, 0.05f)

        val impossibleLow = BatteryHardwareReader.evaluateCapacityRetention(100, 5000) // 2%
        assertTrue(impossibleLow.second)
        assertNull(impossibleLow.first)

        val impossibleHigh = BatteryHardwareReader.evaluateCapacityRetention(8000, 5000) // 160%
        assertTrue(impossibleHigh.second)
        assertNull(impossibleHigh.first)

        // Bug 4, 5, 6: At 100% battery, Charge Counter is NOT automatically treated as Full Charge Capacity,
        // and PowerProfile Design Capacity is marked MEDIUM_DERIVED (not HIGH_DIRECT).
        val reader = BatteryHardwareReader(context)
        val overridesAt100WithoutFullReg = com.example.hardware.HardwarePropertyOverrides(
            rawApiChargeCounterUah = 4_850_000, // 4850 mAh
            rawApiCurrentNowUa = 150_000,       // 150 mA
            rawApiCurrentAvgUa = 140_000,       // 140 mA
            rawSysfsChargeFullUah = null,       // No charge_full register exposed
            rawSysfsChargeFullDesignUah = null, // No charge_full_design register exposed
            powerProfileDesignCapacityMah = 5000.0 // PowerProfile static XML
        )
        val intent100 = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 100)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4400)
            putExtra(BatteryManager.EXTRA_TEMPERATURE, 310)
        }
        val snap100 = reader.readSnapshot(intent100, overridesAt100WithoutFullReg)
        assertEquals(4850, snap100.chargeCounterMah)
        assertNull("Charge Counter at 100% must NOT become Full Charge Capacity", snap100.fullChargeCapacityMah)
        assertEquals(MetricConfidence.UNAVAILABLE, snap100.healthReport.fullChargeCapacityConfidence)
        assertEquals(MetricConfidence.MEDIUM_DERIVED, snap100.healthReport.designCapacityConfidence)
        assertTrue(snap100.currentSourceApi.contains("µA"))

        // Bug 7: Health Status color mapping for Over Voltage and Dead / Depleted maps to ThermalCrimson
        assertEquals(
            com.example.ui.theme.ThermalCrimson,
            com.example.ui.screens.healthStatusColorForLabel("Over Voltage")
        )
        assertEquals(
            com.example.ui.theme.ThermalCrimson,
            com.example.ui.screens.healthStatusColorForLabel("Dead / Depleted")
        )
        assertEquals(
            com.example.ui.theme.EmeraldCharge,
            com.example.ui.screens.healthStatusColorForLabel("Good")
        )
    }

    @Test
    fun `technical bug fixes 9 and 10 verify BUNDLED_READY is shown ONLY when real Piper onnx and onnx_json files exist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelId = VoiceProfilePreset.HINDI_FEMALE.piperModelId
        val modelDir = File(File(context.filesDir, "offline_voice_models"), modelId)
        val onnxFile = File(modelDir, "$modelId.onnx")
        val jsonFile = File(modelDir, "$modelId.onnx.json")

        try {
            modelDir.deleteRecursively()
            // 1. Even if model_card.json exists in assets or DataStore has IDs, real verification is FALSE without .onnx + .onnx.json
            assertFalse(com.example.voice.OfflineVoiceAssistant.verifyRealPiperModelFiles(context, modelId))

            val dataStore = com.example.data.preferences.SettingsDataStore(context)
            val db = com.example.data.local.BatteryBossDatabase.getInstance(context)
            val repo = com.example.data.local.BatteryBossRepository(db.batteryBossDao())
            val assistant = com.example.voice.OfflineVoiceAssistant(context, dataStore, repo)

            kotlinx.coroutines.runBlocking {
                val verified = assistant.verifyAndSyncModelPackNow(modelId)
                assertFalse(verified)
                val pack = assistant.voiceModelPacks.value.first { it.modelId == modelId }
                assertNotEquals(com.example.model.VoiceModelState.BUNDLED_READY, pack.state)
                assertFalse(pack.isPiperOnnxVerified)
            }

            // 2. Create valid .onnx (>= 1024 bytes) and .onnx.json (with sample_rate & num_speakers)
            modelDir.mkdirs()
            onnxFile.writeBytes(ByteArray(2048) { 0x42 })
            jsonFile.writeText("""{"audio":{"sample_rate":22050},"num_speakers":1,"espeak":{"voice":"hi"}}""")

            assertTrue(com.example.voice.OfflineVoiceAssistant.verifyRealPiperModelFiles(context, modelId))

            kotlinx.coroutines.runBlocking {
                val verifiedNow = assistant.verifyAndSyncModelPackNow(modelId)
                assertTrue(verifiedNow)
                val packNow = assistant.voiceModelPacks.value.first { it.modelId == modelId }
                assertEquals(com.example.model.VoiceModelState.BUNDLED_READY, packNow.state)
                assertTrue(packNow.isPiperOnnxVerified)
            }
            assistant.shutdown()
        } finally {
            modelDir.deleteRecursively()
        }
    }

    @Test
    fun `smart charger connect and disconnect battery percentage announcement passes all 9 test scenarios`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore = com.example.data.preferences.SettingsDataStore(context)
        val db = com.example.data.local.BatteryBossDatabase.getInstance(context)
        val repo = com.example.data.local.BatteryBossRepository(db.batteryBossDao())
        val assistant = com.example.voice.OfflineVoiceAssistant(context, dataStore, repo)

        kotlinx.coroutines.runBlocking {
            dataStore.resetCustomMessagesToDefault()
            dataStore.updateChargingAlerts(
                chargerConnected = true,
                chargerDisconnected = true,
                percentOnConnect = true,
                percentOnDisconnect = true
            )
            dataStore.selectVoicePreset(VoiceProfilePreset.HINDI_FEMALE)

            var clock = 100_000L

            // Scenario 1: Charger Connect at 45% -> speaks 45%
            val snap45Connect = com.example.model.BatteryHardwareSnapshot(
                percentage = 45,
                isPercentageValid = true,
                isCharging = true,
                chargingSource = ChargingSource.AC
            )
            val spoken1 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snap45Connect,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                currentTimeMillis = clock
            )
            assertEquals("बॉस, चार्जर लग गया है। अभी बैटरी 45 प्रतिशत है।", spoken1)

            // Scenario 8: Rapid duplicate broadcast within debounce window -> NOT spoken twice
            val duplicateSpoken = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snap45Connect,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                currentTimeMillis = clock + 400L
            )
            assertNull("Duplicate ACTION_POWER_CONNECTED within debounce window must be suppressed", duplicateSpoken)

            // Scenario 3: Charger Disconnect at 85% -> speaks 85%
            clock += 5_000L
            val snap85Disconnect = com.example.model.BatteryHardwareSnapshot(
                percentage = 85,
                isPercentageValid = true,
                isCharging = false,
                chargingSource = ChargingSource.NONE
            )
            val spoken3 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snap85Disconnect,
                explicitTriggerEvent = Intent.ACTION_POWER_DISCONNECTED,
                currentTimeMillis = clock
            )
            assertEquals("बॉस, चार्जर निकाल दिया गया है। अभी बैटरी 85 प्रतिशत है।", spoken3)

            // Scenario 2: Charger Connect at 78% -> speaks 78%
            clock += 5_000L
            val snap78Connect = com.example.model.BatteryHardwareSnapshot(
                percentage = 78,
                isPercentageValid = true,
                isCharging = true,
                chargingSource = ChargingSource.AC
            )
            val spoken2 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snap78Connect,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                currentTimeMillis = clock
            )
            assertEquals("बॉस, चार्जर लग गया है। अभी बैटरी 78 प्रतिशत है।", spoken2)

            // Scenario 4: Charger Disconnect at 100% -> speaks 100%
            clock += 5_000L
            val snap100Disconnect = com.example.model.BatteryHardwareSnapshot(
                percentage = 100,
                isPercentageValid = true,
                isCharging = false,
                chargingSource = ChargingSource.NONE
            )
            val spoken4 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snap100Disconnect,
                explicitTriggerEvent = Intent.ACTION_POWER_DISCONNECTED,
                currentTimeMillis = clock
            )
            assertEquals("बॉस, चार्जर निकाल दिया गया है। अभी बैटरी 100 प्रतिशत है।", spoken4)

            // Scenario 5: Connect Percentage Toggle OFF -> Connect Percentage Announcement suppressed
            clock += 5_000L
            dataStore.updateChargingAlerts(percentOnConnect = false, percentOnDisconnect = true)
            val spoken5 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snap45Connect,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                currentTimeMillis = clock
            )
            assertNull("When announceBatteryPercentOnConnect is OFF, connect percentage announcement must be suppressed", spoken5)

            // Scenario 6: Disconnect Percentage Toggle OFF -> Disconnect Percentage Announcement suppressed
            clock += 5_000L
            dataStore.updateChargingAlerts(percentOnConnect = true, percentOnDisconnect = false)
            val spoken6 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snap85Disconnect,
                explicitTriggerEvent = Intent.ACTION_POWER_DISCONNECTED,
                currentTimeMillis = clock
            )
            assertNull("When announceBatteryPercentOnDisconnect is OFF, disconnect percentage announcement must be suppressed", spoken6)

            // Restore both toggles ON and test Scenario 7 (safe fallback when percentage is invalid) & Scenario 9 (all 4 Voice Profiles)
            dataStore.updateChargingAlerts(percentOnConnect = true, percentOnDisconnect = true)
            val invalidPctSnap = com.example.model.BatteryHardwareSnapshot(
                percentage = 0,
                isPercentageValid = false,
                isCharging = true
            )
            val fallbackMsg = com.example.voice.OfflineVoiceAssistant.buildChargerEventMessage(
                isConnect = true,
                snapshot = invalidPctSnap,
                settings = dataStore.getSettingsSnapshot()
            )
            assertEquals(DefaultBossMessages.HI_CHARGER_CONNECTED_NO_PERCENT, fallbackMsg)

            // Scenario 9: Verify across all 4 Voice Profiles (Hindi Female, Hindi Male, English Female, English Male)
            for (preset in VoiceProfilePreset.entries) {
                clock += 5_000L
                dataStore.selectVoicePreset(preset)
                val connectMsg = assistant.evaluateSnapshotAndAnnounceNow(
                    snapshot = snap45Connect,
                    explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                    currentTimeMillis = clock
                )
                assertNotNull(connectMsg)
                assertTrue("Expected 45 in $connectMsg for ${preset.id}", connectMsg!!.contains("45"))

                clock += 5_000L
                val disconnectMsg = assistant.evaluateSnapshotAndAnnounceNow(
                    snapshot = snap85Disconnect,
                    explicitTriggerEvent = Intent.ACTION_POWER_DISCONNECTED,
                    currentTimeMillis = clock
                )
                assertNotNull(disconnectMsg)
                assertTrue("Expected 85 in $disconnectMsg for ${preset.id}", disconnectMsg!!.contains("85"))
            }
        }
        assistant.shutdown()
    }

    @Test
    fun `smart battery tracker, app power manager, saver modes, room history, and protected apps pass all 10 verification requirements`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1 & 2. Verify Usage Access missing state and System Settings Intent targets
        val trackerNoPerm = com.example.hardware.SmartAppUsageTracker(
            context = context,
            overrides = com.example.hardware.UsageTrackerTestOverrides(hasUsagePermissionOverride = false)
        )
        assertFalse(trackerNoPerm.hasUsageAccessPermission())
        assertEquals(
            android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
            com.example.hardware.SmartAppUsageTracker.buildUsageAccessSettingsIntent().action
        )
        assertEquals(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            com.example.hardware.SmartAppUsageTracker.buildAppInfoSettingsIntent("com.example.test").action
        )
        assertEquals(
            android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS,
            com.example.hardware.SmartAppUsageTracker.buildBatterySaverSettingsIntent().action
        )

        val stateNoPerm = trackerNoPerm.readSmartTrackerState(
            snapshot = com.example.model.BatteryHardwareSnapshot(percentage = 65, isCharging = false),
            settings = UserSettings()
        )
        assertFalse(stateNoPerm.hasUsageAccessPermission)
        assertNull("Screen On Time must be null when Usage Access is not granted", stateNoPerm.screenOnTimeMillis)
        assertNull("Total App Usage Time must be null when Usage Access is not granted", stateNoPerm.totalAppUsageTimeMillis)
        assertTrue("Installed apps must still be discovered via PackageManager", stateNoPerm.allInstalledApps.isNotEmpty())

        // 3, 4, 5 & 10. Verify App Usage Time vs. Verified Battery Energy separation and Protected Critical Apps
        val simulatedApps = listOf(
            com.example.model.AppBatteryUsageRecord(
                packageName = "com.heavy.videoapp",
                appName = "Video Streamer",
                isSystemApp = false,
                isProtectedCriticalApp = false,
                foregroundTimeMillis = 95 * 60_000L, // 1h 35m
                backgroundServiceTimeMillis = 30 * 60_000L,
                usageSourceApi = "UsageStatsManager.queryUsageStats",
                usageConfidence = MetricConfidence.HIGH_DIRECT,
                verifiedBatteryMah = null, // Unprivileged 3rd-party app -> verified mAh is null!
                verifiedBatteryPercent = null,
                estimatedBatteryMah = 420f,
                estimatedBatteryPercent = 14f,
                energySourceApi = "Unavailable to 3rd-Party Apps (BATTERY_STATS is signature|privileged)",
                energyConfidence = MetricConfidence.MEDIUM_DERIVED,
                lastTimeUsedMillis = System.currentTimeMillis() - 60_000L,
                isIgnoringBatteryOptimizations = true,
                optimizationStatusLabel = "Unrestricted (Ignoring Battery Optimizations)",
                isHighDrainDetected = true,
                isHighBackgroundDetected = true
            ),
            com.example.model.AppBatteryUsageRecord(
                packageName = "com.android.dialer",
                appName = "Phone Dialer",
                isSystemApp = true,
                isProtectedCriticalApp = true,
                protectedReason = "Protected: Phone / Dialer",
                foregroundTimeMillis = 50 * 60_000L,
                backgroundServiceTimeMillis = 15 * 60_000L,
                usageSourceApi = "UsageStatsManager.queryUsageStats",
                usageConfidence = MetricConfidence.HIGH_DIRECT,
                verifiedBatteryMah = null,
                verifiedBatteryPercent = null,
                estimatedBatteryMah = 180f,
                estimatedBatteryPercent = 6f,
                energySourceApi = "Unavailable to 3rd-Party Apps",
                energyConfidence = MetricConfidence.MEDIUM_DERIVED,
                lastTimeUsedMillis = System.currentTimeMillis() - 120_000L,
                isIgnoringBatteryOptimizations = true,
                optimizationStatusLabel = "Unrestricted",
                isHighDrainDetected = true,
                isHighBackgroundDetected = false
            )
        )

        val trackerWithPerm = com.example.hardware.SmartAppUsageTracker(
            context = context,
            overrides = com.example.hardware.UsageTrackerTestOverrides(
                hasUsagePermissionOverride = true,
                simulatedApps = simulatedApps,
                screenOnMillisOverride = 145 * 60_000L,
                screenOffMillisOverride = 1295 * 60_000L
            )
        )
        val snapDischarging = com.example.model.BatteryHardwareSnapshot(
            percentage = 25,
            isPercentageValid = true,
            isCharging = false,
            currentMilliAmps = -950,
            currentSourceApi = "BatteryManager.BATTERY_PROPERTY_CURRENT_NOW (µA -> mA)",
            temperatureCelsius = 43.0f,
            voltageVolts = 3.82f,
            chargeCounterMah = 1100,
            designCapacityMah = 5000
        )
        val stateWithPerm = trackerWithPerm.readSmartTrackerState(
            snapshot = snapDischarging,
            settings = UserSettings(temperatureWarningCelsius = 42)
        )
        assertTrue(stateWithPerm.hasUsageAccessPermission)
        assertEquals(950, stateWithPerm.dischargeRateMa)
        assertEquals(145 * 60_000L, stateWithPerm.screenOnTimeMillis)
        assertNotNull(stateWithPerm.estimatedRemainingMinutes)
        assertEquals("Video Streamer", stateWithPerm.topConsumingApp?.appName)
        assertNull("Unverified per-app mAh must stay null", stateWithPerm.topConsumingApp?.verifiedBatteryMah)
        assertTrue(stateWithPerm.topConsumingApp!!.verifiedConsumptionLabel.contains("Unavailable"))
        assertTrue(stateWithPerm.topConsumingApp!!.estimatedConsumptionLabel.contains("Estimated"))

        // Verify Protected Critical Apps detection (Dialer, System UI, DeskClock, Emergency, Battery Boss)
        assertTrue(com.example.hardware.SmartAppUsageTracker.isBuiltInProtectedPackage("com.android.dialer", context.packageName).first)
        assertTrue(com.example.hardware.SmartAppUsageTracker.isBuiltInProtectedPackage("com.android.systemui", context.packageName).first)
        assertTrue(com.example.hardware.SmartAppUsageTracker.isBuiltInProtectedPackage("com.google.android.deskclock", context.packageName).first)
        assertTrue(com.example.hardware.SmartAppUsageTracker.isBuiltInProtectedPackage("com.android.emergency", context.packageName).first)
        assertTrue(com.example.hardware.SmartAppUsageTracker.isBuiltInProtectedPackage(context.packageName, context.packageName).first)

        // 6 & 7. Verify Event-Driven Smart Battery Saver Modes & Extreme Saver preserving critical Low Battery / Thermal alerts
        assertEquals(15_000L, com.example.model.BatterySaverMode.NORMAL.telemetryIntervalMs)
        assertEquals(30_000L, com.example.model.BatterySaverMode.SMART_SAVER.telemetryIntervalMs)
        assertEquals(0L, com.example.model.BatterySaverMode.EXTREME_SAVER.telemetryIntervalMs)
        assertTrue(com.example.model.BatterySaverMode.EXTREME_SAVER.isPureEventDriven)
        assertFalse(com.example.model.BatterySaverMode.NORMAL.suppressNonCriticalVoiceAlerts)
        assertTrue(com.example.model.BatterySaverMode.EXTREME_SAVER.suppressNonCriticalVoiceAlerts)

        val dataStore = com.example.data.preferences.SettingsDataStore(context)
        val db = com.example.data.local.BatteryBossDatabase.getInstance(context)
        val repo = com.example.data.local.BatteryBossRepository(db.batteryBossDao())
        val assistant = com.example.voice.OfflineVoiceAssistant(context, dataStore, repo)

        kotlinx.coroutines.runBlocking {
            dataStore.updateBatterySaverMode(com.example.model.BatterySaverMode.EXTREME_SAVER)
            dataStore.updateChargingAlerts(
                smartMilestones = true,
                lowBattery = true,
                lowBatteryThreshold = 20,
                tempWarning = true,
                tempWarningCelsius = 42
            )

            // In Extreme Saver Mode, routine 50% milestone is suppressed:
            val milestone50Snap = com.example.model.BatteryHardwareSnapshot(
                percentage = 50,
                isPercentageValid = true,
                isCharging = true
            )
            // First align charging state
            assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = milestone50Snap,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                currentTimeMillis = 500_000L
            )
            val suppressedMilestone = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = milestone50Snap,
                explicitTriggerEvent = null,
                currentTimeMillis = 510_000L
            )
            assertNull("Non-critical 50% milestone must be suppressed in Extreme Saver Mode", suppressedMilestone)

            // In Extreme Saver Mode, CRITICAL Low Battery Warning (15%) is STILL spoken!
            assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = com.example.model.BatteryHardwareSnapshot(percentage = 40, isPercentageValid = true, isCharging = false),
                explicitTriggerEvent = Intent.ACTION_POWER_DISCONNECTED,
                currentTimeMillis = 520_000L
            )
            val criticalLowSnap = com.example.model.BatteryHardwareSnapshot(
                percentage = 15,
                isPercentageValid = true,
                isCharging = false
            )
            val criticalLowSpoken = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = criticalLowSnap,
                explicitTriggerEvent = Intent.ACTION_BATTERY_LOW,
                currentTimeMillis = 530_000L
            )
            assertNotNull("Extreme Saver Mode MUST preserve critical Low Battery warnings", criticalLowSpoken)
            assertTrue(criticalLowSpoken!!.contains("15"))

            // 8. Verify Room Database persistence, pruning, and CSV export
            repo.clearSmartTrackerHistory()
            val inserted = repo.recordBatteryUsageSnapshot(
                entry = com.example.data.local.BatteryUsageHistoryEntity(
                    timestampMillis = System.currentTimeMillis(),
                    batteryPercentage = 25,
                    isCharging = false,
                    dischargeRateMa = 950,
                    screenOnTimeMillis = 145 * 60_000L,
                    temperatureCelsius = 43.0f,
                    voltageVolts = 3.82f,
                    batterySaverMode = com.example.model.BatterySaverMode.EXTREME_SAVER.name,
                    topAppPackage = "com.heavy.videoapp",
                    topAppName = "Video Streamer"
                ),
                minIntervalMillis = 0L
            )
            assertTrue(inserted)
            repo.logAutoManagementReport(
                eventCategory = "AUTO_SAVER_ACTIVATED",
                targetPackageName = null,
                targetAppName = "Battery Boss Saver Engine",
                detectedBatteryUsageSummary = "Battery at 25%",
                alertIssued = "Automatic Smart Saver",
                restrictionOrActionApplied = "Activated Extreme Saver",
                actionSucceeded = true,
                saverModeState = "EXTREME_SAVER"
            )
            val csv = repo.buildExportCsv(
                historyRecords = db.batteryBossDao().getBatteryUsageHistorySince(0L),
                appRecords = emptyList(),
                autoReports = emptyList()
            )
            assertTrue(csv.contains("USAGE_HISTORY"))
            assertTrue(csv.contains("Video Streamer"))

            // Restore Normal mode
            dataStore.updateBatterySaverMode(com.example.model.BatterySaverMode.NORMAL)
        }
        assistant.shutdown()
    }

    @Test
    fun testPowerEfficiencyAuditWakeLocksAndEstimatedVsVerifiedRanking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.service.BatteryTelemetryHub.releaseAllTrackedWakeLocks()
        com.example.service.BatteryTelemetryHub.setServiceRunning(true)

        val baselineAudit = com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL)

        // 1. Verify Bounded WakeLock acquisition, max timeout enforcement (<= 3,000ms and <= 15,000ms), and release in normal & error paths
        val powerWakeLock = com.example.service.BatteryTelemetryHub.acquireBoundedWakeLock(
            context = context,
            tag = "BatteryBoss:PowerEventSettleWakeLock",
            timeoutMs = 99_000L // Should be clamped to <= 15,000ms
        )
        assertTrue(powerWakeLock.timeoutMs <= com.example.service.BatteryTelemetryHub.MAX_SPEECH_WAKELOCK_MS)
        assertEquals(1, com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL).activeHeldWakeLocksCount)
        com.example.service.BatteryTelemetryHub.releaseBoundedWakeLock(powerWakeLock)
        assertEquals(0, com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL).activeHeldWakeLocksCount)

        // Simulate an exception during speech / power handling and verify finally block releases WakeLock
        var exceptionCaught = false
        val speechWakeLock = com.example.service.BatteryTelemetryHub.acquireBoundedWakeLock(
            context = context,
            tag = "BatteryBoss:SpeechWakeLock",
            timeoutMs = com.example.service.BatteryTelemetryHub.MAX_SPEECH_WAKELOCK_MS
        )
        try {
            assertEquals(1, com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL).activeHeldWakeLocksCount)
            throw IllegalStateException("Simulated TTS synthesis error")
        } catch (_: IllegalStateException) {
            exceptionCaught = true
        } finally {
            com.example.service.BatteryTelemetryHub.releaseBoundedWakeLock(speechWakeLock)
        }
        assertTrue(exceptionCaught)
        val auditReport = com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.EXTREME_SAVER)
        assertEquals("Zero WakeLocks must remain held after normal or error completion", 0, auditReport.activeHeldWakeLocksCount)
        assertEquals(baselineAudit.wakeLockAcquisitionsCount + 2, auditReport.wakeLockAcquisitionsCount)
        assertEquals(baselineAudit.wakeLockReleasesCount + 2, auditReport.wakeLockReleasesCount)
        assertTrue(auditReport.wakeLockSafetyVerdict.contains("0 Active WakeLocks Held"))
        assertTrue(auditReport.monitoringArchitectureLabel.contains("100% Pure Event-Driven"))
        com.example.service.BatteryTelemetryHub.setServiceRunning(false)

        // 2. Verify Verified vs. Estimated Power Contribution tab naming & separation
        assertEquals(
            "Estimated Power Contribution",
            com.example.model.AppUsageSectionTab.HIGHEST_CONSUMING.resolveTitle(hasVerifiedPerAppBatteryData = false)
        )
        assertTrue(
            com.example.model.AppUsageSectionTab.HIGHEST_CONSUMING
                .resolveTitle(hasVerifiedPerAppBatteryData = true)
                .contains("Highest Battery Consuming")
        )

        val unverifiedApps = listOf(
            com.example.model.AppBatteryUsageRecord(
                packageName = "com.example.social",
                appName = "Social Feed",
                isSystemApp = false,
                isProtectedCriticalApp = false,
                protectedReason = null,
                foregroundTimeMillis = 60 * 60_000L,
                backgroundServiceTimeMillis = 10 * 60_000L,
                usageSourceApi = "UsageStatsManager",
                usageConfidence = MetricConfidence.HIGH_DIRECT,
                verifiedBatteryMah = null,
                verifiedBatteryPercent = null,
                estimatedBatteryMah = 250f,
                estimatedBatteryPercent = 12f,
                energySourceApi = "Unavailable",
                energyConfidence = MetricConfidence.MEDIUM_DERIVED,
                lastTimeUsedMillis = System.currentTimeMillis(),
                isIgnoringBatteryOptimizations = true,
                optimizationStatusLabel = "Unrestricted",
                isHighDrainDetected = true,
                isHighBackgroundDetected = false
            )
        )
        val trackerUnverified = com.example.hardware.SmartAppUsageTracker(
            context = context,
            overrides = com.example.hardware.UsageTrackerTestOverrides(
                hasUsagePermissionOverride = true,
                simulatedApps = unverifiedApps
            )
        )
        val stateUnverified = trackerUnverified.readSmartTrackerState(
            snapshot = com.example.model.BatteryHardwareSnapshot(percentage = 50, isPercentageValid = true),
            settings = UserSettings()
        )
        assertFalse(stateUnverified.hasVerifiedPerAppBatteryData)
        assertTrue(stateUnverified.verifiedHighestConsumingApps.isEmpty())
        assertEquals(1, stateUnverified.estimatedPowerContributionApps.size)

        // Now with verified per-app battery mAh:
        val verifiedApps = listOf(
            unverifiedApps.first().copy(verifiedBatteryMah = 310f, verifiedBatteryPercent = 15.5f)
        )
        val trackerVerified = com.example.hardware.SmartAppUsageTracker(
            context = context,
            overrides = com.example.hardware.UsageTrackerTestOverrides(
                hasUsagePermissionOverride = true,
                simulatedApps = verifiedApps
            )
        )
        val stateVerified = trackerVerified.readSmartTrackerState(
            snapshot = com.example.model.BatteryHardwareSnapshot(percentage = 50, isPercentageValid = true),
            settings = UserSettings()
        )
        assertTrue(stateVerified.hasVerifiedPerAppBatteryData)
        assertEquals(1, stateVerified.verifiedHighestConsumingApps.size)

        // 3. Verify Rapid Plug -> Unplug -> Plug transitions announce each real transition without contact bounce
        val dataStore = com.example.data.preferences.SettingsDataStore(context)
        val db = com.example.data.local.BatteryBossDatabase.getInstance(context)
        val repo = com.example.data.local.BatteryBossRepository(db.batteryBossDao())
        val assistant = com.example.voice.OfflineVoiceAssistant(context, dataStore, repo)

        kotlinx.coroutines.runBlocking {
            assistant.resetAnnouncementTrackingState()
            val settings = UserSettings(
                language = VoiceLanguage.ENGLISH,
                voiceAnnouncementsEnabled = true,
                announceChargerConnected = true,
                announceChargerDisconnected = true,
                announceBatteryPercentOnConnect = true,
                announceBatteryPercentOnDisconnect = true
            )
            val snapPlugged = com.example.model.BatteryHardwareSnapshot(percentage = 64, isPercentageValid = true, isCharging = true)
            val snapUnplugged = com.example.model.BatteryHardwareSnapshot(percentage = 64, isPercentageValid = true, isCharging = false)

            // T = 1000ms: Plug In -> Announces Connect
            val msg1 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snapPlugged,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                settingsOverride = settings,
                currentTimeMillis = 1_000L
            )
            assertNotNull("Initial plug-in must announce", msg1)
            assertTrue(msg1!!.contains("64"))

            // T = 1100ms: Duplicate ACTION_POWER_CONNECTED within 2000ms -> Suppressed
            val msgDup = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snapPlugged,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                settingsOverride = settings,
                currentTimeMillis = 1_100L
            )
            assertNull("Duplicate connect within 2s must be suppressed", msgDup)

            // T = 1600ms (600ms later): Unplug -> Announces Disconnect
            val msg2 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snapUnplugged,
                explicitTriggerEvent = Intent.ACTION_POWER_DISCONNECTED,
                settingsOverride = settings,
                currentTimeMillis = 1_600L
            )
            assertNotNull("Unplug after 600ms must announce", msg2)
            assertTrue(msg2!!.contains("64"))

            // T = 2200ms (600ms after unplug): Plug In again -> Announces Connect!
            val msg3 = assistant.evaluateSnapshotAndAnnounceNow(
                snapshot = snapPlugged,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                settingsOverride = settings,
                currentTimeMillis = 2_200L
            )
            assertNotNull("Re-plug 600ms after unplug must announce", msg3)
            assertTrue(msg3!!.contains("64"))
        }
        assistant.shutdown()
    }

    @Test
    fun testUserControlledPersistentNotificationAndIndependentMonitoringVoiceSystem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        val dataStore = com.example.data.preferences.SettingsDataStore(context)

        // Scenario 1A: Monitoring ON with Silent Notification ON (Default) ->
        // BatteryMonitorService runs as Foreground Service with Compact Silent Notification ("Battery Boss Active • <pct>%")
        // on CHANNEL_ID_SILENT (IMPORTANCE_MIN) and 0 bulky action buttons!
        kotlinx.coroutines.runBlocking {
            dataStore.updateSilentNotificationEnabled(true)
            dataStore.updateBackgroundSettings(
                backgroundMonitoring = true,
                persistentNotification = true,
                silentNotification = true,
                monitoringMode = com.example.model.BackgroundMonitoringMode.SILENT_BACKGROUND
            )
            dataStore.updateVoiceAnnouncementsEnabled(true)
        }

        val serviceController = org.robolectric.Robolectric.buildService(com.example.service.BatteryMonitorService::class.java)
        val startIntent = Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
            action = com.example.service.BatteryMonitorService.ACTION_START_MONITORING
        }
        serviceController.create().startCommand(0, 1)
        val service = serviceController.get()
        service.onStartCommand(startIntent, 0, 1)

        com.example.service.BatteryTelemetryHub.updateSnapshot(
            com.example.model.BatteryHardwareSnapshot(
                percentage = 83,
                isPercentageValid = true,
                isCharging = true,
                statusLabel = "Charging"
            )
        )
        service.onStartCommand(
            Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
                action = com.example.service.BatteryMonitorService.ACTION_REFRESH_NOTIFICATION
            },
            0,
            2
        )

        assertTrue("Service must be marked running when Monitoring is ON", com.example.service.BatteryTelemetryHub.isServiceRunning.value)
        assertTrue("Foreground Notification must be active when Monitoring is ON", com.example.service.BatteryTelemetryHub.isForegroundNotificationVisible.value)

        val silentNotif = shadowNotificationManager.getNotification(com.example.service.BatteryMonitorService.NOTIFICATION_ID)
        assertNotNull("Compact Silent Foreground Notification must be posted with NOTIFICATION_ID", silentNotif)
        val silentTitle = silentNotif.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        assertEquals("Battery Boss Active • 83%", silentTitle)
        assertEquals(com.example.service.BatteryMonitorService.CHANNEL_ID_SILENT, silentNotif.channelId)
        assertEquals(
            "Compact Silent Notification must omit bulky action buttons",
            0,
            silentNotif.actions?.size ?: 0
        )

        val silentChannel = notificationManager.getNotificationChannel(com.example.service.BatteryMonitorService.CHANNEL_ID_SILENT)
        assertNotNull("Silent notification channel must exist", silentChannel)
        assertEquals(android.app.NotificationManager.IMPORTANCE_MIN, silentChannel.importance)
        assertNull("Silent channel sound must be null", silentChannel.sound)
        assertFalse("Silent channel vibration must be disabled", silentChannel.shouldVibrate())

        // Scenario 1B: Toggle Silent Notification OFF -> Notification switches in-place to Standard Low-Priority (IMPORTANCE_LOW) with Quick Actions
        kotlinx.coroutines.runBlocking {
            dataStore.updateSilentNotificationEnabled(false)
        }
        val refreshIntent = Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
            action = com.example.service.BatteryMonitorService.ACTION_REFRESH_NOTIFICATION
        }
        service.onStartCommand(refreshIntent, 0, 2)

        val standardNotif = shadowNotificationManager.getNotification(com.example.service.BatteryMonitorService.NOTIFICATION_ID)
        assertNotNull(standardNotif)
        assertEquals(com.example.service.BatteryMonitorService.CHANNEL_ID, standardNotif.channelId)
        val actionTitles = standardNotif.actions?.map { it.title.toString() }.orEmpty()
        assertTrue("Standard notification must include STOP MONITORING action", actionTitles.any { it.contains("STOP MONITORING") })
        assertTrue("Standard notification must include SPEAK STATUS action", actionTitles.any { it.contains("SPEAK STATUS") })
        assertTrue("Standard notification must include MUTE VOICE or RESUME VOICE action", actionTitles.any { it.contains("MUTE VOICE") || it.contains("RESUME VOICE") })

        // Scenario 3: Voice OFF (MUTE VOICE action) -> Monitoring continues & Foreground Service stays active, ONLY voice is muted!
        val muteIntent = Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
            action = com.example.service.BatteryMonitorService.ACTION_MUTE_VOICE
        }
        service.onStartCommand(muteIntent, 0, 3)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        kotlinx.coroutines.runBlocking {
            dataStore.updateVoiceAnnouncementsEnabled(false)
            val mutedSettings = dataStore.getSettingsSnapshot()
            assertFalse("Voice announcements must be OFF after MUTE_VOICE", mutedSettings.voiceAnnouncementsEnabled)
            assertTrue("Background Monitoring MUST remain ON when Voice is muted", com.example.service.BatteryTelemetryHub.isServiceRunning.value)
            assertTrue("Foreground Service MUST remain active when Voice is muted", com.example.service.BatteryTelemetryHub.isForegroundNotificationVisible.value)

            val voiceAssistant = com.example.service.BatteryTelemetryHub.getOrCreateVoiceAssistant(context)
            val mutedResult = voiceAssistant.evaluateSnapshotAndAnnounceNow(
                snapshot = com.example.model.BatteryHardwareSnapshot(percentage = 72, isPercentageValid = true, isCharging = true),
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                settingsOverride = mutedSettings,
                currentTimeMillis = 50_000L
            )
            assertNull("No speech may be triggered while voiceAnnouncementsEnabled is false", mutedResult)
            assertTrue(voiceAssistant.lastSpokenBanner.value.orEmpty().contains("Muted • Voice Announcements OFF"))
        }

        // Scenario 4: Voice ON (RESUME VOICE action) -> Voice announcements resume immediately
        val resumeIntent = Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
            action = com.example.service.BatteryMonitorService.ACTION_RESUME_VOICE
        }
        service.onStartCommand(resumeIntent, 0, 4)

        kotlinx.coroutines.runBlocking {
            dataStore.updateVoiceAnnouncementsEnabled(true)
            val resumedSettings = dataStore.getSettingsSnapshot()
            assertTrue("Voice announcements must be ON after RESUME_VOICE", resumedSettings.voiceAnnouncementsEnabled)

            val voiceAssistant = com.example.service.BatteryTelemetryHub.getOrCreateVoiceAssistant(context)
            voiceAssistant.resetAnnouncementTrackingState()
            val spokenResult = voiceAssistant.evaluateSnapshotAndAnnounceNow(
                snapshot = com.example.model.BatteryHardwareSnapshot(percentage = 75, isPercentageValid = true, isCharging = true),
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                settingsOverride = resumedSettings,
                currentTimeMillis = 60_000L
            )
            assertNotNull("Voice announcements must resume immediately when Voice is ON", spokenResult)
            assertTrue(spokenResult!!.contains("75"))
        }

        // Scenario 6: Screen OFF -> Event-driven monitoring continues with 0 periodic polling
        com.example.service.BatteryTelemetryHub.setScreenInteractive(false)
        assertFalse(com.example.service.BatteryTelemetryHub.isScreenInteractive.value)
        val screenOffAudit = com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL)
        assertTrue(
            "Screen-Off must pause periodic polling",
            screenOffAudit.activePollingIntervalLabel.contains("0s Polling") &&
                screenOffAudit.activePollingIntervalLabel.contains("Screen OFF")
        )
        com.example.service.BatteryTelemetryHub.setScreenInteractive(true)

        // Scenario 2 & 5: Notification STOP MONITORING button -> Service stops, WakeLocks released, Notification removed
        val stopIntent = Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
            action = com.example.service.BatteryMonitorService.ACTION_STOP_MONITORING
        }
        service.onStartCommand(stopIntent, 0, 5)
        serviceController.destroy()

        assertFalse("Service must stop when STOP MONITORING is triggered", com.example.service.BatteryTelemetryHub.isServiceRunning.value)
        assertFalse("Foreground Notification must be removed when Monitoring is OFF", com.example.service.BatteryTelemetryHub.isForegroundNotificationVisible.value)
        assertEquals(
            "All tracked WakeLocks must be released when Monitoring stops",
            0,
            com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL).activeHeldWakeLocksCount
        )
    }

    @Test
    fun testRestoredForegroundServiceBackgroundVoiceAndSevenRegressionScenarios() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        val dataStore = com.example.data.preferences.SettingsDataStore(context)

        // Clean initial state
        com.example.service.SilentBackgroundMonitorEngine.applyMonitoringMode(
            context = context,
            enabled = false,
            mode = com.example.model.BackgroundMonitoringMode.SILENT_BACKGROUND
        )
        notificationManager.cancelAll()

        // Setup: Select Hindi Male (Rohan) profile, enable Background Monitoring, Background Voice, and Silent Notification
        kotlinx.coroutines.runBlocking {
            dataStore.selectVoicePreset(VoiceProfilePreset.HINDI_MALE)
            dataStore.updateSilentNotificationEnabled(true)
            dataStore.updateBackgroundSettings(
                backgroundMonitoring = true,
                persistentNotification = true,
                silentNotification = true,
                monitoringMode = com.example.model.BackgroundMonitoringMode.SILENT_BACKGROUND
            )
            dataStore.updateVoiceAnnouncementsEnabled(true)
        }

        // Start Foreground Service (BatteryMonitorService)
        val serviceController = org.robolectric.Robolectric.buildService(com.example.service.BatteryMonitorService::class.java)
        serviceController.create().startCommand(0, 1)
        val service = serviceController.get()
        service.onStartCommand(
            Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
                action = com.example.service.BatteryMonitorService.ACTION_START_MONITORING
            },
            0,
            1
        )

        // Test 4: Notification Silent होने पर भी Foreground Service और Voice काम करे
        assertTrue("Foreground Service must be running when Silent Notification is ON", com.example.service.BatteryTelemetryHub.isServiceRunning.value)
        assertTrue("Foreground Notification must be active in Compact Silent mode", com.example.service.BatteryTelemetryHub.isForegroundNotificationVisible.value)
        val notif = shadowNotificationManager.getNotification(com.example.service.BatteryMonitorService.NOTIFICATION_ID)
        assertNotNull("Foreground Service notification must exist so OS never kills Background Voice", notif)
        assertEquals(com.example.service.BatteryMonitorService.CHANNEL_ID_SILENT, notif.channelId)
        val title = notif.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        assertTrue("Notification title must use compact 'Battery Boss Active • <pct>%' format", title.startsWith("Battery Boss Active • "))
        assertEquals("Silent Notification must have 0 bulky action buttons", 0, notif.actions?.size ?: 0)

        // Test 1 & Test 2: Screen Off + Charger Connected & Charger Disconnected -> Voice Announcement आए
        com.example.service.BatteryTelemetryHub.setScreenInteractive(false)
        assertFalse("Screen must be OFF", com.example.service.BatteryTelemetryHub.isScreenInteractive.value)

        kotlinx.coroutines.runBlocking {
            val voiceAssistant = com.example.service.BatteryTelemetryHub.getOrCreateVoiceAssistant(context)
            voiceAssistant.resetAnnouncementTrackingState()

            // Screen OFF + Charger Connected at 62%
            val stickyConnectIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 62)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                putExtra(BatteryManager.EXTRA_TEMPERATURE, 335)
                putExtra(BatteryManager.EXTRA_VOLTAGE, 4120)
            }
            val connectSnap = com.example.service.SilentBackgroundMonitorEngine.evaluateSilentBackgroundEventNow(
                context = context,
                triggerAction = Intent.ACTION_POWER_CONNECTED,
                batteryIntent = stickyConnectIntent
            )
            assertEquals(62, connectSnap.percentage)
            assertTrue(connectSnap.isCharging)
            assertTrue(
                "Screen OFF + Charger Connected must speak Battery Percentage (62%)",
                voiceAssistant.lastSpokenBanner.value.orEmpty().contains("62")
            )

            // Screen OFF + Charger Disconnected at 81%
            voiceAssistant.resetAnnouncementTrackingState()
            val stickyDisconnectIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 81)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                putExtra(BatteryManager.EXTRA_TEMPERATURE, 320)
                putExtra(BatteryManager.EXTRA_VOLTAGE, 4050)
            }
            val disconnectSnap = com.example.service.SilentBackgroundMonitorEngine.evaluateSilentBackgroundEventNow(
                context = context,
                triggerAction = Intent.ACTION_POWER_DISCONNECTED,
                batteryIntent = stickyDisconnectIntent
            )
            assertEquals(81, disconnectSnap.percentage)
            assertFalse(disconnectSnap.isCharging)
            assertTrue(
                "Screen OFF + Charger Disconnected must speak Battery Percentage (81%)",
                voiceAssistant.lastSpokenBanner.value.orEmpty().contains("81")
            )

            // Test 3: App से बाहर निकलने पर (Activity not running; only Service trigger) -> Voice काम करे
            voiceAssistant.resetAnnouncementTrackingState()
            com.example.service.BatteryTelemetryHub.updateSnapshot(
                com.example.model.BatteryHardwareSnapshot(
                    percentage = 49,
                    isPercentageValid = true,
                    isCharging = true,
                    statusLabel = "Charging"
                )
            )
            val spokenOnAppExit = voiceAssistant.evaluateSnapshotAndAnnounceNow(
                snapshot = com.example.service.BatteryTelemetryHub.snapshot.value,
                explicitTriggerEvent = Intent.ACTION_POWER_CONNECTED,
                settingsOverride = dataStore.getSettingsSnapshot(),
                currentTimeMillis = 90_000L
            )
            assertNotNull("Background Voice must work when user has exited the Activity", spokenOnAppExit)
            assertTrue(spokenOnAppExit!!.contains("49"))
        }
        com.example.service.BatteryTelemetryHub.setScreenInteractive(true)

        // Test 5: Voice OFF करने पर -> केवल Voice बंद हो, Monitoring चालू रहे
        kotlinx.coroutines.runBlocking {
            dataStore.updateVoiceAnnouncementsEnabled(false)
            val settingsWithVoiceOff = dataStore.getSettingsSnapshot()
            assertFalse(settingsWithVoiceOff.voiceAnnouncementsEnabled)
            assertTrue("Background Monitoring setting must stay ON when Voice is turned OFF", settingsWithVoiceOff.backgroundMonitoringEnabled)
            assertTrue("BatteryMonitorService must stay running when Voice is turned OFF", com.example.service.BatteryTelemetryHub.isServiceRunning.value)
        }

        // Re-enable Voice for Service Restart test
        kotlinx.coroutines.runBlocking {
            dataStore.updateVoiceAnnouncementsEnabled(true)
            dataStore.selectVoicePreset(VoiceProfilePreset.ENGLISH_FEMALE)
        }

        // Test 7: Service Restart पर -> Selected Voice Profile सुरक्षित रहे
        serviceController.destroy()
        val restartedController = org.robolectric.Robolectric.buildService(com.example.service.BatteryMonitorService::class.java)
        restartedController.create().startCommand(0, 2)
        val restartedService = restartedController.get()
        restartedService.onStartCommand(
            Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
                action = com.example.service.BatteryMonitorService.ACTION_START_MONITORING
            },
            0,
            2
        )

        kotlinx.coroutines.runBlocking<Unit> {
            val voiceAssistant = com.example.service.BatteryTelemetryHub.getOrCreateVoiceAssistant(context)
            // Even if verifyAllModelPacksOnStartup runs on restart, selected preset must remain ENGLISH_FEMALE
            dataStore.setOfflineModelVerifiedState(VoiceProfilePreset.HINDI_FEMALE.piperModelId, true)
            val postRestartSettings = dataStore.getSettingsSnapshot()
            assertEquals(
                "Selected Voice Profile (ENGLISH_FEMALE) must be preserved across Service Restart",
                VoiceProfilePreset.ENGLISH_FEMALE,
                postRestartSettings.activePreset
            )
            voiceAssistant.ensureEngineAliveAndSyncSettings(postRestartSettings)
            assertTrue(
                voiceAssistant.audioDiagnostics.value.selectedVoiceLabel.contains(VoiceProfilePreset.ENGLISH_FEMALE.piperModelId)
            )
        }

        // Test 6: Monitoring OFF करने पर -> Service सुरक्षित रूप से बंद हो
        restartedService.onStartCommand(
            Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
                action = com.example.service.BatteryMonitorService.ACTION_STOP_MONITORING
            },
            0,
            3
        )
        restartedController.destroy()

        assertFalse("Service must stop when Monitoring is turned OFF", com.example.service.BatteryTelemetryHub.isServiceRunning.value)
        assertFalse("Foreground Notification must be removed when Monitoring is turned OFF", com.example.service.BatteryTelemetryHub.isForegroundNotificationVisible.value)
    }

    @Test
    fun testNotificationChannelTechnicalAuditSpecialUseAndScreenOffAll4Voices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val shadowPowerManager = org.robolectric.Shadows.shadowOf(powerManager)
        val dataStore = com.example.data.preferences.SettingsDataStore(context)

        // Reset hub audit counters cleanly
        com.example.service.BatteryTelemetryHub.resetAuditCountersForTest()
        shadowPowerManager.setIsInteractive(true)

        // Seed legacy channel ID to verify migration cleans it up
        val legacyChannel = android.app.NotificationChannel(
            com.example.service.BatteryMonitorService.LEGACY_CHANNEL_ID,
            "Legacy Channel",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(legacyChannel)
        assertNotNull(notificationManager.getNotificationChannel("battery_boss_monitor_channel"))

        // 1 & 2. Exact channel IDs: battery_boss_silent_channel & battery_boss_standard_channel
        assertEquals("battery_boss_silent_channel", com.example.service.BatteryMonitorService.CHANNEL_ID_SILENT)
        assertEquals("battery_boss_standard_channel", com.example.service.BatteryMonitorService.CHANNEL_ID_STANDARD)
        assertEquals("battery_boss_standard_channel", com.example.service.BatteryMonitorService.CHANNEL_ID)
        assertEquals("battery_boss_silent_channel", com.example.model.BackgroundMonitoringMode.SILENT_BACKGROUND.channelId)
        assertEquals("battery_boss_standard_channel", com.example.model.BackgroundMonitoringMode.CONTINUOUS_FOREGROUND.channelId)

        // 4. Verify zero false claims in BackgroundMonitoringMode copy
        assertFalse(
            com.example.model.BackgroundMonitoringMode.SILENT_BACKGROUND.reliabilityAndLimitationsNote
                .contains("modifies importance", ignoreCase = true)
        )
        assertTrue(
            com.example.model.BackgroundMonitoringMode.SILENT_BACKGROUND.reliabilityAndLimitationsNote
                .contains("locks channel importance", ignoreCase = true)
        )

        // Start service with Silent Channel selected
        kotlinx.coroutines.runBlocking {
            dataStore.updateBackgroundSettings(
                backgroundMonitoring = true,
                persistentNotification = true,
                silentNotification = true,
                monitoringMode = com.example.model.BackgroundMonitoringMode.SILENT_BACKGROUND
            )
            dataStore.updateVoiceAnnouncementsEnabled(true)
        }
        com.example.service.BatteryTelemetryHub.updateSnapshot(
            com.example.model.BatteryHardwareSnapshot(
                percentage = 77,
                isPercentageValid = true,
                isCharging = false,
                statusLabel = "Discharging"
            )
        )

        val controller = org.robolectric.Robolectric.buildService(com.example.service.BatteryMonitorService::class.java)
        controller.create()
        val service = controller.get()
        service.onStartCommand(
            Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
                action = com.example.service.BatteryMonitorService.ACTION_START_MONITORING
            },
            0,
            1
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Legacy channel deleted; both audited channels created
        assertNull(notificationManager.getNotificationChannel("battery_boss_monitor_channel"))
        val silentChannel = notificationManager.getNotificationChannel("battery_boss_silent_channel")
        val standardChannel = notificationManager.getNotificationChannel("battery_boss_standard_channel")
        assertNotNull("Silent channel must exist", silentChannel)
        assertNotNull("Standard channel must exist", standardChannel)
        assertEquals(android.app.NotificationManager.IMPORTANCE_MIN, silentChannel.importance)
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, standardChannel.importance)

        val initialDiag = com.example.service.BatteryTelemetryHub.notificationChannelDiagnostics.value
        val createsAfterStart = initialDiag.serviceInstanceCreateCount
        assertEquals(1, createsAfterStart)
        assertEquals(0, initialDiag.serviceInstanceDestroyCount)

        // Verify initial notification posted onto battery_boss_silent_channel
        val postedSilent = shadowNotificationManager.getNotification(com.example.service.BatteryMonitorService.NOTIFICATION_ID)
        assertNotNull(postedSilent)
        assertEquals("battery_boss_silent_channel", postedSilent.channelId)

        // 3 & 5. Repost onto battery_boss_standard_channel IN-PLACE without restarting/stopping BatteryMonitorService
        kotlinx.coroutines.runBlocking {
            dataStore.updateSilentNotificationEnabled(false)
        }
        com.example.service.BatteryMonitorService.refreshNotificationInPlace(context, silentOverride = false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val postedStandard = shadowNotificationManager.getNotification(com.example.service.BatteryMonitorService.NOTIFICATION_ID)
        assertNotNull(postedStandard)
        assertEquals("battery_boss_standard_channel", postedStandard.channelId)
        val diagAfterSwitch = com.example.service.BatteryTelemetryHub.notificationChannelDiagnostics.value
        assertEquals("battery_boss_standard_channel", diagAfterSwitch.activeChannelId)
        assertEquals("Switching channels MUST NOT recreate BatteryMonitorService", createsAfterStart, diagAfterSwitch.serviceInstanceCreateCount)
        assertEquals("Switching channels MUST NOT destroy BatteryMonitorService", 0, diagAfterSwitch.serviceInstanceDestroyCount)
        assertTrue("In-place notification update counter must increment", diagAfterSwitch.inPlaceNotificationUpdatesCount >= 1)

        // Repost back onto battery_boss_silent_channel without restarting service
        kotlinx.coroutines.runBlocking {
            dataStore.updateSilentNotificationEnabled(true)
        }
        com.example.service.BatteryMonitorService.refreshNotificationInPlace(context, silentOverride = true)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val repostedSilent = shadowNotificationManager.getNotification(com.example.service.BatteryMonitorService.NOTIFICATION_ID)
        assertEquals("battery_boss_silent_channel", repostedSilent.channelId)
        assertEquals(createsAfterStart, com.example.service.BatteryTelemetryHub.notificationChannelDiagnostics.value.serviceInstanceCreateCount)
        assertEquals(0, com.example.service.BatteryTelemetryHub.notificationChannelDiagnostics.value.serviceInstanceDestroyCount)

        // 6. Respect user's Notification Channel override in Android System Settings (e.g., blocked by user)
        silentChannel.importance = android.app.NotificationManager.IMPORTANCE_NONE
        com.example.service.BatteryTelemetryHub.refreshNotificationChannelDiagnostics(context, silentSelected = true)
        val blockedDiag = com.example.service.BatteryTelemetryHub.notificationChannelDiagnostics.value
        assertTrue("App must detect user blocked channel importance", blockedDiag.isActiveChannelBlockedByUser)
        assertEquals(android.app.NotificationManager.IMPORTANCE_NONE, blockedDiag.silentChannelOsImportance)
        // Re-running channel setup must NOT overwrite or delete/recreate the user's IMPORTANCE_NONE setting
        com.example.service.BatteryMonitorService.refreshNotificationInPlace(context, silentOverride = true)
        assertEquals(
            "User-blocked channel importance must remain untouched (not reset)",
            android.app.NotificationManager.IMPORTANCE_NONE,
            notificationManager.getNotificationChannel("battery_boss_silent_channel").importance
        )
        // Restore silentChannel importance for remainder of test
        silentChannel.importance = android.app.NotificationManager.IMPORTANCE_MIN

        // 7. Verify Manifest FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, and PROPERTY_SPECIAL_USE_FGS_SUBTYPE
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_META_DATA
        )
        val requestedPermissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(requestedPermissions.contains(android.Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(requestedPermissions.contains(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE))

        val serviceComponent = android.content.ComponentName(context, com.example.service.BatteryMonitorService::class.java)
        val serviceInfo = pm.getServiceInfo(serviceComponent, android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(
            "BatteryMonitorService must declare FOREGROUND_SERVICE_TYPE_SPECIAL_USE",
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            serviceInfo.foregroundServiceType
        )
        // Robolectric's ShadowApplicationPackageManager.getProperty throws NameNotFoundException("unsupported"),
        // so parse the compiled AndroidManifest.xml binary directly to verify PROPERTY_SPECIAL_USE_FGS_SUBTYPE.
        var foundSpecialUseSubtypeValue: String? = null
        context.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "property") {
                    val propName = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (propName == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE") {
                        foundSpecialUseSubtypeValue = parser.getAttributeValue(
                            "http://schemas.android.com/apk/res/android",
                            "value"
                        )
                    }
                }
                eventType = parser.next()
            }
        }
        assertTrue(
            "PROPERTY_SPECIAL_USE_FGS_SUBTYPE must exist and be non-blank on BatteryMonitorService",
            foundSpecialUseSubtypeValue?.isNotBlank() == true
        )

        // 8. Audit PARTIAL_WAKE_LOCK timeout budget for cold TTS startup + synthesis + AudioTrack playback
        assertEquals(3_000L, com.example.service.BatteryTelemetryHub.MAX_POWER_EVENT_WAKELOCK_MS)
        assertEquals(15_000L, com.example.service.BatteryTelemetryHub.MAX_SPEECH_WAKELOCK_MS)
        val clampedSpeechLock = com.example.service.BatteryTelemetryHub.acquireBoundedWakeLock(
            context = context,
            tag = "BatteryBoss:AuditBudgetCheck",
            timeoutMs = 15_000L
        )
        assertEquals(15_000L, clampedSpeechLock.timeoutMs)
        com.example.service.BatteryTelemetryHub.releaseBoundedWakeLock(clampedSpeechLock)
        assertEquals(
            0,
            com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL).activeHeldWakeLocksCount
        )

        // 9. Screen OFF Charger Connect & Charger Disconnect announcements across ALL 4 Voice Profiles
        shadowPowerManager.setIsInteractive(false)
        com.example.service.BatteryTelemetryHub.setScreenInteractive(false)
        assertFalse(powerManager.isInteractive)
        assertFalse(com.example.service.BatteryTelemetryHub.isScreenInteractive.value)

        val voiceAssistant = com.example.service.BatteryTelemetryHub.getOrCreateVoiceAssistant(context)
        val testProfiles = listOf(
            VoiceProfilePreset.HINDI_FEMALE,
            VoiceProfilePreset.HINDI_MALE,
            VoiceProfilePreset.ENGLISH_FEMALE,
            VoiceProfilePreset.ENGLISH_MALE
        )

        kotlinx.coroutines.runBlocking {
            dataStore.updateVoiceAnnouncementsEnabled(true)
            dataStore.updateChargingAlerts(
                chargerConnected = true,
                chargerDisconnected = true,
                percentOnConnect = true,
                percentOnDisconnect = true
            )

            testProfiles.forEachIndexed { index, preset ->
                val connectPct = 40 + (index * 7)
                val disconnectPct = 78 + (index * 4)

                dataStore.selectVoicePreset(preset)
                val activeSettings = dataStore.getSettingsSnapshot()
                assertEquals(preset, activeSettings.activePreset)
                voiceAssistant.ensureEngineAliveAndSyncSettings(activeSettings)
                voiceAssistant.resetAnnouncementTrackingState()

                // Screen OFF + Charger Connected -> Speaks Battery %
                val connectBatteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(BatteryManager.EXTRA_LEVEL, connectPct)
                    putExtra(BatteryManager.EXTRA_SCALE, 100)
                    putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                    putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                }
                val connectSnap = com.example.service.SilentBackgroundMonitorEngine.evaluateSilentBackgroundEventNow(
                    context = context,
                    triggerAction = Intent.ACTION_POWER_CONNECTED,
                    batteryIntent = connectBatteryIntent
                )
                assertEquals(connectPct, connectSnap.percentage)
                assertTrue(connectSnap.isCharging)
                val connectSpoken = voiceAssistant.lastSpokenBanner.value.orEmpty()
                assertTrue(
                    "Screen OFF Charger Connected must announce $connectPct% for ${preset.personaName}, got: $connectSpoken",
                    connectSpoken.contains(connectPct.toString())
                )

                // Screen OFF + Charger Disconnected -> Speaks Battery %
                voiceAssistant.resetAnnouncementTrackingState()
                val disconnectBatteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(BatteryManager.EXTRA_LEVEL, disconnectPct)
                    putExtra(BatteryManager.EXTRA_SCALE, 100)
                    putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                    putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                }
                val disconnectSnap = com.example.service.SilentBackgroundMonitorEngine.evaluateSilentBackgroundEventNow(
                    context = context,
                    triggerAction = Intent.ACTION_POWER_DISCONNECTED,
                    batteryIntent = disconnectBatteryIntent
                )
                assertEquals(disconnectPct, disconnectSnap.percentage)
                assertFalse(disconnectSnap.isCharging)
                val disconnectSpoken = voiceAssistant.lastSpokenBanner.value.orEmpty()
                assertTrue(
                    "Screen OFF Charger Disconnected must announce $disconnectPct% for ${preset.personaName}, got: $disconnectSpoken",
                    disconnectSpoken.contains(disconnectPct.toString())
                )
            }
        }

        // Cleanup
        shadowPowerManager.setIsInteractive(true)
        com.example.service.BatteryTelemetryHub.setScreenInteractive(true)
        service.onStartCommand(
            Intent(context, com.example.service.BatteryMonitorService::class.java).apply {
                action = com.example.service.BatteryMonitorService.ACTION_STOP_MONITORING
            },
            0,
            99
        )
        controller.destroy()
        assertEquals(
            "Zero WakeLocks must remain held after audit completion",
            0,
            com.example.service.BatteryTelemetryHub.buildSelfPowerAuditReport(com.example.model.BatterySaverMode.NORMAL).activeHeldWakeLocksCount
        )
    }

    @Test
    fun testFeature1AutoOffRadiosOnChargeAndFeature2NoPersistentHourlyMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        val dataStore = com.example.data.preferences.SettingsDataStore(context)

        // 1. Verify BLUETOOTH_CONNECT is declared in AndroidManifest.xml
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(
            "AndroidManifest must declare BLUETOOTH_CONNECT for Android 12+",
            requestedPermissions.contains(android.Manifest.permission.BLUETOOTH_CONNECT)
        )

        // 2. Feature 1 disabled -> ChargerRadioController is a no-op
        com.example.hardware.ChargerRadioController.resetDebounceForTest()
        val disabledResult = com.example.hardware.ChargerRadioController.onChargerConnected(
            context = context,
            settings = com.example.model.UserSettings(autoDisableRadiosOnChargeEnabled = false),
            isAppInForeground = false,
            bypassDebounce = true
        )
        assertFalse(disabledResult.featureEnabled)
        assertFalse(disabledResult.internetPanelNotificationPosted)
        assertNull(
            shadowNotificationManager.getNotification(
                com.example.hardware.ChargerRadioController.NOTIFICATION_ID_CHARGER_RADIOS
            )
        )

        // 3. Feature 1 enabled -> Posts high-priority auto-cancel notification with "Open Internet Panel" action
        val enabledResult = com.example.hardware.ChargerRadioController.onChargerConnected(
            context = context,
            settings = com.example.model.UserSettings(
                autoDisableRadiosOnChargeEnabled = true,
                autoDisableBluetoothOnCharge = true,
                autoDisableWifiAndDataOnCharge = true
            ),
            isAppInForeground = false,
            bypassDebounce = true
        )
        assertTrue(enabledResult.featureEnabled)
        assertTrue(enabledResult.internetPanelNotificationPosted)
        val radioNotif = shadowNotificationManager.getNotification(
            com.example.hardware.ChargerRadioController.NOTIFICATION_ID_CHARGER_RADIOS
        )
        assertNotNull("Charger radio quick-off notification must be posted", radioNotif)
        assertEquals(
            "Charger Connected — Turn Off Wi-Fi & Mobile Data",
            radioNotif.extras.getString(android.app.Notification.EXTRA_TITLE)
        )
        assertEquals(
            "Charger radio notification must NOT be ongoing",
            0,
            radioNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT
        )
        assertTrue(
            "Charger radio notification must be auto-cancel",
            (radioNotif.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0
        )
        assertTrue(
            "Notification must include 'Open Internet Panel' action",
            radioNotif.actions?.any { it.title?.toString() == "Open Internet Panel" } == true
        )

        // Cancel on disconnect
        com.example.hardware.ChargerRadioController.cancelChargerRadioNotification(context)
        assertNull(
            shadowNotificationManager.getNotification(
                com.example.hardware.ChargerRadioController.NOTIFICATION_ID_CHARGER_RADIOS
            )
        )

        // 4. Feature 2: Switch to NO_PERSISTENT_HOURLY -> stops persistent FGS, cancels ID 4010, schedules 1-hour job
        kotlinx.coroutines.runBlocking {
            dataStore.updateBackgroundSettings(
                backgroundMonitoring = true,
                persistentNotification = false,
                silentNotification = false,
                monitoringMode = com.example.model.BackgroundMonitoringMode.NO_PERSISTENT_HOURLY
            )
            dataStore.updateHourlyStatusNotificationEnabled(true)
            val saved = dataStore.getSettingsSnapshot()
            assertEquals(com.example.model.BackgroundMonitoringMode.NO_PERSISTENT_HOURLY, saved.backgroundMonitoringMode)
            assertTrue(saved.hourlyStatusNotificationEnabled)
        }

        com.example.service.SilentBackgroundMonitorEngine.applyMonitoringMode(
            context = context,
            enabled = true,
            mode = com.example.model.BackgroundMonitoringMode.NO_PERSISTENT_HOURLY
        )
        assertFalse(
            "Persistent foreground notification flag must be false in NO_PERSISTENT_HOURLY mode",
            com.example.service.BatteryTelemetryHub.isForegroundNotificationVisible.value
        )
        assertNull(
            "Persistent notification ID 4010 must be cancelled in NO_PERSISTENT_HOURLY mode",
            shadowNotificationManager.getNotification(com.example.service.BatteryMonitorService.NOTIFICATION_ID)
        )
        val scheduledHourlyJob = jobScheduler.allPendingJobs.firstOrNull {
            it.id == com.example.service.SilentBackgroundMonitorEngine.JOB_ID_SILENT_PERIODIC
        }
        assertNotNull("Hourly periodic JobScheduler job must be scheduled", scheduledHourlyJob)
        assertEquals(
            com.example.service.SilentBackgroundMonitorEngine.JOB_PERIODIC_HOURLY_INTERVAL_MS,
            scheduledHourlyJob!!.intervalMillis
        )

        // 5. Hourly status notification must be auto-cancel & swipe-dismissible (not ongoing)
        val postedHourly = com.example.service.SilentBackgroundMonitorEngine.postHourlyStatusNotification(
            context = context,
            snapshot = com.example.model.BatteryHardwareSnapshot(
                percentage = 74,
                isPercentageValid = true,
                isCharging = false,
                statusLabel = "Discharging",
                healthLabel = "Good",
                temperatureCelsius = 33.4f
            )
        )
        assertTrue(postedHourly)
        val hourlyNotif = shadowNotificationManager.getNotification(
            com.example.service.SilentBackgroundMonitorEngine.NOTIFICATION_ID_HOURLY_STATUS
        )
        assertNotNull("Hourly status notification must be posted", hourlyNotif)
        assertEquals(
            "Hourly status notification must be swipe-dismissible (not ongoing)",
            0,
            hourlyNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT
        )
        assertTrue(
            "Hourly status notification must be auto-cancel",
            (hourlyNotif.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0
        )

        // 6. Selective unplug restore: Verify new manifest permissions, AccessibilityService registration,
        //    and that onChargerDisconnectedSuspending restores ONLY previously-ON radios & clears snapshot
        assertTrue(
            "AndroidManifest must declare ACCESS_WIFI_STATE",
            requestedPermissions.contains(android.Manifest.permission.ACCESS_WIFI_STATE)
        )
        assertTrue(
            "AndroidManifest must declare CHANGE_WIFI_STATE",
            requestedPermissions.contains(android.Manifest.permission.CHANGE_WIFI_STATE)
        )
        assertTrue(
            "AndroidManifest must declare ACCESS_NETWORK_STATE",
            requestedPermissions.contains(android.Manifest.permission.ACCESS_NETWORK_STATE)
        )
        val a11yServiceComponent = android.content.ComponentName(
            context,
            com.example.service.RadioToggleAccessibilityService::class.java
        )
        val a11yServiceInfo = context.packageManager.getServiceInfo(a11yServiceComponent, 0)
        assertEquals(
            android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            a11yServiceInfo.permission
        )
        assertTrue(
            "RadioToggleAccessibilityService must be exported=true so Android OS can bind it",
            a11yServiceInfo.exported
        )

        kotlinx.coroutines.runBlocking {
            dataStore.updateAutoDisableRadiosSettings(
                enabled = true,
                disableBluetooth = true,
                disableWifiAndData = true,
                restoreOnUnplug = true
            )
            // Simulate: before charging Wi-Fi & Mobile Data were ON, Bluetooth was OFF
            dataStore.savePreChargeRadioSnapshot(
                bluetoothOn = false,
                wifiOn = true,
                mobileDataOn = true
            )
            val preDisconnectSettings = dataStore.getSettingsSnapshot()
            assertTrue(preDisconnectSettings.hasPendingRadioRestoreOnUnplug)

            com.example.hardware.ChargerRadioController.resetDebounceForTest()
            val restoreResult = com.example.hardware.ChargerRadioController.onChargerDisconnectedSuspending(
                context = context,
                settings = preDisconnectSettings,
                dataStore = dataStore,
                bypassDebounce = true
            )
            assertTrue(restoreResult.restoreTriggered)
            assertFalse("Bluetooth was OFF before charging -> must stay false in restore plan", restoreResult.plan.restoreBluetooth)
            assertTrue("Wi-Fi was ON before charging -> must be true in restore plan", restoreResult.plan.restoreWifi)
            assertTrue("Mobile Data was ON before charging -> must be true in restore plan", restoreResult.plan.restoreMobileData)

            val postDisconnectSettings = dataStore.getSettingsSnapshot()
            assertFalse(
                "Pre-charge radio snapshot must be cleared after charger disconnect",
                postDisconnectSettings.hasPendingRadioRestoreOnUnplug
            )
        }

        // Cleanup
        com.example.hardware.ChargerRadioController.cancelAllRadioNotifications(context)
        com.example.service.SilentBackgroundMonitorEngine.stopSilentMonitoring(context)
    }
}

