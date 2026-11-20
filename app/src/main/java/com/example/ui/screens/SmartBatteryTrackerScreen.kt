package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AppSettingsAlt
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AppUsageRecordEntity
import com.example.data.local.AutoManagementReportEntity
import com.example.data.local.BatteryUsageHistoryEntity
import com.example.hardware.SmartAppUsageTracker
import com.example.model.AppBatteryUsageRecord
import com.example.model.AppUsageSectionTab
import com.example.model.AutoSaverTriggerPreset
import com.example.model.BatteryHardwareSnapshot
import com.example.model.BatterySaverMode
import com.example.model.ConsumptionGraphWindow
import com.example.model.HistoryPeriodFilter
import com.example.model.SmartBatteryDashboardState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartBatteryTrackerScreen(
    snapshot: BatteryHardwareSnapshot,
    trackerState: SmartBatteryDashboardState,
    settings: UserSettings,
    telemetryHistory: List<TelemetrySamplePoint>,
    usageHistoryRecords: List<BatteryUsageHistoryEntity>,
    autoManagementReports: List<AutoManagementReportEntity>,
    selectedAppForDetails: AppBatteryUsageRecord?,
    selectedAppHistoryRecords: List<AppUsageRecordEntity>,
    onRefreshTracker: () -> Unit,
    onSelectGraphWindow: (ConsumptionGraphWindow) -> Unit,
    onSelectHistoryPeriod: (HistoryPeriodFilter) -> Unit,
    onSelectAppSection: (AppUsageSectionTab) -> Unit,
    onOpenAppDetails: (AppBatteryUsageRecord?) -> Unit,
    onSetBatterySaverMode: (BatterySaverMode) -> Unit,
    onUpdateAutoSaverSettings: (
        enabled: Boolean?,
        thresholdPercent: Int?,
        targetMode: BatterySaverMode?,
        restoreNormalEnabled: Boolean?,
        recoveryPercent: Int?
    ) -> Unit,
    onUpdateAutoRestrictionSettings: (
        autoRestrictEnabled: Boolean?,
        restrictionThresholdPercent: Int?
    ) -> Unit,
    onToggleAppProtected: (String, Boolean) -> Unit,
    onToggleAppExcluded: (String, Boolean) -> Unit,
    onToggleAppUserPermittedRestrict: (String, Boolean) -> Unit,
    onUpdateSmartAlertToggles: (
        highAppUsage: Boolean?,
        highBackgroundActivity: Boolean?,
        rapidDrain: Boolean?,
        highBatteryTemp: Boolean?,
        saverRecommendation: Boolean?,
        abnormalScreenDrain: Boolean?
    ) -> Unit,
    onRecordPowerAction: (AppBatteryUsageRecord, String, Boolean) -> Unit,
    onDeleteOldHistory: (Int) -> Unit,
    onClearTrackerHistory: () -> Unit,
    onExportCsv: suspend () -> String,
    modifier: Modifier = Modifier
) {
    if (selectedAppForDetails != null) {
        AppBatteryDetailsScreen(
            app = selectedAppForDetails,
            historyRecords = selectedAppHistoryRecords,
            onBack = { onOpenAppDetails(null) },
            onToggleProtected = { isProt -> onToggleAppProtected(selectedAppForDetails.packageName, isProt) },
            onToggleExcluded = { isExcl -> onToggleAppExcluded(selectedAppForDetails.packageName, isExcl) },
            onToggleUserPermittedRestrict = { isPerm ->
                onToggleAppUserPermittedRestrict(selectedAppForDetails.packageName, isPerm)
            },
            onRecordPowerAction = { desc, ok ->
                onRecordPowerAction(selectedAppForDetails, desc, ok)
            },
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportedCsvPreview by remember { mutableStateOf<String?>(null) }

    if (exportedCsvPreview != null) {
        AlertDialog(
            onDismissRequest = { exportedCsvPreview = null },
            containerColor = NavySurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Exported Battery Tracker CSV", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = exportedCsvPreview.orEmpty().take(1200),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val csv = exportedCsvPreview.orEmpty()
                        exportedCsvPreview = null
                        runCatching {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "Battery Boss 2.0 Smart Tracker Export")
                                putExtra(Intent.EXTRA_TEXT, csv)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share Battery Tracker Export").apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldCharge, contentColor = Color(0xFF00210E))
                ) {
                    Text("Share CSV")
                }
            },
            dismissButton = {
                TextButton(onClick = { exportedCsvPreview = null }) {
                    Text("Close", color = ElectricCyan)
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("smart_battery_tracker_screen"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 0. Usage Access Permission & Privacy Disclosure Card
        item {
            UsageAccessPermissionCard(
                hasUsageAccess = trackerState.hasUsageAccessPermission,
                onRefresh = onRefreshTracker,
                onOpenUsageAccessSettings = {
                    runCatching {
                        context.startActivity(SmartAppUsageTracker.buildUsageAccessSettingsIntent())
                    }
                }
            )
        }

        // 1. Battery Usage Dashboard & 1h/6h/12h/24h Consumption Graph
        item {
            SmartBatteryUsageDashboardCard(
                snapshot = snapshot,
                trackerState = trackerState,
                telemetryHistory = telemetryHistory,
                usageHistoryRecords = usageHistoryRecords,
                onSelectGraphWindow = onSelectGraphWindow,
                onRefresh = onRefreshTracker
            )
        }

        // 1B. Battery Boss Self-Power Efficiency, CPU & WakeLock Audit Card
        item {
            SelfPowerEfficiencyAuditCard(
                audit = trackerState.selfPowerAudit
            )
        }

        // 2. Smart Battery Saver Mode (Normal / Smart / Extreme) & Automatic Battery Saver
        item {
            SmartBatterySaverAndAutoModeCard(
                settings = settings,
                onSetBatterySaverMode = onSetBatterySaverMode,
                onUpdateAutoSaverSettings = onUpdateAutoSaverSettings,
                onOpenSystemBatterySaver = {
                    runCatching {
                        context.startActivity(SmartAppUsageTracker.buildBatterySaverSettingsIntent())
                    }.onFailure {
                        runCatching {
                            context.startActivity(SmartAppUsageTracker.buildSystemBatterySettingsIntent())
                        }
                    }
                }
            )
        }

        // 3. Smart Battery Alerts Card (Active Alerts + Individual Toggles)
        item {
            SmartBatteryAlertsCard(
                settings = settings,
                activeAlerts = trackerState.activeSmartAlerts,
                onUpdateSmartAlertToggles = onUpdateSmartAlertToggles
            )
        }

        // 4. App-wise Battery Usage Tracker (3 Sections + Ranking Cards + App Power Manager)
        item {
            AppWiseBatteryTrackerSectionCard(
                trackerState = trackerState,
                onSelectAppSection = onSelectAppSection,
                onOpenAppDetails = { app -> onOpenAppDetails(app) },
                onRecordPowerAction = onRecordPowerAction
            )
        }

        // 5. Automatic High Usage Detection, Auto Restriction & Auto Management Audit Report
        item {
            AutoRestrictionAndAuditReportCard(
                settings = settings,
                trackerState = trackerState,
                autoReports = autoManagementReports,
                onUpdateAutoRestrictionSettings = onUpdateAutoRestrictionSettings,
                onOpenAppDetails = { app -> onOpenAppDetails(app) }
            )
        }

        // 6. Room Database Battery Usage History & Records (Daily / Weekly / Monthly + Delete / Export)
        item {
            RoomBatteryUsageHistoryCard(
                selectedPeriod = trackerState.selectedHistoryPeriod,
                historyRecords = usageHistoryRecords,
                onSelectPeriod = onSelectHistoryPeriod,
                onDeleteOldHistory = { onDeleteOldHistory(7) },
                onClearAllTrackerHistory = onClearTrackerHistory,
                onExportHistory = {
                    scope.launch {
                        exportedCsvPreview = onExportCsv()
                    }
                }
            )
        }
    }
}

@Composable
private fun UsageAccessPermissionCard(
    hasUsageAccess: Boolean,
    onRefresh: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("usage_access_permission_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, if (hasUsageAccess) EmeraldCharge.copy(alpha = 0.45f) else AmberWarning)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        imageVector = if (hasUsageAccess) Icons.Default.Shield else Icons.Default.Lock,
                        contentDescription = "Usage Access Permission",
                        tint = if (hasUsageAccess) EmeraldCharge else AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = if (hasUsageAccess) {
                                "USAGE ACCESS GRANTED • ON-DEVICE PRIVACY"
                            } else {
                                "USAGE ACCESS REQUIRED FOR APP FOREGROUND TIMES"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (hasUsageAccess) EmeraldCharge else AmberWarning,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Reads only app foreground duration, last used time & screen interactive events via UsageStatsManager. Never reads personal messages, passwords, or private app content.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("tracker_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Smart Tracker",
                        tint = ElectricCyan
                    )
                }
            }

            if (!hasUsageAccess) {
                Button(
                    onClick = onOpenUsageAccessSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("grant_usage_access_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberWarning,
                        contentColor = Color(0xFF1F1200)
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "OPEN SYSTEM USAGE ACCESS SETTINGS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartBatteryUsageDashboardCard(
    snapshot: BatteryHardwareSnapshot,
    trackerState: SmartBatteryDashboardState,
    telemetryHistory: List<TelemetrySamplePoint>,
    usageHistoryRecords: List<BatteryUsageHistoryEntity>,
    onSelectGraphWindow: (ConsumptionGraphWindow) -> Unit,
    onRefresh: () -> Unit
) {
    val dischargeText = when {
        snapshot.isCharging -> "Charging (+${snapshot.currentMilliAmps?.let { kotlin.math.abs(it) } ?: 0} mA)"
        trackerState.dischargeRateMa != null && trackerState.dischargeRatePercentPerHour != null ->
            String.format(Locale.US, "%d mA (%.1f %%/hr)", trackerState.dischargeRateMa, trackerState.dischargeRatePercentPerHour)
        trackerState.dischargeRateMa != null -> "${trackerState.dischargeRateMa} mA"
        trackerState.dischargeRatePercentPerHour != null ->
            String.format(Locale.US, "%.1f %%/hr (Estimated)", trackerState.dischargeRatePercentPerHour)
        else -> "Unavailable"
    }

    val screenOnText = trackerState.screenOnTimeMillis?.let {
        AppBatteryUsageRecord.formatDurationMillis(it)
    } ?: "Unavailable"

    val screenOffText = trackerState.screenOffTimeMillis?.let {
        AppBatteryUsageRecord.formatDurationMillis(it)
    } ?: "Unavailable"

    val totalAppUsageText = trackerState.totalAppUsageTimeMillis?.let {
        AppBatteryUsageRecord.formatDurationMillis(it)
    } ?: "Unavailable"

    val estRemainingText = when {
        snapshot.isCharging -> snapshot.estimatedMinutesToFull?.let { mins ->
            "Charging (${mins / 60}h ${mins % 60}m to Full)"
        } ?: "Charging Active"
        trackerState.estimatedRemainingMinutes != null -> {
            val mins = trackerState.estimatedRemainingMinutes
            "~${mins / 60}h ${mins % 60}m (Estimated)"
        }
        else -> "Unavailable"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_tracker_dashboard_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Battery Usage Dashboard",
                        tint = EmeraldCharge
                    )
                    Column {
                        Text(
                            text = "SMART BATTERY USAGE DASHBOARD",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Real-time hardware sensors + UsageStatsManager telemetry",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                StatusBadgePill(
                    text = trackerState.activeSaverMode.badge,
                    color = when (trackerState.activeSaverMode) {
                        BatterySaverMode.NORMAL -> ElectricCyan
                        BatterySaverMode.SMART_SAVER -> EmeraldCharge
                        BatterySaverMode.EXTREME_SAVER -> AmberWarning
                    }
                )
            }

            // 8 Core Metrics in a 2-column compact matrix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackerMetricCell(
                    title = "BATTERY LEVEL",
                    value = "${snapshot.percentage}%",
                    sourceBadge = "BatteryManager • High",
                    accent = EmeraldCharge,
                    modifier = Modifier.weight(1f)
                )
                TrackerMetricCell(
                    title = "DISCHARGE RATE",
                    value = dischargeText,
                    sourceBadge = trackerState.dischargeRateConfidence.badgeText,
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackerMetricCell(
                    title = "TEMPERATURE",
                    value = snapshot.temperatureCelsius?.let { String.format(Locale.US, "%.1f °C", it) } ?: "Unavailable",
                    sourceBadge = "EXTRA_TEMPERATURE • High",
                    accent = if ((snapshot.temperatureCelsius ?: 0f) >= 42f) ThermalCrimson else AmberWarning,
                    modifier = Modifier.weight(1f)
                )
                TrackerMetricCell(
                    title = "VOLTAGE",
                    value = snapshot.voltageVolts?.let { String.format(Locale.US, "%.3f V", it) } ?: "Unavailable",
                    sourceBadge = "EXTRA_VOLTAGE • High",
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackerMetricCell(
                    title = "SCREEN ON TIME (24H)",
                    value = screenOnText,
                    sourceBadge = trackerState.screenOnConfidence.badgeText,
                    accent = EmeraldCharge,
                    modifier = Modifier.weight(1f)
                )
                TrackerMetricCell(
                    title = "SCREEN OFF TIME (24H)",
                    value = screenOffText,
                    sourceBadge = trackerState.screenOffConfidence.badgeText,
                    accent = PurpleVoiceAccent,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackerMetricCell(
                    title = "TOTAL APP USAGE (24H)",
                    value = totalAppUsageText,
                    sourceBadge = trackerState.totalAppUsageConfidence.badgeText,
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                TrackerMetricCell(
                    title = "EST. REMAINING TIME",
                    value = estRemainingText,
                    sourceBadge = trackerState.estimatedRemainingConfidence.badgeText,
                    accent = AmberWarning,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            // Battery Consumption Graph (1h, 6h, 12h, 24h)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = "Consumption History Graph",
                        tint = EmeraldCharge,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "BATTERY CONSUMPTION GRAPH (${trackerState.selectedGraphWindow.label})",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldCharge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ConsumptionGraphWindow.entries.forEach { window ->
                    val selected = trackerState.selectedGraphWindow == window
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectGraphWindow(window) },
                        label = { Text(window.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldCharge.copy(alpha = 0.2f),
                            selectedLabelColor = EmeraldCharge
                        ),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("graph_window_${window.hours}")
                    )
                }
            }

            val cutoffMillis = System.currentTimeMillis() - (trackerState.selectedGraphWindow.hours * 3_600_000L)
            val filteredDbPoints = remember(usageHistoryRecords, cutoffMillis) {
                usageHistoryRecords
                    .filter { it.timestampMillis >= cutoffMillis }
                    .sortedBy { it.timestampMillis }
                    .map { it.batteryPercentage }
            }
            val graphPercentages = if (filteredDbPoints.size >= 2) {
                filteredDbPoints
            } else if (telemetryHistory.isNotEmpty()) {
                telemetryHistory.map { it.percentage }
            } else {
                listOf(snapshot.percentage)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A101D))
                    .padding(10.dp)
                    .testTag("consumption_history_graph_canvas")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                        drawLine(
                            color = Color(0xFF1E2D47),
                            start = Offset(0f, h * frac),
                            end = Offset(w, h * frac),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    val count = graphPercentages.size
                    val path = Path()
                    graphPercentages.forEachIndexed { idx, pct ->
                        val x = if (count <= 1) w / 2f else (idx.toFloat() / (count - 1).toFloat()) * w
                        val y = h - ((pct.coerceIn(0, 100) / 100f) * h)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        drawCircle(
                            color = EmeraldCharge,
                            radius = 3.dp.toPx(),
                            center = Offset(x, y)
                        )
                    }
                    if (count > 1) {
                        drawPath(
                            path = path,
                            color = EmeraldCharge,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartBatterySaverAndAutoModeCard(
    settings: UserSettings,
    onSetBatterySaverMode: (BatterySaverMode) -> Unit,
    onUpdateAutoSaverSettings: (
        enabled: Boolean?,
        thresholdPercent: Int?,
        targetMode: BatterySaverMode?,
        restoreNormalEnabled: Boolean?,
        recoveryPercent: Int?
    ) -> Unit,
    onOpenSystemBatterySaver: () -> Unit
) {
    var thresholdSlider by remember(settings.autoSaverThresholdPercent) {
        mutableFloatStateOf(settings.autoSaverThresholdPercent.toFloat())
    }
    var recoverySlider by remember(settings.autoSaverRecoveryPercent) {
        mutableFloatStateOf(settings.autoSaverRecoveryPercent.toFloat())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_battery_saver_card"),
        shape = RoundedCornerShape(16.dp),
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BatterySaver,
                    contentDescription = "Smart Battery Saver Mode",
                    tint = EmeraldCharge
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SMART BATTERY SAVER & AUTO SAVER",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Active: ${settings.batterySaverMode.title} (${if (settings.batterySaverMode.isPureEventDriven) "100% Pure Event-Driven • 0 Polling" else "${settings.batterySaverMode.telemetryIntervalMs / 1000}s Screen-On • 0s Screen-Off"})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                }
            }

            // 3 Saver Modes Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BatterySaverMode.entries.forEach { mode ->
                    val selected = settings.batterySaverMode == mode
                    val modeColor = when (mode) {
                        BatterySaverMode.NORMAL -> ElectricCyan
                        BatterySaverMode.SMART_SAVER -> EmeraldCharge
                        BatterySaverMode.EXTREME_SAVER -> AmberWarning
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) modeColor.copy(alpha = 0.2f) else NavySurfaceElevated,
                        border = BorderStroke(1.dp, if (selected) modeColor else NavyCardBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSetBatterySaverMode(mode) }
                            .testTag("saver_mode_${mode.name}")
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = mode.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) modeColor else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = if (mode.isPureEventDriven) "0 Poll (Events)" else "${mode.telemetryIntervalMs / 1000}s Screen-On",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Text(
                text = settings.batterySaverMode.summary,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )

            OutlinedButton(
                onClick = onOpenSystemBatterySaver,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .testTag("open_system_battery_saver_button"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f))
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "OPEN ANDROID SYSTEM BATTERY SAVER SETTINGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            // Automatic Battery Saver Controls (Section 12A)
            CompactSwitchRow(
                title = "Automatic Battery Saver (${thresholdSlider.roundToInt()}%)",
                subtitle = "Automatically activates ${settings.autoSaverTargetMode.title} when battery reaches ${thresholdSlider.roundToInt()}%",
                checked = settings.autoBatterySaverEnabled,
                onCheckedChange = { onUpdateAutoSaverSettings(it, null, null, null, null) },
                testTag = "switch_auto_battery_saver"
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AutoSaverTriggerPreset.entries.filter { it != AutoSaverTriggerPreset.CUSTOM }.forEach { preset ->
                    val isSelected = settings.autoSaverThresholdPercent == preset.thresholdPercent &&
                        settings.autoSaverTargetMode == preset.targetMode
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            thresholdSlider = preset.thresholdPercent.toFloat()
                            onUpdateAutoSaverSettings(
                                true,
                                preset.thresholdPercent,
                                preset.targetMode,
                                null,
                                null
                            )
                        },
                        label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldCharge.copy(alpha = 0.2f),
                            selectedLabelColor = EmeraldCharge
                        ),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("auto_saver_preset_${preset.thresholdPercent}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Custom Auto Saver Trigger Level", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text("${thresholdSlider.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = EmeraldCharge, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = thresholdSlider,
                onValueChange = { thresholdSlider = it },
                onValueChangeFinished = {
                    val target = if (thresholdSlider.roundToInt() <= 15) BatterySaverMode.EXTREME_SAVER else BatterySaverMode.SMART_SAVER
                    onUpdateAutoSaverSettings(null, thresholdSlider.roundToInt(), target, null, null)
                },
                valueRange = 5f..80f,
                colors = SliderDefaults.colors(
                    thumbColor = EmeraldCharge,
                    activeTrackColor = EmeraldCharge,
                    inactiveTrackColor = NavySurfaceElevated
                ),
                modifier = Modifier.height(26.dp)
            )

            CompactSwitchRow(
                title = "Auto-Restore Normal Mode at ${recoverySlider.roundToInt()}%",
                subtitle = "Automatically switches back to Normal Mode once battery charges to ${recoverySlider.roundToInt()}%",
                checked = settings.autoSaverRestoreNormalEnabled,
                onCheckedChange = { onUpdateAutoSaverSettings(null, null, null, it, null) },
                testTag = "switch_auto_restore_normal"
            )
            Slider(
                value = recoverySlider,
                onValueChange = { recoverySlider = it },
                onValueChangeFinished = {
                    onUpdateAutoSaverSettings(null, null, null, null, recoverySlider.roundToInt())
                },
                valueRange = 25f..95f,
                colors = SliderDefaults.colors(
                    thumbColor = ElectricCyan,
                    activeTrackColor = ElectricCyan,
                    inactiveTrackColor = NavySurfaceElevated
                ),
                modifier = Modifier.height(26.dp)
            )
        }
    }
}

@Composable
private fun SmartBatteryAlertsCard(
    settings: UserSettings,
    activeAlerts: List<com.example.model.SmartBatteryAlertItem>,
    onUpdateSmartAlertToggles: (
        highAppUsage: Boolean?,
        highBackgroundActivity: Boolean?,
        rapidDrain: Boolean?,
        highBatteryTemp: Boolean?,
        saverRecommendation: Boolean?,
        abnormalScreenDrain: Boolean?
    ) -> Unit
) {
    var showAlertToggles by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_battery_alerts_card"),
        shape = RoundedCornerShape(16.dp),
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
                    .clickable { showAlertToggles = !showAlertToggles },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Smart Battery Alerts",
                        tint = AmberWarning
                    )
                    Column {
                        Text(
                            text = "SMART BATTERY ALERTS (${activeAlerts.size} Active)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap to configure individual alert ON/OFF switches",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = if (showAlertToggles) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Configure Smart Alerts",
                    tint = ElectricCyan
                )
            }

            if (activeAlerts.isEmpty()) {
                Text(
                    text = "No abnormal battery drain, background spike, or thermal anomaly currently active.",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldCharge
                )
            } else {
                activeAlerts.forEach { alert ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = NavySurfaceElevated,
                        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = alert.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AmberWarning,
                                    fontWeight = FontWeight.Bold
                                )
                                StatusBadgePill(text = alert.severityLabel, color = AmberWarning)
                            }
                            Text(
                                text = alert.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showAlertToggles) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))
                    CompactSwitchRow(
                        title = "Abnormal High App Battery / Usage Alert",
                        subtitle = "Alert when an app consumes excessive active foreground time",
                        checked = settings.alertHighAppBatteryUsage,
                        onCheckedChange = { onUpdateSmartAlertToggles(it, null, null, null, null, null) },
                        testTag = "switch_alert_high_app_usage"
                    )
                    CompactSwitchRow(
                        title = "Excessive Background Activity Alert",
                        subtitle = "Alert when apps run heavy background services or ignore optimization",
                        checked = settings.alertHighBackgroundActivity,
                        onCheckedChange = { onUpdateSmartAlertToggles(null, it, null, null, null, null) },
                        testTag = "switch_alert_high_bg"
                    )
                    CompactSwitchRow(
                        title = "Rapid Battery Drop Alert",
                        subtitle = "Alert when discharge current or %/hr rate spikes",
                        checked = settings.alertRapidBatteryDrain,
                        onCheckedChange = { onUpdateSmartAlertToggles(null, null, it, null, null, null) },
                        testTag = "switch_alert_rapid_drain"
                    )
                    CompactSwitchRow(
                        title = "High Battery Temperature Alert",
                        subtitle = "Alert when cell temperature reaches warning threshold",
                        checked = settings.alertHighBatteryTemp,
                        onCheckedChange = { onUpdateSmartAlertToggles(null, null, null, it, null, null) },
                        testTag = "switch_alert_high_temp"
                    )
                    CompactSwitchRow(
                        title = "Battery Saver Recommendation Alert",
                        subtitle = "Suggest enabling Smart Saver when discharging below 30%",
                        checked = settings.alertSaverRecommendation,
                        onCheckedChange = { onUpdateSmartAlertToggles(null, null, null, null, it, null) },
                        testTag = "switch_alert_saver_rec"
                    )
                    CompactSwitchRow(
                        title = "Screen-On vs. Drain Imbalance Alert",
                        subtitle = "Detect high current drain during low screen-on time",
                        checked = settings.alertAbnormalScreenDrain,
                        onCheckedChange = { onUpdateSmartAlertToggles(null, null, null, null, null, it) },
                        testTag = "switch_alert_screen_drain"
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppWiseBatteryTrackerSectionCard(
    trackerState: SmartBatteryDashboardState,
    onSelectAppSection: (AppUsageSectionTab) -> Unit,
    onOpenAppDetails: (AppBatteryUsageRecord) -> Unit,
    onRecordPowerAction: (AppBatteryUsageRecord, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val displayedApps = when (trackerState.selectedAppSection) {
        AppUsageSectionTab.HIGHEST_CONSUMING -> trackerState.highestConsumingApps
        AppUsageSectionTab.MOST_USED -> trackerState.mostUsedApps
        AppUsageSectionTab.BACKGROUND_ACTIVITY -> trackerState.backgroundActivityApps
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_usage_tracker_card"),
        shape = RoundedCornerShape(16.dp),
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
                    imageVector = Icons.Default.AppSettingsAlt,
                    contentDescription = "App-wise Battery Usage Tracker",
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "APP-WISE BATTERY USAGE & POWER MANAGER (${trackerState.allInstalledApps.size} Apps)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Usage Time != Measured mAh • Tap any app for full details & system controls",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // 3 Required Section Tabs (Uses "Estimated Power Contribution" when verified OS per-app battery mAh is unavailable)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppUsageSectionTab.entries.forEach { tab ->
                    val selected = trackerState.selectedAppSection == tab
                    val resolvedTitle = tab.resolveTitle(trackerState.hasVerifiedPerAppBatteryData)
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectAppSection(tab) },
                        label = { Text(resolvedTitle, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("app_tab_${tab.name}")
                    )
                }
            }

            Text(
                text = if (trackerState.hasVerifiedPerAppBatteryData) {
                    "Ranking Mode: Highest Battery Consuming Apps (Verified OS Power Attribution mAh)"
                } else {
                    "Ranking Mode: Estimated Power Contribution (Derived from Foreground Time Share — Direct per-app mAh requires signature|privileged BATTERY_STATS)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (trackerState.hasVerifiedPerAppBatteryData) EmeraldCharge else AmberWarning,
                modifier = Modifier.testTag("ranking_data_source_banner")
            )

            if (displayedApps.isEmpty()) {
                Text(
                    text = "No apps matched this section filter. Grant Usage Access to view foreground and background activity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                displayedApps.take(15).forEach { app ->
                    AppRankingItemCard(
                        app = app,
                        onOpenDetails = { onOpenAppDetails(app) },
                        onOpenSystemAppInfo = {
                            val ok = runCatching {
                                context.startActivity(SmartAppUsageTracker.buildAppInfoSettingsIntent(app.packageName))
                            }.isSuccess
                            onRecordPowerAction(app, "Opened System App Info / Force Stop for ${app.appName}", ok)
                        },
                        onOpenBatterySettings = {
                            val ok = runCatching {
                                context.startActivity(SmartAppUsageTracker.buildAppInfoSettingsIntent(app.packageName))
                            }.isSuccess
                            onRecordPowerAction(app, "Opened App Battery Settings for ${app.appName}", ok)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRankingItemCard(
    app: AppBatteryUsageRecord,
    onOpenDetails: () -> Unit,
    onOpenSystemAppInfo: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("hh:mm a", Locale.US) }
    val lastUsedShort = if (app.lastTimeUsedMillis > 0L) {
        dateFormat.format(Date(app.lastTimeUsedMillis))
    } else {
        "Never / N/A"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = NavySurfaceElevated,
        border = BorderStroke(
            1.dp,
            when {
                app.isProtectedCriticalApp -> EmeraldCharge.copy(alpha = 0.45f)
                app.isHighDrainDetected -> AmberWarning.copy(alpha = 0.55f)
                else -> NavyCardBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetails() }
            .testTag("app_ranking_card_${app.packageName}")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = ElectricCyan.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "#${app.rank}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                AppPackageIcon(
                    packageName = app.packageName,
                    contentDescription = app.appName,
                    modifier = Modifier.size(36.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (app.isProtectedCriticalApp) {
                    StatusBadgePill(text = "Protected", color = EmeraldCharge)
                } else if (app.isHighDrainDetected) {
                    StatusBadgePill(text = "High Usage", color = AmberWarning)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Foreground: ${app.formattedForegroundTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldCharge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Last Used: $lastUsedShort",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Text(
                text = "Verified Battery: ${app.verifiedConsumptionLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = if (app.verifiedBatteryMah != null) EmeraldCharge else TextMuted
            )
            Text(
                text = "Estimated Power Contribution: ${app.estimatedConsumptionLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
            Text(
                text = "Restriction Status: ${app.restrictionExecutionStatus.badgeText} (${app.optimizationStatusLabel})",
                style = MaterialTheme.typography.labelSmall,
                color = if (app.isProtectedCriticalApp) EmeraldCharge else TextSecondary
            )

            // Quick App Power Manager Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenDetails,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .testTag("btn_app_details_${app.packageName}"),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("Details & Trend", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                }
                OutlinedButton(
                    onClick = onOpenSystemAppInfo,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .testTag("btn_app_info_${app.packageName}"),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("App Info / Stop", style = MaterialTheme.typography.labelSmall, color = EmeraldCharge)
                }
                OutlinedButton(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .testTag("btn_app_battery_${app.packageName}"),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("Battery Opt", style = MaterialTheme.typography.labelSmall, color = AmberWarning)
                }
            }
        }
    }
}

@Composable
private fun AutoRestrictionAndAuditReportCard(
    settings: UserSettings,
    trackerState: SmartBatteryDashboardState,
    autoReports: List<AutoManagementReportEntity>,
    onUpdateAutoRestrictionSettings: (Boolean?, Int?) -> Unit,
    onOpenAppDetails: (AppBatteryUsageRecord) -> Unit
) {
    var thresholdSlider by remember(settings.restrictionBatteryThresholdPercent) {
        mutableFloatStateOf(settings.restrictionBatteryThresholdPercent.toFloat())
    }
    val protectedApps = remember(trackerState.allInstalledApps) {
        trackerState.allInstalledApps.filter { it.isProtectedCriticalApp }
    }
    val excludedApps = remember(trackerState.allInstalledApps) {
        trackerState.allInstalledApps.filter { it.isUserExcluded }
    }
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.US) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auto_restriction_and_reports_card"),
        shape = RoundedCornerShape(16.dp),
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
                    imageVector = Icons.Default.AutoMode,
                    contentDescription = "Automatic App Restriction & Audit Report",
                    tint = PurpleVoiceAccent
                )
                Column {
                    Text(
                        text = "AUTO APP RESTRICTION & AUDIT REPORT",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Safe OS-compliant detection • Protected Apps are never auto-restricted",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            CompactSwitchRow(
                title = "Auto Restrict High Battery Usage Apps (<= ${thresholdSlider.roundToInt()}%)",
                subtitle = "Flags user-permitted high-drain apps and opens Android Battery Settings for confirmation",
                checked = settings.autoRestrictHighUsageEnabled,
                onCheckedChange = { onUpdateAutoRestrictionSettings(it, null) },
                testTag = "switch_auto_restrict_apps"
            )

            Slider(
                value = thresholdSlider,
                onValueChange = { thresholdSlider = it },
                onValueChangeFinished = {
                    onUpdateAutoRestrictionSettings(null, thresholdSlider.roundToInt())
                },
                valueRange = 5f..85f,
                colors = SliderDefaults.colors(
                    thumbColor = PurpleVoiceAccent,
                    activeTrackColor = PurpleVoiceAccent,
                    inactiveTrackColor = NavySurfaceElevated
                ),
                modifier = Modifier.height(26.dp)
            )

            Text(
                text = "Protected Apps (${protectedApps.size}): Phone/Dialer, System UI, Alarm Clock, Emergency Services, Battery Boss" +
                    if (protectedApps.isNotEmpty()) " • ${protectedApps.joinToString(", ") { it.appName }}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldCharge
            )
            Text(
                text = "Excluded Apps (${excludedApps.size}): " +
                    if (excludedApps.isEmpty()) "None (Tap any app to add to Excluded list)" else excludedApps.joinToString(", ") { it.appName },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )

            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))

            val selfRestrictedCount = autoReports.count { it.wasActuallyRestrictedByApp }
            val openedSettingsOnlyCount = autoReports.count { it.openedSystemSettingsOnly }
            val flaggedPendingCount = autoReports.count { !it.wasActuallyRestrictedByApp && !it.openedSystemSettingsOnly }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = NavySurfaceElevated,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("restriction_truth_summary_box")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "RESTRICTION VERIFICATION SUMMARY (NO FAKE FORCE-STOP)",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• 3rd-Party Apps Force-Stopped Automatically: 0 (Android prohibits silent 3rd-party force-stop without root/signature permission)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarning
                    )
                    Text(
                        text = "• Internal Battery Boss Saver Applied: $selfRestrictedCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldCharge
                    )
                    Text(
                        text = "• Opened in Android System Settings Only: $openedSettingsOnlyCount • Flagged / Protected: $flaggedPendingCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary
                    )
                }
            }

            Text(
                text = "AUTO MANAGEMENT AUDIT REPORT (${autoReports.size} events)",
                style = MaterialTheme.typography.labelLarge,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold
            )

            if (autoReports.isEmpty()) {
                Text(
                    text = "No automatic Saver transitions or restriction actions logged yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            } else {
                autoReports.take(8).forEach { rep ->
                    val statusBadge = when {
                        rep.wasActuallyRestrictedByApp -> "INTERNAL SAVER APPLIED"
                        rep.openedSystemSettingsOnly -> "OPENED IN SYSTEM SETTINGS ONLY"
                        rep.eventCategory.contains("PROTECTED") -> "PROTECTED • SKIPPED"
                        else -> "FLAGGED • USER ACTION REQUIRED"
                    }
                    val statusColor = when {
                        rep.wasActuallyRestrictedByApp -> EmeraldCharge
                        rep.openedSystemSettingsOnly -> ElectricCyan
                        rep.eventCategory.contains("PROTECTED") -> EmeraldCharge
                        else -> AmberWarning
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = NavySurfaceElevated,
                        border = BorderStroke(1.dp, NavyCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${rep.eventCategory} • ${rep.targetAppName ?: "System"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                StatusBadgePill(text = statusBadge, color = statusColor)
                            }
                            Text(
                                text = "Time: ${dateFormat.format(Date(rep.timestampMillis))} • Usage: ${rep.detectedBatteryUsageSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "Action: ${rep.restrictionOrActionApplied}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelfPowerEfficiencyAuditCard(
    audit: com.example.model.SelfPowerAuditReport
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("self_power_audit_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, EmeraldCharge.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Battery Boss Self-Power Efficiency Audit",
                        tint = EmeraldCharge
                    )
                    Column {
                        Text(
                            text = "BATTERY BOSS SELF-POWER & CPU EFFICIENCY AUDIT",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${audit.monitoringArchitectureLabel} • ${audit.activePollingIntervalLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }
                StatusBadgePill(
                    text = if (audit.activeHeldWakeLocksCount == 0) "0 WAKELOCKS HELD" else "${audit.activeHeldWakeLocksCount} HELD",
                    color = if (audit.activeHeldWakeLocksCount == 0) EmeraldCharge else AmberWarning
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackerMetricCell(
                    title = "PROCESS CPU TIME",
                    value = "${audit.processCpuTimeMs} ms (${String.format(Locale.US, "%.3f%%", audit.cpuDutyCyclePercent)})",
                    sourceBadge = "Process.getElapsedCpuTime",
                    accent = EmeraldCharge,
                    modifier = Modifier.weight(1f)
                )
                TrackerMetricCell(
                    title = "SCREEN-OFF POLLING",
                    value = "0 Hz (Event-Driven)",
                    sourceBadge = "Broadcasts: ${audit.broadcastEventsHandledCount} • Reads: ${audit.screenOnGaugeReadsCount}",
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "WakeLock Safety: ${audit.wakeLockSafetyVerdict}",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldCharge
            )
            Text(
                text = "IPC Cache & Drain: ${audit.packageManagerCacheHitsCount} PM Cache Hits • ${audit.usageStatsQueriesCount} UsageStats Scans • Est. Self-Drain ~${String.format(Locale.US, "%.2f", audit.estimatedSelfDrainPercentPerHour)}%/hr",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomBatteryUsageHistoryCard(
    selectedPeriod: HistoryPeriodFilter,
    historyRecords: List<BatteryUsageHistoryEntity>,
    onSelectPeriod: (HistoryPeriodFilter) -> Unit,
    onDeleteOldHistory: () -> Unit,
    onClearAllTrackerHistory: () -> Unit,
    onExportHistory: () -> Unit
) {
    val cutoffMillis = System.currentTimeMillis() - (selectedPeriod.days * 86_400_000L)
    val filteredRecords = remember(historyRecords, cutoffMillis) {
        historyRecords.filter { it.timestampMillis >= cutoffMillis }
    }
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.US) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("usage_history_records_card"),
        shape = RoundedCornerShape(16.dp),
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
                    imageVector = Icons.Default.History,
                    contentDescription = "Room Battery Usage History",
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "BATTERY USAGE HISTORY (ROOM DB • ${filteredRecords.size} records)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Filter by Daily, Weekly, or Monthly • Delete or Export CSV",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HistoryPeriodFilter.entries.forEach { period ->
                    val selected = selectedPeriod == period
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(period.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("history_period_${period.name}")
                    )
                }
            }

            if (filteredRecords.isEmpty()) {
                Text(
                    text = "No Room database usage records in this time window yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            } else {
                filteredRecords.take(8).forEach { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${dateFormat.format(Date(rec.timestampMillis))} • ${rec.batteryPercentage}% (${rec.batterySaverMode})",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${rec.dischargeRateMa?.let { "${it}mA" } ?: if (rec.isCharging) "CHG" else "N/A"} • ${rec.temperatureCelsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onDeleteOldHistory,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .testTag("btn_delete_old_history"),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("Prune 7d+", style = MaterialTheme.typography.labelSmall, color = AmberWarning)
                }
                OutlinedButton(
                    onClick = onClearAllTrackerHistory,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .testTag("btn_clear_tracker_history"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ThermalCrimson.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = ThermalCrimson, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All", style = MaterialTheme.typography.labelSmall, color = ThermalCrimson)
                }
                Button(
                    onClick = onExportHistory,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .testTag("btn_export_tracker_csv"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldCharge, contentColor = Color(0xFF00210E)),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export CSV", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TrackerMetricCell(
    title: String,
    value: String,
    sourceBadge: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = NavySurfaceElevated,
        border = BorderStroke(1.dp, NavyCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = if (value == "Unavailable") TextSecondary else accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sourceBadge,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
