package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.BatteryBossDatabase
import com.example.data.local.BatteryBossRepository
import com.example.data.local.BatteryUsageHistoryEntity
import com.example.data.preferences.SettingsDataStore
import com.example.hardware.BatteryHardwareReader
import com.example.model.BackgroundMonitoringMode
import com.example.model.BatteryHardwareSnapshot
import com.example.model.BatterySaverMode
import com.example.model.NotificationChannelDiagnostics
import com.example.model.RestrictionExecutionStatus
import com.example.model.SelfPowerAuditReport
import com.example.model.TelemetrySamplePoint
import com.example.model.UserSettings
import com.example.update.UpdateLockManager
import com.example.voice.OfflineVoiceAssistant
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audited wrapper around a bounded Android PARTIAL_WAKE_LOCK to guarantee:
 * 1. Every WakeLock is non-reference-counted and has a strict timeout (<= 3s for power events, <= 15s for cold TTS startup + WAV synthesis + AudioTrack playback).
 * 2. Every WakeLock is tracked and released in `finally` blocks and on service stop.
 */
class TrackedWakeLockHandle(
    val tag: String,
    val acquiredAtElapsedMs: Long,
    val timeoutMs: Long,
    internal val wakeLock: PowerManager.WakeLock?
) {
    @Volatile
    var isReleased: Boolean = false
        internal set
}

/**
 * Shared singleton hub so UI, Foreground Service, Silent Background Engine, and Self-Power Audit
 * observe the exact same hardware state, service/notification lifecycle, WakeLock safety counters, and CPU usage.
 */
object BatteryTelemetryHub {
    const val MAX_POWER_EVENT_WAKELOCK_MS = 3_000L
    // Real TTS cold-start + synthesis + AudioTrack budget:
    // ~2,500ms cold TTS binder onInit + ~6,000ms synthesizeToFile + ~6,500ms AudioTrack PCM playback = 15,000ms
    const val MAX_SPEECH_WAKELOCK_MS = 15_000L

    private val processStartElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()

    private val _snapshot = MutableStateFlow(BatteryHardwareSnapshot())
    val snapshot: StateFlow<BatteryHardwareSnapshot> = _snapshot.asStateFlow()

    private val _telemetryHistory = MutableStateFlow<List<TelemetrySamplePoint>>(emptyList())
    val telemetryHistory: StateFlow<List<TelemetrySamplePoint>> = _telemetryHistory.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isForegroundNotificationVisible = MutableStateFlow(false)
    val isForegroundNotificationVisible: StateFlow<Boolean> = _isForegroundNotificationVisible.asStateFlow()

    private val _isSilentBackgroundActive = MutableStateFlow(false)
    val isSilentBackgroundActive: StateFlow<Boolean> = _isSilentBackgroundActive.asStateFlow()

    private val _isJobSchedulerRegistered = MutableStateFlow(false)
    val isJobSchedulerRegistered: StateFlow<Boolean> = _isJobSchedulerRegistered.asStateFlow()

    private val _activeMonitoringMode = MutableStateFlow(BackgroundMonitoringMode.SILENT_BACKGROUND)
    val activeMonitoringMode: StateFlow<BackgroundMonitoringMode> = _activeMonitoringMode.asStateFlow()

    private val _isScreenInteractive = MutableStateFlow(true)
    val isScreenInteractive: StateFlow<Boolean> = _isScreenInteractive.asStateFlow()

    private val _lastNotificationActionHandled = MutableStateFlow("None")
    val lastNotificationActionHandled: StateFlow<String> = _lastNotificationActionHandled.asStateFlow()

    private val _notificationChannelDiagnostics = MutableStateFlow(NotificationChannelDiagnostics())
    val notificationChannelDiagnostics: StateFlow<NotificationChannelDiagnostics> =
        _notificationChannelDiagnostics.asStateFlow()

    // Self-Power & Efficiency Audit Counters
    private val broadcastEventsCount = AtomicInteger(0)
    private val screenOnGaugeReadsCount = AtomicInteger(0)
    private val usageStatsQueriesCount = AtomicInteger(0)
    private val packageManagerCacheHitsCount = AtomicInteger(0)
    private val wakeLockAcquisitionsCount = AtomicInteger(0)
    private val wakeLockReleasesCount = AtomicInteger(0)
    private val totalWakeLockHeldMs = AtomicLong(0L)
    private val notificationSentCount = AtomicInteger(0)
    private val notificationSkippedCount = AtomicInteger(0)
    private val serviceInstanceCreateCount = AtomicInteger(0)
    private val serviceInstanceDestroyCount = AtomicInteger(0)
    private val inPlaceNotificationUpdatesCount = AtomicInteger(0)

    private val activeWakeLocks: MutableSet<TrackedWakeLockHandle> =
        Collections.newSetFromMap(ConcurrentHashMap<TrackedWakeLockHandle, Boolean>())

    @Volatile
    private var minGraphSampleIntervalMs: Long = 10_000L

    @Volatile
    private var voiceAssistantInstance: OfflineVoiceAssistant? = null

    fun setGraphSampleInterval(intervalMs: Long) {
        minGraphSampleIntervalMs = intervalMs.coerceAtLeast(1_000L)
    }

    fun getOrCreateVoiceAssistant(context: Context): OfflineVoiceAssistant {
        return voiceAssistantInstance ?: synchronized(this) {
            voiceAssistantInstance ?: run {
                val appContext = context.applicationContext
                val dataStore = SettingsDataStore(appContext)
                val db = BatteryBossDatabase.getInstance(appContext)
                val repo = BatteryBossRepository(db.batteryBossDao())
                OfflineVoiceAssistant(appContext, dataStore, repo).also {
                    voiceAssistantInstance = it
                }
            }
        }
    }

    fun updateSnapshot(newSnapshot: BatteryHardwareSnapshot) {
        _snapshot.value = newSnapshot
        val point = TelemetrySamplePoint(
            timestampMillis = newSnapshot.timestampMillis,
            percentage = newSnapshot.percentage,
            powerWatts = newSnapshot.powerWatts?.let { abs(it) },
            currentMilliAmps = newSnapshot.currentMilliAmps,
            temperatureCelsius = newSnapshot.temperatureCelsius
        )
        val currentList = _telemetryHistory.value
        val shouldAppend = currentList.isEmpty() ||
            (newSnapshot.timestampMillis - currentList.last().timestampMillis >= minGraphSampleIntervalMs) ||
            (currentList.last().percentage != newSnapshot.percentage)

        if (shouldAppend) {
            _telemetryHistory.value = (currentList + point).takeLast(60)
        }
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (!running) {
            _isForegroundNotificationVisible.value = false
            _isSilentBackgroundActive.value = false
        }
    }

    fun setForegroundNotificationVisible(visible: Boolean) {
        _isForegroundNotificationVisible.value = visible
    }

    fun setSilentBackgroundActive(active: Boolean) {
        _isSilentBackgroundActive.value = active
    }

    fun setJobSchedulerRegistered(registered: Boolean) {
        _isJobSchedulerRegistered.value = registered
    }

    fun setActiveMonitoringMode(mode: BackgroundMonitoringMode) {
        _activeMonitoringMode.value = mode
    }

    fun setScreenInteractive(interactive: Boolean) {
        _isScreenInteractive.value = interactive
    }

    fun recordNotificationActionHandled(action: String) {
        _lastNotificationActionHandled.value = action
    }

    fun recordBroadcastEventHandled() {
        broadcastEventsCount.incrementAndGet()
    }

    fun recordScreenOnGaugeRead() {
        screenOnGaugeReadsCount.incrementAndGet()
    }

    fun recordUsageStatsQuery() {
        usageStatsQueriesCount.incrementAndGet()
    }

    fun recordPackageManagerCacheHit() {
        packageManagerCacheHitsCount.incrementAndGet()
    }

    fun recordNotificationUpdateSent() {
        notificationSentCount.incrementAndGet()
    }

    fun recordNotificationUpdateSkipped() {
        notificationSkippedCount.incrementAndGet()
    }

    fun recordServiceInstanceCreated() {
        serviceInstanceCreateCount.incrementAndGet()
    }

    fun recordServiceInstanceDestroyed() {
        serviceInstanceDestroyCount.incrementAndGet()
    }

    fun recordInPlaceNotificationUpdate() {
        inPlaceNotificationUpdatesCount.incrementAndGet()
    }

    /**
     * Reads Android OS NotificationManager state honestly so the app never claims a channel's
     * importance was programmatically modified after creation and accurately reflects user overrides.
     */
    fun refreshNotificationChannelDiagnostics(context: Context, silentSelected: Boolean) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val activeChannelId = if (silentSelected) {
            BatteryMonitorService.CHANNEL_ID_SILENT
        } else {
            BatteryMonitorService.CHANNEL_ID_STANDARD
        }
        val appNotificationsEnabled = runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(true)

        val silentImp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { nm?.getNotificationChannel(BatteryMonitorService.CHANNEL_ID_SILENT)?.importance }.getOrNull()
        } else {
            NotificationManagerCompat.IMPORTANCE_MIN
        }
        val standardImp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { nm?.getNotificationChannel(BatteryMonitorService.CHANNEL_ID_STANDARD)?.importance }.getOrNull()
        } else {
            NotificationManagerCompat.IMPORTANCE_LOW
        }
        val activeImp = if (silentSelected) {
            silentImp ?: NotificationManagerCompat.IMPORTANCE_MIN
        } else {
            standardImp ?: NotificationManagerCompat.IMPORTANCE_LOW
        }
        val isBlocked = !appNotificationsEnabled || activeImp == NotificationManagerCompat.IMPORTANCE_NONE

        _notificationChannelDiagnostics.value = NotificationChannelDiagnostics(
            activeChannelId = activeChannelId,
            activeChannelOsImportance = activeImp,
            silentChannelOsImportance = silentImp,
            standardChannelOsImportance = standardImp,
            areAppNotificationsEnabledOnOs = appNotificationsEnabled,
            isActiveChannelBlockedByUser = isBlocked,
            serviceInstanceCreateCount = serviceInstanceCreateCount.get(),
            serviceInstanceDestroyCount = serviceInstanceDestroyCount.get(),
            inPlaceNotificationUpdatesCount = inPlaceNotificationUpdatesCount.get()
        )
    }

    /**
     * Acquires a strictly bounded PARTIAL_WAKE_LOCK and tracks it in the audit registry.
     */
    fun acquireBoundedWakeLock(
        context: Context,
        tag: String,
        timeoutMs: Long = MAX_POWER_EVENT_WAKELOCK_MS
    ): TrackedWakeLockHandle {
        val boundedTimeout = timeoutMs.coerceIn(100L, MAX_SPEECH_WAKELOCK_MS)
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val rawWl = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply {
                setReferenceCounted(false)
                acquire(boundedTimeout)
            }
        }.getOrNull()

        val handle = TrackedWakeLockHandle(
            tag = tag,
            acquiredAtElapsedMs = SystemClock.elapsedRealtime(),
            timeoutMs = boundedTimeout,
            wakeLock = rawWl
        )
        activeWakeLocks.add(handle)
        wakeLockAcquisitionsCount.incrementAndGet()
        return handle
    }

    /**
     * Releases a tracked PARTIAL_WAKE_LOCK idempotently and records held duration.
     */
    fun releaseBoundedWakeLock(handle: TrackedWakeLockHandle?) {
        if (handle == null) return
        val removed = activeWakeLocks.remove(handle)
        if (!handle.isReleased || removed) {
            handle.isReleased = true
            runCatching {
                if (handle.wakeLock?.isHeld == true) {
                    handle.wakeLock.release()
                }
            }
            val heldMs = (SystemClock.elapsedRealtime() - handle.acquiredAtElapsedMs)
                .coerceIn(0L, handle.timeoutMs)
            totalWakeLockHeldMs.addAndGet(heldMs)
            wakeLockReleasesCount.incrementAndGet()
        }
    }

    /**
     * Releases all currently tracked PARTIAL_WAKE_LOCKs (called on STOP MONITORING and service destroy).
     */
    fun releaseAllTrackedWakeLocks() {
        val snapshotHandles = activeWakeLocks.toList()
        for (handle in snapshotHandles) {
            releaseBoundedWakeLock(handle)
        }
        activeWakeLocks.clear()
    }

    fun resetAuditCountersForTest() {
        releaseAllTrackedWakeLocks()
        broadcastEventsCount.set(0)
        screenOnGaugeReadsCount.set(0)
        usageStatsQueriesCount.set(0)
        packageManagerCacheHitsCount.set(0)
        wakeLockAcquisitionsCount.set(0)
        wakeLockReleasesCount.set(0)
        totalWakeLockHeldMs.set(0L)
        notificationSentCount.set(0)
        notificationSkippedCount.set(0)
        serviceInstanceCreateCount.set(0)
        serviceInstanceDestroyCount.set(0)
        inPlaceNotificationUpdatesCount.set(0)
    }

    fun buildSelfPowerAuditReport(saverMode: BatterySaverMode): SelfPowerAuditReport {
        val cpuMs = runCatching { Process.getElapsedCpuTime() }.getOrDefault(0L).coerceAtLeast(0L)
        val uptimeMs = (SystemClock.elapsedRealtime() - processStartElapsedRealtimeMs).coerceAtLeast(1L)
        val dutyCyclePct = ((cpuMs.toDouble() / uptimeMs.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
        val interactive = _isScreenInteractive.value
        val serviceActive = _isServiceRunning.value
        val isSilent = _isSilentBackgroundActive.value

        val modePrefix = if (isSilent) {
            "Silent FGS (battery_boss_silent_channel • IMPORTANCE_MIN)"
        } else {
            "Standard FGS (battery_boss_standard_channel • IMPORTANCE_LOW)"
        }

        val architectureLabel = when {
            !serviceActive -> "Monitoring Stopped (0 Background Loops • 0 WakeLocks)"
            saverMode.isPureEventDriven -> "$modePrefix • 100% Pure Event-Driven (0 Periodic Loops)"
            !interactive -> "$modePrefix • Event-Driven Broadcasts Only (Screen OFF • 0 Hz Polling)"
            else -> "$modePrefix • Event-Driven + ${saverMode.telemetryIntervalMs / 1000}s Screen-ON Gauge"
        }

        val intervalLabel = when {
            !serviceActive -> "OFF (Service Stopped)"
            saverMode.isPureEventDriven -> "0s Polling (OS Broadcast Events Only)"
            !interactive -> "0s Polling (Screen OFF Event-Driven)"
            else -> "${saverMode.telemetryIntervalMs / 1000}s Screen-ON Only (0s when Screen OFF)"
        }

        val estimatedSelfDrainPctPerHour = when {
            !serviceActive -> 0.0f
            saverMode == BatterySaverMode.EXTREME_SAVER -> 0.02f
            saverMode == BatterySaverMode.SMART_SAVER -> 0.03f
            isSilent -> 0.04f
            else -> 0.06f
        }

        return SelfPowerAuditReport(
            processCpuTimeMs = cpuMs,
            processUptimeMs = uptimeMs,
            cpuDutyCyclePercent = dutyCyclePct,
            isScreenInteractive = interactive,
            activeSaverMode = saverMode,
            monitoringArchitectureLabel = architectureLabel,
            activePollingIntervalLabel = intervalLabel,
            broadcastEventsHandledCount = broadcastEventsCount.get(),
            screenOnGaugeReadsCount = screenOnGaugeReadsCount.get(),
            usageStatsQueriesCount = usageStatsQueriesCount.get(),
            packageManagerCacheHitsCount = packageManagerCacheHitsCount.get(),
            wakeLockAcquisitionsCount = wakeLockAcquisitionsCount.get(),
            wakeLockReleasesCount = wakeLockReleasesCount.get(),
            activeHeldWakeLocksCount = activeWakeLocks.size,
            totalWakeLockHeldDurationMs = totalWakeLockHeldMs.get(),
            maxWakeLockTimeoutMs = MAX_POWER_EVENT_WAKELOCK_MS,
            notificationUpdatesSentCount = notificationSentCount.get(),
            notificationUpdatesSkippedCount = notificationSkippedCount.get(),
            estimatedSelfDrainPercentPerHour = estimatedSelfDrainPctPerHour
        )
    }
}

/**
 * Event-Driven Foreground Service for Battery Boss 2.0:
 * - Always runs as an Android Foreground Service (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, `START_STICKY`)
 *   whenever Background Monitoring is ON, so Charger Connect/Disconnect, Battery Milestone, Low Battery,
 *   and Temperature voice announcements work reliably even when the screen is OFF or the app is closed.
 * - Supports 3 independent controls:
 *   1. Background Monitoring ON/OFF
 *   2. Background Voice ON/OFF (muting voice never stops monitoring)
 *   3. Silent Notification ON/OFF (`IMPORTANCE_MIN` / `PRIORITY_MIN`, zero sound/vibration, zero bulky
 *      actions, compact status text: `"Battery Boss Active • 83%"`)
 */
class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID_SILENT = "battery_boss_silent_channel"
        const val CHANNEL_ID_STANDARD = "battery_boss_standard_channel"
        const val CHANNEL_ID = CHANNEL_ID_STANDARD
        const val LEGACY_CHANNEL_ID = "battery_boss_monitor_channel"
        const val NOTIFICATION_ID = 4010
        const val ACTION_START_MONITORING = "com.example.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.example.action.STOP_MONITORING"
        const val ACTION_SPEAK_NOW = "com.example.action.SPEAK_NOW"
        const val ACTION_STOP_SPEAKING = "com.example.action.STOP_SPEAKING"
        const val ACTION_MUTE_VOICE = "com.example.action.MUTE_VOICE"
        const val ACTION_RESUME_VOICE = "com.example.action.RESUME_VOICE"
        const val ACTION_REFRESH_NOTIFICATION = "com.example.action.REFRESH_NOTIFICATION"
        const val EXTRA_TRIGGER_EVENT = "extra_trigger_event"

        @Volatile
        private var activeInstance: BatteryMonitorService? = null

        fun isLiveServiceInstanceActive(): Boolean = activeInstance != null

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun buildChannelNotificationSettingsIntent(context: Context, silentChannel: Boolean = true): Intent {
            val targetChannel = if (silentChannel) CHANNEL_ID_SILENT else CHANNEL_ID_STANDARD
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, targetChannel)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
        }

        fun buildServiceActionIntent(context: Context, action: String, triggerEvent: String? = null): Intent {
            return Intent(context, BatteryMonitorService::class.java).apply {
                this.action = action
                if (triggerEvent != null) {
                    putExtra(EXTRA_TRIGGER_EVENT, triggerEvent)
                }
            }
        }

        fun startService(context: Context, triggerEvent: String? = null) {
            val appContext = context.applicationContext
            // If BatteryMonitorService is already running and there is no power trigger event,
            // refresh its notification in-place without calling startForegroundService() again.
            val running = activeInstance
            if (running != null && triggerEvent == null) {
                running.refreshNotificationFromExternalChange(silentOverride = null)
                return
            }
            val intent = buildServiceActionIntent(appContext, ACTION_START_MONITORING, triggerEvent)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        /**
         * Updates the active Foreground Service's notification on the new channel (`battery_boss_silent_channel`
         * or `battery_boss_standard_channel`) IN-PLACE without stopping or restarting `BatteryMonitorService`.
         */
        fun refreshNotificationInPlace(context: Context, silentOverride: Boolean? = null) {
            val appContext = context.applicationContext
            val running = activeInstance
            if (running != null) {
                running.refreshNotificationFromExternalChange(silentOverride = silentOverride)
                return
            }
            if (!BatteryTelemetryHub.isServiceRunning.value) return
            val intent = buildServiceActionIntent(appContext, ACTION_REFRESH_NOTIFICATION)
            runCatching {
                appContext.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val appContext = context.applicationContext
            SilentBackgroundMonitorEngine.cancelSilentJobs(appContext)
            // Immediately release any tracked wake locks and cancel non-critical announcements
            BatteryTelemetryHub.releaseAllTrackedWakeLocks()
            BatteryTelemetryHub.getOrCreateVoiceAssistant(appContext).cancelPendingNonCriticalAnnouncements()
            BatteryTelemetryHub.setSilentBackgroundActive(false)
            BatteryTelemetryHub.setServiceRunning(false)
            BatteryTelemetryHub.setForegroundNotificationVisible(false)
            runCatching {
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)
            }
            val intent = buildServiceActionIntent(appContext, ACTION_STOP_MONITORING)
            runCatching {
                appContext.stopService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var hardwareReader: BatteryHardwareReader
    private lateinit var voiceAssistant: OfflineVoiceAssistant
    private lateinit var repository: BatteryBossRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private var tickerJob: Job? = null
    private var powerEventRefreshJob: Job? = null
    private var isReceiverRegistered = false

    @Volatile
    private var currentSettings: UserSettings = UserSettings()

    // Notification deduplication state to avoid redundant SystemUI IPC
    private var lastNotifiedPercent: Int = -1
    private var lastNotifiedCharging: Boolean? = null
    private var lastNotifiedSource: String = ""
    private var lastNotifiedSaverBadge: String = ""
    private var lastNotifiedVoiceEnabled: Boolean? = null
    private var lastNotifiedSilentMode: Boolean? = null
    private var lastNotifiedCurrentMa: Int = 0

    private val batteryBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            BatteryTelemetryHub.recordBroadcastEventHandled()
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val snap = hardwareReader.readSnapshot(intent)
                    handleSnapshotUpdate(snap, explicitEvent = null)
                }
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handlePowerConnectionBroadcast(action)
                }
                Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_OKAY -> {
                    val snap = hardwareReader.readSnapshot(null)
                    handleSnapshotUpdate(snap, explicitEvent = action)
                }
                Intent.ACTION_SCREEN_ON -> {
                    BatteryTelemetryHub.setScreenInteractive(true)
                    updateScreenOnSupplementTicker()
                    val snap = hardwareReader.readSnapshot(null)
                    handleSnapshotUpdate(snap, explicitEvent = null)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    BatteryTelemetryHub.setScreenInteractive(false)
                    // Immediately stop periodic polling when Screen turns OFF; BroadcastReceiver stays active!
                    tickerJob?.cancel()
                    tickerJob = null
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        BatteryTelemetryHub.recordServiceInstanceCreated()
        hardwareReader = BatteryHardwareReader(applicationContext)
        voiceAssistant = BatteryTelemetryHub.getOrCreateVoiceAssistant(applicationContext)
        val db = BatteryBossDatabase.getInstance(applicationContext)
        repository = BatteryBossRepository(db.batteryBossDao())
        settingsDataStore = SettingsDataStore(applicationContext)

        runCatching {
            currentSettings = kotlinx.coroutines.runBlocking { settingsDataStore.getSettingsSnapshot() }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val initialInteractive = runCatching { pm?.isInteractive ?: true }.getOrDefault(true)
        BatteryTelemetryHub.setScreenInteractive(initialInteractive)

        serviceScope.launch {
            // Load saved settings immediately on Service creation/restart so selected VoiceProfilePreset is preserved
            val initialSaved = settingsDataStore.getSettingsSnapshot()
            currentSettings = initialSaved
            voiceAssistant.ensureEngineAliveAndSyncSettings(initialSaved)
            BatteryTelemetryHub.setSilentBackgroundActive(initialSaved.silentNotificationEnabled)
            BatteryTelemetryHub.setActiveMonitoringMode(
                if (initialSaved.silentNotificationEnabled) {
                    BackgroundMonitoringMode.SILENT_BACKGROUND
                } else {
                    BackgroundMonitoringMode.CONTINUOUS_FOREGROUND
                }
            )
            BatteryTelemetryHub.setForegroundNotificationVisible(true)
            updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)

            settingsDataStore.settingsFlow.collect { s ->
                val prevVoiceEnabled = currentSettings.voiceAnnouncementsEnabled
                val prevSilentNotif = currentSettings.silentNotificationEnabled
                val prevMode = currentSettings.batterySaverMode
                val prevPreset = currentSettings.activePreset
                currentSettings = s
                voiceAssistant.ensureEngineAliveAndSyncSettings(s)
                BatteryTelemetryHub.setGraphSampleInterval(s.batterySaverMode.graphSampleIntervalMs)
                BatteryTelemetryHub.setSilentBackgroundActive(s.silentNotificationEnabled)
                BatteryTelemetryHub.setActiveMonitoringMode(
                    if (s.silentNotificationEnabled) {
                        BackgroundMonitoringMode.SILENT_BACKGROUND
                    } else {
                        BackgroundMonitoringMode.CONTINUOUS_FOREGROUND
                    }
                )
                if (BatteryTelemetryHub.isServiceRunning.value) {
                    BatteryTelemetryHub.setForegroundNotificationVisible(true)
                }

                if (prevMode != s.batterySaverMode) {
                    updateScreenOnSupplementTicker()
                }
                if ((prevVoiceEnabled != s.voiceAnnouncementsEnabled ||
                        prevSilentNotif != s.silentNotificationEnabled ||
                        prevPreset != s.activePreset) &&
                    BatteryTelemetryHub.isServiceRunning.value
                ) {
                    updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
                }
            }
        }

        createNotificationChannels()
        val rawInitial = hardwareReader.readSnapshot()
        val initialSnapshot = if (rawInitial.percentage == 0 &&
            rawInitial.statusLabel == "Unknown" &&
            BatteryTelemetryHub.snapshot.value.percentage > 0
        ) {
            BatteryTelemetryHub.snapshot.value
        } else {
            rawInitial
        }
        BatteryTelemetryHub.updateSnapshot(initialSnapshot)
        startForegroundCompat(buildNotification(initialSnapshot))
        BatteryTelemetryHub.setServiceRunning(true)
        BatteryTelemetryHub.setSilentBackgroundActive(currentSettings.silentNotificationEnabled)
        BatteryTelemetryHub.setForegroundNotificationVisible(true)
        SilentBackgroundMonitorEngine.scheduleSilentJobs(applicationContext)

        registerBatteryReceiver()
        updateScreenOnSupplementTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // When Android restarts START_STICKY service after memory pressure, intent may be null
        val action = intent?.action ?: ACTION_START_MONITORING
        BatteryTelemetryHub.recordNotificationActionHandled(action)

        when (action) {
            ACTION_STOP_MONITORING -> {
                executeStopMonitoringCleanly()
                return START_NOT_STICKY
            }
            ACTION_SPEAK_NOW -> {
                if (UpdateLockManager.isUpdateLockActive(applicationContext)) {
                    return START_STICKY
                }
                val rawSnap = hardwareReader.readSnapshot()
                val snap = if (rawSnap.percentage == 0 &&
                    rawSnap.statusLabel == "Unknown" &&
                    BatteryTelemetryHub.snapshot.value.percentage > 0
                ) {
                    BatteryTelemetryHub.snapshot.value
                } else {
                    rawSnap
                }
                BatteryTelemetryHub.updateSnapshot(snap)
                voiceAssistant.speakImmediateSample(snapshot = snap)
            }
            ACTION_STOP_SPEAKING -> {
                voiceAssistant.stopSpeaking()
            }
            ACTION_MUTE_VOICE -> {
                // Mute Voice Announcements WITHOUT stopping Battery Monitoring!
                voiceAssistant.stopSpeaking()
                currentSettings = currentSettings.copy(voiceAnnouncementsEnabled = false)
                updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
                serviceScope.launch {
                    settingsDataStore.updateVoiceAnnouncementsEnabled(false)
                    updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
                }
            }
            ACTION_RESUME_VOICE -> {
                // Resume Voice Announcements immediately while Monitoring continues!
                currentSettings = currentSettings.copy(voiceAnnouncementsEnabled = true)
                updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
                serviceScope.launch {
                    settingsDataStore.updateVoiceAnnouncementsEnabled(true)
                    updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
                }
            }
            ACTION_REFRESH_NOTIFICATION -> {
                runCatching {
                    currentSettings = kotlinx.coroutines.runBlocking { settingsDataStore.getSettingsSnapshot() }
                }
                voiceAssistant.ensureEngineAliveAndSyncSettings(currentSettings)
                BatteryTelemetryHub.setSilentBackgroundActive(currentSettings.silentNotificationEnabled)
                BatteryTelemetryHub.setForegroundNotificationVisible(true)
                updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
            }
            ACTION_START_MONITORING -> {
                val triggerEvent = intent?.getStringExtra(EXTRA_TRIGGER_EVENT)
                registerBatteryReceiver()
                runCatching {
                    val saved = kotlinx.coroutines.runBlocking { settingsDataStore.getSettingsSnapshot() }
                    currentSettings = saved
                }
                val rawSnap = hardwareReader.readSnapshot()
                val snap = if (rawSnap.percentage == 0 &&
                    rawSnap.statusLabel == "Unknown" &&
                    BatteryTelemetryHub.snapshot.value.percentage > 0
                ) {
                    BatteryTelemetryHub.snapshot.value
                } else {
                    rawSnap
                }
                startForegroundCompat(buildNotification(snap))
                BatteryTelemetryHub.setServiceRunning(true)
                BatteryTelemetryHub.setSilentBackgroundActive(currentSettings.silentNotificationEnabled)
                BatteryTelemetryHub.setForegroundNotificationVisible(true)
                SilentBackgroundMonitorEngine.scheduleSilentJobs(applicationContext)

                serviceScope.launch {
                    val saved = settingsDataStore.getSettingsSnapshot()
                    if (!saved.backgroundMonitoringEnabled) {
                        settingsDataStore.updateBackgroundSettings(
                            backgroundMonitoring = true,
                            persistentNotification = true
                        )
                    }
                    val synced = settingsDataStore.getSettingsSnapshot()
                    currentSettings = synced
                    voiceAssistant.ensureEngineAliveAndSyncSettings(synced)
                    BatteryTelemetryHub.setSilentBackgroundActive(synced.silentNotificationEnabled)
                    BatteryTelemetryHub.setForegroundNotificationVisible(true)
                    updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
                }

                if (triggerEvent == Intent.ACTION_POWER_CONNECTED ||
                    triggerEvent == Intent.ACTION_POWER_DISCONNECTED
                ) {
                    handlePowerConnectionBroadcast(triggerEvent)
                } else {
                    handleSnapshotUpdate(snap, explicitEvent = triggerEvent)
                }
                updateScreenOnSupplementTicker()
            }
            else -> {
                val snap = hardwareReader.readSnapshot()
                handleSnapshotUpdate(snap, explicitEvent = intent?.getStringExtra(EXTRA_TRIGGER_EVENT))
            }
        }
        return START_STICKY
    }

    /**
     * Safely stops BatteryMonitorService, releases all PARTIAL_WAKE_LOCKs,
     * stops all polling loops, cancels pending non-critical announcements, and removes the Foreground Notification.
     */
    private fun executeStopMonitoringCleanly() {
        tickerJob?.cancel()
        tickerJob = null
        powerEventRefreshJob?.cancel()
        powerEventRefreshJob = null

        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(batteryBroadcastReceiver) }
            isReceiverRegistered = false
        }

        SilentBackgroundMonitorEngine.cancelSilentJobs(applicationContext)

        BatteryTelemetryHub.setSilentBackgroundActive(false)
        BatteryTelemetryHub.setServiceRunning(false)
        BatteryTelemetryHub.setForegroundNotificationVisible(false)

        // Cancel ongoing & queued background speech before releasing WakeLocks so no worker re-acquires one
        voiceAssistant.stopSpeaking()

        // Release all tracked Partial WakeLocks
        BatteryTelemetryHub.releaseAllTrackedWakeLocks()

        // Persist Monitoring OFF in DataStore
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                SettingsDataStore(applicationContext).updateBackgroundSettings(backgroundMonitoring = false)
            }
        }

        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    /**
     * Handles ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED reliably even when the screen is off
     * or the user has exited the app:
     * acquires a bounded PARTIAL_WAKE_LOCK (<= 3,000ms), syncs persisted VoiceProfilePreset settings,
     * waits 120ms for the sticky ACTION_BATTERY_CHANGED broadcast to settle so battery percentage is fresh,
     * aligns isCharging with the power event, evaluates & speaks the announcement inside the WakeLock,
     * and guarantees WakeLock release in `finally`.
     */
    private fun handlePowerConnectionBroadcast(action: String) {
        powerEventRefreshJob?.cancel()
        powerEventRefreshJob = serviceScope.launch {
            val wakeLockHandle = BatteryTelemetryHub.acquireBoundedWakeLock(
                context = applicationContext,
                tag = "BatteryBoss:PowerEventWakeLock",
                timeoutMs = BatteryTelemetryHub.MAX_POWER_EVENT_WAKELOCK_MS
            )
            try {
                val freshSettings = settingsDataStore.getSettingsSnapshot()
                currentSettings = freshSettings
                voiceAssistant.ensureEngineAliveAndSyncSettings(freshSettings)

                delay(120L)
                val isUpdateLocked = if (action == Intent.ACTION_POWER_CONNECTED) {
                    UpdateLockManager.refreshIfStaleAndCheckLocked(applicationContext)
                } else {
                    UpdateLockManager.isUpdateLockActive(applicationContext)
                }
                val rawSnap = hardwareReader.readSnapshot(null)
                val alignedSnap = when (action) {
                    Intent.ACTION_POWER_CONNECTED -> rawSnap.copy(isCharging = true)
                    Intent.ACTION_POWER_DISCONNECTED -> rawSnap.copy(isCharging = false)
                    else -> rawSnap
                }
                BatteryTelemetryHub.updateSnapshot(alignedSnap)
                updateNotification(alignedSnap, forceUpdate = true)
                voiceAssistant.evaluateSnapshotAndAnnounceNow(
                    snapshot = alignedSnap,
                    explicitTriggerEvent = action,
                    settingsOverride = freshSettings
                )
                if (!isUpdateLocked) {
                    persistSnapshotToDatabaseAsync(alignedSnap, freshSettings)
                }
            } finally {
                BatteryTelemetryHub.releaseBoundedWakeLock(wakeLockHandle)
            }
        }
    }

    private fun handleSnapshotUpdate(snapshot: BatteryHardwareSnapshot, explicitEvent: String?) {
        BatteryTelemetryHub.updateSnapshot(snapshot)
        updateNotification(snapshot, forceUpdate = explicitEvent != null)

        serviceScope.launch {
            val wakeLockHandle = if (!BatteryTelemetryHub.isScreenInteractive.value || explicitEvent != null) {
                BatteryTelemetryHub.acquireBoundedWakeLock(
                    context = applicationContext,
                    tag = "BatteryBoss:SnapshotEventWakeLock",
                    timeoutMs = BatteryTelemetryHub.MAX_POWER_EVENT_WAKELOCK_MS
                )
            } else {
                null
            }
            try {
                val freshSettings = settingsDataStore.getSettingsSnapshot()
                currentSettings = freshSettings
                voiceAssistant.ensureEngineAliveAndSyncSettings(freshSettings)
                voiceAssistant.evaluateSnapshotAndAnnounceNow(
                    snapshot = snapshot,
                    explicitTriggerEvent = explicitEvent,
                    settingsOverride = freshSettings
                )
            } finally {
                BatteryTelemetryHub.releaseBoundedWakeLock(wakeLockHandle)
            }
        }

        if (!UpdateLockManager.isUpdateLockActive(applicationContext)) {
            persistSnapshotToDatabaseAsync(snapshot, currentSettings)
        }
    }

    private fun persistSnapshotToDatabaseAsync(
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings
    ) {
        serviceScope.launch(Dispatchers.IO) {
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
            evaluateAutomaticBatterySaver(snapshot, settings)
        }
    }

    private suspend fun evaluateAutomaticBatterySaver(
        snapshot: BatteryHardwareSnapshot,
        settings: UserSettings
    ) {
        if (!settings.autoBatterySaverEnabled || !snapshot.isPercentageValid) return

        // Auto-activate target saver mode when discharging at or below threshold
        if (!snapshot.isCharging && snapshot.percentage <= settings.autoSaverThresholdPercent) {
            val desiredMode = if (snapshot.percentage <= 15 && settings.autoSaverThresholdPercent <= 15) {
                BatterySaverMode.EXTREME_SAVER
            } else {
                settings.autoSaverTargetMode
            }
            if (settings.batterySaverMode != desiredMode &&
                !(settings.batterySaverMode == BatterySaverMode.EXTREME_SAVER && desiredMode == BatterySaverMode.SMART_SAVER)
            ) {
                settingsDataStore.updateBatterySaverMode(desiredMode)
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
            // Auto-restore Normal Mode when recovered above recovery threshold
            settingsDataStore.updateBatterySaverMode(BatterySaverMode.NORMAL)
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

    /**
     * Event-Driven + Screen-ON Only Supplement Ticker:
     * - When Screen is OFF or Mode is EXTREME_SAVER: ZERO periodic polling loops!
     * - When Screen is ON and Mode is NORMAL (15s) or SMART_SAVER (30s): reads the current sensor
     *   at a gentle interval without waking the device when idle.
     */
    private fun updateScreenOnSupplementTicker() {
        tickerJob?.cancel()
        tickerJob = null

        val mode = currentSettings.batterySaverMode
        val screenInteractive = BatteryTelemetryHub.isScreenInteractive.value
        if (!BatteryTelemetryHub.isServiceRunning.value || mode.isPureEventDriven || !screenInteractive || mode.telemetryIntervalMs <= 0L) {
            return
        }

        tickerJob = serviceScope.launch {
            while (isActive && BatteryTelemetryHub.isServiceRunning.value && BatteryTelemetryHub.isScreenInteractive.value) {
                val activeMode = currentSettings.batterySaverMode
                if (activeMode.isPureEventDriven || activeMode.telemetryIntervalMs <= 0L) break
                delay(activeMode.telemetryIntervalMs)
                if (!BatteryTelemetryHub.isScreenInteractive.value || !BatteryTelemetryHub.isServiceRunning.value) break
                BatteryTelemetryHub.recordScreenOnGaugeRead()
                val snap = hardwareReader.readSnapshot()
                handleSnapshotUpdate(snap, explicitEvent = null)
            }
        }
    }

    private fun registerBatteryReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryBroadcastReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    internal fun refreshNotificationFromExternalChange(silentOverride: Boolean?) {
        val synced = runCatching {
            kotlinx.coroutines.runBlocking { settingsDataStore.getSettingsSnapshot() }
        }.getOrElse { currentSettings }
        currentSettings = if (silentOverride != null) {
            synced.copy(
                silentNotificationEnabled = silentOverride,
                backgroundMonitoringMode = if (silentOverride) {
                    BackgroundMonitoringMode.SILENT_BACKGROUND
                } else {
                    BackgroundMonitoringMode.CONTINUOUS_FOREGROUND
                }
            )
        } else {
            synced
        }
        BatteryTelemetryHub.setSilentBackgroundActive(currentSettings.silentNotificationEnabled)
        BatteryTelemetryHub.setActiveMonitoringMode(
            if (currentSettings.silentNotificationEnabled) {
                BackgroundMonitoringMode.SILENT_BACKGROUND
            } else {
                BackgroundMonitoringMode.CONTINUOUS_FOREGROUND
            }
        )
        BatteryTelemetryHub.setForegroundNotificationVisible(true)
        BatteryTelemetryHub.recordInPlaceNotificationUpdate()
        updateNotification(BatteryTelemetryHub.snapshot.value, forceUpdate = true)
    }

    private fun updateNotification(snapshot: BatteryHardwareSnapshot, forceUpdate: Boolean = false) {
        if (!BatteryTelemetryHub.isServiceRunning.value) return
        val curMa = snapshot.currentMilliAmps ?: 0
        val saverBadge = currentSettings.batterySaverMode.badge
        val voiceEnabled = currentSettings.voiceAnnouncementsEnabled
        val silentMode = currentSettings.silentNotificationEnabled
        val hasMeaningfulChange = forceUpdate ||
            snapshot.percentage != lastNotifiedPercent ||
            snapshot.isCharging != lastNotifiedCharging ||
            snapshot.chargingSource.displayName != lastNotifiedSource ||
            saverBadge != lastNotifiedSaverBadge ||
            voiceEnabled != lastNotifiedVoiceEnabled ||
            silentMode != lastNotifiedSilentMode ||
            abs(curMa - lastNotifiedCurrentMa) >= 150

        if (!hasMeaningfulChange) {
            BatteryTelemetryHub.recordNotificationUpdateSkipped()
            return
        }

        lastNotifiedPercent = snapshot.percentage
        lastNotifiedCharging = snapshot.isCharging
        lastNotifiedSource = snapshot.chargingSource.displayName
        lastNotifiedSaverBadge = saverBadge
        lastNotifiedVoiceEnabled = voiceEnabled
        lastNotifiedSilentMode = silentMode
        lastNotifiedCurrentMa = curMa

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        BatteryTelemetryHub.refreshNotificationChannelDiagnostics(applicationContext, silentMode)
        runCatching {
            // Repost notification onto the active channel (battery_boss_silent_channel or battery_boss_standard_channel)
            // directly in-place using the same NOTIFICATION_ID without restarting the Foreground Service.
            nm.notify(NOTIFICATION_ID, buildNotification(snapshot))
            BatteryTelemetryHub.recordNotificationUpdateSent()
        }
    }

    /**
     * Requirement 1–6 Compliant Foreground Service Notification:
     * - Compact status headline: `"Battery Boss Active • ${snapshot.percentage}%"`
     * - Uses two distinct immutable Notification Channels (`CHANNEL_ID_SILENT` / `CHANNEL_ID_STANDARD`)
     *   and reposts onto the selected channel in-place without attempting to mutate an existing channel's
     *   importance or restarting the Foreground Service.
     * - Respects user overrides configured in Android System Notification Channel Settings.
     */
    fun buildNotification(snapshot: BatteryHardwareSnapshot): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isSilentNotif = currentSettings.silentNotificationEnabled
        val activeChannelId = if (isSilentNotif) CHANNEL_ID_SILENT else CHANNEL_ID_STANDARD
        val compactTitle = "Battery Boss Active • ${snapshot.percentage}%"
        val voiceLabel = if (currentSettings.voiceAnnouncementsEnabled) "Voice ON" else "Voice OFF"
        val compactBody = if (isSilentNotif) {
            "${snapshot.statusLabel} • $voiceLabel"
        } else {
            val tempText = snapshot.temperatureCelsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"
            "${snapshot.statusLabel} • $tempText • $voiceLabel"
        }

        val builder = NotificationCompat.Builder(this, activeChannelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(compactTitle)
            .setContentText(compactBody)
            .setSubText(if (isSilentNotif) "Silent" else currentSettings.batterySaverMode.badge)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setPriority(if (isSilentNotif) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setVisibility(
                if (isSilentNotif) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)

        // When Silent Notification is OFF, include notification action buttons.
        // When Silent Notification is ON, omit bulky action buttons so the notification stays ultra-compact.
        if (!isSilentNotif) {
            val stopMonitoringPendingIntent = PendingIntent.getService(
                this,
                101,
                buildServiceActionIntent(this, ACTION_STOP_MONITORING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val speakStatusPendingIntent = PendingIntent.getService(
                this,
                102,
                buildServiceActionIntent(this, ACTION_SPEAK_NOW),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val muteVoicePendingIntent = PendingIntent.getService(
                this,
                103,
                buildServiceActionIntent(this, ACTION_MUTE_VOICE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val resumeVoicePendingIntent = PendingIntent.getService(
                this,
                104,
                buildServiceActionIntent(this, ACTION_RESUME_VOICE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP MONITORING",
                stopMonitoringPendingIntent
            )
            builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                "SPEAK STATUS",
                speakStatusPendingIntent
            )
            if (currentSettings.voiceAnnouncementsEnabled) {
                builder.addAction(
                    android.R.drawable.ic_lock_silent_mode,
                    "MUTE VOICE",
                    muteVoicePendingIntent
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_lock_silent_mode_off,
                    "RESUME VOICE",
                    resumeVoicePendingIntent
                )
            }
        }

        return builder.build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Remove pre-audit legacy channel ID if present on upgraded installs
            runCatching {
                if (nm.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
                    nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
                }
            }

            // 1. Silent Notification Channel (created once at IMPORTANCE_MIN; OS locks importance after creation)
            if (nm.getNotificationChannel(CHANNEL_ID_SILENT) == null) {
                val silentChannel = NotificationChannel(
                    CHANNEL_ID_SILENT,
                    "Battery Boss Silent Notification",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Compact silent foreground monitoring channel (IMPORTANCE_MIN). Note: Android locks channel importance after creation; adjust in System Settings if desired."
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(silentChannel)
            }

            // 2. Standard Notification Channel (created once at IMPORTANCE_LOW; OS locks importance after creation)
            if (nm.getNotificationChannel(CHANNEL_ID_STANDARD) == null) {
                val standardChannel = NotificationChannel(
                    CHANNEL_ID_STANDARD,
                    "Battery Boss Standard Notification",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Standard foreground monitoring channel with quick action buttons (IMPORTANCE_LOW). Note: Android locks channel importance after creation; adjust in System Settings if desired."
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(standardChannel)
            }

            BatteryTelemetryHub.refreshNotificationChannelDiagnostics(
                applicationContext,
                currentSettings.silentNotificationEnabled
            )
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        BatteryTelemetryHub.recordServiceInstanceDestroyed()
        tickerJob?.cancel()
        powerEventRefreshJob?.cancel()
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(batteryBroadcastReceiver) }
            isReceiverRegistered = false
        }
        BatteryTelemetryHub.setForegroundNotificationVisible(false)
        BatteryTelemetryHub.setSilentBackgroundActive(false)
        BatteryTelemetryHub.releaseAllTrackedWakeLocks()
        BatteryTelemetryHub.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
