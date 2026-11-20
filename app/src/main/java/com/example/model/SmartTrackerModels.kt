package com.example.model

/**
 * Three Smart Battery Saver operating modes for Battery Boss 2.0.
 *
 * POWER EFFICIENCY AUDIT GUARANTEES:
 * - Zero background polling when the device screen is OFF across all modes (100% broadcast event-driven).
 * - Never claims to silently force-stop third-party apps without Android OS permission.
 * - Normal Mode: Event-driven broadcasts + 15s lightweight gauge supplement only while screen is ON.
 * - Smart Saver Mode: Event-driven broadcasts + 30s screen-on gauge supplement, 3m app scan throttle, animations OFF.
 * - Extreme Saver Mode: 100% Pure Event-Driven (0 periodic polling loops even when screen is ON),
 *   pauses background UsageStats scans & routine P3 milestones while preserving Critical Low Battery & Thermal alerts.
 */
enum class BatterySaverMode(
    val title: String,
    val badge: String,
    val summary: String,
    val telemetryIntervalMs: Long,
    val graphSampleIntervalMs: Long,
    val appUsageScanMinIntervalMs: Long,
    val dbSnapshotMinIntervalMs: Long,
    val isPureEventDriven: Boolean,
    val screenOffPollingEnabled: Boolean,
    val disableAnimations: Boolean,
    val suppressNonCriticalVoiceAlerts: Boolean
) {
    NORMAL(
        title = "Normal Mode",
        badge = "NORMAL",
        summary = "Event-driven broadcasts (0 Hz polling when Screen OFF) + 15s screen-on current gauge supplement & full animations.",
        telemetryIntervalMs = 15_000L,
        graphSampleIntervalMs = 10_000L,
        appUsageScanMinIntervalMs = 60_000L,
        dbSnapshotMinIntervalMs = 5 * 60_000L,
        isPureEventDriven = false,
        screenOffPollingEnabled = false,
        disableAnimations = false,
        suppressNonCriticalVoiceAlerts = false
    ),
    SMART_SAVER(
        title = "Smart Saver Mode",
        badge = "SMART SAVER",
        summary = "Event-driven + reduced 30s screen-on gauge interval (0 Hz when Screen OFF), 3m app scan throttle, and UI animations disabled.",
        telemetryIntervalMs = 30_000L,
        graphSampleIntervalMs = 30_000L,
        appUsageScanMinIntervalMs = 180_000L,
        dbSnapshotMinIntervalMs = 15 * 60_000L,
        isPureEventDriven = false,
        screenOffPollingEnabled = false,
        disableAnimations = true,
        suppressNonCriticalVoiceAlerts = false
    ),
    EXTREME_SAVER(
        title = "Extreme Saver Mode",
        badge = "EXTREME SAVER",
        summary = "100% Pure Event-Driven (0 periodic polling loops), pauses background app scans & routine milestones, preserves Critical Low Battery & Thermal alerts.",
        telemetryIntervalMs = 0L,
        graphSampleIntervalMs = 60_000L,
        appUsageScanMinIntervalMs = 0L,
        dbSnapshotMinIntervalMs = 30 * 60_000L,
        isPureEventDriven = true,
        screenOffPollingEnabled = false,
        disableAnimations = true,
        suppressNonCriticalVoiceAlerts = true
    )
}

/**
 * Explicit execution status for Auto App Restriction & App Power Manager actions.
 * Strictly distinguishes between apps actually restricted by OS/privileged permission vs.
 * apps merely opened in Android System Settings or flagged for user confirmation.
 */
enum class RestrictionExecutionStatus(
    val badgeText: String,
    val explanation: String,
    val isActuallyRestricted: Boolean,
    val isOpenedInSettingsOnly: Boolean
) {
    VERIFIED_OS_RESTRICTED(
        badgeText = "VERIFIED OS RESTRICTED",
        explanation = "Android OS confirmed background/battery optimization restriction is active",
        isActuallyRestricted = true,
        isOpenedInSettingsOnly = false
    ),
    OPENED_SYSTEM_SETTINGS_ONLY(
        badgeText = "OPENED IN SYSTEM SETTINGS ONLY",
        explanation = "System Settings screen opened; NOT restricted or Force Stopped by Battery Boss until user confirms in OS Settings",
        isActuallyRestricted = false,
        isOpenedInSettingsOnly = true
    ),
    FLAGGED_PENDING_USER_ACTION(
        badgeText = "FLAGGED • USER ACTION REQUIRED",
        explanation = "High usage detected; NOT restricted automatically (3rd-party apps lack signature FORCE_STOP_PACKAGES permission)",
        isActuallyRestricted = false,
        isOpenedInSettingsOnly = false
    ),
    SKIPPED_PROTECTED_CRITICAL(
        badgeText = "PROTECTED • NEVER RESTRICTED",
        explanation = "Critical system or user-protected app; exempt from restriction",
        isActuallyRestricted = false,
        isOpenedInSettingsOnly = false
    ),
    SKIPPED_USER_EXCLUDED(
        badgeText = "EXCLUDED BY USER",
        explanation = "Excluded by user preference; no restriction applied",
        isActuallyRestricted = false,
        isOpenedInSettingsOnly = false
    ),
    SELF_INTERNAL_SAVER_APPLIED(
        badgeText = "INTERNAL SAVER APPLIED",
        explanation = "Battery Boss reduced its own monitoring workload and UI power consumption",
        isActuallyRestricted = true,
        isOpenedInSettingsOnly = false
    )
}

/**
 * Real-time self-monitoring telemetry for Battery Boss 2.0's own CPU, WakeLock, and background workload.
 */
data class SelfPowerAuditReport(
    val processCpuTimeMs: Long = 0L,
    val processUptimeMs: Long = 1L,
    val cpuDutyCyclePercent: Float = 0f,
    val isScreenInteractive: Boolean = true,
    val activeSaverMode: BatterySaverMode = BatterySaverMode.NORMAL,
    val monitoringArchitectureLabel: String = "Event-Driven Broadcasts (0 Hz Polling when Screen OFF)",
    val activePollingIntervalLabel: String = "15s (Screen ON Only • 0s Screen OFF)",
    val broadcastEventsHandledCount: Int = 0,
    val screenOnGaugeReadsCount: Int = 0,
    val usageStatsQueriesCount: Int = 0,
    val packageManagerCacheHitsCount: Int = 0,
    val wakeLockAcquisitionsCount: Int = 0,
    val wakeLockReleasesCount: Int = 0,
    val activeHeldWakeLocksCount: Int = 0,
    val totalWakeLockHeldDurationMs: Long = 0L,
    val maxWakeLockTimeoutMs: Long = 3_000L,
    val notificationUpdatesSentCount: Int = 0,
    val notificationUpdatesSkippedCount: Int = 0,
    val estimatedSelfDrainPercentPerHour: Float = 0.04f
) {
    val wakeLockSafetyVerdict: String
        get() = if (activeHeldWakeLocksCount == 0 && wakeLockAcquisitionsCount == wakeLockReleasesCount) {
            "0 Active WakeLocks Held (${wakeLockReleasesCount}/${wakeLockAcquisitionsCount} Released • Total Held ${totalWakeLockHeldDurationMs}ms)"
        } else {
            "$activeHeldWakeLocksCount Active WakeLock Held (Bounded <= ${maxWakeLockTimeoutMs}ms • ${wakeLockReleasesCount}/${wakeLockAcquisitionsCount} Released)"
        }
}

/**
 * Automatic Battery Saver trigger presets.
 */
enum class AutoSaverTriggerPreset(
    val thresholdPercent: Int,
    val targetMode: BatterySaverMode,
    val label: String
) {
    AT_50_SMART(50, BatterySaverMode.SMART_SAVER, "50% • Auto Smart Saver"),
    AT_30_SMART(30, BatterySaverMode.SMART_SAVER, "30% • Auto Smart Saver"),
    AT_20_SMART(20, BatterySaverMode.SMART_SAVER, "20% • Auto Smart Saver"),
    AT_15_EXTREME(15, BatterySaverMode.EXTREME_SAVER, "15% • Auto Extreme Saver"),
    CUSTOM(25, BatterySaverMode.SMART_SAVER, "Custom %")
}

/**
 * Time windows for the Battery Consumption Graph on the Smart Battery Tracker Dashboard.
 */
enum class ConsumptionGraphWindow(val hours: Int, val label: String) {
    HOURS_1(1, "1 Hour"),
    HOURS_6(6, "6 Hours"),
    HOURS_12(12, "12 Hours"),
    HOURS_24(24, "24 Hours")
}

/**
 * Time windows for viewing historical Room DB records.
 */
enum class HistoryPeriodFilter(val days: Int, val label: String) {
    DAILY(1, "Daily (24h)"),
    WEEKLY(7, "Weekly (7d)"),
    MONTHLY(30, "Monthly (30d)")
}

/**
 * Three required sections for the App-wise Battery Usage Tracker.
 * Note: HIGHEST_CONSUMING only uses the "Highest Battery Consuming" label when verified OS battery data
 * is present; otherwise it transparently labels the ranking "Estimated Power Contribution".
 */
enum class AppUsageSectionTab(val title: String, val subtitle: String) {
    HIGHEST_CONSUMING(
        title = "Estimated Power Contribution",
        subtitle = "Ranked by verified OS energy if available; otherwise usage-derived Estimated Power Contribution"
    ),
    MOST_USED(
        title = "Most Used Apps",
        subtitle = "Ranked by verified UsageStatsManager foreground time"
    ),
    BACKGROUND_ACTIVITY(
        title = "Background Activity Apps",
        subtitle = "Apps with foreground service / background execution or unrestricted battery state"
    );

    fun resolveTitle(hasVerifiedPerAppBatteryData: Boolean): String {
        return when (this) {
            HIGHEST_CONSUMING -> if (hasVerifiedPerAppBatteryData) {
                "Highest Battery Consuming (Verified mAh)"
            } else {
                "Estimated Power Contribution"
            }
            MOST_USED -> title
            BACKGROUND_ACTIVITY -> title
        }
    }
}

/**
 * Smart Battery Alert types that can be individually enabled/disabled in UserSettings.
 */
enum class SmartBatteryAlertType(
    val id: String,
    val title: String,
    val description: String
) {
    ABNORMAL_APP_DRAIN(
        id = "abnormal_app_drain",
        title = "High App Usage / Power Contribution Alert",
        description = "Alerts when an app exhibits unusually high active usage or estimated power share"
    ),
    HIGH_BACKGROUND_ACTIVITY(
        id = "high_background_activity",
        title = "Heavy Background Activity Alert",
        description = "Alerts when apps run extended background services or bypass OS battery optimization"
    ),
    RAPID_BATTERY_DROP(
        id = "rapid_battery_drop",
        title = "Rapid Battery Discharge Alert",
        description = "Alerts when battery discharge rate exceeds normal current/percentage thresholds"
    ),
    HIGH_TEMPERATURE(
        id = "high_temperature",
        title = "Elevated Battery Temperature Alert",
        description = "Alerts when battery temperature rises above safe operating limits"
    ),
    SAVER_RECOMMENDATION(
        id = "saver_recommendation",
        title = "Smart Battery Saver Recommendation",
        description = "Recommends enabling Smart or Extreme Saver when battery is low and discharging"
    ),
    SCREEN_CONSUMPTION_ANOMALY(
        id = "screen_consumption_anomaly",
        title = "Screen-On vs. Drain Imbalance",
        description = "Detects abnormal battery drop relative to screen-on / screen-off time"
    )
}

/**
 * Active smart alert surfaced in the Smart Battery Tracker.
 */
data class SmartBatteryAlertItem(
    val type: SmartBatteryAlertType,
    val title: String,
    val message: String,
    val severityLabel: String,
    val targetPackageName: String? = null,
    val targetAppName: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Per-app usage & power telemetry record.
 * Strictly separates Usage Time (UsageStatsManager) from Battery Energy Consumption (mAh / %),
 * and never claims an app is "Force Stopped" or "Restricted" without verified OS permission.
 */
data class AppBatteryUsageRecord(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val isProtectedCriticalApp: Boolean,
    val protectedReason: String? = null,
    val isUserExcluded: Boolean = false,
    val isUserPermittedForAutoRestrict: Boolean = false,
    val foregroundTimeMillis: Long,
    val backgroundServiceTimeMillis: Long?,
    val usageSourceApi: String,
    val usageConfidence: MetricConfidence,
    val verifiedBatteryMah: Float? = null,
    val verifiedBatteryPercent: Float? = null,
    val estimatedBatteryMah: Float? = null,
    val estimatedBatteryPercent: Float? = null,
    val energySourceApi: String,
    val energyConfidence: MetricConfidence,
    val lastTimeUsedMillis: Long,
    val isIgnoringBatteryOptimizations: Boolean?,
    val optimizationStatusLabel: String,
    val restrictionExecutionStatus: RestrictionExecutionStatus = RestrictionExecutionStatus.FLAGGED_PENDING_USER_ACTION,
    val rank: Int = 0,
    val isHighDrainDetected: Boolean = false,
    val isHighBackgroundDetected: Boolean = false
) {
    val formattedForegroundTime: String
        get() = formatDurationMillis(foregroundTimeMillis)

    val formattedBackgroundTime: String
        get() = backgroundServiceTimeMillis?.let { formatDurationMillis(it) } ?: "Unavailable (OS API Restricted)"

    val verifiedConsumptionLabel: String
        get() = when {
            verifiedBatteryMah != null && verifiedBatteryPercent != null ->
                String.format(java.util.Locale.US, "%.1f mAh (%.1f%%) • Verified OS Data", verifiedBatteryMah, verifiedBatteryPercent)
            verifiedBatteryMah != null ->
                String.format(java.util.Locale.US, "%.1f mAh • Verified OS Data", verifiedBatteryMah)
            else -> "Unavailable (Requires privileged BATTERY_STATS)"
        }

    val estimatedConsumptionLabel: String
        get() = when {
            estimatedBatteryMah != null && estimatedBatteryPercent != null ->
                String.format(java.util.Locale.US, "~%.1f mAh (~%.1f%%) • Estimated Power Contribution", estimatedBatteryMah, estimatedBatteryPercent)
            estimatedBatteryPercent != null ->
                String.format(java.util.Locale.US, "~%.1f%% • Estimated Power Contribution", estimatedBatteryPercent)
            else -> "Unavailable (Grant Usage Access for Estimate)"
        }

    companion object {
        fun formatDurationMillis(millis: Long): String {
            if (millis <= 0L) return "0m"
            val totalSeconds = millis / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L -> "${hours}h ${minutes}m"
                minutes > 0L -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}

/**
 * Aggregated state for the Smart Battery Tracker & App Power Manager Dashboard.
 */
data class SmartBatteryDashboardState(
    val hasUsageAccessPermission: Boolean = false,
    val hasVerifiedPerAppBatteryData: Boolean = false,
    val totalBatteryPercentage: Int = 0,
    val isCharging: Boolean = false,
    val dischargeRateMa: Int? = null,
    val dischargeRatePercentPerHour: Float? = null,
    val dischargeRateSource: String = "N/A",
    val dischargeRateConfidence: MetricConfidence = MetricConfidence.UNAVAILABLE,
    val temperatureCelsius: Float? = null,
    val voltageVolts: Float? = null,
    val screenOnTimeMillis: Long? = null,
    val screenOnSource: String = "Requires Usage Access Permission",
    val screenOnConfidence: MetricConfidence = MetricConfidence.UNAVAILABLE,
    val screenOffTimeMillis: Long? = null,
    val screenOffSource: String = "Requires Usage Access Permission",
    val screenOffConfidence: MetricConfidence = MetricConfidence.UNAVAILABLE,
    val totalAppUsageTimeMillis: Long? = null,
    val totalAppUsageSource: String = "Requires Usage Access Permission",
    val totalAppUsageConfidence: MetricConfidence = MetricConfidence.UNAVAILABLE,
    val estimatedRemainingMinutes: Int? = null,
    val estimatedRemainingSource: String = "Derived from live discharge current / rate",
    val estimatedRemainingConfidence: MetricConfidence = MetricConfidence.UNAVAILABLE,
    val activeSaverMode: BatterySaverMode = BatterySaverMode.NORMAL,
    val selectedGraphWindow: ConsumptionGraphWindow = ConsumptionGraphWindow.HOURS_6,
    val selectedHistoryPeriod: HistoryPeriodFilter = HistoryPeriodFilter.DAILY,
    val selectedAppSection: AppUsageSectionTab = AppUsageSectionTab.HIGHEST_CONSUMING,
    val allInstalledApps: List<AppBatteryUsageRecord> = emptyList(),
    val verifiedHighestConsumingApps: List<AppBatteryUsageRecord> = emptyList(),
    val estimatedPowerContributionApps: List<AppBatteryUsageRecord> = emptyList(),
    val highestConsumingApps: List<AppBatteryUsageRecord> = emptyList(),
    val mostUsedApps: List<AppBatteryUsageRecord> = emptyList(),
    val backgroundActivityApps: List<AppBatteryUsageRecord> = emptyList(),
    val topConsumingApp: AppBatteryUsageRecord? = null,
    val activeSmartAlerts: List<SmartBatteryAlertItem> = emptyList(),
    val selfPowerAudit: SelfPowerAuditReport = SelfPowerAuditReport(),
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)
