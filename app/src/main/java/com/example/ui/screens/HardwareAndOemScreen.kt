package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BatteryHardwareSnapshot
import com.example.model.OemOptimizationGuide
import com.example.model.UserSettings
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

import com.example.model.AudioDiagnosticsState
import com.example.model.BackgroundMonitoringMode
import com.example.model.NotificationChannelDiagnostics
import com.example.service.BatteryMonitorService

@Composable
fun HardwareAndOemScreen(
    snapshot: BatteryHardwareSnapshot,
    settings: UserSettings,
    audioDiagnostics: AudioDiagnosticsState,
    notificationChannelDiagnostics: NotificationChannelDiagnostics = NotificationChannelDiagnostics(),
    isServiceRunning: Boolean,
    isForegroundNotificationVisible: Boolean = false,
    isSilentBackgroundActive: Boolean = false,
    isJobSchedulerRegistered: Boolean = false,
    isIgnoringBatteryOptimizations: Boolean,
    oemGuide: OemOptimizationGuide,
    onToggleMonitoring: (Boolean) -> Unit,
    onSelectMonitoringMode: (BackgroundMonitoringMode) -> Unit = {},
    onToggleSilentNotification: (Boolean) -> Unit = {},
    onToggleVoiceAnnouncements: (Boolean) -> Unit = {},
    onTriggerNotificationAction: (String) -> Unit = {},
    onUpdateBackgroundSettings: (Boolean?, Boolean?) -> Unit,
    onRefreshOptimizationState: () -> Unit,
    onRunQuickVoiceTest: () -> Unit,
    onRunQueueBurstTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("hardware_oem_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Background Operation, Dual Notification Channels & 3 Independent Controls
        item {
            BackgroundServiceControlCard(
                snapshot = snapshot,
                settings = settings,
                channelDiagnostics = notificationChannelDiagnostics,
                isServiceRunning = isServiceRunning,
                isForegroundNotificationVisible = isForegroundNotificationVisible,
                isSilentBackgroundActive = isSilentBackgroundActive,
                isJobSchedulerRegistered = isJobSchedulerRegistered,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                onToggleMonitoring = onToggleMonitoring,
                onSelectMonitoringMode = onSelectMonitoringMode,
                onToggleSilentNotification = onToggleSilentNotification,
                onToggleVoiceAnnouncements = onToggleVoiceAnnouncements,
                onTriggerNotificationAction = onTriggerNotificationAction,
                onUpdateBackgroundSettings = onUpdateBackgroundSettings,
                onOpenNotificationChannelSettings = {
                    runCatching {
                        val intent = BatteryMonitorService.buildChannelNotificationSettingsIntent(
                            context = context,
                            silentChannel = settings.silentNotificationEnabled
                        )
                        context.startActivity(intent)
                    }.onFailure {
                        runCatching {
                            val appNotif = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(appNotif)
                        }
                    }
                },
                onOpenBatteryOptimizationSettings = {
                    runCatching {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }.onFailure {
                        runCatching {
                            val appDetails = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(appDetails)
                        }
                    }
                    onRefreshOptimizationState()
                },
                onOpenAppDetails = {
                    runCatching {
                        val appDetails = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(appDetails)
                    }
                }
            )
        }

        // 2. Voice & Audio System Diagnostics (Section 10)
        item {
            AudioDiagnosticsCard(
                diagnostics = audioDiagnostics,
                onRefreshDiagnostics = onRefreshOptimizationState,
                onRunQuickVoiceTest = onRunQuickVoiceTest,
                onRunQueueBurstTest = onRunQueueBurstTest
            )
        }

        // 3. Detected OEM Background Survival Guide
        item {
            OemSurvivalGuideCard(oemGuide = oemGuide)
        }

        // 4. Complete Raw BatteryManager Hardware Diagnostics Table
        item {
            HardwareDiagnosticsTableCard(snapshot = snapshot)
        }
    }
}

@Composable
private fun BackgroundServiceControlCard(
    snapshot: BatteryHardwareSnapshot,
    settings: UserSettings,
    channelDiagnostics: NotificationChannelDiagnostics,
    isServiceRunning: Boolean,
    isForegroundNotificationVisible: Boolean,
    isSilentBackgroundActive: Boolean,
    isJobSchedulerRegistered: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    onSelectMonitoringMode: (BackgroundMonitoringMode) -> Unit,
    onToggleSilentNotification: (Boolean) -> Unit,
    onToggleVoiceAnnouncements: (Boolean) -> Unit,
    onTriggerNotificationAction: (String) -> Unit,
    onUpdateBackgroundSettings: (Boolean?, Boolean?) -> Unit,
    onOpenNotificationChannelSettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    val activeMode = settings.backgroundMonitoringMode
    val isSilentSelected = settings.silentNotificationEnabled || activeMode == BackgroundMonitoringMode.SILENT_BACKGROUND

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("background_service_control_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Background Service Controls",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "BATTERY BOSS BACKGROUND & VOICE CONTROLS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "specialUse Foreground Service • Dual Notification Channels • Screen-Off Voice",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Start / Stop Monitoring Action Button
            Button(
                onClick = { onToggleMonitoring(!isServiceRunning) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("start_stop_service_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) ThermalCrimson else EmeraldCharge,
                    contentColor = if (isServiceRunning) Color.White else Color(0xFF00210E)
                )
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceRunning) "STOP BACKGROUND MONITORING" else "START BACKGROUND MONITORING",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Compact & Silent Notification Preview Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("background_monitoring_mode_selector_card")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "COMPACT FOREGROUND NOTIFICATION STATUS",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldCharge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Battery Boss Active • ${snapshot.percentage}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("compact_notification_headline_preview")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onToggleSilentNotification(true)
                                onSelectMonitoringMode(BackgroundMonitoringMode.SILENT_BACKGROUND)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("mode_silent_background_card"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSilentSelected) EmeraldCharge.copy(alpha = 0.18f) else Color.Transparent
                            ),
                            border = BorderStroke(
                                width = if (isSilentSelected) 1.5.dp else 1.dp,
                                color = if (isSilentSelected) EmeraldCharge else NavyCardBorder
                            )
                        ) {
                            Text(
                                text = "Silent Channel\n(battery_boss_silent_channel)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSilentSelected) EmeraldCharge else TextSecondary,
                                fontWeight = if (isSilentSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                onToggleSilentNotification(false)
                                onSelectMonitoringMode(BackgroundMonitoringMode.CONTINUOUS_FOREGROUND)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("mode_continuous_foreground_card"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (!isSilentSelected) ElectricCyan.copy(alpha = 0.18f) else Color.Transparent
                            ),
                            border = BorderStroke(
                                width = if (!isSilentSelected) 1.5.dp else 1.dp,
                                color = if (!isSilentSelected) ElectricCyan else NavyCardBorder
                            )
                        ) {
                            Text(
                                text = "Standard Channel\n(battery_boss_standard_channel)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!isSilentSelected) ElectricCyan else TextSecondary,
                                fontWeight = if (!isSilentSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    Text(
                        text = "Active Channel: ${channelDiagnostics.activeChannelId} • OS Importance: ${channelDiagnostics.activeImportanceLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (channelDiagnostics.isActiveChannelBlockedByUser) {
                            ThermalCrimson
                        } else if (isSilentSelected) {
                            EmeraldCharge
                        } else {
                            ElectricCyan
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("active_monitoring_mode_label")
                    )

                    Text(
                        text = "FGS Active: ${if (isServiceRunning) "Yes (specialUse)" else "No"} • Starts: ${channelDiagnostics.serviceInstanceCreateCount} • In-Place Reposts: ${channelDiagnostics.inPlaceNotificationUpdatesCount} • JobScheduler: ${if (isJobSchedulerRegistered) "ID 4021" else "Standby"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )

                    Text(
                        text = "OS Registered Channels -> Silent: ${channelDiagnostics.importanceToLabel(channelDiagnostics.silentChannelOsImportance)} | Standard: ${channelDiagnostics.importanceToLabel(channelDiagnostics.standardChannelOsImportance)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.testTag("os_notification_channels_summary")
                    )

                    if (channelDiagnostics.isActiveChannelBlockedByUser || !channelDiagnostics.areAppNotificationsEnabledOnOs) {
                        Text(
                            text = "User Override Detected: Android System Notification Settings currently block notifications for this channel/app. Battery Boss respects your OS choice and will never bypass it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = ThermalCrimson,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("channel_blocked_warning_text")
                        )
                    }

                    Text(
                        text = activeMode.reliabilityAndLimitationsNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.testTag("monitoring_mode_limitations_note")
                    )
                }
            }

            // 1. Background Monitoring ON/OFF
            SettingSwitchRow(
                title = "1. Background Monitoring ON/OFF",
                subtitle = if (settings.backgroundMonitoringEnabled && isServiceRunning) {
                    "ON: BatteryMonitorService running as Foreground Service (Screen-Off Charger & Battery events active)"
                } else {
                    "OFF: BatteryMonitorService stopped, WakeLocks released & Notification removed"
                },
                checked = settings.backgroundMonitoringEnabled && isServiceRunning,
                onCheckedChange = { onToggleMonitoring(it) }
            )

            // 2. Background Voice ON/OFF
            SettingSwitchRow(
                title = "2. Background Voice ON/OFF",
                subtitle = if (settings.voiceAnnouncementsEnabled) {
                    "ON: Speaks Charger Connect/Disconnect %, Milestone, Low Battery & Thermal alerts (even when Screen is OFF)"
                } else {
                    "OFF (Voice Muted Only): Background Voice Announcements muted; Background Monitoring stays ON"
                },
                checked = settings.voiceAnnouncementsEnabled,
                onCheckedChange = { onToggleVoiceAnnouncements(it) }
            )

            // 3. Silent Notification Channel ON/OFF (reposting between two immutable channels)
            SettingSwitchRow(
                title = "3. Silent Notification Channel (battery_boss_silent_channel)",
                subtitle = if (isSilentSelected) {
                    "ON -> Reposts Notification ID 4010 onto 'battery_boss_silent_channel' (created at IMPORTANCE_MIN, 0 buttons) without restarting Service; respects System Settings"
                } else {
                    "OFF -> Reposts Notification ID 4010 onto 'battery_boss_standard_channel' (created at IMPORTANCE_LOW + buttons) without restarting Service"
                },
                checked = isSilentSelected,
                onCheckedChange = { silentOn ->
                    onToggleSilentNotification(silentOn)
                    onSelectMonitoringMode(
                        if (silentOn) BackgroundMonitoringMode.SILENT_BACKGROUND
                        else BackgroundMonitoringMode.CONTINUOUS_FOREGROUND
                    )
                }
            )

            SettingSwitchRow(
                title = "Start Automatically After Reboot",
                subtitle = "Resumes ${activeMode.title} on BOOT_COMPLETED when Background Monitoring is ON",
                checked = settings.startOnBootEnabled,
                onCheckedChange = { onUpdateBackgroundSettings(it, null) }
            )

            // Notification Action Controls Preview / Direct Trigger Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notification_actions_control_panel")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "QUICK MONITORING & VOICE ACTIONS",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Trigger Monitoring & Voice controls immediately (also available in Continuous Mode notification):",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onTriggerNotificationAction(com.example.service.BatteryMonitorService.ACTION_START_MONITORING)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("btn_notif_action_start"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("START", style = MaterialTheme.typography.labelSmall, color = EmeraldCharge)
                        }
                        OutlinedButton(
                            onClick = {
                                onTriggerNotificationAction(com.example.service.BatteryMonitorService.ACTION_STOP_MONITORING)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("btn_notif_action_stop"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("STOP", style = MaterialTheme.typography.labelSmall, color = ThermalCrimson)
                        }
                        OutlinedButton(
                            onClick = {
                                onTriggerNotificationAction(com.example.service.BatteryMonitorService.ACTION_SPEAK_NOW)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("btn_notif_action_speak"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("SPEAK STATUS", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onTriggerNotificationAction(com.example.service.BatteryMonitorService.ACTION_MUTE_VOICE)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("btn_notif_action_mute"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("MUTE VOICE", style = MaterialTheme.typography.labelSmall, color = AmberWarning)
                        }
                        OutlinedButton(
                            onClick = {
                                onTriggerNotificationAction(com.example.service.BatteryMonitorService.ACTION_RESUME_VOICE)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("btn_notif_action_resume"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("RESUME VOICE", style = MaterialTheme.typography.labelSmall, color = EmeraldCharge)
                        }
                        OutlinedButton(
                            onClick = onOpenNotificationChannelSettings,
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("btn_open_notif_channel_settings"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("NOTIF CHANNEL", style = MaterialTheme.typography.labelSmall, color = PurpleVoiceAccent)
                        }
                    }
                }
            }

            HorizontalDivider(color = NavyCardBorder)

            // Battery Optimization Exemption Status
            val optBorderColor by animateColorAsState(
                targetValue = if (isIgnoringBatteryOptimizations) EmeraldCharge.copy(alpha = 0.55f) else AmberWarning.copy(alpha = 0.65f),
                animationSpec = tween(280),
                label = "opt_border_color"
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(1.dp, optBorderColor)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isIgnoringBatteryOptimizations) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = "Optimization Status",
                            tint = if (isIgnoringBatteryOptimizations) EmeraldCharge else AmberWarning
                        )
                        Text(
                            text = if (isIgnoringBatteryOptimizations) {
                                "Battery Optimization: Unrestricted (Recommended)"
                            } else {
                                "Battery Optimization: Active (May pause background voice)"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isIgnoringBatteryOptimizations) EmeraldCharge else AmberWarning,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Android Doze and OEM battery savers can suspend background services when the screen is off. Set Battery Boss to 'Unrestricted' in system settings for reliable overnight charging alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenBatteryOptimizationSettings,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_battery_optimization_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatterySaver,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Optimization List", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = onOpenAppDetails,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_app_details_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("App Battery Info", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OemSurvivalGuideCard(oemGuide: OemOptimizationGuide) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = "OEM Guide",
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "DETECTED OEM: ${oemGuide.manufacturerName.uppercase(Locale.US)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Platform Profile: ${oemGuide.osSkinName} • Model: ${Build.MODEL}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }
            }

            oemGuide.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        color = ElectricCyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Surface(
                color = NavySurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, NavyCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Important OEM Note",
                        tint = PurpleVoiceAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = oemGuide.caveatNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareDiagnosticsTableCard(snapshot: BatteryHardwareSnapshot) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hardware_diagnostics_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeveloperBoard,
                    contentDescription = "Raw Hardware Sensors",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "BATTERY HARDWARE DIAGNOSTICS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Direct kernel & BatteryManager properties (Unreported sensors show N/A)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            val rows = listOf(
                "Battery Level" to "${snapshot.percentage}%",
                "Charging Status" to snapshot.statusLabel,
                "Power Source" to snapshot.chargingSource.displayName,
                "Battery Voltage" to (snapshot.voltageVolts?.let {
                    String.format(Locale.US, "%.3f V (%d mV)", it, snapshot.voltageMillivolts ?: 0)
                } ?: "N/A"),
                "Instant Current (CURRENT_NOW)" to (snapshot.currentMilliAmps?.let { "$it mA" } ?: "N/A"),
                "Average Current (CURRENT_AVG)" to (snapshot.averageCurrentMilliAmps?.let { "$it mA" } ?: "N/A"),
                "Charging / Discharge Power" to (snapshot.powerWatts?.let {
                    String.format(Locale.US, "%.2f W", abs(it))
                } ?: "N/A"),
                "Battery Temperature" to (snapshot.temperatureCelsius?.let {
                    String.format(Locale.US, "%.1f °C (%.1f °F)", it, it * 9f / 5f + 32f)
                } ?: "N/A"),
                "Battery Health" to snapshot.healthLabel,
                "Battery Chemistry / Tech" to (snapshot.technology ?: "N/A"),
                "Remaining Charge Counter" to (snapshot.chargeCounterMah?.let { "$it mAh" } ?: "N/A"),
                "Hardware Design Capacity" to (snapshot.designCapacityMah?.let { "$it mAh" } ?: "N/A"),
                "Estimated Time to Full" to (snapshot.estimatedMinutesToFull?.let { "$it min" } ?: "N/A"),
                "Battery Present" to if (snapshot.isBatteryPresent) "Yes" else "No",
                "Device Hardware" to "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
            )

            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (value == "N/A") TextMuted else TextPrimary
                    )
                }
            }
        }
    }
}
