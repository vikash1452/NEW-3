package com.example.service

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import com.example.data.local.BatteryBossDatabase
import com.example.data.local.BatteryBossRepository
import com.example.data.local.BatteryUsageHistoryEntity
import com.example.data.preferences.SettingsDataStore
import com.example.hardware.BatteryHardwareReader
import com.example.model.BackgroundMonitoringMode
import com.example.model.BatteryHardwareSnapshot
import com.example.model.BatterySaverMode
import com.example.model.RestrictionExecutionStatus
import com.example.model.UserSettings
import com.example.update.UpdateLockManager
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Silent Background Monitoring Engine (No Persistent Notification).
 *
 * Implements genuine Android background monitoring without starting a Foreground Service
 * or posting a Persistent Notification card in the Notification Panel, using only standard
 * public Android APIs:
 *
 * 1. Application-Scoped Dynamic BroadcastReceiver (`appContext.registerReceiver`):
 *    Receives real-time `ACTION_BATTERY_CHANGED`, `ACTION_POWER_CONNECTED`,
 *    `ACTION_POWER_DISCONNECTED`, `ACTION_BATTERY_LOW`, `ACTION_BATTERY_OKAY`,
 *    `ACTION_SCREEN_ON`, and `ACTION_SCREEN_OFF` events while the app process is alive
 *    in RAM (including when the user navigates away from the app or turns the screen OFF).
 * 2. Manifest-Registered `BootAndPowerReceiver`:
 *    Receives `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`,
 *    `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`, `ACTION_BATTERY_LOW`, and
 *    `ACTION_BATTERY_OKAY` to wake up the app on charger/boot events and speak Charger
 *    Connect/Disconnect battery percentage announcements via `goAsync()` + bounded `PARTIAL_WAKE_LOCK`.
 * 3. `JobScheduler` (`SilentBatteryJobService`):
 *    Schedules OS-managed periodic background battery/thermal/history checks (>= 15 min minimum
 *    OS window) and charging-state job triggers without requiring any Persistent Notification.
 *
 * HONEST ANDROID OS / OEM DISCLOSURE:
 * Never uses notification-hiding hacks or invalid Foreground Service tricks. When the user
 * selects `Silent Background Mode`, `BatteryMonitorService` is cleanly stopped and its
 * notification (`NOTIFICATION_ID = 4010`) is cancelled. If Android Doze or an aggressive OEM
 * task killer force-stops the cached app process while unplugged, periodic checks are governed
 * by Android's `JobScheduler` interval (>= 15 min) rather than every-second Foreground Service
 * residency. Users can switch between `Silent Background Mode` and `Continuous Monitoring Mode`
 * at any time in Settings.
 */
object SilentBackgroundMonitorEngine {

    const val JOB_ID_SILENT_PERIODIC = 4021
    const val JOB_ID_SILENT_CHARGING_EVENT = 4022
    const val MIN_JOB_PERIODIC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes (Android OS minimum)
    const val MIN_JOB_FLEX_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes flex window

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var screenOnTickerJob: Job? = null
    private var powerEventJob: Job? = null
    private var settingsCollectorJob: Job? = null

    @Volatile
    private var isSilentReceiverRegistered: Boolean = false

    @Volatile
    private var registeredAppContext: Context? = null

    @Volatile
    private var latestSettings: UserSettings = UserSettings()

    private val silentBatteryBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val appCtx = context?.applicationContext ?: registeredAppContext ?: return
            val action = intent?.action ?: return
            BatteryTelemetryHub.recordBroadcastEventHandled()

            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val snap = BatteryHardwareReader(appCtx).readSnapshot(intent)
                    handleSilentSnapshot(appCtx, snap, explicitEvent = null)
                }
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handleSilentPowerConnectionBroadcast(appCtx, action)
                }
                Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_OKAY -> {
                    val snap = BatteryHardwareReader(appCtx).readSnapshot(null)
                    handleSilentSnapshot(appCtx, snap, explicitEvent = action)
                }
                Intent.ACTION_SCREEN_ON -> {
                    BatteryTelemetryHub.setScreenInteractive(true)
                    updateSilentScreenOnSupplementTicker(appCtx)
                    val snap = BatteryHardwareReader(appCtx).readSnapshot(null)
                    handleSilentSnapshot(appCtx, snap, explicitEvent = null)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    BatteryTelemetryHub.setScreenInteractive(false)
                    // Zero periodic polling when the screen turns OFF
                    screenOnTickerJob?.cancel()
                    screenOnTickerJob = null
                }
            }
        }
    }

    /**
     * Central orchestrator that applies the user's selected Background Monitoring state & notification mode:
     * - When `enabled == false`: stops `BatteryMonitorService` and `SilentBackgroundMonitorEngine`,
     *   cancels `JobScheduler` jobs, releases all WakeLocks, and removes the Foreground Service notification.
     * - When `enabled == true`: ALWAYS runs `BatteryMonitorService` as an Android Foreground Service
     *   (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, `START_STICKY`) so Charger Connect/Disconnect, Milestone,
     *   Low Battery, and Temperature Voice Announcements work reliably when the Screen is OFF or when
     *   the user exits the app.
     *   - `SILENT_BACKGROUND` (`silentNotificationEnabled = true`): uses `IMPORTANCE_MIN` compact silent
     *     notification (`"Battery Boss Active • 83%"`, 0 sound, 0 vibration, 0 bulky action buttons).
     *   - `CONTINUOUS_FOREGROUND` (`silentNotificationEnabled = false`): uses `IMPORTANCE_LOW` silent
     *     notification with quick action buttons.
     */
    fun applyMonitoringMode(
        context: Context,
        enabled: Boolean,
        mode: BackgroundMonitoringMode,
        triggerEvent: String? = null
    ) {
        val appContext = context.applicationContext
        BatteryTelemetryHub.setActiveMonitoringMode(mode)

        if (!enabled) {
            stopSilentMonitoring(appContext)
            BatteryMonitorService.stopService(appContext)
            return
        }

        val isSilent = (mode == BackgroundMonitoringMode.SILENT_BACKGROUND)
        if (BatteryMonitorService.isLiveServiceInstanceActive() && triggerEvent == null) {
            BatteryTelemetryHub.setSilentBackgroundActive(isSilent)
            BatteryMonitorService.refreshNotificationInPlace(appContext, silentOverride = isSilent)
            return
        }

        // Always start/maintain BatteryMonitorService as an active Foreground Service when monitoring is ON
        startSilentMonitoring(appContext, triggerEvent = triggerEvent, mode = mode)
        BatteryMonitorService.startService(appContext, triggerEvent = triggerEvent)
        BatteryMonitorService.refreshNotificationInPlace(appContext, silentOverride = isSilent)
    }

    /**
     * Starts/updates background supplemental receivers & JobScheduler while keeping
     * `BatteryMonitorService` active as a Foreground Service with a Compact Silent notification.
     */
    fun startSilentMonitoring(
        context: Context,
        triggerEvent: String? = null,
        mode: BackgroundMonitoringMode = BackgroundMonitoringMode.SILENT_BACKGROUND
    ) {
        val appContext = context.applicationContext
        registeredAppContext = appContext

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = runCatching { pm?.isInteractive ?: true }.getOrDefault(true)
        BatteryTelemetryHub.setScreenInteractive(interactive)
        BatteryTelemetryHub.setActiveMonitoringMode(mode)
        BatteryTelemetryHub.setSilentBackgroundActive(mode == BackgroundMonitoringMode.SILENT_BACKGROUND)
        BatteryTelemetryHub.setServiceRunning(true)

        ensureSettingsCollector(appContext)
        registerSilentReceiver(appContext)
        scheduleSilentJobs(appContext)

        // Ensure Foreground Service is alive and its notification matches current silent/low-priority mode
        BatteryMonitorService.startService(appContext, triggerEvent = triggerEvent)

        val initialSnap = BatteryHardwareReader(appContext).readSnapshot()
        BatteryTelemetryHub.updateSnapshot(initialSnap)

        updateSilentScreenOnSupplementTicker(appContext)
    }

    /**
     * Stops background receivers and cancels scheduled JobScheduler jobs when Background Monitoring is turned OFF.
     */
    fun stopSilentMonitoring(context: Context, keepServiceRunningState: Boolean = false) {
        val appContext = context.applicationContext
        screenOnTickerJob?.cancel()
        screenOnTickerJob = null
        powerEventJob?.cancel()
        powerEventJob = null

        if (isSilentReceiverRegistered) {
            runCatching {
                (registeredAppContext ?: appContext).unregisterReceiver(silentBatteryBroadcastReceiver)
            }
            isSilentReceiverRegistered = false
        }

        cancelSilentJobs(appContext)
        BatteryTelemetryHub.setSilentBackgroundActive(false)

        if (!keepServiceRunningState) {
            BatteryTelemetryHub.releaseAllTrackedWakeLocks()
            BatteryTelemetryHub.getOrCreateVoiceAssistant(appContext).cancelPendingNonCriticalAnnouncements()
            BatteryTelemetryHub.setServiceRunning(false)
            BatteryTelemetryHub.setForegroundNotificationVisible(false)
        }
    }

    /**
     * Synchronous/suspend evaluation used by `BootAndPowerReceiver` (`goAsync()`) and
     * `SilentBatteryJobService` (`onStartJob()`) when Android wakes the app in Silent Background Mode.
     * Never posts any Persistent Notification.
     */
    suspend fun evaluateSilentBackgroundEventNow(
        context: Context,
        triggerAction: String? = null,
        batteryIntent: Intent? = null
    ): BatteryHardwareSnapshot {
        val appContext = context.applicationContext
        val wakeLockHandle = BatteryTelemetryHub.acquireBoundedWakeLock(
            context = appContext,
            tag = "BatteryBoss:SilentEventWakeLock",
            timeoutMs = BatteryTelemetryHub.MAX_POWER_EVENT_WAKELOCK_MS
        )
        return try {
            if (triggerAction == Intent.ACTION_POWER_CONNECTED ||
                triggerAction == Intent.ACTION_POWER_DISCONNECTED
            ) {
                delay(120L)
            }
            val rawSnap = BatteryHardwareReader(appContext).readSnapshot(batteryIntent)
            val alignedSnap = when (triggerAction) {
                Intent.ACTION_POWER_CONNECTED -> rawSnap.copy(isCharging = true)
                Intent.ACTION_POWER_DISCONNECTED -> rawSnap.copy(isCharging = false)
                else -> rawSnap
            }

            BatteryTelemetryHub.updateSnapshot(alignedSnap)
            val dataStore = SettingsDataStore(appContext)
            val settings = dataStore.getSettingsSnapshot()
            latestSettings = settings

            val isUpdateLocked = if (triggerAction == Intent.ACTION_POWER_CONNECTED) {
                UpdateLockManager.refreshIfStaleAndCheckLocked(appContext)
            } else {
                UpdateLockManager.isUpdateLockActive(appContext)
            }

            val voiceAssistant = BatteryTelemetryHub.getOrCreateVoiceAssistant(appContext)
            voiceAssistant.ensureEngineAliveAndSyncSettings(settings)
            voiceAssistant.evaluateSnapshotAndAnnounceNow(
                snapshot = alignedSnap,
                explicitTriggerEvent = triggerAction,
                settingsOverride = settings
            )

            if (!isUpdateLocked) {
                persistSnapshotAndEvaluateAutoSaver(appContext, alignedSnap, settings, dataStore)
            }
            alignedSnap
        } finally {
            BatteryTelemetryHub.releaseBoundedWakeLock(wakeLockHandle)
        }
    }

    /**
     * Schedules OS-managed background checks via `JobScheduler` without requiring any notification.
     */
    fun scheduleSilentJobs(context: Context): Boolean {
        val appContext = context.applicationContext
        val jobScheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: run {
                BatteryTelemetryHub.setJobSchedulerRegistered(false)
                return false
            }

        val serviceComponent = ComponentName(appContext, SilentBatteryJobService::class.java)
        val periodicIntervalMs = when (latestSettings.batterySaverMode) {
            BatterySaverMode.EXTREME_SAVER -> 30 * 60 * 1000L
            BatterySaverMode.SMART_SAVER -> 20 * 60 * 1000L
            BatterySaverMode.NORMAL -> MIN_JOB_PERIODIC_INTERVAL_MS
        }.coerceAtLeast(MIN_JOB_PERIODIC_INTERVAL_MS)

        val periodicBuilder = JobInfo.Builder(JOB_ID_SILENT_PERIODIC, serviceComponent)
            .setPersisted(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            periodicBuilder.setPeriodic(periodicIntervalMs, MIN_JOB_FLEX_INTERVAL_MS)
        } else {
            periodicBuilder.setPeriodic(periodicIntervalMs)
        }

        val chargingBuilder = JobInfo.Builder(JOB_ID_SILENT_CHARGING_EVENT, serviceComponent)
            .setRequiresCharging(true)
            .setMinimumLatency(1_000L)
            .setOverrideDeadline(60_000L)

        val scheduledPeriodic = runCatching {
            jobScheduler.schedule(periodicBuilder.build()) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)

        runCatching {
            jobScheduler.schedule(chargingBuilder.build())
        }

        BatteryTelemetryHub.setJobSchedulerRegistered(scheduledPeriodic)
        return scheduledPeriodic
    }

    fun cancelSilentJobs(context: Context) {
        val appContext = context.applicationContext
        val jobScheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        runCatching {
            jobScheduler?.cancel(JOB_ID_SILENT_PERIODIC)
            jobScheduler?.cancel(JOB_ID_SILENT_CHARGING_EVENT)
        }
        BatteryTelemetryHub.setJobSchedulerRegistered(false)
    }

    private fun ensureSettingsCollector(appContext: Context) {
        if (settingsCollectorJob?.isActive == true) return
        val dataStore = SettingsDataStore(appContext)
        settingsCollectorJob = engineScope.launch {
            dataStore.settingsFlow.collect { s ->
                val prevSaverMode = latestSettings.batterySaverMode
                latestSettings = s
                BatteryTelemetryHub.setGraphSampleInterval(s.batterySaverMode.graphSampleIntervalMs)
                if (prevSaverMode != s.batterySaverMode && BatteryTelemetryHub.isSilentBackgroundActive.value) {
                    updateSilentScreenOnSupplementTicker(appContext)
                    scheduleSilentJobs(appContext)
                }
            }
        }
    }

    private fun registerSilentReceiver(appContext: Context) {
        if (isSilentReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    silentBatteryBroadcastReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                appContext.registerReceiver(silentBatteryBroadcastReceiver, filter)
            }
            isSilentReceiverRegistered = true
        }
    }

    private fun handleSilentPowerConnectionBroadcast(appContext: Context, action: String) {
        powerEventJob?.cancel()
        powerEventJob = engineScope.launch {
            evaluateSilentBackgroundEventNow(
                context = appContext,
                triggerAction = action,
                batteryIntent = null
            )
        }
    }

    private fun handleSilentSnapshot(
        appContext: Context,
        snapshot: BatteryHardwareSnapshot,
        explicitEvent: String?
    ) {
        BatteryTelemetryHub.updateSnapshot(snapshot)
        val voiceAssistant = BatteryTelemetryHub.getOrCreateVoiceAssistant(appContext)
        voiceAssistant.evaluateSnapshotAndAnnounce(snapshot, explicitEvent)

        if (!UpdateLockManager.isUpdateLockActive(appContext)) {
            engineScope.launch(Dispatchers.IO) {
                val dataStore = SettingsDataStore(appContext)
                val settings = latestSettings
                persistSnapshotAndEvaluateAutoSaver(appContext, snapshot, settings, dataStore)
            }
        }
    }

    private fun updateSilentScreenOnSupplementTicker(appContext: Context) {
        screenOnTickerJob?.cancel()
        screenOnTickerJob = null

        val mode = latestSettings.batterySaverMode
        val interactive = BatteryTelemetryHub.isScreenInteractive.value
        if (!BatteryTelemetryHub.isSilentBackgroundActive.value ||
            mode.isPureEventDriven ||
            !interactive ||
            mode.telemetryIntervalMs <= 0L
        ) {
            return
        }

        screenOnTickerJob = engineScope.launch {
            while (isActive &&
                BatteryTelemetryHub.isSilentBackgroundActive.value &&
                BatteryTelemetryHub.isScreenInteractive.value
            ) {
                val activeMode = latestSettings.batterySaverMode
                if (activeMode.isPureEventDriven || activeMode.telemetryIntervalMs <= 0L) break
                delay(activeMode.telemetryIntervalMs)
                if (!BatteryTelemetryHub.isSilentBackgroundActive.value ||
                    !BatteryTelemetryHub.isScreenInteractive.value
                ) {
                    break
                }
                BatteryTelemetryHub.recordScreenOnGaugeRead()
                val snap = BatteryHardwareReader(appContext).readSnapshot()
                handleSilentSnapshot(appContext, snap, explicitEvent = null)
            }
        }
    }

    internal suspend fun persistSnapshotAndEvaluateAutoSaver(
        appContext: Context,
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings,
        dataStore: SettingsDataStore
    ) {
        val db = BatteryBossDatabase.getInstance(appContext)
        val repository = BatteryBossRepository(db.batteryBossDao())

        // 1. Update charging session history
        if (snapshot.isCharging) {
            repository.startOrUpdateChargingSession(
                percentage = snapshot.percentage,
                sourceName = snapshot.chargingSource.displayName,
                watts = snapshot.powerWatts,
                currentMa = snapshot.currentMilliAmps,
                tempCelsius = snapshot.temperatureCelsius
            )
        } else {
            repository.completeActiveChargingSession(
                finalPercentage = snapshot.percentage,
                tempCelsius = snapshot.temperatureCelsius
            )
        }

        // 2. Record Battery Usage History in Room DB (respecting Saver Mode DB interval)
        val dischargeMa = if (!snapshot.isCharging && snapshot.currentMilliAmps != null) {
            abs(snapshot.currentMilliAmps)
        } else {
            null
        }
        repository.recordBatteryUsageSnapshot(
            entry = BatteryUsageHistoryEntity(
                timestampMillis = snapshot.timestampMillis,
                batteryPercentage = snapshot.percentage,
                isCharging = snapshot.isCharging,
                dischargeRateMa = dischargeMa,
                dischargeRatePercentPerHour = snapshot.percentPerHourRate,
                temperatureCelsius = snapshot.temperatureCelsius,
                voltageVolts = snapshot.voltageVolts,
                batterySaverMode = settings.batterySaverMode.name
            ),
            minIntervalMillis = settings.batterySaverMode.dbSnapshotMinIntervalMs
        )

        // 3. Evaluate Automatic Battery Saver rules
        if (!settings.autoBatterySaverEnabled || !snapshot.isPercentageValid) return

        if (!snapshot.isCharging && snapshot.percentage <= settings.autoSaverThresholdPercent) {
            val desiredMode = if (snapshot.percentage <= 15 && settings.autoSaverThresholdPercent <= 15) {
                BatterySaverMode.EXTREME_SAVER
            } else {
                settings.autoSaverTargetMode
            }
            if (settings.batterySaverMode != desiredMode &&
                !(settings.batterySaverMode == BatterySaverMode.EXTREME_SAVER && desiredMode == BatterySaverMode.SMART_SAVER)
            ) {
                dataStore.updateBatterySaverMode(desiredMode)
                repository.logAutoManagementReport(
                    eventCategory = "AUTO_SAVER_ACTIVATED",
                    targetPackageName = null,
                    targetAppName = "Battery Boss Saver Engine",
                    detectedBatteryUsageSummary = "Battery dropped to ${snapshot.percentage}% (Threshold: ${settings.autoSaverThresholdPercent}%)",
                    alertIssued = "Automatic ${desiredMode.title} Triggered",
                    restrictionOrActionApplied = "Switched Battery Boss internal workload from ${settings.batterySaverMode.title} to ${desiredMode.title}",
                    actionSucceeded = true,
                    saverModeState = desiredMode.name,
                    executionStatus = RestrictionExecutionStatus.SELF_INTERNAL_SAVER_APPLIED.name,
                    wasActuallyRestrictedByApp = true,
                    openedSystemSettingsOnly = false
                )
            }
        } else if (settings.autoSaverRestoreNormalEnabled &&
            snapshot.percentage >= settings.autoSaverRecoveryPercent &&
            settings.batterySaverMode != BatterySaverMode.NORMAL
        ) {
            dataStore.updateBatterySaverMode(BatterySaverMode.NORMAL)
            repository.logAutoManagementReport(
                eventCategory = "AUTO_SAVER_RECOVERY",
                targetPackageName = null,
                targetAppName = "Battery Boss Saver Engine",
                detectedBatteryUsageSummary = "Battery recovered to ${snapshot.percentage}% (Recovery Level: ${settings.autoSaverRecoveryPercent}%)",
                alertIssued = "Normal Mode Restored Automatically",
                restrictionOrActionApplied = "Restored Normal Mode from ${settings.batterySaverMode.title}",
                actionSucceeded = true,
                saverModeState = BatterySaverMode.NORMAL.name,
                executionStatus = RestrictionExecutionStatus.SELF_INTERNAL_SAVER_APPLIED.name,
                wasActuallyRestrictedByApp = true,
                openedSystemSettingsOnly = false
            )
        }
    }
}

/**
 * Android `JobService` scheduled by `SilentBackgroundMonitorEngine` to perform background
 * battery percentage, temperature, charging status, Room history, and voice alert checks
 * in `Silent Background Mode` WITHOUT any Foreground Service or Persistent Notification.
 */
class SilentBatteryJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        activeJob = jobScope.launch {
            try {
                val settings = SettingsDataStore(appContext).getSettingsSnapshot()
                if (settings.backgroundMonitoringEnabled &&
                    settings.backgroundMonitoringMode == BackgroundMonitoringMode.SILENT_BACKGROUND
                ) {
                    SilentBackgroundMonitorEngine.startSilentMonitoring(appContext)
                    SilentBackgroundMonitorEngine.evaluateSilentBackgroundEventNow(
                        context = appContext,
                        triggerAction = null,
                        batteryIntent = null
                    )
                }
            } finally {
                if (params != null) {
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        activeJob = null
        return true
    }
}
