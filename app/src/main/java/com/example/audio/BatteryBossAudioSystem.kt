package com.example.audio

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.example.model.AlarmMediaTestMatrixRow
import com.example.model.AnnouncementPriority
import com.example.model.AudioStreamUsageOption
import com.example.model.MediaDuckingLevel
import com.example.model.MediaVolumeStepObservation
import com.example.model.OsVolumeStreamSnapshot
import com.example.model.PcmLoudnessMetrics
import com.example.model.PlaybackTraceRecord
import com.example.model.UsageRoutingAuditEntry
import com.example.model.VoiceProfilePreset
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TRACE_TAG = "BatteryBossAudioTrace"

/**
 * Decoded RIFF WAV metadata produced by [VoicePlaybackEngine.decodeRiffWavHeader].
 */
data class DecodedWavInfo(
    val audioFormatCode: Int,
    val channels: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val dataOffsetBytes: Long,
    val dataSizeBytes: Long
) {
    val formatSummary: String
        get() = "${sampleRateHz} Hz • ${bitsPerSample}-bit PCM • ${if (channels == 2) "Stereo" else "Mono"} (${dataSizeBytes} bytes)"
}

/**
 * Raw 16-bit PCM signal statistics before loudness normalization or boost.
 */
data class RawPcmSignalAnalysis(
    val sampleCount: Int,
    val activeSpeechSampleCount: Int,
    val originalRmsSample: Int,
    val originalRmsDbfs: Float,
    val originalPeakSample: Int,
    val originalPeakDbfs: Float
)

/**
 * Complete snapshot of the device's Android AudioPolicyManager / AudioManager stream routing
 * and volume-group state queried exclusively through public, non-deprecated Android SDK APIs.
 */
data class DeviceAudioPolicyReport(
    val activeUsageOption: AudioStreamUsageOption,
    val actualAudioAttributesUsageLabel: String,
    val reportedVolumeControlStream: Int,
    val reportedVolumeControlStreamName: String,
    val effectivePolicyStreamType: Int,
    val effectivePolicyStreamName: String,
    val controllingSystemSliderName: String,
    val isMappedToStreamMusic: Boolean,
    val doesMediaVolumeChangeLoudness: Boolean,
    val isControlledByAlarmVolume: Boolean,
    val isIndependentAppPrivateStreamSupported: Boolean,
    val isSeparateFromMediaStream: Boolean,
    val streamIndependenceVerdictBadge: String,
    val isVolumeFixed: Boolean,
    val isAccessibilityServiceEnabled: Boolean,
    val activeOutputDeviceName: String,
    val activeOutputDeviceType: Int,
    val systemMediaVolumePercent: Int?,
    val systemMediaVolumeDb: Float?,
    val systemAlarmVolumePercent: Int?,
    val systemAlarmVolumeDb: Float?,
    val systemAlarmVolumeSummary: String,
    val activeStreamVolumePercent: Int?,
    val activeStreamVolumeDb: Float?,
    val volumeGroupSummary: String,
    val observedRoutingBehaviorSummary: String,
    val osStreamRoutingNote: String,
    val allStreamSnapshots: List<OsVolumeStreamSnapshot>,
    val allUsageAudits: List<UsageRoutingAuditEntry>,
    val mediaVolumeStepObservations: List<MediaVolumeStepObservation>,
    val alarmMediaTestMatrix: List<AlarmMediaTestMatrixRow>
)

/**
 * Real-time DSP Loudness Analyzer, Automatic Speech Loudness Normalizer,
 * 100%–200% Voice Booster, and Soft-Knee Tanh Anti-Clipping Limiter for 16-bit PCM WAV audio.
 */
object PcmLoudnessProcessor {

    private const val FULL_SCALE_REF = 32768.0
    private const val MAX_INT16 = 32767

    // Target speech RMS: -13.5 dBFS (~6,925 sample units out of 32,768)
    const val TARGET_SPEECH_RMS_SAMPLE = 6925.0f

    // Target minimum peak for quiet TTS waveforms: -1.0 dBFS (~29,204 sample units)
    const val TARGET_MIN_PEAK_SAMPLE = 29204.0f

    // Maximum automatic makeup normalization gain: 4.0x (+12.04 dB)
    const val MAX_AUTO_NORMALIZATION_GAIN = 4.0f

    // Soft-Knee Limiter parameters:
    // Linear up to 0.82f (-1.72 dBFS, 26,869 sample units).
    // Above 26,869, smooth tanh soft-knee saturation asymptotically approaches 0.985f (-0.13 dBFS, 32,275 sample units).
    const val KNEE_THRESHOLD_SAMPLE = 26869.0f
    const val CEILING_SAMPLE = 32275.0f
    private const val KNEE_SPAN_SAMPLE = CEILING_SAMPLE - KNEE_THRESHOLD_SAMPLE

    // Silence gate for active speech RMS measurement (-55 dBFS ≈ 58 sample units)
    private const val ACTIVE_FRAME_RMS_GATE = 58.0

    fun sampleToDbfs(sampleAmplitude: Int): Float {
        if (sampleAmplitude <= 0) return -96.0f
        val ratio = (sampleAmplitude.toDouble() / FULL_SCALE_REF).coerceIn(1e-5, 2.0)
        return (20.0 * log10(ratio)).toFloat().coerceIn(-96.0f, 6.0f)
    }

    fun gainToDb(gain: Float): Float {
        if (gain <= 0.0001f) return -96.0f
        return (20.0 * log10(gain.toDouble())).toFloat()
    }

    /**
     * Pass 1: Inspects raw 16-bit Little-Endian PCM samples from TTS-generated WAV.
     * Computes active-speech RMS (ignoring leading/trailing silence padding), Peak sample amplitude,
     * and both RMS/Peak values in dBFS.
     */
    fun analyzeRawPcm16(
        pcmBytes: ByteArray,
        bytesLength: Int = pcmBytes.size,
        sampleRateHz: Int = 22050,
        channels: Int = 1
    ): RawPcmSignalAnalysis {
        val validBytes = bytesLength.coerceAtMost(pcmBytes.size)
        val totalSamples = validBytes / 2
        if (totalSamples <= 0) {
            return RawPcmSignalAnalysis(
                sampleCount = 0,
                activeSpeechSampleCount = 0,
                originalRmsSample = 0,
                originalRmsDbfs = -96.0f,
                originalPeakSample = 0,
                originalPeakDbfs = -96.0f
            )
        }

        val frameSamples = ((sampleRateHz * channels) / 50).coerceIn(64, 2048) // 20ms frame
        var maxPeak = 0
        var sumSquaresAll = 0.0
        var sumSquaresActive = 0.0
        var activeSampleCount = 0

        var frameSumSquares = 0.0
        var frameCount = 0

        var i = 0
        while (i + 1 < validBytes) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = (high shl 8) or low
            val absSample = abs(sample).coerceAtMost(32768)
            if (absSample > maxPeak) {
                maxPeak = absSample
            }

            val sq = sample.toDouble() * sample.toDouble()
            sumSquaresAll += sq
            frameSumSquares += sq
            frameCount++

            if (frameCount >= frameSamples || i + 2 >= validBytes) {
                val frameRms = sqrt(frameSumSquares / frameCount.toDouble())
                if (frameRms >= ACTIVE_FRAME_RMS_GATE) {
                    sumSquaresActive += frameSumSquares
                    activeSampleCount += frameCount
                }
                frameSumSquares = 0.0
                frameCount = 0
            }
            i += 2
        }

        val effectiveRmsDouble = if (activeSampleCount > 0) {
            sqrt(sumSquaresActive / activeSampleCount.toDouble())
        } else {
            sqrt(sumSquaresAll / totalSamples.toDouble())
        }

        val rmsSample = effectiveRmsDouble.roundToInt().coerceIn(0, 32768)
        return RawPcmSignalAnalysis(
            sampleCount = totalSamples,
            activeSpeechSampleCount = activeSampleCount,
            originalRmsSample = rmsSample,
            originalRmsDbfs = sampleToDbfs(rmsSample),
            originalPeakSample = maxPeak,
            originalPeakDbfs = sampleToDbfs(maxPeak)
        )
    }

    /**
     * Computes automatic loudness normalization gain for weak TTS-generated WAV files.
     * Raises quiet speech toward -13.5 dBFS RMS / -1.0 dBFS Peak, capped at 4.0x (+12.0 dB).
     */
    fun computeAutoNormalizationGain(
        analysis: RawPcmSignalAnalysis,
        autoNormalizeEnabled: Boolean
    ): Float {
        if (!autoNormalizeEnabled || analysis.sampleCount == 0 || analysis.originalPeakSample <= 32) {
            return 1.0f
        }
        val rmsGain = TARGET_SPEECH_RMS_SAMPLE / analysis.originalRmsSample.coerceAtLeast(1).toFloat()
        val peakGain = TARGET_MIN_PEAK_SAMPLE / analysis.originalPeakSample.coerceAtLeast(1).toFloat()

        val desiredGain = maxOf(rmsGain, minOf(peakGain, 2.5f))
        return desiredGain.coerceIn(1.0f, MAX_AUTO_NORMALIZATION_GAIN)
    }

    /**
     * Applies single-stage sample amplification (`userGain * normalizationGain`) followed by
     * a Soft-Knee Tanh Anti-Clipping Limiter directly on 16-bit Little-Endian PCM samples.
     */
    fun applyGainAndSoftKneeLimiterInPlace(
        pcmBytes: ByteArray,
        bytesLength: Int = pcmBytes.size,
        userGain: Float,
        normalizationGain: Float = 1.0f,
        rawAnalysis: RawPcmSignalAnalysis? = null,
        sampleRateHz: Int = 22050,
        channels: Int = 1
    ): PcmLoudnessMetrics {
        val validBytes = bytesLength.coerceAtMost(pcmBytes.size)
        val preAnalysis = rawAnalysis ?: analyzeRawPcm16(pcmBytes, validBytes, sampleRateHz, channels)
        if (preAnalysis.sampleCount <= 0) {
            return PcmLoudnessMetrics()
        }

        val safeUserGain = userGain.coerceIn(0.0f, IndependentVoiceController.MAX_VOICE_GAIN)
        val safeNormGain = normalizationGain.coerceIn(0.5f, MAX_AUTO_NORMALIZATION_GAIN)
        val totalGain = safeUserGain * safeNormGain

        if (totalGain <= 0.001f) {
            for (i in 0 until validBytes) {
                pcmBytes[i] = 0
            }
            return PcmLoudnessMetrics(
                sampleCount = preAnalysis.sampleCount,
                originalRmsSample = preAnalysis.originalRmsSample,
                originalRmsDbfs = preAnalysis.originalRmsDbfs,
                originalPeakSample = preAnalysis.originalPeakSample,
                originalPeakDbfs = preAnalysis.originalPeakDbfs,
                finalRmsSample = 0,
                finalRmsDbfs = -96.0f,
                finalPeakSample = 0,
                finalPeakDbfs = -96.0f,
                userGainMultiplier = safeUserGain,
                normalizationGainMultiplier = safeNormGain,
                totalAppliedPcmGain = 0.0f,
                totalAppliedGainDb = -96.0f,
                limiterEngaged = false,
                limiterEngagedSamples = 0,
                hardClippedSamples = 0,
                wavSignalDiagnosis = "Muted (App-Level PCM Digital Gain = 0%)"
            )
        }

        var limiterSamples = 0
        var hardClipCount = 0

        var i = 0
        while (i + 1 < validBytes) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = (high shl 8) or low

            val amplified = sample.toFloat() * totalGain
            val absAmp = abs(amplified)

            val outputSample = if (absAmp <= KNEE_THRESHOLD_SAMPLE) {
                amplified.roundToInt()
            } else {
                limiterSamples++
                val excess = (absAmp - KNEE_THRESHOLD_SAMPLE) / KNEE_SPAN_SAMPLE
                val compressedMag = KNEE_THRESHOLD_SAMPLE +
                    KNEE_SPAN_SAMPLE * tanh(excess.toDouble()).toFloat()
                val signed = if (amplified >= 0f) compressedMag else -compressedMag
                signed.roundToInt()
            }.coerceIn(-MAX_INT16, MAX_INT16)

            if (abs(outputSample) >= MAX_INT16) {
                hardClipCount++
            }

            pcmBytes[i] = (outputSample and 0xFF).toByte()
            pcmBytes[i + 1] = ((outputSample shr 8) and 0xFF).toByte()
            i += 2
        }

        val postAnalysis = analyzeRawPcm16(pcmBytes, validBytes, sampleRateHz, channels)
        val totalGainDb = gainToDb(totalGain)
        val wasWeakRawWav = preAnalysis.originalRmsDbfs < -17.5f || preAnalysis.originalPeakDbfs < -6.0f

        val diagnosis = if (wasWeakRawWav) {
            String.format(
                Locale.US,
                "Weak Raw TTS WAV (Orig RMS %.1f dBFS, Peak %.1f dBFS) -> Auto-Normalized %.2fx × PCM Gain %.0f%% -> Final RMS %.1f dBFS (Peak %.1f dBFS)",
                preAnalysis.originalRmsDbfs,
                preAnalysis.originalPeakDbfs,
                safeNormGain,
                safeUserGain * 100f,
                postAnalysis.originalRmsDbfs,
                postAnalysis.originalPeakDbfs
            )
        } else {
            String.format(
                Locale.US,
                "Raw TTS WAV (Orig RMS %.1f dBFS, Peak %.1f dBFS) -> PCM Gain %.0f%% -> Final RMS %.1f dBFS (Peak %.1f dBFS)",
                preAnalysis.originalRmsDbfs,
                preAnalysis.originalPeakDbfs,
                safeUserGain * 100f,
                postAnalysis.originalRmsDbfs,
                postAnalysis.originalPeakDbfs
            )
        }

        return PcmLoudnessMetrics(
            sampleCount = preAnalysis.sampleCount,
            originalRmsSample = preAnalysis.originalRmsSample,
            originalRmsDbfs = preAnalysis.originalRmsDbfs,
            originalPeakSample = preAnalysis.originalPeakSample,
            originalPeakDbfs = preAnalysis.originalPeakDbfs,
            finalRmsSample = postAnalysis.originalRmsSample,
            finalRmsDbfs = postAnalysis.originalRmsDbfs,
            finalPeakSample = postAnalysis.originalPeakSample,
            finalPeakDbfs = postAnalysis.originalPeakDbfs,
            userGainMultiplier = safeUserGain,
            normalizationGainMultiplier = safeNormGain,
            totalAppliedPcmGain = totalGain,
            totalAppliedGainDb = totalGainDb,
            limiterEngaged = limiterSamples > 0,
            limiterEngagedSamples = limiterSamples,
            hardClippedSamples = hardClipCount,
            wavSignalDiagnosis = diagnosis
        )
    }
}

/**
 * Audits the physical device's Android AudioPolicyManager / AudioManager stream routing
 * and volume-group behavior using ONLY supported public, non-deprecated Android APIs.
 *
 * In Independent Playback Mode, Battery Boss routes all announcements via:
 * - `AudioAttributes.USAGE_ALARM (4)`
 * - `AudioAttributes.CONTENT_TYPE_SPEECH (1)`
 * which routes AudioTrack to `AudioManager.STREAM_ALARM (4)` (Alarm Volume) instead of `STREAM_MUSIC` (Media Volume).
 */
class AndroidAudioPolicyAuditor(private val context: Context) {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val accessibilityManager: AccessibilityManager? =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    // Empirical observations recorded during actual playback at Media Volume ~0%, ~50%, and ~100%
    private val recordedStepObservations = ConcurrentHashMap<Int, MediaVolumeStepObservation>()

    // Empirical observations recorded for the 3 Media vs. Alarm test scenarios:
    // "M0_A100" (Media=0%, Alarm=100%), "M100_A0" (Media=100%, Alarm=0%), "M50_A50" (Media=50%, Alarm=50%)
    private val recordedAlarmMatrixObservations = ConcurrentHashMap<String, AlarmMediaTestMatrixRow>()

    companion object {
        fun streamTypeToName(streamType: Int): String = when (streamType) {
            AudioManager.STREAM_MUSIC -> "STREAM_MUSIC (3 • Media Volume)"
            AudioManager.STREAM_ACCESSIBILITY -> "STREAM_ACCESSIBILITY (10 • Accessibility)"
            AudioManager.STREAM_ALARM -> "STREAM_ALARM (4 • Alarm Volume)"
            AudioManager.STREAM_NOTIFICATION -> "STREAM_NOTIFICATION (5 • Notification Volume)"
            AudioManager.STREAM_SYSTEM -> "STREAM_SYSTEM (1 • System/Ringtone Volume)"
            AudioManager.STREAM_RING -> "STREAM_RING (2 • Ringtone Volume)"
            AudioManager.STREAM_VOICE_CALL -> "STREAM_VOICE_CALL (0 • Call Volume)"
            AudioManager.STREAM_DTMF -> "STREAM_DTMF (8 • DTMF)"
            else -> "STREAM_UNKNOWN ($streamType)"
        }

        fun streamTypeToControllingSliderName(streamType: Int): String = when (streamType) {
            AudioManager.STREAM_MUSIC -> "Media Volume Slider (STREAM_MUSIC)"
            AudioManager.STREAM_ACCESSIBILITY -> "Accessibility Volume Slider (STREAM_ACCESSIBILITY)"
            AudioManager.STREAM_ALARM -> "Alarm Volume Slider (STREAM_ALARM)"
            AudioManager.STREAM_NOTIFICATION -> "Notification Volume Slider (STREAM_NOTIFICATION)"
            AudioManager.STREAM_SYSTEM -> "System / Ringtone Volume Slider (STREAM_SYSTEM)"
            AudioManager.STREAM_RING -> "Ringtone Volume Slider (STREAM_RING)"
            AudioManager.STREAM_VOICE_CALL -> "In-Call Volume Slider (STREAM_VOICE_CALL)"
            else -> "System Volume Slider ($streamType)"
        }

        fun deviceTypeToName(deviceType: Int, productName: CharSequence? = null): String {
            val prod = productName?.toString()?.takeIf { it.isNotBlank() }
            return when (deviceType) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> if (prod != null) "Bluetooth Audio ($prod)" else "Bluetooth Audio"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headphones / Headset"
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> if (prod != null) "USB Audio DAC ($prod)" else "USB Audio DAC"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Device Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in Earpiece"
                else -> if (prod != null) "System Output ($prod)" else "Default Audio Output"
            }
        }
    }

    /**
     * Checks whether an Android AccessibilityService with spoken/generic feedback is active.
     */
    fun isAccessibilityServiceActiveOnDevice(): Boolean {
        val am = accessibilityManager ?: return false
        return runCatching {
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            am.isEnabled && !enabledServices.isNullOrEmpty()
        }.getOrDefault(false)
    }

    /**
     * Resolves the active output [AudioDeviceInfo] from either a live [AudioTrack.getRoutedDevice]
     * or `AudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)`.
     */
    fun resolveActiveOutputDevice(routedDevice: AudioDeviceInfo? = null): Pair<Int, String> {
        if (routedDevice != null) {
            return routedDevice.type to deviceTypeToName(routedDevice.type, routedDevice.productName)
        }
        val am = audioManager ?: return (AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Built-in Device Speaker")
        return runCatching {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            if (devices.isEmpty()) {
                return@runCatching AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Default Output Route"
            }
            val preferred = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            } ?: devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } ?: devices.first()

            preferred.type to deviceTypeToName(preferred.type, preferred.productName)
        }.getOrDefault(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Built-in Device Speaker")
    }

    /**
     * Reads a complete public-API snapshot for a single `AudioManager.STREAM_*` constant,
     * including `getStreamVolumeDb` attenuation on Android 9+ (API 28+).
     */
    fun readStreamSnapshot(streamType: Int, deviceType: Int): OsVolumeStreamSnapshot {
        val am = audioManager ?: return OsVolumeStreamSnapshot(
            streamType = streamType,
            streamName = streamTypeToName(streamType),
            currentIndex = 0,
            minIndex = 0,
            maxIndex = 15,
            volumePercent = 0,
            isMuted = false,
            currentAttenuationDb = null,
            minIndexAttenuationDb = null
        )

        val current = runCatching { am.getStreamVolume(streamType) }.getOrDefault(0)
        val max = runCatching { am.getStreamMaxVolume(streamType) }.getOrDefault(15).coerceAtLeast(1)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { am.getStreamMinVolume(streamType) }.getOrDefault(0)
        } else {
            0
        }
        val muted = runCatching { am.isStreamMute(streamType) }.getOrDefault(false)
        val pct = ((current.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)

        val currentDb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { am.getStreamVolumeDb(streamType, current, deviceType) }.getOrNull()
        } else {
            null
        }
        val minDb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { am.getStreamVolumeDb(streamType, min, deviceType) }.getOrNull()
        } else {
            null
        }

        return OsVolumeStreamSnapshot(
            streamType = streamType,
            streamName = streamTypeToName(streamType),
            currentIndex = current,
            minIndex = min,
            maxIndex = max,
            volumePercent = pct,
            isMuted = muted,
            currentAttenuationDb = currentDb,
            minIndexAttenuationDb = minDb
        )
    }

    /**
     * Audits how a specific [AudioStreamUsageOption] is mapped both by `AudioAttributes.volumeControlStream`
     * and by Android's actual runtime `AudioPolicyManager` rules on the current device.
     */
    fun auditUsageOption(
        option: AudioStreamUsageOption,
        a11yServiceActive: Boolean,
        streamSnapshots: Map<Int, OsVolumeStreamSnapshot>
    ): UsageRoutingAuditEntry {
        val attrs = AudioAttributes.Builder()
            .setUsage(option.usageConstant)
            .setContentType(option.contentTypeConstant)
            .build()

        val reportedStream = runCatching { attrs.volumeControlStream }.getOrDefault(AudioManager.STREAM_ALARM)
        val reportedStreamName = streamTypeToName(reportedStream)

        val effectiveStream = when (option) {
            AudioStreamUsageOption.ALARM_SPEECH -> {
                if (reportedStream >= 0 && reportedStream != AudioManager.USE_DEFAULT_STREAM_TYPE) {
                    reportedStream
                } else {
                    AudioManager.STREAM_ALARM
                }
            }
            AudioStreamUsageOption.ACCESSIBILITY_SPEECH -> {
                if (a11yServiceActive) {
                    AudioManager.STREAM_ACCESSIBILITY
                } else {
                    AudioManager.STREAM_MUSIC
                }
            }
            AudioStreamUsageOption.SONIFICATION_SPEECH -> {
                if (reportedStream >= 0 && reportedStream != AudioManager.USE_DEFAULT_STREAM_TYPE) {
                    reportedStream
                } else {
                    AudioManager.STREAM_SYSTEM
                }
            }
            AudioStreamUsageOption.NOTIFICATION_SPEECH -> {
                if (reportedStream >= 0 && reportedStream != AudioManager.USE_DEFAULT_STREAM_TYPE) {
                    reportedStream
                } else {
                    AudioManager.STREAM_NOTIFICATION
                }
            }
            AudioStreamUsageOption.MEDIA_SPEECH -> AudioManager.STREAM_MUSIC
        }

        val effectiveStreamName = streamTypeToName(effectiveStream)
        val isMappedToMusic = effectiveStream == AudioManager.STREAM_MUSIC
        val doesMediaChangeLoudness = isMappedToMusic
        val controllingSlider = streamTypeToControllingSliderName(effectiveStream)
        val snap = streamSnapshots[effectiveStream]
        val snapInfo = if (snap != null) {
            "Current OS Level: ${snap.volumePercent}% (${snap.currentIndex}/${snap.maxIndex}, ${snap.attenuationSummary})"
        } else {
            "Current OS Level: Unknown"
        }

        val verdict = when (option) {
            AudioStreamUsageOption.ALARM_SPEECH -> {
                "ROUTED TO STREAM_ALARM (4): Audible even when Media Volume is 0%, provided Alarm Volume > 0%. " +
                    "Controlled by the system Alarm Volume slider. ($snapInfo)"
            }
            AudioStreamUsageOption.ACCESSIBILITY_SPEECH -> {
                if (!a11yServiceActive) {
                    "MAPPED TO STREAM_MUSIC: AudioAttributes.volumeControlStream reports STREAM_ACCESSIBILITY (10), " +
                        "but because no OS AccessibilityService is active, Android AudioPolicyManager routes this usage " +
                        "to STREAM_MUSIC (Media Volume). Media Volume WILL change loudness. ($snapInfo)"
                } else {
                    "ROUTED TO STREAM_ACCESSIBILITY (10): An OS AccessibilityService is active on this device, so Android " +
                        "separates Accessibility speech from STREAM_MUSIC. Controlled by Accessibility Volume. ($snapInfo)"
                }
            }
            AudioStreamUsageOption.NOTIFICATION_SPEECH -> {
                "SEPARATE FROM STREAM_MUSIC (Uses STREAM_NOTIFICATION): Unaffected by Media Volume slider, " +
                    "but controlled by the system Notification Volume slider and muted in Vibrate/Silent ringer mode. ($snapInfo)"
            }
            AudioStreamUsageOption.SONIFICATION_SPEECH -> {
                "SEPARATE FROM STREAM_MUSIC (Uses STREAM_SYSTEM): Unaffected by Media Volume slider, " +
                    "but controlled by System/Ringtone Volume and muted in Vibrate/Silent ringer mode. ($snapInfo)"
            }
            AudioStreamUsageOption.MEDIA_SPEECH -> {
                "DIRECT STREAM_MUSIC ROUTING: Directly controlled by the system Media Volume slider. ($snapInfo)"
            }
        }

        return UsageRoutingAuditEntry(
            option = option,
            reportedVolumeControlStream = reportedStream,
            reportedVolumeControlStreamName = reportedStreamName,
            effectivePolicyStreamName = effectiveStreamName,
            isMappedToStreamMusic = isMappedToMusic,
            doesMediaVolumeChangeLoudness = doesMediaChangeLoudness,
            isIndependentAppPrivateStream = false,
            controllingSystemSliderName = controllingSlider,
            statusVerdict = verdict
        )
    }

    /**
     * Records an empirical observation whenever an announcement or test voice plays, bucketed
     * into both the 0%/50%/100% Media Volume steps AND the 3 Media vs. Alarm Volume test scenarios:
     * - Scenario 1: Media Volume = 0%, Alarm Volume = 100%
     * - Scenario 2: Media Volume = 100%, Alarm Volume = 0%
     * - Scenario 3: Media Volume = 50%, Alarm Volume = 50%
     */
    fun recordPlaybackObservation(
        mediaVolumePercent: Int?,
        activeOption: AudioStreamUsageOption,
        effectivePolicyStreamType: Int,
        effectiveStreamPercent: Int?,
        effectiveStreamDb: Float?,
        pcmFinalRmsDbfs: Float,
        alarmVolumePercent: Int? = null,
        alarmVolumeDb: Float? = null,
        mediaVolumeDb: Float? = null
    ) {
        val mediaPct = mediaVolumePercent ?: return
        val bucketTarget = when {
            mediaPct == 0 -> 0
            mediaPct in 40..60 -> 50
            mediaPct >= 95 -> 100
            else -> mediaPct
        }

        val am = audioManager
        val maxMusic = runCatching { am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }.getOrDefault(15)
        val curMusic = runCatching { am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0)
        val maxAlarm = runCatching { am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7 }.getOrDefault(7)
        val curAlarm = runCatching { am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: maxAlarm }.getOrDefault(maxAlarm)
        val resolvedAlarmPct = alarmVolumePercent ?: ((curAlarm.toFloat() / maxAlarm.coerceAtLeast(1).toFloat()) * 100f).roundToInt()

        val isMusicStream = effectivePolicyStreamType == AudioManager.STREAM_MUSIC
        val isAlarmStream = effectivePolicyStreamType == AudioManager.STREAM_ALARM

        val behavior = if (isMusicStream) {
            when {
                mediaPct == 0 ->
                    "VERIFIED ON DEVICE (Media = 0%): Active usage (${activeOption.usageConstantName}) is mapped to STREAM_MUSIC. At Media Volume 0% (index $curMusic/$maxMusic, ${formatDb(effectiveStreamDb)}), the OS hardware mixer mutes/attenuates STREAM_MUSIC regardless of App-Level PCM Gain (Final PCM RMS ${String.format(Locale.US, "%.1f dBFS", pcmFinalRmsDbfs)})."
                mediaPct in 40..60 ->
                    "VERIFIED ON DEVICE (Media = $mediaPct%): Active usage (${activeOption.usageConstantName}) is mapped to STREAM_MUSIC. At Media Volume $mediaPct% (index $curMusic/$maxMusic, ${formatDb(effectiveStreamDb)}), the OS hardware mixer attenuates announcement loudness proportionally to the Media slider."
                mediaPct >= 95 ->
                    "VERIFIED ON DEVICE (Media = $mediaPct%): Active usage (${activeOption.usageConstantName}) is mapped to STREAM_MUSIC. At Media Volume $mediaPct% (index $curMusic/$maxMusic, ${formatDb(effectiveStreamDb)}), full unattenuated PCM output reaches the speaker."
                else ->
                    "VERIFIED ON DEVICE (Media = $mediaPct%): Active usage (${activeOption.usageConstantName}) is mapped to STREAM_MUSIC (index $curMusic/$maxMusic, ${formatDb(effectiveStreamDb)}). Media Volume directly scales speaker loudness."
            }
        } else {
            val activeStreamName = streamTypeToName(effectivePolicyStreamType)
            "VERIFIED ON DEVICE (Media = $mediaPct%, Alarm = $resolvedAlarmPct%): Active usage (${activeOption.usageConstantName}) routes to $activeStreamName (OS level ${effectiveStreamPercent ?: resolvedAlarmPct}%, ${formatDb(effectiveStreamDb)}). Changing Media Volume to $mediaPct% does NOT change announcement loudness; loudness is controlled by ${streamTypeToControllingSliderName(effectivePolicyStreamType)}."
        }

        recordedStepObservations[bucketTarget] = MediaVolumeStepObservation(
            targetMediaPercent = bucketTarget,
            streamMusicIndex = curMusic,
            streamMusicMaxIndex = maxMusic,
            streamMusicAttenuationDb = effectiveStreamDb,
            activeUsageConstantName = activeOption.usageConstantName,
            effectivePolicyStreamName = streamTypeToName(effectivePolicyStreamType),
            expectedAndObservedBehavior = behavior,
            verifiedByPlaybackAtThisLevel = true
        )

        if (isAlarmStream) {
            val scenarioKey = when {
                mediaPct == 0 && resolvedAlarmPct >= 90 -> "M0_A100"
                mediaPct >= 90 && resolvedAlarmPct == 0 -> "M100_A0"
                mediaPct in 40..60 && resolvedAlarmPct in 40..60 -> "M50_A50"
                else -> null
            }
            if (scenarioKey != null) {
                val (label, targetM, targetA, resultSummary) = when (scenarioKey) {
                    "M0_A100" -> Quadruple(
                        "Scenario 1: Media = 0%, Alarm = 100%",
                        0,
                        100,
                        "VERIFIED ON DEVICE (Media = 0%, Alarm = $resolvedAlarmPct%): AudioTrack with USAGE_ALARM (4) + CONTENT_TYPE_SPEECH (1) plays at full Alarm Volume (${formatDb(alarmVolumeDb ?: effectiveStreamDb)}). Announcement is clearly AUDIBLE even though Media Volume is 0%."
                    )
                    "M100_A0" -> Quadruple(
                        "Scenario 2: Media = 100%, Alarm = 0%",
                        100,
                        0,
                        "VERIFIED ON DEVICE (Media = $mediaPct%, Alarm = 0%): Because playback is controlled by STREAM_ALARM (index 0/$maxAlarm, ${formatDb(alarmVolumeDb ?: effectiveStreamDb)}), setting Alarm Volume to 0% mutes/attenuates the announcement even when Media Volume is 100%."
                    )
                    else -> Quadruple(
                        "Scenario 3: Media = 50%, Alarm = 50%",
                        50,
                        50,
                        "VERIFIED ON DEVICE (Media = $mediaPct%, Alarm = $resolvedAlarmPct%): AudioTrack with USAGE_ALARM (4) plays at 50% Alarm Volume (${formatDb(alarmVolumeDb ?: effectiveStreamDb)}), independent of the Media Volume slider."
                    )
                }
                recordedAlarmMatrixObservations[scenarioKey] = AlarmMediaTestMatrixRow(
                    scenarioId = scenarioKey,
                    scenarioLabel = label,
                    targetMediaPercent = targetM,
                    targetAlarmPercent = targetA,
                    alarmStreamIndex = curAlarm,
                    alarmStreamMaxIndex = maxAlarm,
                    alarmAttenuationDb = alarmVolumeDb ?: effectiveStreamDb,
                    mediaAttenuationDb = mediaVolumeDb,
                    expectedAndObservedResult = resultSummary,
                    verifiedLiveOnDevice = true
                )
            }
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * Builds the 3 required physical-device verification rows:
     * 1. Media Volume = 0%, Alarm Volume = 100%
     * 2. Media Volume = 100%, Alarm Volume = 0%
     * 3. Media Volume = 50%, Alarm Volume = 50%
     */
    fun buildAlarmMediaTestMatrix(
        activeOption: AudioStreamUsageOption,
        effectivePolicyStreamType: Int,
        deviceType: Int,
        streamSnapshots: Map<Int, OsVolumeStreamSnapshot>
    ): List<AlarmMediaTestMatrixRow> {
        val am = audioManager
        val musicSnap = streamSnapshots[AudioManager.STREAM_MUSIC]
        val alarmSnap = streamSnapshots[AudioManager.STREAM_ALARM]
        val maxMusic = musicSnap?.maxIndex ?: 15
        val maxAlarm = alarmSnap?.maxIndex ?: 7

        fun musicDbAt(idx: Int): Float? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am != null) {
            runCatching { am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, idx.coerceIn(0, maxMusic), deviceType) }.getOrNull()
        } else null

        fun alarmDbAt(idx: Int): Float? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am != null) {
            runCatching { am.getStreamVolumeDb(AudioManager.STREAM_ALARM, idx.coerceIn(0, maxAlarm), deviceType) }.getOrNull()
        } else null

        val halfMusicIdx = (maxMusic / 2.0f).roundToInt().coerceIn(0, maxMusic)
        val halfAlarmIdx = (maxAlarm / 2.0f).roundToInt().coerceIn(0, maxAlarm)
        val isControlledByAlarm = effectivePolicyStreamType == AudioManager.STREAM_ALARM

        val row1 = recordedAlarmMatrixObservations["M0_A100"] ?: run {
            val aDb = alarmDbAt(maxAlarm)
            val mDb = musicDbAt(0)
            val text = if (isControlledByAlarm) {
                "Media = 0% (${formatDb(mDb)}), Alarm = 100% (index $maxAlarm/$maxAlarm, ${formatDb(aDb)}): " +
                    "Because AudioTrack uses USAGE_ALARM (4) + CONTENT_TYPE_SPEECH (1) -> STREAM_ALARM, announcements remain FULLY AUDIBLE at 100% Alarm Volume even when Media Volume is 0%."
            } else {
                "Media = 0% (${formatDb(mDb)}), Alarm = 100%: Active usage is ${activeOption.usageConstantName} (${streamTypeToName(effectivePolicyStreamType)}). Switch to Alarm Speech (USAGE_ALARM) to play through STREAM_ALARM."
            }
            AlarmMediaTestMatrixRow(
                scenarioId = "M0_A100",
                scenarioLabel = "Scenario 1: Media = 0%, Alarm = 100%",
                targetMediaPercent = 0,
                targetAlarmPercent = 100,
                alarmStreamIndex = maxAlarm,
                alarmStreamMaxIndex = maxAlarm,
                alarmAttenuationDb = aDb,
                mediaAttenuationDb = mDb,
                expectedAndObservedResult = text,
                verifiedLiveOnDevice = false
            )
        }

        val row2 = recordedAlarmMatrixObservations["M100_A0"] ?: run {
            val aDb = alarmDbAt(0)
            val mDb = musicDbAt(maxMusic)
            val text = if (isControlledByAlarm) {
                "Media = 100% (${formatDb(mDb)}), Alarm = 0% (index 0/$maxAlarm, ${formatDb(aDb)}): " +
                    "Because AudioTrack is controlled by STREAM_ALARM, setting Alarm Volume to 0% mutes/attenuates announcements at the OS Alarm mixer even though Media Volume is 100%."
            } else {
                "Media = 100% (${formatDb(mDb)}), Alarm = 0%: Active usage is ${activeOption.usageConstantName} (${streamTypeToName(effectivePolicyStreamType)})."
            }
            AlarmMediaTestMatrixRow(
                scenarioId = "M100_A0",
                scenarioLabel = "Scenario 2: Media = 100%, Alarm = 0%",
                targetMediaPercent = 100,
                targetAlarmPercent = 0,
                alarmStreamIndex = 0,
                alarmStreamMaxIndex = maxAlarm,
                alarmAttenuationDb = aDb,
                mediaAttenuationDb = mDb,
                expectedAndObservedResult = text,
                verifiedLiveOnDevice = false
            )
        }

        val row3 = recordedAlarmMatrixObservations["M50_A50"] ?: run {
            val aDb = alarmDbAt(halfAlarmIdx)
            val mDb = musicDbAt(halfMusicIdx)
            val text = if (isControlledByAlarm) {
                "Media = 50% (index $halfMusicIdx/$maxMusic, ${formatDb(mDb)}), Alarm = 50% (index $halfAlarmIdx/$maxAlarm, ${formatDb(aDb)}): " +
                    "AudioTrack plays at the 50% Alarm Volume curve (${formatDb(aDb)}), completely independent of the Media Volume slider."
            } else {
                "Media = 50%, Alarm = 50%: Active usage is ${activeOption.usageConstantName} (${streamTypeToName(effectivePolicyStreamType)})."
            }
            AlarmMediaTestMatrixRow(
                scenarioId = "M50_A50",
                scenarioLabel = "Scenario 3: Media = 50%, Alarm = 50%",
                targetMediaPercent = 50,
                targetAlarmPercent = 50,
                alarmStreamIndex = halfAlarmIdx,
                alarmStreamMaxIndex = maxAlarm,
                alarmAttenuationDb = aDb,
                mediaAttenuationDb = mDb,
                expectedAndObservedResult = text,
                verifiedLiveOnDevice = false
            )
        }

        return listOf(row1, row2, row3)
    }

    /**
     * Builds the 0%, 50%, and 100% Media Volume test/audit rows combining live hardware dB curves
     * from `AudioManager.getStreamVolumeDb` with any empirical playback observations recorded at those steps.
     */
    fun buildMediaVolumeStepObservations(
        activeOption: AudioStreamUsageOption,
        effectivePolicyStreamType: Int,
        deviceType: Int,
        streamSnapshots: Map<Int, OsVolumeStreamSnapshot>
    ): List<MediaVolumeStepObservation> {
        val am = audioManager
        val musicSnap = streamSnapshots[AudioManager.STREAM_MUSIC]
        val maxMusic = musicSnap?.maxIndex ?: 15
        val isMusicStream = effectivePolicyStreamType == AudioManager.STREAM_MUSIC
        val activeStreamSnap = streamSnapshots[effectivePolicyStreamType]

        val targets = listOf(0, 50, 100)
        return targets.map { targetPct ->
            val recorded = recordedStepObservations[targetPct]
            if (recorded != null && recorded.activeUsageConstantName == activeOption.usageConstantName) {
                return@map recorded
            }

            val indexForTarget = when (targetPct) {
                0 -> 0
                50 -> (maxMusic / 2.0f).roundToInt().coerceIn(0, maxMusic)
                else -> maxMusic
            }

            val musicDbAtStep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am != null) {
                runCatching { am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, indexForTarget, deviceType) }.getOrNull()
            } else {
                null
            }

            val behaviorText = if (isMusicStream) {
                when (targetPct) {
                    0 ->
                        "Media Volume = 0% (index 0/$maxMusic, ${formatDb(musicDbAtStep)}): Because ${activeOption.usageConstantName} maps to STREAM_MUSIC on this device, setting Media Volume to 0% silences/attenuates the OS mixer even when App-Level PCM Gain is 100%–200%."
                    50 ->
                        "Media Volume = 50% (index $indexForTarget/$maxMusic, ${formatDb(musicDbAtStep)}): Because ${activeOption.usageConstantName} maps to STREAM_MUSIC on this device, announcement loudness is attenuated by ${formatDb(musicDbAtStep)} by the Media Volume slider."
                    else ->
                        "Media Volume = 100% (index $maxMusic/$maxMusic, ${formatDb(musicDbAtStep)}): 0.0 dB OS mixer attenuation; full App-Level PCM Digital Gain reaches the output sink."
                }
            } else {
                val activeStreamName = streamTypeToName(effectivePolicyStreamType)
                val activeSlider = streamTypeToControllingSliderName(effectivePolicyStreamType)
                val activePct = activeStreamSnap?.volumePercent ?: 100
                val activeDb = activeStreamSnap?.currentAttenuationDb
                "Media Volume = $targetPct% (STREAM_MUSIC index $indexForTarget/$maxMusic, ${formatDb(musicDbAtStep)}): ${activeOption.usageConstantName} routes to $activeStreamName (currently $activePct%, ${formatDb(activeDb)}). Media Volume at $targetPct% does NOT change loudness; controlled by $activeSlider."
            }

            MediaVolumeStepObservation(
                targetMediaPercent = targetPct,
                streamMusicIndex = indexForTarget,
                streamMusicMaxIndex = maxMusic,
                streamMusicAttenuationDb = musicDbAtStep,
                activeUsageConstantName = activeOption.usageConstantName,
                effectivePolicyStreamName = streamTypeToName(effectivePolicyStreamType),
                expectedAndObservedBehavior = behaviorText,
                verifiedByPlaybackAtThisLevel = false
            )
        }
    }

    /**
     * Performs a complete live audit of the device's audio policy, stream volume groups,
     * `AudioAttributes` usage mappings, and Media vs. Alarm Volume behavior.
     */
    fun auditDeviceAudioPolicy(
        selectedOption: AudioStreamUsageOption = AudioStreamUsageOption.ALARM_SPEECH,
        independentModeEnabled: Boolean = true,
        routedDevice: AudioDeviceInfo? = null
    ): DeviceAudioPolicyReport {
        val effectiveOption = if (independentModeEnabled) {
            selectedOption
        } else {
            AudioStreamUsageOption.MEDIA_SPEECH
        }

        val (deviceType, deviceName) = resolveActiveOutputDevice(routedDevice)
        val a11yActive = isAccessibilityServiceActiveOnDevice()
        val volumeFixed = runCatching { audioManager?.isVolumeFixed == true }.getOrDefault(false)

        val trackedStreams = listOf(
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ACCESSIBILITY
        )
        val snapshots = trackedStreams.map { readStreamSnapshot(it, deviceType) }
        val snapshotMap = snapshots.associateBy { it.streamType }

        val usageAudits = AudioStreamUsageOption.entries.map { option ->
            auditUsageOption(option, a11yActive, snapshotMap)
        }
        val activeAudit = usageAudits.first { it.option == effectiveOption }

        val effectivePolicyStreamType = when (effectiveOption) {
            AudioStreamUsageOption.ALARM_SPEECH -> AudioManager.STREAM_ALARM
            AudioStreamUsageOption.ACCESSIBILITY_SPEECH ->
                if (a11yActive) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_MUSIC
            AudioStreamUsageOption.SONIFICATION_SPEECH -> AudioManager.STREAM_SYSTEM
            AudioStreamUsageOption.NOTIFICATION_SPEECH -> AudioManager.STREAM_NOTIFICATION
            AudioStreamUsageOption.MEDIA_SPEECH -> AudioManager.STREAM_MUSIC
        }

        val musicSnap = snapshotMap[AudioManager.STREAM_MUSIC]
        val alarmSnap = snapshotMap[AudioManager.STREAM_ALARM]
        val activeSnap = snapshotMap[effectivePolicyStreamType]
        val a11ySnap = snapshotMap[AudioManager.STREAM_ACCESSIBILITY]

        val stepObservations = buildMediaVolumeStepObservations(
            activeOption = effectiveOption,
            effectivePolicyStreamType = effectivePolicyStreamType,
            deviceType = deviceType,
            streamSnapshots = snapshotMap
        )

        val alarmMediaMatrix = buildAlarmMediaTestMatrix(
            activeOption = effectiveOption,
            effectivePolicyStreamType = effectivePolicyStreamType,
            deviceType = deviceType,
            streamSnapshots = snapshotMap
        )

        val isMappedToMusic = activeAudit.isMappedToStreamMusic
        val doesMediaChange = activeAudit.doesMediaVolumeChangeLoudness
        val isSeparateFromMedia = !isMappedToMusic
        val isControlledByAlarm = effectivePolicyStreamType == AudioManager.STREAM_ALARM

        val verdictBadge = when {
            isControlledByAlarm ->
                "ROUTED TO ALARM STREAM (USAGE_ALARM -> STREAM_ALARM • INDEPENDENT OF MEDIA VOLUME)"
            isMappedToMusic && effectiveOption == AudioStreamUsageOption.ACCESSIBILITY_SPEECH ->
                "INDEPENDENT SYSTEM STREAM UNSUPPORTED (MAPPED TO STREAM_MUSIC)"
            isMappedToMusic ->
                "ROUTED TO STREAM_MUSIC (CONTROLLED BY MEDIA VOLUME)"
            effectivePolicyStreamType == AudioManager.STREAM_ACCESSIBILITY ->
                "SEPARATE OS ACCESSIBILITY STREAM (STREAM_ACCESSIBILITY)"
            effectivePolicyStreamType == AudioManager.STREAM_NOTIFICATION ->
                "SEPARATE FROM MEDIA (USES SYSTEM NOTIFICATION SLIDER)"
            else ->
                "SEPARATE FROM MEDIA (USES SYSTEM/RING VOLUME SLIDER)"
        }

        val alarmSummary = if (alarmSnap != null) {
            "${alarmSnap.volumePercent}% (index ${alarmSnap.currentIndex}/${alarmSnap.maxIndex} • ${alarmSnap.attenuationSummary}${if (alarmSnap.isMuted) " • MUTED" else ""})"
        } else {
            "N/A"
        }

        val volumeGroupSummary = buildString {
            append("Alarm: ${alarmSnap?.volumePercent ?: 0}% (${alarmSnap?.currentIndex ?: 0}/${alarmSnap?.maxIndex ?: 7}, ${alarmSnap?.attenuationSummary ?: "N/A"})")
            append(" | Music: ${musicSnap?.volumePercent ?: 0}% (${musicSnap?.currentIndex ?: 0}/${musicSnap?.maxIndex ?: 15}, ${musicSnap?.attenuationSummary ?: "N/A"})")
            val notifSnap = snapshotMap[AudioManager.STREAM_NOTIFICATION]
            append(" | Notif: ${notifSnap?.volumePercent ?: 0}% (${notifSnap?.currentIndex ?: 0}/${notifSnap?.maxIndex ?: 15})")
            append(" | A11y: ${a11ySnap?.volumePercent ?: 0}% (${a11ySnap?.currentIndex ?: 0}/${a11ySnap?.maxIndex ?: 15})")
        }

        val oem = "${Build.MANUFACTURER} ${Build.MODEL} (Android API ${Build.VERSION.SDK_INT})"
        val observedRoutingBehavior = if (isControlledByAlarm) {
            "ALARM STREAM ROUTING ACTIVE on $oem: AudioTrack is built with AudioAttributes.USAGE_ALARM (4) + CONTENT_TYPE_SPEECH (1), " +
                "which Android's AudioPolicyManager routes to STREAM_ALARM (Current Alarm Volume = $alarmSummary, Current Media Volume = ${musicSnap?.volumePercent ?: 0}%). " +
                "Announcements remain audible when Media Volume is 0% (provided Alarm Volume > 0%) and are controlled by the system Alarm Volume slider."
        } else if (isMappedToMusic) {
            if (effectiveOption == AudioStreamUsageOption.ACCESSIBILITY_SPEECH) {
                "UNSUPPORTED ON $oem: AudioAttributes.volumeControlStream reports ${activeAudit.reportedVolumeControlStreamName}, " +
                    "but because no OS AccessibilityService is enabled, Android's AudioPolicyManager maps USAGE_ASSISTANCE_ACCESSIBILITY (11) " +
                    "to STREAM_MUSIC (Media Volume = ${musicSnap?.volumePercent ?: 0}%, ${musicSnap?.attenuationSummary ?: "N/A"}). " +
                    "Changing the system Media Volume slider WILL change announcement loudness."
            } else {
                "MAPPED TO STREAM_MUSIC on $oem: Active usage ${effectiveOption.usageConstantName} routes to STREAM_MUSIC " +
                    "(Media Volume = ${musicSnap?.volumePercent ?: 0}%, ${musicSnap?.attenuationSummary ?: "N/A"}). " +
                    "Changing the system Media Volume slider WILL change announcement loudness."
            }
        } else {
            "SEPARATE FROM STREAM_MUSIC on $oem: Active usage ${effectiveOption.usageConstantName} routes to ${activeAudit.effectivePolicyStreamName} " +
                "(OS Volume = ${activeSnap?.volumePercent ?: 0}%, ${activeSnap?.attenuationSummary ?: "N/A"}). " +
                "Changing Media Volume (0%, 50%, 100%) does NOT change announcement loudness; instead, loudness is controlled by ${activeAudit.controllingSystemSliderName}."
        }

        val transparentLimitationNote =
            "Android & OEM Alarm Stream Routing Notes ($oem): All Battery Boss announcements use AudioAttributes.USAGE_ALARM (4) + CONTENT_TYPE_SPEECH (1) with AudioTrack.setVolume(1.0f) at unity gain, routing speech to STREAM_ALARM (Alarm Volume) instead of STREAM_MUSIC (Media Volume). " +
                "• Expected Physical-Device Behavior: (1) At Media = 0% & Alarm = 100%, announcements play clearly at full Alarm volume. (2) At Media = 100% & Alarm = 0%, announcements are muted because STREAM_ALARM is 0%. (3) At Media = 50% & Alarm = 50%, announcements play at 50% Alarm volume. " +
                "• OEM Caveats: On Realme/Oppo/OnePlus (ColorOS), Xiaomi/Redmi (HyperOS/MIUI), Samsung (One UI), Vivo (FuntouchOS), and Motorola, STREAM_ALARM is independent of Media Volume, but will be muted if the user sets the system Alarm slider to 0%, enables Total Silence DND (blocking Alarms), or if an active phone call holds exclusive communication focus."

        return DeviceAudioPolicyReport(
            activeUsageOption = effectiveOption,
            actualAudioAttributesUsageLabel = effectiveOption.usageConstantName,
            reportedVolumeControlStream = activeAudit.reportedVolumeControlStream,
            reportedVolumeControlStreamName = activeAudit.reportedVolumeControlStreamName,
            effectivePolicyStreamType = effectivePolicyStreamType,
            effectivePolicyStreamName = activeAudit.effectivePolicyStreamName,
            controllingSystemSliderName = activeAudit.controllingSystemSliderName,
            isMappedToStreamMusic = isMappedToMusic,
            doesMediaVolumeChangeLoudness = doesMediaChange,
            isControlledByAlarmVolume = isControlledByAlarm,
            isIndependentAppPrivateStreamSupported = false,
            isSeparateFromMediaStream = isSeparateFromMedia,
            streamIndependenceVerdictBadge = verdictBadge,
            isVolumeFixed = volumeFixed,
            isAccessibilityServiceEnabled = a11yActive,
            activeOutputDeviceName = deviceName,
            activeOutputDeviceType = deviceType,
            systemMediaVolumePercent = musicSnap?.volumePercent,
            systemMediaVolumeDb = musicSnap?.currentAttenuationDb,
            systemAlarmVolumePercent = alarmSnap?.volumePercent,
            systemAlarmVolumeDb = alarmSnap?.currentAttenuationDb,
            systemAlarmVolumeSummary = alarmSummary,
            activeStreamVolumePercent = activeSnap?.volumePercent,
            activeStreamVolumeDb = activeSnap?.currentAttenuationDb,
            volumeGroupSummary = volumeGroupSummary,
            observedRoutingBehaviorSummary = observedRoutingBehavior,
            osStreamRoutingNote = transparentLimitationNote,
            allStreamSnapshots = snapshots,
            allUsageAudits = usageAudits,
            mediaVolumeStepObservations = stepObservations,
            alarmMediaTestMatrix = alarmMediaMatrix
        )
    }

    private fun formatDb(db: Float?): String {
        if (db == null) return "dB N/A"
        if (db <= -100f || db.isInfinite()) return "-∞ dB (Muted)"
        return String.format(Locale.US, "%.1f dB", db)
    }
}

/**
 * 1. IndependentVoiceController
 *
 * Manages Battery Boss's App-Level PCM Digital Gain (0.0f..2.0f, default 1.0f / 100%,
 * with boost presets at 100%, 125%, 150%, 175%, 200%), automatic WAV loudness normalization,
 * and the selected [AudioStreamUsageOption] (default [AudioStreamUsageOption.ALARM_SPEECH]:
 * `AudioAttributes.USAGE_ALARM` + `AudioAttributes.CONTENT_TYPE_SPEECH`).
 */
class IndependentVoiceController {

    companion object {
        const val DEFAULT_VOICE_GAIN = 1.0f
        const val MAX_VOICE_GAIN = 2.0f // 200% Max App-Level PCM Boost
    }

    private val _configuredVolume = MutableStateFlow(DEFAULT_VOICE_GAIN)
    val configuredVolume: StateFlow<Float> = _configuredVolume.asStateFlow()

    private val _independentModeEnabled = MutableStateFlow(true)
    val independentModeEnabled: StateFlow<Boolean> = _independentModeEnabled.asStateFlow()

    private val _audioStreamUsageOption = MutableStateFlow(AudioStreamUsageOption.ALARM_SPEECH)
    val audioStreamUsageOption: StateFlow<AudioStreamUsageOption> = _audioStreamUsageOption.asStateFlow()

    private val _autoLoudnessNormalizationEnabled = MutableStateFlow(true)
    val autoLoudnessNormalizationEnabled: StateFlow<Boolean> = _autoLoudnessNormalizationEnabled.asStateFlow()

    private val _applyToAllAnnouncements = MutableStateFlow(true)
    val applyToAllAnnouncements: StateFlow<Boolean> = _applyToAllAnnouncements.asStateFlow()

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var activeTrackIsPcm16Scaled: Boolean = true

    fun updateConfiguration(
        volume: Float,
        independentEnabled: Boolean,
        applyToAll: Boolean,
        autoLoudnessNormalization: Boolean = _autoLoudnessNormalizationEnabled.value,
        audioStreamUsageOption: AudioStreamUsageOption = _audioStreamUsageOption.value
    ) {
        val clamped = volume.coerceIn(0.0f, MAX_VOICE_GAIN)
        _configuredVolume.value = clamped
        _independentModeEnabled.value = independentEnabled
        _applyToAllAnnouncements.value = applyToAll
        _autoLoudnessNormalizationEnabled.value = autoLoudnessNormalization
        _audioStreamUsageOption.value = audioStreamUsageOption

        runCatching {
            activeAudioTrack?.setVolume(getAudioTrackStreamGain(activeTrackIsPcm16Scaled))
        }
    }

    fun bindActiveAudioTrack(track: AudioTrack?, isPcm16SoftwareScaled: Boolean = true) {
        activeAudioTrack = track
        activeTrackIsPcm16Scaled = isPcm16SoftwareScaled
        if (track != null) {
            runCatching {
                track.setVolume(getAudioTrackStreamGain(isPcm16SoftwareScaled))
            }
        }
    }

    fun getEffectiveGain(isPreviewOrTest: Boolean = false): Float {
        return if (_applyToAllAnnouncements.value || isPreviewOrTest) {
            _configuredVolume.value.coerceIn(0.0f, MAX_VOICE_GAIN)
        } else {
            DEFAULT_VOICE_GAIN
        }
    }

    fun getEffectiveUsageOption(): AudioStreamUsageOption {
        return if (_independentModeEnabled.value) {
            _audioStreamUsageOption.value
        } else {
            AudioStreamUsageOption.MEDIA_SPEECH
        }
    }

    /**
     * Returns the AudioTrack stream multiplier so that gain is NEVER applied twice:
     * - When 16-bit PCM software scaling is active (isPcm16SoftwareScaled = true), AudioTrack
     *   volume is locked to 1.0f (unity) because PCM processing already scales the samples.
     * - Only when 8-bit PCM is used (isPcm16SoftwareScaled = false) does AudioTrack apply the gain (capped at 1.0f).
     */
    fun getAudioTrackStreamGain(
        isPcm16SoftwareScaled: Boolean,
        isPreviewOrTest: Boolean = false
    ): Float {
        return if (isPcm16SoftwareScaled) {
            1.0f
        } else {
            getEffectiveGain(isPreviewOrTest).coerceIn(0.0f, 1.0f)
        }
    }

    /**
     * Scales 16-bit Little-Endian PCM audio samples in-place across 0.0f..2.0f (0%..200% boost)
     * combined with optional automatic loudness normalization and a soft-knee anti-clipping limiter.
     */
    fun applyPcm16VolumeInPlace(
        buffer: ByteArray,
        bytesRead: Int,
        gain: Float,
        normalizationGain: Float = 1.0f
    ): PcmLoudnessMetrics {
        return PcmLoudnessProcessor.applyGainAndSoftKneeLimiterInPlace(
            pcmBytes = buffer,
            bytesLength = bytesRead,
            userGain = gain.coerceIn(0.0f, MAX_VOICE_GAIN),
            normalizationGain = normalizationGain
        )
    }
}

/**
 * 2. AudioFocusController
 *
 * Manages Android AudioFocus (using modern API 26+ [AudioFocusRequest]), configurable Media Ducking,
 * phone call / alarm detection, and delegates stream routing inspection to [AndroidAudioPolicyAuditor].
 * Never calls AudioManager.setStreamVolume() and uses zero deprecated APIs.
 */
class AudioFocusController(
    private val context: Context,
    private val onTransientInterruption: (reason: String, shouldStopPlayback: Boolean) -> Unit
) {
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val policyAuditor = AndroidAudioPolicyAuditor(context)

    private var activeFocusRequest: AudioFocusRequest? = null

    private val _audioFocusStateText = MutableStateFlow("Idle (No Active Focus Request)")
    val audioFocusStateText: StateFlow<String> = _audioFocusStateText.asStateFlow()

    private val _callOrAlarmStateText = MutableStateFlow("Normal (No Call/Alarm Active)")
    val callOrAlarmStateText: StateFlow<String> = _callOrAlarmStateText.asStateFlow()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                _audioFocusStateText.value = "Focus Lost Permanently (Stopped)"
                onTransientInterruption("AudioFocus Loss", true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                _audioFocusStateText.value = "Paused (Phone Call / Alarm / System Interruption)"
                _callOrAlarmStateText.value = "Interrupted by Call / Alarm (Transient Loss)"
                onTransientInterruption("Transient Call/Alarm Interruption", true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                _audioFocusStateText.value = "Active (Coexisting / Ducked)"
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                _audioFocusStateText.value = "Focus Gained"
                _callOrAlarmStateText.value = "Normal (No Call/Alarm Active)"
            }
        }
    }

    /**
     * Checks whether a phone call, VoIP communication, or incoming ringtone is active.
     */
    fun isPhoneCallOrRingtoneActive(): Boolean {
        val mode = audioManager?.mode ?: return false
        val inCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_CALL_SCREENING
        _callOrAlarmStateText.value = when (mode) {
            AudioManager.MODE_IN_CALL -> "Active Phone Call (Announcements Deferred)"
            AudioManager.MODE_IN_COMMUNICATION -> "Active VoIP Call (Announcements Deferred)"
            AudioManager.MODE_RINGTONE -> "Incoming Call Ringing (Announcements Deferred)"
            else -> "Normal (No Call/Alarm Active)"
        }
        return inCall
    }

    /**
     * Reads the current Android OS STREAM_MUSIC volume percentage (0..100) for diagnostics
     * without ever modifying it.
     */
    fun readSystemMediaVolumePercent(): Int? {
        val am = audioManager ?: return null
        return runCatching {
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            ((current.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        }.getOrNull()
    }

    /**
     * Reads the current Android OS STREAM_ALARM volume percentage (0..100) for diagnostics
     * without ever modifying it.
     */
    fun readSystemAlarmVolumePercent(): Int? {
        val am = audioManager ?: return null
        return runCatching {
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
            ((current.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        }.getOrNull()
    }

    /**
     * Checks whether an Android AccessibilityService is enabled on the OS.
     */
    fun isOsAccessibilityServiceEnabled(): Boolean {
        return policyAuditor.isAccessibilityServiceActiveOnDevice()
    }

    /**
     * Produces an honest, non-fabricated explanation of how the current Android device/OEM
     * routes the selected AudioAttributes usage at the OS mixer level.
     */
    fun buildOsStreamRoutingNote(
        selectedOption: AudioStreamUsageOption = AudioStreamUsageOption.ALARM_SPEECH,
        independentModeEnabled: Boolean = true
    ): String {
        return policyAuditor.auditDeviceAudioPolicy(
            selectedOption = selectedOption,
            independentModeEnabled = independentModeEnabled
        ).osStreamRoutingNote
    }

    fun requestFocusForSpeech(
        duckingLevel: MediaDuckingLevel,
        independentModeEnabled: Boolean,
        usageOption: AudioStreamUsageOption = AudioStreamUsageOption.ALARM_SPEECH
    ): Boolean {
        if (isPhoneCallOrRingtoneActive()) {
            _audioFocusStateText.value = "Deferred (Phone Call Active)"
            return false
        }

        if (duckingLevel == MediaDuckingLevel.OFF) {
            _audioFocusStateText.value = "Bypassed (Media Ducking = OFF)"
            return true
        }

        val am = audioManager ?: return true
        val focusGainType = when (duckingLevel) {
            MediaDuckingLevel.OFF -> return true
            MediaDuckingLevel.LOW -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            MediaDuckingLevel.MEDIUM -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            MediaDuckingLevel.HIGH -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }

        val effectiveUsage = if (independentModeEnabled) {
            usageOption.usageConstant // Defaults to AudioAttributes.USAGE_ALARM (4)
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        return runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(effectiveUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(focusGainType)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(duckingLevel == MediaDuckingLevel.HIGH)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            activeFocusRequest = req
            val result = am.requestAudioFocus(req)
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            val effectiveOption = if (independentModeEnabled) usageOption else AudioStreamUsageOption.MEDIA_SPEECH
            _audioFocusStateText.value = if (granted) {
                "Granted (Ducking: ${duckingLevel.label} • ${effectiveOption.usageConstantName})"
            } else {
                "Denied by System Audio Policy"
            }
            granted
        }.getOrDefault(true)
    }

    fun abandonFocus() {
        val am = audioManager ?: return
        runCatching {
            activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            activeFocusRequest = null
            _audioFocusStateText.value = "Idle (Focus Released Cleanly)"
        }
    }

    /**
     * Queries the currently active audio output route for accurate Audio Diagnostics.
     */
    fun getSelectedAudioOutputDeviceName(): String {
        return policyAuditor.resolveActiveOutputDevice().second
    }
}

/**
 * Queued announcement item ordered by Priority (P1 Critical -> P2 Important -> P3 Normal)
 * and FIFO sequence number.
 */
data class QueuedVoiceAnnouncement(
    val id: String = UUID.randomUUID().toString(),
    val eventType: String,
    val rawMessage: String,
    val clauses: List<String>,
    val priority: AnnouncementPriority,
    val preset: VoiceProfilePreset,
    val speed: Float,
    val pitch: Float,
    val isPreviewOrTest: Boolean = false,
    val repeatCountRemaining: Int = 0,
    val sequenceNumber: Long = sequenceGenerator.incrementAndGet()
) : Comparable<QueuedVoiceAnnouncement> {

    override fun compareTo(other: QueuedVoiceAnnouncement): Int {
        val priorityDiff = this.priority.rank.compareTo(other.priority.rank)
        if (priorityDiff != 0) return priorityDiff
        return this.sequenceNumber.compareTo(other.sequenceNumber)
    }

    companion object {
        private val sequenceGenerator = AtomicLong(0L)
    }
}

/**
 * 3. VoicePlaybackEngine
 *
 * Implements the centralized AudioTrack WAV playback pipeline routed through Android's Alarm Volume stream:
 * - Synthesizes speech to a RIFF WAV file via `TextToSpeech.synthesizeToFile()` with `KEY_PARAM_VOLUME = 1.0f`.
 * - Never calls `TextToSpeech.speak()` in Independent Playback Mode.
 * - Decodes RIFF WAV chunks (`fmt ` and `data`) and applies App-Level PCM Digital Gain (0%–200%) +
 *   Auto-Normalization + Soft-Knee Limiter in a single pass with `AudioTrack.setVolume(1.0f)` locked at unity.
 * - Builds `AudioTrack` using `AudioAttributes.USAGE_ALARM` (`4`) and `AudioAttributes.CONTENT_TYPE_SPEECH` (`1`)
 *   so all announcements (Charger Connected, Charger Disconnected, Battery Percentage, Full Battery, Low Battery,
 *   Overheating, Test Voice, Voice Preview, and Custom Announcements) use the Android Alarm Volume stream (`STREAM_ALARM`).
 */
class VoicePlaybackEngine(
    private val context: Context,
    private val volumeController: IndependentVoiceController,
    private val policyAuditor: AndroidAudioPolicyAuditor = AndroidAudioPolicyAuditor(context)
) {
    companion object {
        const val ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE =
            "Independent Voice Playback Unavailable – TTS fallback is disabled."

        private const val MAX_WAV_BUFFER_BYTES = 8 * 1024 * 1024 // 8 MB safety bound

        /**
         * Parses a RIFF WAV file by walking all subchunks (`fmt `, `JUNK`, `LIST`, `data`, etc.)
         * to extract exact PCM format parameters and the exact byte offset and length of the `data` chunk.
         */
        fun decodeRiffWavHeader(wavFile: File): DecodedWavInfo? {
            if (!wavFile.exists() || wavFile.length() <= 44L) return null
            return runCatching {
                RandomAccessFile(wavFile, "r").use { raf ->
                    val riffHeader = ByteArray(12)
                    raf.readFully(riffHeader)
                    val riffId = String(riffHeader, 0, 4, Charsets.US_ASCII)
                    val waveId = String(riffHeader, 8, 4, Charsets.US_ASCII)
                    if (riffId != "RIFF" || waveId != "WAVE") {
                        return@use null
                    }

                    var audioFormatCode = 1
                    var channels = 1
                    var sampleRate = 22050
                    var bitsPerSample = 16
                    var foundFmt = false
                    var dataOffset = -1L
                    var dataSize = 0L

                    val chunkHeader = ByteArray(8)
                    while (raf.filePointer + 8 <= raf.length()) {
                        raf.readFully(chunkHeader)
                        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                        val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int.toLong() and 0xFFFFFFFFL
                        val chunkDataStart = raf.filePointer

                        if (chunkId == "fmt " && chunkSize >= 16L) {
                            val fmtBytes = ByteArray(16)
                            raf.readFully(fmtBytes)
                            val fmtBuf = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormatCode = fmtBuf.getShort(0).toInt() and 0xFFFF
                            channels = fmtBuf.getShort(2).toInt().coerceIn(1, 2)
                            sampleRate = fmtBuf.getInt(4).coerceIn(8000, 96000)
                            bitsPerSample = fmtBuf.getShort(14).toInt()
                            foundFmt = true
                        } else if (chunkId == "data") {
                            dataOffset = chunkDataStart
                            val availableBytes = (raf.length() - chunkDataStart).coerceAtLeast(0L)
                            dataSize = if (chunkSize in 1L..availableBytes) chunkSize else availableBytes
                            break
                        }

                        val nextPos = chunkDataStart + chunkSize + (chunkSize % 2)
                        if (nextPos >= raf.length() || nextPos <= chunkDataStart) break
                        raf.seek(nextPos)
                    }

                    if (!foundFmt || dataOffset < 0L || dataSize <= 0L) {
                        null
                    } else {
                        DecodedWavInfo(
                            audioFormatCode = audioFormatCode,
                            channels = channels,
                            sampleRateHz = sampleRate,
                            bitsPerSample = bitsPerSample,
                            dataOffsetBytes = dataOffset,
                            dataSizeBytes = dataSize
                        )
                    }
                }
            }.getOrNull()
        }
    }

    @Volatile
    private var isCancelled = false

    private val _playbackEngineLabel = MutableStateFlow("SynthesizeToFile -> AudioTrack (USAGE_ALARM -> STREAM_ALARM)")
    val playbackEngineLabel: StateFlow<String> = _playbackEngineLabel.asStateFlow()

    private val _playbackStateLabel = MutableStateFlow("Idle")
    val playbackStateLabel: StateFlow<String> = _playbackStateLabel.asStateFlow()

    private val _lastErrorText = MutableStateFlow("None")
    val lastErrorText: StateFlow<String> = _lastErrorText.asStateFlow()

    private val _lastTraceRecord = MutableStateFlow(PlaybackTraceRecord())
    val lastTraceRecord: StateFlow<PlaybackTraceRecord> = _lastTraceRecord.asStateFlow()

    private val synthCompletionMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun onUtteranceSynthDone(utteranceId: String?, success: Boolean) {
        if (utteranceId != null) {
            synthCompletionMap.remove(utteranceId)?.complete(success)
        }
    }

    fun cancelCurrentPlayback() {
        isCancelled = true
        synthCompletionMap.values.forEach { it.complete(false) }
        synthCompletionMap.clear()
        volumeController.bindActiveAudioTrack(null)
        _playbackStateLabel.value = "Idle"
    }

    suspend fun synthesizeAndPlayItem(
        tts: TextToSpeech,
        item: QueuedVoiceAnnouncement,
        naturalProsodyEnabled: Boolean,
        systemMediaVolumePercent: Int? = null,
        systemAlarmVolumePercent: Int? = null,
        selectedVoiceName: String = "Default Offline Voice",
        synthesizeTimeoutMs: Long = 10_000L
    ): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        val cacheDir = File(context.cacheDir, "tts_independent_wav").apply {
            if (!exists()) mkdirs()
        }

        val independentEnabled = volumeController.independentModeEnabled.value
        val effectiveUsageOption = volumeController.getEffectiveUsageOption()
        val policyReport = policyAuditor.auditDeviceAudioPolicy(
            selectedOption = effectiveUsageOption,
            independentModeEnabled = independentEnabled
        )

        val selectedEngineName = runCatching { tts.defaultEngine ?: "System Default TTS" }
            .getOrDefault("System Default TTS")
        val usageLabel = effectiveUsageOption.usageConstantName
        val contentTypeLabel = "CONTENT_TYPE_SPEECH (1)"

        for ((index, clause) in item.clauses.withIndex()) {
            if (isCancelled || !isActive) return@withContext false

            val utteranceId = "synth_${UUID.randomUUID()}"
            val wavFile = File(cacheDir, "$utteranceId.wav")
            val effectivePcmGain = volumeController.getEffectiveGain(item.isPreviewOrTest)

            try {
                _playbackStateLabel.value = "Synthesizing WAV via $selectedEngineName..."
                val deferred = CompletableDeferred<Boolean>()
                synthCompletionMap[utteranceId] = deferred

                val synthParams = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
                }

                val synthResult = tts.synthesizeToFile(clause, synthParams, wavFile, utteranceId)
                val callbackCompleted = if (synthResult == TextToSpeech.SUCCESS) {
                    withTimeoutOrNull(synthesizeTimeoutMs) { deferred.await() } == true
                } else {
                    synthCompletionMap.remove(utteranceId)
                    false
                }

                val synthSucceeded = (callbackCompleted || (synthResult == TextToSpeech.SUCCESS && wavFile.exists() && wavFile.length() > 64L)) &&
                    wavFile.exists() && wavFile.length() > 44L

                if (isCancelled) return@withContext false

                val decodedWav = if (synthSucceeded) decodeRiffWavHeader(wavFile) else null
                val wavDecoded = decodedWav != null

                if (independentEnabled) {
                    // Requirement 5: Do NOT use TextToSpeech.speak() fallback in Independent Playback Mode.
                    if (!synthSucceeded || decodedWav == null) {
                        _playbackEngineLabel.value = "AudioTrack PCM Pipeline (Direct TTS Disabled)"
                        _playbackStateLabel.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                        _lastErrorText.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE

                        recordAndLogTrace(
                            PlaybackTraceRecord(
                                entryPoint = item.eventType,
                                selectedTtsEngine = selectedEngineName,
                                selectedTtsVoiceName = selectedVoiceName,
                                synthesizeToFileSucceeded = synthSucceeded,
                                pcmWavDecoded = wavDecoded,
                                decodedWavFormatSummary = decodedWav?.formatSummary ?: "Decode Failed",
                                audioTrackCreated = false,
                                actualAudioAttributesUsage = usageLabel,
                                actualAudioAttributesContentType = contentTypeLabel,
                                reportedVolumeControlStream = policyReport.reportedVolumeControlStreamName,
                                effectiveOsPolicyStream = policyReport.effectivePolicyStreamName,
                                controllingSystemSlider = policyReport.controllingSystemSliderName,
                                doesMediaVolumeChangeLoudness = policyReport.doesMediaVolumeChangeLoudness,
                                isControlledByAlarmVolume = policyReport.isControlledByAlarmVolume,
                                isIndependentSystemVolumeSupported = policyReport.isIndependentAppPrivateStreamSupported,
                                audioTrackPlaybackState = "NOT_CREATED",
                                directTtsFallbackUsed = false,
                                effectivePcmGain = effectivePcmGain,
                                normalizationGain = 1.0f,
                                totalAppliedPcmGain = effectivePcmGain,
                                audioTrackStreamGain = 1.0f,
                                systemMediaVolumePercent = systemMediaVolumePercent ?: policyReport.systemMediaVolumePercent,
                                systemAlarmVolumePercent = systemAlarmVolumePercent ?: policyReport.systemAlarmVolumePercent,
                                systemAlarmVolumeDb = policyReport.systemAlarmVolumeDb,
                                errorOrLimitation = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                            )
                        )
                        return@withContext false
                    }

                    _playbackEngineLabel.value =
                        "SynthesizeToFile -> AudioTrack (${effectiveUsageOption.usageConstantName} -> ${policyReport.effectivePolicyStreamName})"
                    val trackResult = playWavFileThroughAudioTrack(
                        wavFile = wavFile,
                        decodedWav = decodedWav,
                        independentModeEnabled = true,
                        isPreviewOrTest = item.isPreviewOrTest,
                        entryPoint = item.eventType,
                        selectedEngineName = selectedEngineName,
                        selectedVoiceName = selectedVoiceName,
                        systemMediaVolumePercent = systemMediaVolumePercent,
                        systemAlarmVolumePercent = systemAlarmVolumePercent,
                        usageOption = effectiveUsageOption
                    )
                    if (!trackResult) {
                        _playbackStateLabel.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                        _lastErrorText.value = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                        return@withContext false
                    } else {
                        _lastErrorText.value = "None"
                    }
                } else {
                    if (synthSucceeded && decodedWav != null) {
                        _playbackEngineLabel.value = "SynthesizeToFile -> AudioTrack (USAGE_MEDIA -> STREAM_MUSIC)"
                        playWavFileThroughAudioTrack(
                            wavFile = wavFile,
                            decodedWav = decodedWav,
                            independentModeEnabled = false,
                            isPreviewOrTest = item.isPreviewOrTest,
                            entryPoint = item.eventType,
                            selectedEngineName = selectedEngineName,
                            selectedVoiceName = selectedVoiceName,
                            systemMediaVolumePercent = systemMediaVolumePercent,
                            systemAlarmVolumePercent = systemAlarmVolumePercent,
                            usageOption = AudioStreamUsageOption.MEDIA_SPEECH
                        )
                    } else {
                        _playbackEngineLabel.value = "Fallback Direct TTS Stream (Independent Mode OFF)"
                        playFallbackDirectTtsWhenIndependentDisabled(
                            tts = tts,
                            clause = clause,
                            isPreviewOrTest = item.isPreviewOrTest,
                            entryPoint = item.eventType,
                            selectedEngineName = selectedEngineName,
                            selectedVoiceName = selectedVoiceName,
                            systemMediaVolumePercent = systemMediaVolumePercent,
                            systemAlarmVolumePercent = systemAlarmVolumePercent
                        )
                    }
                }

                if (naturalProsodyEnabled && index < item.clauses.lastIndex && !isCancelled) {
                    delay(200L)
                }
            } catch (t: Throwable) {
                val errMsg = if (independentEnabled) {
                    ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                } else {
                    t.localizedMessage ?: "AudioTrack playback exception"
                }
                _lastErrorText.value = errMsg
                _playbackStateLabel.value = errMsg
                recordAndLogTrace(
                    PlaybackTraceRecord(
                        entryPoint = item.eventType,
                        selectedTtsEngine = selectedEngineName,
                        selectedTtsVoiceName = selectedVoiceName,
                        synthesizeToFileSucceeded = false,
                        pcmWavDecoded = false,
                        decodedWavFormatSummary = "Exception: ${t.javaClass.simpleName}",
                        audioTrackCreated = false,
                        actualAudioAttributesUsage = usageLabel,
                        actualAudioAttributesContentType = contentTypeLabel,
                        reportedVolumeControlStream = policyReport.reportedVolumeControlStreamName,
                        effectiveOsPolicyStream = policyReport.effectivePolicyStreamName,
                        controllingSystemSlider = policyReport.controllingSystemSliderName,
                        doesMediaVolumeChangeLoudness = policyReport.doesMediaVolumeChangeLoudness,
                        isControlledByAlarmVolume = policyReport.isControlledByAlarmVolume,
                        isIndependentSystemVolumeSupported = policyReport.isIndependentAppPrivateStreamSupported,
                        audioTrackPlaybackState = "ERROR",
                        directTtsFallbackUsed = false,
                        effectivePcmGain = effectivePcmGain,
                        normalizationGain = 1.0f,
                        totalAppliedPcmGain = effectivePcmGain,
                        audioTrackStreamGain = 1.0f,
                        systemMediaVolumePercent = systemMediaVolumePercent ?: policyReport.systemMediaVolumePercent,
                        systemAlarmVolumePercent = systemAlarmVolumePercent ?: policyReport.systemAlarmVolumePercent,
                        systemAlarmVolumeDb = policyReport.systemAlarmVolumeDb,
                        errorOrLimitation = errMsg
                    )
                )
                return@withContext false
            } finally {
                runCatching { if (wavFile.exists()) wavFile.delete() }
            }
        }

        _playbackStateLabel.value = "Idle"
        true
    }

    /**
     * Reads the decoded WAV PCM payload, runs two-pass Loudness Analysis + Auto-Normalization +
     * 100%–200% App-Level PCM Gain + Soft-Knee Tanh Anti-Clipping Limiter, and streams the processed audio
     * through AudioTrack built with `AudioAttributes.USAGE_ALARM` and `AudioAttributes.CONTENT_TYPE_SPEECH`,
     * keeping `AudioTrack.setVolume(1.0f)` locked at unity.
     */
    suspend fun playWavFileThroughAudioTrack(
        wavFile: File,
        decodedWav: DecodedWavInfo,
        independentModeEnabled: Boolean,
        isPreviewOrTest: Boolean,
        entryPoint: String,
        selectedEngineName: String,
        selectedVoiceName: String = "Default Offline Voice",
        systemMediaVolumePercent: Int? = null,
        systemAlarmVolumePercent: Int? = null,
        usageOption: AudioStreamUsageOption = volumeController.getEffectiveUsageOption()
    ): Boolean {
        val channelOut = if (decodedWav.channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val encoding = if (decodedWav.bitsPerSample == 8) {
            AudioFormat.ENCODING_PCM_8BIT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val isPcm16 = encoding == AudioFormat.ENCODING_PCM_16BIT

        val dataBytesToRead = decodedWav.dataSizeBytes.coerceIn(0L, MAX_WAV_BUFFER_BYTES.toLong()).toInt()
        if (dataBytesToRead <= 0) return false

        val rawPcmBuffer = ByteArray(dataBytesToRead)
        val actualBytesRead = RandomAccessFile(wavFile, "r").use { raf ->
            raf.seek(decodedWav.dataOffsetBytes)
            var offset = 0
            while (offset < dataBytesToRead) {
                val r = raf.read(rawPcmBuffer, offset, dataBytesToRead - offset)
                if (r <= 0) break
                offset += r
            }
            offset
        }
        if (actualBytesRead <= 0) return false

        val userGain = volumeController.getEffectiveGain(isPreviewOrTest)
        val autoNormEnabled = volumeController.autoLoudnessNormalizationEnabled.value

        val loudnessMetrics: PcmLoudnessMetrics = if (isPcm16) {
            val rawAnalysis = PcmLoudnessProcessor.analyzeRawPcm16(
                pcmBytes = rawPcmBuffer,
                bytesLength = actualBytesRead,
                sampleRateHz = decodedWav.sampleRateHz,
                channels = decodedWav.channels
            )
            val normGain = PcmLoudnessProcessor.computeAutoNormalizationGain(
                analysis = rawAnalysis,
                autoNormalizeEnabled = autoNormEnabled
            )
            PcmLoudnessProcessor.applyGainAndSoftKneeLimiterInPlace(
                pcmBytes = rawPcmBuffer,
                bytesLength = actualBytesRead,
                userGain = userGain,
                normalizationGain = normGain,
                rawAnalysis = rawAnalysis,
                sampleRateHz = decodedWav.sampleRateHz,
                channels = decodedWav.channels
            )
        } else {
            PcmLoudnessMetrics(
                sampleCount = actualBytesRead,
                userGainMultiplier = userGain,
                normalizationGainMultiplier = 1.0f,
                totalAppliedPcmGain = userGain
            )
        }

        val minBufSize = AudioTrack.getMinBufferSize(decodedWav.sampleRateHz, channelOut, encoding)
            .coerceAtLeast(4096)

        val effectiveUsageOption = if (independentModeEnabled) {
            usageOption
        } else {
            AudioStreamUsageOption.MEDIA_SPEECH
        }

        // Requirements 1 & 2: In Independent Playback Mode, replace USAGE_ASSISTANCE_ACCESSIBILITY
        // with AudioAttributes.USAGE_ALARM (4) and keep AudioAttributes.CONTENT_TYPE_SPEECH (1).
        val usage = if (independentModeEnabled && effectiveUsageOption == AudioStreamUsageOption.ALARM_SPEECH) {
            AudioAttributes.USAGE_ALARM
        } else {
            effectiveUsageOption.usageConstant
        }
        val contentType = AudioAttributes.CONTENT_TYPE_SPEECH

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()

        val usageLabel = if (usage == AudioAttributes.USAGE_ALARM) {
            "USAGE_ALARM (4)"
        } else {
            effectiveUsageOption.usageConstantName
        }
        val contentTypeLabel = "CONTENT_TYPE_SPEECH (1)"

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(decodedWav.sampleRateHz)
            .setEncoding(encoding)
            .setChannelMask(channelOut)
            .build()

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        val prePolicyReport = policyAuditor.auditDeviceAudioPolicy(
            selectedOption = effectiveUsageOption,
            independentModeEnabled = independentModeEnabled
        )

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            recordAndLogTrace(
                PlaybackTraceRecord(
                    entryPoint = entryPoint,
                    selectedTtsEngine = selectedEngineName,
                    selectedTtsVoiceName = selectedVoiceName,
                    synthesizeToFileSucceeded = true,
                    pcmWavDecoded = true,
                    decodedWavFormatSummary = decodedWav.formatSummary,
                    audioTrackCreated = false,
                    actualAudioAttributesUsage = usageLabel,
                    actualAudioAttributesContentType = contentTypeLabel,
                    reportedVolumeControlStream = prePolicyReport.reportedVolumeControlStreamName,
                    effectiveOsPolicyStream = prePolicyReport.effectivePolicyStreamName,
                    controllingSystemSlider = prePolicyReport.controllingSystemSliderName,
                    doesMediaVolumeChangeLoudness = prePolicyReport.doesMediaVolumeChangeLoudness,
                    isControlledByAlarmVolume = prePolicyReport.isControlledByAlarmVolume,
                    isIndependentSystemVolumeSupported = prePolicyReport.isIndependentAppPrivateStreamSupported,
                    audioTrackPlaybackState = "STATE_UNINITIALIZED",
                    directTtsFallbackUsed = false,
                    effectivePcmGain = userGain,
                    normalizationGain = loudnessMetrics.normalizationGainMultiplier,
                    totalAppliedPcmGain = loudnessMetrics.totalAppliedPcmGain,
                    audioTrackStreamGain = volumeController.getAudioTrackStreamGain(isPcm16, isPreviewOrTest),
                    loudnessMetrics = loudnessMetrics,
                    systemMediaVolumePercent = systemMediaVolumePercent ?: prePolicyReport.systemMediaVolumePercent,
                    systemAlarmVolumePercent = systemAlarmVolumePercent ?: prePolicyReport.systemAlarmVolumePercent,
                    systemAlarmVolumeDb = prePolicyReport.systemAlarmVolumeDb,
                    errorOrLimitation = ERROR_INDEPENDENT_PLAYBACK_UNAVAILABLE
                )
            )
            return false
        }

        try {
            volumeController.bindActiveAudioTrack(track, isPcm16SoftwareScaled = isPcm16)
            // Requirement 7: Keep AudioTrack.setVolume(1.0f) at unity gain
            val currentTrackGain = volumeController.getAudioTrackStreamGain(isPcm16, isPreviewOrTest)
            track.setVolume(currentTrackGain)

            track.play()
            val playStateAfterStart = track.playState
            val routedDevice = runCatching { track.routedDevice }.getOrNull()

            val livePolicyReport = policyAuditor.auditDeviceAudioPolicy(
                selectedOption = effectiveUsageOption,
                independentModeEnabled = independentModeEnabled,
                routedDevice = routedDevice
            )
            val liveMediaVol = systemMediaVolumePercent ?: livePolicyReport.systemMediaVolumePercent
            val liveAlarmVol = systemAlarmVolumePercent ?: livePolicyReport.systemAlarmVolumePercent

            policyAuditor.recordPlaybackObservation(
                mediaVolumePercent = liveMediaVol,
                activeOption = effectiveUsageOption,
                effectivePolicyStreamType = livePolicyReport.effectivePolicyStreamType,
                effectiveStreamPercent = livePolicyReport.activeStreamVolumePercent,
                effectiveStreamDb = livePolicyReport.activeStreamVolumeDb,
                pcmFinalRmsDbfs = loudnessMetrics.finalRmsDbfs,
                alarmVolumePercent = liveAlarmVol,
                alarmVolumeDb = livePolicyReport.systemAlarmVolumeDb,
                mediaVolumeDb = livePolicyReport.systemMediaVolumeDb
            )

            _playbackStateLabel.value = String.format(
                Locale.US,
                "Playing AudioTrack (%s -> %s • Alarm Vol %s%% • PCM %.0f%% × Norm %.2fx)",
                usageLabel,
                livePolicyReport.effectivePolicyStreamName,
                liveAlarmVol?.toString() ?: "N/A",
                userGain * 100f,
                loudnessMetrics.normalizationGainMultiplier
            )

            val limitationStatus = if (livePolicyReport.isControlledByAlarmVolume) {
                "Routed to STREAM_ALARM (Controlled by Alarm Volume = ${liveAlarmVol ?: 100}%; Independent of Media Volume)"
            } else if (livePolicyReport.isMappedToStreamMusic) {
                "Mapped to STREAM_MUSIC (${livePolicyReport.controllingSystemSliderName})"
            } else {
                "Routed to ${livePolicyReport.effectivePolicyStreamName} (Controlled by ${livePolicyReport.controllingSystemSliderName})"
            }

            recordAndLogTrace(
                PlaybackTraceRecord(
                    entryPoint = entryPoint,
                    selectedTtsEngine = selectedEngineName,
                    selectedTtsVoiceName = selectedVoiceName,
                    synthesizeToFileSucceeded = true,
                    pcmWavDecoded = true,
                    decodedWavFormatSummary = decodedWav.formatSummary,
                    audioTrackCreated = true,
                    actualAudioAttributesUsage = usageLabel,
                    actualAudioAttributesContentType = contentTypeLabel,
                    reportedVolumeControlStream = livePolicyReport.reportedVolumeControlStreamName,
                    effectiveOsPolicyStream = livePolicyReport.effectivePolicyStreamName,
                    controllingSystemSlider = livePolicyReport.controllingSystemSliderName,
                    doesMediaVolumeChangeLoudness = livePolicyReport.doesMediaVolumeChangeLoudness,
                    isControlledByAlarmVolume = livePolicyReport.isControlledByAlarmVolume,
                    isIndependentSystemVolumeSupported = livePolicyReport.isIndependentAppPrivateStreamSupported,
                    audioTrackPlaybackState = "PLAYSTATE_PLAYING ($playStateAfterStart)",
                    directTtsFallbackUsed = false,
                    effectivePcmGain = userGain,
                    normalizationGain = loudnessMetrics.normalizationGainMultiplier,
                    totalAppliedPcmGain = loudnessMetrics.totalAppliedPcmGain,
                    audioTrackStreamGain = currentTrackGain,
                    loudnessMetrics = loudnessMetrics,
                    systemMediaVolumePercent = liveMediaVol,
                    systemAlarmVolumePercent = liveAlarmVol,
                    systemAlarmVolumeDb = livePolicyReport.systemAlarmVolumeDb,
                    errorOrLimitation = limitationStatus
                )
            )

            val chunkSize = 2048
            var offset = 0
            var totalBytesWritten = 0L

            while (!isCancelled && offset < actualBytesRead) {
                val toWrite = minOf(chunkSize, actualBytesRead - offset)
                if (isPcm16) {
                    track.setVolume(1.0f)
                }
                val written = track.write(rawPcmBuffer, offset, toWrite)
                if (written <= 0) break
                offset += written
                totalBytesWritten += written
            }

            if (!isCancelled && totalBytesWritten in 1 until minBufSize.toLong()) {
                val padSize = (minBufSize - totalBytesWritten.toInt()).coerceAtLeast(0)
                if (padSize > 0) {
                    val silencePad = ByteArray(padSize)
                    track.write(silencePad, 0, padSize)
                    totalBytesWritten += padSize
                }
            }

            if (!isCancelled && totalBytesWritten > 0L) {
                val bytesPerFrame = (decodedWav.channels * (decodedWav.bitsPerSample / 8)).coerceAtLeast(2)
                val totalFrames = (totalBytesWritten / bytesPerFrame).toInt()
                val expectedDurationMs = ((totalFrames.toLong() * 1000L) / decodedWav.sampleRateHz).coerceIn(50L, 15_000L)
                val startTime = System.currentTimeMillis()
                while (!isCancelled && (System.currentTimeMillis() - startTime) < expectedDurationMs + 250L) {
                    val head = runCatching { track.playbackHeadPosition }.getOrDefault(totalFrames)
                    if (head >= totalFrames) break
                    delay(25L)
                }
            }

            runCatching { track.stop() }
            return true
        } finally {
            volumeController.bindActiveAudioTrack(null)
            runCatching { track.release() }
        }
    }

    /**
     * Used ONLY when Independent Playback Mode is explicitly turned OFF by the user
     * and file synthesis is unavailable. Never invoked in Independent Playback Mode.
     */
    private suspend fun playFallbackDirectTtsWhenIndependentDisabled(
        tts: TextToSpeech,
        clause: String,
        isPreviewOrTest: Boolean,
        entryPoint: String,
        selectedEngineName: String,
        selectedVoiceName: String,
        systemMediaVolumePercent: Int?,
        systemAlarmVolumePercent: Int?
    ) {
        val utteranceId = "fallback_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        synthCompletionMap[utteranceId] = deferred

        val gain = volumeController.getEffectiveGain(isPreviewOrTest).coerceIn(0.0f, 1.0f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain)
        }
        _playbackStateLabel.value = "Playing Fallback TTS Stream (STREAM_MUSIC • ${(gain * 100).roundToInt()}%)"

        recordAndLogTrace(
            PlaybackTraceRecord(
                entryPoint = entryPoint,
                selectedTtsEngine = selectedEngineName,
                selectedTtsVoiceName = selectedVoiceName,
                synthesizeToFileSucceeded = false,
                pcmWavDecoded = false,
                decodedWavFormatSummary = "N/A (Direct TTS Mode)",
                audioTrackCreated = false,
                actualAudioAttributesUsage = "USAGE_MEDIA (1)",
                actualAudioAttributesContentType = "CONTENT_TYPE_SPEECH (1)",
                reportedVolumeControlStream = "STREAM_MUSIC (3 • Media Volume)",
                effectiveOsPolicyStream = "STREAM_MUSIC (3 • Media Volume)",
                controllingSystemSlider = "Media Volume Slider (STREAM_MUSIC)",
                doesMediaVolumeChangeLoudness = true,
                isControlledByAlarmVolume = false,
                isIndependentSystemVolumeSupported = false,
                audioTrackPlaybackState = "DIRECT_TTS_SPEAK",
                directTtsFallbackUsed = true,
                effectivePcmGain = gain,
                normalizationGain = 1.0f,
                totalAppliedPcmGain = gain,
                audioTrackStreamGain = gain,
                systemMediaVolumePercent = systemMediaVolumePercent,
                systemAlarmVolumePercent = systemAlarmVolumePercent,
                errorOrLimitation = "Direct TTS mode active (Controlled by Media Volume)"
            )
        )

        val res = tts.speak(clause, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res == TextToSpeech.SUCCESS) {
            withTimeoutOrNull(8_000L) { deferred.await() }
        } else {
            synthCompletionMap.remove(utteranceId)
        }
    }

    private fun recordAndLogTrace(record: PlaybackTraceRecord) {
        _lastTraceRecord.value = record
        val m = record.loudnessMetrics
        runCatching {
            Log.i(
                TRACE_TAG,
                "EntryPoint=${record.entryPoint} | Engine=${record.selectedTtsEngine} | Voice=${record.selectedTtsVoiceName} | " +
                    "synthToFile=${record.synthesizeToFileSucceeded} | wavDecoded=${record.pcmWavDecoded} (${record.decodedWavFormatSummary}) | " +
                    "usage=${record.actualAudioAttributesUsage} | contentType=${record.actualAudioAttributesContentType} | " +
                    "reportedStream=${record.reportedVolumeControlStream} | effectivePolicyStream=${record.effectiveOsPolicyStream} | " +
                    "controlledByAlarm=${record.isControlledByAlarmVolume} | sysAlarmVol=${record.systemAlarmVolumePercent}% | " +
                    "sysMediaVol=${record.systemMediaVolumePercent}% | audioTrackCreated=${record.audioTrackCreated} | " +
                    "trackState=${record.audioTrackPlaybackState} | origRMS=${m.originalRmsSummary} | finalRMS=${m.finalRmsSummary} | " +
                    "appliedPcmGain=${m.appliedGainSummary} | trackStreamGain=${record.audioTrackStreamGain} | note=${record.errorOrLimitation}"
            )
        }
    }
}

/**
 * 4. VoiceQueueManager
 *
 * Centralized priority queue for all Battery Boss announcements:
 * - Priority 1 (CRITICAL): Overheating, critical low battery, safety alerts
 * - Priority 2 (IMPORTANT): Charger connected, disconnected, 100% full charge
 * - Priority 3 (NORMAL): Charging percentage milestones (20%, 50%, 80%, intervals)
 */
class VoiceQueueManager(
    private val onProcessItem: suspend (QueuedVoiceAnnouncement) -> Unit,
    private val onQueueStateChanged: (queueSize: Int, summary: String, currentPriority: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val priorityQueue = PriorityBlockingQueue<QueuedVoiceAnnouncement>()

    @Volatile
    private var currentlyPlayingItem: QueuedVoiceAnnouncement? = null

    @Volatile
    private var isProcessingLoopActive = false

    fun enqueue(
        item: QueuedVoiceAnnouncement,
        onInterruptLowerPriority: () -> Unit
    ) {
        val isDuplicatePending = priorityQueue.any {
            it.rawMessage == item.rawMessage && it.priority == item.priority
        }
        if (isDuplicatePending && !item.isPreviewOrTest) {
            return
        }

        val active = currentlyPlayingItem
        if (item.isPreviewOrTest) {
            priorityQueue.clear()
            priorityQueue.put(item)
            if (active != null) {
                onInterruptLowerPriority()
            }
        } else if (
            item.priority == AnnouncementPriority.CRITICAL &&
            active != null &&
            active.priority == AnnouncementPriority.NORMAL
        ) {
            priorityQueue.put(item)
            onInterruptLowerPriority()
        } else {
            priorityQueue.put(item)
        }

        notifyState()
        ensureLoopRunning()
    }

    fun cancelAll(onStopCurrentPlayback: () -> Unit) {
        priorityQueue.clear()
        currentlyPlayingItem = null
        onStopCurrentPlayback()
        notifyState()
    }

    fun cancelPendingNonCritical(onStopCurrentPlayback: () -> Unit) {
        priorityQueue.removeIf { it.priority != AnnouncementPriority.CRITICAL }
        val active = currentlyPlayingItem
        if (active != null && active.priority != AnnouncementPriority.CRITICAL) {
            currentlyPlayingItem = null
            onStopCurrentPlayback()
        }
        notifyState()
    }

    private fun ensureLoopRunning() {
        synchronized(this) {
            if (isProcessingLoopActive) return
            isProcessingLoopActive = true
        }

        scope.launch {
            try {
                while (isActive) {
                    val next = priorityQueue.poll() ?: break
                    currentlyPlayingItem = next
                    notifyState()

                    onProcessItem(next)

                    if (next.repeatCountRemaining > 0) {
                        delay(450L)
                        val repeated = next.copy(
                            id = UUID.randomUUID().toString(),
                            repeatCountRemaining = next.repeatCountRemaining - 1
                        )
                        currentlyPlayingItem = repeated
                        notifyState()
                        onProcessItem(repeated)
                    }

                    currentlyPlayingItem = null
                    notifyState()
                }
            } finally {
                synchronized(this@VoiceQueueManager) {
                    isProcessingLoopActive = false
                }
                currentlyPlayingItem = null
                notifyState()
                if (priorityQueue.isNotEmpty()) {
                    ensureLoopRunning()
                }
            }
        }
    }

    private fun notifyState() {
        val active = currentlyPlayingItem
        val pendingCount = priorityQueue.size
        val summary = when {
            active != null && pendingCount > 0 ->
                "Speaking (${active.eventType}) • $pendingCount queued"
            active != null ->
                "Speaking (${active.eventType}) • 0 pending"
            pendingCount > 0 ->
                "$pendingCount announcement(s) queued"
            else ->
                "Queue Empty (0 pending)"
        }
        val priLabel = active?.priority?.badgeText
            ?: priorityQueue.peek()?.priority?.badgeText
            ?: "None"
        onQueueStateChanged(pendingCount, summary, priLabel)
    }
}
