package com.example.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.example.model.BatteryHardwareSnapshot
import com.example.model.BatteryHealthReport
import com.example.model.ChargingSource
import com.example.model.ChargingSpeedTier
import com.example.model.DiagnosticMetric
import com.example.model.MetricConfidence
import com.example.model.OemOptimizationGuide
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Optional hardware property overrides for deterministic unit testing of sensor conversions,
 * sysfs registers, PowerProfile vs hardware register confidence, and invalid/inconsistent values.
 */
data class HardwarePropertyOverrides(
    val rawApiChargeCounterUah: Int? = null,
    val rawApiCurrentNowUa: Int? = null,
    val rawApiCurrentAvgUa: Int? = null,
    val rawSysfsCurrentNowUa: Int? = null,
    val rawSysfsCurrentAvgUa: Int? = null,
    val rawApiEnergyCounterNwh: Long? = null,
    val rawSysfsChargeFullUah: Int? = null,
    val derivedOrCachedFullCapacityMah: Int? = null,
    val rawSysfsChargeFullDesignUah: Int? = null,
    val powerProfileDesignCapacityMah: Double? = null,
    val disablePowerProfileFallback: Boolean = false,
    val rawSysfsCycleCount: Int? = null,
    val rawSysfsVoltageUv: Int? = null
)

/**
 * Deep Battery Hardware Diagnostics Reader for Battery Boss 2.0.
 *
 * Reads genuine hardware registers from Android's BatteryManager, sticky ACTION_BATTERY_CHANGED
 * broadcasts, and SELinux-permitted /sys/class/power_supply/battery/ nodes.
 *
 * CRITICAL GUARANTEES:
 * 1. Never clamps Capacity Retention to 10–110%; if Full Charge Capacity vs Design Capacity
 *    yields an inconsistent ratio (< 20% or > 110%), reports N/A and flags Inconsistent Hardware Data.
 * 2. Converts BATTERY_PROPERTY_CHARGE_COUNTER strictly from its documented µAh unit (/ 1000.0),
 *    without any arbitrary 50,000 guessing threshold.
 * 3. Converts BATTERY_PROPERTY_CURRENT_NOW and CURRENT_AVERAGE strictly from their documented µA
 *    unit (/ 1000.0) and keeps API (µA) vs sysfs (µA) sources and units explicitly separate.
 * 4. Never automatically assumes Charge Counter at 100% battery is Full Charge Capacity.
 *    Full Charge Capacity is only populated from an actual charge_full register or verified source.
 * 5. Never labels Android PowerProfile design capacity as a Direct Hardware Register (HIGH_DIRECT);
 *    marks it as MEDIUM_DERIVED with explicit static OS profile source metadata.
 * 6. Never gives Full Charge Capacity a HIGH_DIRECT badge if it comes from a derived/cached source.
 */
class BatteryHardwareReader(private val context: Context) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    companion object {
        private const val MIN_VALID_RETENTION_PERCENT = 20.0f
        private const val MAX_VALID_RETENTION_PERCENT = 110.0f

        /**
         * Converts `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER` from its documented Android API
         * unit of microampere-hours (µAh) to milliampere-hours (mAh).
         * Does NOT use any arbitrary 50,000 threshold.
         */
        fun convertChargeCounterUahToMah(rawChargeCounterUah: Int?): Int? {
            if (rawChargeCounterUah == null ||
                rawChargeCounterUah == Int.MIN_VALUE ||
                rawChargeCounterUah == Int.MAX_VALUE ||
                rawChargeCounterUah <= 0
            ) {
                return null
            }
            val mah = (rawChargeCounterUah / 1000.0).roundToInt()
            return if (mah in 1..100_000) mah else null
        }

        /**
         * Converts `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` or `BATTERY_PROPERTY_CURRENT_AVERAGE`
         * from its documented Android API unit of microamperes (µA) to milliamperes (mA).
         */
        fun convertApiCurrentMicroAmpsToMilliAmps(rawApiMicroAmps: Int?): Int? {
            if (rawApiMicroAmps == null ||
                rawApiMicroAmps == Int.MIN_VALUE ||
                rawApiMicroAmps == Int.MAX_VALUE ||
                rawApiMicroAmps == 0
            ) {
                return null
            }
            val ma = (rawApiMicroAmps / 1000.0).roundToInt()
            return if (ma != 0 && abs(ma) <= 50_000) ma else null
        }

        /**
         * Converts Linux kernel `/sys/class/power_supply/battery/current_now` or `current_avg`
         * from its documented kernel power_supply ABI unit of microamperes (µA) to milliamperes (mA).
         */
        fun convertSysfsCurrentMicroAmpsToMilliAmps(rawSysfsMicroAmps: Int?): Int? {
            if (rawSysfsMicroAmps == null ||
                rawSysfsMicroAmps == Int.MIN_VALUE ||
                rawSysfsMicroAmps == Int.MAX_VALUE ||
                rawSysfsMicroAmps == 0
            ) {
                return null
            }
            val ma = (rawSysfsMicroAmps / 1000.0).roundToInt()
            return if (ma != 0 && abs(ma) <= 50_000) ma else null
        }

        /**
         * Converts kernel sysfs `charge_full` or `charge_full_design` from documented microampere-hours (µAh)
         * to milliampere-hours (mAh).
         */
        fun convertSysfsCapacityUahToMah(rawSysfsCapacityUah: Int?): Int? {
            if (rawSysfsCapacityUah == null ||
                rawSysfsCapacityUah == Int.MIN_VALUE ||
                rawSysfsCapacityUah == Int.MAX_VALUE ||
                rawSysfsCapacityUah <= 0
            ) {
                return null
            }
            val mah = (rawSysfsCapacityUah / 1000.0).roundToInt()
            return if (mah in 100..100_000) mah else null
        }

        /**
         * Evaluates Capacity Retention without clamping to 10–110%.
         * Returns `(measuredPercentOrNull, isInconsistent, rawRatioOrNull)`.
         */
        fun evaluateCapacityRetention(
            fullChargeCapacityMah: Int?,
            designCapacityMah: Int?
        ): Triple<Float?, Boolean, Float?> {
            if (fullChargeCapacityMah == null || designCapacityMah == null) {
                return Triple(null, false, null)
            }
            if (fullChargeCapacityMah <= 0 || designCapacityMah <= 0) {
                return Triple(null, true, null)
            }
            val rawRatioPercent = (fullChargeCapacityMah.toFloat() / designCapacityMah.toFloat()) * 100f
            return if (rawRatioPercent in MIN_VALID_RETENTION_PERCENT..MAX_VALID_RETENTION_PERCENT) {
                Triple(rawRatioPercent, false, rawRatioPercent)
            } else {
                Triple(null, true, rawRatioPercent)
            }
        }
    }

    fun readSnapshot(
        batteryIntent: Intent? = null,
        overrides: HardwarePropertyOverrides? = null
    ): BatteryHardwareSnapshot {
        val intent = batteryIntent ?: context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        if (intent == null && overrides == null) {
            return BatteryHardwareSnapshot(
                percentage = 0,
                isPercentageValid = false,
                statusLabel = "N/A"
            )
        }

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val propCapacity = if (overrides == null) {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } else {
            -1
        }

        val (percentage, isPercentageValid) = when {
            level >= 0 && scale > 0 && level <= scale -> {
                val computed = ((level.toFloat() / scale.toFloat()) * 100f).roundToInt()
                if (computed in 0..100) computed to true else 0 to false
            }
            propCapacity in 0..100 -> propCapacity to true
            else -> 0 to false
        }

        val statusInt = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val pluggedInt = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargingSource = mapChargingSource(pluggedInt)

        val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            (pluggedInt != 0 && statusInt == BatteryManager.BATTERY_STATUS_FULL) ||
            (pluggedInt != 0 && statusInt == BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isFull = statusInt == BatteryManager.BATTERY_STATUS_FULL || (isPercentageValid && percentage == 100 && pluggedInt != 0)

        val statusLabel = when {
            isFull -> "Fully Charged"
            statusInt == BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            statusInt == BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            statusInt == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging (Plugged)"
            pluggedInt != 0 -> "Connected (${chargingSource.displayName})"
            else -> "Discharging"
        }

        // 1. Voltage (EXTRA_VOLTAGE is reported in mV, or sysfs voltage_now in µV)
        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val voltageMillivolts: Int? = when {
            rawVoltage in 2000..20000 -> rawVoltage
            rawVoltage in 2..20 -> rawVoltage * 1000
            rawVoltage <= 0 -> {
                val sysfsUv = overrides?.rawSysfsVoltageUv
                    ?: readSysfsInt("/sys/class/power_supply/battery/voltage_now")
                if (sysfsUv != null && sysfsUv > 0) {
                    val mv = (sysfsUv / 1000.0).roundToInt()
                    if (mv in 2000..20000) mv else null
                } else {
                    null
                }
            }
            else -> null
        }
        val voltageVolts: Float? = voltageMillivolts?.let { it / 1000f }

        // 2. Temperature (EXTRA_TEMPERATURE is reported in tenths of °C)
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temperatureCelsius: Float? = if (rawTemp != Int.MIN_VALUE && rawTemp in -400..1200 && rawTemp != 0) {
            rawTemp / 10f
        } else {
            null
        }

        // 3. Instant & Average Battery Current (Explicitly separated API µA vs sysfs µA)
        val rawApiCurrentNowUa = overrides?.rawApiCurrentNowUa
            ?: if (overrides == null) batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) else null
        val apiCurrentNowMa = convertApiCurrentMicroAmpsToMilliAmps(rawApiCurrentNowUa)

        val rawSysfsCurrentNowUa = overrides?.rawSysfsCurrentNowUa
            ?: if (overrides == null && apiCurrentNowMa == null) {
                readSysfsInt("/sys/class/power_supply/battery/current_now")
            } else {
                null
            }
        val sysfsCurrentNowMa = convertSysfsCurrentMicroAmpsToMilliAmps(rawSysfsCurrentNowUa)

        val currentMilliAmps: Int? = apiCurrentNowMa ?: sysfsCurrentNowMa
        val currentSourceApi: String = when {
            apiCurrentNowMa != null -> "BatteryManager.BATTERY_PROPERTY_CURRENT_NOW (API µA → mA)"
            sysfsCurrentNowMa != null -> "/sys/class/power_supply/battery/current_now (sysfs µA → mA)"
            else -> "N/A — Current sensor not exposed"
        }

        val rawApiCurrentAvgUa = overrides?.rawApiCurrentAvgUa
            ?: if (overrides == null) batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) else null
        val apiCurrentAvgMa = convertApiCurrentMicroAmpsToMilliAmps(rawApiCurrentAvgUa)

        val rawSysfsCurrentAvgUa = overrides?.rawSysfsCurrentAvgUa
            ?: if (overrides == null && apiCurrentAvgMa == null) {
                readSysfsInt("/sys/class/power_supply/battery/current_avg")
            } else {
                null
            }
        val sysfsCurrentAvgMa = convertSysfsCurrentMicroAmpsToMilliAmps(rawSysfsCurrentAvgUa)

        val averageCurrentMilliAmps: Int? = apiCurrentAvgMa ?: sysfsCurrentAvgMa
        val averageCurrentSourceApi: String = when {
            apiCurrentAvgMa != null -> "BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE (API µA → mA)"
            sysfsCurrentAvgMa != null -> "/sys/class/power_supply/battery/current_avg (sysfs µA → mA)"
            else -> "N/A — Average current sensor not exposed"
        }

        // 4. Measured Battery-Side Power (W = V_cell * I_cell)
        val powerWatts: Float? = if (voltageVolts != null && currentMilliAmps != null && currentMilliAmps != 0) {
            val amps = abs(currentMilliAmps) / 1000f
            voltageVolts * amps
        } else {
            null
        }

        val averagePowerWatts: Float? = if (voltageVolts != null && averageCurrentMilliAmps != null && averageCurrentMilliAmps != 0) {
            val amps = abs(averageCurrentMilliAmps) / 1000f
            voltageVolts * amps
        } else {
            null
        }

        // 5. Charge Counter (Remaining Capacity in mAh converted from documented µAh unit)
        val rawChargeCounterUah = overrides?.rawApiChargeCounterUah
            ?: if (overrides == null) batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) else null
        val chargeCounterMah: Int? = convertChargeCounterUahToMah(rawChargeCounterUah)

        // 6. Energy Counter (BATTERY_PROPERTY_ENERGY_COUNTER in nanowatt-hours -> Wh)
        val rawEnergyNwh = overrides?.rawApiEnergyCounterNwh
            ?: if (overrides == null) batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) else null
        val energyCounterWh: Float? = if (rawEnergyNwh != null && rawEnergyNwh != Long.MIN_VALUE && rawEnergyNwh > 0L) {
            val wh = (rawEnergyNwh.toDouble() / 1_000_000_000.0).toFloat()
            if (wh in 0.01f..1000.0f) wh else null
        } else {
            null
        }

        // 7. Cycle Count (Intent extra "android.os.extra.CYCLE_COUNT" on Android 14+ or sysfs)
        val intentCycleCount = intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
        val sysfsCycleCount = overrides?.rawSysfsCycleCount
            ?: if (overrides == null) readSysfsInt("/sys/class/power_supply/battery/cycle_count") else null
        val cycleCount: Int? = when {
            intentCycleCount in 1..50_000 -> intentCycleCount
            sysfsCycleCount != null && sysfsCycleCount in 1..50_000 -> sysfsCycleCount
            else -> null
        }
        val cycleCountSource = when {
            intentCycleCount in 1..50_000 -> "BatteryManager.EXTRA_CYCLE_COUNT (Android 14+)"
            sysfsCycleCount != null && sysfsCycleCount in 1..50_000 -> "/sys/class/power_supply/battery/cycle_count"
            else -> "Not exposed by OEM kernel / Android API"
        }

        // 8. Design Capacity & Full Charge Capacity (with accurate hardware vs OS profile confidence)
        // Note: Charge Counter at 100% is NEVER automatically treated as Full Charge Capacity.
        val (designCapacityMah, designCapSource, designCapConfidence) = readDesignCapacityWithSource(overrides)
        val (fullChargeCapacityMah, fullCapSource, fullCapConfidence) = readFullChargeCapacityWithSource(overrides)

        // 9. Charging Speed Classification & %/hr Rate
        val chargingSpeedTier = classifyChargingSpeed(isCharging, isFull, powerWatts)
        val percentPerHourRate: Float? = computePercentPerHourRate(
            currentMilliAmps = currentMilliAmps,
            referenceCapacityMah = fullChargeCapacityMah ?: designCapacityMah
        )

        // 10. Estimated Time to Full
        val estimatedMinutesToFull = computeReliableChargeTimeMinutes(
            isCharging = isCharging,
            isFull = isFull,
            percentage = percentage,
            chargeCounterMah = chargeCounterMah,
            fullOrDesignCapacityMah = fullChargeCapacityMah ?: designCapacityMah,
            currentMilliAmps = currentMilliAmps
        )

        val healthInt = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthLabel = mapHealthLabel(healthInt)

        val rawTech = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.trim()
        val technology: String? = if (!rawTech.isNullOrEmpty()) rawTech else null
        val isPresent = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true

        val healthReport = buildBatteryHealthReport(
            healthLabel = healthLabel,
            temperatureCelsius = temperatureCelsius,
            cycleCount = cycleCount,
            cycleCountSource = cycleCountSource,
            designCapacityMah = designCapacityMah,
            designCapSource = designCapSource,
            designCapConfidence = designCapConfidence,
            fullChargeCapacityMah = fullChargeCapacityMah,
            fullCapSource = fullCapSource,
            fullCapConfidence = fullCapConfidence,
            chargeCounterMah = chargeCounterMah,
            energyCounterWh = energyCounterWh
        )

        val diagnosticMetrics = buildDiagnosticMetricsList(
            percentage = percentage,
            isPercentageValid = isPercentageValid,
            statusLabel = statusLabel,
            healthLabel = healthLabel,
            voltageVolts = voltageVolts,
            voltageMillivolts = voltageMillivolts,
            currentMilliAmps = currentMilliAmps,
            currentSourceApi = currentSourceApi,
            averageCurrentMilliAmps = averageCurrentMilliAmps,
            averageCurrentSourceApi = averageCurrentSourceApi,
            powerWatts = powerWatts,
            temperatureCelsius = temperatureCelsius,
            technology = technology,
            chargingSource = chargingSource,
            chargeCounterMah = chargeCounterMah,
            cycleCount = cycleCount,
            cycleCountSource = cycleCountSource,
            designCapacityMah = designCapacityMah,
            designCapSource = designCapSource,
            designCapConfidence = designCapConfidence,
            fullChargeCapacityMah = fullChargeCapacityMah,
            fullCapSource = fullCapSource,
            fullCapConfidence = fullCapConfidence,
            energyCounterWh = energyCounterWh,
            estimatedMinutesToFull = estimatedMinutesToFull,
            chargingSpeedTier = chargingSpeedTier
        )

        return BatteryHardwareSnapshot(
            percentage = percentage,
            isPercentageValid = isPercentageValid,
            isCharging = isCharging,
            isFull = isFull,
            statusLabel = statusLabel,
            chargingSource = chargingSource,
            voltageVolts = voltageVolts,
            voltageMillivolts = voltageMillivolts,
            currentMilliAmps = currentMilliAmps,
            currentSourceApi = currentSourceApi,
            averageCurrentMilliAmps = averageCurrentMilliAmps,
            averageCurrentSourceApi = averageCurrentSourceApi,
            powerWatts = powerWatts,
            averagePowerWatts = averagePowerWatts,
            temperatureCelsius = temperatureCelsius,
            healthLabel = healthLabel,
            technology = technology,
            chargeCounterMah = chargeCounterMah,
            fullChargeCapacityMah = fullChargeCapacityMah,
            designCapacityMah = designCapacityMah,
            cycleCount = cycleCount,
            energyCounterWh = energyCounterWh,
            estimatedMinutesToFull = estimatedMinutesToFull,
            chargingSpeedTier = chargingSpeedTier,
            percentPerHourRate = percentPerHourRate,
            isBatteryPresent = isPresent,
            healthReport = healthReport,
            diagnosticMetrics = diagnosticMetrics,
            timestampMillis = System.currentTimeMillis()
        )
    }

    /**
     * Reads Design Capacity with accurate hardware vs static OS PowerProfile confidence.
     * - `/sys/class/power_supply/battery/charge_full_design` (µAh -> mAh) -> `MetricConfidence.HIGH_DIRECT`
     * - `com.android.internal.os.PowerProfile` (Static OEM XML config) -> `MetricConfidence.MEDIUM_DERIVED`
     * - Unavailable -> `MetricConfidence.UNAVAILABLE`
     */
    private fun readDesignCapacityWithSource(
        overrides: HardwarePropertyOverrides?
    ): Triple<Int?, String, MetricConfidence> {
        // 1. Check sysfs charge_full_design hardware register (documented in µAh)
        val rawSysfsDesignUah = overrides?.rawSysfsChargeFullDesignUah
            ?: if (overrides == null) readSysfsInt("/sys/class/power_supply/battery/charge_full_design") else null
        val sysfsDesignMah = convertSysfsCapacityUahToMah(rawSysfsDesignUah)
        if (sysfsDesignMah != null) {
            return Triple(
                sysfsDesignMah,
                "/sys/class/power_supply/battery/charge_full_design (Hardware Register µAh → mAh)",
                MetricConfidence.HIGH_DIRECT
            )
        }

        // 2. Check Android OS PowerProfile (Static OEM framework XML — NOT a direct hardware register)
        if (overrides?.disablePowerProfileFallback != true) {
            val profileCapacity: Double? = overrides?.powerProfileDesignCapacityMah ?: runCatching {
                val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
                val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
                powerProfileClass.getMethod("getBatteryCapacity").invoke(powerProfile) as? Double
            }.getOrNull()

            if (profileCapacity != null && profileCapacity > 500.0 && profileCapacity <= 100_000.0) {
                return Triple(
                    profileCapacity.roundToInt(),
                    "Android OS PowerProfile (Static OEM Framework Config — Not a Direct Hardware Register)",
                    MetricConfidence.MEDIUM_DERIVED
                )
            }
        }

        return Triple(
            null,
            "N/A — Restricted by SELinux / Not exposed by OEM kernel",
            MetricConfidence.UNAVAILABLE
        )
    }

    /**
     * Reads Full Charge Capacity strictly from verified capacity sources:
     * - Direct kernel `/sys/class/power_supply/battery/charge_full` register (µAh -> mAh) -> `MetricConfidence.HIGH_DIRECT`
     * - Explicit derived/cached capacity source -> `MetricConfidence.MEDIUM_DERIVED`
     * - Never equates `BATTERY_PROPERTY_CHARGE_COUNTER` at 100% battery with Full Charge Capacity.
     */
    private fun readFullChargeCapacityWithSource(
        overrides: HardwarePropertyOverrides?
    ): Triple<Int?, String, MetricConfidence> {
        val rawSysfsFullUah = overrides?.rawSysfsChargeFullUah
            ?: if (overrides == null) readSysfsInt("/sys/class/power_supply/battery/charge_full") else null
        val sysfsFullMah = convertSysfsCapacityUahToMah(rawSysfsFullUah)
        if (sysfsFullMah != null) {
            return Triple(
                sysfsFullMah,
                "/sys/class/power_supply/battery/charge_full (Hardware Register µAh → mAh)",
                MetricConfidence.HIGH_DIRECT
            )
        }

        val derivedOrCachedMah = overrides?.derivedOrCachedFullCapacityMah
        if (derivedOrCachedMah != null && derivedOrCachedMah in 100..100_000) {
            return Triple(
                derivedOrCachedMah,
                "Derived / Cached Capacity Estimate (Non-Register)",
                MetricConfidence.MEDIUM_DERIVED
            )
        }

        return Triple(
            null,
            "N/A — Hardware charge_full register not exposed by kernel",
            MetricConfidence.UNAVAILABLE
        )
    }

    private fun classifyChargingSpeed(
        isCharging: Boolean,
        isFull: Boolean,
        powerWatts: Float?
    ): ChargingSpeedTier {
        if (!isCharging && !isFull) return ChargingSpeedTier.NOT_CHARGING
        if (powerWatts == null) return ChargingSpeedTier.UNAVAILABLE
        val absWatts = abs(powerWatts)
        return when {
            absWatts < 5.0f -> ChargingSpeedTier.SLOW
            absWatts < 15.0f -> ChargingSpeedTier.NORMAL
            absWatts < 30.0f -> ChargingSpeedTier.FAST
            else -> ChargingSpeedTier.SUPER_FAST
        }
    }

    private fun computePercentPerHourRate(
        currentMilliAmps: Int?,
        referenceCapacityMah: Int?
    ): Float? {
        if (currentMilliAmps == null || referenceCapacityMah == null || referenceCapacityMah <= 0) {
            return null
        }
        val rate = (abs(currentMilliAmps).toFloat() / referenceCapacityMah.toFloat()) * 100f
        return if (rate in 0.5f..350f) rate else null
    }

    private fun computeReliableChargeTimeMinutes(
        isCharging: Boolean,
        isFull: Boolean,
        percentage: Int,
        chargeCounterMah: Int?,
        fullOrDesignCapacityMah: Int?,
        currentMilliAmps: Int?
    ): Int? {
        if (!isCharging || isFull || percentage >= 100) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val remainingMs = batteryManager?.computeChargeTimeRemaining() ?: -1L
            if (remainingMs > 0L) {
                val mins = (remainingMs / 60_000L).toInt()
                if (mins in 1..720) return mins
            }
        }

        if (chargeCounterMah != null && fullOrDesignCapacityMah != null && currentMilliAmps != null) {
            val absCurrentMa = abs(currentMilliAmps)
            val remainingMah = (fullOrDesignCapacityMah - chargeCounterMah).coerceAtLeast(0)
            if (absCurrentMa > 100 && remainingMah > 0) {
                val hours = remainingMah.toFloat() / absCurrentMa.toFloat()
                val mins = (hours * 60f * 1.15f).roundToInt()
                if (mins in 1..720) return mins
            }
        }
        return null
    }

    private fun buildBatteryHealthReport(
        healthLabel: String,
        temperatureCelsius: Float?,
        cycleCount: Int?,
        cycleCountSource: String,
        designCapacityMah: Int?,
        designCapSource: String,
        designCapConfidence: MetricConfidence,
        fullChargeCapacityMah: Int?,
        fullCapSource: String,
        fullCapConfidence: MetricConfidence,
        chargeCounterMah: Int?,
        energyCounterWh: Float?
    ): BatteryHealthReport {
        val osExplanation = when (healthLabel) {
            "Good" -> "Android EXTRA_HEALTH reports GOOD. Important: This is a qualitative safety flag indicating no thermal/voltage fault—it does NOT mean 100% capacity retention."
            "Overheating" -> "Battery temperature exceeds safe operating envelope. Disconnect charger and let the phone cool down."
            "Over Voltage" -> "Battery voltage exceeds maximum cell threshold. Check charger cable and adapter."
            "Dead / Depleted" -> "Hardware reports severe cell depletion or failure."
            "Hardware Failure" -> "Hardware fuel gauge or PMIC reports an unspecified battery failure."
            "Too Cold" -> "Battery cell is below minimum safe charging temperature."
            else -> "Hardware health status flag is not reported by this device."
        }

        val (measuredHealthPercent, isInconsistent, rawRatio) = evaluateCapacityRetention(
            fullChargeCapacityMah = fullChargeCapacityMah,
            designCapacityMah = designCapacityMah
        )

        val degradationSummary = when {
            isInconsistent -> {
                val ratioStr = rawRatio?.let { String.format(Locale.US, "%.1f%%", it) } ?: "Invalid"
                "N/A • Inconsistent Hardware Data: Full Charge Capacity (${fullChargeCapacityMah ?: 0} mAh) vs Design Capacity (${designCapacityMah ?: 0} mAh) yields an invalid ratio ($ratioStr). Battery Boss never clamps or fabricates capacity retention."
            }
            measuredHealthPercent != null -> {
                String.format(
                    Locale.US,
                    "Measured Full Capacity (%d mAh) vs Design Capacity (%d mAh) indicates %.1f%% capacity retention.",
                    fullChargeCapacityMah,
                    designCapacityMah,
                    measuredHealthPercent
                )
            }
            else -> {
                "Numeric degradation % is N/A because the device kernel does not expose a verified hardware Full Charge Capacity (charge_full) alongside Design Capacity. Battery Boss never invents fake health percentages."
            }
        }

        val tempStatus = when {
            temperatureCelsius == null -> "N/A (Temperature sensor unavailable)"
            temperatureCelsius >= 43f -> String.format(Locale.US, "%.1f °C • High Thermal Stress (Slow down charging)", temperatureCelsius)
            temperatureCelsius >= 38f -> String.format(Locale.US, "%.1f °C • Warm (Normal during fast charging)", temperatureCelsius)
            temperatureCelsius < 10f -> String.format(Locale.US, "%.1f °C • Cold (Increased internal resistance)", temperatureCelsius)
            else -> String.format(Locale.US, "%.1f °C • Optimal Thermal Range (15°C–37°C)", temperatureCelsius)
        }

        return BatteryHealthReport(
            reportedOsHealthStatus = healthLabel,
            osHealthExplanation = osExplanation,
            cycleCount = cycleCount,
            cycleCountSource = cycleCountSource,
            designCapacityMah = designCapacityMah,
            designCapacitySource = designCapSource,
            designCapacityConfidence = designCapConfidence,
            fullChargeCapacityMah = fullChargeCapacityMah,
            fullChargeCapacitySource = fullCapSource,
            fullChargeCapacityConfidence = fullCapConfidence,
            remainingCapacityMah = chargeCounterMah,
            remainingCapacitySource = if (chargeCounterMah != null) {
                "BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER (API µAh → mAh)"
            } else {
                "N/A — Fuel gauge coulomb counter not exposed"
            },
            energyCounterWh = energyCounterWh,
            measuredCapacityHealthPercent = measuredHealthPercent,
            isCapacityDataInconsistent = isInconsistent,
            degradationSummary = degradationSummary,
            temperatureStatus = tempStatus
        )
    }

    private fun buildDiagnosticMetricsList(
        percentage: Int,
        isPercentageValid: Boolean,
        statusLabel: String,
        healthLabel: String,
        voltageVolts: Float?,
        voltageMillivolts: Int?,
        currentMilliAmps: Int?,
        currentSourceApi: String,
        averageCurrentMilliAmps: Int?,
        averageCurrentSourceApi: String,
        powerWatts: Float?,
        temperatureCelsius: Float?,
        technology: String?,
        chargingSource: ChargingSource,
        chargeCounterMah: Int?,
        cycleCount: Int?,
        cycleCountSource: String,
        designCapacityMah: Int?,
        designCapSource: String,
        designCapConfidence: MetricConfidence,
        fullChargeCapacityMah: Int?,
        fullCapSource: String,
        fullCapConfidence: MetricConfidence,
        energyCounterWh: Float?,
        estimatedMinutesToFull: Int?,
        chargingSpeedTier: ChargingSpeedTier
    ): List<DiagnosticMetric> = listOf(
        DiagnosticMetric(
            title = "Battery Percentage",
            value = if (isPercentageValid) "$percentage%" else "N/A",
            sourceApi = "EXTRA_LEVEL / EXTRA_SCALE",
            confidence = if (isPercentageValid) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (isPercentageValid) {
                "Direct state-of-charge ratio reported by the fuel gauge IC."
            } else {
                "N/A — Battery level/scale broadcast is missing or out of bounds."
            }
        ),
        DiagnosticMetric(
            title = "Battery Charging Status",
            value = statusLabel,
            sourceApi = "BatteryManager.EXTRA_STATUS",
            confidence = MetricConfidence.HIGH_DIRECT,
            explanation = "Current PMIC charging state machine status."
        ),
        DiagnosticMetric(
            title = "Battery Health Condition",
            value = healthLabel,
            sourceApi = "BatteryManager.EXTRA_HEALTH",
            confidence = MetricConfidence.QUALITATIVE_OS,
            explanation = "Qualitative safety status flag (Good / Overheating / Over Voltage / Dead / Depleted). Not a numeric wear %."
        ),
        DiagnosticMetric(
            title = "Battery Voltage",
            value = voltageVolts?.let { String.format(Locale.US, "%.3f V (%d mV)", it, voltageMillivolts ?: 0) } ?: "N/A",
            sourceApi = "BatteryManager.EXTRA_VOLTAGE (mV) / sysfs voltage_now (µV)",
            confidence = if (voltageVolts != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (voltageVolts != null) {
                "Terminal voltage measured across the lithium cell pack."
            } else {
                "N/A — Kernel did not include valid EXTRA_VOLTAGE in battery broadcast."
            }
        ),
        DiagnosticMetric(
            title = "Instant Battery Current",
            value = currentMilliAmps?.let { "$it mA" } ?: "N/A",
            sourceApi = currentSourceApi,
            confidence = if (currentMilliAmps != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (currentMilliAmps != null) {
                "Instantaneous net current entering or leaving the battery cell (converted from documented µA to mA; source: $currentSourceApi)."
            } else {
                "N/A — This device kernel or emulator does not expose BATTERY_PROPERTY_CURRENT_NOW (µA) or sysfs current_now (µA)."
            }
        ),
        DiagnosticMetric(
            title = "Average Battery Current",
            value = averageCurrentMilliAmps?.let { "$it mA" } ?: "N/A",
            sourceApi = averageCurrentSourceApi,
            confidence = if (averageCurrentMilliAmps != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (averageCurrentMilliAmps != null) {
                "Rolling average current computed by the hardware fuel gauge (converted from documented µA to mA; source: $averageCurrentSourceApi)."
            } else {
                "N/A — Many OEM fuel gauges omit CURRENT_AVERAGE and only report CURRENT_NOW."
            }
        ),
        DiagnosticMetric(
            title = "Measured Battery-Side Power",
            value = powerWatts?.let { String.format(Locale.US, "%.2f W", abs(it)) } ?: "N/A",
            sourceApi = "Derived: EXTRA_VOLTAGE × CURRENT_NOW",
            confidence = if (powerWatts != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            explanation = if (powerWatts != null) {
                "Net power entering/leaving the battery cell (V_cell × I_cell). Note: Wall brick input power is higher due to system load and DC-DC conversion loss."
            } else {
                "N/A — Requires both hardware Voltage (V) and Instant Current (mA) sensors."
            }
        ),
        DiagnosticMetric(
            title = "Charging Speed Analysis",
            value = chargingSpeedTier.label,
            sourceApi = "Derived from Battery-Side Wattage",
            confidence = if (powerWatts != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            explanation = chargingSpeedTier.description
        ),
        DiagnosticMetric(
            title = "Battery Temperature",
            value = temperatureCelsius?.let { String.format(Locale.US, "%.1f °C (%.1f °F)", it, it * 9f / 5f + 32f) } ?: "N/A",
            sourceApi = "BatteryManager.EXTRA_TEMPERATURE (0.1 °C)",
            confidence = if (temperatureCelsius != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (temperatureCelsius != null) {
                "NTC thermistor reading inside the battery pack (reported in 0.1°C steps)."
            } else {
                "N/A — Battery thermistor reading not reported by hardware."
            }
        ),
        DiagnosticMetric(
            title = "Battery Technology",
            value = technology ?: "N/A",
            sourceApi = "BatteryManager.EXTRA_TECHNOLOGY",
            confidence = if (technology != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (technology != null) "Cell chemistry identifier reported by kernel driver." else "N/A — Chemistry string omitted by kernel."
        ),
        DiagnosticMetric(
            title = "Charging Source",
            value = chargingSource.displayName,
            sourceApi = "BatteryManager.EXTRA_PLUGGED",
            confidence = MetricConfidence.HIGH_DIRECT,
            explanation = "Active power rail negotiated by the PMIC (AC, USB, Wireless, Dock, or Battery)."
        ),
        DiagnosticMetric(
            title = "Remaining Capacity (Charge Counter)",
            value = chargeCounterMah?.let { "$it mAh" } ?: "N/A",
            sourceApi = "BATTERY_PROPERTY_CHARGE_COUNTER (API µAh → mAh)",
            confidence = if (chargeCounterMah != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (chargeCounterMah != null) {
                "Remaining charge in milliampere-hours converted directly from the documented Android API microampere-hours (µAh / 1000) coulomb counter."
            } else {
                "N/A — Hardware coulomb counter is not exposed on this device."
            }
        ),
        DiagnosticMetric(
            title = "Battery Cycle Count",
            value = cycleCount?.let { "$it cycles" } ?: "N/A",
            sourceApi = cycleCountSource,
            confidence = if (cycleCount != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (cycleCount != null) {
                "Total full-equivalent charge-discharge cycles recorded by the fuel gauge."
            } else {
                "N/A — Cycle count requires Android 14+ EXTRA_CYCLE_COUNT support or unrestricted kernel sysfs access."
            }
        ),
        DiagnosticMetric(
            title = "Design Capacity (Factory)",
            value = designCapacityMah?.let { "$it mAh" } ?: "N/A",
            sourceApi = designCapSource,
            confidence = designCapConfidence,
            explanation = when (designCapConfidence) {
                MetricConfidence.HIGH_DIRECT -> "Nominal rated battery capacity read directly from kernel charge_full_design register (µAh → mAh)."
                MetricConfidence.MEDIUM_DERIVED -> "Nominal factory capacity read from Android OS PowerProfile XML (static OEM framework config, not a live hardware register)."
                else -> "N/A — Factory design capacity register is restricted on this build."
            }
        ),
        DiagnosticMetric(
            title = "Full Charge Capacity",
            value = fullChargeCapacityMah?.let { "$it mAh" } ?: "N/A",
            sourceApi = fullCapSource,
            confidence = fullCapConfidence,
            explanation = when (fullCapConfidence) {
                MetricConfidence.HIGH_DIRECT -> "Verified full-charge capacity read directly from kernel charge_full fuel-gauge register (µAh → mAh)."
                MetricConfidence.MEDIUM_DERIVED -> "Derived or cached full-charge capacity estimate (not a direct hardware register)."
                else -> "N/A — Kernel blocks charge_full sysfs node; Charge Counter at 100% is never assumed to be Full Charge Capacity."
            }
        ),
        DiagnosticMetric(
            title = "Energy Counter",
            value = energyCounterWh?.let { String.format(Locale.US, "%.2f Wh", it) } ?: "N/A",
            sourceApi = "BATTERY_PROPERTY_ENERGY_COUNTER (nWh → Wh)",
            confidence = if (energyCounterWh != null) MetricConfidence.HIGH_DIRECT else MetricConfidence.UNAVAILABLE,
            explanation = if (energyCounterWh != null) {
                "Remaining energy in watt-hours converted from nanowatt-hours (nWh)."
            } else {
                "N/A — Most smartphone PMICs report coulomb charge (µAh) rather than energy (nWh)."
            }
        ),
        DiagnosticMetric(
            title = "Estimated Time to Full",
            value = estimatedMinutesToFull?.let { "$it min" } ?: "N/A",
            sourceApi = "computeChargeTimeRemaining() / Coulomb Delta",
            confidence = if (estimatedMinutesToFull != null) MetricConfidence.MEDIUM_DERIVED else MetricConfidence.UNAVAILABLE,
            explanation = if (estimatedMinutesToFull != null) {
                "Estimated minutes remaining until 100% full charge (includes CC/CV taper allowance)."
            } else {
                "N/A — Only available while actively charging with a valid positive current sensor."
            }
        )
    )

    private fun readSysfsInt(path: String): Int? {
        return runCatching {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull()
            } else {
                null
            }
        }.getOrNull()
    }

    private fun mapChargingSource(plugged: Int): ChargingSource = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> ChargingSource.AC
        BatteryManager.BATTERY_PLUGGED_USB -> ChargingSource.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingSource.WIRELESS
        BatteryManager.BATTERY_PLUGGED_DOCK -> ChargingSource.DOCK
        else -> ChargingSource.NONE
    }

    fun mapHealthLabel(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead / Depleted"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Hardware Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Too Cold"
        else -> "N/A"
    }

    fun getDetectedOemGuide(): OemOptimizationGuide {
        val manufacturer = (Build.MANUFACTURER ?: "Android").trim()
        val brand = (Build.BRAND ?: manufacturer).trim()
        val combined = "$manufacturer $brand".lowercase()

        return when {
            combined.contains("realme") || combined.contains("oppo") -> OemOptimizationGuide(
                manufacturerName = "$manufacturer ($brand)",
                osSkinName = "Realme UI / ColorOS",
                steps = listOf(
                    "Open App Info → Battery Usage → Enable 'Allow background activity' and 'Allow auto-launch'.",
                    "Open Recent Apps tray → Tap the 3-dot menu on Battery Boss → Select 'Lock' so system memory cleanup does not kill the voice service.",
                    "In System Settings → Battery → More Battery Settings → Optimize battery use → Set Battery Boss to 'Don't optimize'."
                ),
                caveatNote = "Realme UI and ColorOS aggressively suspend background services when the screen is off for extended periods unless Auto-Launch and Background Activity are explicitly allowed."
            )
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") -> OemOptimizationGuide(
                manufacturerName = "$manufacturer ($brand)",
                osSkinName = "Xiaomi HyperOS / MIUI",
                steps = listOf(
                    "Open App Info → Enable 'Autostart' (Background Autostart) toggle.",
                    "Tap 'Battery saver' (or Battery) → Choose 'No restrictions'.",
                    "In the Recent Apps screen, long-press or pull down on Battery Boss and tap the Padlock icon to lock it in RAM."
                ),
                caveatNote = "Xiaomi HyperOS and MIUI require the 'Autostart' permission for Battery Boss to resume automatically after a phone reboot."
            )
            combined.contains("samsung") -> OemOptimizationGuide(
                manufacturerName = "Samsung",
                osSkinName = "One UI",
                steps = listOf(
                    "Open App Info → Battery → Select 'Unrestricted'.",
                    "Open Device Care → Battery → Background usage limits → Ensure Battery Boss is added to 'Never auto sleeping apps'.",
                    "Keep the persistent Foreground Notification enabled so One UI recognizes active battery monitoring."
                ),
                caveatNote = "Samsung One UI places apps into Deep Sleep after a few days of inactivity unless set to 'Unrestricted' and added to 'Never sleeping apps'."
            )
            combined.contains("vivo") || combined.contains("iqoo") -> OemOptimizationGuide(
                manufacturerName = "$manufacturer ($brand)",
                osSkinName = "Funtouch OS / OriginOS",
                steps = listOf(
                    "Open Settings → Battery → Background power consumption management → Set Battery Boss to 'High background power usage' / 'Don't restrict'.",
                    "Open iManager → App Manager → Autostart Manager → Enable Autostart for Battery Boss.",
                    "Lock Battery Boss in the Recent Apps task switcher."
                ),
                caveatNote = "Vivo Funtouch OS blocks boot broadcasts and background TTS audio unless both Autostart and High Background Power Usage are enabled."
            )
            combined.contains("oneplus") -> OemOptimizationGuide(
                manufacturerName = "OnePlus",
                osSkinName = "OxygenOS",
                steps = listOf(
                    "Open App Info → Battery usage → Enable 'Allow background activity' and 'Allow auto-launch'.",
                    "Go to Settings → Battery → Advanced settings → Disable 'Sleep standby optimization' if overnight charging alerts are delayed.",
                    "Lock Battery Boss in Recent Apps."
                ),
                caveatNote = "OxygenOS shares power management with ColorOS; enabling Auto-launch ensures charger connection broadcasts wake the voice assistant."
            )
            combined.contains("motorola") || combined.contains("moto") -> OemOptimizationGuide(
                manufacturerName = "Motorola",
                osSkinName = "My UX / Hello UI",
                steps = listOf(
                    "Open App Info → App battery usage → Select 'Unrestricted'.",
                    "Ensure Adaptive Battery does not restrict Battery Boss when the screen is off."
                ),
                caveatNote = "Motorola runs near-stock Android and works reliably with the Foreground Service once battery usage is set to Unrestricted."
            )
            else -> OemOptimizationGuide(
                manufacturerName = manufacturer.replaceFirstChar { it.uppercase() },
                osSkinName = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                steps = listOf(
                    "Tap 'Optimization List' below and set Battery Boss to 'Unrestricted' or 'Don't optimize'.",
                    "Keep the Foreground Service notification active so Android prioritizes real-time charger and percentage broadcasts.",
                    "Allow Notification permission on Android 13+ so the persistent monitoring bar stays visible."
                ),
                caveatNote = "Android background execution varies by OEM firmware. Battery Boss uses a standard Foreground Service and dynamic broadcast registration, but strict OEM power savers can still pause background processes if optimization is left on."
            )
        }
    }
}
