package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BackgroundMonitoringMode
import com.example.model.BatteryHardwareSnapshot
import com.example.model.TelemetrySamplePoint
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

@Composable
fun DashboardScreen(
    snapshot: BatteryHardwareSnapshot,
    telemetryHistory: List<TelemetrySamplePoint>,
    settings: UserSettings,
    isServiceRunning: Boolean,
    isForegroundNotificationVisible: Boolean = false,
    isSpeaking: Boolean,
    engineStatusText: String,
    lastSpokenBanner: String?,
    onToggleMonitoring: (Boolean) -> Unit,
    onSelectMonitoringMode: (BackgroundMonitoringMode) -> Unit = {},
    onToggleSilentNotification: (Boolean) -> Unit = {},
    onToggleVoiceAnnouncements: (Boolean) -> Unit = {},
    onTriggerNotificationAction: (String) -> Unit = {},
    onSpeakStatusNow: () -> Unit,
    onStopSpeaking: () -> Unit,
    onOpenHealthScreen: () -> Unit,
    onRefreshTelemetry: () -> Unit,
    smartTrackerState: com.example.model.SmartBatteryDashboardState = com.example.model.SmartBatteryDashboardState(),
    onOpenSmartTracker: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top Telemetry Hero Banner + 3 Independent Controls (Background Monitoring, Background Voice, Silent Notification)
        item {
            HeroTelemetryCard(
                percentage = snapshot.percentage,
                isServiceRunning = isServiceRunning,
                isForegroundNotificationVisible = isForegroundNotificationVisible,
                monitoringMode = settings.backgroundMonitoringMode,
                silentNotificationEnabled = settings.silentNotificationEnabled,
                isCharging = snapshot.isCharging,
                voiceAnnouncementsEnabled = settings.voiceAnnouncementsEnabled,
                engineStatusText = engineStatusText,
                onToggleMonitoring = onToggleMonitoring,
                onSelectMonitoringMode = onSelectMonitoringMode,
                onToggleSilentNotification = onToggleSilentNotification,
                onToggleVoiceAnnouncements = onToggleVoiceAnnouncements,
                onTriggerNotificationAction = onTriggerNotificationAction,
                onRefreshTelemetry = onRefreshTelemetry
            )
        }

        // 2. Animated Radial Battery Gauge & Primary Power Status
        item {
            RadialBatteryCoreCard(
                snapshot = snapshot,
                isSpeaking = isSpeaking,
                disableAnimations = settings.batterySaverMode.disableAnimations,
                onSpeakStatusNow = onSpeakStatusNow,
                onStopSpeaking = onStopSpeaking
            )
        }

        // 3. Last Spoken Voice Announcement Banner (if any)
        item {
            AnimatedVisibility(visible = !lastSpokenBanner.isNullOrBlank()) {
                LastSpokenAlertCard(
                    message = lastSpokenBanner.orEmpty(),
                    activePresetTitle = "${settings.activePreset.title} (${settings.activePreset.personaName})",
                    isSpeaking = isSpeaking,
                    onReplay = onSpeakStatusNow,
                    onStop = onStopSpeaking
                )
            }
        }

        // 4. Compact Smart Battery Tracker & App Power Manager Card (Requirement 10)
        item {
            HomeSmartBatteryTrackerCard(
                snapshot = snapshot,
                settings = settings,
                trackerState = smartTrackerState,
                onOpenSmartTracker = onOpenSmartTracker
            )
        }

        // 5. Live Charging Power Meter & Speed Analysis Card
        item {
            ChargingPowerMeterCard(snapshot = snapshot)
        }

        // 6. Real-Time Charging History Graph (Battery % & Power W Curve)
        item {
            ChargingHistoryGraphCard(
                snapshot = snapshot,
                historyPoints = telemetryHistory
            )
        }

        // 7. Real-Time Electrical & Thermal Telemetry Grid (6 Cards, honest N/A fallback)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("deep_hardware_telemetry_section"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "DEEP HARDWARE TELEMETRY MATRIX",
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricCyan
                )
                HardwareMetricsGrid(snapshot = snapshot)
            }
        }

        // 8. Battery Health Condition Summary Card (Links to full Health Analysis screen)
        item {
            DashboardHealthSummaryCard(
                snapshot = snapshot,
                onOpenHealthScreen = onOpenHealthScreen
            )
        }
    }
}

@Composable
private fun HeroTelemetryCard(
    percentage: Int,
    isServiceRunning: Boolean,
    isForegroundNotificationVisible: Boolean,
    monitoringMode: BackgroundMonitoringMode,
    silentNotificationEnabled: Boolean,
    isCharging: Boolean,
    voiceAnnouncementsEnabled: Boolean,
    engineStatusText: String,
    onToggleMonitoring: (Boolean) -> Unit,
    onSelectMonitoringMode: (BackgroundMonitoringMode) -> Unit,
    onToggleSilentNotification: (Boolean) -> Unit,
    onToggleVoiceAnnouncements: (Boolean) -> Unit,
    onTriggerNotificationAction: (String) -> Unit,
    onRefreshTelemetry: () -> Unit
) {
    val isSilentMode = silentNotificationEnabled || monitoringMode == BackgroundMonitoringMode.SILENT_BACKGROUND
    val statusHeadline = when {
        !isServiceRunning -> "MONITOR STOPPED"
        isSilentMode -> "Battery Boss Active • $percentage% (SILENT FGS)"
        else -> "Battery Boss Active • $percentage% (STANDARD FGS)"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_telemetry_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, if (isCharging) EmeraldCharge.copy(alpha = 0.5f) else NavyCardBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_telemetry_1794576104255),
                    contentDescription = "Battery Boss telemetry cockpit background",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x99070B14),
                                    Color(0xEE070B14)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
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
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isServiceRunning) EmeraldCharge else AmberWarning)
                            )
                            Text(
                                text = statusHeadline,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isServiceRunning) EmeraldCharge else AmberWarning,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("hero_monitoring_status_text")
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onRefreshTelemetry,
                                modifier = Modifier.testTag("refresh_telemetry_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh battery telemetry",
                                    tint = TextSecondary
                                )
                            }
                            Switch(
                                checked = isServiceRunning,
                                onCheckedChange = onToggleMonitoring,
                                modifier = Modifier.testTag("monitoring_master_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF00210E),
                                    checkedTrackColor = EmeraldCharge,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = NavySurfaceElevated
                                )
                            )
                        }
                    }
                    Text(
                        text = "BATTERY BOSS 2.0 • FOREGROUND VOICE & DIAGNOSTICS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = engineStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            // 3 Independent Controls: 1. Background Monitoring, 2. Background Voice, 3. Silent Notification
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("independent_monitoring_voice_controls"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Background Voice: ${if (voiceAnnouncementsEnabled) "ON (Screen-Off Ready)" else "OFF (Monitoring Continues)"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (voiceAnnouncementsEnabled) EmeraldCharge else AmberWarning,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                !isServiceRunning -> "Foreground Service: OFF (0 Notifications)"
                                isSilentMode ->
                                    "Channel 'battery_boss_silent_channel': \"Battery Boss Active • $percentage%\" (Reposted In-Place)"
                                else ->
                                    "Channel 'battery_boss_standard_channel': \"Battery Boss Active • $percentage%\" (+Buttons)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSilentMode) ElectricCyan else TextSecondary,
                            modifier = Modifier.testTag("hero_notification_status_text")
                        )
                    }
                    Switch(
                        checked = voiceAnnouncementsEnabled,
                        onCheckedChange = onToggleVoiceAnnouncements,
                        modifier = Modifier.testTag("voice_announcements_master_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00210E),
                            checkedTrackColor = ElectricCyan,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = NavySurfaceElevated
                        )
                    )
                }

                // Channel Mode Row: Reposts Notification ID 4010 onto Silent Channel vs Standard Channel without restarting service
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hero_monitoring_mode_selector"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onToggleSilentNotification(true)
                            onSelectMonitoringMode(BackgroundMonitoringMode.SILENT_BACKGROUND)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .testTag("hero_mode_silent_background_button"),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSilentMode) EmeraldCharge.copy(alpha = 0.16f) else Color.Transparent
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSilentMode) EmeraldCharge else NavyCardBorder
                        )
                    ) {
                        Text(
                            text = "SILENT CHANNEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSilentMode) EmeraldCharge else TextSecondary,
                            fontWeight = if (isSilentMode) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onToggleSilentNotification(false)
                            onSelectMonitoringMode(BackgroundMonitoringMode.CONTINUOUS_FOREGROUND)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .testTag("hero_mode_continuous_foreground_button"),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (!isSilentMode) ElectricCyan.copy(alpha = 0.16f) else Color.Transparent
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (!isSilentMode) ElectricCyan else NavyCardBorder
                        )
                    ) {
                        Text(
                            text = "STANDARD CHANNEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!isSilentMode) ElectricCyan else TextSecondary,
                            fontWeight = if (!isSilentMode) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onToggleMonitoring(!isServiceRunning)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .testTag("quick_notif_toggle_monitoring_button"),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        border = BorderStroke(1.dp, if (isServiceRunning) ThermalCrimson.copy(alpha = 0.7f) else EmeraldCharge)
                    ) {
                        Text(
                            text = if (isServiceRunning) "STOP MONITOR" else "START MONITOR",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isServiceRunning) ThermalCrimson else EmeraldCharge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onToggleVoiceAnnouncements(!voiceAnnouncementsEnabled)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .testTag("quick_notif_mute_resume_voice_button"),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = if (voiceAnnouncementsEnabled) "MUTE VOICE" else "RESUME VOICE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadialBatteryCoreCard(
    snapshot: BatteryHardwareSnapshot,
    isSpeaking: Boolean,
    disableAnimations: Boolean = false,
    onSpeakStatusNow: () -> Unit,
    onStopSpeaking: () -> Unit
) {
    val targetSweep = (snapshot.percentage.coerceIn(0, 100) / 100f) * 280f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = if (disableAnimations) 0 else 700, easing = FastOutSlowInEasing),
        label = "battery_sweep"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "charging_pulse")
    val rawPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val pulseAlpha = if (disableAnimations) 0.35f else rawPulseAlpha

    val primaryRingColor = when {
        snapshot.temperatureCelsius != null && snapshot.temperatureCelsius >= 43f -> ThermalCrimson
        snapshot.isCharging -> EmeraldCharge
        snapshot.percentage <= 20 -> AmberWarning
        else -> ElectricCyan
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("radial_battery_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(228.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 18.dp.toPx()
                    val diameter = size.minDimension - strokeWidth * 1.4f
                    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                    val arcSize = Size(diameter, diameter)

                    if (snapshot.isCharging) {
                        drawArc(
                            color = primaryRingColor.copy(alpha = pulseAlpha * 0.28f),
                            startAngle = 130f,
                            sweepAngle = 280f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth * 1.65f, cap = StrokeCap.Round)
                        )
                    }

                    drawArc(
                        color = Color(0xFF1C2840),
                        startAngle = 130f,
                        sweepAngle = 280f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                primaryRingColor.copy(alpha = 0.75f),
                                primaryRingColor,
                                ElectricCyan,
                                primaryRingColor
                            )
                        ),
                        startAngle = 130f,
                        sweepAngle = animatedSweep.coerceAtLeast(4f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when {
                            snapshot.isCharging -> Icons.Default.BatteryChargingFull
                            snapshot.isFull -> Icons.Default.BatteryFull
                            else -> Icons.Default.BatteryStd
                        },
                        contentDescription = snapshot.statusLabel,
                        tint = primaryRingColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "${snapshot.percentage}%",
                        style = MaterialTheme.typography.displayLarge,
                        color = TextPrimary,
                        modifier = Modifier.testTag("battery_percentage_text")
                    )
                    Surface(
                        color = primaryRingColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, primaryRingColor.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = snapshot.statusLabel.uppercase(Locale.US),
                            style = MaterialTheme.typography.labelLarge,
                            color = primaryRingColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "POWER SOURCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = snapshot.chargingSource.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(NavyCardBorder)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "EST. TIME TO FULL",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    val etaText = snapshot.estimatedMinutesToFull?.let { mins ->
                        if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins} min"
                    } ?: if (snapshot.isFull) "Full (100%)" else "N/A"
                    Text(
                        text = etaText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (snapshot.estimatedMinutesToFull != null) EmeraldCharge else TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSpeakStatusNow,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("speak_status_now_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldCharge,
                        contentColor = Color(0xFF00210E)
                    )
                ) {
                    Icon(
                        imageVector = if (isSpeaking) Icons.Default.GraphicEq else Icons.Default.VolumeUp,
                        contentDescription = "Speak battery status",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSpeaking) "SPEAKING BOSS BRIEFING..." else "ANNOUNCE STATUS NOW",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (isSpeaking) {
                    OutlinedButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("stop_speaking_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ThermalCrimson)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop speaking",
                            tint = ThermalCrimson
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("STOP", color = ThermalCrimson, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargingPowerMeterCard(snapshot: BatteryHardwareSnapshot) {
    val watts = snapshot.powerWatts?.let { abs(it) }
    val normalizedProgress = ((watts ?: 0f) / 35f).coerceIn(0f, 1f)
    val rateText = snapshot.percentPerHourRate?.let {
        String.format(Locale.US, "%.1f %%/hr", it)
    } ?: "N/A"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("charging_power_meter_card"),
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = "Power Meter",
                        tint = EmeraldCharge
                    )
                    Column {
                        Text(
                            text = "LIVE CHARGING POWER METER",
                            style = MaterialTheme.typography.labelLarge,
                            color = EmeraldCharge
                        )
                        Text(
                            text = snapshot.chargingSpeedTier.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Text(
                    text = watts?.let { String.format(Locale.US, "%.2f W", it) } ?: "N/A",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (watts != null) EmeraldCharge else TextSecondary
                )
            }

            LinearProgressIndicator(
                progress = { normalizedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50)),
                color = when {
                    (watts ?: 0f) >= 15f -> EmeraldCharge
                    (watts ?: 0f) >= 5f -> ElectricCyan
                    else -> AmberWarning
                },
                trackColor = NavySurfaceElevated
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0W (Idle)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("5W (Slow)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("15W (Fast)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("30W+ (Super)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Charge Velocity (Est.): $rateText",
                    style = MaterialTheme.typography.bodySmall,
                    color = ElectricCyan
                )
                Text(
                    text = "Avg Power: ${snapshot.averagePowerWatts?.let { String.format(Locale.US, "%.1f W", abs(it)) } ?: "N/A"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Text(
                text = "Note: Measured battery-side power (V_cell × I_cell) reflects net energy entering the battery pack after active phone load and DC-DC step-down conversion.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun ChargingHistoryGraphCard(
    snapshot: BatteryHardwareSnapshot,
    historyPoints: List<TelemetrySamplePoint>
) {
    val points = if (historyPoints.isEmpty()) {
        listOf(
            TelemetrySamplePoint(
                timestampMillis = snapshot.timestampMillis,
                percentage = snapshot.percentage,
                powerWatts = snapshot.powerWatts?.let { abs(it) },
                currentMilliAmps = snapshot.currentMilliAmps,
                temperatureCelsius = snapshot.temperatureCelsius
            )
        )
    } else {
        historyPoints
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("charging_history_graph_card"),
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = "Charging History Graph",
                        tint = ElectricCyan
                    )
                    Column {
                        Text(
                            text = "LIVE TELEMETRY TREND GRAPH",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Green: Battery % (0–100%) • Cyan: Temperature / Power Curve",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Text(
                    text = "${points.size} samples",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0A101D))
                    .padding(12.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Subtle horizontal grid lines (25%, 50%, 75%)
                    listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                        drawLine(
                            color = Color(0xFF1E2D47),
                            start = Offset(0f, h * frac),
                            end = Offset(w, h * frac),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val count = points.size
                    val pathPct = Path()
                    val pathTemp = Path()

                    points.forEachIndexed { idx, pt ->
                        val x = if (count <= 1) w / 2f else (idx.toFloat() / (count - 1).toFloat()) * w
                        val yPct = h - ((pt.percentage.coerceIn(0, 100) / 100f) * h)
                        val tempVal = (pt.temperatureCelsius ?: 30f).coerceIn(15f, 50f)
                        val yTemp = h - (((tempVal - 15f) / 35f) * h)

                        if (idx == 0) {
                            pathPct.moveTo(x, yPct)
                            pathTemp.moveTo(x, yTemp)
                        } else {
                            pathPct.lineTo(x, yPct)
                            pathTemp.lineTo(x, yTemp)
                        }

                        drawCircle(
                            color = EmeraldCharge,
                            radius = 3.5.dp.toPx(),
                            center = Offset(x, yPct)
                        )
                    }

                    if (count > 1) {
                        drawPath(
                            path = pathPct,
                            color = EmeraldCharge,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawPath(
                            path = pathTemp,
                            color = ElectricCyan.copy(alpha = 0.75f),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LastSpokenAlertCard(
    message: String,
    activePresetTitle: String,
    isSpeaking: Boolean,
    onReplay: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurfaceElevated),
        border = BorderStroke(1.dp, PurpleVoiceAccent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSpeaking) "SPEAKING NOW • $activePresetTitle" else "LAST VOICE ANNOUNCEMENT • $activePresetTitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = PurpleVoiceAccent
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
            if (isSpeaking) {
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop voice announcement",
                        tint = ThermalCrimson
                    )
                }
            } else {
                IconButton(onClick = onReplay) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Replay voice announcement",
                        tint = EmeraldCharge
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareMetricsGrid(snapshot: BatteryHardwareSnapshot) {
    val voltageText = snapshot.voltageVolts?.let {
        String.format(Locale.US, "%.3f V", it)
    } ?: "N/A"
    val voltageSub = snapshot.voltageMillivolts?.let { "$it mV" } ?: "Not reported"

    val currentText = snapshot.currentMilliAmps?.let { "$it mA" } ?: "N/A"
    val currentSub = when {
        snapshot.averageCurrentMilliAmps != null -> "Avg: ${snapshot.averageCurrentMilliAmps} mA"
        snapshot.currentMilliAmps == null -> "Sensor unavailable"
        snapshot.isCharging -> "Live charge current"
        else -> "Live discharge current"
    }

    val powerText = snapshot.powerWatts?.let {
        String.format(Locale.US, "%.2f W", abs(it))
    } ?: "N/A"
    val powerSub = snapshot.chargingSpeedTier.label

    val tempText = snapshot.temperatureCelsius?.let {
        String.format(Locale.US, "%.1f °C", it)
    } ?: "N/A"
    val tempSub = snapshot.temperatureCelsius?.let { c ->
        val f = c * 9f / 5f + 32f
        String.format(Locale.US, "%.1f °F", f)
    } ?: "Sensor unavailable"

    val healthText = snapshot.healthLabel
    val techSub = snapshot.technology ?: "Tech: N/A"

    val capacityText = snapshot.chargeCounterMah?.let { "$it mAh" }
        ?: snapshot.designCapacityMah?.let { "$it mAh" }
        ?: "N/A"
    val capacitySub = when {
        snapshot.chargeCounterMah != null && snapshot.designCapacityMah != null ->
            "of ${snapshot.designCapacityMah} mAh design"
        snapshot.chargeCounterMah != null -> "Remaining charge counter"
        snapshot.cycleCount != null -> "${snapshot.cycleCount} cycles"
        else -> "Not exposed by OEM kernel"
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricCard(
                title = "VOLTAGE",
                value = voltageText,
                subtitle = voltageSub,
                icon = Icons.Default.Bolt,
                accentColor = ElectricCyan,
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "CURRENT (NOW / AVG)",
                value = currentText,
                subtitle = currentSub,
                icon = Icons.Default.Speed,
                accentColor = EmeraldCharge,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricCard(
                title = "CELL-SIDE POWER",
                value = powerText,
                subtitle = powerSub,
                icon = Icons.Default.ElectricBolt,
                accentColor = EmeraldCharge,
                modifier = Modifier.weight(1f)
            )
            val tempColor = if ((snapshot.temperatureCelsius ?: 0f) >= 42f) ThermalCrimson else AmberWarning
            TelemetryMetricCard(
                title = "TEMPERATURE",
                value = tempText,
                subtitle = tempSub,
                icon = Icons.Default.Thermostat,
                accentColor = tempColor,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricCard(
                title = "OS HEALTH FLAG",
                value = healthText,
                subtitle = "Chemistry: $techSub",
                icon = Icons.Default.HealthAndSafety,
                accentColor = ElectricCyan,
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "CHARGE COUNTER",
                value = capacityText,
                subtitle = capacitySub,
                icon = Icons.Default.Memory,
                accentColor = PurpleVoiceAccent,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DashboardHealthSummaryCard(
    snapshot: BatteryHardwareSnapshot,
    onOpenHealthScreen: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HealthAndSafety,
                        contentDescription = "Battery Health Analysis",
                        tint = EmeraldCharge
                    )
                    Column {
                        Text(
                            text = "BATTERY HEALTH & CAPACITY SUMMARY",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Status: ${snapshot.healthReport.reportedOsHealthStatus} • Cycles: ${snapshot.cycleCount?.toString() ?: "N/A"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenHealthScreen,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("open_health_analysis_button")
                ) {
                    Text("Deep Health", style = MaterialTheme.typography.labelMedium, color = EmeraldCharge)
                }
            }
            Text(
                text = snapshot.healthReport.osHealthExplanation,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun HomeSmartBatteryTrackerCard(
    snapshot: BatteryHardwareSnapshot,
    settings: UserSettings,
    trackerState: com.example.model.SmartBatteryDashboardState,
    onOpenSmartTracker: () -> Unit
) {
    val topAppText = trackerState.topConsumingApp?.let { app ->
        "${app.appName} (${app.formattedForegroundTime})"
    } ?: if (trackerState.hasUsageAccessPermission) "Scanning Installed Apps..." else "Grant Usage Access"

    val screenTimeText = trackerState.screenOnTimeMillis?.let {
        com.example.model.AppBatteryUsageRecord.formatDurationMillis(it)
    } ?: "Unavailable"

    val dischargeRateText = when {
        snapshot.isCharging -> "Charging (+${snapshot.currentMilliAmps?.let { abs(it) } ?: 0} mA)"
        trackerState.dischargeRateMa != null -> "${trackerState.dischargeRateMa} mA"
        trackerState.dischargeRatePercentPerHour != null ->
            String.format(Locale.US, "%.1f %%/hr", trackerState.dischargeRatePercentPerHour)
        else -> "N/A"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_smart_battery_tracker_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Smart Battery Tracker & App Power Manager",
                        tint = ElectricCyan
                    )
                    Column {
                        Text(
                            text = "SMART BATTERY TRACKER & APP POWER MGR",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Saver: ${settings.batterySaverMode.title} • Auto Saver: ${if (settings.autoBatterySaverEnabled) "ON (${settings.autoSaverThresholdPercent}%)" else "OFF"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldCharge
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenSmartTracker,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("home_view_all_apps_button")
                ) {
                    Text("View All Apps", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TOP CONSUMING APP", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        text = topAppText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("SCREEN USAGE (24H)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        text = screenTimeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldCharge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                    Text("DISCHARGE RATE", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        text = dischargeRateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = if (value == "N/A") TextSecondary else TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
