package com.example.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.model.VoiceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val enabled: Boolean,
    val version: String,
    val title: String,
    val description: String,
    val apkUrl: String,
    val mandatory: Boolean,
    val downloadClicks: Long = 0L
) {
    val bulletPoints: List<String>
        get() = description
            .replace("\\n", "\n")
            .lines()
            .map { line -> line.trim().trimStart('-', '*', '•').trim() }
            .filter { it.isNotEmpty() }

    val displayVersion: String
        get() {
            val cleaned = version.trim()
            if (cleaned.isEmpty()) return ""
            return if (cleaned.startsWith("v", ignoreCase = true)) cleaned else "v$cleaned"
        }
}

sealed interface UpdateFetchResult {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateFetchResult
    data object UpToDate : UpdateFetchResult
    data class Error(val message: String) : UpdateFetchResult
}

/**
 * Internal, UI-invisible gate that tracks whether an active remote update is live on Firebase.
 *
 * Behavior:
 * - When `isUpdateLockActive(context) == false` (normal state): zero effect on app or voice.
 * - When `isUpdateLockActive(context) == true` (update pushed):
 *   1. All normal app UI and background battery functions are locked/paused.
 *   2. Whenever the user plugs in the charger, instead of announcing battery percentage,
 *      the voice engine announces ONLY that an app update has arrived.
 */
object UpdateLockManager {
    private const val PREFS_NAME = "battery_boss_update_gate"
    private const val KEY_LOCK_ACTIVE = "lock_active"
    private const val KEY_VERSION = "cached_version"
    private const val KEY_TITLE = "cached_title"
    private const val KEY_DESCRIPTION = "cached_description"
    private const val KEY_APK_URL = "cached_apk_url"
    private const val KEY_MANDATORY = "cached_mandatory"
    private const val KEY_LAST_CHECKED_MS = "last_checked_ms"

    private const val BACKGROUND_CACHE_TTL_MS = 30_000L
    private const val BACKGROUND_FETCH_TIMEOUT_MS = 1_800L

    private const val HI_UPDATE_CHARGER_VOICE =
        "बॉस, अभी ऐप में नया अपडेट आ गया है। कृपया नया वर्ज़न डाउनलोड करें।"
    private const val EN_UPDATE_CHARGER_VOICE =
        "Boss, a new app update is available now. Please download the new version."

    fun buildHiddenUpdateVoiceMessage(language: VoiceLanguage): String {
        return if (language == VoiceLanguage.HINDI) {
            HI_UPDATE_CHARGER_VOICE
        } else {
            EN_UPDATE_CHARGER_VOICE
        }
    }

    fun isUpdateLockActive(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LOCK_ACTIVE, false)) return false
        val cachedVersion = prefs.getString(KEY_VERSION, "").orEmpty()
        // Auto-unlock automatically once the user installs the updated APK version
        if (cachedVersion.isNotEmpty() && !UpdateRepository.isVersionMismatch(BuildConfig.VERSION_NAME, cachedVersion)) {
            clearLock(context)
            return false
        }
        return true
    }

    fun getCachedUpdateInfo(context: Context): AppUpdateInfo? {
        if (!isUpdateLockActive(context)) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, "").orEmpty()
        val apkUrl = prefs.getString(KEY_APK_URL, "").orEmpty()
        if (version.isEmpty() || !UpdateRepository.isValidDownloadUrl(apkUrl)) return null
        return AppUpdateInfo(
            enabled = true,
            version = version,
            title = prefs.getString(KEY_TITLE, "ABOUT UPDATE").orEmpty().ifBlank { "ABOUT UPDATE" },
            description = prefs.getString(KEY_DESCRIPTION, "").orEmpty(),
            apkUrl = apkUrl,
            mandatory = true
        )
    }

    fun syncFromFetchResult(context: Context, result: UpdateFetchResult) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        when (result) {
            is UpdateFetchResult.UpdateAvailable -> {
                prefs.edit()
                    .putBoolean(KEY_LOCK_ACTIVE, true)
                    .putString(KEY_VERSION, result.info.version)
                    .putString(KEY_TITLE, result.info.title)
                    .putString(KEY_DESCRIPTION, result.info.description)
                    .putString(KEY_APK_URL, result.info.apkUrl)
                    .putBoolean(KEY_MANDATORY, true)
                    .putLong(KEY_LAST_CHECKED_MS, now)
                    .apply()
            }
            is UpdateFetchResult.UpToDate -> {
                prefs.edit()
                    .putBoolean(KEY_LOCK_ACTIVE, false)
                    .remove(KEY_VERSION)
                    .remove(KEY_TITLE)
                    .remove(KEY_DESCRIPTION)
                    .remove(KEY_APK_URL)
                    .remove(KEY_MANDATORY)
                    .putLong(KEY_LAST_CHECKED_MS, now)
                    .apply()
            }
            is UpdateFetchResult.Error -> {
                // Keep existing persisted lock state unchanged on transient offline/network errors
            }
        }
    }

    fun setUpdateLockForTest(context: Context, active: Boolean, version: String = "99.0") {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (active) {
            prefs.edit()
                .putBoolean(KEY_LOCK_ACTIVE, true)
                .putString(KEY_VERSION, version)
                .putString(KEY_TITLE, "ABOUT UPDATE")
                .putString(KEY_DESCRIPTION, "New version available")
                .putString(KEY_APK_URL, "https://example.com/update.apk")
                .putBoolean(KEY_MANDATORY, true)
                .putLong(KEY_LAST_CHECKED_MS, System.currentTimeMillis())
                .apply()
        } else {
            clearLock(context)
        }
    }

    fun clearLock(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Quickly refreshes the Firebase update lock state when a charger is plugged in,
     * even if the user hasn't opened the app since the Admin enabled an update.
     * Falls back seamlessly to the cached lock state if offline or timed out.
     */
    suspend fun refreshIfStaleAndCheckLocked(
        context: Context,
        repository: UpdateRepository = UpdateRepository()
    ): Boolean {
        val appContext = context.applicationContext
        // Skip live internet round-trip in Robolectric JVM unit tests
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return isUpdateLockActive(appContext)
        }

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastChecked = prefs.getLong(KEY_LAST_CHECKED_MS, 0L)
        val isStale = (System.currentTimeMillis() - lastChecked) >= BACKGROUND_CACHE_TTL_MS

        if (isStale) {
            val result = withTimeoutOrNull(BACKGROUND_FETCH_TIMEOUT_MS) {
                repository.fetchUpdate(currentVersion = BuildConfig.VERSION_NAME)
            }
            if (result != null) {
                syncFromFetchResult(appContext, result)
            }
        }
        return isUpdateLockActive(appContext)
    }
}

/**
 * Fetches app update metadata directly from the Firebase Realtime Database REST endpoint:
 * https://admin-50dca-default-rtdb.firebaseio.com/battery_boss_update
 *
 * - Supports both camelCase (`enabled`, `apkUrl`, `mandatory`) and snake_case (`is_enabled`, `apk_url`, `is_mandatory`).
 * - Does NOT apply any `release_date` / future-date blocking check.
 * - Shows update whenever `enabled == true` and server `version` differs from current app version.
 * - Atomically increments `download_clicks` when the user taps Update.
 */
class UpdateRepository(
    private val databaseBaseUrl: String = FIREBASE_DATABASE_URL,
    private val nodePath: String = UPDATE_NODE_PATH
) {
    companion object {
        private const val TAG = "UpdateRepository"
        const val FIREBASE_DATABASE_URL = "https://admin-50dca-default-rtdb.firebaseio.com"
        const val UPDATE_NODE_PATH = "battery_boss_update"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000

        fun parseUpdateJson(rawJson: String?): AppUpdateInfo? {
            if (rawJson.isNullOrBlank()) return null
            val trimmed = rawJson.trim()
            if (trimmed.equals("null", ignoreCase = true)) return null

            return runCatching {
                val json = JSONObject(trimmed)

                val enabled = readBooleanAny(json, "enabled", "is_enabled", defaultValue = false)
                val mandatory = readBooleanAny(json, "mandatory", "is_mandatory", defaultValue = false)
                val apkUrl = readStringAny(json, "apkUrl", "apk_url", "download_url", "url")
                val version = readStringAny(json, "version", "latest_version", "app_version")
                val title = readStringAny(json, "title", "update_title").ifEmpty { "ABOUT UPDATE" }
                val downloadClicks = readLongAny(json, "download_clicks", "downloadClicks", defaultValue = 0L)

                val description = when (val rawDesc = json.opt("description") ?: json.opt("message")) {
                    is JSONArray -> {
                        buildList {
                            for (i in 0 until rawDesc.length()) {
                                val item = rawDesc.optString(i, "").trim()
                                if (item.isNotEmpty()) add(item)
                            }
                        }.joinToString("\n")
                    }
                    is String -> rawDesc.trim()
                    else -> ""
                }

                AppUpdateInfo(
                    enabled = enabled,
                    version = version,
                    title = title,
                    description = description,
                    apkUrl = apkUrl,
                    mandatory = mandatory,
                    downloadClicks = downloadClicks
                )
            }.onFailure { error ->
                runCatching { Log.w(TAG, "Failed to parse update JSON: ${error.message}") }
            }.getOrNull()
        }

        private fun readBooleanAny(json: JSONObject, vararg keys: String, defaultValue: Boolean): Boolean {
            for (key in keys) {
                if (json.has(key) && !json.isNull(key)) {
                    return when (val value = json.opt(key)) {
                        is Boolean -> value
                        is Number -> value.toInt() != 0
                        is String -> value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
                        else -> defaultValue
                    }
                }
            }
            return defaultValue
        }

        private fun readStringAny(json: JSONObject, vararg keys: String): String {
            for (key in keys) {
                if (json.has(key) && !json.isNull(key)) {
                    val value = json.optString(key, "").trim()
                    if (value.isNotEmpty()) return value
                }
            }
            return ""
        }

        private fun readLongAny(json: JSONObject, vararg keys: String, defaultValue: Long): Long {
            for (key in keys) {
                if (json.has(key) && !json.isNull(key)) {
                    return json.optLong(key, defaultValue)
                }
            }
            return defaultValue
        }

        fun isVersionMismatch(currentVersion: String, serverVersion: String): Boolean {
            val normalizedCurrent = normalizeVersion(currentVersion)
            val normalizedServer = normalizeVersion(serverVersion)
            if (normalizedServer.isEmpty()) return false
            return !normalizedCurrent.equals(normalizedServer, ignoreCase = true)
        }

        private fun normalizeVersion(version: String): String {
            return version.trim().removePrefix("v").removePrefix("V").trim()
        }

        fun isValidDownloadUrl(url: String): Boolean {
            val trimmed = url.trim()
            return trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
        }
    }

    suspend fun fetchUpdate(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): UpdateFetchResult = withContext(Dispatchers.IO) {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return@withContext UpdateFetchResult.UpToDate
        }

        val endpoint = "${databaseBaseUrl.trimEnd('/')}/${nodePath.trim('/')}.json"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext UpdateFetchResult.Error("HTTP $responseCode")
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }

            val info = parseUpdateJson(responseBody)
                ?: return@withContext UpdateFetchResult.UpToDate

            if (!info.enabled) {
                return@withContext UpdateFetchResult.UpToDate
            }

            if (!isVersionMismatch(currentVersion = currentVersion, serverVersion = info.version)) {
                return@withContext UpdateFetchResult.UpToDate
            }

            if (!isValidDownloadUrl(info.apkUrl)) {
                return@withContext UpdateFetchResult.Error("Invalid APK download URL")
            }

            UpdateFetchResult.UpdateAvailable(info)
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Update check skipped due to error: ${e.message}") }
            UpdateFetchResult.Error(e.message ?: "Network error")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun incrementDownloadClicks(): Boolean = withContext(Dispatchers.IO) {
        val endpoint = "${databaseBaseUrl.trimEnd('/')}/${nodePath.trim('/')}.json"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            val payload = """{"download_clicks":{".sv":{"increment":1}}}"""
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            connection.responseCode in 200..299
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Failed to increment download_clicks: ${e.message}") }
            false
        } finally {
            connection?.disconnect()
        }
    }
}
