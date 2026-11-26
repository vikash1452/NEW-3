package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.hardware.ChargerRadioController
import com.example.hardware.RadioStatesSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RadioTargetKind {
    BLUETOOTH,
    WIFI,
    MOBILE_DATA,
    INTERNET_COMBINED
}

data class RadioDesiredAction(
    val targetBluetooth: Boolean?,
    val targetWifi: Boolean?,
    val targetMobileData: Boolean?,
    val reasonLabel: String,
    val initialOsStates: RadioStatesSnapshot? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun hasAnyTarget(): Boolean =
        targetBluetooth != null || targetWifi != null || targetMobileData != null
}

/**
 * Pure Kotlin helper for Quick Settings node matching and ON/OFF state inference so all matching
 * rules can be unit-tested on the JVM without requiring a physical device.
 */
object RadioAccessibilityNodeHelper {

    private val EXCLUDED_KEYWORDS = listOf(
        "saver",
        "hotspot",
        "tether",
        "airplane",
        "flight",
        "location",
        "nfc",
        "cast",
        "flashlight",
        "torch",
        "do not disturb",
        "auto-rotate",
        "background data",
        "roaming",
        "calling",
        "wifi calling",
        "wi-fi calling",
        "nearby"
    )

    private fun isExcludedLabel(normalized: String): Boolean {
        return EXCLUDED_KEYWORDS.any { normalized.contains(it) }
    }

    fun matchesRadioLabel(kind: RadioTargetKind, combinedTexts: List<String>): Boolean {
        val merged = combinedTexts
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
        if (merged.isEmpty() || isExcludedLabel(merged)) return false

        val tokens = combinedTexts
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        return when (kind) {
            RadioTargetKind.WIFI -> {
                tokens.any { t ->
                    t == "wi-fi" || t == "wifi" || t == "wlan" || t == "वाई-फाई" ||
                        t.startsWith("wi-fi,") || t.startsWith("wi-fi ") ||
                        t.startsWith("wifi,") || t.startsWith("wifi ") ||
                        t.startsWith("wlan,") || t.startsWith("wlan ")
                } || merged.contains("wi-fi") || merged.contains("wlan")
            }
            RadioTargetKind.BLUETOOTH -> {
                tokens.any { t ->
                    t == "bluetooth" || t == "ब्लूटूथ" ||
                        t.startsWith("bluetooth,") || t.startsWith("bluetooth ") ||
                        t.contains("bluetooth") || t.contains("ब्लूटूथ")
                }
            }
            RadioTargetKind.MOBILE_DATA -> {
                tokens.any { t ->
                    t == "mobile data" || t == "cellular data" || t == "cellular" ||
                        t == "data" || t == "sim data" || t == "मोबाइल डेटा" ||
                        t.startsWith("mobile data") || t.startsWith("cellular data") ||
                        t.startsWith("data,") || t.startsWith("data connection") ||
                        t.contains("mobile data") || t.contains("cellular data") ||
                        t.contains("mobile network") || t.contains("मोबाइल डेटा")
                }
            }
            RadioTargetKind.INTERNET_COMBINED -> {
                tokens.any { t ->
                    t == "internet" || t.startsWith("internet,") || t.startsWith("internet ") ||
                        t == "इंटरनेट" || t.startsWith("इंटरनेट,")
                }
            }
        }
    }

    /**
     * Infers whether a Quick Settings tile or Switch node is currently ON (`true`), OFF (`false`),
     * or unknown (`null`). Evaluates negative/OFF phrases before positive/ON phrases to prevent
     * `"not connected"` or `"disconnected"` from matching `"connected"`.
     */
    fun inferNodeEnabledState(
        isCheckable: Boolean,
        isChecked: Boolean,
        isSelected: Boolean,
        stateDescription: String?,
        contentDescription: String?,
        descendantTexts: List<String>
    ): Boolean? {
        if (isCheckable) {
            return isChecked
        }

        val stateNorm = stateDescription?.trim()?.lowercase().orEmpty()
        if (stateNorm.isNotEmpty()) {
            inferFromLabelPhrase(stateNorm)?.let { return it }
        }

        val descNormList = descendantTexts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (phrase in descNormList) {
            inferFromLabelPhrase(phrase)?.let { return it }
        }

        val cdNorm = contentDescription?.trim()?.lowercase().orEmpty()
        if (cdNorm.isNotEmpty()) {
            // Look at comma/space segments (e.g., "Wi-Fi, Off", "Bluetooth, On, MyBuds")
            val segments = cdNorm.split(",", "\n", "|", "•").map { it.trim() }
            for (seg in segments) {
                inferFromLabelPhrase(seg)?.let { return it }
            }
        }

        return when {
            isChecked -> true
            isSelected -> true
            else -> null
        }
    }

    private fun inferFromLabelPhrase(phrase: String): Boolean? {
        if (phrase.isBlank()) return null

        // Check explicit OFF signals BEFORE ON signals
        val offExact = setOf("off", "disabled", "inactive", "disconnected", "not connected", "बंद", "ऑफ")
        if (offExact.contains(phrase)) return false
        if (phrase.contains("turned off") ||
            phrase.contains("not connected") ||
            phrase.contains("disconnected") ||
            phrase.endsWith(" off") ||
            phrase.contains(", off") ||
            phrase.contains("disabled") ||
            phrase.contains("बंद")
        ) {
            return false
        }

        val onExact = setOf("on", "enabled", "active", "connected", "चालू", "ऑन", "5g", "4g", "lte", "3g")
        if (onExact.contains(phrase)) return true
        if (phrase.contains("turned on") ||
            phrase.endsWith(" on") ||
            phrase.contains(", on") ||
            phrase.contains("enabled") ||
            phrase.contains("connected") ||
            phrase.contains("चालू") ||
            phrase.contains("active")
        ) {
            return true
        }
        return null
    }
}

/**
 * Optional, strictly user-enabled `AccessibilityService` (`Battery Boss Radio Auto-Toggle`)
 * that automates Android 10–16 Quick Settings radio toggles without root:
 * - On charger connect: turns OFF Bluetooth, Wi-Fi, and Mobile Data via Quick Settings tiles
 *   (or Stock Pixel `"Internet"` dialog).
 * - On charger disconnect: turns ON only whichever radios were recorded ON prior to charging.
 * - If the screen is locked when charger state changes, waits cleanly for `ACTION_USER_PRESENT`
 *   and executes automatically the instant the user unlocks the phone.
 *
 * Strictly zero action is performed unless a `PendingRadioTask` was triggered by charger events.
 */
class RadioToggleAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val executionMutex = Mutex()
    private var unlockReceiverRegistered = false
    private var activeTaskJob: Job? = null

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                val pending = pendingTask ?: return
                scheduleExecution(pending)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BatteryTelemetryHub.setAccessibilityRadioHelperEnabled(true)
        registerUnlockReceiver()

        // If user just enabled the service or unlocked while task is queued, process it now
        pendingTask?.let { scheduleExecution(it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately passive: never scrapes screens unless an explicit charger event queued a task.
    }

    override fun onInterrupt() {
        activeTaskJob?.cancel()
    }

    override fun onDestroy() {
        unregisterUnlockReceiver()
        activeTaskJob?.cancel()
        serviceScope.cancel()
        if (instance === this) {
            instance = null
        }
        BatteryTelemetryHub.setAccessibilityRadioHelperEnabled(false)
        super.onDestroy()
    }

    private fun registerUnlockReceiver() {
        if (unlockReceiverRegistered) return
        runCatching {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(userPresentReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(userPresentReceiver, filter)
            }
            unlockReceiverRegistered = true
        }
    }

    private fun unregisterUnlockReceiver() {
        if (!unlockReceiverRegistered) return
        runCatching { unregisterReceiver(userPresentReceiver) }
        unlockReceiverRegistered = false
    }

    private fun isDeviceUnlockedAndInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = runCatching { pm?.isInteractive ?: true }.getOrDefault(true)
        val keyguardLocked = runCatching { km?.isKeyguardLocked ?: false }.getOrDefault(false)
        return interactive && !keyguardLocked
    }

    private fun scheduleExecution(task: RadioDesiredAction) {
        if (!task.hasAnyTarget()) {
            pendingTask = null
            return
        }
        if (!isDeviceUnlockedAndInteractive()) {
            BatteryTelemetryHub.updateLastRadioAutomationStatus(
                "Waiting for phone unlock to ${task.reasonLabel}..."
            )
            return
        }

        activeTaskJob?.cancel()
        activeTaskJob = serviceScope.launch {
            executionMutex.withLock {
                val current = pendingTask ?: return@withLock
                if (!isDeviceUnlockedAndInteractive()) return@withLock
                pendingTask = null
                executeQuickSettingsTask(current)
            }
        }
    }

    private suspend fun executeQuickSettingsTask(task: RadioDesiredAction) {
        var remainingBt = task.targetBluetooth
        var remainingWifi = task.targetWifi
        var remainingData = task.targetMobileData

        // Skip any radio whose live OS state ALREADY equals desired state
        val preCheckOs = ChargerRadioController.readCurrentRadioStates(applicationContext)
        if (remainingBt != null && preCheckOs.bluetoothOn == remainingBt) remainingBt = null
        if (remainingWifi != null && preCheckOs.wifiOn == remainingWifi) remainingWifi = null
        if (remainingData != null && preCheckOs.mobileDataOn == remainingData) remainingData = null

        if (remainingBt == null && remainingWifi == null && remainingData == null) {
            ChargerRadioController.cancelAllRadioNotifications(applicationContext)
            BatteryTelemetryHub.updateLastRadioAutomationStatus("All target radios already in desired state")
            return
        }

        val qsOpened = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        if (!qsOpened) {
            BatteryTelemetryHub.updateLastRadioAutomationStatus("Could not open Quick Settings automatically")
            return
        }

        delay(550L)

        var actionsClicked = 0
        try {
            // Try Page 1 of Quick Settings
            val page1Result = attemptToggleVisibleTiles(
                needBt = remainingBt,
                needWifi = remainingWifi,
                needData = remainingData,
                osFallback = preCheckOs
            )
            actionsClicked += page1Result.clickedCount
            remainingBt = page1Result.remainingBt
            remainingWifi = page1Result.remainingWifi
            remainingData = page1Result.remainingData

            // If any tile wasn't on page 1, try scrolling Quick Settings forward once
            if (remainingBt != null || remainingWifi != null || remainingData != null) {
                if (scrollQuickSettingsForward()) {
                    delay(420L)
                    val refreshedOs = ChargerRadioController.readCurrentRadioStates(applicationContext)
                    val page2Result = attemptToggleVisibleTiles(
                        needBt = remainingBt,
                        needWifi = remainingWifi,
                        needData = remainingData,
                        osFallback = refreshedOs
                    )
                    actionsClicked += page2Result.clickedCount
                    remainingBt = page2Result.remainingBt
                    remainingWifi = page2Result.remainingWifi
                    remainingData = page2Result.remainingData
                }
            }

            // Stock Pixel Android 12-16 uses a combined "Internet" tile for Wi-Fi + Mobile Data
            if (remainingWifi != null || remainingData != null) {
                val internetTile = findTileNodeForKind(RadioTargetKind.INTERNET_COMBINED)
                if (internetTile != null && internetTile.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    actionsClicked++
                    delay(550L)
                    val refreshedOs = ChargerRadioController.readCurrentRadioStates(applicationContext)
                    val dialogResult = attemptToggleVisibleTiles(
                        needBt = null,
                        needWifi = remainingWifi,
                        needData = remainingData,
                        osFallback = refreshedOs
                    )
                    actionsClicked += dialogResult.clickedCount
                    remainingWifi = dialogResult.remainingWifi
                    remainingData = dialogResult.remainingData
                    // Close the Internet panel dialog
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(260L)
                }
            }
        } finally {
            delay(320L)
            dismissQuickSettingsShade()
        }

        delay(350L)
        val postOs = ChargerRadioController.readCurrentRadioStates(applicationContext)
        val statusMsg = buildString {
            append(task.reasonLabel)
            append(": ")
            append("Wi-Fi=")
            append(if (postOs.wifiOn) "ON" else "OFF")
            append(", BT=")
            append(if (postOs.bluetoothOn) "ON" else "OFF")
            append(", Data=")
            append(if (postOs.mobileDataOn) "ON" else "OFF")
        }
        BatteryTelemetryHub.updateLastRadioAutomationStatus(statusMsg)

        if (actionsClicked > 0) {
            // Cancel notification prompt once automation handled toggles cleanly
            ChargerRadioController.cancelChargerRadioNotification(applicationContext)
        }
    }

    private data class TilePassResult(
        val clickedCount: Int,
        val remainingBt: Boolean?,
        val remainingWifi: Boolean?,
        val remainingData: Boolean?
    )

    private suspend fun attemptToggleVisibleTiles(
        needBt: Boolean?,
        needWifi: Boolean?,
        needData: Boolean?,
        osFallback: RadioStatesSnapshot
    ): TilePassResult {
        var remBt = needBt
        var remWifi = needWifi
        var remData = needData
        var clicked = 0

        if (remBt != null) {
            val node = findTileNodeForKind(RadioTargetKind.BLUETOOTH)
            if (node != null) {
                val curOn = inferTileState(node) ?: osFallback.bluetoothOn
                if (curOn != remBt) {
                    if (clickNodeOrParent(node)) {
                        clicked++
                        delay(220L)
                    }
                }
                remBt = null
            }
        }

        if (remWifi != null) {
            val node = findTileNodeForKind(RadioTargetKind.WIFI)
            if (node != null) {
                val curOn = inferTileState(node) ?: osFallback.wifiOn
                if (curOn != remWifi) {
                    if (clickNodeOrParent(node)) {
                        clicked++
                        delay(220L)
                    }
                }
                remWifi = null
            }
        }

        if (remData != null) {
            val node = findTileNodeForKind(RadioTargetKind.MOBILE_DATA)
            if (node != null) {
                val curOn = inferTileState(node) ?: osFallback.mobileDataOn
                if (curOn != remData) {
                    if (clickNodeOrParent(node)) {
                        clicked++
                        delay(220L)
                    }
                }
                remData = null
            }
        }

        return TilePassResult(
            clickedCount = clicked,
            remainingBt = remBt,
            remainingWifi = remWifi,
            remainingData = remData
        )
    }

    private fun dismissQuickSettingsShade() {
        val dismissed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            false
        }
        if (!dismissed) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun collectRootNodes(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows?.forEach { win ->
                win.root?.let { roots.add(it) }
            }
        }
        runCatching {
            rootInActiveWindow?.let { active ->
                if (roots.none { it == active }) {
                    roots.add(active)
                }
            }
        }
        return roots
    }

    private fun findTileNodeForKind(kind: RadioTargetKind): AccessibilityNodeInfo? {
        val roots = collectRootNodes()
        for (root in roots) {
            val found = searchNodeRecursive(root, kind, depth = 0)
            if (found != null) return found
        }
        return null
    }

    private fun searchNodeRecursive(
        node: AccessibilityNodeInfo?,
        kind: RadioTargetKind,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 14) return null

        val ownTexts = collectOwnAndChildTexts(node, maxDepth = 3)
        if ((node.isClickable || node.isCheckable) &&
            RadioAccessibilityNodeHelper.matchesRadioLabel(kind, ownTexts)
        ) {
            // Prefer inner checkable Switch when inside an Internet dialog row
            val switchChild = findCheckableDescendant(node)
            return switchChild ?: node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = searchNodeRecursive(child, kind, depth + 1)
            if (match != null) return match
        }
        return null
    }

    private fun findCheckableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isCheckable) return child
        }
        return null
    }

    private fun collectOwnAndChildTexts(node: AccessibilityNodeInfo, maxDepth: Int): List<String> {
        val list = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > maxDepth) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            for (i in 0 until n.childCount) {
                collect(n.getChild(i), d + 1)
            }
        }
        collect(node, 0)
        return list
    }

    private fun inferTileState(node: AccessibilityNodeInfo): Boolean? {
        val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()
        } else {
            null
        }
        val childTexts = collectOwnAndChildTexts(node, maxDepth = 3)
        return RadioAccessibilityNodeHelper.inferNodeEnabledState(
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isSelected = node.isSelected,
            stateDescription = stateDesc,
            contentDescription = node.contentDescription?.toString(),
            descendantTexts = childTexts
        )
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var p = node.parent
        var hops = 0
        while (p != null && hops < 3) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            p = p.parent
            hops++
        }
        return false
    }

    private fun scrollQuickSettingsForward(): Boolean {
        val roots = collectRootNodes()
        for (root in roots) {
            val scrollable = findScrollableNode(root, 0)
            if (scrollable != null) {
                if (scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 10) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val match = findScrollableNode(node.getChild(i), depth + 1)
            if (match != null) return match
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: RadioToggleAccessibilityService? = null

        @Volatile
        private var pendingTask: RadioDesiredAction? = null

        fun isServiceConnected(context: Context): Boolean {
            if (instance != null) return true
            val expectedName = "${context.packageName}/${RadioToggleAccessibilityService::class.java.name}"
            val shortName = "${context.packageName}/.service.${RadioToggleAccessibilityService::class.java.simpleName}"
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            return enabled.split(':')
                .map { it.trim() }
                .any { it.equals(expectedName, ignoreCase = true) || it.equals(shortName, ignoreCase = true) }
        }

        fun enqueueChargerConnectDisable(
            disableBluetooth: Boolean,
            disableWifi: Boolean,
            disableMobileData: Boolean,
            initialOsStates: RadioStatesSnapshot
        ): Boolean {
            val task = RadioDesiredAction(
                targetBluetooth = if (disableBluetooth) false else null,
                targetWifi = if (disableWifi) false else null,
                targetMobileData = if (disableMobileData) false else null,
                reasonLabel = "Charger Connected Auto-Off",
                initialOsStates = initialOsStates
            )
            if (!task.hasAnyTarget()) return false
            val service = instance ?: return false
            pendingTask = task
            service.scheduleExecution(task)
            return true
        }

        fun enqueueChargerDisconnectRestore(
            restoreBluetooth: Boolean,
            restoreWifi: Boolean,
            restoreMobileData: Boolean,
            initialOsStates: RadioStatesSnapshot
        ): Boolean {
            val task = RadioDesiredAction(
                targetBluetooth = if (restoreBluetooth) true else null,
                targetWifi = if (restoreWifi) true else null,
                targetMobileData = if (restoreMobileData) true else null,
                reasonLabel = "Charger Unplugged Auto-Restore",
                initialOsStates = initialOsStates
            )
            if (!task.hasAnyTarget()) return false
            val service = instance ?: return false
            pendingTask = task
            service.scheduleExecution(task)
            return true
        }

        fun clearPendingTasks() {
            pendingTask = null
        }
    }
}
