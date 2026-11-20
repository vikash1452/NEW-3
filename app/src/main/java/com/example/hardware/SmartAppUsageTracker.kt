package com.example.hardware

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.example.model.AppBatteryUsageRecord
import com.example.model.AppUsageSectionTab
import com.example.model.BatteryHardwareSnapshot
import com.example.model.BatterySaverMode
import com.example.model.ConsumptionGraphWindow
import com.example.model.HistoryPeriodFilter
import com.example.model.MetricConfidence
import com.example.model.RestrictionExecutionStatus
import com.example.model.SmartBatteryAlertItem
import com.example.model.SmartBatteryAlertType
import com.example.model.SmartBatteryDashboardState
import com.example.model.UserSettings
import com.example.service.BatteryTelemetryHub
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Optional test overrides so Robolectric and JVM unit tests can verify
 * UsageStatsManager permission states, installed app lists, verified vs. unverified energy data,
 * and Auto Saver / Auto Restriction rules deterministically.
 */
data class UsageTrackerTestOverrides(
    val hasUsagePermissionOverride: Boolean? = null,
    val simulatedApps: List<AppBatteryUsageRecord>? = null,
    val screenOnMillisOverride: Long? = null,
    val screenOffMillisOverride: Long? = null,
    val verifiedPackageEnergyMah: Map<String, Float> = emptyMap()
)

class SmartAppUsageTracker(
    private val context: Context,
    private val overrides: UsageTrackerTestOverrides? = null
) {

    companion object {
        val CRITICAL_SYSTEM_PROTECTED_PACKAGES: Set<String> = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.systemui",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.android.emergency",
            "com.google.android.ims"
        )

        fun isBuiltInProtectedPackage(packageName: String, ownPackageName: String): Pair<Boolean, String?> {
            if (packageName == ownPackageName) {
                return true to "Protected: Battery Boss Core Service"
            }
            val lower = packageName.lowercase()
            return when {
                packageName in CRITICAL_SYSTEM_PROTECTED_PACKAGES && lower.contains("dialer") || lower.contains("phone") || lower.contains("telecom") || lower.contains("incallui") ->
                    true to "Protected: Phone / Dialer"
                lower == "com.android.systemui" ->
                    true to "Protected: Android System UI"
                lower.contains("deskclock") || lower.contains("clockpackage") || lower.contains("alarm") ->
                    true to "Protected: Alarm Clock"
                lower.contains("emergency") || lower.contains("ims") || lower.contains("cellbroadcast") ->
                    true to "Protected: Emergency Services"
                else -> false to null
            }
        }

        fun buildUsageAccessSettingsIntent(): Intent {
            return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun buildAppInfoSettingsIntent(packageName: String): Intent {
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun buildSystemBatterySettingsIntent(): Intent {
            return Intent(Intent.ACTION_POWER_USAGE_SUMMARY).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun buildBatterySaverSettingsIntent(): Intent {
            return Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun buildAppNotificationSettingsIntent(context: Context, packageName: String): Intent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                buildAppInfoSettingsIntent(packageName)
            }
        }

        private const val PM_CACHE_TTL_MS = 10 * 60_000L

        private data class CachedPackageMeta(
            val appName: String,
            val isSystemApp: Boolean
        )

        private val cacheLock = Any()
        private var lastLauncherDiscoveryMs: Long = 0L
        private var cachedLauncherPackages: Set<String> = emptySet()
        private val cachedAppMetaMap = mutableMapOf<String, Pair<Long, CachedPackageMeta>>()
    }

    /**
     * Checks whether the user has granted PACKAGE_USAGE_STATS (Usage Access) in Android System Settings.
     */
    fun hasUsageAccessPermission(): Boolean {
        overrides?.hasUsagePermissionOverride?.let { return it }
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        if (mode == AppOpsManager.MODE_ALLOWED) return true

        // Fallback check on devices where AppOps returns MODE_DEFAULT when granted via Settings
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now)
        }.getOrNull()
        return !stats.isNullOrEmpty()
    }

    /**
     * Collects the complete SmartBatteryDashboardState combining hardware battery metrics,
     * UsageStatsManager foreground/background telemetry, installed apps from PackageManager,
     * and active Smart Battery Alerts.
     */
    fun readSmartTrackerState(
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings,
        selectedGraphWindow: ConsumptionGraphWindow = ConsumptionGraphWindow.HOURS_6,
        selectedHistoryPeriod: HistoryPeriodFilter = HistoryPeriodFilter.DAILY,
        selectedAppSection: AppUsageSectionTab = AppUsageSectionTab.HIGHEST_CONSUMING
    ): SmartBatteryDashboardState {
        val hasUsageAccess = hasUsageAccessPermission()
        val windowMillis = 24L * 3_600_000L
        val now = System.currentTimeMillis()
        val startMillis = now - windowMillis

        val apps = collectInstalledAndUsedApps(
            hasUsageAccess = hasUsageAccess,
            startMillis = startMillis,
            endMillis = now,
            snapshot = snapshot,
            settings = settings
        )

        val totalForegroundMillis: Long? = if (hasUsageAccess) {
            apps.sumOf { it.foregroundTimeMillis }
        } else {
            null
        }

        val (screenOnMillis, screenOffMillis, screenSource, screenConfidence) = computeScreenOnOffTimes(
            hasUsageAccess = hasUsageAccess,
            startMillis = startMillis,
            endMillis = now,
            totalForegroundFallbackMillis = totalForegroundMillis
        )

        // Discharge Rate calculation from verified hardware current or session rate
        val rawCurrentMa = snapshot.currentMilliAmps
        val dischargeRateMa: Int? = when {
            snapshot.isCharging -> null
            rawCurrentMa != null && rawCurrentMa != 0 -> abs(rawCurrentMa)
            else -> null
        }

        val dischargePercentPerHour: Float? = when {
            !snapshot.isCharging && snapshot.percentPerHourRate != null && snapshot.percentPerHourRate > 0f ->
                snapshot.percentPerHourRate
            dischargeRateMa != null && (snapshot.fullChargeCapacityMah ?: snapshot.designCapacityMah) != null -> {
                val cap = (snapshot.fullChargeCapacityMah ?: snapshot.designCapacityMah ?: 0).toFloat()
                if (cap > 0f) ((dischargeRateMa.toFloat() / cap) * 100f).coerceIn(0.1f, 100f) else null
            }
            else -> null
        }

        val dischargeConfidence = when {
            dischargeRateMa != null -> MetricConfidence.HIGH_DIRECT
            dischargePercentPerHour != null -> MetricConfidence.MEDIUM_DERIVED
            else -> MetricConfidence.UNAVAILABLE
        }

        val dischargeSource = when {
            dischargeRateMa != null -> snapshot.currentSourceApi
            dischargePercentPerHour != null -> "Derived from Battery % Delta"
            snapshot.isCharging -> "Charging Active (Not Discharging)"
            else -> "N/A • Kernel current sensor unavailable"
        }

        // Estimated Battery Remaining Time
        val estimatedRemainingMinutes: Int? = computeEstimatedRemainingMinutes(
            snapshot = snapshot,
            dischargeRateMa = dischargeRateMa,
            dischargePercentPerHour = dischargePercentPerHour
        )
        val estimatedRemainingConfidence = if (estimatedRemainingMinutes != null) {
            MetricConfidence.MEDIUM_DERIVED
        } else {
            MetricConfidence.UNAVAILABLE
        }

        // Separate Verified Battery Consumption (real OS mAh) vs Estimated Power Contribution (foreground time share)
        val verifiedHighestConsumingApps = apps
            .filter { it.verifiedBatteryMah != null && it.verifiedBatteryMah > 0f }
            .sortedWith(
                compareByDescending<AppBatteryUsageRecord> { it.verifiedBatteryMah ?: -1f }
                    .thenByDescending { it.foregroundTimeMillis }
                    .thenBy { it.appName.lowercase() }
            )
            .mapIndexed { idx, rec -> rec.copy(rank = idx + 1) }

        val estimatedPowerContributionApps = apps
            .sortedWith(
                compareByDescending<AppBatteryUsageRecord> { it.estimatedBatteryMah ?: -1f }
                    .thenByDescending { it.estimatedBatteryPercent ?: -1f }
                    .thenByDescending { it.foregroundTimeMillis }
                    .thenBy { it.appName.lowercase() }
            )
            .mapIndexed { idx, rec -> rec.copy(rank = idx + 1) }

        val hasVerifiedPerAppData = verifiedHighestConsumingApps.isNotEmpty()
        val highestConsumingApps = if (hasVerifiedPerAppData) {
            verifiedHighestConsumingApps
        } else {
            estimatedPowerContributionApps
        }

        val mostUsedApps = apps
            .sortedWith(
                compareByDescending<AppBatteryUsageRecord> { it.foregroundTimeMillis }
                    .thenByDescending { it.lastTimeUsedMillis }
                    .thenBy { it.appName.lowercase() }
            )
            .mapIndexed { idx, rec -> rec.copy(rank = idx + 1) }

        val backgroundActivityApps = apps
            .filter {
                (it.backgroundServiceTimeMillis != null && it.backgroundServiceTimeMillis > 0L) ||
                    it.isIgnoringBatteryOptimizations == true ||
                    it.isHighBackgroundDetected
            }
            .sortedWith(
                compareByDescending<AppBatteryUsageRecord> { it.backgroundServiceTimeMillis ?: 0L }
                    .thenByDescending { it.isIgnoringBatteryOptimizations == true }
                    .thenByDescending { it.foregroundTimeMillis }
            )
            .mapIndexed { idx, rec -> rec.copy(rank = idx + 1) }

        val topApp = highestConsumingApps.firstOrNull {
            (it.verifiedBatteryMah ?: 0f) > 0f || it.foregroundTimeMillis > 0L
        } ?: highestConsumingApps.firstOrNull()

        val activeAlerts = evaluateSmartBatteryAlerts(
            snapshot = snapshot,
            settings = settings,
            dischargeRateMa = dischargeRateMa,
            dischargePercentPerHour = dischargePercentPerHour,
            screenOnTimeMillis = screenOnMillis,
            apps = highestConsumingApps
        )

        val selfPowerAudit = BatteryTelemetryHub.buildSelfPowerAuditReport(settings.batterySaverMode)

        return SmartBatteryDashboardState(
            hasUsageAccessPermission = hasUsageAccess,
            hasVerifiedPerAppBatteryData = hasVerifiedPerAppData,
            totalBatteryPercentage = snapshot.percentage,
            isCharging = snapshot.isCharging,
            dischargeRateMa = dischargeRateMa,
            dischargeRatePercentPerHour = dischargePercentPerHour,
            dischargeRateSource = dischargeSource,
            dischargeRateConfidence = dischargeConfidence,
            temperatureCelsius = snapshot.temperatureCelsius,
            voltageVolts = snapshot.voltageVolts,
            screenOnTimeMillis = screenOnMillis,
            screenOnSource = screenSource,
            screenOnConfidence = screenConfidence,
            screenOffTimeMillis = screenOffMillis,
            screenOffSource = screenSource,
            screenOffConfidence = screenConfidence,
            totalAppUsageTimeMillis = totalForegroundMillis,
            totalAppUsageSource = if (hasUsageAccess) "UsageStatsManager.queryUsageStats (24h)" else "Unavailable (Usage Access Required)",
            totalAppUsageConfidence = if (hasUsageAccess) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            estimatedRemainingMinutes = estimatedRemainingMinutes,
            estimatedRemainingSource = if (estimatedRemainingMinutes != null) {
                "Estimated from Charge Counter / Live Discharge Current"
            } else {
                "Unavailable (Requires Discharging Current Sensor)"
            },
            estimatedRemainingConfidence = estimatedRemainingConfidence,
            activeSaverMode = settings.batterySaverMode,
            selectedGraphWindow = selectedGraphWindow,
            selectedHistoryPeriod = selectedHistoryPeriod,
            selectedAppSection = selectedAppSection,
            allInstalledApps = apps,
            highestConsumingApps = highestConsumingApps,
            verifiedHighestConsumingApps = verifiedHighestConsumingApps,
            estimatedPowerContributionApps = estimatedPowerContributionApps,
            mostUsedApps = mostUsedApps,
            backgroundActivityApps = backgroundActivityApps,
            topConsumingApp = topApp,
            activeSmartAlerts = activeAlerts,
            selfPowerAudit = selfPowerAudit,
            lastUpdatedMillis = now
        )
    }

    private fun collectInstalledAndUsedApps(
        hasUsageAccess: Boolean,
        startMillis: Long,
        endMillis: Long,
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings
    ): List<AppBatteryUsageRecord> {
        overrides?.simulatedApps?.let { simulated ->
            return applyUserProtectionSettings(simulated, settings)
        }

        val pm = context.packageManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

        val usageMap = mutableMapOf<String, Triple<Long, Long?, Long>>() // pkg -> (fgMillis, bgServiceMillis, lastUsed)
        if (hasUsageAccess && usm != null) {
            BatteryTelemetryHub.recordUsageStatsQuery()
            val rawStats = runCatching {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMillis, endMillis)
            }.getOrNull().orEmpty()

            for (stat in rawStats) {
                val pkg = stat.packageName ?: continue
                val fg = stat.totalTimeInForeground.coerceAtLeast(0L)
                val bgService: Long? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { stat.totalTimeForegroundServiceUsed.coerceAtLeast(0L) }.getOrNull()
                } else {
                    null
                }
                val lastUsed = stat.lastTimeUsed.coerceAtLeast(0L)
                val existing = usageMap[pkg]
                if (existing == null) {
                    usageMap[pkg] = Triple(fg, bgService, lastUsed)
                } else {
                    val sumFg = existing.first + fg
                    val sumBg = if (existing.second != null || bgService != null) {
                        (existing.second ?: 0L) + (bgService ?: 0L)
                    } else {
                        null
                    }
                    val maxLast = maxOf(existing.third, lastUsed)
                    usageMap[pkg] = Triple(sumFg, sumBg, maxLast)
                }
            }
        }

        // Discover installed launcher packages with 10-minute TTL cache to avoid repeated binder IPC
        val launcherPackages: Set<String> = synchronized(cacheLock) {
            if (cachedLauncherPackages.isNotEmpty() && (endMillis - lastLauncherDiscoveryMs) < PM_CACHE_TTL_MS) {
                BatteryTelemetryHub.recordPackageManagerCacheHit()
                cachedLauncherPackages
            } else {
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = runCatching {
                    pm.queryIntentActivities(launcherIntent, 0)
                }.getOrNull().orEmpty()
                val discovered = linkedSetOf<String>()
                resolveInfos.forEach { info ->
                    info.activityInfo?.packageName?.let { discovered.add(it) }
                }
                cachedLauncherPackages = discovered
                lastLauncherDiscoveryMs = endMillis
                discovered
            }
        }

        val allPackageNames = linkedSetOf<String>()
        allPackageNames.addAll(launcherPackages)
        allPackageNames.addAll(usageMap.keys)
        allPackageNames.add(context.packageName)

        val totalFgAcrossApps = usageMap.values.sumOf { it.first }.coerceAtLeast(0L)
        val referenceCapacityMah = (snapshot.fullChargeCapacityMah ?: snapshot.designCapacityMah)?.toFloat()

        val records = mutableListOf<AppBatteryUsageRecord>()
        for (pkg in allPackageNames) {
            val cachedMeta: CachedPackageMeta = synchronized(cacheLock) {
                val entry = cachedAppMetaMap[pkg]
                if (entry != null && (endMillis - entry.first) < PM_CACHE_TTL_MS) {
                    BatteryTelemetryHub.recordPackageManagerCacheHit()
                    entry.second
                } else {
                    val appInfo: ApplicationInfo? = runCatching {
                        pm.getApplicationInfo(pkg, 0)
                    }.getOrNull()

                    val label = appInfo?.let {
                        runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
                    }?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')

                    val isSystem = appInfo != null &&
                        ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) &&
                        ((appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)

                    val freshMeta = CachedPackageMeta(appName = label, isSystemApp = isSystem)
                    cachedAppMetaMap[pkg] = endMillis to freshMeta
                    freshMeta
                }
            }

            val label = cachedMeta.appName
            val isSystem = cachedMeta.isSystemApp

            val (builtInProtected, builtInReason) = isBuiltInProtectedPackage(pkg, context.packageName)
            val isUserProtected = pkg in settings.protectedAppPackages
            val isProtected = builtInProtected || isUserProtected
            val protectedReason = when {
                builtInReason != null -> builtInReason
                isUserProtected -> "Protected by User"
                else -> null
            }

            val isExcluded = pkg in settings.excludedAppPackages
            val isPermittedAutoRestrict = !isProtected && !isExcluded && (pkg in settings.userPermittedRestrictPackages)

            val usageTriple = usageMap[pkg]
            val fgMillis = usageTriple?.first ?: 0L
            val bgMillis = usageTriple?.second
            val lastUsedMillis = usageTriple?.third ?: 0L

            val isIgnoringOpt = runCatching {
                powerManager?.isIgnoringBatteryOptimizations(pkg)
            }.getOrNull()

            val optLabel = when (isIgnoringOpt) {
                true -> "Unrestricted (Ignoring Battery Optimizations)"
                false -> "Optimized by Android OS"
                null -> "Optimization Status N/A"
            }

            // Verified Battery Energy: Only populated if an explicit verified hardware/OS energy source exists
            val verifiedMah = overrides?.verifiedPackageEnergyMah?.get(pkg)
            val verifiedPct = if (verifiedMah != null && referenceCapacityMah != null && referenceCapacityMah > 0f) {
                ((verifiedMah / referenceCapacityMah) * 100f).coerceIn(0f, 100f)
            } else {
                null
            }

            // Estimated Power Contribution: Clearly separated from Verified Consumption; never faked as direct mAh!
            val estimatedPct: Float? = if (hasUsageAccess && totalFgAcrossApps > 0L && fgMillis > 0L) {
                ((fgMillis.toFloat() / totalFgAcrossApps.toFloat()) * (100f - snapshot.percentage.coerceIn(0, 99)).coerceAtLeast(5f))
                    .coerceIn(0.1f, 100f)
            } else {
                null
            }
            val estimatedMah: Float? = if (estimatedPct != null && referenceCapacityMah != null && referenceCapacityMah > 0f) {
                (referenceCapacityMah * (estimatedPct / 100f))
            } else {
                null
            }

            val isHighDrain = fgMillis >= 45 * 60_000L || (verifiedPct != null && verifiedPct >= 15f) || (estimatedPct != null && estimatedPct >= 25f)
            val isHighBg = (bgMillis != null && bgMillis >= 20 * 60_000L) || (isIgnoringOpt == true && !isProtected && fgMillis > 5 * 60_000L)

            val execStatus = when {
                isProtected -> RestrictionExecutionStatus.SKIPPED_PROTECTED_CRITICAL
                isExcluded -> RestrictionExecutionStatus.SKIPPED_USER_EXCLUDED
                (isHighDrain || isHighBg) && isPermittedAutoRestrict -> RestrictionExecutionStatus.FLAGGED_PENDING_USER_ACTION
                isIgnoringOpt == false -> RestrictionExecutionStatus.VERIFIED_OS_RESTRICTED
                else -> RestrictionExecutionStatus.FLAGGED_PENDING_USER_ACTION
            }

            records.add(
                AppBatteryUsageRecord(
                    packageName = pkg,
                    appName = label,
                    isSystemApp = isSystem,
                    isProtectedCriticalApp = isProtected,
                    protectedReason = protectedReason,
                    isUserExcluded = isExcluded,
                    isUserPermittedForAutoRestrict = isPermittedAutoRestrict,
                    foregroundTimeMillis = fgMillis,
                    backgroundServiceTimeMillis = bgMillis,
                    usageSourceApi = if (hasUsageAccess) {
                        "UsageStatsManager.queryUsageStats"
                    } else {
                        "PackageManager (Grant Usage Access for Foreground Time)"
                    },
                    usageConfidence = if (hasUsageAccess) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
                    verifiedBatteryMah = verifiedMah,
                    verifiedBatteryPercent = verifiedPct,
                    estimatedBatteryMah = estimatedMah,
                    estimatedBatteryPercent = estimatedPct,
                    energySourceApi = if (verifiedMah != null) {
                        "Verified OS Power Attribution"
                    } else {
                        "Unavailable to 3rd-Party Apps (BATTERY_STATS is signature|privileged)"
                    },
                    energyConfidence = if (verifiedMah != null) {
                        MetricConfidence.HIGH_DIRECT
                    } else if (estimatedPct != null) {
                        MetricConfidence.MEDIUM_DERIVED
                    } else {
                        MetricConfidence.UNAVAILABLE
                    },
                    lastTimeUsedMillis = lastUsedMillis,
                    isIgnoringBatteryOptimizations = isIgnoringOpt,
                    optimizationStatusLabel = optLabel,
                    restrictionExecutionStatus = execStatus,
                    isHighDrainDetected = isHighDrain,
                    isHighBackgroundDetected = isHighBg
                )
            )
        }

        return records
    }

    private fun applyUserProtectionSettings(
        apps: List<AppBatteryUsageRecord>,
        settings: UserSettings
    ): List<AppBatteryUsageRecord> {
        return apps.map { rec ->
            val (builtInProtected, builtInReason) = isBuiltInProtectedPackage(rec.packageName, context.packageName)
            val isUserProtected = rec.packageName in settings.protectedAppPackages
            val isProtected = rec.isProtectedCriticalApp || builtInProtected || isUserProtected
            val isExcluded = rec.packageName in settings.excludedAppPackages
            val isPermitted = !isProtected && !isExcluded && (rec.packageName in settings.userPermittedRestrictPackages)
            rec.copy(
                isProtectedCriticalApp = isProtected,
                protectedReason = rec.protectedReason ?: builtInReason ?: if (isUserProtected) "Protected by User" else null,
                isUserExcluded = isExcluded,
                isUserPermittedForAutoRestrict = isPermitted
            )
        }
    }

    private data class ScreenTimeResult(
        val screenOnMillis: Long?,
        val screenOffMillis: Long?,
        val source: String,
        val confidence: MetricConfidence
    )

    private fun computeScreenOnOffTimes(
        hasUsageAccess: Boolean,
        startMillis: Long,
        endMillis: Long,
        totalForegroundFallbackMillis: Long?
    ): ScreenTimeResult {
        if (overrides?.screenOnMillisOverride != null || overrides?.screenOffMillisOverride != null) {
            return ScreenTimeResult(
                screenOnMillis = overrides.screenOnMillisOverride,
                screenOffMillis = overrides.screenOffMillisOverride,
                source = "UsageEvents (SCREEN_INTERACTIVE)",
                confidence = MetricConfidence.HIGH_DIRECT
            )
        }
        if (!hasUsageAccess) {
            return ScreenTimeResult(
                screenOnMillis = null,
                screenOffMillis = null,
                source = "Unavailable (Requires Usage Access Permission)",
                confidence = MetricConfidence.UNAVAILABLE
            )
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            val events = runCatching { usm.queryEvents(startMillis, endMillis) }.getOrNull()
            if (events != null) {
                var interactiveStart: Long? = null
                var accumulatedInteractive = 0L
                var sawScreenEvent = false
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    when (event.eventType) {
                        UsageEvents.Event.SCREEN_INTERACTIVE -> {
                            sawScreenEvent = true
                            if (interactiveStart == null) {
                                interactiveStart = event.timeStamp
                            }
                        }
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                            sawScreenEvent = true
                            val start = interactiveStart
                            if (start != null && event.timeStamp > start) {
                                accumulatedInteractive += (event.timeStamp - start)
                            }
                            interactiveStart = null
                        }
                    }
                }
                if (interactiveStart != null && endMillis > interactiveStart) {
                    accumulatedInteractive += (endMillis - interactiveStart)
                }
                if (sawScreenEvent && accumulatedInteractive > 0L) {
                    val window = (endMillis - startMillis).coerceAtLeast(accumulatedInteractive)
                    val screenOff = (window - accumulatedInteractive).coerceAtLeast(0L)
                    return ScreenTimeResult(
                        screenOnMillis = accumulatedInteractive,
                        screenOffMillis = screenOff,
                        source = "UsageStatsManager.queryEvents (SCREEN_INTERACTIVE)",
                        confidence = MetricConfidence.HIGH_DIRECT
                    )
                }
            }
        }

        // Fallback to aggregated foreground usage time if SCREEN_INTERACTIVE events were not in the slice
        if (totalForegroundFallbackMillis != null) {
            val window = (endMillis - startMillis).coerceAtLeast(totalForegroundFallbackMillis)
            return ScreenTimeResult(
                screenOnMillis = totalForegroundFallbackMillis,
                screenOffMillis = (window - totalForegroundFallbackMillis).coerceAtLeast(0L),
                source = "Derived from Total Foreground App Usage (24h)",
                confidence = MetricConfidence.MEDIUM_DERIVED
            )
        }

        return ScreenTimeResult(
            screenOnMillis = null,
            screenOffMillis = null,
            source = "Unavailable",
            confidence = MetricConfidence.UNAVAILABLE
        )
    }

    private fun computeEstimatedRemainingMinutes(
        snapshot: BatteryHardwareSnapshot,
        dischargeRateMa: Int?,
        dischargePercentPerHour: Float?
    ): Int? {
        if (snapshot.isCharging || !snapshot.isPercentageValid || snapshot.percentage <= 0) {
            return null
        }
        // 1. Direct coulomb counter / live discharge current
        val remainingMah = snapshot.chargeCounterMah
        if (remainingMah != null && remainingMah > 0 && dischargeRateMa != null && dischargeRateMa >= 25) {
            val hours = remainingMah.toFloat() / dischargeRateMa.toFloat()
            val mins = (hours * 60f).roundToInt()
            if (mins in 1..(72 * 60)) {
                return mins
            }
        }
        // 2. Discharge %/hr rate
        if (dischargePercentPerHour != null && dischargePercentPerHour >= 0.5f) {
            val hours = snapshot.percentage.toFloat() / dischargePercentPerHour
            val mins = (hours * 60f).roundToInt()
            if (mins in 1..(72 * 60)) {
                return mins
            }
        }
        return null
    }

    /**
     * Evaluates the 6 configurable Smart Battery Alerts based on real telemetry and UsageStats.
     */
    fun evaluateSmartBatteryAlerts(
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings,
        dischargeRateMa: Int?,
        dischargePercentPerHour: Float?,
        screenOnTimeMillis: Long?,
        apps: List<AppBatteryUsageRecord>
    ): List<SmartBatteryAlertItem> {
        val alerts = mutableListOf<SmartBatteryAlertItem>()

        // 1. High App Battery / Usage Alert
        if (settings.alertHighAppBatteryUsage) {
            val heavyApp = apps.firstOrNull { !it.isProtectedCriticalApp && !it.isUserExcluded && it.isHighDrainDetected }
            if (heavyApp != null) {
                alerts.add(
                    SmartBatteryAlertItem(
                        type = SmartBatteryAlertType.ABNORMAL_APP_DRAIN,
                        title = "High Usage Detected: ${heavyApp.appName}",
                        message = "Active foreground: ${heavyApp.formattedForegroundTime} • ${heavyApp.estimatedConsumptionLabel}",
                        severityLabel = "HIGH USAGE",
                        targetPackageName = heavyApp.packageName,
                        targetAppName = heavyApp.appName
                    )
                )
            }
        }

        // 2. Heavy Background Activity Alert
        if (settings.alertHighBackgroundActivity) {
            val bgApp = apps.firstOrNull { !it.isProtectedCriticalApp && !it.isUserExcluded && it.isHighBackgroundDetected }
            if (bgApp != null) {
                alerts.add(
                    SmartBatteryAlertItem(
                        type = SmartBatteryAlertType.HIGH_BACKGROUND_ACTIVITY,
                        title = "Background Activity: ${bgApp.appName}",
                        message = "Bg Service: ${bgApp.formattedBackgroundTime} • ${bgApp.optimizationStatusLabel}",
                        severityLabel = "BACKGROUND",
                        targetPackageName = bgApp.packageName,
                        targetAppName = bgApp.appName
                    )
                )
            }
        }

        // 3. Rapid Battery Drop Alert
        if (settings.alertRapidBatteryDrain && !snapshot.isCharging) {
            val isFastCurrent = dischargeRateMa != null && dischargeRateMa >= 900
            val isFastRate = dischargePercentPerHour != null && dischargePercentPerHour >= 18f
            if (isFastCurrent || isFastRate) {
                val detail = dischargeRateMa?.let { "${it} mA" }
                    ?: dischargePercentPerHour?.let { String.format(java.util.Locale.US, "%.1f %%/hr", it) }
                    ?: "Elevated"
                alerts.add(
                    SmartBatteryAlertItem(
                        type = SmartBatteryAlertType.RAPID_BATTERY_DROP,
                        title = "Rapid Battery Discharge ($detail)",
                        message = "Device is discharging faster than normal. Consider enabling Smart Saver or checking active apps.",
                        severityLabel = "RAPID DRAIN"
                    )
                )
            }
        }

        // 4. High Battery Temperature Alert
        if (settings.alertHighBatteryTemp) {
            val temp = snapshot.temperatureCelsius
            if (temp != null && temp >= settings.temperatureWarningCelsius) {
                alerts.add(
                    SmartBatteryAlertItem(
                        type = SmartBatteryAlertType.HIGH_TEMPERATURE,
                        title = String.format(java.util.Locale.US, "High Battery Temperature (%.1f°C)", temp),
                        message = "Thermal threshold (${settings.temperatureWarningCelsius}°C) reached. Avoid heavy foreground tasks while charging.",
                        severityLabel = "THERMAL"
                    )
                )
            }
        }

        // 5. Smart Battery Saver Recommendation
        if (settings.alertSaverRecommendation &&
            !snapshot.isCharging &&
            snapshot.isPercentageValid &&
            snapshot.percentage <= 30 &&
            settings.batterySaverMode == BatterySaverMode.NORMAL
        ) {
            alerts.add(
                SmartBatteryAlertItem(
                    type = SmartBatteryAlertType.SAVER_RECOMMENDATION,
                    title = "Enable Smart Battery Saver (${snapshot.percentage}%)",
                    message = "Battery is at ${snapshot.percentage}% on battery power. Switch to Smart Saver or Extreme Saver to reduce background load.",
                    severityLabel = "SAVER TIP"
                )
            )
        }

        // 6. Screen-On Time & Battery Consumption Imbalance
        if (settings.alertAbnormalScreenDrain &&
            !snapshot.isCharging &&
            screenOnTimeMillis != null &&
            screenOnTimeMillis < 30 * 60_000L &&
            (dischargeRateMa ?: 0) >= 650
        ) {
            alerts.add(
                SmartBatteryAlertItem(
                    type = SmartBatteryAlertType.SCREEN_CONSUMPTION_ANOMALY,
                    title = "High Standby / Low Screen-On Drain",
                    message = "High current draw (${dischargeRateMa} mA) despite low Screen-On Time (${AppBatteryUsageRecord.formatDurationMillis(screenOnTimeMillis)}).",
                    severityLabel = "ANOMALY"
                )
            )
        }

        return alerts
    }
}
