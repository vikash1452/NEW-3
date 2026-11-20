package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AudioDiagnosticsState
import com.example.model.AudioStreamUsageOption
import com.example.model.MediaDuckingLevel
import com.example.model.UserSettings
import com.example.model.VoiceProfilePreset
import com.example.model.VoiceVolumePreset
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldCharge
import com.example.ui.theme.NavyCardBorder
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceElevated
import com.example.ui.theme.PurpleVoiceAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThermalCrimson
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact Acoustic Controls, Speech Rate, Voice Pitch, Announcement Volume / PCM Boost (0%–200%),
 * Automatic WAV Loudness Normalization & Limiter, and Test Active Voice Card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndependentVoiceVolumeCard(
    settings: UserSettings,
    isSpeaking: Boolean,
    onLiveVolumeChange: (Float) -> Unit,
    onUpdateIndependentVolumeSettings: (
        independentEnabled: Boolean?,
        volume: Float?,
        rememberLastVolume: Boolean?,
        applyToAllAnnouncements: Boolean?,
        mediaDuckingLevel: MediaDuckingLevel?,
        repeatPolicy: AnnouncementRepeatPolicy?,
        autoLoudnessNormalization: Boolean?,
        audioStreamUsageOption: AudioStreamUsageOption?
    ) -> Unit,
    onTestVoice: () -> Unit,
    onPreviewActiveVoice: (VoiceProfilePreset) -> Unit,
    onTestChargerConnected: () -> Unit = onTestVoice,
    onTestPriorityBurst: () -> Unit,
    onStopSpeaking: () -> Unit,
    audioDiagnostics: AudioDiagnosticsState = AudioDiagnosticsState(),
    onUpdateVoiceParameters: ((Float, Float, Float, Boolean?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var sliderVolume by remember(settings.voiceVolume) {
        mutableFloatStateOf(settings.voiceVolume.coerceIn(0.0f, 2.0f))
    }
    var speed by remember(settings.voiceSpeed) {
        mutableFloatStateOf(settings.voiceSpeed)
    }
    var pitch by remember(settings.voicePitch) {
        mutableFloatStateOf(settings.voicePitch)
    }
    var showMoreVolumeOptions by rememberSaveable { mutableStateOf(false) }

    val percentDisplay = (sliderVolume * 100).roundToInt()
    val isBoosted = percentDisplay > 100
    val isMappedToMusic = audioDiagnostics.isMappedToStreamMusic

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("independent_voice_volume_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isMappedToMusic -> AmberWarning.copy(alpha = 0.7f)
                isBoosted -> ElectricCyan.copy(alpha = 0.7f)
                else -> EmeraldCharge.copy(alpha = 0.6f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Compact Header: Acoustic Controls & Announcement Volume / PCM Boost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when {
                            percentDisplay == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                            percentDisplay < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = "Acoustic & Volume Controls",
                        tint = if (isMappedToMusic) AmberWarning else EmeraldCharge,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "ACOUSTIC CONTROLS & PCM VOLUME",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${settings.activePreset.title} (${settings.activePreset.personaName})",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    color = if (isMappedToMusic) AmberWarning.copy(alpha = 0.16f) else EmeraldCharge.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, if (isMappedToMusic) AmberWarning else EmeraldCharge)
                ) {
                    Text(
                        text = if (audioDiagnostics.isControlledByAlarmVolume) {
                            "USAGE_ALARM • $percentDisplay%"
                        } else if (isMappedToMusic) {
                            "STREAM_MUSIC • $percentDisplay%"
                        } else {
                            "NON-MEDIA • $percentDisplay%"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMappedToMusic) AmberWarning else EmeraldCharge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // 2. Announcement Volume / PCM Boost (0% – 200%) Slider + Presets
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Announcement Volume / PCM Boost",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$percentDisplay% (${String.format(Locale.US, "%.2fx", sliderVolume)})",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isBoosted) ElectricCyan else EmeraldCharge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = sliderVolume,
                    onValueChange = { newVal ->
                        sliderVolume = newVal.coerceIn(0.0f, 2.0f)
                        onLiveVolumeChange(sliderVolume)
                    },
                    onValueChangeFinished = {
                        onUpdateIndependentVolumeSettings(null, sliderVolume, null, null, null, null, null, null)
                        onUpdateVoiceParameters?.invoke(speed, pitch, sliderVolume, null)
                    },
                    valueRange = 0.0f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = if (isBoosted) ElectricCyan else EmeraldCharge,
                        activeTrackColor = if (isBoosted) ElectricCyan else EmeraldCharge,
                        inactiveTrackColor = NavySurfaceElevated
                    ),
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("slider_independent_volume")
                )

                // Compact Preset Chips (20%..200%)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VoiceVolumePreset.entries.forEach { preset ->
                        val isSelected = abs(sliderVolume - preset.volumeFraction) < 0.03f
                        val chipAccent = if (preset.isBoost) ElectricCyan else EmeraldCharge
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                sliderVolume = preset.volumeFraction
                                onLiveVolumeChange(preset.volumeFraction)
                                onUpdateIndependentVolumeSettings(
                                    null,
                                    preset.volumeFraction,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null
                                )
                            },
                            label = {
                                Text(
                                    text = if (preset.isBoost) "⚡${preset.percentLabel}" else preset.percentLabel,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipAccent.copy(alpha = 0.22f),
                                selectedLabelColor = chipAccent
                            ),
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("preset_volume_${preset.name.lowercase(Locale.US)}")
                        )
                    }
                }
            }

            // 3. Speech Rate & Voice Pitch Sliders (when onUpdateVoiceParameters is provided)
            if (onUpdateVoiceParameters != null) {
                HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Speech Rate", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(
                                String.format(Locale.US, "%.2fx", speed),
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldCharge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = speed,
                            onValueChange = { speed = it },
                            onValueChangeFinished = {
                                onUpdateVoiceParameters(speed, pitch, sliderVolume, null)
                            },
                            valueRange = 0.55f..1.75f,
                            colors = SliderDefaults.colors(
                                thumbColor = EmeraldCharge,
                                activeTrackColor = EmeraldCharge,
                                inactiveTrackColor = NavySurfaceElevated
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .testTag("slider_voice_speed")
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Voice Pitch", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(
                                String.format(Locale.US, "%.2fx", pitch),
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = pitch,
                            onValueChange = { pitch = it },
                            onValueChangeFinished = {
                                onUpdateVoiceParameters(speed, pitch, sliderVolume, null)
                            },
                            valueRange = 0.6f..1.6f,
                            colors = SliderDefaults.colors(
                                thumbColor = ElectricCyan,
                                activeTrackColor = ElectricCyan,
                                inactiveTrackColor = NavySurfaceElevated
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .testTag("slider_voice_pitch")
                        )
                    }
                }

                CompactSwitchRow(
                    title = "Natural Prosody & Breath Pauses",
                    subtitle = "Smooth 210ms breath pauses & Hindi/English intonation",
                    checked = settings.naturalProsodyEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateVoiceParameters(speed, pitch, sliderVolume, enabled)
                    },
                    testTag = "switch_natural_prosody"
                )
            }

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            // 4. Core AudioTrack & Automatic WAV Loudness Normalization Toggles
            CompactSwitchRow(
                title = "Independent AudioTrack PCM Pipeline",
                subtitle = "Synthesizes 16-bit WAV -> plays on ${audioDiagnostics.effectiveOsPolicyStream}",
                checked = settings.independentVolumeEnabled,
                onCheckedChange = { enabled ->
                    onUpdateIndependentVolumeSettings(enabled, null, null, null, null, null, null, null)
                },
                testTag = "switch_independent_volume"
            )

            CompactSwitchRow(
                title = "Automatic WAV Loudness Normalization & Limiter",
                subtitle = "Normalizes toward -13.5 dBFS RMS with Soft-Knee Tanh Limiter (-0.13 dBFS ceiling)",
                checked = settings.autoLoudnessNormalizationEnabled,
                onCheckedChange = { autoNorm ->
                    onUpdateIndependentVolumeSettings(null, null, null, null, null, null, autoNorm, null)
                },
                testTag = "switch_auto_loudness_normalization"
            )

            // Collapsible extra persistence toggles (Remember Last Gain & Apply to All)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showMoreVolumeOptions = !showMoreVolumeOptions }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showMoreVolumeOptions) "Hide Persistence Options" else "More Volume Options (Remember Gain & Apply to All)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Icon(
                    imageVector = if (showMoreVolumeOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle more volume options",
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = showMoreVolumeOptions) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactSwitchRow(
                        title = "Remember Last PCM Gain (${(settings.lastRememberedVoiceVolume * 100).roundToInt()}%)",
                        subtitle = "Persists volume across restarts & reboots",
                        checked = settings.rememberLastVoiceVolume,
                        onCheckedChange = { remember ->
                            onUpdateIndependentVolumeSettings(null, null, remember, null, null, null, null, null)
                        },
                        testTag = "switch_remember_last_volume"
                    )
                    CompactSwitchRow(
                        title = "Apply Settings to All Announcements",
                        subtitle = "Uses same routing & gain for Charger, Milestones, Test & Preview",
                        checked = settings.applyVolumeToAllAnnouncements,
                        onCheckedChange = { applyAll ->
                            onUpdateIndependentVolumeSettings(null, null, null, applyAll, null, null, null, null)
                        },
                        testTag = "switch_apply_to_all_announcements"
                    )
                }
            }

            // 5. Compact Test Active Voice & Charger Connected Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTestVoice,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("btn_volume_test_voice"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldCharge,
                        contentColor = Color(0xFF00210E)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Test Active Voice",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSpeaking) "Speaking..." else "Test Active Voice ($percentDisplay%)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = { onPreviewActiveVoice(settings.activePreset) },
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("btn_volume_preview_voice"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ElectricCyan),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = if (isSpeaking) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                        contentDescription = "Voice Preview",
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isSpeaking) {
                    OutlinedButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("btn_volume_stop_voice"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ThermalCrimson),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Voice",
                            tint = ThermalCrimson,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onTestChargerConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .testTag("btn_test_charger_connected"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Test Charger Connected",
                    tint = EmeraldCharge,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Test Charger Connected (Alarm Vol: ${audioDiagnostics.systemAlarmVolumePercent ?: "N/A"}% • Media Vol: ${audioDiagnostics.systemMediaVolumePercent ?: "N/A"}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldCharge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Compact Card grouping:
 * - Section 5: Smart Audio Focus & Media Ducking (OFF, LOW, MEDIUM, HIGH)
 * - Section 6: Voice Priority Queue & Repeat Policy
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartFocusAndQueueCard(
    settings: UserSettings,
    onUpdateIndependentVolumeSettings: (
        independentEnabled: Boolean?,
        volume: Float?,
        rememberLastVolume: Boolean?,
        applyToAllAnnouncements: Boolean?,
        mediaDuckingLevel: MediaDuckingLevel?,
        repeatPolicy: AnnouncementRepeatPolicy?,
        autoLoudnessNormalization: Boolean?,
        audioStreamUsageOption: AudioStreamUsageOption?
    ) -> Unit,
    onTestPriorityBurst: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("smart_focus_and_queue_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section 5: Smart Audio Focus & Media Ducking
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Smart Audio Focus & Media Ducking",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "SMART AUDIO FOCUS & MEDIA DUCKING",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = settings.mediaDuckingLevel.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MediaDuckingLevel.entries.forEach { level ->
                    val selected = settings.mediaDuckingLevel == level
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onUpdateIndependentVolumeSettings(null, null, null, null, level, null, null, null)
                        },
                        label = {
                            Text(
                                text = "Duck: ${level.label}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("chip_ducking_${level.name.lowercase(Locale.US)}")
                    )
                }
            }

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            // Section 6: Voice Priority Queue & Repeat Policy
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Voice Priority Queue",
                    tint = PurpleVoiceAccent,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "VOICE PRIORITY QUEUE & REPEAT POLICY",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "P1 Critical > P2 Important > P3 Normal (Non-overlapping queue)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnnouncementRepeatPolicy.entries.forEach { policy ->
                    val selected = settings.repeatPolicy == policy
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onUpdateIndependentVolumeSettings(null, null, null, null, null, policy, null, null)
                        },
                        label = {
                            Text(
                                text = policy.label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PurpleVoiceAccent.copy(alpha = 0.22f),
                            selectedLabelColor = PurpleVoiceAccent
                        ),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("chip_repeat_${policy.name.lowercase(Locale.US)}")
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = onTestPriorityBurst,
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("btn_test_priority_queue"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, PurpleVoiceAccent.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Test Queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = PurpleVoiceAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Section 9: Compact & Collapsible Android Audio Stream Routing Card.
 * Preserves full USAGE_ALARM (4) and STREAM_ALARM routing status and usage selector.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioStreamRoutingCard(
    settings: UserSettings,
    audioDiagnostics: AudioDiagnosticsState,
    onUpdateIndependentVolumeSettings: (
        independentEnabled: Boolean?,
        volume: Float?,
        rememberLastVolume: Boolean?,
        applyToAllAnnouncements: Boolean?,
        mediaDuckingLevel: MediaDuckingLevel?,
        repeatPolicy: AnnouncementRepeatPolicy?,
        autoLoudnessNormalization: Boolean?,
        audioStreamUsageOption: AudioStreamUsageOption?
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isMappedToMusic = audioDiagnostics.isMappedToStreamMusic

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_stream_routing_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, if (isMappedToMusic) AmberWarning.copy(alpha = 0.6f) else EmeraldCharge.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isMappedToMusic) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = "Android Audio Stream Routing",
                        tint = if (isMappedToMusic) AmberWarning else EmeraldCharge,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "ANDROID AUDIO STREAM ROUTING",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${audioDiagnostics.actualAudioAttributesUsage} -> ${audioDiagnostics.effectiveOsPolicyStream}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMappedToMusic) AmberWarning else EmeraldCharge
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand or collapse stream routing",
                    tint = ElectricCyan
                )
            }

            // Compact live routing summary bar (Always visible)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(1.dp, NavyCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stream_routing_verdict_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Alarm Vol: ${audioDiagnostics.systemAlarmVolumePercent ?: "N/A"}% • Media Vol: ${audioDiagnostics.systemMediaVolumePercent ?: "N/A"}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (audioDiagnostics.isControlledByAlarmVolume) "Controlled by Alarm: YES" else "Controlled by Alarm: NO",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (audioDiagnostics.isControlledByAlarmVolume) EmeraldCharge else AmberWarning,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = audioDiagnostics.observedRoutingBehaviorSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "Select Public AudioAttributes Usage:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AudioStreamUsageOption.entries.forEach { option ->
                            val isSelected = settings.audioStreamUsageOption == option
                            val optionAudit = audioDiagnostics.usageRoutingAudits.firstOrNull { it.option == option }
                            val mappedToMusic = optionAudit?.isMappedToStreamMusic
                                ?: (option == AudioStreamUsageOption.MEDIA_SPEECH || option == AudioStreamUsageOption.ACCESSIBILITY_SPEECH)
                            val chipColor = if (mappedToMusic) AmberWarning else EmeraldCharge

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    onUpdateIndependentVolumeSettings(
                                        true, null, null, null, null, null, null, option
                                    )
                                },
                                label = {
                                    Text(
                                        text = "${option.title} (${option.usageConstantName})",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = chipColor.copy(alpha = 0.22f),
                                    selectedLabelColor = chipColor
                                ),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("chip_usage_${option.name.lowercase(Locale.US)}")
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Section 10: Compact & Collapsible Audio Diagnostics and Volume Audit Card.
 * Shows essential USAGE_ALARM / STREAM_ALARM / Alarm Volume % summary at a glance,
 * and expands on demand to reveal the 35 technical metrics, OS Volume Group table,
 * and 3-Scenario Media vs. Alarm Volume Verification Matrix.
 */
@Composable
fun AudioDiagnosticsCard(
    diagnostics: AudioDiagnosticsState,
    onRefreshDiagnostics: () -> Unit,
    onRunQuickVoiceTest: () -> Unit,
    onRunQueueBurstTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_diagnostics_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Compact Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speaker,
                        contentDescription = "Audio Diagnostics & Volume Audit",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "AUDIO DIAGNOSTICS & VOLUME AUDIT",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${diagnostics.actualAudioAttributesUsage} • Alarm Vol: ${diagnostics.systemAlarmVolumePercent ?: "N/A"}% • State: ${diagnostics.audioTrackPlaybackState}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshDiagnostics,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("btn_refresh_audio_diagnostics")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Audio Diagnostics",
                            tint = ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand or collapse diagnostics",
                        tint = ElectricCyan
                    )
                }
            }

            if (diagnostics.lastPlaybackError != "None") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ThermalCrimson.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, ThermalCrimson),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("independent_playback_error_banner")
                ) {
                    Text(
                        text = diagnostics.lastPlaybackError,
                        style = MaterialTheme.typography.bodySmall,
                        color = ThermalCrimson,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Compact 4-pill key summary row always visible
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactDiagnosticPill(
                    label = "Usage",
                    value = diagnostics.actualAudioAttributesUsage,
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                CompactDiagnosticPill(
                    label = "Alarm Volume",
                    value = "${diagnostics.systemAlarmVolumePercent ?: "N/A"}%",
                    accent = EmeraldCharge,
                    modifier = Modifier.weight(1f)
                )
                CompactDiagnosticPill(
                    label = "Alarm Routed",
                    value = if (diagnostics.isControlledByAlarmVolume) "YES (STREAM_ALARM)" else "NO",
                    accent = if (diagnostics.isControlledByAlarmVolume) EmeraldCharge else AmberWarning,
                    modifier = Modifier.weight(1f)
                )
            }

            // Quick Test Actions + Expand/Collapse Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRunQuickVoiceTest,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .testTag("diag_btn_test_voice"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, EmeraldCharge),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = EmeraldCharge,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Stream", style = MaterialTheme.typography.labelSmall, color = EmeraldCharge)
                }

                OutlinedButton(
                    onClick = onRunQueueBurstTest,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .testTag("diag_btn_test_queue"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ElectricCyan),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Queue Burst", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                }

                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("btn_toggle_diagnostics_details")
                ) {
                    Text(
                        text = if (expanded) "Hide Details" else "Full Audit",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Collapsible Full Technical Audit
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val synthStatus = when (diagnostics.synthesizeToFileSucceeded) {
                        null -> "Awaiting First Utterance"
                        true -> "Yes (WAV Synthesized at 1.0f)"
                        false -> "FAILED (Direct TTS Disabled)"
                    }
                    val wavDecodeStatus = when (diagnostics.pcmWavDecoded) {
                        null -> "Awaiting First Utterance"
                        true -> "Yes (${diagnostics.decodedWavFormatSummary})"
                        false -> "FAILED"
                    }
                    val trackCreatedStatus = when (diagnostics.audioTrackCreated) {
                        null -> "Awaiting First Utterance"
                        true -> "Yes (STATE_INITIALIZED)"
                        false -> "FAILED"
                    }
                    val fallbackStatus = if (diagnostics.independentVolumeEnabled) {
                        "false (Disabled in Custom AudioTrack Mode)"
                    } else {
                        diagnostics.directTtsFallbackUsed.toString()
                    }
                    val controlledByAlarmLabel = if (diagnostics.isControlledByAlarmVolume) {
                        "YES — Controlled by System Alarm Volume (STREAM_ALARM)"
                    } else {
                        "NO — Routed to ${diagnostics.effectiveOsPolicyStream}"
                    }
                    val mediaChangesLoudnessLabel = if (diagnostics.doesMediaVolumeChangeLoudness) {
                        "YES — Mapped to STREAM_MUSIC"
                    } else {
                        "NO — Separate from STREAM_MUSIC (${diagnostics.controllingSystemSlider})"
                    }

                    val rows = listOf(
                        "Actual AudioAttributes Usage" to diagnostics.actualAudioAttributesUsage,
                        "Actual Content Type" to diagnostics.actualAudioAttributesContentType,
                        "AudioTrack Playback State" to diagnostics.audioTrackPlaybackState,
                        "Current Alarm Volume Percentage" to (diagnostics.systemAlarmVolumePercent?.let { "$it% (${diagnostics.systemAlarmVolumeSummary})" } ?: diagnostics.systemAlarmVolumeSummary),
                        "Controlled by Alarm Volume?" to controlledByAlarmLabel,
                        "System Media Volume (STREAM_MUSIC)" to (diagnostics.systemMediaVolumePercent?.let { "$it% (Read-Only)" } ?: "N/A"),
                        "Reported API volumeControlStream" to diagnostics.reportedVolumeControlStream,
                        "Effective OS AudioPolicy Stream" to diagnostics.effectiveOsPolicyStream,
                        "Controlling System Slider" to diagnostics.controllingSystemSlider,
                        "Does Media Volume Change Loudness?" to mediaChangesLoudnessLabel,
                        "Stream Routing Verdict" to diagnostics.streamIndependenceVerdictBadge,
                        "Volume-Group Summary" to diagnostics.volumeGroupSummary,
                        "App-Level PCM Gain" to diagnostics.appliedGainReport,
                        "Original / Final RMS" to "${diagnostics.originalRmsReport} -> ${diagnostics.finalRmsReport}",
                        "Original / Final Peak" to "${diagnostics.originalPeakReport} -> ${diagnostics.finalPeakReport}",
                        "Soft-Knee Limiter" to diagnostics.limiterStatusReport,
                        "AudioTrack Stream Gain" to "${String.format(Locale.US, "%.2f", diagnostics.audioTrackStreamGainValue)} (Unity)",
                        "synthesizeToFile() / WAV / Track" to "$synthStatus | $wavDecodeStatus | $trackCreatedStatus",
                        "Direct TTS.speak() Fallback" to fallbackStatus,
                        "Selected Voice & Engine" to "${diagnostics.selectedVoiceLabel} (${diagnostics.selectedTtsEnginePackage})",
                        "Last Traced Entry Point" to diagnostics.lastTraceEntryPoint,
                        "Audio Focus & Queue" to "${diagnostics.audioFocusState} • ${diagnostics.queueSummary}"
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = NavySurfaceElevated,
                        border = BorderStroke(1.dp, NavyCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            rows.forEachIndexed { index, (label, value) ->
                                if (index > 0) {
                                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.4f))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        modifier = Modifier.weight(0.42f)
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            label == "Controlled by Alarm Volume?" && diagnostics.isControlledByAlarmVolume -> EmeraldCharge
                                            label.startsWith("Actual AudioAttributes") -> ElectricCyan
                                            else -> TextPrimary
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(0.58f)
                                    )
                                }
                            }
                        }
                    }

                    // 3-Scenario Media vs. Alarm Volume Verification Matrix
                    if (diagnostics.alarmMediaTestMatrix.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NavySurfaceElevated,
                            border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("alarm_media_test_matrix_table")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "ALARM VS. MEDIA VOLUME 3-SCENARIO VERIFICATION MATRIX",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldCharge,
                                    fontWeight = FontWeight.Bold
                                )
                                diagnostics.alarmMediaTestMatrix.forEach { row ->
                                    val alarmDbStr = row.alarmAttenuationDb?.let {
                                        if (it <= -700f) "-∞ dB (Muted)" else String.format(Locale.US, "%.1f dB", it)
                                    } ?: "0.0 dB"
                                    val mediaDbStr = row.mediaAttenuationDb?.let {
                                        if (it <= -700f) "-∞ dB (Muted)" else String.format(Locale.US, "%.1f dB", it)
                                    } ?: "0.0 dB"
                                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.4f))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = row.scenarioLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ElectricCyan,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (row.verifiedLiveOnDevice) "LIVE VERIFIED" else "OS DB CURVE",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (row.verifiedLiveOnDevice) EmeraldCharge else TextSecondary
                                            )
                                        }
                                        Text(
                                            text = "STREAM_ALARM (${row.targetAlarmPercent}%): $alarmDbStr • STREAM_MUSIC (${row.targetMediaPercent}%): $mediaDbStr",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EmeraldCharge
                                        )
                                        Text(
                                            text = row.expectedAndObservedResult,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Live OS Volume Groups
                    if (diagnostics.osStreamSnapshots.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NavySurfaceElevated,
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("volume_group_table")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "DEVICE AUDIO VOLUME GROUPS (READ-ONLY)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                diagnostics.osStreamSnapshots.forEach { snap ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = snap.streamName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(0.46f)
                                        )
                                        Text(
                                            text = "${snap.volumePercent}% (${snap.currentIndex}/${snap.maxIndex} • ${snap.attenuationSummary})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (snap.volumePercent == 0 || snap.isMuted) AmberWarning else EmeraldCharge,
                                            modifier = Modifier.weight(0.54f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Compact OEM Disclosure
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = NavySurfaceElevated,
                        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "OEM Audio Notes",
                                tint = AmberWarning,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = diagnostics.osStreamRoutingLimitationNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactDiagnosticPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = NavySurfaceElevated,
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun CompactSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00210E),
                checkedTrackColor = EmeraldCharge,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = NavySurfaceElevated
            ),
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}
