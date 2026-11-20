package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AnnouncementLogEntity
import com.example.data.local.ChargingSessionEntity
import com.example.model.BatteryHardwareSnapshot
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun HistoryAndAboutScreen(
    snapshot: BatteryHardwareSnapshot,
    chargingSessions: List<ChargingSessionEntity>,
    announcementLogs: List<AnnouncementLogEntity>,
    onReplayAnnouncement: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("history_about_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Charging Speed & Session Telemetry Overview (PART 5 – CHARGING ANALYTICS)
        item {
            LiveChargingAnalyticsCard(snapshot = snapshot)
        }

        // 2. Charging Sessions Summary & Analytics
        item {
            ChargingSessionsCard(
                sessions = chargingSessions,
                onClearHistory = onClearHistory
            )
        }

        // 3. Chronological Spoken Voice Announcements Log
        item {
            AnnouncementLogsCard(
                logs = announcementLogs,
                onReplayAnnouncement = onReplayAnnouncement
            )
        }

        // 4. About Battery Boss, Offline Voice Licensing & Device Testing Guide
        item {
            AboutAndTestingGuideCard()
        }
    }
}

@Composable
private fun LiveChargingAnalyticsCard(snapshot: BatteryHardwareSnapshot) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_charging_analytics_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.5f))
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
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Live Charging Analytics",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "REAL-TIME CHARGING SPEED ANALYTICS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Speed Tier: ${snapshot.chargingSpeedTier.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }
            }

            val rows = listOf(
                "Live Battery-Side Power" to (snapshot.powerWatts?.let { String.format(Locale.US, "%.2f W", abs(it)) } ?: "N/A"),
                "Average Battery Power (Est.)" to (snapshot.averagePowerWatts?.let { String.format(Locale.US, "%.2f W", abs(it)) } ?: "N/A"),
                "Instant Charging Current" to (snapshot.currentMilliAmps?.let { "$it mA" } ?: "N/A"),
                "Cell Voltage" to (snapshot.voltageVolts?.let { String.format(Locale.US, "%.3f V", it) } ?: "N/A"),
                "Charging Speed Classification" to snapshot.chargingSpeedTier.label,
                "Percentage Gained / Hour (Est.)" to (snapshot.percentPerHourRate?.let { String.format(Locale.US, "%.1f %%/hr", it) } ?: "N/A"),
                "Estimated Remaining Time" to (snapshot.estimatedMinutesToFull?.let { "$it min" } ?: if (snapshot.isFull) "Full (100%)" else "N/A")
            )

            rows.forEachIndexed { idx, (label, value) ->
                if (idx > 0) {
                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
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

@Composable
private fun ChargingSessionsCard(
    sessions: List<ChargingSessionEntity>,
    onClearHistory: () -> Unit
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        imageVector = Icons.Default.EvStation,
                        contentDescription = "Charging History",
                        tint = EmeraldCharge
                    )
                    Column {
                        Text(
                            text = "CHARGING SESSION LOGS",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${sessions.size} recorded sessions on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                if (sessions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearHistory,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear History",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (sessions.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = NavySurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "No Charging Sessions Logged Yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Connect a charger to automatically track start/end percentage, duration, %/hr rate, average/peak power (W), and peak temperature (°C).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                sessions.take(15).forEach { session ->
                    SessionItemRow(session = session)
                }
            }
        }
    }
}

@Composable
private fun SessionItemRow(session: ChargingSessionEntity) {
    val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.US)
    val startStr = dateFormat.format(Date(session.startTimeMillis))

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = NavySurfaceElevated,
        border = BorderStroke(
            1.dp,
            if (!session.isCompleted) EmeraldCharge.copy(alpha = 0.5f) else NavyCardBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${session.startPercentage}% → ${session.endPercentage}% (+${session.percentageGained}%)",
                    style = MaterialTheme.typography.titleSmall,
                    color = EmeraldCharge,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = if (!session.isCompleted) EmeraldCharge.copy(alpha = 0.2f) else ElectricCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (!session.isCompleted) "ACTIVE SESSION" else "${session.durationMinutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!session.isCompleted) EmeraldCharge else ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (session.endPercentage.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = EmeraldCharge,
                trackColor = NavySurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val rateStr = session.percentPerHour?.let { String.format(Locale.US, "%.1f %%/hr", it) } ?: "Rate N/A"
                Text(
                    text = "$startStr • ${session.chargingSource} • $rateStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val avgWattsStr = session.averageWatts?.let { String.format(Locale.US, "%.1fW", it) } ?: "N/A"
                val peakWattsStr = session.peakWatts?.let { String.format(Locale.US, "%.1fW", it) } ?: "N/A"
                val peakTempStr = session.peakTemperatureCelsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"
                Text(
                    text = "Avg Power: $avgWattsStr • Peak: $peakWattsStr • Peak Temp: $peakTempStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun AnnouncementLogsCard(
    logs: List<AnnouncementLogEntity>,
    onReplayAnnouncement: (String) -> Unit
) {
    val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)

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
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = "Spoken Announcements",
                    tint = PurpleVoiceAccent
                )
                Column {
                    Text(
                        text = "VOICE ANNOUNCEMENT EVENT LOG",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Recent spoken alerts & deduplicated battery events",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (logs.isEmpty()) {
                Text(
                    text = "No voice announcements logged yet. Tap 'Announce Status Now' on the Dashboard or 'Preview Voice' in Voice Studio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                logs.take(20).forEachIndexed { idx, log ->
                    if (idx > 0) {
                        HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${log.eventType} • ${log.batteryPercentage}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (log.wasMutedByQuietMode) AmberWarning else ElectricCyan
                                )
                                Text(
                                    text = "(${timeFormat.format(Date(log.timestampMillis))})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                            Text(
                                text = log.messageSpoken,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Voice: ${log.voiceProfileLabel}${if (log.wasMutedByQuietMode) " • Muted by Quiet/Ringer" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        IconButton(onClick = { onReplayAnnouncement(log.messageSpoken) }) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Replay announcement",
                                tint = EmeraldCharge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutAndTestingGuideCard() {
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
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = "About Battery Boss 2.0",
                    tint = EmeraldCharge
                )
                Column {
                    Text(
                        text = "ABOUT BATTERY BOSS 2.0 & TESTING GUIDE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pro Neural Voice Studio & Deep Hardware Diagnostics",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }
            }

            Text(
                text = "1. Honest Hardware Telemetry Guarantee",
                style = MaterialTheme.typography.titleSmall,
                color = EmeraldCharge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Battery Boss 2.0 reads directly from Android BatteryManager, ACTION_BATTERY_CHANGED broadcasts, and readable kernel power_supply registers. It never fabricates or simulates sensor values. Android's GOOD status is reported as a safety condition flag, never as '100% battery health'.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Text(
                text = "2. Pro Voice Studio & AudioFocus Management",
                style = MaterialTheme.typography.titleSmall,
                color = EmeraldCharge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "• Supports 4 independent voice profiles (Hindi Female – Priyamvada, Hindi Male – Rohan, English Female – Prerana, English Male – Prabhat).\n• Uses transient AudioFocus ducking (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) and natural clause prosody with 210ms breath pauses.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
