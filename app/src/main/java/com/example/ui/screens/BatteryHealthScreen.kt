package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.BatteryHardwareSnapshot
import com.example.model.DiagnosticMetric
import com.example.model.MetricConfidence
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

/**
 * Compact, Modern & Scientific Battery Health Dashboard Screen.
 *
 * Strictly preserves all real hardware values from `BatteryManager`, `BatteryProperties`,
 * `PowerProfile`, and kernel `/sys/class/power_supply/battery` nodes without inventing
 * or mocking any battery health percentage or unsupported sensor value.
 */
@Composable
fun BatteryHealthScreen(
    snapshot: BatteryHardwareSnapshot,
    modifier: Modifier = Modifier
) {
    var expandAllGridDetails by rememberSaveable { mutableStateOf(false) }
    var expandAllRegistersCard by rememberSaveable { mutableStateOf(false) }
    var expandThermalDetails by rememberSaveable { mutableStateOf(false) }

    val coreGridSpecs = buildCoreHealthGridSpecs(snapshot)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("battery_health_screen"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Top Hero: Battery Health Summary Card
        item {
            BatteryHealthSummaryHeroCard(snapshot = snapshot)
        }

        // 2. Section Header for the 9 Core Battery Information Cards + Expand/Collapse Details toggle
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BATTERY HEALTH & HARDWARE TELEMETRY",
                        style = MaterialTheme.typography.titleSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap any card or 'API Details' to inspect hardware source & register info",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = { expandAllGridDetails = !expandAllGridDetails },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("btn_toggle_all_health_api_details")
                ) {
                    Text(
                        text = if (expandAllGridDetails) "Hide API Info" else "Show API Info",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldCharge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. 2-Column Grid for the first 8 Battery Information Cards + Full-Width 9th Card (Estimated Time to Full)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("battery_health_metrics_grid"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val pairedRows = coreGridSpecs.take(8).chunked(2)
                pairedRows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { spec ->
                            CompactHealthMetricCard(
                                spec = spec,
                                forceExpanded = expandAllGridDetails,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 9th Item: Estimated Time to Full (Full-width compact card)
                if (coreGridSpecs.size >= 9) {
                    CompactHealthMetricCard(
                        spec = coreGridSpecs[8],
                        forceExpanded = expandAllGridDetails,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 4. Compact & Expandable Thermal & Electrical Stress Profile Card
        item {
            CompactThermalAndPowerExplanationCard(
                snapshot = snapshot,
                expanded = expandThermalDetails,
                onToggleExpanded = { expandThermalDetails = !expandThermalDetails }
            )
        }

        // 5. Collapsible Complete Hardware Diagnostic Registers Card (All 15 Registers with non-overlapping badges)
        item {
            CollapsibleDiagnosticRegistersCard(
                metrics = snapshot.diagnosticMetrics,
                expanded = expandAllRegistersCard,
                onToggleExpanded = { expandAllRegistersCard = !expandAllRegistersCard }
            )
        }
    }
}

/**
 * Top Hero Card: Battery Health Summary Card
 * Displays:
 * - Battery Health Status (Good / Overheat / etc.)
 * - Current Battery Percentage
 * - Charging Status
 * - Full Charge Capacity (mAh)
 * - Design Capacity (mAh)
 * - Battery Cycle Count
 * Never fabricates numeric battery health % when hardware registers are unavailable.
 */
fun healthStatusColorForLabel(reportedOsHealthStatus: String): Color = when (reportedOsHealthStatus) {
    "Good" -> EmeraldCharge
    "Overheating", "Over Voltage", "Dead / Depleted", "Hardware Failure" -> ThermalCrimson
    else -> AmberWarning
}

@Composable
private fun BatteryHealthSummaryHeroCard(snapshot: BatteryHardwareSnapshot) {
    val report = snapshot.healthReport
    var showHonestyDetails by rememberSaveable { mutableStateOf(false) }

    val statusColor = healthStatusColorForLabel(report.reportedOsHealthStatus)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("battery_health_summary_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Health Status + Current Percentage & Charging Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.HealthAndSafety,
                            contentDescription = "Battery Health Status",
                            tint = statusColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "BATTERY HEALTH SUMMARY",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = report.reportedOsHealthStatus.uppercase(Locale.US),
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            HardwareRegisterBadge(
                                confidence = MetricConfidence.QUALITATIVE_OS,
                                compact = true
                            )
                        }
                    }
                }

                // Current Battery % & Charging Status Badge
                Surface(
                    color = if (snapshot.isCharging) EmeraldCharge.copy(alpha = 0.16f) else NavySurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (snapshot.isCharging) EmeraldCharge.copy(alpha = 0.6f) else NavyCardBorder
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    snapshot.isCharging -> Icons.Default.BatteryChargingFull
                                    snapshot.isFull -> Icons.Default.BatteryFull
                                    else -> Icons.Default.BatteryStd
                                },
                                contentDescription = snapshot.statusLabel,
                                tint = if (snapshot.isCharging) EmeraldCharge else ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${snapshot.percentage}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = snapshot.statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (snapshot.isCharging) EmeraldCharge else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 2x2 Summary Pills Grid: Full Charge Capacity, Design Capacity, Cycle Count, Capacity Retention
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SummaryMetricTile(
                        label = "Full Charge Capacity",
                        value = report.fullChargeCapacityMah?.let { "$it mAh" } ?: "N/A",
                        subLabel = when (report.fullChargeCapacityConfidence) {
                            MetricConfidence.HIGH_DIRECT -> "charge_full register"
                            MetricConfidence.MEDIUM_DERIVED -> "Derived / Cached"
                            else -> "Kernel restricted"
                        },
                        accent = if (report.fullChargeCapacityMah != null) EmeraldCharge else TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryMetricTile(
                        label = "Design Capacity",
                        value = report.designCapacityMah?.let { "$it mAh" } ?: "N/A",
                        subLabel = when (report.designCapacityConfidence) {
                            MetricConfidence.HIGH_DIRECT -> "Hardware register"
                            MetricConfidence.MEDIUM_DERIVED -> "OS PowerProfile"
                            else -> "Not exposed"
                        },
                        accent = if (report.designCapacityMah != null) ElectricCyan else TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SummaryMetricTile(
                        label = "Battery Cycle Count",
                        value = report.cycleCount?.let { "$it cycles" } ?: "N/A",
                        subLabel = if (report.cycleCount != null) "Hardware register" else "Not reported",
                        accent = if (report.cycleCount != null) EmeraldCharge else TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryMetricTile(
                        label = "Capacity Retention",
                        value = report.measuredCapacityHealthPercent?.let {
                            String.format(Locale.US, "%.1f%%", it)
                        } ?: "N/A",
                        subLabel = when {
                            report.isCapacityDataInconsistent -> "Inconsistent Hardware Data"
                            report.measuredCapacityHealthPercent != null -> "Full / Design ratio"
                            else -> "No fake %"
                        },
                        accent = when {
                            report.isCapacityDataInconsistent -> ThermalCrimson
                            report.measuredCapacityHealthPercent != null -> PurpleVoiceAccent
                            else -> TextMuted
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Compact Expandable Scientific Note & Wear Assessment
            Surface(
                color = NavySurfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, NavyCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHonestyDetails = !showHonestyDetails }
                    .testTag("health_honesty_note_banner")
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "OS Health Flag Info",
                                tint = AmberWarning,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Android '${report.reportedOsHealthStatus}' is a safety flag, NOT 100% capacity retention.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = if (showHonestyDetails) 4 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = if (showHonestyDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand health explanation",
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(visible = showHonestyDetails) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                            Text(
                                text = report.osHealthExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = report.degradationSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricTile(
    label: String,
    value: String,
    subLabel: String,
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (value == "N/A") TextMuted else accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Specification for each of the 9 core Battery Information Cards.
 */
private data class HealthCardSpec(
    val id: String,
    val title: String,
    val primaryValue: String,
    val shortSubtitle: String,
    val apiPath: String,
    val technicalExplanation: String,
    val confidence: MetricConfidence,
    val icon: ImageVector,
    val accentColor: Color
)

private fun buildCoreHealthGridSpecs(snapshot: BatteryHardwareSnapshot): List<HealthCardSpec> {
    val report = snapshot.healthReport

    // 1. Battery Temperature
    val tempVal = snapshot.temperatureCelsius?.let {
        String.format(Locale.US, "%.1f °C", it)
    } ?: "N/A"
    val tempSub = snapshot.temperatureCelsius?.let { c ->
        val f = c * 9f / 5f + 32f
        String.format(Locale.US, "%.1f °F • Pack thermistor", f)
    } ?: "Thermistor unavailable"
    val tempColor = when {
        (snapshot.temperatureCelsius ?: 0f) >= 43f -> ThermalCrimson
        (snapshot.temperatureCelsius ?: 0f) >= 38f -> AmberWarning
        snapshot.temperatureCelsius != null -> EmeraldCharge
        else -> TextMuted
    }

    // 2. Battery Voltage
    val voltageVal = snapshot.voltageVolts?.let {
        String.format(Locale.US, "%.3f V", it)
    } ?: "N/A"
    val voltageSub = snapshot.voltageMillivolts?.let { "$it mV cell terminal" } ?: "Voltage not reported"

    // 3. Charging Source
    val sourceVal = snapshot.chargingSource.displayName
    val sourceSub = if (snapshot.isCharging) "Active power rail (${snapshot.statusLabel})" else "Running on battery pack"

    // 4. Remaining Capacity / Charge Counter
    val remCapVal = snapshot.chargeCounterMah?.let { "$it mAh" } ?: "N/A"
    val remCapSub = if (snapshot.chargeCounterMah != null) {
        "Live coulomb counter"
    } else {
        "Coulomb counter unexposed"
    }

    // 5. Battery Cycle Count
    val cycleVal = snapshot.cycleCount?.let { "$it cycles" } ?: "N/A"
    val cycleSub = if (snapshot.cycleCount != null) {
        "Charge-discharge cycles"
    } else {
        "Not exposed by kernel"
    }

    // 6. Design Capacity (Factory)
    val designVal = snapshot.designCapacityMah?.let { "$it mAh" } ?: "N/A"
    val designSub = if (snapshot.designCapacityMah != null) {
        "Nominal rated capacity"
    } else {
        "Factory register restricted"
    }

    // 7. Full Charge Capacity
    val fullCapVal = snapshot.fullChargeCapacityMah?.let { "$it mAh" } ?: "N/A"
    val fullCapSub = if (snapshot.fullChargeCapacityMah != null) {
        "Measured at 100% charge"
    } else {
        "Requires 100% charge / sysfs"
    }

    // 8. Energy Counter
    val energyVal = snapshot.energyCounterWh?.let {
        String.format(Locale.US, "%.2f Wh", it)
    } ?: "N/A"
    val energySub = if (snapshot.energyCounterWh != null) {
        "Remaining watt-hours"
    } else {
        "PMIC reports mAh only"
    }

    // 9. Estimated Time to Full
    val etaVal = snapshot.estimatedMinutesToFull?.let { mins ->
        if (mins >= 60) "${mins / 60}h ${mins % 60}m ($mins min)" else "$mins min"
    } ?: if (snapshot.isFull) "Full (100%)" else "N/A"
    val etaSub = when {
        snapshot.estimatedMinutesToFull != null -> "Includes CC/CV taper allowance"
        snapshot.isFull -> "Battery is fully charged"
        snapshot.isCharging -> "Awaiting positive current sensor"
        else -> "Only active while charging"
    }

    return listOf(
        HealthCardSpec(
            id = "temperature",
            title = "Battery Temperature",
            primaryValue = tempVal,
            shortSubtitle = tempSub,
            apiPath = "BatteryManager.EXTRA_TEMPERATURE",
            technicalExplanation = if (snapshot.temperatureCelsius != null) {
                "NTC thermistor reading inside the battery pack (reported in 0.1°C steps)."
            } else {
                "N/A — Battery thermistor reading is not reported by hardware."
            },
            confidence = if (snapshot.temperatureCelsius != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            icon = Icons.Default.Thermostat,
            accentColor = tempColor
        ),
        HealthCardSpec(
            id = "voltage",
            title = "Battery Voltage",
            primaryValue = voltageVal,
            shortSubtitle = voltageSub,
            apiPath = "BatteryManager.EXTRA_VOLTAGE",
            technicalExplanation = if (snapshot.voltageVolts != null) {
                "Terminal voltage measured directly across the lithium cell pack."
            } else {
                "N/A — Kernel did not include EXTRA_VOLTAGE in the battery broadcast."
            },
            confidence = if (snapshot.voltageVolts != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            icon = Icons.Default.Bolt,
            accentColor = if (snapshot.voltageVolts != null) ElectricCyan else TextMuted
        ),
        HealthCardSpec(
            id = "charging_source",
            title = "Charging Source",
            primaryValue = sourceVal,
            shortSubtitle = sourceSub,
            apiPath = "BatteryManager.EXTRA_PLUGGED",
            technicalExplanation = "Active power rail negotiated by the PMIC (AC Charger, USB Port, Wireless Pad, Dock, or On Battery).",
            confidence = MetricConfidence.HIGH_DIRECT,
            icon = Icons.Default.Power,
            accentColor = if (snapshot.isCharging) EmeraldCharge else ElectricCyan
        ),
        HealthCardSpec(
            id = "remaining_capacity",
            title = "Remaining Capacity",
            primaryValue = remCapVal,
            shortSubtitle = remCapSub,
            apiPath = report.remainingCapacitySource,
            technicalExplanation = if (snapshot.chargeCounterMah != null) {
                "Remaining charge in milliampere-hours converted from the hardware fuel gauge µAh coulomb counter."
            } else {
                "N/A — Hardware coulomb counter (BATTERY_PROPERTY_CHARGE_COUNTER) is not exposed on this device."
            },
            confidence = if (snapshot.chargeCounterMah != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            icon = Icons.Default.Memory,
            accentColor = if (snapshot.chargeCounterMah != null) EmeraldCharge else TextMuted
        ),
        HealthCardSpec(
            id = "cycle_count",
            title = "Battery Cycle Count",
            primaryValue = cycleVal,
            shortSubtitle = cycleSub,
            apiPath = report.cycleCountSource,
            technicalExplanation = if (snapshot.cycleCount != null) {
                "Total full-equivalent charge-discharge cycles recorded by the fuel gauge register."
            } else {
                "N/A — Cycle count requires Android 14+ EXTRA_CYCLE_COUNT support or unrestricted kernel sysfs access."
            },
            confidence = if (snapshot.cycleCount != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            icon = Icons.Default.Cached,
            accentColor = if (snapshot.cycleCount != null) EmeraldCharge else TextMuted
        ),
        HealthCardSpec(
            id = "design_capacity",
            title = "Design Capacity (Factory)",
            primaryValue = designVal,
            shortSubtitle = when (report.designCapacityConfidence) {
                MetricConfidence.HIGH_DIRECT -> "Direct hardware register"
                MetricConfidence.MEDIUM_DERIVED -> "OS PowerProfile config"
                else -> "Factory register restricted"
            },
            apiPath = report.designCapacitySource,
            technicalExplanation = when (report.designCapacityConfidence) {
                MetricConfidence.HIGH_DIRECT -> "Nominal rated battery capacity read directly from kernel charge_full_design register (µAh → mAh)."
                MetricConfidence.MEDIUM_DERIVED -> "Nominal factory capacity read from Android OS PowerProfile XML (static OEM framework config — not a live hardware register)."
                else -> "N/A — Factory design capacity register is restricted on this build."
            },
            confidence = report.designCapacityConfidence,
            icon = Icons.Default.Factory,
            accentColor = if (snapshot.designCapacityMah != null) ElectricCyan else TextMuted
        ),
        HealthCardSpec(
            id = "full_charge_capacity",
            title = "Full Charge Capacity",
            primaryValue = fullCapVal,
            shortSubtitle = when (report.fullChargeCapacityConfidence) {
                MetricConfidence.HIGH_DIRECT -> "charge_full register"
                MetricConfidence.MEDIUM_DERIVED -> "Derived / cached source"
                else -> "charge_full not exposed"
            },
            apiPath = report.fullChargeCapacitySource,
            technicalExplanation = when (report.fullChargeCapacityConfidence) {
                MetricConfidence.HIGH_DIRECT -> "Verified full-charge capacity read directly from kernel charge_full fuel-gauge register (µAh → mAh)."
                MetricConfidence.MEDIUM_DERIVED -> "Derived or cached full-charge capacity estimate (not a direct hardware register)."
                else -> "N/A — Kernel blocks charge_full sysfs node; Charge Counter at 100% is never assumed to be Full Charge Capacity."
            },
            confidence = report.fullChargeCapacityConfidence,
            icon = Icons.Default.BatteryFull,
            accentColor = if (snapshot.fullChargeCapacityMah != null) EmeraldCharge else TextMuted
        ),
        HealthCardSpec(
            id = "energy_counter",
            title = "Energy Counter",
            primaryValue = energyVal,
            shortSubtitle = energySub,
            apiPath = "BATTERY_PROPERTY_ENERGY_COUNTER",
            technicalExplanation = if (snapshot.energyCounterWh != null) {
                "Remaining energy in watt-hours reported by the hardware fuel gauge."
            } else {
                "N/A — Most smartphone PMICs report coulomb charge (mAh) rather than energy (nWh)."
            },
            confidence = if (snapshot.energyCounterWh != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            icon = Icons.Default.ElectricBolt,
            accentColor = if (snapshot.energyCounterWh != null) PurpleVoiceAccent else TextMuted
        ),
        HealthCardSpec(
            id = "estimated_time_to_full",
            title = "Estimated Time to Full",
            primaryValue = etaVal,
            shortSubtitle = etaSub,
            apiPath = "BatteryManager.computeChargeTimeRemaining() / Coulomb Delta",
            technicalExplanation = if (snapshot.estimatedMinutesToFull != null) {
                "Estimated minutes remaining until 100% full charge (includes CC/CV taper allowance)."
            } else {
                "N/A — Only available while actively charging with a valid positive current sensor."
            },
            confidence = if (snapshot.estimatedMinutesToFull != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            icon = Icons.Default.AccessTime,
            accentColor = if (snapshot.estimatedMinutesToFull != null) EmeraldCharge else TextMuted
        )
    )
}

/**
 * Compact 2-Column / Full-Width Battery Information Card.
 *
 * Guarantees:
 * - Zero badge/text overlap: The Hardware Register Badge is a compact pill placed in its own row
 *   or alongside the value with sufficient width so text never breaks letter-by-letter.
 * - Concise 1-line subtitle by default.
 * - Expandable Details drawer showing the exact API path, full hardware confidence label, and technical explanation.
 */
@Composable
private fun CompactHealthMetricCard(
    spec: HealthCardSpec,
    forceExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    var localExpanded by rememberSaveable(spec.id) { mutableStateOf(false) }
    val isExpanded = forceExpanded || localExpanded
    val isUnavailable = spec.primaryValue == "N/A"

    Card(
        modifier = modifier
            .clickable { localExpanded = !localExpanded }
            .testTag("health_metric_card_${spec.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isExpanded) ElectricCyan.copy(alpha = 0.55f) else NavyCardBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Row 1: Icon + Metric Title + Expand Chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = spec.title,
                        tint = spec.accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = spec.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle ${spec.title} API details",
                    tint = TextMuted,
                    modifier = Modifier.size(15.dp)
                )
            }

            // Row 2: Crisp Full-Width Primary Value (Never collides with badge)
            Text(
                text = spec.primaryValue,
                style = if (isUnavailable) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = if (isUnavailable) TextMuted else spec.accentColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Row 3: Concise 1-line Subtitle
            Text(
                text = spec.shortSubtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Row 4: Dedicated Non-Overlapping Hardware Register Badge Pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                HardwareRegisterBadge(
                    confidence = spec.confidence,
                    compact = true
                )
            }

            // Expandable API Path & Technical Explanation Section
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.6f))
                    HardwareRegisterBadge(
                        confidence = spec.confidence,
                        compact = false
                    )
                    Text(
                        text = "API: ${spec.apiPath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = spec.technicalExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Compact & Collapsible Thermal & Cell-Side vs. Wall Power Explanation Card.
 */
@Composable
private fun CompactThermalAndPowerExplanationCard(
    snapshot: BatteryHardwareSnapshot,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val report = snapshot.healthReport

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpanded() }
            .testTag("thermal_stress_profile_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        border = BorderStroke(1.dp, NavyCardBorder)
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
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = "Thermal & Electrical Stress Profile",
                        tint = AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "THERMAL & CELL-SIDE POWER PROFILE",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = report.temperatureStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand thermal & power explanation",
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                    Text(
                        text = "Battery-Side vs. Wall Charger Input Power:",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Android's BatteryManager measures current and voltage directly at the battery cell terminals (${snapshot.voltageVolts?.let { String.format(Locale.US, "%.2f V", it) } ?: "N/A"}). When charging with a fast adapter, total wall/USB-C input power is higher than measured cell-side power (${snapshot.powerWatts?.let { String.format(Locale.US, "%.2f W", abs(it)) } ?: "N/A"}) because part of the adapter's power runs the display/SoC and ~8–12% is dissipated as heat in the PMIC step-down converter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Collapsible Complete Hardware Diagnostic Registers Card.
 * Lists all 15 diagnostic metrics with non-overlapping Hardware Register badges
 * and clean API source labels.
 */
@Composable
private fun CollapsibleDiagnosticRegistersCard(
    metrics: List<DiagnosticMetric>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val availableCount = metrics.count { it.confidence != MetricConfidence.UNAVAILABLE }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("complete_diagnostic_registers_card"),
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
                    .clickable { onToggleExpanded() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeveloperBoard,
                        contentDescription = "Complete Hardware Diagnostic Registers",
                        tint = EmeraldCharge,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "ALL HARDWARE DIAGNOSTIC REGISTERS ($availableCount/${metrics.size} ACTIVE)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (expanded) "Tap to collapse full register table" else "Tap to inspect all ${metrics.size} Android API & kernel registers",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle complete register list",
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.forEachIndexed { idx, metric ->
                        if (idx > 0) {
                            HorizontalDivider(color = NavyCardBorder.copy(alpha = 0.5f))
                        }
                        DiagnosticRegisterRow(metric = metric)
                    }
                }
            }
        }
    }
}

/**
 * Clean, non-overlapping register row:
 * - Row 1: Metric Title (left, weighted) + Value (right, bold)
 * - Row 2: HardwareRegisterBadge pill (left, never squeezed)
 * - Row 3: API Path (`API: ...`) on its own full width so it never collides with the badge
 * - Row 4: Concise technical explanation
 */
@Composable
private fun DiagnosticRegisterRow(metric: DiagnosticMetric) {
    val isUnavailable = metric.value == "N/A"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = metric.title,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = metric.value,
                style = MaterialTheme.typography.labelMedium,
                color = if (isUnavailable) TextMuted else ElectricCyan,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HardwareRegisterBadge(
                confidence = metric.confidence,
                compact = false
            )
        }

        Text(
            text = "API: ${metric.sourceApi}",
            style = MaterialTheme.typography.labelSmall,
            color = ElectricCyan.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = metric.explanation,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

/**
 * Consistent, Non-Overlapping Hardware Register Status Pill Badge.
 *
 * - `compact = true`: Short pill placed next to the metric value inside 2-column cards
 *   (`Direct Register`, `Derived`, `OS Flag`, `N/A`) with `softWrap = false` and `maxLines = 1`
 *   so it NEVER breaks into vertical single letters.
 * - `compact = false`: Full descriptive pill (`High • Direct Hardware Register`,
 *   `Medium • Derived From Hardware`, `Qualitative • OS Status Flag`, `Unavailable on Device`)
 *   placed on its own dedicated line inside expanded details.
 */
@Composable
private fun HardwareRegisterBadge(
    confidence: MetricConfidence,
    compact: Boolean
) {
    val (badgeColor, shortText) = when (confidence) {
        MetricConfidence.HIGH_DIRECT -> EmeraldCharge to "Direct Register"
        MetricConfidence.MEDIUM_DERIVED -> ElectricCyan to "Derived"
        MetricConfidence.QUALITATIVE_OS -> AmberWarning to "OS Flag"
        MetricConfidence.UNAVAILABLE -> TextMuted to "Unavailable"
    }

    val displayText = if (compact) shortText else confidence.badgeText

    Surface(
        color = badgeColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
