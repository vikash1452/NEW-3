package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AudioDiagnosticsState
import com.example.model.AudioStreamUsageOption
import com.example.model.DefaultBossMessages
import com.example.model.MediaDuckingLevel
import com.example.model.OfflineVoiceModelPack
import com.example.model.UserSettings
import com.example.model.VoiceGender
import com.example.model.VoiceLanguage
import com.example.model.VoiceProfilePreset
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
import kotlin.math.roundToInt

@Composable
fun VoiceAndAlertsScreen(
    settings: UserSettings,
    voiceModelPacks: List<OfflineVoiceModelPack>,
    audioDiagnostics: AudioDiagnosticsState,
    isSpeaking: Boolean,
    previewingPreset: VoiceProfilePreset?,
    onSelectPreset: (VoiceProfilePreset) -> Unit,
    onPreviewPreset: (VoiceProfilePreset, String?) -> Unit,
    onStopSpeaking: () -> Unit,
    onUpdateLanguage: (VoiceLanguage) -> Unit,
    onUpdateGender: (VoiceGender) -> Unit,
    onUpdateVoiceParameters: (Float, Float, Float, Boolean?) -> Unit,
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
    onTestVoice: (String?) -> Unit,
    onTestChargerConnected: () -> Unit = { onTestVoice(null) },
    onTestPriorityBurst: () -> Unit,
    onRefreshAudioDiagnostics: () -> Unit,
    onInstallOrVerifyModel: (String) -> Unit,
    onUpdateChargingAlerts: (
        intervalPercent: Int?,
        intervalEnabled: Boolean?,
        smartMilestones: Boolean?,
        fullCharge: Boolean?,
        chargerConnected: Boolean?,
        chargerDisconnected: Boolean?,
        lowBattery: Boolean?,
        lowBatteryThreshold: Int?,
        tempWarning: Boolean?,
        tempWarningCelsius: Int?
    ) -> Unit,
    onUpdateChargerPercentToggles: (
        percentOnConnect: Boolean?,
        percentOnDisconnect: Boolean?
    ) -> Unit = { _, _ -> },
    onUpdateQuietHoursAndRinger: (
        quietHoursEnabled: Boolean?,
        quietStartHour: Int?,
        quietEndHour: Int?,
        respectSystemRinger: Boolean?
    ) -> Unit,
    onSaveCustomMessages: (Boolean, String, String, String, String, String, String, String, String, String) -> Unit,
    onResetCustomMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("voice_settings_screen"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1–4 & 11. Compact Acoustic Controls, Speech Rate, Voice Pitch, Announcement Volume / PCM Boost,
        // Automatic WAV Loudness Normalization & Limiter, and Test Active Voice
        item {
            IndependentVoiceVolumeCard(
                settings = settings,
                isSpeaking = isSpeaking,
                onLiveVolumeChange = onLiveVolumeChange,
                onUpdateIndependentVolumeSettings = onUpdateIndependentVolumeSettings,
                onTestVoice = { onTestVoice(null) },
                onPreviewActiveVoice = { preset -> onPreviewPreset(preset, null) },
                onTestChargerConnected = onTestChargerConnected,
                onTestPriorityBurst = onTestPriorityBurst,
                onStopSpeaking = onStopSpeaking,
                audioDiagnostics = audioDiagnostics,
                onUpdateVoiceParameters = onUpdateVoiceParameters
            )
        }

        // 7. Pro Voice Studio – 4 Neural Voice Profiles (Compact 2-Column Grid + Collapsible Side-by-Side Comparison Lab)
        item {
            VoiceStudioGalleryCard(
                activePreset = settings.activePreset,
                previewingPreset = previewingPreset,
                isSpeaking = isSpeaking,
                onSelectPreset = onSelectPreset,
                onPreviewPreset = { preset -> onPreviewPreset(preset, null) },
                onPreviewScenarioWithVoice = { preset, phrase -> onPreviewPreset(preset, phrase) },
                onStopSpeaking = onStopSpeaking
            )
        }

        // 5 & 6. Smart Audio Focus & Media Ducking + Voice Priority Queue & Repeat Policy
        item {
            SmartFocusAndQueueCard(
                settings = settings,
                onUpdateIndependentVolumeSettings = onUpdateIndependentVolumeSettings,
                onTestPriorityBurst = onTestPriorityBurst
            )
        }

        // 12, 13 & 14. Charger Connected / Disconnected, Battery Milestones, Low Battery & High Temp Warnings
        item {
            ChargingAnnouncementRulesCard(
                settings = settings,
                onUpdateChargingAlerts = onUpdateChargingAlerts,
                onUpdateChargerPercentToggles = onUpdateChargerPercentToggles
            )
        }

        // 15. Quiet Hours & System Ringer Mode Controls
        item {
            QuietHoursAndRingerCard(
                settings = settings,
                onUpdateQuietHoursAndRinger = onUpdateQuietHoursAndRinger
            )
        }

        // 16 & 17. Custom Announcements + Reset Defaults & Save All Phrases
        item {
            CustomMessagesEditorCard(
                settings = settings,
                onSaveCustomMessages = onSaveCustomMessages,
                onResetCustomMessages = onResetCustomMessages,
                onPreviewPhrase = { phrase -> onTestVoice(phrase) }
            )
        }

        // 8. Offline Neural Model Registry (Expandable Model Cards)
        item {
            OfflineVoiceModelManagerCard(
                voiceModelPacks = voiceModelPacks,
                selectedModelId = settings.selectedModelId,
                onInstallOrVerifyModel = onInstallOrVerifyModel,
                onOpenSystemTtsInstaller = {
                    runCatching {
                        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(installIntent)
                    }.onFailure {
                        runCatching {
                            val ttsSettingsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(ttsSettingsIntent)
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }

        // 9. Android Audio Stream Routing (Collapsible Card with USAGE_ALARM & STREAM_ALARM status)
        item {
            AudioStreamRoutingCard(
                settings = settings,
                audioDiagnostics = audioDiagnostics,
                onUpdateIndependentVolumeSettings = onUpdateIndependentVolumeSettings
            )
        }

        // 10. Audio Diagnostics & Volume Audit (Collapsible Card)
        item {
            AudioDiagnosticsCard(
                diagnostics = audioDiagnostics,
                onRefreshDiagnostics = onRefreshAudioDiagnostics,
                onRunQuickVoiceTest = { onTestVoice(null) },
                onRunQueueBurstTest = onTestPriorityBurst
            )
        }
    }
}

/**
 * Section 7: Pro Voice Studio – 4 Neural Voice Profiles in a compact 2-column grid,
 * plus an inline collapsible Side-by-Side 4-Voice Sample Comparison Lab.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceStudioGalleryCard(
    activePreset: VoiceProfilePreset,
    previewingPreset: VoiceProfilePreset?,
    isSpeaking: Boolean,
    onSelectPreset: (VoiceProfilePreset) -> Unit,
    onPreviewPreset: (VoiceProfilePreset) -> Unit,
    onPreviewScenarioWithVoice: (VoiceProfilePreset, String) -> Unit,
    onStopSpeaking: () -> Unit
) {
    var showComparisonLab by rememberSaveable { mutableStateOf(false) }
    val scenarios = DefaultBossMessages.comparisonScenarios
    var selectedScenario by remember { mutableStateOf(scenarios.first()) }
    val presets = VoiceProfilePreset.entries

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_studio_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = "Pro Voice Studio",
                        tint = EmeraldCharge,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "PRO VOICE STUDIO (4 NEURAL PROFILES)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "2-Column Offline Voice Grid • Tap to Preview or Select",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                if (isSpeaking) {
                    OutlinedButton(
                        onClick = onStopSpeaking,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ThermalCrimson),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Voice",
                            tint = ThermalCrimson,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", style = MaterialTheme.typography.labelSmall, color = ThermalCrimson)
                    }
                }
            }

            // 2-Column Grid for the 4 Neural Voice Profiles
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactVoiceProfileGridCell(
                        preset = presets[0],
                        isSelected = activePreset == presets[0],
                        isPreviewing = isSpeaking && previewingPreset == presets[0],
                        onSelect = { onSelectPreset(presets[0]) },
                        onPreview = { onPreviewPreset(presets[0]) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactVoiceProfileGridCell(
                        preset = presets[1],
                        isSelected = activePreset == presets[1],
                        isPreviewing = isSpeaking && previewingPreset == presets[1],
                        onSelect = { onSelectPreset(presets[1]) },
                        onPreview = { onPreviewPreset(presets[1]) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactVoiceProfileGridCell(
                        preset = presets[2],
                        isSelected = activePreset == presets[2],
                        isPreviewing = isSpeaking && previewingPreset == presets[2],
                        onSelect = { onSelectPreset(presets[2]) },
                        onPreview = { onPreviewPreset(presets[2]) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactVoiceProfileGridCell(
                        preset = presets[3],
                        isSelected = activePreset == presets[3],
                        isPreviewing = isSpeaking && previewingPreset == presets[3],
                        onSelect = { onSelectPreset(presets[3]) },
                        onPreview = { onPreviewPreset(presets[3]) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Collapsible Audio Sample Comparison Lab
            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showComparisonLab = !showComparisonLab }
                    .padding(vertical = 2.dp)
                    .testTag("audio_sample_comparison_card"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = "Audio Sample Comparison Lab",
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (showComparisonLab) "Hide 4-Voice Scenario Comparison Lab" else "Compare All 4 Voices Side-by-Side",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
                val labChevronDeg by animateFloatAsState(
                    targetValue = if (showComparisonLab) 180f else 0f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "comparison_lab_chevron"
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "Toggle Comparison Lab",
                    tint = ElectricCyan,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = labChevronDeg }
                )
            }

            AnimatedVisibility(
                visible = showComparisonLab,
                enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        scenarios.forEach { scenario ->
                            val isSelected = selectedScenario.id == scenario.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedScenario = scenario },
                                label = {
                                    Text(
                                        text = "${scenario.badge} • ${scenario.title}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = ElectricCyan
                                ),
                                modifier = Modifier
                                    .height(26.dp)
                                    .testTag("compare_scenario_${scenario.id}")
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = NavySurfaceElevated,
                        border = BorderStroke(1.dp, NavyCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "HI: ${selectedScenario.hindiText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onPreviewScenarioWithVoice(
                                            VoiceProfilePreset.HINDI_FEMALE,
                                            selectedScenario.hindiText
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                        .testTag("compare_play_hindi_female"),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("Hindi Female", style = MaterialTheme.typography.labelSmall, color = EmeraldCharge)
                                }
                                OutlinedButton(
                                    onClick = {
                                        onPreviewScenarioWithVoice(
                                            VoiceProfilePreset.HINDI_MALE,
                                            selectedScenario.hindiText
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                        .testTag("compare_play_hindi_male"),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("Hindi Male", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                                }
                            }

                            Text(
                                text = "EN: ${selectedScenario.englishText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onPreviewScenarioWithVoice(
                                            VoiceProfilePreset.ENGLISH_FEMALE,
                                            selectedScenario.englishText
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                        .testTag("compare_play_english_female"),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("English Female", style = MaterialTheme.typography.labelSmall, color = EmeraldCharge)
                                }
                                OutlinedButton(
                                    onClick = {
                                        onPreviewScenarioWithVoice(
                                            VoiceProfilePreset.ENGLISH_MALE,
                                            selectedScenario.englishText
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                        .testTag("compare_play_english_male"),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("English Male", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactVoiceProfileGridCell(
    preset: VoiceProfilePreset,
    isSelected: Boolean,
    isPreviewing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cellContainerColor by animateColorAsState(
        targetValue = if (isSelected) EmeraldCharge.copy(alpha = 0.15f) else NavySurfaceElevated,
        animationSpec = tween(250),
        label = "voice_cell_bg_${preset.id}"
    )
    val cellBorderColor by animateColorAsState(
        targetValue = when {
            isPreviewing -> ElectricCyan
            isSelected -> EmeraldCharge
            else -> NavyCardBorder
        },
        animationSpec = tween(250),
        label = "voice_cell_border_${preset.id}"
    )
    val cellScale by animateFloatAsState(
        targetValue = if (isPreviewing) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "voice_cell_scale_${preset.id}"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cellContainerColor,
        border = BorderStroke(
            width = if (isSelected || isPreviewing) 1.5.dp else 1.dp,
            color = cellBorderColor
        ),
        modifier = modifier.graphicsLayer {
            scaleX = cellScale
            scaleY = cellScale
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) EmeraldCharge else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = preset.personaName,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isSelected) {
                    Surface(
                        color = EmeraldCharge,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "ON",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00210E),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = preset.voiceDescription,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onPreview,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .testTag("studio_preview_${preset.id}"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                        contentDescription = "Preview ${preset.title}",
                        tint = ElectricCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (isPreviewing) "Play..." else "Listen",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onSelect,
                    enabled = !isSelected,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .testTag("studio_select_${preset.id}"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldCharge,
                        contentColor = Color(0xFF00210E),
                        disabledContainerColor = NavySurface,
                        disabledContentColor = EmeraldCharge
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (isSelected) "Active" else "Select",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Section 8: Compact & Expandable Offline Neural Model Registry Card.
 * Displays Model ID, Sample Rate, and File Size cleanly on a single line, and expands
 * individual model rows on tap to show Voice Binding & License details.
 */
@Composable
private fun OfflineVoiceModelManagerCard(
    voiceModelPacks: List<OfflineVoiceModelPack>,
    selectedModelId: String,
    onInstallOrVerifyModel: (String) -> Unit,
    onOpenSystemTtsInstaller: () -> Unit
) {
    var expandedModelId by rememberSaveable { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("offline_model_registry_card"),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsVoice,
                    contentDescription = "Offline Neural Model Registry",
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OFFLINE NEURAL MODEL REGISTRY",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Piper VITS / Sherpa-ONNX • Tap a model row for binding & license info",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            voiceModelPacks.forEach { pack ->
                val isSelected = pack.modelId == selectedModelId
                val isExpanded = expandedModelId == pack.modelId

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) NavySurfaceElevated else Color(0xFF0B111E),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) ElectricCyan else NavyCardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedModelId = if (isExpanded) null else pack.modelId
                            onInstallOrVerifyModel(pack.modelId)
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pack.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) ElectricCyan else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${pack.modelId} • ${pack.sampleRateHz} Hz • ${pack.sizeMb} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val badgeColor = when (pack.state) {
                                    com.example.model.VoiceModelState.BUNDLED_READY -> EmeraldCharge
                                    com.example.model.VoiceModelState.INSTALLED_LOCAL -> ElectricCyan
                                    com.example.model.VoiceModelState.INSTALLING -> PurpleVoiceAccent
                                    com.example.model.VoiceModelState.SYSTEM_VOICE_MISSING -> AmberWarning
                                }
                                Surface(
                                    color = badgeColor.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = pack.state.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                val modelChevronDeg by animateFloatAsState(
                                    targetValue = if (isExpanded) 180f else 0f,
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                    label = "model_chevron_${pack.modelId}"
                                )
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = "Expand model details",
                                    tint = TextSecondary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .graphicsLayer { rotationZ = modelChevronDeg }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                                Text(
                                    text = "Voice Binding: ${pack.systemOfflineVoiceMatched ?: "Local Neural Acoustic Engine"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                                Text(
                                    text = "License: ${pack.licenseName} (${pack.licenseAttribution})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onOpenSystemTtsInstaller,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .testTag("open_system_tts_button"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Manage Android Offline Voice Data",
                    tint = ElectricCyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CHECK / INSTALL SYSTEM OFFLINE TTS VOICES",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Sections 12, 13 & 14: Compact Card for:
 * - 12. Charger Connected / Disconnected & Full Charge (100%) Announcements
 * - 13. Battery Milestone Announcements (20%, 50%, 80% + Custom Interval)
 * - 14. Low Battery & High Temperature Warnings
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargingAnnouncementRulesCard(
    settings: UserSettings,
    onUpdateChargingAlerts: (
        intervalPercent: Int?,
        intervalEnabled: Boolean?,
        smartMilestones: Boolean?,
        fullCharge: Boolean?,
        chargerConnected: Boolean?,
        chargerDisconnected: Boolean?,
        lowBattery: Boolean?,
        lowBatteryThreshold: Int?,
        tempWarning: Boolean?,
        tempWarningCelsius: Int?
    ) -> Unit,
    onUpdateChargerPercentToggles: (
        percentOnConnect: Boolean?,
        percentOnDisconnect: Boolean?
    ) -> Unit = { _, _ -> }
) {
    var customIntervalSlider by remember(settings.announceIntervalPercent) {
        mutableFloatStateOf(settings.announceIntervalPercent.toFloat())
    }
    var lowBatterySlider by remember(settings.lowBatteryThreshold) {
        mutableFloatStateOf(settings.lowBatteryThreshold.toFloat())
    }
    var tempThresholdSlider by remember(settings.temperatureWarningCelsius) {
        mutableFloatStateOf(settings.temperatureWarningCelsius.toFloat())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("charging_announcement_rules_card"),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = "Charging Announcements & Warnings",
                    tint = EmeraldCharge,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "CHARGER ALERTS, MILESTONES & WARNINGS",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Connect/Disconnect, 20%/50%/80%/100%, Low Battery & Thermal Alerts",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // Section 12: Charger Connected / Disconnected & Full Charge (100%)
            CompactSwitchRow(
                title = "Charger Connected Announcement",
                subtitle = "Speaks immediately when AC, USB, or Wireless power connects",
                checked = settings.announceChargerConnected,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, it, null, null, null, null, null)
                }
            )
            CompactSwitchRow(
                title = "Announce Battery Percentage on Charger Connect",
                subtitle = "Speaks live battery % immediately when charger is connected",
                checked = settings.announceBatteryPercentOnConnect,
                onCheckedChange = {
                    onUpdateChargerPercentToggles(it, null)
                },
                testTag = "switch_announce_percent_on_connect"
            )
            CompactSwitchRow(
                title = "Charger Disconnected Announcement",
                subtitle = "Speaks when power cable is unplugged",
                checked = settings.announceChargerDisconnected,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, null, it, null, null, null, null)
                }
            )
            CompactSwitchRow(
                title = "Announce Battery Percentage on Charger Disconnect",
                subtitle = "Speaks remaining battery % immediately when charger is unplugged",
                checked = settings.announceBatteryPercentOnDisconnect,
                onCheckedChange = {
                    onUpdateChargerPercentToggles(null, it)
                },
                testTag = "switch_announce_percent_on_disconnect"
            )
            CompactSwitchRow(
                title = "Full Charge (100%) Announcement",
                subtitle = "Alerts when battery reaches 100% full capacity",
                checked = settings.announceFullCharge,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, it, null, null, null, null, null, null)
                }
            )

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            // Section 13: Battery Milestone Announcements
            CompactSwitchRow(
                title = "Smart Milestones (20%, 50%, 80%)",
                subtitle = "Natural milestone briefings during charging",
                checked = settings.announceSmartMilestones,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, it, null, null, null, null, null, null, null)
                }
            )

            CompactSwitchRow(
                title = "Announce Every ${settings.announceIntervalPercent}% While Charging",
                subtitle = "Select 5%, 10%, 15%, 20%, 25% or custom step",
                checked = settings.announceIntervalEnabled,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, it, null, null, null, null, null, null, null, null)
                }
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(5, 10, 15, 20, 25).forEach { step ->
                    val selected = settings.announceIntervalPercent == step
                    FilterChip(
                        selected = selected,
                        onClick = {
                            customIntervalSlider = step.toFloat()
                            onUpdateChargingAlerts(step, true, null, null, null, null, null, null, null, null)
                        },
                        label = {
                            Text(
                                text = "Every $step%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldCharge.copy(alpha = 0.2f),
                            selectedLabelColor = EmeraldCharge
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Custom Interval Step", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        "${customIntervalSlider.roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldCharge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = customIntervalSlider,
                    onValueChange = { customIntervalSlider = it },
                    onValueChangeFinished = {
                        onUpdateChargingAlerts(
                            customIntervalSlider.roundToInt(),
                            true, null, null, null, null, null, null, null, null
                        )
                    },
                    valueRange = 1f..50f,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldCharge,
                        activeTrackColor = EmeraldCharge,
                        inactiveTrackColor = NavySurfaceElevated
                    ),
                    modifier = Modifier.height(26.dp)
                )
            }

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            // Section 14: Low Battery & High Temperature Warnings
            CompactSwitchRow(
                title = "Low Battery Warning (${lowBatterySlider.roundToInt()}%)",
                subtitle = "Warns when battery drops below threshold while discharging",
                checked = settings.announceLowBattery,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, null, null, it, null, null, null)
                }
            )
            Slider(
                value = lowBatterySlider,
                onValueChange = { lowBatterySlider = it },
                onValueChangeFinished = {
                    onUpdateChargingAlerts(
                        null, null, null, null, null, null, null,
                        lowBatterySlider.roundToInt(),
                        null, null
                    )
                },
                valueRange = 5f..35f,
                colors = SliderDefaults.colors(
                    thumbColor = AmberWarning,
                    activeTrackColor = AmberWarning,
                    inactiveTrackColor = NavySurfaceElevated
                ),
                modifier = Modifier.height(26.dp)
            )

            CompactSwitchRow(
                title = "High Temperature Warning (${tempThresholdSlider.roundToInt()}°C)",
                subtitle = "Critical thermal alert when battery exceeds threshold",
                checked = settings.announceTemperatureWarning,
                onCheckedChange = {
                    onUpdateChargingAlerts(null, null, null, null, null, null, null, null, it, null)
                }
            )
            Slider(
                value = tempThresholdSlider,
                onValueChange = { tempThresholdSlider = it },
                onValueChangeFinished = {
                    onUpdateChargingAlerts(
                        null, null, null, null, null, null, null, null, null,
                        tempThresholdSlider.roundToInt()
                    )
                },
                valueRange = 36f..50f,
                colors = SliderDefaults.colors(
                    thumbColor = AmberWarning,
                    activeTrackColor = AmberWarning,
                    inactiveTrackColor = NavySurfaceElevated
                ),
                modifier = Modifier.height(26.dp)
            )
        }
    }
}

/**
 * Section 15: Compact Quiet Hours & Ringer Mode Card.
 */
@Composable
private fun QuietHoursAndRingerCard(
    settings: UserSettings,
    onUpdateQuietHoursAndRinger: (
        quietHoursEnabled: Boolean?,
        quietStartHour: Int?,
        quietEndHour: Int?,
        respectSystemRinger: Boolean?
    ) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quiet_hours_card"),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NightlightRound,
                    contentDescription = "Quiet Hours & Ringer Mode",
                    tint = PurpleVoiceAccent,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "QUIET HOURS & RINGER MODE",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Nighttime sleep schedule & system Silent/Vibrate rule",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            CompactSwitchRow(
                title = "Nighttime Quiet Hours (${formatHour(settings.quietStartHour)} – ${formatHour(settings.quietEndHour)})",
                subtitle = "Mute automatic alerts during sleeping hours",
                checked = settings.quietHoursEnabled,
                onCheckedChange = {
                    onUpdateQuietHoursAndRinger(it, null, null, null)
                }
            )

            AnimatedVisibility(visible = settings.quietHoursEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start: ${formatHour(settings.quietStartHour)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Slider(
                            value = settings.quietStartHour.toFloat(),
                            onValueChange = {
                                onUpdateQuietHoursAndRinger(null, it.roundToInt(), null, null)
                            },
                            valueRange = 0f..23f,
                            steps = 22,
                            modifier = Modifier.height(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "End: ${formatHour(settings.quietEndHour)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Slider(
                            value = settings.quietEndHour.toFloat(),
                            onValueChange = {
                                onUpdateQuietHoursAndRinger(null, null, it.roundToInt(), null)
                            },
                            valueRange = 0f..23f,
                            steps = 22,
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
            }

            CompactSwitchRow(
                title = "Respect System Silent / Vibrate Mode",
                subtitle = "Mute automatic alerts when phone ringer is on Silent or Vibrate",
                checked = settings.respectSystemRinger,
                onCheckedChange = {
                    onUpdateQuietHoursAndRinger(null, null, null, it)
                }
            )
        }
    }
}

/**
 * Sections 16 & 17: Compact Custom Announcements Editor + Reset Defaults & Save All Phrases.
 */
@Composable
private fun CustomMessagesEditorCard(
    settings: UserSettings,
    onSaveCustomMessages: (Boolean, String, String, String, String, String, String, String, String, String) -> Unit,
    onResetCustomMessages: () -> Unit,
    onPreviewPhrase: (String) -> Unit
) {
    val isHindi = settings.language == VoiceLanguage.HINDI

    var connectedText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiConnected else settings.msgEnConnected)
    }
    var milestone20Text by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHi20Percent else settings.msgEn20Percent)
    }
    var milestone50Text by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHi50Percent else settings.msgEn50Percent)
    }
    var milestone80Text by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHi80Percent else settings.msgEn80Percent)
    }
    var intervalText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiInterval else settings.msgEnInterval)
    }
    var fullText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiFull else settings.msgEnFull)
    }
    var highTempText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiHighTemp else settings.msgEnHighTemp)
    }
    var disconnectedText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiDisconnected else settings.msgEnDisconnected)
    }
    var lowText by remember(isHindi, settings) {
        mutableStateOf(if (isHindi) settings.msgHiLowBattery else settings.msgEnLowBattery)
    }
    var savedFeedback by remember { mutableStateOf(false) }
    var showAllPhrases by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(savedFeedback) {
        if (savedFeedback) {
            kotlinx.coroutines.delay(2000)
            savedFeedback = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("custom_messages_editor_card"),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAllPhrases = !showAllPhrases },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = "Custom Announcements",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "CUSTOM ANNOUNCEMENTS (${settings.language.displayName.uppercase(Locale.US)})",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Use {level} for % and {temp} for °C • Tap speaker to preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = if (showAllPhrases) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand or collapse custom phrases",
                    tint = ElectricCyan
                )
            }

            AnimatedVisibility(visible = showAllPhrases) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactPhraseField(
                        label = "Charger Connected ({level}%)",
                        value = connectedText,
                        onValueChange = { connectedText = it },
                        onPreview = { onPreviewPhrase(connectedText.replace("{level}", "45").replace("{temp}", "35")) }
                    )
                    CompactPhraseField(
                        label = "Charger Disconnected ({level}%)",
                        value = disconnectedText,
                        onValueChange = { disconnectedText = it },
                        onPreview = { onPreviewPhrase(disconnectedText.replace("{level}", "85").replace("{temp}", "35")) }
                    )
                    CompactPhraseField(
                        label = "Battery 20% Milestone",
                        value = milestone20Text,
                        onValueChange = { milestone20Text = it },
                        onPreview = { onPreviewPhrase(milestone20Text) }
                    )
                    CompactPhraseField(
                        label = "Battery 50% Halfway Milestone",
                        value = milestone50Text,
                        onValueChange = { milestone50Text = it },
                        onPreview = { onPreviewPhrase(milestone50Text) }
                    )
                    CompactPhraseField(
                        label = "Battery 80% Milestone",
                        value = milestone80Text,
                        onValueChange = { milestone80Text = it },
                        onPreview = { onPreviewPhrase(milestone80Text) }
                    )
                    CompactPhraseField(
                        label = "Battery 100% Full Charge",
                        value = fullText,
                        onValueChange = { fullText = it },
                        onPreview = { onPreviewPhrase(fullText) }
                    )
                    CompactPhraseField(
                        label = "Custom Interval ({level}%)",
                        value = intervalText,
                        onValueChange = { intervalText = it },
                        onPreview = { onPreviewPhrase(intervalText.replace("{level}", "60")) }
                    )
                    CompactPhraseField(
                        label = "Low Battery Warning ({level}%)",
                        value = lowText,
                        onValueChange = { lowText = it },
                        onPreview = { onPreviewPhrase(lowText.replace("{level}", "15")) }
                    )
                    CompactPhraseField(
                        label = "High Temperature Warning ({temp}°C)",
                        value = highTempText,
                        onValueChange = { highTempText = it },
                        onPreview = { onPreviewPhrase(highTempText.replace("{temp}", "43")) }
                    )
                }
            }

            // Section 17: Reset Defaults and Save All Phrases
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onResetCustomMessages,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("reset_custom_messages_button"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset Defaults",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset Defaults", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = {
                        onSaveCustomMessages(
                            isHindi,
                            connectedText,
                            disconnectedText,
                            milestone20Text,
                            milestone50Text,
                            milestone80Text,
                            intervalText,
                            fullText,
                            lowText,
                            highTempText
                        )
                        savedFeedback = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("save_custom_messages_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldCharge,
                        contentColor = Color(0xFF00210E)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save All Phrases",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (savedFeedback) "Saved!" else "Save All Phrases",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactPhraseField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPreview: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
        trailingIcon = {
            IconButton(
                onClick = onPreview,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Preview $label",
                    tint = EmeraldCharge,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = EmeraldCharge,
            unfocusedBorderColor = NavyCardBorder,
            focusedLabelColor = EmeraldCharge,
            unfocusedLabelColor = TextSecondary
        )
    )
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    CompactSwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

private fun formatHour(hour24: Int): String {
    val h = hour24.coerceIn(0, 23)
    val amPm = if (h < 12) "AM" else "PM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$h12:00 $amPm"
}
