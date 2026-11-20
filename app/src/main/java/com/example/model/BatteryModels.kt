package com.example.model

/**
 * Charging source reported by Android BatteryManager.EXTRA_PLUGGED.
 */
enum class ChargingSource(val displayName: String) {
    AC("AC Wall Charger"),
    USB("USB Port"),
    WIRELESS("Wireless Pad"),
    DOCK("Dock Charger"),
    NONE("On Battery")
}

/**
 * Classification of measured battery-side charging power.
 */
enum class ChargingSpeedTier(val label: String, val description: String) {
    NOT_CHARGING("Discharging", "Device is running on battery power"),
    SLOW("Slow Charging (< 5W)", "Standard USB or trickle charge rate"),
    NORMAL("Normal Charging (5W–15W)", "Standard AC adapter charging rate"),
    FAST("Fast Charging (15W–30W)", "High-speed PD / QC / VOOC fast charging"),
    SUPER_FAST("Super Fast (30W+)", "Ultra-fast high-current cell charging"),
    UNAVAILABLE("Speed N/A", "Kernel does not expose live current (mA) sensor")
}

/**
 * Confidence level and hardware origin for each battery diagnostic metric.
 */
enum class MetricConfidence(val badgeText: String) {
    HIGH_DIRECT("High • Direct Hardware Register"),
    MEDIUM_DERIVED("Medium • Derived from Hardware Counters"),
    QUALITATIVE_OS("OS Status Flag (Non-Numeric)"),
    UNAVAILABLE("N/A • Not Exposed by Device Kernel")
}

/**
 * Represents a single transparent hardware diagnostic metric with its API source,
 * confidence level, and explanation if unavailable.
 */
data class DiagnosticMetric(
    val title: String,
    val value: String,
    val unit: String = "",
    val sourceApi: String,
    val confidence: MetricConfidence,
    val explanation: String
)

/**
 * Point-in-time telemetry sample for the real-time Dashboard Charging History Graph.
 */
data class TelemetrySamplePoint(
    val timestampMillis: Long,
    val percentage: Int,
    val powerWatts: Float?,
    val currentMilliAmps: Int?,
    val temperatureCelsius: Float?
)

/**
 * Deep Battery Health Analysis report with explicit hardware source and confidence metadata.
 * Never claims Android's GOOD status means 100% battery health.
 */
data class BatteryHealthReport(
    val reportedOsHealthStatus: String,
    val osHealthExplanation: String,
    val cycleCount: Int?,
    val cycleCountSource: String,
    val designCapacityMah: Int?,
    val designCapacitySource: String,
    val designCapacityConfidence: MetricConfidence = MetricConfidence.UNAVAILABLE,
    val fullChargeCapacityMah: Int?,
    val fullChargeCapacitySource: String,
    val fullChargeCapacityConfidence: MetricConfidence = MetricConfidence.UNAVAILABLE,
    val remainingCapacityMah: Int?,
    val remainingCapacitySource: String,
    val energyCounterWh: Float?,
    val measuredCapacityHealthPercent: Float?,
    val isCapacityDataInconsistent: Boolean = false,
    val degradationSummary: String,
    val temperatureStatus: String
)

/**
 * Real hardware snapshot collected from Android BatteryManager, ACTION_BATTERY_CHANGED,
 * and readable kernel power_supply nodes.
 */
data class BatteryHardwareSnapshot(
    val percentage: Int = 0,
    val isPercentageValid: Boolean = true,
    val isCharging: Boolean = false,
    val isFull: Boolean = false,
    val statusLabel: String = "Unknown",
    val chargingSource: ChargingSource = ChargingSource.NONE,
    val voltageVolts: Float? = null,
    val voltageMillivolts: Int? = null,
    val currentMilliAmps: Int? = null,
    val currentSourceApi: String = "N/A",
    val averageCurrentMilliAmps: Int? = null,
    val averageCurrentSourceApi: String = "N/A",
    val powerWatts: Float? = null,
    val averagePowerWatts: Float? = null,
    val temperatureCelsius: Float? = null,
    val healthLabel: String = "N/A",
    val technology: String? = null,
    val chargeCounterMah: Int? = null,
    val fullChargeCapacityMah: Int? = null,
    val designCapacityMah: Int? = null,
    val cycleCount: Int? = null,
    val energyCounterWh: Float? = null,
    val estimatedMinutesToFull: Int? = null,
    val chargingSpeedTier: ChargingSpeedTier = ChargingSpeedTier.NOT_CHARGING,
    val percentPerHourRate: Float? = null,
    val isBatteryPresent: Boolean = true,
    val healthReport: BatteryHealthReport = BatteryHealthReport(
        reportedOsHealthStatus = "N/A",
        osHealthExplanation = "Awaiting hardware broadcast",
        cycleCount = null,
        cycleCountSource = "Not reported by kernel",
        designCapacityMah = null,
        designCapacitySource = "Not reported by kernel",
        designCapacityConfidence = MetricConfidence.UNAVAILABLE,
        fullChargeCapacityMah = null,
        fullChargeCapacitySource = "Not reported by kernel",
        fullChargeCapacityConfidence = MetricConfidence.UNAVAILABLE,
        remainingCapacityMah = null,
        remainingCapacitySource = "Not reported by kernel",
        energyCounterWh = null,
        measuredCapacityHealthPercent = null,
        isCapacityDataInconsistent = false,
        degradationSummary = "N/A — Hardware does not expose full-charge coulomb counter vs design capacity.",
        temperatureStatus = "N/A"
    ),
    val diagnosticMetrics: List<DiagnosticMetric> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis()
)

enum class VoiceLanguage(val code: String, val displayName: String, val nativeName: String) {
    HINDI("hi-IN", "Hindi", "हिन्दी (Indian Hindi)"),
    ENGLISH("en-IN", "English", "English (Indian English)")
}

enum class VoiceGender(val displayName: String, val hindiLabel: String) {
    FEMALE("Female Voice", "महिला आवाज़"),
    MALE("Male Voice", "पुरुष आवाज़")
}

/**
 * Voice Volume Presets for the Independent Voice Volume & Loudness Boost System.
 * Supports standard attenuation (20%–80%), default unity (100%), and PCM Loudness Boost (125%–200%).
 */
enum class VoiceVolumePreset(
    val label: String,
    val volumeFraction: Float,
    val percentLabel: String,
    val isBoost: Boolean = false
) {
    VERY_LOW("Very Low", 0.20f, "20%", false),
    LOW("Low", 0.40f, "40%", false),
    MEDIUM("Medium", 0.60f, "60%", false),
    HIGH("High", 0.80f, "80%", false),
    MAXIMUM("Maximum", 1.00f, "100%", false),
    BOOST_125("Boost 125%", 1.25f, "125%", true),
    BOOST_150("Boost 150%", 1.50f, "150%", true),
    BOOST_175("Boost 175%", 1.75f, "175%", true),
    BOOST_200("Boost 200%", 2.00f, "200%", true);

    companion object {
        fun matchingPresetOrNull(volume: Float): VoiceVolumePreset? {
            return entries.firstOrNull { kotlin.math.abs(it.volumeFraction - volume) < 0.03f }
        }
    }
}

/**
 * Measured 16-bit PCM loudness report before and after automatic loudness normalization,
 * user voice boost (100%–200%), and soft-knee anti-clipping limiting.
 */
data class PcmLoudnessMetrics(
    val sampleCount: Int = 0,
    val originalRmsSample: Int = 0,
    val originalRmsDbfs: Float = -96.0f,
    val originalPeakSample: Int = 0,
    val originalPeakDbfs: Float = -96.0f,
    val finalRmsSample: Int = 0,
    val finalRmsDbfs: Float = -96.0f,
    val finalPeakSample: Int = 0,
    val finalPeakDbfs: Float = -96.0f,
    val userGainMultiplier: Float = 1.0f,
    val normalizationGainMultiplier: Float = 1.0f,
    val totalAppliedPcmGain: Float = 1.0f,
    val totalAppliedGainDb: Float = 0.0f,
    val limiterEngaged: Boolean = false,
    val limiterEngagedSamples: Int = 0,
    val hardClippedSamples: Int = 0,
    val wavSignalDiagnosis: String = "N/A"
) {
    val originalRmsSummary: String
        get() = if (sampleCount == 0) "N/A" else String.format(java.util.Locale.US, "%.1f dBFS (%d / 32768)", originalRmsDbfs, originalRmsSample)

    val finalRmsSummary: String
        get() = if (sampleCount == 0) "N/A" else String.format(java.util.Locale.US, "%.1f dBFS (%d / 32768)", finalRmsDbfs, finalRmsSample)

    val originalPeakSummary: String
        get() = if (sampleCount == 0) "N/A" else String.format(java.util.Locale.US, "%.1f dBFS (%d / 32767)", originalPeakDbfs, originalPeakSample)

    val finalPeakSummary: String
        get() = if (sampleCount == 0) "N/A" else String.format(java.util.Locale.US, "%.1f dBFS (%d / 32767)", finalPeakDbfs, finalPeakSample)

    val appliedGainSummary: String
        get() = String.format(
            java.util.Locale.US,
            "User %.0f%% (%.2fx) × Norm %.2fx = %.2fx (%+.1f dB)",
            userGainMultiplier * 100f,
            userGainMultiplier,
            normalizationGainMultiplier,
            totalAppliedPcmGain,
            totalAppliedGainDb
        )

    val limiterSummary: String
        get() = if (sampleCount == 0) {
            "Idle (Awaiting PCM Stream)"
        } else if (limiterEngaged) {
            "Active ($limiterEngagedSamples samples soft-limited • $hardClippedSamples hard clips)"
        } else {
            "Linear Pass (No limiting needed • 0 hard clips)"
        }
}

/**
 * Smart Audio Focus & Media Ducking level.
 */
enum class MediaDuckingLevel(val label: String, val description: String) {
    OFF("OFF", "Do not request media ducking; speaks alongside other apps"),
    LOW("LOW", "Request light transient audio ducking (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)"),
    MEDIUM("MEDIUM", "Standard speech navigation ducking for music & video players"),
    HIGH("HIGH", "Exclusive transient focus (pauses or strongly ducks background media)")
}

/**
 * Announcement priority levels for the centralized VoiceQueueManager.
 * Lower rank number = Higher urgency.
 */
enum class AnnouncementPriority(val rank: Int, val label: String, val badgeText: String) {
    CRITICAL(1, "Priority 1 – Critical", "P1 CRITICAL"),
    IMPORTANT(2, "Priority 2 – Important", "P2 IMPORTANT"),
    NORMAL(3, "Priority 3 – Normal", "P3 NORMAL")
}

/**
 * Configurable repeat policy for queued announcements.
 */
enum class AnnouncementRepeatPolicy(val label: String, val description: String) {
    NONE("Play Once", "Speak each announcement a single time"),
    REPEAT_CRITICAL_ONCE("Repeat Critical Alerts", "Repeat Priority 1 (Overheat / Critical Low) twice for safety")
}

/**
 * Legitimate public Android AudioAttributes usages audited and selectable for voice announcements.
 * Default is ALARM_SPEECH (AudioAttributes.USAGE_ALARM = 4, AudioAttributes.CONTENT_TYPE_SPEECH = 1)
 * so all Battery Boss announcements route through Android's Alarm Volume stream (STREAM_ALARM)
 * instead of Media Volume (STREAM_MUSIC).
 */
enum class AudioStreamUsageOption(
    val usageConstant: Int,
    val contentTypeConstant: Int,
    val usageConstantName: String,
    val contentTypeConstantName: String,
    val title: String,
    val expectedPublicControlStreamName: String,
    val architecturalNote: String
) {
    ALARM_SPEECH(
        usageConstant = 4, // AudioAttributes.USAGE_ALARM
        contentTypeConstant = 1, // AudioAttributes.CONTENT_TYPE_SPEECH
        usageConstantName = "USAGE_ALARM (4)",
        contentTypeConstantName = "CONTENT_TYPE_SPEECH (1)",
        title = "Alarm Speech (Default)",
        expectedPublicControlStreamName = "STREAM_ALARM (4)",
        architecturalNote = "Routes AudioTrack to STREAM_ALARM (4) with CONTENT_TYPE_SPEECH (1). Remains audible when Media Volume (STREAM_MUSIC) is 0%, and is controlled by the system Alarm Volume slider."
    ),
    ACCESSIBILITY_SPEECH(
        usageConstant = 11, // AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        contentTypeConstant = 1, // AudioAttributes.CONTENT_TYPE_SPEECH
        usageConstantName = "USAGE_ASSISTANCE_ACCESSIBILITY (11)",
        contentTypeConstantName = "CONTENT_TYPE_SPEECH (1)",
        title = "Accessibility Speech",
        expectedPublicControlStreamName = "STREAM_ACCESSIBILITY (10)",
        architecturalNote = "Public volumeControlStream is STREAM_ACCESSIBILITY (10), but Android AudioPolicyManager routes it to STREAM_MUSIC (Media Volume) when no OS AccessibilityService is active."
    ),
    SONIFICATION_SPEECH(
        usageConstant = 13, // AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        contentTypeConstant = 4, // AudioAttributes.CONTENT_TYPE_SONIFICATION
        usageConstantName = "USAGE_ASSISTANCE_SONIFICATION (13)",
        contentTypeConstantName = "CONTENT_TYPE_SONIFICATION (4)",
        title = "Assistance Sonification",
        expectedPublicControlStreamName = "STREAM_SYSTEM (1)",
        architecturalNote = "Mapped to STREAM_SYSTEM (1). Separate from Media Volume on AOSP, but controlled by System/Ringtone volume and muted in Vibrate/Silent mode."
    ),
    NOTIFICATION_SPEECH(
        usageConstant = 10, // AudioAttributes.USAGE_NOTIFICATION_EVENT
        contentTypeConstant = 1, // AudioAttributes.CONTENT_TYPE_SPEECH
        usageConstantName = "USAGE_NOTIFICATION_EVENT (10)",
        contentTypeConstantName = "CONTENT_TYPE_SPEECH (1)",
        title = "Notification Event",
        expectedPublicControlStreamName = "STREAM_NOTIFICATION (5)",
        architecturalNote = "Mapped to STREAM_NOTIFICATION (5). Separate from Media Volume, but controlled by the system Notification Volume slider and muted in Vibrate/Silent mode."
    ),
    MEDIA_SPEECH(
        usageConstant = 1, // AudioAttributes.USAGE_MEDIA
        contentTypeConstant = 1, // AudioAttributes.CONTENT_TYPE_SPEECH
        usageConstantName = "USAGE_MEDIA (1)",
        contentTypeConstantName = "CONTENT_TYPE_SPEECH (1)",
        title = "Media Speech",
        expectedPublicControlStreamName = "STREAM_MUSIC (3)",
        architecturalNote = "Directly mapped to STREAM_MUSIC (3). Controlled by the system Media Volume slider."
    )
}

/**
 * Snapshot of a public Android AudioManager volume group/stream on the live device.
 */
data class OsVolumeStreamSnapshot(
    val streamType: Int,
    val streamName: String,
    val currentIndex: Int,
    val minIndex: Int,
    val maxIndex: Int,
    val volumePercent: Int,
    val isMuted: Boolean,
    val currentAttenuationDb: Float?,
    val minIndexAttenuationDb: Float?
) {
    val attenuationSummary: String
        get() {
            val db = currentAttenuationDb ?: return "dB N/A"
            return if (db <= -100f || db.isInfinite()) {
                "-∞ dB (Muted)"
            } else {
                String.format(java.util.Locale.US, "%+.1f dB", db)
            }
        }
}

/**
 * Live device audit result for a specific public AudioAttributes usage.
 */
data class UsageRoutingAuditEntry(
    val option: AudioStreamUsageOption,
    val reportedVolumeControlStream: Int,
    val reportedVolumeControlStreamName: String,
    val effectivePolicyStreamName: String,
    val isMappedToStreamMusic: Boolean,
    val doesMediaVolumeChangeLoudness: Boolean,
    val isIndependentAppPrivateStream: Boolean,
    val controllingSystemSliderName: String,
    val statusVerdict: String
)

/**
 * Empirical & dB-curve observation record at Media Volume = 0%, 50%, and 100%.
 */
data class MediaVolumeStepObservation(
    val targetMediaPercent: Int,
    val streamMusicIndex: Int,
    val streamMusicMaxIndex: Int,
    val streamMusicAttenuationDb: Float?,
    val activeUsageConstantName: String,
    val effectivePolicyStreamName: String,
    val expectedAndObservedBehavior: String,
    val verifiedByPlaybackAtThisLevel: Boolean
)

/**
 * Verification matrix row for the 3 Media vs. Alarm Volume combinations:
 * 1. Media Volume = 0%, Alarm Volume = 100%
 * 2. Media Volume = 100%, Alarm Volume = 0%
 * 3. Media Volume = 50%, Alarm Volume = 50%
 */
data class AlarmMediaTestMatrixRow(
    val scenarioId: String,
    val scenarioLabel: String,
    val targetMediaPercent: Int,
    val targetAlarmPercent: Int,
    val alarmStreamIndex: Int,
    val alarmStreamMaxIndex: Int,
    val alarmAttenuationDb: Float?,
    val mediaAttenuationDb: Float?,
    val expectedAndObservedResult: String,
    val verifiedLiveOnDevice: Boolean
)

/**
 * Detailed trace record captured for every announcement playback attempt.
 */
data class PlaybackTraceRecord(
    val timestampMillis: Long = System.currentTimeMillis(),
    val entryPoint: String = "None",
    val selectedTtsEngine: String = "N/A",
    val selectedTtsVoiceName: String = "N/A",
    val synthesizeToFileSucceeded: Boolean = false,
    val pcmWavDecoded: Boolean = false,
    val decodedWavFormatSummary: String = "N/A",
    val audioTrackCreated: Boolean = false,
    val actualAudioAttributesUsage: String = "USAGE_ALARM (4)",
    val actualAudioAttributesContentType: String = "CONTENT_TYPE_SPEECH (1)",
    val reportedVolumeControlStream: String = "STREAM_ALARM (4 • Alarm Volume)",
    val effectiveOsPolicyStream: String = "STREAM_ALARM (4 • Alarm Volume)",
    val controllingSystemSlider: String = "Alarm Volume Slider (STREAM_ALARM)",
    val doesMediaVolumeChangeLoudness: Boolean = false,
    val isControlledByAlarmVolume: Boolean = true,
    val isIndependentSystemVolumeSupported: Boolean = false,
    val audioTrackPlaybackState: String = "PLAYSTATE_STOPPED (1)",
    val directTtsFallbackUsed: Boolean = false,
    val effectivePcmGain: Float = 1.0f,
    val normalizationGain: Float = 1.0f,
    val totalAppliedPcmGain: Float = 1.0f,
    val audioTrackStreamGain: Float = 1.0f,
    val loudnessMetrics: PcmLoudnessMetrics = PcmLoudnessMetrics(),
    val systemMediaVolumePercent: Int? = null,
    val systemAlarmVolumePercent: Int? = null,
    val systemAlarmVolumeDb: Float? = null,
    val errorOrLimitation: String = "None"
)

/**
 * Real-time state exposed to the Audio Stream Routing & Diagnostics Screen.
 */
data class AudioDiagnosticsState(
    val selectedVoiceLabel: String = "Hindi Female • hi_IN-priyamvada-medium",
    val selectedTtsEnginePackage: String = "Default System TTS",
    val selectedTtsVoiceName: String = "Default Offline Voice",
    val playbackEngineLabel: String = "SynthesizeToFile -> AudioTrack (USAGE_ALARM -> STREAM_ALARM)",
    val independentVolumeEnabled: Boolean = true,
    val activeAudioStreamUsageOption: AudioStreamUsageOption = AudioStreamUsageOption.ALARM_SPEECH,
    val autoLoudnessNormalizationEnabled: Boolean = true,
    val independentVolumePercent: Int = 100,
    val effectiveGainValue: Float = 1.0f,
    val normalizationGainValue: Float = 1.0f,
    val totalAppliedPcmGainValue: Float = 1.0f,
    val audioTrackStreamGainValue: Float = 1.0f,
    val originalRmsReport: String = "Awaiting First Utterance",
    val finalRmsReport: String = "Awaiting First Utterance",
    val originalPeakReport: String = "Awaiting First Utterance",
    val finalPeakReport: String = "Awaiting First Utterance",
    val appliedGainReport: String = "100% (1.00x App-Level PCM Gain • Auto-Norm Ready)",
    val limiterStatusReport: String = "Soft-Knee Tanh Limiter Ready (-0.13 dBFS Ceiling)",
    val ttsWavSignalDiagnosis: String = "Awaiting First WAV Synthesis",
    val playbackState: String = "Idle",
    val audioFocusState: String = "Idle (No Active Focus Request)",
    val mediaDuckingLevel: String = "MEDIUM",
    val outputDeviceName: String = "N/A",
    val currentPriorityLabel: String = "None",
    val queueSize: Int = 0,
    val queueSummary: String = "Queue Empty (0 pending)",
    val callOrAlarmState: String = "Normal (No Call/Alarm Active)",
    val lastPlaybackError: String = "None",
    // Trace & Routing Verification Fields
    val lastTraceEntryPoint: String = "None (Run Test Voice or Connect Charger)",
    val synthesizeToFileSucceeded: Boolean? = null,
    val pcmWavDecoded: Boolean? = null,
    val decodedWavFormatSummary: String = "N/A",
    val audioTrackCreated: Boolean? = null,
    val actualAudioAttributesUsage: String = "USAGE_ALARM (4)",
    val actualAudioAttributesContentType: String = "CONTENT_TYPE_SPEECH (1)",
    val reportedVolumeControlStream: String = "STREAM_ALARM (4 • Alarm Volume)",
    val effectiveOsPolicyStream: String = "STREAM_ALARM (4 • Alarm Volume)",
    val controllingSystemSlider: String = "Alarm Volume Slider (STREAM_ALARM)",
    val isMappedToStreamMusic: Boolean = false,
    val doesMediaVolumeChangeLoudness: Boolean = false,
    val isControlledByAlarmVolume: Boolean = true,
    val isIndependentSystemVolumeSupported: Boolean = false,
    val streamIndependenceVerdictBadge: String = "ROUTED TO ALARM STREAM (STREAM_ALARM • INDEPENDENT OF MEDIA VOLUME)",
    val volumeGroupSummary: String = "Awaiting volume group audit",
    val observedRoutingBehaviorSummary: String = "Awaiting live stream routing inspection",
    val osStreamSnapshots: List<OsVolumeStreamSnapshot> = emptyList(),
    val usageRoutingAudits: List<UsageRoutingAuditEntry> = emptyList(),
    val mediaVolumeStepObservations: List<MediaVolumeStepObservation> = emptyList(),
    val alarmMediaTestMatrix: List<AlarmMediaTestMatrixRow> = emptyList(),
    val audioTrackPlaybackState: String = "Idle",
    val directTtsFallbackUsed: Boolean = false,
    val systemMediaVolumePercent: Int? = null,
    val systemAlarmVolumePercent: Int? = null,
    val systemAlarmVolumeSummary: String = "N/A",
    val isAccessibilityServiceEnabledOnOs: Boolean = false,
    val osStreamRoutingLimitationNote: String = "Awaiting hardware audio routing inspection"
)

/**
 * Four independent neural/offline voice profiles for Battery Boss.
 */
enum class VoiceProfilePreset(
    val id: String,
    val title: String,
    val personaName: String,
    val subtitle: String,
    val voiceDescription: String,
    val language: VoiceLanguage,
    val gender: VoiceGender,
    val defaultPitch: Float,
    val defaultSpeed: Float,
    val piperModelId: String,
    val samplePhrase: String
) {
    HINDI_FEMALE(
        id = "hindi_female",
        title = "Hindi Female",
        personaName = "Priyamvada (प्रियम्वदा)",
        subtitle = "hi_IN-priyamvada-medium • Natural Indian Hindi Female",
        voiceDescription = "Warm, expressive Indian Hindi female neural prosody with natural breath pauses.",
        language = VoiceLanguage.HINDI,
        gender = VoiceGender.FEMALE,
        defaultPitch = 1.04f,
        defaultSpeed = 0.96f,
        piperModelId = "hi_IN-priyamvada-medium",
        samplePhrase = "नमस्ते बॉस! मैं प्रियम्वदा हूँ। चार्जर लग गया है बॉस। अब बैटरी चार्ज हो रही है।"
    ),
    HINDI_MALE(
        id = "hindi_male",
        title = "Hindi Male",
        personaName = "Rohan (रोहन)",
        subtitle = "hi_IN-rohan-medium • Deep Confident Indian Hindi Male",
        voiceDescription = "Deep, resonant Indian Hindi male voice tuned for clear Boss status briefings.",
        language = VoiceLanguage.HINDI,
        gender = VoiceGender.MALE,
        defaultPitch = 0.84f,
        defaultSpeed = 0.95f,
        piperModelId = "hi_IN-rohan-medium",
        samplePhrase = "नमस्ते बॉस! मैं रोहन हूँ। बॉस, अब बैटरी आधी चार्ज हो चुकी है।"
    ),
    ENGLISH_FEMALE(
        id = "english_female",
        title = "English Female",
        personaName = "Lessac / Prerana (English)",
        subtitle = "en_US-lessac-medium • Natural Indian/US English Female",
        voiceDescription = "Crisp, natural English female articulation with smooth sentence cadence.",
        language = VoiceLanguage.ENGLISH,
        gender = VoiceGender.FEMALE,
        defaultPitch = 1.03f,
        defaultSpeed = 0.97f,
        piperModelId = "en_US-lessac-medium",
        samplePhrase = "Hello Boss! Charger connected, Boss. Battery is now charging smoothly."
    ),
    ENGLISH_MALE(
        id = "english_male",
        title = "English Male",
        personaName = "Ryan / Prabhat (English)",
        subtitle = "en_US-ryan-medium • Deep Natural English Male",
        voiceDescription = "Authoritative, calm English male voice with balanced low-frequency resonance.",
        language = VoiceLanguage.ENGLISH,
        gender = VoiceGender.MALE,
        defaultPitch = 0.82f,
        defaultSpeed = 0.96f,
        piperModelId = "en_US-ryan-medium",
        samplePhrase = "Hello Boss! Boss, battery is fully charged. You can unplug the charger now."
    )
}

enum class VoiceModelState(val label: String) {
    BUNDLED_READY("Piper ONNX Verified"),
    INSTALLED_LOCAL("System Offline TTS"),
    INSTALLING("Verifying Model..."),
    SYSTEM_VOICE_MISSING("System TTS Active")
}

/**
 * Metadata and licensing for offline Sherpa-ONNX / Piper neural models and Android offline TTS packs.
 */
data class OfflineVoiceModelPack(
    val preset: VoiceProfilePreset,
    val modelId: String,
    val displayName: String,
    val language: VoiceLanguage,
    val gender: VoiceGender,
    val engineArchitecture: String,
    val sampleRateHz: Int,
    val sizeMb: Float,
    val licenseName: String,
    val licenseAttribution: String,
    val state: VoiceModelState,
    val isPiperOnnxVerified: Boolean = false,
    val systemOfflineVoiceMatched: String? = null,
    val isTrueGenderMatchedVoice: Boolean = false
)

/**
 * Audio sample scenario for the Voice Studio Comparison Matrix.
 */
data class VoiceSampleScenario(
    val id: String,
    val title: String,
    val badge: String,
    val hindiText: String,
    val englishText: String
)

/**
 * Conversational Smart Boss default Hindi and English phrases.
 */
object DefaultBossMessages {
    const val HI_CHARGER_CONNECTED = "बॉस, चार्जर लग गया है। अभी बैटरी {level} प्रतिशत है।"
    const val HI_CHARGER_CONNECTED_NO_PERCENT = "चार्जर लग गया है बॉस। अब बैटरी चार्ज हो रही है।"
    const val HI_BATTERY_20 = "बॉस, बैटरी 20 प्रतिशत हो गई है।"
    const val HI_BATTERY_50 = "बॉस, अब बैटरी आधी चार्ज हो चुकी है।"
    const val HI_BATTERY_80 = "बॉस, बैटरी 80 प्रतिशत तक पहुँच गई है।"
    const val HI_BATTERY_FULL = "बॉस, बैटरी फुल हो गई है। अब चार्जर निकाल सकते हैं।"
    const val HI_HIGH_TEMP = "बॉस, मोबाइल थोड़ा ज्यादा गर्म हो रहा है।"
    const val HI_CHARGER_DISCONNECTED = "बॉस, चार्जर निकाल दिया गया है। अभी बैटरी {level} प्रतिशत है।"
    const val HI_CHARGER_DISCONNECTED_NO_PERCENT = "बॉस, चार्जर निकाल दिया गया है।"
    const val HI_BATTERY_INTERVAL = "बॉस, बैटरी {level} प्रतिशत चार्ज हो गई है।"
    const val HI_LOW_BATTERY = "बॉस, बैटरी कम है, अभी {level} प्रतिशत बची है।"

    const val EN_CHARGER_CONNECTED = "Boss, charger connected. Battery is currently at {level} percent."
    const val EN_CHARGER_CONNECTED_NO_PERCENT = "Charger connected, Boss. Battery is now charging."
    const val EN_BATTERY_20 = "Boss, battery has reached 20 percent."
    const val EN_BATTERY_50 = "Boss, battery is now half charged at 50 percent."
    const val EN_BATTERY_80 = "Boss, battery has reached 80 percent."
    const val EN_BATTERY_FULL = "Boss, battery is fully charged. You can unplug the charger now."
    const val EN_HIGH_TEMP = "Boss, the phone is getting a bit too warm."
    const val EN_CHARGER_DISCONNECTED = "Boss, charger disconnected. Battery is currently at {level} percent."
    const val EN_CHARGER_DISCONNECTED_NO_PERCENT = "Boss, the charger has been unplugged."
    const val EN_BATTERY_INTERVAL = "Boss, battery is {level} percent charged."
    const val EN_LOW_BATTERY = "Boss, battery is low at {level} percent."

    val comparisonScenarios: List<VoiceSampleScenario> = listOf(
        VoiceSampleScenario(
            id = "connected",
            title = "Charger Connected",
            badge = "PLUG IN",
            hindiText = HI_CHARGER_CONNECTED.replace("{level}", "45"),
            englishText = EN_CHARGER_CONNECTED.replace("{level}", "45")
        ),
        VoiceSampleScenario(
            id = "milestone_20",
            title = "Battery 20% Milestone",
            badge = "20%",
            hindiText = HI_BATTERY_20,
            englishText = EN_BATTERY_20
        ),
        VoiceSampleScenario(
            id = "milestone_50",
            title = "Battery 50% Halfway",
            badge = "50%",
            hindiText = HI_BATTERY_50,
            englishText = EN_BATTERY_50
        ),
        VoiceSampleScenario(
            id = "milestone_80",
            title = "Battery 80% Milestone",
            badge = "80%",
            hindiText = HI_BATTERY_80,
            englishText = EN_BATTERY_80
        ),
        VoiceSampleScenario(
            id = "full_100",
            title = "Battery 100% Full",
            badge = "100%",
            hindiText = HI_BATTERY_FULL,
            englishText = EN_BATTERY_FULL
        ),
        VoiceSampleScenario(
            id = "high_temp",
            title = "High Temperature Alert",
            badge = "THERMAL",
            hindiText = HI_HIGH_TEMP,
            englishText = EN_HIGH_TEMP
        ),
        VoiceSampleScenario(
            id = "disconnected",
            title = "Charger Disconnected",
            badge = "UNPLUG",
            hindiText = HI_CHARGER_DISCONNECTED.replace("{level}", "85"),
            englishText = EN_CHARGER_DISCONNECTED.replace("{level}", "85")
        )
    )
}

/**
 * Real-time OS Notification Channel state read directly from Android NotificationManager.
 * Android locks a NotificationChannel's importance once created; only the user can alter
 * an existing channel's importance in System Settings.
 */
data class NotificationChannelDiagnostics(
    val activeChannelId: String = "battery_boss_silent_channel",
    val activeChannelOsImportance: Int = 1,
    val silentChannelOsImportance: Int? = null,
    val standardChannelOsImportance: Int? = null,
    val areAppNotificationsEnabledOnOs: Boolean = true,
    val isActiveChannelBlockedByUser: Boolean = false,
    val serviceInstanceCreateCount: Int = 0,
    val serviceInstanceDestroyCount: Int = 0,
    val inPlaceNotificationUpdatesCount: Int = 0
) {
    fun importanceToLabel(importance: Int?): String = when (importance) {
        null -> "Not Yet Created"
        0 -> "IMPORTANCE_NONE (Blocked by User)"
        1 -> "IMPORTANCE_MIN (Silent • Collapsed)"
        2 -> "IMPORTANCE_LOW (Silent • Shown in Shade)"
        3 -> "IMPORTANCE_DEFAULT (Sound / Normal)"
        4, 5 -> "IMPORTANCE_HIGH (Heads-Up Alert)"
        else -> "Importance ($importance)"
    }

    val activeImportanceLabel: String
        get() = importanceToLabel(activeChannelOsImportance)
}

/**
 * Two distinct user-selectable Foreground Service notification presentation modes in Battery Boss 2.0:
 * Both keep `BatteryMonitorService` (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) continuously running so
 * Screen-Off Charger Connect/Disconnect and Battery Voice Announcements never stop.
 *
 * Switching modes reposts `NOTIFICATION_ID` onto one of two immutable Notification Channels
 * (`battery_boss_silent_channel` or `battery_boss_standard_channel`) IN-PLACE without restarting
 * the Foreground Service, and respects any user customization in Android System Settings.
 */
enum class BackgroundMonitoringMode(
    val title: String,
    val shortBadge: String,
    val channelId: String,
    val requiresPersistentNotification: Boolean,
    val usesForegroundService: Boolean,
    val mechanismSummary: String,
    val reliabilityAndLimitationsNote: String
) {
    SILENT_BACKGROUND(
        title = "Silent Notification Channel (battery_boss_silent_channel)",
        shortBadge = "SILENT CHANNEL • FGS ON",
        channelId = "battery_boss_silent_channel",
        requiresPersistentNotification = true,
        usesForegroundService = true,
        mechanismSummary = "BatteryMonitorService (specialUse) • Reposts NOTIFICATION_ID onto channel 'battery_boss_silent_channel' without restarting service",
        reliabilityAndLimitationsNote = "Channel 'battery_boss_silent_channel' is created at IMPORTANCE_MIN (compact 'Battery Boss Active • 83%', 0 buttons, 0 sound/vibration). Android locks channel importance once created and respects your System Settings override; Battery Boss switches channels by reposting onto the silent channel without stopping the Foreground Service."
    ),
    CONTINUOUS_FOREGROUND(
        title = "Standard Notification Channel (battery_boss_standard_channel)",
        shortBadge = "STANDARD CHANNEL • FGS ON",
        channelId = "battery_boss_standard_channel",
        requiresPersistentNotification = true,
        usesForegroundService = true,
        mechanismSummary = "BatteryMonitorService (specialUse) • Reposts NOTIFICATION_ID onto channel 'battery_boss_standard_channel' without restarting service",
        reliabilityAndLimitationsNote = "Channel 'battery_boss_standard_channel' is created at IMPORTANCE_LOW (includes STOP, SPEAK STATUS, and MUTE/RESUME action buttons). Android locks channel importance once created; only you can modify a channel's importance in Android Notification Settings."
    )
}

/**
 * Complete user settings persisted via DataStore.
 */
data class UserSettings(
    // Voice Settings
    val language: VoiceLanguage = VoiceLanguage.HINDI,
    val gender: VoiceGender = VoiceGender.FEMALE,
    val voiceSpeed: Float = 0.96f,
    val voicePitch: Float = 1.04f,
    val voiceVolume: Float = 1.0f, // Default 1.0f (100%) App-Level PCM Gain; supports 0.0f..2.0f (0%..200% Boost)
    val lastRememberedVoiceVolume: Float = 1.0f,
    val independentVolumeEnabled: Boolean = true,
    val audioStreamUsageOption: AudioStreamUsageOption = AudioStreamUsageOption.ALARM_SPEECH,
    val autoLoudnessNormalizationEnabled: Boolean = true,
    val rememberLastVoiceVolume: Boolean = true,
    val applyVolumeToAllAnnouncements: Boolean = true,
    val mediaDuckingLevel: MediaDuckingLevel = MediaDuckingLevel.MEDIUM,
    val repeatPolicy: AnnouncementRepeatPolicy = AnnouncementRepeatPolicy.NONE,
    val selectedModelId: String = VoiceProfilePreset.HINDI_FEMALE.piperModelId,
    val naturalProsodyEnabled: Boolean = true,

    // Conversational Smart Boss Messages (Hindi)
    val msgHiConnected: String = DefaultBossMessages.HI_CHARGER_CONNECTED,
    val msgHiDisconnected: String = DefaultBossMessages.HI_CHARGER_DISCONNECTED,
    val msgHi20Percent: String = DefaultBossMessages.HI_BATTERY_20,
    val msgHi50Percent: String = DefaultBossMessages.HI_BATTERY_50,
    val msgHi80Percent: String = DefaultBossMessages.HI_BATTERY_80,
    val msgHiInterval: String = DefaultBossMessages.HI_BATTERY_INTERVAL,
    val msgHiFull: String = DefaultBossMessages.HI_BATTERY_FULL,
    val msgHiLowBattery: String = DefaultBossMessages.HI_LOW_BATTERY,
    val msgHiHighTemp: String = DefaultBossMessages.HI_HIGH_TEMP,

    // Conversational Smart Boss Messages (English)
    val msgEnConnected: String = DefaultBossMessages.EN_CHARGER_CONNECTED,
    val msgEnDisconnected: String = DefaultBossMessages.EN_CHARGER_DISCONNECTED,
    val msgEn20Percent: String = DefaultBossMessages.EN_BATTERY_20,
    val msgEn50Percent: String = DefaultBossMessages.EN_BATTERY_50,
    val msgEn80Percent: String = DefaultBossMessages.EN_BATTERY_80,
    val msgEnInterval: String = DefaultBossMessages.EN_BATTERY_INTERVAL,
    val msgEnFull: String = DefaultBossMessages.EN_BATTERY_FULL,
    val msgEnLowBattery: String = DefaultBossMessages.EN_LOW_BATTERY,
    val msgEnHighTemp: String = DefaultBossMessages.EN_HIGH_TEMP,

    // Charging Announcement Triggers
    val announceIntervalPercent: Int = 10, // 5, 10, 15, 20, or custom (1..50)
    val announceIntervalEnabled: Boolean = true,
    val announceSmartMilestones: Boolean = true, // 20%, 50%, 80% conversational alerts
    val announceFullCharge: Boolean = true,
    val announceChargerConnected: Boolean = true,
    val announceChargerDisconnected: Boolean = true,
    val announceBatteryPercentOnConnect: Boolean = true,
    val announceBatteryPercentOnDisconnect: Boolean = true,
    val announceLowBattery: Boolean = true,
    val lowBatteryThreshold: Int = 20,
    val announceTemperatureWarning: Boolean = true,
    val temperatureWarningCelsius: Int = 42,

    // Quiet Hours & Ringer Mode Controls
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 23, // 11:00 PM
    val quietEndHour: Int = 7,    // 7:00 AM
    val respectSystemRinger: Boolean = false,

    // 3 Independent Background Controls:
    // 1. Background Monitoring ON/OFF (Foreground Service)
    // 2. Background Voice ON/OFF (voiceAnnouncementsEnabled)
    // 3. Silent Notification ON/OFF (silentNotificationEnabled - IMPORTANCE_MIN compact 'Battery Boss Active • 83%')
    val backgroundMonitoringEnabled: Boolean = true,
    val backgroundMonitoringMode: BackgroundMonitoringMode = BackgroundMonitoringMode.SILENT_BACKGROUND,
    val voiceAnnouncementsEnabled: Boolean = true,
    val silentNotificationEnabled: Boolean = true,
    val startOnBootEnabled: Boolean = true,
    val persistentNotificationEnabled: Boolean = true,
    val installedModelIds: Set<String> = emptySet(),

    // Smart Battery Saver & Auto Battery Management Settings
    val batterySaverMode: BatterySaverMode = BatterySaverMode.NORMAL,
    val autoBatterySaverEnabled: Boolean = false,
    val autoSaverThresholdPercent: Int = 30,
    val autoSaverTargetMode: BatterySaverMode = BatterySaverMode.SMART_SAVER,
    val autoSaverRestoreNormalEnabled: Boolean = true,
    val autoSaverRecoveryPercent: Int = 60,

    // Automatic App Restriction & Protected / Excluded Lists
    val autoRestrictHighUsageEnabled: Boolean = false,
    val restrictionBatteryThresholdPercent: Int = 35,
    val protectedAppPackages: Set<String> = emptySet(),
    val excludedAppPackages: Set<String> = emptySet(),
    val userPermittedRestrictPackages: Set<String> = emptySet(),

    // Smart Battery Alerts Toggles
    val alertHighAppBatteryUsage: Boolean = true,
    val alertHighBackgroundActivity: Boolean = true,
    val alertRapidBatteryDrain: Boolean = true,
    val alertHighBatteryTemp: Boolean = true,
    val alertSaverRecommendation: Boolean = true,
    val alertAbnormalScreenDrain: Boolean = true
) {
    val activePreset: VoiceProfilePreset
        get() = when {
            language == VoiceLanguage.HINDI && gender == VoiceGender.FEMALE -> VoiceProfilePreset.HINDI_FEMALE
            language == VoiceLanguage.HINDI && gender == VoiceGender.MALE -> VoiceProfilePreset.HINDI_MALE
            language == VoiceLanguage.ENGLISH && gender == VoiceGender.FEMALE -> VoiceProfilePreset.ENGLISH_FEMALE
            else -> VoiceProfilePreset.ENGLISH_MALE
        }
}

/**
 * OEM-specific background execution guidance based on Build.MANUFACTURER.
 */
data class OemOptimizationGuide(
    val manufacturerName: String,
    val osSkinName: String,
    val steps: List<String>,
    val caveatNote: String
)
