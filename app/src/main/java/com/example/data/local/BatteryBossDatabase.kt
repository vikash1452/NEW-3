package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "charging_sessions")
data class ChargingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val startPercentage: Int,
    val endPercentage: Int,
    val chargingSource: String,
    val peakWatts: Float? = null,
    val averageWatts: Float? = null,
    val averageCurrentMa: Int? = null,
    val peakTemperatureCelsius: Float? = null,
    val isCompleted: Boolean = false
) {
    val durationMinutes: Int
        get() {
            val end = endTimeMillis ?: System.currentTimeMillis()
            return ((end - startTimeMillis).coerceAtLeast(0L) / 60_000L).toInt()
        }

    val percentageGained: Int
        get() = (endPercentage - startPercentage).coerceAtLeast(0)

    val percentPerHour: Float?
        get() {
            val end = endTimeMillis ?: System.currentTimeMillis()
            val elapsedHours = (end - startTimeMillis).coerceAtLeast(0L) / 3_600_000f
            return if (elapsedHours >= (1f / 60f) && percentageGained > 0) {
                percentageGained / elapsedHours
            } else {
                null
            }
        }
}

@Entity(tableName = "announcement_logs")
data class AnnouncementLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val eventType: String,
    val messageSpoken: String,
    val voiceProfileLabel: String,
    val batteryPercentage: Int,
    val temperatureCelsius: Float? = null,
    val wasMutedByQuietMode: Boolean = false
)

/**
 * Room Entity storing periodic Battery Usage & Discharge History records
 * (1h, 6h, 12h, 24h consumption graph + Daily, Weekly, Monthly history).
 */
@Entity(tableName = "battery_usage_history")
data class BatteryUsageHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val batteryPercentage: Int,
    val isCharging: Boolean,
    val dischargeRateMa: Int? = null,
    val dischargeRatePercentPerHour: Float? = null,
    val screenOnTimeMillis: Long? = null,
    val totalAppUsageTimeMillis: Long? = null,
    val verifiedConsumptionMah: Float? = null,
    val temperatureCelsius: Float? = null,
    val voltageVolts: Float? = null,
    val batterySaverMode: String = "NORMAL",
    val topAppPackage: String? = null,
    val topAppName: String? = null,
    val topAppForegroundMillis: Long? = null
)

/**
 * Room Entity storing App-wise Usage & Power History snapshots.
 */
@Entity(tableName = "app_usage_records")
data class AppUsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val dayBucketEpochDay: Long = System.currentTimeMillis() / 86_400_000L,
    val packageName: String,
    val appName: String,
    val foregroundTimeMillis: Long,
    val backgroundTimeMillis: Long? = null,
    val verifiedBatteryMah: Float? = null,
    val estimatedBatteryMah: Float? = null,
    val lastUsedMillis: Long,
    val isOptimizedByOs: Boolean = true
)

/**
 * Room Entity storing Automatic Battery Saver & App Restriction Audit Reports.
 * Explicitly distinguishes between apps actually restricted by OS/Battery Boss internal saver
 * vs. apps that were only opened in Android System Settings or flagged for user action.
 */
@Entity(tableName = "auto_management_reports")
data class AutoManagementReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val eventCategory: String,
    val targetPackageName: String? = null,
    val targetAppName: String? = null,
    val detectedBatteryUsageSummary: String,
    val alertIssued: String,
    val restrictionOrActionApplied: String,
    val actionSucceeded: Boolean,
    val saverModeState: String,
    val executionStatus: String = "OPENED_SYSTEM_SETTINGS_ONLY",
    val wasActuallyRestrictedByApp: Boolean = false,
    val openedSystemSettingsOnly: Boolean = false
)

@Dao
interface BatteryBossDao {
    @Query("SELECT * FROM charging_sessions ORDER BY startTimeMillis DESC LIMIT 50")
    fun observeChargingSessions(): Flow<List<ChargingSessionEntity>>

    @Query("SELECT * FROM charging_sessions WHERE isCompleted = 0 ORDER BY startTimeMillis DESC LIMIT 1")
    suspend fun getActiveChargingSession(): ChargingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChargingSession(session: ChargingSessionEntity): Long

    @Update
    suspend fun updateChargingSession(session: ChargingSessionEntity)

    @Query("DELETE FROM charging_sessions")
    suspend fun clearAllSessions()

    @Query("SELECT * FROM announcement_logs ORDER BY timestampMillis DESC LIMIT 100")
    fun observeAnnouncementLogs(): Flow<List<AnnouncementLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnouncementLog(log: AnnouncementLogEntity)

    @Query("DELETE FROM announcement_logs")
    suspend fun clearAllAnnouncementLogs()

    // Battery Usage History Queries
    @Query("SELECT * FROM battery_usage_history ORDER BY timestampMillis DESC LIMIT 500")
    fun observeBatteryUsageHistory(): Flow<List<BatteryUsageHistoryEntity>>

    @Query("SELECT * FROM battery_usage_history WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
    suspend fun getBatteryUsageHistorySince(sinceMillis: Long): List<BatteryUsageHistoryEntity>

    @Query("SELECT * FROM battery_usage_history ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun getLatestBatteryUsageHistory(): BatteryUsageHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatteryUsageHistory(entry: BatteryUsageHistoryEntity): Long

    @Query("DELETE FROM battery_usage_history WHERE id = :id")
    suspend fun deleteBatteryUsageHistoryById(id: Long)

    @Query("DELETE FROM battery_usage_history WHERE timestampMillis < :olderThanMillis")
    suspend fun deleteBatteryUsageHistoryOlderThan(olderThanMillis: Long)

    @Query("DELETE FROM battery_usage_history")
    suspend fun clearAllBatteryUsageHistory()

    // App-wise Usage Record Queries
    @Query("SELECT * FROM app_usage_records ORDER BY timestampMillis DESC LIMIT 300")
    fun observeAppUsageRecords(): Flow<List<AppUsageRecordEntity>>

    @Query("SELECT * FROM app_usage_records WHERE packageName = :packageName ORDER BY timestampMillis DESC LIMIT 50")
    suspend fun getAppUsageRecordsForPackage(packageName: String): List<AppUsageRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppUsageRecords(records: List<AppUsageRecordEntity>)

    @Query("DELETE FROM app_usage_records")
    suspend fun clearAllAppUsageRecords()

    // Auto Management Audit Report Queries
    @Query("SELECT * FROM auto_management_reports ORDER BY timestampMillis DESC LIMIT 150")
    fun observeAutoManagementReports(): Flow<List<AutoManagementReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutoManagementReport(report: AutoManagementReportEntity): Long

    @Query("DELETE FROM auto_management_reports")
    suspend fun clearAllAutoManagementReports()
}

@Database(
    entities = [
        ChargingSessionEntity::class,
        AnnouncementLogEntity::class,
        BatteryUsageHistoryEntity::class,
        AppUsageRecordEntity::class,
        AutoManagementReportEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class BatteryBossDatabase : RoomDatabase() {
    abstract fun batteryBossDao(): BatteryBossDao

    companion object {
        @Volatile
        private var INSTANCE: BatteryBossDatabase? = null

        fun getInstance(context: Context): BatteryBossDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryBossDatabase::class.java,
                    "battery_boss_telemetry.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class BatteryBossRepository(private val dao: BatteryBossDao) {
    val chargingSessions: Flow<List<ChargingSessionEntity>> = dao.observeChargingSessions()
    val announcementLogs: Flow<List<AnnouncementLogEntity>> = dao.observeAnnouncementLogs()
    val batteryUsageHistory: Flow<List<BatteryUsageHistoryEntity>> = dao.observeBatteryUsageHistory()
    val appUsageRecords: Flow<List<AppUsageRecordEntity>> = dao.observeAppUsageRecords()
    val autoManagementReports: Flow<List<AutoManagementReportEntity>> = dao.observeAutoManagementReports()

    suspend fun getActiveSession(): ChargingSessionEntity? = dao.getActiveChargingSession()

    suspend fun startOrUpdateChargingSession(
        percentage: Int,
        sourceName: String,
        watts: Float?,
        currentMa: Int?,
        tempCelsius: Float?
    ) {
        val cleanWatts = watts?.let { abs(it) }
        val cleanCurrent = currentMa?.let { abs(it) }
        val active = dao.getActiveChargingSession()
        if (active == null) {
            dao.insertChargingSession(
                ChargingSessionEntity(
                    startTimeMillis = System.currentTimeMillis(),
                    startPercentage = percentage,
                    endPercentage = percentage,
                    chargingSource = sourceName,
                    peakWatts = cleanWatts,
                    averageWatts = cleanWatts,
                    averageCurrentMa = cleanCurrent,
                    peakTemperatureCelsius = tempCelsius,
                    isCompleted = false
                )
            )
        } else {
            val updatedPeakWatts = when {
                cleanWatts != null && active.peakWatts != null -> maxOf(active.peakWatts, cleanWatts)
                cleanWatts != null -> cleanWatts
                else -> active.peakWatts
            }
            val updatedAvgWatts = when {
                cleanWatts != null && active.averageWatts != null ->
                    (active.averageWatts * 0.75f) + (cleanWatts * 0.25f)
                cleanWatts != null -> cleanWatts
                else -> active.averageWatts
            }
            val updatedPeakTemp = when {
                tempCelsius != null && active.peakTemperatureCelsius != null ->
                    maxOf(active.peakTemperatureCelsius, tempCelsius)
                tempCelsius != null -> tempCelsius
                else -> active.peakTemperatureCelsius
            }
            val updatedAvgCurrent = when {
                cleanCurrent != null && active.averageCurrentMa != null ->
                    (active.averageCurrentMa + cleanCurrent) / 2
                cleanCurrent != null -> cleanCurrent
                else -> active.averageCurrentMa
            }
            dao.updateChargingSession(
                active.copy(
                    endPercentage = percentage,
                    chargingSource = if (sourceName != "On Battery") sourceName else active.chargingSource,
                    peakWatts = updatedPeakWatts,
                    averageWatts = updatedAvgWatts,
                    averageCurrentMa = updatedAvgCurrent,
                    peakTemperatureCelsius = updatedPeakTemp
                )
            )
        }
    }

    suspend fun completeActiveChargingSession(finalPercentage: Int, tempCelsius: Float?) {
        val active = dao.getActiveChargingSession() ?: return
        val updatedPeakTemp = when {
            tempCelsius != null && active.peakTemperatureCelsius != null ->
                maxOf(active.peakTemperatureCelsius, tempCelsius)
            tempCelsius != null -> tempCelsius
            else -> active.peakTemperatureCelsius
        }
        dao.updateChargingSession(
            active.copy(
                endTimeMillis = System.currentTimeMillis(),
                endPercentage = finalPercentage,
                peakTemperatureCelsius = updatedPeakTemp,
                isCompleted = true
            )
        )
    }

    suspend fun logAnnouncement(
        eventType: String,
        messageSpoken: String,
        voiceProfileLabel: String,
        batteryPercentage: Int,
        temperatureCelsius: Float?,
        wasMutedByQuietMode: Boolean = false
    ) {
        dao.insertAnnouncementLog(
            AnnouncementLogEntity(
                eventType = eventType,
                messageSpoken = messageSpoken,
                voiceProfileLabel = voiceProfileLabel,
                batteryPercentage = batteryPercentage,
                temperatureCelsius = temperatureCelsius,
                wasMutedByQuietMode = wasMutedByQuietMode
            )
        )
    }

    suspend fun recordBatteryUsageSnapshot(
        entry: BatteryUsageHistoryEntity,
        minIntervalMillis: Long = 60_000L
    ): Boolean {
        val latest = dao.getLatestBatteryUsageHistory()
        val shouldInsert = latest == null ||
            (entry.timestampMillis - latest.timestampMillis >= minIntervalMillis) ||
            (latest.batteryPercentage != entry.batteryPercentage) ||
            (latest.batterySaverMode != entry.batterySaverMode)
        if (shouldInsert) {
            dao.insertBatteryUsageHistory(entry)
            return true
        }
        return false
    }

    suspend fun recordAppUsageSnapshots(records: List<AppUsageRecordEntity>) {
        if (records.isNotEmpty()) {
            dao.insertAppUsageRecords(records)
        }
    }

    suspend fun getAppUsageHistoryForPackage(packageName: String): List<AppUsageRecordEntity> {
        return dao.getAppUsageRecordsForPackage(packageName)
    }

    suspend fun logAutoManagementReport(
        eventCategory: String,
        targetPackageName: String? = null,
        targetAppName: String? = null,
        detectedBatteryUsageSummary: String,
        alertIssued: String,
        restrictionOrActionApplied: String,
        actionSucceeded: Boolean,
        saverModeState: String,
        executionStatus: String = "OPENED_SYSTEM_SETTINGS_ONLY",
        wasActuallyRestrictedByApp: Boolean = false,
        openedSystemSettingsOnly: Boolean = false
    ) {
        dao.insertAutoManagementReport(
            AutoManagementReportEntity(
                eventCategory = eventCategory,
                targetPackageName = targetPackageName,
                targetAppName = targetAppName,
                detectedBatteryUsageSummary = detectedBatteryUsageSummary,
                alertIssued = alertIssued,
                restrictionOrActionApplied = restrictionOrActionApplied,
                actionSucceeded = actionSucceeded,
                saverModeState = saverModeState,
                executionStatus = executionStatus,
                wasActuallyRestrictedByApp = wasActuallyRestrictedByApp,
                openedSystemSettingsOnly = openedSystemSettingsOnly
            )
        )
    }

    suspend fun deleteUsageHistoryItem(id: Long) {
        dao.deleteBatteryUsageHistoryById(id)
    }

    suspend fun deleteUsageHistoryOlderThan(olderThanMillis: Long) {
        dao.deleteBatteryUsageHistoryOlderThan(olderThanMillis)
    }

    suspend fun clearSmartTrackerHistory() {
        dao.clearAllBatteryUsageHistory()
        dao.clearAllAppUsageRecords()
        dao.clearAllAutoManagementReports()
    }

    suspend fun clearAllHistory() {
        dao.clearAllSessions()
        dao.clearAllAnnouncementLogs()
        dao.clearAllBatteryUsageHistory()
        dao.clearAllAppUsageRecords()
        dao.clearAllAutoManagementReports()
    }

    fun buildExportCsv(
        historyRecords: List<BatteryUsageHistoryEntity>,
        appRecords: List<AppUsageRecordEntity>,
        autoReports: List<AutoManagementReportEntity>
    ): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("# BATTERY BOSS 2.0 - SMART BATTERY TRACKER EXPORT")
        sb.appendLine(" Section,Timestamp,Battery%,Charging,DischargeRate(mA),Temp(C),SaverMode,TopApp/Package,Details")
        historyRecords.forEach { rec ->
            val ts = df.format(Date(rec.timestampMillis))
            val rate = rec.dischargeRateMa?.toString() ?: "N/A"
            val temp = rec.temperatureCelsius?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
            val topApp = rec.topAppName ?: "N/A"
            sb.appendLine("USAGE_HISTORY,\"$ts\",${rec.batteryPercentage},${rec.isCharging},$rate,$temp,${rec.batterySaverMode},\"$topApp\",\"ScreenOn=${rec.screenOnTimeMillis ?: "N/A"}ms\"")
        }
        appRecords.take(50).forEach { app ->
            val ts = df.format(Date(app.timestampMillis))
            val verMah = app.verifiedBatteryMah?.toString() ?: "N/A"
            val estMah = app.estimatedBatteryMah?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
            sb.appendLine("APP_RECORD,\"$ts\",N/A,N/A,N/A,N/A,N/A,\"${app.appName} (${app.packageName})\",\"Fg=${app.foregroundTimeMillis}ms,VerifiedMah=$verMah,EstMah=$estMah\"")
        }
        autoReports.take(50).forEach { rep ->
            val ts = df.format(Date(rep.timestampMillis))
            sb.appendLine("AUTO_REPORT,\"$ts\",N/A,N/A,N/A,N/A,${rep.saverModeState},\"${rep.targetAppName ?: "System"}\",\"${rep.eventCategory} [${rep.executionStatus}]: ${rep.restrictionOrActionApplied} (ActuallyRestricted=${rep.wasActuallyRestrictedByApp},SettingsOnly=${rep.openedSystemSettingsOnly})\"")
        }
        return sb.toString()
    }
}
