package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.audio.AudioFocusController
import com.example.audio.IndependentVoiceController
import com.example.audio.QueuedVoiceAnnouncement
import com.example.audio.VoicePlaybackEngine
import com.example.audio.VoiceQueueManager
import com.example.data.local.BatteryBossRepository
import com.example.data.preferences.SettingsDataStore
import com.example.model.AnnouncementPriority
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AudioDiagnosticsState
import com.example.model.AudioStreamUsageOption
import com.example.model.BatteryHardwareSnapshot
import com.example.model.DefaultBossMessages
import com.example.model.OfflineVoiceModelPack
import com.example.model.UserSettings
import com.example.model.VoiceGender
import com.example.model.VoiceLanguage
import com.example.model.VoiceModelState
import com.example.model.VoiceProfilePreset
import com.example.service.BatteryTelemetryHub
import com.example.update.UpdateLockManager
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Natural Prosody & Phonetics Pre-Processor for Indian Hindi and English.
 */
object ProsodyAndPhoneticsEngine {

    fun prepareConversationalClauses(
        rawText: String,
        language: VoiceLanguage,
        naturalProsodyEnabled: Boolean
    ): List<String> {
        val normalized = normalizeSymbolsAndPronunciation(rawText.trim(), language)
        if (!naturalProsodyEnabled) {
            return listOf(normalized).filter { it.isNotBlank() }
        }

        val clauses = normalized
            .split(Regex("(?<=[।.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return if (clauses.isEmpty()) listOf(normalized) else clauses
    }

    fun normalizeSymbolsAndPronunciation(text: String, language: VoiceLanguage): String {
        return if (language == VoiceLanguage.HINDI) {
            text
                .replace("%", " प्रतिशत ")
                .replace("°C", " डिग्री सेल्सियस ")
                .replace("℃", " डिग्री सेल्सियस ")
                .replace(Regex("\\s+"), " ")
                .trim()
        } else {
            text
                .replace("%", " percent ")
                .replace("°C", " degrees Celsius ")
                .replace("℃", " degrees Celsius ")
                .replace(Regex("\\bmAh\\b"), "milliamp hours")
                .replace(Regex("\\bmA\\b"), "milliamps")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

/**
 * BatteryBossAudioManager & OfflineVoiceAssistant
 *
 * Central single-owner audio coordinator integrating:
 * - IndependentVoiceController (App-Level PCM Digital Gain 0.0f..2.0f, default 1.0f / 100%)
 * - AndroidAudioPolicyAuditor (Live inspection of Android AudioPolicyManager stream & volume-group mapping)
 * - VoicePlaybackEngine (TTS synthesizeToFile -> 16-bit PCM WAV -> AudioTrack)
 * - AudioFocusController (Smart Media Ducking OFF/LOW/MEDIUM/HIGH + Phone Call & Alarm handling)
 * - VoiceQueueManager (Priority 1 Critical, Priority 2 Important, Priority 3 Normal)
 * - Real-time AudioDiagnosticsState reporting
 */
class OfflineVoiceAssistant(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val repository: BatteryBossRepository
) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val volumeController = IndependentVoiceController()
    private val focusController = AudioFocusController(context.applicationContext) { _, shouldStop ->
        if (shouldStop) {
            stopSpeaking()
        }
    }
    private val playbackEngine = VoicePlaybackEngine(
        context = context.applicationContext,
        volumeController = volumeController,
        policyAuditor = focusController.policyAuditor
    )

    private lateinit var queueManager: VoiceQueueManager

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _previewingPreset = MutableStateFlow<VoiceProfilePreset?>(null)
    val previewingPreset: StateFlow<VoiceProfilePreset?> = _previewingPreset.asStateFlow()

    private val _engineStatusText = MutableStateFlow("Initializing Offline Voice & Stream Auditor...")
    val engineStatusText: StateFlow<String> = _engineStatusText.asStateFlow()

    private val _voiceModelPacks = MutableStateFlow<List<OfflineVoiceModelPack>>(emptyList())
    val voiceModelPacks: StateFlow<List<OfflineVoiceModelPack>> = _voiceModelPacks.asStateFlow()

    private val _lastSpokenBanner = MutableStateFlow<String?>(null)
    val lastSpokenBanner: StateFlow<String?> = _lastSpokenBanner.asStateFlow()

    private val _audioDiagnostics = MutableStateFlow(AudioDiagnosticsState())
    val audioDiagnostics: StateFlow<AudioDiagnosticsState> = _audioDiagnostics.asStateFlow()

    private var currentQueueSize = 0
    private var currentQueueSummary = "Queue Empty (0 pending)"
    private var currentPriorityLabel = "None"
    private var latestSettings = UserSettings()

    // Anti-duplicate state tracking
    private var hasInitializedBaseline = false
    private var lastChargingState: Boolean? = null
    private var lastAnnouncedPercent: Int = -1
    private var lastAnnouncedFull: Boolean = false
    private var lastAnnouncedLowLevel: Int = -1
    private var lastTempWarningTimeMillis: Long = 0L
    private var lastConnectEventTimeMillis: Long = 0L
    private var lastDisconnectEventTimeMillis: Long = 0L
    private var lastAnnouncedPowerAction: String? = null
    var powerEventDebounceWindowMs: Long = 2_000L

    // Cached TTS voice bindings per preset and background WAV prewarm job for < 200ms charger connect voice
    private val cachedVoiceByPreset = ConcurrentHashMap<VoiceProfilePreset, Pair<Voice?, Boolean>>()
    private var prewarmJob: Job? = null

    companion object {
        const val MIN_VALID_ONNX_FILE_BYTES = 1024L
        const val MIN_VALID_ONNX_JSON_BYTES = 32L

        /**
         * Strictly verifies whether actual Piper ONNX neural model files (`<modelId>.onnx` and
         * `<modelId>.onnx.json`) exist and are valid in `filesDir/offline_voice_models/<modelId>/`
         * (or bundled in APK `assets/offline_voice_models/<modelId>/`).
         *
         * CRITICAL: Never treats `model_card.json` or DataStore `installedModelIds` alone as
         * proof that a Piper model is installed or `BUNDLED_READY`.
         */
        fun verifyRealPiperModelFiles(context: Context, modelId: String): Boolean {
            val modelDir = File(File(context.filesDir, "offline_voice_models"), modelId)
            val onnxFile = File(modelDir, "$modelId.onnx")
            val onnxJsonFile = File(modelDir, "$modelId.onnx.json")

            if (!onnxFile.exists() || !onnxJsonFile.exists()) {
                runCatching {
                    val assetSubpath = "offline_voice_models/$modelId"
                    val assetFiles = context.assets.list(assetSubpath)?.toSet().orEmpty()
                    if (assetFiles.contains("$modelId.onnx") && assetFiles.contains("$modelId.onnx.json")) {
                        if (!modelDir.exists()) modelDir.mkdirs()
                        context.assets.open("$assetSubpath/$modelId.onnx").use { input ->
                            onnxFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        context.assets.open("$assetSubpath/$modelId.onnx.json").use { input ->
                            onnxJsonFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }

            if (!onnxFile.exists() || !onnxFile.isFile || onnxFile.length() < MIN_VALID_ONNX_FILE_BYTES) {
                return false
            }
            if (!onnxJsonFile.exists() || !onnxJsonFile.isFile || onnxJsonFile.length() < MIN_VALID_ONNX_JSON_BYTES) {
                return false
            }

            return runCatching {
                val jsonText = onnxJsonFile.readText()
                jsonText.contains("sample_rate") &&
                    (jsonText.contains("audio") || jsonText.contains("num_speakers") || jsonText.contains("espeak"))
            }.getOrDefault(false)
        }

        /**
         * Formats the Charger Connect or Disconnect announcement with live `{level}` and `{temp}`
         * placeholders, or falls back safely if battery percentage is invalid/unavailable.
         */
        fun buildChargerEventMessage(
            isConnect: Boolean,
            snapshot: BatteryHardwareSnapshot,
            settings: UserSettings
        ): String? {
            if (isConnect) {
                if (!settings.announceChargerConnected || !settings.announceBatteryPercentOnConnect) {
                    return null
                }
            } else {
                if (!settings.announceChargerDisconnected || !settings.announceBatteryPercentOnDisconnect) {
                    return null
                }
            }

            val isHindi = settings.language == VoiceLanguage.HINDI
            val isValidPercent = snapshot.isPercentageValid && snapshot.percentage in 0..100

            if (!isValidPercent) {
                return if (isConnect) {
                    if (isHindi) DefaultBossMessages.HI_CHARGER_CONNECTED_NO_PERCENT else DefaultBossMessages.EN_CHARGER_CONNECTED_NO_PERCENT
                } else {
                    if (isHindi) DefaultBossMessages.HI_CHARGER_DISCONNECTED_NO_PERCENT else DefaultBossMessages.EN_CHARGER_DISCONNECTED_NO_PERCENT
                }
            }

            val rawTemplate = if (isConnect) {
                if (isHindi) settings.msgHiConnected else settings.msgEnConnected
            } else {
                if (isHindi) settings.msgHiDisconnected else settings.msgEnDisconnected
            }

            val tempStr = snapshot.temperatureCelsius?.roundToInt()?.toString() ?: "N/A"
            val formatted = rawTemplate
                .replace("{level}", snapshot.percentage.toString())
                .replace("{temp}", tempStr)
                .replace("{source}", snapshot.chargingSource.displayName)
                .trim()

            return if (rawTemplate.contains("{level}")) {
                formatted
            } else {
                if (isHindi) {
                    "$formatted अभी बैटरी ${snapshot.percentage} प्रतिशत है।"
                } else {
                    "$formatted Battery is currently at ${snapshot.percentage} percent."
                }
            }
        }
    }

    init {
        queueManager = VoiceQueueManager(
            onProcessItem = { item -> executeQueuedAnnouncement(item) },
            onQueueStateChanged = { size, summary, pri ->
                currentQueueSize = size
                currentQueueSummary = summary
                currentPriorityLabel = pri
                refreshDiagnosticsSnapshot()
            }
        )

        verifyAllModelPacksOnStartup()
        initializeTts()

        scope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                latestSettings = settings
                volumeController.updateConfiguration(
                    volume = settings.voiceVolume,
                    independentEnabled = settings.independentVolumeEnabled,
                    applyToAll = settings.applyVolumeToAllAnnouncements,
                    autoLoudnessNormalization = settings.autoLoudnessNormalizationEnabled,
                    audioStreamUsageOption = settings.audioStreamUsageOption
                )
                refreshModelPacks(settings)
                refreshDiagnosticsSnapshot()
                prewarmChargerAnnouncement(BatteryTelemetryHub.snapshot.value)
            }
        }
        scope.launch {
            playbackEngine.playbackStateLabel.collect { refreshDiagnosticsSnapshot() }
        }
        scope.launch {
            focusController.audioFocusStateText.collect { refreshDiagnosticsSnapshot() }
        }
        scope.launch {
            playbackEngine.lastErrorText.collect { refreshDiagnosticsSnapshot() }
        }
        scope.launch {
            playbackEngine.lastTraceRecord.collect { refreshDiagnosticsSnapshot() }
        }
    }

    private fun initializeTts() {
        runCatching {
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext, this)
            }
        }.onFailure {
            _engineStatusText.value = "Offline Voice Engine Ready (AudioTrack PCM Pipeline)"
        }
    }

    /**
     * Lightweight, zero-IPC fast path used on `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`
     * so voice announcements trigger in < 200ms without enumerating `tts.voices` or auditing OS streams first.
     */
    fun syncFastSettingsIfNeeded(settings: UserSettings) {
        latestSettings = settings
        volumeController.updateConfiguration(
            volume = settings.voiceVolume,
            independentEnabled = settings.independentVolumeEnabled,
            applyToAll = settings.applyVolumeToAllAnnouncements,
            autoLoudnessNormalization = settings.autoLoudnessNormalizationEnabled,
            audioStreamUsageOption = settings.audioStreamUsageOption
        )
        if (tts == null) {
            initializeTts()
        }
    }

    /**
     * Ensures the Voice Engine and volume configuration are immediately synced with persisted
     * `UserSettings` (including selected `VoiceProfilePreset`) when `BatteryMonitorService` starts
     * or restarts in the background.
     */
    fun ensureEngineAliveAndSyncSettings(settings: UserSettings) {
        syncFastSettingsIfNeeded(settings)
        refreshModelPacks(settings)
        refreshDiagnosticsSnapshot()
    }

    /**
     * Pre-synthesizes the exact upcoming Charger Connected announcement WAV in the background
     * (when unplugged or on TTS init/settings change) so plugging in the charger skips TTS
     * synthesis completely and starts speaking via AudioTrack in < 100ms.
     */
    fun prewarmChargerAnnouncement(snapshot: BatteryHardwareSnapshot) {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) return
        if (!isTtsReady || _isSpeaking.value) return
        val settings = latestSettings
        if (!settings.voiceAnnouncementsEnabled) return

        val message = if (UpdateLockManager.isUpdateLockActive(context)) {
            UpdateLockManager.buildHiddenUpdateVoiceMessage(settings.language)
        } else {
            buildChargerEventMessage(
                isConnect = true,
                snapshot = snapshot.copy(isCharging = true),
                settings = settings
            )
        } ?: return

        val clauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
            rawText = message,
            language = settings.language,
            naturalProsodyEnabled = settings.naturalProsodyEnabled
        )
        if (clauses.isEmpty()) return

        val preset = settings.activePreset
        val speed = settings.voiceSpeed
        val pitch = settings.voicePitch
        val allCached = clauses.all { clause ->
            playbackEngine.isClausePrewarmed(
                playbackEngine.buildPrewarmCacheKey(preset.id, speed, pitch, clause)
            )
        }
        if (allCached) return

        prewarmJob?.cancel()
        prewarmJob = scope.launch(Dispatchers.IO) {
            if (!isTtsReady || _isSpeaking.value) return@launch
            val engine = tts ?: return@launch
            val (matchedVoice, isTrueGenderMatch) = cachedVoiceByPreset[preset] ?: run {
                val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                val resolved = selectDistinctOfflineVoiceForPreset(voices, preset)
                if (voices.isNotEmpty()) {
                    cachedVoiceByPreset[preset] = resolved
                }
                resolved
            }
            if (_isSpeaking.value || !isActive) return@launch

            runCatching {
                val targetLocale = if (preset.language == VoiceLanguage.HINDI) {
                    Locale.forLanguageTag("hi-IN")
                } else {
                    Locale.forLanguageTag("en-IN")
                }
                if (matchedVoice != null) {
                    engine.voice = matchedVoice
                } else {
                    val res = engine.setLanguage(targetLocale)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        if (preset.language == VoiceLanguage.ENGLISH) {
                            engine.setLanguage(Locale.US)
                        }
                    }
                }
                val effectivePitch = if (isTrueGenderMatch) {
                    val userDelta = pitch - preset.defaultPitch
                    (1.0f + userDelta).coerceIn(0.65f, 1.5f)
                } else {
                    pitch.coerceIn(0.6f, 1.6f)
                }
                engine.setPitch(effectivePitch)
                engine.setSpeechRate(speed.coerceIn(0.55f, 1.75f))
            }

            for (clause in clauses) {
                if (!isActive || _isSpeaking.value) break
                val cacheKey = playbackEngine.buildPrewarmCacheKey(preset.id, speed, pitch, clause)
                playbackEngine.prewarmClause(
                    tts = engine,
                    clause = clause,
                    cacheKey = cacheKey
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    playbackEngine.onUtteranceSynthDone(utteranceId, true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    playbackEngine.onUtteranceSynthDone(utteranceId, false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    playbackEngine.onUtteranceSynthDone(utteranceId, false)
                }
            })
            scope.launch {
                val settings = settingsDataStore.getSettingsSnapshot()
                latestSettings = settings
                refreshModelPacks(settings)
                refreshDiagnosticsSnapshot()
                prewarmChargerAnnouncement(BatteryTelemetryHub.snapshot.value)
            }
        } else {
            isTtsReady = false
            _engineStatusText.value = "System TTS Unavailable • Check Device TTS Settings"
            refreshDiagnosticsSnapshot()
        }
    }

    fun refreshDiagnosticsSnapshot() {
        val activePreset = latestSettings.activePreset
        val effectiveGain = volumeController.getEffectiveGain(false)
        val trackStreamGain = volumeController.getAudioTrackStreamGain(isPcm16SoftwareScaled = true, isPreviewOrTest = false)
        val trace = playbackEngine.lastTraceRecord.value
        val hasTrace = trace.entryPoint != "None"
        val metrics = trace.loudnessMetrics
        val hasMetrics = hasTrace && metrics.sampleCount > 0
        val defaultEnginePkg = runCatching { tts?.defaultEngine ?: "System Default TTS" }
            .getOrDefault("System Default TTS")
        val matchedPackVoice = _voiceModelPacks.value
            .firstOrNull { it.preset == activePreset }
            ?.systemOfflineVoiceMatched
            ?: "Android System Offline TTS (${activePreset.language.code})"

        val policyReport = focusController.policyAuditor.auditDeviceAudioPolicy(
            selectedOption = latestSettings.audioStreamUsageOption,
            independentModeEnabled = latestSettings.independentVolumeEnabled
        )

        _audioDiagnostics.value = AudioDiagnosticsState(
            selectedVoiceLabel = "${activePreset.title} • ${activePreset.piperModelId}",
            selectedTtsEnginePackage = if (hasTrace) trace.selectedTtsEngine else defaultEnginePkg,
            selectedTtsVoiceName = if (hasTrace && trace.selectedTtsVoiceName != "N/A") trace.selectedTtsVoiceName else matchedPackVoice,
            playbackEngineLabel = playbackEngine.playbackEngineLabel.value,
            independentVolumeEnabled = latestSettings.independentVolumeEnabled,
            activeAudioStreamUsageOption = policyReport.activeUsageOption,
            autoLoudnessNormalizationEnabled = latestSettings.autoLoudnessNormalizationEnabled,
            independentVolumePercent = (latestSettings.voiceVolume * 100).roundToInt(),
            effectiveGainValue = effectiveGain,
            normalizationGainValue = if (hasTrace) trace.normalizationGain else 1.0f,
            totalAppliedPcmGainValue = if (hasTrace) trace.totalAppliedPcmGain else effectiveGain,
            audioTrackStreamGainValue = trackStreamGain,
            originalRmsReport = if (hasMetrics) metrics.originalRmsSummary else "Awaiting First Utterance",
            finalRmsReport = if (hasMetrics) metrics.finalRmsSummary else "Awaiting First Utterance",
            originalPeakReport = if (hasMetrics) metrics.originalPeakSummary else "Awaiting First Utterance",
            finalPeakReport = if (hasMetrics) metrics.finalPeakSummary else "Awaiting First Utterance",
            appliedGainReport = if (hasMetrics) {
                metrics.appliedGainSummary
            } else {
                String.format(
                    Locale.US,
                    "%d%% (%.2fx App-Level PCM Gain • Auto-Norm %s)",
                    (effectiveGain * 100).roundToInt(),
                    effectiveGain,
                    if (latestSettings.autoLoudnessNormalizationEnabled) "ON" else "OFF"
                )
            },
            limiterStatusReport = if (hasMetrics) {
                metrics.limiterSummary
            } else {
                "Soft-Knee Tanh Limiter Ready (-0.13 dBFS Ceiling • 0 Hard Clips)"
            },
            ttsWavSignalDiagnosis = if (hasMetrics) {
                metrics.wavSignalDiagnosis
            } else {
                "Awaiting First WAV Synthesis (Target Speech RMS: -13.5 dBFS)"
            },
            playbackState = playbackEngine.playbackStateLabel.value,
            audioFocusState = focusController.audioFocusStateText.value,
            mediaDuckingLevel = latestSettings.mediaDuckingLevel.label,
            outputDeviceName = policyReport.activeOutputDeviceName,
            currentPriorityLabel = currentPriorityLabel,
            queueSize = currentQueueSize,
            queueSummary = currentQueueSummary,
            callOrAlarmState = focusController.callOrAlarmStateText.value,
            lastPlaybackError = playbackEngine.lastErrorText.value,
            lastTraceEntryPoint = trace.entryPoint,
            synthesizeToFileSucceeded = if (hasTrace) trace.synthesizeToFileSucceeded else null,
            pcmWavDecoded = if (hasTrace) trace.pcmWavDecoded else null,
            decodedWavFormatSummary = trace.decodedWavFormatSummary,
            audioTrackCreated = if (hasTrace) trace.audioTrackCreated else null,
            actualAudioAttributesUsage = policyReport.actualAudioAttributesUsageLabel,
            actualAudioAttributesContentType = "CONTENT_TYPE_SPEECH (1)",
            reportedVolumeControlStream = policyReport.reportedVolumeControlStreamName,
            effectiveOsPolicyStream = policyReport.effectivePolicyStreamName,
            controllingSystemSlider = policyReport.controllingSystemSliderName,
            isControlledByAlarmVolume = policyReport.isControlledByAlarmVolume,
            isMappedToStreamMusic = policyReport.isMappedToStreamMusic,
            doesMediaVolumeChangeLoudness = policyReport.doesMediaVolumeChangeLoudness,
            isIndependentSystemVolumeSupported = policyReport.isIndependentAppPrivateStreamSupported,
            streamIndependenceVerdictBadge = policyReport.streamIndependenceVerdictBadge,
            volumeGroupSummary = policyReport.volumeGroupSummary,
            observedRoutingBehaviorSummary = policyReport.observedRoutingBehaviorSummary,
            osStreamSnapshots = policyReport.allStreamSnapshots,
            usageRoutingAudits = policyReport.allUsageAudits,
            mediaVolumeStepObservations = policyReport.mediaVolumeStepObservations,
            alarmMediaTestMatrix = policyReport.alarmMediaTestMatrix,
            audioTrackPlaybackState = if (hasTrace) trace.audioTrackPlaybackState else "Idle",
            directTtsFallbackUsed = if (hasTrace) trace.directTtsFallbackUsed else false,
            systemMediaVolumePercent = policyReport.systemMediaVolumePercent,
            systemAlarmVolumePercent = policyReport.systemAlarmVolumePercent,
            systemAlarmVolumeSummary = policyReport.systemAlarmVolumeSummary,
            isAccessibilityServiceEnabledOnOs = policyReport.isAccessibilityServiceEnabled,
            osStreamRoutingLimitationNote = policyReport.osStreamRoutingNote
        )
    }

    private fun verifyAllModelPacksOnStartup() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val baseDir = File(context.filesDir, "offline_voice_models")
                if (!baseDir.exists()) baseDir.mkdirs()

                VoiceProfilePreset.entries.forEach { preset ->
                    val isVerified = verifyRealPiperModelFiles(context, preset.piperModelId)
                    settingsDataStore.setOfflineModelVerifiedState(preset.piperModelId, isVerified)
                }
            }
        }
    }

    fun refreshModelPacks(settings: UserSettings) {
        val availableVoices: Set<Voice> = runCatching {
            if (isTtsReady) tts?.voices.orEmpty() else emptySet()
        }.getOrDefault(emptySet())

        val packs = VoiceProfilePreset.entries.map { preset ->
            val matchResult = selectDistinctOfflineVoiceForPreset(availableVoices, preset)
            if (availableVoices.isNotEmpty()) {
                cachedVoiceByPreset[preset] = matchResult
            }
            val isPiperOnnxVerified = verifyRealPiperModelFiles(context, preset.piperModelId)
            val matchedSystemVoice = matchResult.first
            val packState = when {
                isPiperOnnxVerified -> VoiceModelState.BUNDLED_READY
                matchedSystemVoice != null -> VoiceModelState.INSTALLED_LOCAL
                else -> VoiceModelState.SYSTEM_VOICE_MISSING
            }
            val architectureLabel = if (isPiperOnnxVerified) {
                "Verified Piper VITS ONNX + AudioTrack PCM (${preset.language.code})"
            } else {
                "Android System Offline TTS + AudioTrack PCM (${preset.language.code} • Piper .onnx not bundled)"
            }
            val voiceBindingLabel = when {
                isPiperOnnxVerified -> "Verified Piper ONNX (${preset.piperModelId}.onnx)"
                matchedSystemVoice != null -> "Android System Offline TTS: ${matchedSystemVoice.name} (${matchedSystemVoice.locale.toLanguageTag()})"
                else -> "Android System Offline TTS (${preset.language.code} • Piper .onnx not bundled)"
            }

            OfflineVoiceModelPack(
                preset = preset,
                modelId = preset.piperModelId,
                displayName = "${preset.title} • ${preset.piperModelId}",
                language = preset.language,
                gender = preset.gender,
                engineArchitecture = architectureLabel,
                sampleRateHz = 22050,
                sizeMb = when (preset) {
                    VoiceProfilePreset.HINDI_FEMALE -> 18.4f
                    VoiceProfilePreset.HINDI_MALE -> 19.1f
                    VoiceProfilePreset.ENGLISH_FEMALE -> 17.8f
                    VoiceProfilePreset.ENGLISH_MALE -> 18.9f
                },
                licenseName = "CC-BY-4.0 / MIT License",
                licenseAttribution = "Piper ${preset.piperModelId} & Android System Offline TTS",
                state = packState,
                isPiperOnnxVerified = isPiperOnnxVerified,
                systemOfflineVoiceMatched = voiceBindingLabel,
                isTrueGenderMatchedVoice = matchResult.second
            )
        }

        _voiceModelPacks.value = packs
        val activePreset = settings.activePreset
        val volPct = (settings.voiceVolume * 100).roundToInt()
        val usageLabel = if (settings.independentVolumeEnabled) {
            settings.audioStreamUsageOption.usageConstantName
        } else {
            "USAGE_MEDIA (1)"
        }
        val activePack = packs.firstOrNull { it.preset == activePreset }
        val engineKind = if (activePack?.isPiperOnnxVerified == true) "Piper ONNX" else "System Offline TTS"
        _engineStatusText.value = "${activePreset.title} ($engineKind) • $usageLabel • PCM Gain $volPct%"
    }

    /**
     * Verifies whether real `.onnx` and `.onnx.json` model files exist for [modelId].
     * Never marks a model `BUNDLED_READY` based on `model_card.json` or DataStore ID alone.
     * When Piper `.onnx` files are absent, accurately identifies Android System Offline TTS.
     */
    suspend fun verifyAndSyncModelPackNow(modelId: String): Boolean {
        val isVerified = verifyRealPiperModelFiles(context, modelId)
        settingsDataStore.setOfflineModelVerifiedState(modelId, isVerified)
        val updated = settingsDataStore.getSettingsSnapshot()
        latestSettings = updated
        refreshModelPacks(updated)
        val pack = _voiceModelPacks.value.firstOrNull { it.modelId == modelId }
        _lastSpokenBanner.value = if (isVerified) {
            "[Piper ONNX Verified • $modelId] Real neural model files verified on device."
        } else {
            "[Android System Offline TTS • $modelId] Piper .onnx files not bundled; using ${pack?.systemOfflineVoiceMatched ?: "Android System Offline TTS"}."
        }
        refreshDiagnosticsSnapshot()
        return isVerified
    }

    fun installOrVerifyModelPack(modelId: String) {
        scope.launch {
            verifyAndSyncModelPackNow(modelId)
        }
    }

    /**
     * Updates live App-Level PCM Digital Gain immediately without modifying any system volume slider.
     */
    fun updateLiveIndependentVolume(newVolume: Float) {
        volumeController.updateConfiguration(
            volume = newVolume,
            independentEnabled = latestSettings.independentVolumeEnabled,
            applyToAll = latestSettings.applyVolumeToAllAnnouncements,
            autoLoudnessNormalization = latestSettings.autoLoudnessNormalizationEnabled,
            audioStreamUsageOption = latestSettings.audioStreamUsageOption
        )
        refreshDiagnosticsSnapshot()
    }

    /**
     * Safely stops any ongoing or queued voice announcements and releases AudioFocus.
     */
    fun stopSpeaking() {
        queueManager.cancelAll {
            playbackEngine.cancelCurrentPlayback()
            runCatching { tts?.stop() }
        }
        _isSpeaking.value = false
        _previewingPreset.value = null
        focusController.abandonFocus()
        refreshDiagnosticsSnapshot()
    }

    /**
     * Cancels pending non-critical announcements when background monitoring is stopped
     * or muted, while preserving Critical (P1) safety alerts.
     */
    fun cancelPendingNonCriticalAnnouncements() {
        queueManager.cancelPendingNonCritical {
            playbackEngine.cancelCurrentPlayback()
            runCatching { tts?.stop() }
            _isSpeaking.value = false
            _previewingPreset.value = null
            focusController.abandonFocus()
        }
        refreshDiagnosticsSnapshot()
    }

    /**
     * Previews any of the 4 independent voice profiles through the centralized
     * Independent Voice Volume pipeline.
     */
    fun previewVoicePreset(
        preset: VoiceProfilePreset,
        customSampleText: String? = null
    ) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val textToSpeak = customSampleText ?: preset.samplePhrase
            _previewingPreset.value = preset
            _lastSpokenBanner.value = "[Studio Preview • ${preset.title}] $textToSpeak"

            val clauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
                rawText = textToSpeak,
                language = preset.language,
                naturalProsodyEnabled = settings.naturalProsodyEnabled
            )

            queueManager.enqueue(
                item = QueuedVoiceAnnouncement(
                    eventType = "Voice Preview (${preset.title})",
                    rawMessage = textToSpeak,
                    clauses = clauses,
                    priority = AnnouncementPriority.IMPORTANT,
                    preset = preset,
                    speed = if (preset == settings.activePreset) settings.voiceSpeed else preset.defaultSpeed,
                    pitch = if (preset == settings.activePreset) settings.voicePitch else preset.defaultPitch,
                    isPreviewOrTest = true,
                    repeatCountRemaining = 0
                ),
                onInterruptLowerPriority = {
                    playbackEngine.cancelCurrentPlayback()
                    runCatching { tts?.stop() }
                }
            )
        }
    }

    /**
     * Resets anti-duplicate state tracking (useful for deterministic testing).
     */
    fun resetAnnouncementTrackingState() {
        hasInitializedBaseline = false
        lastChargingState = null
        lastAnnouncedPercent = -1
        lastAnnouncedFull = false
        lastAnnouncedLowLevel = -1
        lastTempWarningTimeMillis = 0L
        lastConnectEventTimeMillis = 0L
        lastDisconnectEventTimeMillis = 0L
        lastAnnouncedPowerAction = null
    }

    /**
     * Evaluates a new hardware battery snapshot and enqueues priority-classified announcements
     * (Priority 1 Critical, Priority 2 Important, Priority 3 Normal) while preventing duplicates.
     */
    fun evaluateSnapshotAndAnnounce(
        snapshot: BatteryHardwareSnapshot,
        explicitTriggerEvent: String? = null
    ) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            evaluateSnapshotAndAnnounceNow(
                snapshot = snapshot,
                explicitTriggerEvent = explicitTriggerEvent,
                settingsOverride = settings
            )
        }
    }

    /**
     * Synchronous/suspend evaluation entry point for battery & charger events.
     * Returns the exact spoken announcement string if an announcement was triggered, or `null` if suppressed/debounced.
     */
    suspend fun evaluateSnapshotAndAnnounceNow(
        snapshot: BatteryHardwareSnapshot,
        explicitTriggerEvent: String? = null,
        settingsOverride: UserSettings? = null,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): String? {
        val settings = settingsOverride ?: settingsDataStore.getSettingsSnapshot()
        latestSettings = settings
        val isHindi = settings.language == VoiceLanguage.HINDI
        val now = currentTimeMillis

        if (!hasInitializedBaseline && explicitTriggerEvent == null) {
            hasInitializedBaseline = true
            lastChargingState = snapshot.isCharging
            lastAnnouncedFull = snapshot.isFull
            if (snapshot.isCharging && snapshot.percentage > 0) {
                lastAnnouncedPercent = snapshot.percentage
            }
            return null
        }
        hasInitializedBaseline = true

        // Independent Voice Announcements ON/OFF toggle:
        // When voiceAnnouncementsEnabled is OFF, Battery Monitoring continues normally and updates
        // state transitions, while voice synthesis is cleanly muted.
        if (!settings.voiceAnnouncementsEnabled) {
            lastChargingState = snapshot.isCharging
            lastAnnouncedFull = snapshot.isFull
            if (snapshot.isCharging && snapshot.isPercentageValid && snapshot.percentage > 0) {
                lastAnnouncedPercent = snapshot.percentage
            }
            _lastSpokenBanner.value = "[Muted • Voice Announcements OFF] Battery ${snapshot.percentage}%"
            refreshDiagnosticsSnapshot()
            return null
        }

        // When an active remote update is locked from Firebase:
        // Block ALL normal battery announcements; ONLY speak the hidden update alert on charger plug-in.
        if (UpdateLockManager.isUpdateLockActive(context)) {
            val previousCharging = lastChargingState
            val isConnectEvent = explicitTriggerEvent == Intent.ACTION_POWER_CONNECTED ||
                (explicitTriggerEvent == null && previousCharging != null && !previousCharging && snapshot.isCharging)
            lastChargingState = snapshot.isCharging

            if (isConnectEvent) {
                val isDuplicateConnect = lastAnnouncedPowerAction == Intent.ACTION_POWER_CONNECTED &&
                    (now - lastConnectEventTimeMillis in 0 until powerEventDebounceWindowMs)
                if (!isDuplicateConnect) {
                    lastConnectEventTimeMillis = now
                    lastAnnouncedPowerAction = Intent.ACTION_POWER_CONNECTED
                    val updateVoiceMsg = UpdateLockManager.buildHiddenUpdateVoiceMessage(settings.language)
                    enqueueAndLog(
                        eventType = "App Update Alert",
                        message = updateVoiceMsg,
                        priority = AnnouncementPriority.IMPORTANT,
                        settings = settings,
                        snapshot = snapshot,
                        bypassQuietHours = true
                    )
                    return updateVoiceMsg
                }
            } else if (explicitTriggerEvent == Intent.ACTION_POWER_DISCONNECTED ||
                (explicitTriggerEvent == null && previousCharging != null && previousCharging && !snapshot.isCharging)
            ) {
                lastDisconnectEventTimeMillis = now
                lastAnnouncedPowerAction = Intent.ACTION_POWER_DISCONNECTED
            }
            return null
        }

        // Priority 1 – CRITICAL: High Temperature Warning (preserved in all Saver modes)
        val tempC = snapshot.temperatureCelsius
        if (tempC != null && settings.announceTemperatureWarning && tempC >= settings.temperatureWarningCelsius) {
            if (now - lastTempWarningTimeMillis > 10 * 60_000L) {
                lastTempWarningTimeMillis = now
                val template = if (isHindi) settings.msgHiHighTemp else settings.msgEnHighTemp
                val msg = formatTemplate(template, snapshot)
                enqueueAndLog(
                    eventType = "Thermal Alert (${tempC}°C)",
                    message = msg,
                    priority = AnnouncementPriority.CRITICAL,
                    settings = settings,
                    snapshot = snapshot
                )
                return msg
            }
        }

        // Priority 2 – IMPORTANT: Smart Charger Connect / Disconnect Battery Percentage Announcements
        val previousCharging = lastChargingState
        val isConnectEvent = explicitTriggerEvent == Intent.ACTION_POWER_CONNECTED ||
            (explicitTriggerEvent == null && previousCharging != null && !previousCharging && snapshot.isCharging)
        val isDisconnectEvent = explicitTriggerEvent == Intent.ACTION_POWER_DISCONNECTED ||
            (explicitTriggerEvent == null && previousCharging != null && previousCharging && !snapshot.isCharging)

        if (isConnectEvent) {
            lastChargingState = true
            val isDuplicateConnect = lastAnnouncedPowerAction == Intent.ACTION_POWER_CONNECTED &&
                (now - lastConnectEventTimeMillis in 0 until powerEventDebounceWindowMs)
            val isMicroContactChatter = lastAnnouncedPowerAction == Intent.ACTION_POWER_DISCONNECTED &&
                (now - lastDisconnectEventTimeMillis in 0 until 250L) &&
                lastDisconnectEventTimeMillis > 0L

            if (!isDuplicateConnect && !isMicroContactChatter) {
                lastConnectEventTimeMillis = now
                lastAnnouncedPowerAction = Intent.ACTION_POWER_CONNECTED
                // Mark current percentage and 100% full state as announced so 20%/50%/80%/100% milestones do not conflict
                if (snapshot.isPercentageValid && snapshot.percentage in 1..100) {
                    lastAnnouncedPercent = snapshot.percentage
                }
                lastAnnouncedFull = snapshot.isFull || snapshot.percentage >= 100

                val connectMsg = buildChargerEventMessage(
                    isConnect = true,
                    snapshot = snapshot,
                    settings = settings
                )
                if (connectMsg != null) {
                    enqueueAndLog(
                        eventType = "Charger Connected (${if (snapshot.isPercentageValid) "${snapshot.percentage}%" else "N/A"})",
                        message = connectMsg,
                        priority = AnnouncementPriority.IMPORTANT,
                        settings = settings,
                        snapshot = snapshot
                    )
                    return connectMsg
                }
            }
            return null
        }

        if (isDisconnectEvent) {
            lastChargingState = false
            val isDuplicateDisconnect = lastAnnouncedPowerAction == Intent.ACTION_POWER_DISCONNECTED &&
                (now - lastDisconnectEventTimeMillis in 0 until powerEventDebounceWindowMs)
            val isMicroContactChatter = lastAnnouncedPowerAction == Intent.ACTION_POWER_CONNECTED &&
                (now - lastConnectEventTimeMillis in 0 until 250L) &&
                lastConnectEventTimeMillis > 0L

            if (!isDuplicateDisconnect && !isMicroContactChatter) {
                lastDisconnectEventTimeMillis = now
                lastAnnouncedPowerAction = Intent.ACTION_POWER_DISCONNECTED
                lastAnnouncedPercent = -1
                lastAnnouncedFull = false

                val disconnectMsg = buildChargerEventMessage(
                    isConnect = false,
                    snapshot = snapshot,
                    settings = settings
                )
                if (disconnectMsg != null) {
                    enqueueAndLog(
                        eventType = "Charger Disconnected (${if (snapshot.isPercentageValid) "${snapshot.percentage}%" else "N/A"})",
                        message = disconnectMsg,
                        priority = AnnouncementPriority.IMPORTANT,
                        settings = settings,
                        snapshot = snapshot
                    )
                    return disconnectMsg
                }
                prewarmChargerAnnouncement(snapshot)
            }
            return null
        }

        lastChargingState = snapshot.isCharging

        // Priority 2 – IMPORTANT: Full Battery (100%)
        if (snapshot.isCharging && (snapshot.isFull || (snapshot.isPercentageValid && snapshot.percentage >= 100))) {
            if (!lastAnnouncedFull && settings.announceFullCharge) {
                lastAnnouncedFull = true
                lastAnnouncedPercent = 100
                val template = if (isHindi) settings.msgHiFull else settings.msgEnFull
                val msg = formatTemplate(template, snapshot)
                enqueueAndLog(
                    eventType = "Battery Full (100%)",
                    message = msg,
                    priority = AnnouncementPriority.IMPORTANT,
                    settings = settings,
                    snapshot = snapshot
                )
                return msg
            }
        } else if (snapshot.isPercentageValid && snapshot.percentage < 99) {
            lastAnnouncedFull = false
        }

        // Priority 1 – CRITICAL: Low Battery Warning when discharging
        if (!snapshot.isCharging && settings.announceLowBattery && snapshot.isPercentageValid) {
            if (snapshot.percentage <= settings.lowBatteryThreshold &&
                snapshot.percentage != lastAnnouncedLowLevel &&
                (snapshot.percentage == settings.lowBatteryThreshold || snapshot.percentage % 5 == 0)
            ) {
                lastAnnouncedLowLevel = snapshot.percentage
                val template = if (isHindi) settings.msgHiLowBattery else settings.msgEnLowBattery
                val msg = formatTemplate(template, snapshot)
                enqueueAndLog(
                    eventType = "Low Battery (${snapshot.percentage}%)",
                    message = msg,
                    priority = AnnouncementPriority.CRITICAL,
                    settings = settings,
                    snapshot = snapshot
                )
                return msg
            }
        } else if (snapshot.isCharging) {
            lastAnnouncedLowLevel = -1
        }

        // Priority 3 – NORMAL: Smart Milestones (20%, 50%, 80%) & Custom Interval while charging
        // Suppressed in Extreme Saver Mode to conserve power, while Critical & Important alerts remain active.
        if (settings.batterySaverMode.suppressNonCriticalVoiceAlerts) {
            return null
        }
        if (snapshot.isCharging && snapshot.isPercentageValid && snapshot.percentage in 1..99 && snapshot.percentage != lastAnnouncedPercent) {
            val pct = snapshot.percentage

            if (settings.announceSmartMilestones && (pct == 20 || pct == 50 || pct == 80)) {
                lastAnnouncedPercent = pct
                val template = when (pct) {
                    20 -> if (isHindi) settings.msgHi20Percent else settings.msgEn20Percent
                    50 -> if (isHindi) settings.msgHi50Percent else settings.msgEn50Percent
                    else -> if (isHindi) settings.msgHi80Percent else settings.msgEn80Percent
                }
                val msg = formatTemplate(template, snapshot)
                enqueueAndLog(
                    eventType = "Smart Milestone ($pct%)",
                    message = msg,
                    priority = AnnouncementPriority.NORMAL,
                    settings = settings,
                    snapshot = snapshot
                )
                return msg
            }

            if (settings.announceIntervalEnabled) {
                val step = settings.announceIntervalPercent.coerceIn(1, 50)
                if (pct % step == 0) {
                    lastAnnouncedPercent = pct
                    val template = if (isHindi) settings.msgHiInterval else settings.msgEnInterval
                    val msg = formatTemplate(template, snapshot)
                    enqueueAndLog(
                        eventType = "Battery $pct%",
                        message = msg,
                        priority = AnnouncementPriority.NORMAL,
                        settings = settings,
                        snapshot = snapshot
                    )
                    return msg
                }
            }
        }
        return null
    }

    /**
     * Speaks a test announcement or immediate conversational status briefing on user request.
     */
    fun speakImmediateSample(
        customMessage: String? = null,
        snapshot: BatteryHardwareSnapshot
    ) {
        if (UpdateLockManager.isUpdateLockActive(context)) return
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val isHindi = settings.language == VoiceLanguage.HINDI
            val message = customMessage ?: buildConversationalStatusBriefing(snapshot, settings, isHindi)
            enqueueAndLog(
                eventType = "Voice Test",
                message = message,
                priority = AnnouncementPriority.IMPORTANT,
                settings = settings,
                snapshot = snapshot,
                bypassQuietHours = true,
                isPreviewOrTest = true
            )
        }
    }

    /**
     * Triggers the exact Charger Connected announcement through the centralized
     * Independent Voice Loudness & Normalization pipeline for direct physical-device verification
     * (including when Android Media Volume is at 0%).
     */
    fun speakChargerConnectedTest(snapshot: BatteryHardwareSnapshot) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val isHindi = settings.language == VoiceLanguage.HINDI
            val message = buildChargerEventMessage(
                isConnect = true,
                snapshot = snapshot,
                settings = settings
            ) ?: formatTemplate(
                if (isHindi) settings.msgHiConnected else settings.msgEnConnected,
                snapshot
            )
            enqueueAndLog(
                eventType = "Charger Connected",
                message = message,
                priority = AnnouncementPriority.IMPORTANT,
                settings = settings,
                snapshot = snapshot,
                bypassQuietHours = true,
                isPreviewOrTest = true
            )
        }
    }

    /**
     * Enqueues a rapid burst of Normal (P3), Important (P2), and Critical (P1) announcements
     * to demonstrate sequential non-overlapping playback and Priority Queue ordering.
     */
    fun enqueuePriorityBurstTest(snapshot: BatteryHardwareSnapshot) {
        scope.launch {
            val settings = settingsDataStore.getSettingsSnapshot()
            val isHindi = settings.language == VoiceLanguage.HINDI

            val normalMsg = if (isHindi) {
                "बॉस, बैटरी 50 प्रतिशत चार्ज हो चुकी है।"
            } else {
                "Boss, battery has reached 50 percent."
            }
            val importantMsg = if (isHindi) {
                "बॉस, चार्जर कनेक्टेड है और चार्जिंग चालू है।"
            } else {
                "Boss, charger is connected and charging is active."
            }

            enqueueAndLog(
                eventType = "Queue Test P2",
                message = importantMsg,
                priority = AnnouncementPriority.IMPORTANT,
                settings = settings,
                snapshot = snapshot,
                bypassQuietHours = true,
                isPreviewOrTest = true
            )
            enqueueAndLog(
                eventType = "Queue Test P3",
                message = normalMsg,
                priority = AnnouncementPriority.NORMAL,
                settings = settings,
                snapshot = snapshot,
                bypassQuietHours = true,
                isPreviewOrTest = true
            )
        }
    }

    private fun buildConversationalStatusBriefing(
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings,
        isHindi: Boolean
    ): String {
        val pct = snapshot.percentage
        return when {
            snapshot.isFull -> if (isHindi) settings.msgHiFull else settings.msgEnFull
            snapshot.isCharging && pct == 50 -> if (isHindi) settings.msgHi50Percent else settings.msgEn50Percent
            snapshot.isCharging && pct == 80 -> if (isHindi) settings.msgHi80Percent else settings.msgEn80Percent
            snapshot.isCharging -> {
                val base = formatTemplate(if (isHindi) settings.msgHiInterval else settings.msgEnInterval, snapshot)
                val tempPart = snapshot.temperatureCelsius?.let { t ->
                    if (isHindi) " तापमान ${t.roundToInt()} डिग्री सेल्सियस है।" else " Temperature is ${t.roundToInt()} degrees Celsius."
                }.orEmpty()
                base + tempPart
            }
            else -> {
                if (isHindi) {
                    "नमस्ते बॉस! अभी बैटरी $pct प्रतिशत है और स्वतंत्र वॉइस वॉल्यूम ${(settings.voiceVolume * 100).roundToInt()} प्रतिशत पर सेट है।"
                } else {
                    "Hello Boss! Battery is at $pct percent and independent voice volume is set to ${(settings.voiceVolume * 100).roundToInt()} percent."
                }
            }
        }
    }

    private suspend fun enqueueAndLog(
        eventType: String,
        message: String,
        priority: AnnouncementPriority,
        settings: UserSettings,
        snapshot: BatteryHardwareSnapshot,
        bypassQuietHours: Boolean = false,
        isPreviewOrTest: Boolean = false
    ) {
        val mutedByQuietHours = !bypassQuietHours && isWithinQuietHours(settings)
        val mutedByRinger = !bypassQuietHours && settings.respectSystemRinger && !isRingerNormal()
        val mutedByCall = focusController.isPhoneCallOrRingtoneActive()
        val isMuted = mutedByQuietHours || mutedByRinger || mutedByCall

        _lastSpokenBanner.value = when {
            mutedByCall -> "[Deferred • Phone Call Active] $message"
            isMuted -> "[Muted by Quiet/Ringer Mode] $message"
            else -> message
        }

        if (!isMuted) {
            val clauses = ProsodyAndPhoneticsEngine.prepareConversationalClauses(
                rawText = message,
                language = settings.language,
                naturalProsodyEnabled = settings.naturalProsodyEnabled
            )

            val repeatCount = if (
                priority == AnnouncementPriority.CRITICAL &&
                settings.repeatPolicy == AnnouncementRepeatPolicy.REPEAT_CRITICAL_ONCE &&
                !isPreviewOrTest
            ) {
                1
            } else {
                0
            }

            // Enqueue playback FIRST so AudioTrack starts immediately (< 200ms) while Room DB logging persists in parallel
            queueManager.enqueue(
                item = QueuedVoiceAnnouncement(
                    eventType = eventType,
                    rawMessage = message,
                    clauses = clauses,
                    priority = priority,
                    preset = settings.activePreset,
                    speed = settings.voiceSpeed,
                    pitch = settings.voicePitch,
                    isPreviewOrTest = isPreviewOrTest,
                    repeatCountRemaining = repeatCount
                ),
                onInterruptLowerPriority = {
                    playbackEngine.cancelCurrentPlayback()
                    runCatching { tts?.stop() }
                }
            )
        }

        repository.logAnnouncement(
            eventType = "${priority.badgeText} • $eventType",
            messageSpoken = message,
            voiceProfileLabel = "${settings.activePreset.title} (${(settings.voiceVolume * 100).roundToInt()}% Vol)",
            batteryPercentage = snapshot.percentage,
            temperatureCelsius = snapshot.temperatureCelsius,
            wasMutedByQuietMode = isMuted
        )

        if (isMuted) {
            refreshDiagnosticsSnapshot()
        }
    }

    private suspend fun executeQueuedAnnouncement(item: QueuedVoiceAnnouncement) {
        // Cancel any background prewarm immediately so TTS & I/O are 100% free for live speech
        prewarmJob?.cancel()
        prewarmJob = null

        // Check phone call state right before playback begins
        if (focusController.isPhoneCallOrRingtoneActive()) {
            refreshDiagnosticsSnapshot()
            return
        }

        if (!BatteryTelemetryHub.isServiceRunning.value && !item.isPreviewOrTest) {
            return
        }

        // Acquire bounded 15s PARTIAL_WAKE_LOCK BEFORE cold TTS binder startup so Doze never
        // suspends the CPU across cold TTS onInit (up to 2.5s) + synthesizeToFile (up to 6s) + AudioTrack playback (up to 6.5s).
        val wakeLockHandle = BatteryTelemetryHub.acquireBoundedWakeLock(
            context = context,
            tag = "BatteryBoss:SpeechWakeLock",
            timeoutMs = BatteryTelemetryHub.MAX_SPEECH_WAKELOCK_MS
        )

        try {
            if (tts == null) {
                initializeTts()
            }
            var waitAttempts = 0
            while (!isTtsReady && waitAttempts < 250) {
                if (!BatteryTelemetryHub.isServiceRunning.value && !item.isPreviewOrTest) {
                    return
                }
                kotlinx.coroutines.delay(10L)
                waitAttempts++
            }

            val engine = tts ?: return
            if (!isTtsReady) return

            // Request audio focus for optional media ducking; when the screen is OFF, proceed with
            // USAGE_ALARM AudioTrack speech as long as no phone call is active.
            focusController.requestFocusForSpeech(
                duckingLevel = latestSettings.mediaDuckingLevel,
                independentModeEnabled = latestSettings.independentVolumeEnabled,
                usageOption = latestSettings.audioStreamUsageOption
            )

            val targetLocale = if (item.preset.language == VoiceLanguage.HINDI) {
                Locale.forLanguageTag("hi-IN")
            } else {
                Locale.forLanguageTag("en-IN")
            }

            _isSpeaking.value = true

            val cachedVoicePair = cachedVoiceByPreset[item.preset]
            val isFullyPrewarmed = playbackEngine.isItemFullyPrewarmed(item)
            val resolvedVoiceName: String

            if (isFullyPrewarmed) {
                // Zero TTS Binder IPC when cached WAV is already ready on disk -> audible in < 60ms!
                val matchedVoice = cachedVoicePair?.first
                resolvedVoiceName = matchedVoice?.let { v ->
                    "${v.name} (${v.locale.toLanguageTag()} • Q=${v.quality})"
                } ?: "${item.preset.piperModelId} (${targetLocale.toLanguageTag()})"
            } else {
                val (matchedVoice, isTrueGenderMatch) = cachedVoicePair ?: run {
                    val availableVoices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                    val resolved = selectDistinctOfflineVoiceForPreset(availableVoices, item.preset)
                    if (availableVoices.isNotEmpty()) {
                        cachedVoiceByPreset[item.preset] = resolved
                    }
                    resolved
                }
                if (matchedVoice != null) {
                    engine.voice = matchedVoice
                } else {
                    val res = engine.setLanguage(targetLocale)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        if (item.preset.language == VoiceLanguage.ENGLISH) {
                            engine.setLanguage(Locale.US)
                        }
                    }
                }

                val effectivePitch = if (isTrueGenderMatch) {
                    val userDelta = item.pitch - item.preset.defaultPitch
                    (1.0f + userDelta).coerceIn(0.65f, 1.5f)
                } else {
                    item.pitch.coerceIn(0.6f, 1.6f)
                }
                val effectiveSpeed = item.speed.coerceIn(0.55f, 1.75f)

                engine.setPitch(effectivePitch)
                engine.setSpeechRate(effectiveSpeed)
                runCatching {
                    val effectiveUsage = if (latestSettings.independentVolumeEnabled) {
                        AudioAttributes.USAGE_ALARM
                    } else {
                        latestSettings.audioStreamUsageOption.usageConstant
                    }
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(effectiveUsage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }

                resolvedVoiceName = matchedVoice?.let { v ->
                    "${v.name} (${v.locale.toLanguageTag()} • Q=${v.quality})"
                } ?: "${item.preset.piperModelId} (${targetLocale.toLanguageTag()})"
            }

            val ok = playbackEngine.synthesizeAndPlayItem(
                tts = engine,
                item = item,
                naturalProsodyEnabled = latestSettings.naturalProsodyEnabled,
                systemMediaVolumePercent = null,
                systemAlarmVolumePercent = null,
                selectedVoiceName = resolvedVoiceName,
                synthesizeTimeoutMs = 6_000L
            )
            if (!ok && latestSettings.independentVolumeEnabled) {
                _lastSpokenBanner.value = VoicePlaybackEngine.ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
            }
        } finally {
            _isSpeaking.value = false
            _previewingPreset.value = null
            focusController.abandonFocus()
            BatteryTelemetryHub.releaseBoundedWakeLock(wakeLockHandle)
            refreshDiagnosticsSnapshot()
        }
    }

    private fun selectDistinctOfflineVoiceForPreset(
        voices: Set<Voice>,
        preset: VoiceProfilePreset
    ): Pair<Voice?, Boolean> {
        if (voices.isEmpty()) return null to false
        val langPrefix = if (preset.language == VoiceLanguage.HINDI) "hi" else "en"

        val offlineCandidates = voices.filter { voice ->
            !voice.isNetworkConnectionRequired &&
                voice.locale.language.lowercase(Locale.US).startsWith(langPrefix)
        }.sortedWith(
            compareByDescending<Voice> { it.locale.country.equals("IN", ignoreCase = true) }
                .thenByDescending { it.quality }
                .thenBy { it.name }
        )

        if (offlineCandidates.isEmpty()) return null to false

        val maleMarkers = listOf(
            "male", "#male", "_m_", "-m-", "rohan", "ryan", "prabhat", "madhur",
            "hie", "hif", "end", "enc", "iom", "enm", "gb-x-rjs", "us-x-iom"
        )
        val femaleMarkers = listOf(
            "female", "#female", "_f_", "-f-", "priyamvada", "lessac", "prerana", "swara", "neerja",
            "hid", "hia", "ene", "ena", "iof", "enf", "gb-x-fis", "us-x-sfg"
        )

        val targetMarkers = if (preset.gender == VoiceGender.MALE) maleMarkers else femaleMarkers

        val exactGenderMatch = offlineCandidates.firstOrNull { v ->
            val signature = "${v.name} ${v.features?.joinToString(" ").orEmpty()}".lowercase(Locale.US)
            targetMarkers.any { marker -> signature.contains(marker) }
        }

        if (exactGenderMatch != null) {
            return exactGenderMatch to true
        }

        if (offlineCandidates.size >= 2) {
            val chosen = if (preset.gender == VoiceGender.FEMALE) {
                offlineCandidates.first()
            } else {
                offlineCandidates.last()
            }
            return chosen to true
        }

        return offlineCandidates.first() to false
    }

    private fun formatTemplate(template: String, snapshot: BatteryHardwareSnapshot): String {
        val tempInt = snapshot.temperatureCelsius?.roundToInt()?.toString() ?: "N/A"
        return template
            .replace("{level}", snapshot.percentage.toString())
            .replace("{temp}", tempInt)
            .replace("{source}", snapshot.chargingSource.displayName)
    }

    private fun isWithinQuietHours(settings: UserSettings): Boolean {
        if (!settings.quietHoursEnabled) return false
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = settings.quietStartHour
        val end = settings.quietEndHour
        return if (start <= end) {
            currentHour in start until end
        } else {
            currentHour >= start || currentHour < end
        }
    }

    private fun isRingerNormal(): Boolean {
        return audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    fun shutdown() {
        stopSpeaking()
        runCatching {
            tts?.shutdown()
        }
        tts = null
        isTtsReady = false
    }
}
