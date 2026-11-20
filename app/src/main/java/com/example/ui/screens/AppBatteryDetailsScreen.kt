package com.example.ui.screens

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.data.local.AppUsageRecordEntity
import com.example.hardware.SmartAppUsageTracker
import com.example.model.AppBatteryUsageRecord
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated App Battery Usage Details & App Power Manager Screen (Sections 3, 4, 12C & 12D).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppBatteryDetailsScreen(
    app: AppBatteryUsageRecord,
    historyRecords: List<AppUsageRecordEntity>,
    onBack: () -> Unit,
    onToggleProtected: (Boolean) -> Unit,
    onToggleExcluded: (Boolean) -> Unit,
    onToggleUserPermittedRestrict: (Boolean) -> Unit,
    onRecordPowerAction: (actionDescription: String, succeeded: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.US) }

    BackHandler {
        onBack()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("app_battery_details_screen"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header Card with App Icon, Package Name, Rank & Back Button
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("app_details_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to App Tracker",
                                tint = ElectricCyan
                            )
                        }
                        AppPackageIcon(
                            packageName = app.packageName,
                            contentDescription = app.appName,
                            modifier = Modifier.size(44.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    color = EmeraldCharge.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = "#${app.rank}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldCharge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusBadgePill(
                            text = if (app.isSystemApp) "System Package" else "User Installed App",
                            color = if (app.isSystemApp) PurpleVoiceAccent else ElectricCyan
                        )
                        StatusBadgePill(
                            text = app.optimizationStatusLabel,
                            color = if (app.isIgnoringBatteryOptimizations == true) AmberWarning else EmeraldCharge
                        )
                        if (app.isProtectedCriticalApp) {
                            StatusBadgePill(
                                text = app.protectedReason ?: "Protected Critical App",
                                color = EmeraldCharge
                            )
                        }
                    }
                }
            }
        }

        // 2. Verified Usage & Battery Consumption Breakdown Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_details_metrics_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "USAGE TIME VS. BATTERY ENERGY ATTRIBUTION",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DetailMetricBox(
                            label = "FOREGROUND TIME",
                            value = app.formattedForegroundTime,
                            subtext = app.usageConfidence.badgeText,
                            accent = EmeraldCharge,
                            modifier = Modifier.weight(1f)
                        )
                        DetailMetricBox(
                            label = "BACKGROUND ACTIVITY",
                            value = app.formattedBackgroundTime,
                            subtext = if (app.backgroundServiceTimeMillis != null) "API 29+ FGS Counter" else "Not Exposed by OS",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DetailMetricBox(
                            label = "VERIFIED BATTERY (mAh)",
                            value = app.verifiedBatteryMah?.let { String.format(Locale.US, "%.1f mAh", it) } ?: "N/A",
                            subtext = app.verifiedConsumptionLabel,
                            accent = if (app.verifiedBatteryMah != null) EmeraldCharge else TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        DetailMetricBox(
                            label = "ESTIMATED SHARE",
                            value = app.estimatedBatteryPercent?.let { String.format(Locale.US, "~%.1f%%", it) } ?: "N/A",
                            subtext = app.estimatedConsumptionLabel,
                            accent = AmberWarning,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

                    val lastUsedStr = if (app.lastTimeUsedMillis > 0L) {
                        dateFormat.format(Date(app.lastTimeUsedMillis))
                    } else {
                        "Unavailable / Not Used in Window"
                    }
                    Text(
                        text = "Last Used Time: $lastUsedStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Usage API Source: ${app.usageSourceApi}\nEnergy API Source: ${app.energySourceApi}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // 3. Section 4: App Power Manager (System Settings Controls)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_power_manager_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsApplications,
                            contentDescription = "App Power Manager",
                            tint = EmeraldCharge
                        )
                        Column {
                            Text(
                                text = "APP POWER MANAGER (SYSTEM CONTROLS)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Opens official Android Settings so you can safely Force Stop, Restrict Background Battery, or Uninstall.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val ok = runCatching {
                                    context.startActivity(SmartAppUsageTracker.buildAppInfoSettingsIntent(app.packageName))
                                }.isSuccess
                                onRecordPowerAction("Opened App Info / Force Stop screen for ${app.appName}", ok)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_open_app_info"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldCharge,
                                contentColor = Color(0xFF00210E)
                            )
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("App Info / Force Stop", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val ok = runCatching {
                                    context.startActivity(SmartAppUsageTracker.buildAppInfoSettingsIntent(app.packageName))
                                }.isSuccess
                                onRecordPowerAction("Opened App Battery Restriction Settings for ${app.appName}", ok)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_open_app_battery_settings"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ElectricCyan)
                        ) {
                            Icon(Icons.Default.BatterySaver, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Battery Settings", style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val ok = runCatching {
                                    context.startActivity(SmartAppUsageTracker.buildUsageAccessSettingsIntent())
                                }.isSuccess
                                onRecordPowerAction("Opened Usage Access Settings from ${app.appName}", ok)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_open_usage_settings"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NavyCardBorder)
                        ) {
                            Icon(Icons.Default.QueryStats, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("App Usage Settings", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }

                        OutlinedButton(
                            onClick = {
                                val ok = runCatching {
                                    context.startActivity(
                                        SmartAppUsageTracker.buildAppNotificationSettingsIntent(context, app.packageName)
                                    )
                                }.isSuccess
                                onRecordPowerAction("Opened Notification Settings for ${app.appName}", ok)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_open_notification_settings"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NavyCardBorder)
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Notifications", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }

                    Text(
                        text = "Android Security Policy: Normal third-party apps cannot silently Force Stop other apps in the background. Tapping 'App Info / Force Stop' opens the system screen where you have full OS control.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }

        // 4. Section 12C & 12D: Auto-Restriction Permission, Protected Status & Exclusion Controls
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_protection_and_restriction_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Protection & Auto Restriction",
                            tint = ElectricCyan
                        )
                        Text(
                            text = "PROTECTED & AUTO-RESTRICTION RULES",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    CompactSwitchRow(
                        title = "Protected Critical App (Never Auto-Restrict)",
                        subtitle = app.protectedReason ?: "Prevents Auto Restriction alerts or actions for this app",
                        checked = app.isProtectedCriticalApp,
                        onCheckedChange = onToggleProtected,
                        testTag = "switch_app_protected"
                    )

                    CompactSwitchRow(
                        title = "Exclude from High-Drain Alerts",
                        subtitle = "Ignore this app when scanning for high foreground/background usage",
                        checked = app.isUserExcluded,
                        onCheckedChange = onToggleExcluded,
                        testTag = "switch_app_excluded"
                    )

                    CompactSwitchRow(
                        title = "Allow User-Confirmed Auto-Restriction Prompt",
                        subtitle = if (app.isProtectedCriticalApp) {
                            "Disabled because this app is in the Protected Apps list"
                        } else {
                            "Include this app when Auto Restrict High Battery Usage Apps is enabled"
                        },
                        checked = app.isUserPermittedForAutoRestrict && !app.isProtectedCriticalApp,
                        onCheckedChange = { allowed ->
                            if (!app.isProtectedCriticalApp) {
                                onToggleUserPermittedRestrict(allowed)
                            }
                        },
                        testTag = "switch_app_user_permitted_restrict"
                    )
                }
            }
        }

        // 5. Battery Consumption & Usage Trend Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_usage_trend_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                border = BorderStroke(1.dp, NavyCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "App Usage History Trend",
                            tint = PurpleVoiceAccent
                        )
                        Text(
                            text = "APP USAGE HISTORY & TREND (${historyRecords.size} snapshots)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (historyRecords.size >= 2) {
                        val chronological = remember(historyRecords) { historyRecords.sortedBy { it.timestampMillis } }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0A101D))
                                .padding(10.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val maxFg = chronological.maxOf { it.foregroundTimeMillis }.coerceAtLeast(60_000L).toFloat()
                                val w = size.width
                                val h = size.height
                                val path = Path()
                                chronological.forEachIndexed { index, rec ->
                                    val x = (index.toFloat() / (chronological.size - 1).coerceAtLeast(1)) * w
                                    val y = h - ((rec.foregroundTimeMillis.toFloat() / maxFg).coerceIn(0f, 1f) * h)
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                    drawCircle(
                                        color = ElectricCyan,
                                        radius = 3.dp.toPx(),
                                        center = Offset(x, y)
                                    )
                                }
                                drawPath(
                                    path = path,
                                    color = ElectricCyan,
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
                    }

                    if (historyRecords.isEmpty()) {
                        Text(
                            text = "Current session usage is tracked above. Historical Room DB snapshots populate automatically as usage progresses.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        historyRecords.take(8).forEach { rec ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = dateFormat.format(Date(rec.timestampMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Fg: ${AppBatteryUsageRecord.formatDurationMillis(rec.foregroundTimeMillis)} • ${
                                        rec.estimatedBatteryMah?.let { String.format(Locale.US, "~%.1f mAh", it) } ?: "Verified mAh: N/A"
                                    }",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppPackageIcon(
    packageName: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(width = 96, height = 96).asImageBitmap()
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(NavySurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = contentDescription,
                tint = EmeraldCharge,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun DetailMetricBox(
    label: String,
    value: String,
    subtext: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = NavySurfaceElevated,
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun StatusBadgePill(
    text: String,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
