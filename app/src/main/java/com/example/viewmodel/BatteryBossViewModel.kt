package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AnnouncementLogEntity
import com.example.data.local.AppUsageRecordEntity
import com.example.data.local.AutoManagementReportEntity
import com.example.data.local.BatteryBossDatabase
import com.example.data.local.BatteryBossRepository
import com.example.data.local.BatteryUsageHistoryEntity
import com.example.data.local.ChargingSessionEntity
import com.example.data.preferences.SettingsDataStore
import com.example.hardware.BatteryHardwareReader
import com.example.hardware.ChargerRadioController
import com.example.hardware.SmartAppUsageTracker
import com.example.model.AnnouncementRepeatPolicy
import com.example.model.AppBatteryUsageRecord
import com.example.model.AppUsageSectionTab
import com.example.model.AudioDiagnosticsState
import com.example.model.AudioStreamUsageOption
import com.example.model.BackgroundMonitoringMode
import com.example.model.BatteryHardwareSnapshot
import com.example.model.BatterySaverMode
import com.example.model.ConsumptionGraphWindow
import com.example.model.HistoryPeriodFilter
import com.example.model.MediaDuckingLevel
import com.example.model.NotificationChannelDiagnostics
import com.example.model.OemOptimizationGuide
import com.example.model.OfflineVoiceModelPack
import com.example.model.RestrictionExecutionStatus
import com.example.model.SmartBatteryDashboardState
import com.example.model.TelemetrySamplePoint
import com.example.model.UserSettings
import com.example.model.VoiceGender
import com.example.model.VoiceLanguage
import com.example.model.VoiceProfilePreset
import com.example.service.BatteryMonitorService
import com.example.service.BatteryTelemetryHub
import com.example.service.SilentBackgroundMonitorEngine
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatteryBossViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val hardwareReader = BatteryHardwareReader(appContext)
    private val appUsageTracker = SmartAppUsageTracker(appContext)
    private val settingsDataStore = SettingsDataStore(appContext)
    private val database = BatteryBossDatabase.getInstance(appContext)
    private val repository = BatteryBossRepository(database.batteryBossDao())
    private val voiceAssistant = BatteryTelemetryHub.getOrCreateVoiceAssistant(appContext)

    val snapshot: StateFlow<BatteryHardwareSnapshot> = BatteryTelemetryHub.snapshot
    val telemetryHistory: StateFlow<List<TelemetrySamplePoint>> = BatteryTelemetryHub.telemetryHistory
    val isServiceRunning: StateFlow<Boolean> = BatteryTelemetryHub.isServiceRunning
    val isForegroundNotificationVisible: StateFlow<Boolean> = BatteryTelemetryHub.isForegroundNotificationVisible
    val isSilentBackgroundActive: StateFlow<Boolean> = BatteryTelemetryHub.isSilentBackgroundActive
    val isJobSchedulerRegistered: StateFlow<Boolean> = BatteryTelemetryHub.isJobSchedulerRegistered
    val activeMonitoringMode: StateFlow<BackgroundMonitoringMode> = BatteryTelemetryHub.activeMonitoringMode
    val lastNotificationActionHandled: StateFlow<String> = BatteryTelemetryHub.lastNotificationActionHandled
    val isSpeaking: StateFlow<Boolean> = voiceAssistant.isSpeaking
    val previewingPreset: StateFlow<VoiceProfilePreset?> = voiceAssistant.previewingPreset
    val engineStatusText: StateFlow<String> = voiceAssistant.engineStatusText
    val voiceModelPacks: StateFlow<List<OfflineVoiceModelPack>> = voiceAssistant.voiceModelPacks
    val lastSpokenBanner: StateFlow<String?> = voiceAssistant.lastSpokenBanner
    val audioDiagnostics: StateFlow<AudioDiagnosticsState> = voiceAssistant.audioDiagnostics
    val notificationChannelDiagnostics: StateFlow<NotificationChannelDiagnostics> =
        BatteryTelemetryHub.notificationChannelDiagnostics
    val isAccessibilityRadioHelperEnabled: StateFlow<Boolean> =
        BatteryTelemetryHub.isAccessibilityRadioHelperEnabled
    val lastRadioAutomationStatus: StateFlow<String> =
        BatteryTelemetryHub.lastRadioAutomationStatus

    val userSettings: StateFlow<UserSettings> = settingsDataStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserSettings()
    )

    val chargingSessions: StateFlow<List<ChargingSessionEntity>> = repository.chargingSessions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val announcementLogs: StateFlow<List<AnnouncementLogEntity>> = repository.announcementLogs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val batteryUsageHistory: StateFlow<List<BatteryUsageHistoryEntity>> = repository.batteryUsageHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val appUsageRecords: StateFlow<List<AppUsageRecordEntity>> = repository.appUsageRecords.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val autoManagementReports: StateFlow<List<AutoManagementReportEntity>> = repository.autoManagementReports.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _smartTrackerState = MutableStateFlow(SmartBatteryDashboardState())
    val smartTrackerState: StateFlow<SmartBatteryDashboardState> = _smartTrackerState.asStateFlow()

    private val _selectedAppForDetails = MutableStateFlow<AppBatteryUsageRecord?>(null)
    val selectedAppForDetails: StateFlow<AppBatteryUsageRecord?> = _selectedAppForDetails.asStateFlow()

    private val _selectedAppHistoryRecords = MutableStateFlow<List<AppUsageRecordEntity>>(emptyList())
    val selectedAppHistoryRecords: StateFlow<List<AppUsageRecordEntity>> = _selectedAppHistoryRecords.asStateFlow()

    val oemGuide: OemOptimizationGuide = hardwareReader.getDetectedOemGuide()

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(checkIgnoringBatteryOptimizations())
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    private var lastAppUsageRefreshMillis: Long = 0L
    private val reportedAutoRestrictPackagesInSession = mutableSetOf<String>()

    init {
        refreshHardwareTelemetry()
        refreshRadioHelperStatus()
        refreshSmartBatteryTracker(forcePersist = true)

        viewModelScope.launch {
            val initialSettings = settingsDataStore.getSettingsSnapshot()
            if (initialSettings.backgroundMonitoringEnabled && !BatteryTelemetryHub.isServiceRunning.value) {
                SilentBackgroundMonitorEngine.applyMonitoringMode(
                    context = appContext,
                    enabled = true,
                    mode = initialSettings.backgroundMonitoringMode
                )
            }
        }

        // Reactive update when user settings (e.g. Saver Mode, Protected Apps, Alert toggles) change
        viewModelScope.launch {
            userSettings.collect { settings ->
                BatteryTelemetryHub.setGraphSampleInterval(settings.batterySaverMode.graphSampleIntervalMs)
                refreshSmartBatteryTracker(forcePersist = false)
            }
        }

        // Event-driven reaction to BatteryTelemetryHub snapshots (no 4-second background polling loop)
        viewModelScope.launch {
            BatteryTelemetryHub.snapshot.collect {
                voiceAssistant.refreshDiagnosticsSnapshot()
                val activeMode = userSettings.value.batterySaverMode
                val minScanInterval = activeMode.appUsageScanMinIntervalMs
                val now = System.currentTimeMillis()
                // In Extreme Saver (minScanInterval == 0L) or when Screen is OFF, automatic background app scans are paused
                if (minScanInterval > 0L &&
                    BatteryTelemetryHub.isScreenInteractive.value &&
                    (now - lastAppUsageRefreshMillis >= minScanInterval)
                ) {
                    refreshSmartBatteryTracker(forcePersist = true)
                } else {
                    // Refresh lightweight self-power audit metrics in state without scanning PackageManager/UsageStats
                    _smartTrackerState.value = _smartTrackerState.value.copy(
                        selfPowerAudit = BatteryTelemetryHub.buildSelfPowerAuditReport(activeMode)
                    )
                }
            }
        }

        // Screen-On UI supplement ONLY when background service is OFF and mode is not Pure Event-Driven
        viewModelScope.launch {
            while (isActive) {
                val activeMode = userSettings.value.batterySaverMode
                val waitMs = if (activeMode.isPureEventDriven || !BatteryTelemetryHub.isScreenInteractive.value) {
                    30_000L
                } else {
                    activeMode.telemetryIntervalMs.coerceAtLeast(15_000L)
                }
                delay(waitMs)
                if (!BatteryTelemetryHub.isServiceRunning.value &&
                    BatteryTelemetryHub.isScreenInteractive.value &&
                    !activeMode.isPureEventDriven
                ) {
                    BatteryTelemetryHub.recordScreenOnGaugeRead()
                    refreshHardwareTelemetry()
                }
            }
        }
    }

    fun refreshHardwareTelemetry() {
        val latest = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(latest)
        _isIgnoringBatteryOptimizations.value = checkIgnoringBatteryOptimizations()
        BatteryTelemetryHub.refreshNotificationChannelDiagnostics(
            appContext,
            userSettings.value.silentNotificationEnabled
        )
        voiceAssistant.refreshDiagnosticsSnapshot()
    }

    fun refreshNotificationChannelDiagnostics() {
        BatteryTelemetryHub.refreshNotificationChannelDiagnostics(
            appContext,
            userSettings.value.silentNotificationEnabled
        )
    }

    fun refreshRadioHelperStatus() {
        BatteryTelemetryHub.setAccessibilityRadioHelperEnabled(
            ChargerRadioController.isAccessibilityAutoToggleEnabled(appContext)
        )
    }

    fun refreshSmartBatteryTracker(forcePersist: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val snap = BatteryTelemetryHub.snapshot.value.let {
                if (it.percentage == 0 && it.statusLabel == "Unknown") hardwareReader.readSnapshot() else it
            }
            val settings = settingsDataStore.getSettingsSnapshot()
            val currentState = _smartTrackerState.value

            val updatedState = appUsageTracker.readSmartTrackerState(
                snapshot = snap,
                settings = settings,
                selectedGraphWindow = currentState.selectedGraphWindow,
                selectedHistoryPeriod = currentState.selectedHistoryPeriod,
                selectedAppSection = currentState.selectedAppSection
            )
            lastAppUsageRefreshMillis = System.currentTimeMillis()
            _smartTrackerState.value = updatedState

            // Keep selected app details in sync if open
            val currentSelectedPkg = _selectedAppForDetails.value?.packageName
            if (currentSelectedPkg != null) {
                val refreshedSelected = updatedState.allInstalledApps.firstOrNull { it.packageName == currentSelectedPkg }
                if (refreshedSelected != null) {
                    _selectedAppForDetails.value = refreshedSelected
                }
            }

            if (forcePersist) {
                val dischargeMa = if (!snap.isCharging && snap.currentMilliAmps != null) {
                    abs(snap.currentMilliAmps)
                } else {
                    null
                }
                repository.recordBatteryUsageSnapshot(
                    entry = BatteryUsageHistoryEntity(
                        timestampMillis = snap.timestampMillis,
                        batteryPercentage = snap.percentage,
                        isCharging = snap.isCharging,
                        dischargeRateMa = dischargeMa,
                        dischargeRatePercentPerHour = updatedState.dischargeRatePercentPerHour,
                        screenOnTimeMillis = updatedState.screenOnTimeMillis,
                        totalAppUsageTimeMillis = updatedState.totalAppUsageTimeMillis,
                        verifiedConsumptionMah = snap.chargeCounterMah?.toFloat(),
                        temperatureCelsius = snap.temperatureCelsius,
                        voltageVolts = snap.voltageVolts,
                        batterySaverMode = settings.batterySaverMode.name,
                        topAppPackage = updatedState.topConsumingApp?.packageName,
                        topAppName = updatedState.topConsumingApp?.appName,
                        topAppForegroundMillis = updatedState.topConsumingApp?.foregroundTimeMillis
                    ),
                    minIntervalMillis = settings.batterySaverMode.dbSnapshotMinIntervalMs
                )

                val topAppEntities = updatedState.highestConsumingApps
                    .filter { it.foregroundTimeMillis > 0L || (it.verifiedBatteryMah ?: 0f) > 0f }
                    .take(15)
                    .map { rec ->
                        AppUsageRecordEntity(
                            timestampMillis = System.currentTimeMillis(),
                            packageName = rec.packageName,
                            appName = rec.appName,
                            foregroundTimeMillis = rec.foregroundTimeMillis,
                            backgroundTimeMillis = rec.backgroundServiceTimeMillis,
                            verifiedBatteryMah = rec.verifiedBatteryMah,
                            estimatedBatteryMah = rec.estimatedBatteryMah,
                            lastUsedMillis = rec.lastTimeUsedMillis,
                            isOptimizedByOs = rec.isIgnoringBatteryOptimizations != true
                        )
                    }
                repository.recordAppUsageSnapshots(topAppEntities)

                evaluateAutoRestrictionDetection(snap, settings, updatedState.allInstalledApps)
            }
        }
    }

    private suspend fun evaluateAutoRestrictionDetection(
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings,
        apps: List<AppBatteryUsageRecord>
    ) {
        if (!settings.autoRestrictHighUsageEnabled || !snapshot.isPercentageValid) return
        if (snapshot.percentage > settings.restrictionBatteryThresholdPercent) return

        for (app in apps) {
            if (!app.isHighDrainDetected && !app.isHighBackgroundDetected) continue
            if (app.packageName in reportedAutoRestrictPackagesInSession) continue

            if (app.isProtectedCriticalApp) {
                reportedAutoRestrictPackagesInSession.add(app.packageName)
                repository.logAutoManagementReport(
                    eventCategory = "PROTECTED_APP_SKIPPED",
                    targetPackageName = app.packageName,
                    targetAppName = app.appName,
                    detectedBatteryUsageSummary = "Fg: ${app.formattedForegroundTime} • ${app.protectedReason ?: "Protected"}",
                    alertIssued = "Protected Critical App — Never Auto-Restricted",
                    restrictionOrActionApplied = "No restriction applied (${app.protectedReason ?: "Protected"})",
                    actionSucceeded = true,
                    executionStatus = RestrictionExecutionStatus.SKIPPED_PROTECTED_CRITICAL.name,
                    wasActuallyRestrictedByApp = false,
                    openedSystemSettingsOnly = false,
                    saverModeState = settings.batterySaverMode.name
                )
            } else if (!app.isUserExcluded && app.isUserPermittedForAutoRestrict) {
                reportedAutoRestrictPackagesInSession.add(app.packageName)
                val alreadyOsOptimized = app.isIgnoringBatteryOptimizations == false
                repository.logAutoManagementReport(
                    eventCategory = "HIGH_USAGE_FLAGGED_FOR_USER",
                    targetPackageName = app.packageName,
                    targetAppName = app.appName,
                    detectedBatteryUsageSummary = "Fg: ${app.formattedForegroundTime} • Bg: ${app.formattedBackgroundTime} • ${app.estimatedConsumptionLabel}",
                    alertIssued = "High Usage Detected at ${snapshot.percentage}% (<= ${settings.restrictionBatteryThresholdPercent}%)",
                    restrictionOrActionApplied = if (alreadyOsOptimized) {
                        "Verified OS Optimized; Flagged for Manual Background/Force-Stop Review in System Settings (Not restricted by Battery Boss)"
                    } else {
                        "Flagged for Manual Restriction in System Settings (NOT Restricted or Force-Stopped — Android prohibits 3rd-party silent restriction)"
                    },
                    actionSucceeded = false,
                    executionStatus = RestrictionExecutionStatus.FLAGGED_PENDING_USER_ACTION.name,
                    wasActuallyRestrictedByApp = false,
                    openedSystemSettingsOnly = false,
                    saverModeState = settings.batterySaverMode.name
                )
            }
        }
    }

    fun selectGraphWindow(window: ConsumptionGraphWindow) {
        _smartTrackerState.value = _smartTrackerState.value.copy(selectedGraphWindow = window)
    }

    fun selectHistoryPeriod(period: HistoryPeriodFilter) {
        _smartTrackerState.value = _smartTrackerState.value.copy(selectedHistoryPeriod = period)
    }

    fun selectAppSection(section: AppUsageSectionTab) {
        _smartTrackerState.value = _smartTrackerState.value.copy(selectedAppSection = section)
    }

    fun openAppDetails(app: AppBatteryUsageRecord?) {
        _selectedAppForDetails.value = app
        if (app != null) {
            viewModelScope.launch(Dispatchers.IO) {
                _selectedAppHistoryRecords.value = repository.getAppUsageHistoryForPackage(app.packageName)
            }
        } else {
            _selectedAppHistoryRecords.value = emptyList()
        }
    }

    fun setBatterySaverMode(mode: BatterySaverMode) {
        viewModelScope.launch {
            val previous = userSettings.value.batterySaverMode
            settingsDataStore.updateBatterySaverMode(mode)
            if (previous != mode) {
                repository.logAutoManagementReport(
                    eventCategory = "MANUAL_SAVER_SWITCH",
                    targetPackageName = appContext.packageName,
                    targetAppName = "Battery Boss Saver Control",
                    detectedBatteryUsageSummary = "Battery at ${snapshot.value.percentage}%",
                    alertIssued = "User selected ${mode.title}",
                    restrictionOrActionApplied = "Switched from ${previous.title} to ${mode.title} (${mode.summary})",
                    actionSucceeded = true,
                    executionStatus = RestrictionExecutionStatus.SELF_INTERNAL_SAVER_APPLIED.name,
                    wasActuallyRestrictedByApp = true,
                    openedSystemSettingsOnly = false,
                    saverModeState = mode.name
                )
            }
        }
    }

    fun updateAutoBatterySaverSettings(
        enabled: Boolean? = null,
        thresholdPercent: Int? = null,
        targetMode: BatterySaverMode? = null,
        restoreNormalEnabled: Boolean? = null,
        recoveryPercent: Int? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateAutoBatterySaverSettings(
                enabled = enabled,
                thresholdPercent = thresholdPercent,
                targetMode = targetMode,
                restoreNormalEnabled = restoreNormalEnabled,
                recoveryPercent = recoveryPercent
            )
        }
    }

    fun updateAutoRestrictionSettings(
        autoRestrictEnabled: Boolean? = null,
        restrictionThresholdPercent: Int? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateAutoRestrictionSettings(
                autoRestrictEnabled = autoRestrictEnabled,
                restrictionThresholdPercent = restrictionThresholdPercent
            )
        }
    }

    fun toggleAppProtected(packageName: String, isProtected: Boolean) {
        viewModelScope.launch {
            settingsDataStore.toggleAppProtectedState(packageName, isProtected)
            refreshSmartBatteryTracker(forcePersist = false)
        }
    }

    fun toggleAppExcluded(packageName: String, isExcluded: Boolean) {
        viewModelScope.launch {
            settingsDataStore.toggleAppExcludedState(packageName, isExcluded)
            refreshSmartBatteryTracker(forcePersist = false)
        }
    }

    fun toggleAppUserPermittedRestrict(packageName: String, isPermitted: Boolean) {
        viewModelScope.launch {
            settingsDataStore.toggleAppUserPermittedRestrict(packageName, isPermitted)
            refreshSmartBatteryTracker(forcePersist = false)
        }
    }

    fun updateSmartAlertToggles(
        highAppUsage: Boolean? = null,
        highBackgroundActivity: Boolean? = null,
        rapidDrain: Boolean? = null,
        highBatteryTemp: Boolean? = null,
        saverRecommendation: Boolean? = null,
        abnormalScreenDrain: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateSmartAlertToggles(
                highAppUsage = highAppUsage,
                highBackgroundActivity = highBackgroundActivity,
                rapidDrain = rapidDrain,
                highBatteryTemp = highBatteryTemp,
                saverRecommendation = saverRecommendation,
                abnormalScreenDrain = abnormalScreenDrain
            )
        }
    }

    fun recordUserAppPowerAction(
        app: AppBatteryUsageRecord,
        actionDescription: String,
        intentLaunchedSuccessfully: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.logAutoManagementReport(
                eventCategory = "OPENED_SYSTEM_SETTINGS_ONLY",
                targetPackageName = app.packageName,
                targetAppName = app.appName,
                detectedBatteryUsageSummary = "Fg: ${app.formattedForegroundTime} • ${app.optimizationStatusLabel}",
                alertIssued = "Opened Android System Settings for Manual User Action",
                restrictionOrActionApplied = "$actionDescription — Opened System Settings Only (App NOT restricted or force-stopped by Battery Boss)",
                actionSucceeded = intentLaunchedSuccessfully,
                executionStatus = RestrictionExecutionStatus.OPENED_SYSTEM_SETTINGS_ONLY.name,
                wasActuallyRestrictedByApp = false,
                openedSystemSettingsOnly = true,
                saverModeState = userSettings.value.batterySaverMode.name
            )
        }
    }

    fun deleteOldUsageHistory(olderThanDays: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - (olderThanDays.coerceAtLeast(1) * 86_400_000L)
            repository.deleteUsageHistoryOlderThan(cutoff)
        }
    }

    fun clearSmartTrackerHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearSmartTrackerHistory()
        }
    }

    suspend fun exportSmartTrackerCsv(): String = withContext(Dispatchers.IO) {
        repository.buildExportCsv(
            historyRecords = batteryUsageHistory.value,
            appRecords = appUsageRecords.value,
            autoReports = autoManagementReports.value
        )
    }

    fun refreshAudioDiagnostics() {
        voiceAssistant.refreshDiagnosticsSnapshot()
    }

    private fun checkIgnoringBatteryOptimizations(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun toggleBackgroundMonitoring(enable: Boolean) {
        viewModelScope.launch {
            val currentMode = userSettings.value.backgroundMonitoringMode
            settingsDataStore.updateBackgroundSettings(
                backgroundMonitoring = enable,
                persistentNotification = enable && currentMode.requiresPersistentNotification,
                monitoringMode = currentMode
            )
            SilentBackgroundMonitorEngine.applyMonitoringMode(
                context = appContext,
                enabled = enable,
                mode = currentMode
            )
        }
    }

    /**
     * Switches cleanly between:
     * 1. `NO_PERSISTENT_HOURLY` -> stops `BatteryMonitorService`, cancels Notification ID 4010 (0 persistent notifications),
     *    keeps instant charger events via `BootAndPowerReceiver`, and schedules 1-hour `JobScheduler` checks.
     * 2. `SILENT_BACKGROUND` -> `battery_boss_silent_channel` (`IMPORTANCE_MIN` compact FGS notification).
     * 3. `CONTINUOUS_FOREGROUND` -> `battery_boss_standard_channel` (`IMPORTANCE_LOW` FGS notification with buttons).
     */
    fun setBackgroundMonitoringMode(mode: BackgroundMonitoringMode) {
        viewModelScope.launch {
            val currentEnabled = userSettings.value.backgroundMonitoringEnabled
            val isSilent = mode == BackgroundMonitoringMode.SILENT_BACKGROUND
            settingsDataStore.updateBackgroundMonitoringMode(mode)
            BatteryTelemetryHub.setActiveMonitoringMode(mode)

            if (mode == BackgroundMonitoringMode.NO_PERSISTENT_HOURLY) {
                BatteryTelemetryHub.setSilentBackgroundActive(currentEnabled)
                SilentBackgroundMonitorEngine.applyMonitoringMode(
                    context = appContext,
                    enabled = currentEnabled,
                    mode = BackgroundMonitoringMode.NO_PERSISTENT_HOURLY
                )
            } else {
                BatteryTelemetryHub.setSilentBackgroundActive(isSilent)
                if (currentEnabled || BatteryTelemetryHub.isServiceRunning.value || BatteryMonitorService.isLiveServiceInstanceActive()) {
                    if (BatteryMonitorService.isLiveServiceInstanceActive()) {
                        BatteryMonitorService.refreshNotificationInPlace(appContext, silentOverride = isSilent)
                    } else {
                        SilentBackgroundMonitorEngine.applyMonitoringMode(
                            context = appContext,
                            enabled = true,
                            mode = mode
                        )
                    }
                }
            }
            BatteryTelemetryHub.refreshNotificationChannelDiagnostics(appContext, isSilent)
        }
    }

    fun updateHourlyStatusNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.updateHourlyStatusNotificationEnabled(enabled)
            if (!enabled) {
                SilentBackgroundMonitorEngine.cancelHourlyStatusNotification(appContext)
            }
        }
    }

    fun updateAutoDisableRadiosSettings(
        enabled: Boolean? = null,
        disableBluetooth: Boolean? = null,
        disableWifiAndData: Boolean? = null,
        restoreOnUnplug: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateAutoDisableRadiosSettings(
                enabled = enabled,
                disableBluetooth = disableBluetooth,
                disableWifiAndData = disableWifiAndData,
                restoreOnUnplug = restoreOnUnplug
            )
            if (enabled == false) {
                settingsDataStore.clearPreChargeRadioSnapshot()
                ChargerRadioController.cancelAllRadioNotifications(appContext)
            }
        }
    }

    fun openInternetConnectivityPanel() {
        ChargerRadioController.openInternetConnectivityPanel(appContext)
    }

    fun openAccessibilitySettingsForRadioHelper() {
        ChargerRadioController.openAccessibilitySettings(appContext)
    }

    fun openAppDetailsForRestrictedSettings() {
        ChargerRadioController.openAppDetailsSettings(appContext)
    }

    /**
     * Independent Silent Notification ON/OFF toggle (Control #3):
     * Reposts `NOTIFICATION_ID` between `battery_boss_silent_channel` and
     * `battery_boss_standard_channel` WITHOUT stopping/restarting `BatteryMonitorService`
     * or muting Background Voice Announcements. Never attempts to mutate an existing channel's importance.
     */
    fun toggleSilentNotificationEnabled(enable: Boolean) {
        viewModelScope.launch {
            val targetMode = if (enable) {
                BackgroundMonitoringMode.SILENT_BACKGROUND
            } else {
                BackgroundMonitoringMode.CONTINUOUS_FOREGROUND
            }
            settingsDataStore.updateSilentNotificationEnabled(enable)
            BatteryTelemetryHub.setActiveMonitoringMode(targetMode)
            BatteryTelemetryHub.setSilentBackgroundActive(enable)
            if (userSettings.value.backgroundMonitoringEnabled ||
                BatteryTelemetryHub.isServiceRunning.value ||
                BatteryMonitorService.isLiveServiceInstanceActive()
            ) {
                if (BatteryMonitorService.isLiveServiceInstanceActive()) {
                    BatteryMonitorService.refreshNotificationInPlace(appContext, silentOverride = enable)
                } else {
                    SilentBackgroundMonitorEngine.applyMonitoringMode(
                        context = appContext,
                        enabled = true,
                        mode = targetMode
                    )
                }
            }
            BatteryTelemetryHub.refreshNotificationChannelDiagnostics(appContext, enable)
        }
    }

    /**
     * Independent Background Voice ON/OFF toggle (Control #2):
     * Turning Background Voice OFF mutes spoken alerts ONLY; background monitoring remains active.
     */
    fun toggleVoiceAnnouncementsEnabled(enable: Boolean) {
        viewModelScope.launch {
            settingsDataStore.updateVoiceAnnouncementsEnabled(enable)
            if (!enable) {
                voiceAssistant.cancelPendingNonCriticalAnnouncements()
            }
            if (BatteryMonitorService.isLiveServiceInstanceActive()) {
                BatteryMonitorService.refreshNotificationInPlace(appContext)
            }
        }
    }

    /**
     * Dispatches any of the 5 Notification Control actions (`ACTION_START_MONITORING`,
     * `ACTION_STOP_MONITORING`, `ACTION_SPEAK_NOW`, `ACTION_MUTE_VOICE`, `ACTION_RESUME_VOICE`)
     * directly to `BatteryMonitorService`.
     */
    fun triggerNotificationAction(action: String) {
        val intent = android.content.Intent(appContext, BatteryMonitorService::class.java).apply {
            this.action = action
        }
        if (action == BatteryMonitorService.ACTION_STOP_MONITORING) {
            toggleBackgroundMonitoring(false)
        } else {
            runCatching {
                appContext.startService(intent)
            }
        }
    }

    fun selectVoicePreset(preset: VoiceProfilePreset) {
        viewModelScope.launch {
            settingsDataStore.selectVoicePreset(preset)
        }
    }

    fun previewVoicePreset(preset: VoiceProfilePreset, customSampleText: String? = null) {
        voiceAssistant.previewVoicePreset(preset, customSampleText)
    }

    fun stopSpeaking() {
        voiceAssistant.stopSpeaking()
    }

    fun updateLanguage(language: VoiceLanguage) {
        viewModelScope.launch {
            settingsDataStore.updateLanguage(language)
        }
    }

    fun updateGender(gender: VoiceGender) {
        viewModelScope.launch {
            settingsDataStore.updateGender(gender)
        }
    }

    fun updateVoiceParameters(speed: Float, pitch: Float, volume: Float, naturalProsody: Boolean? = null) {
        voiceAssistant.updateLiveIndependentVolume(volume)
        viewModelScope.launch {
            settingsDataStore.updateVoiceParameters(speed, pitch, volume, naturalProsody)
        }
    }

    fun updateLiveIndependentVolume(volume: Float) {
        voiceAssistant.updateLiveIndependentVolume(volume)
    }

    fun updateIndependentVolumeSettings(
        independentEnabled: Boolean? = null,
        volume: Float? = null,
        rememberLastVolume: Boolean? = null,
        applyToAllAnnouncements: Boolean? = null,
        mediaDuckingLevel: MediaDuckingLevel? = null,
        repeatPolicy: AnnouncementRepeatPolicy? = null,
        autoLoudnessNormalization: Boolean? = null,
        audioStreamUsageOption: AudioStreamUsageOption? = null
    ) {
        if (volume != null) {
            voiceAssistant.updateLiveIndependentVolume(volume)
        }
        viewModelScope.launch {
            settingsDataStore.updateIndependentVolumeSettings(
                independentEnabled = independentEnabled,
                volume = volume,
                rememberLastVolume = rememberLastVolume,
                applyToAllAnnouncements = applyToAllAnnouncements,
                mediaDuckingLevel = mediaDuckingLevel,
                repeatPolicy = repeatPolicy,
                autoLoudnessNormalization = autoLoudnessNormalization,
                audioStreamUsageOption = audioStreamUsageOption
            )
        }
    }

    fun testVoiceNow(customPhrase: String? = null) {
        val currentSnapshot = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(currentSnapshot)
        voiceAssistant.speakImmediateSample(customMessage = customPhrase, snapshot = currentSnapshot)
    }

    fun testChargerConnectedNow() {
        val currentSnapshot = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(currentSnapshot)
        voiceAssistant.speakChargerConnectedTest(currentSnapshot)
    }

    fun testPriorityQueueBurst() {
        val currentSnapshot = hardwareReader.readSnapshot()
        BatteryTelemetryHub.updateSnapshot(currentSnapshot)
        voiceAssistant.enqueuePriorityBurstTest(currentSnapshot)
    }

    fun installOrVerifyModelPack(modelId: String) {
        voiceAssistant.installOrVerifyModelPack(modelId)
    }

    fun saveCustomMessages(
        isHindi: Boolean,
        connected: String,
        disconnected: String,
        milestone20: String,
        milestone50: String,
        milestone80: String,
        interval: String,
        full: String,
        lowBattery: String,
        highTemp: String
    ) {
        viewModelScope.launch {
            settingsDataStore.updateCustomMessages(
                isHindi = isHindi,
                connected = connected,
                disconnected = disconnected,
                milestone20 = milestone20,
                milestone50 = milestone50,
                milestone80 = milestone80,
                interval = interval,
                full = full,
                lowBattery = lowBattery,
                highTemp = highTemp
            )
        }
    }

    fun resetCustomMessages() {
        viewModelScope.launch {
            settingsDataStore.resetCustomMessagesToDefault()
        }
    }

    fun updateChargingAlerts(
        intervalPercent: Int? = null,
        intervalEnabled: Boolean? = null,
        smartMilestones: Boolean? = null,
        fullCharge: Boolean? = null,
        chargerConnected: Boolean? = null,
        chargerDisconnected: Boolean? = null,
        percentOnConnect: Boolean? = null,
        percentOnDisconnect: Boolean? = null,
        lowBattery: Boolean? = null,
        lowBatteryThreshold: Int? = null,
        tempWarning: Boolean? = null,
        tempWarningCelsius: Int? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateChargingAlerts(
                intervalPercent = intervalPercent,
                intervalEnabled = intervalEnabled,
                smartMilestones = smartMilestones,
                fullCharge = fullCharge,
                chargerConnected = chargerConnected,
                chargerDisconnected = chargerDisconnected,
                percentOnConnect = percentOnConnect,
                percentOnDisconnect = percentOnDisconnect,
                lowBattery = lowBattery,
                lowBatteryThreshold = lowBatteryThreshold,
                tempWarning = tempWarning,
                tempWarningCelsius = tempWarningCelsius
            )
        }
    }

    fun updateQuietHoursAndRinger(
        quietHoursEnabled: Boolean? = null,
        quietStartHour: Int? = null,
        quietEndHour: Int? = null,
        respectSystemRinger: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateQuietHoursAndRinger(
                quietHoursEnabled = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietEndHour = quietEndHour,
                respectSystemRinger = respectSystemRinger
            )
        }
    }

    fun updateBackgroundSettings(
        startOnBoot: Boolean? = null,
        persistentNotification: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsDataStore.updateBackgroundSettings(
                startOnBoot = startOnBoot,
                persistentNotification = persistentNotification
            )
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }
}
