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
import java.text.SimpleDateFormat
import java.util.Locale

data class AppUpdateInfo(
    val enabled: Boolean,
    val version: String,
    val title: String,
    val description: String,
    val apkUrl: String,
    val mandatory: Boolean,
    val downloadClicks: Long = 0L,
    val releaseDateMillis: Long = 0L,
    val releaseDate: String = ""
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
 * - When `isUpdateLockActive(context) == false` (normal state or scheduled future release date):
 *   zero effect on app or voice.
 * - When `isUpdateLockActive(context) == true` (newer update live and release date reached):
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
    private const val KEY_RELEASE_DATE_MILLIS = "cached_release_date_millis"
    private const val KEY_RELEASE_DATE = "cached_release_date"
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

    fun isUpdateLockActive(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LOCK_ACTIVE, false)) return false
        val cachedVersion = prefs.getString(KEY_VERSION, "").orEmpty()
        // Auto-unlock once the installed app version is equal to or newer than the cached remote version
        if (cachedVersion.isNotEmpty() && !UpdateRepository.isNewerVersion(BuildConfig.VERSION_NAME, cachedVersion)) {
            clearLock(context)
            return false
        }
        val cachedReleaseMillis = prefs.getLong(KEY_RELEASE_DATE_MILLIS, 0L)
        if (!UpdateRepository.isReleaseDateReached(cachedReleaseMillis, nowMillis)) {
            return false
        }
        return true
    }

    fun getCachedUpdateInfo(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): AppUpdateInfo? {
        if (!isUpdateLockActive(context, nowMillis)) return null
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
            mandatory = true,
            releaseDateMillis = prefs.getLong(KEY_RELEASE_DATE_MILLIS, 0L),
            releaseDate = prefs.getString(KEY_RELEASE_DATE, "").orEmpty()
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
                    .putLong(KEY_RELEASE_DATE_MILLIS, result.info.releaseDateMillis)
                    .putString(KEY_RELEASE_DATE, result.info.releaseDate)
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
                    .remove(KEY_RELEASE_DATE_MILLIS)
                    .remove(KEY_RELEASE_DATE)
                    .putLong(KEY_LAST_CHECKED_MS, now)
                    .apply()
            }
            is UpdateFetchResult.Error -> {
                // Keep existing persisted lock state unchanged on transient offline/network errors
            }
        }
    }

    fun setUpdateLockForTest(
        context: Context,
        active: Boolean,
        version: String = "99.0",
        releaseDateMillis: Long = 0L
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (active) {
            prefs.edit()
                .putBoolean(KEY_LOCK_ACTIVE, true)
                .putString(KEY_VERSION, version)
                .putString(KEY_TITLE, "ABOUT UPDATE")
                .putString(KEY_DESCRIPTION, "New version available")
                .putString(KEY_APK_URL, "https://example.com/update.apk")
                .putBoolean(KEY_MANDATORY, true)
                .putLong(KEY_RELEASE_DATE_MILLIS, releaseDateMillis)
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
 * - Supports both camelCase (`enabled`, `apkUrl`, `mandatory`, `releaseDateMillis`) and
 *   snake_case (`is_enabled`, `apk_url`, `is_mandatory`, `release_date_millis`, `release_date`).
 * - Triggers an update ONLY when:
 *   1. `enabled == true`,
 *   2. remote `version` is strictly newer than the installed app version, AND
 *   3. `releaseDateMillis == 0L` OR `nowMillis >= releaseDateMillis`.
 * - Prioritizes `releaseDateMillis` over date strings, with safe fallback parsing for `release_date`
 *   when `releaseDateMillis` is missing or `0L`.
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

        private val FALLBACK_DATE_PATTERNS = listOf(
            "dd/MM/yyyy",
            "d/M/yyyy",
            "dd MMM yyyy",
            "d MMM yyyy",
            "dd-MM-yyyy",
            "yyyy-MM-dd",
            "dd MMMM yyyy",
            "MMM dd, yyyy"
        )

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
                val releaseDateStr = readStringAny(json, "releaseDate", "release_date")

                // Priority 1: explicit releaseDateMillis (camelCase or snake_case)
                val explicitReleaseMillis = readLongAny(
                    json,
                    "releaseDateMillis",
                    "release_date_millis",
                    defaultValue = 0L
                ).coerceAtLeast(0L)

                // Priority 2: safe fallback parsing of date string only when releaseDateMillis is missing/0L
                val resolvedReleaseDateMillis = if (explicitReleaseMillis > 0L) {
                    explicitReleaseMillis
                } else {
                    parseReleaseDateStringToMillis(releaseDateStr)
                }

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
                    downloadClicks = downloadClicks,
                    releaseDateMillis = resolvedReleaseDateMillis,
                    releaseDate = releaseDateStr
                )
            }.onFailure { error ->
                runCatching { Log.w(TAG, "Failed to parse update JSON: ${error.message}") }
            }.getOrNull()
        }

        /**
         * Safely parses common date strings (e.g., "01/12/2026", "01 Dec 2026", "2026-12-01")
         * to epoch milliseconds (start of that day in local time). Returns 0L if blank or invalid.
         */
        fun parseReleaseDateStringToMillis(dateStr: String?): Long {
            val cleaned = dateStr?.trim().orEmpty()
            if (cleaned.isEmpty()) return 0L
            for (pattern in FALLBACK_DATE_PATTERNS) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                    }.parse(cleaned)?.time
                }.getOrNull()
                if (parsed != null && parsed > 0L) {
                    return parsed
                }
            }
            return 0L
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
                    return when (val value = json.opt(key)) {
                        is Number -> value.toLong()
                        is String -> value.trim().toLongOrNull() ?: defaultValue
                        else -> defaultValue
                    }
                }
            }
            return defaultValue
        }

        /**
         * Semantic version comparator returning true ONLY when [serverVersion] is strictly newer
         * than [currentVersion] (e.g., "2.0" > "1.0", "1.0.1" > "1.0", "2.10" > "2.2", "v2.0" > "1.0").
         */
        fun isNewerVersion(currentVersion: String, serverVersion: String): Boolean {
            val currentParts = normalizeVersionParts(currentVersion)
            val serverParts = normalizeVersionParts(serverVersion)
            if (serverParts.isEmpty()) return false
            if (currentParts.isEmpty()) return true

            val maxLen = maxOf(currentParts.size, serverParts.size)
            for (i in 0 until maxLen) {
                val cur = currentParts.getOrElse(i) { 0 }
                val srv = serverParts.getOrElse(i) { 0 }
                if (srv != cur) {
                    return srv > cur
                }
            }
            return false
        }

        private fun normalizeVersionParts(version: String): List<Int> {
            val cleaned = version.trim().removePrefix("v").removePrefix("V").trim()
            if (cleaned.isEmpty()) return emptyList()
            val numericCore = cleaned.substringBefore('-').substringBefore('+')
            return numericCore
                .split('.')
                .mapNotNull { part -> part.filter { it.isDigit() }.toIntOrNull() }
        }

        /**
         * Returns true when no release date is scheduled (`releaseDateMillis <= 0L`)
         * OR the scheduled release timestamp has been reached (`nowMillis >= releaseDateMillis`).
         */
        fun isReleaseDateReached(
            releaseDateMillis: Long,
            nowMillis: Long = System.currentTimeMillis()
        ): Boolean {
            return releaseDateMillis <= 0L || nowMillis >= releaseDateMillis
        }

        fun isValidDownloadUrl(url: String): Boolean {
            val trimmed = url.trim()
            return trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
        }

        /**
         * Evaluates whether a parsed [AppUpdateInfo] should trigger the update dialog at [nowMillis].
         * Requires BOTH:
         * 1. Remote version is strictly newer than [currentVersion], AND
         * 2. Either `releaseDateMillis == 0L` OR `nowMillis >= releaseDateMillis`.
         */
        fun evaluateUpdate(
            info: AppUpdateInfo?,
            currentVersion: String = BuildConfig.VERSION_NAME,
            nowMillis: Long = System.currentTimeMillis()
        ): UpdateFetchResult {
            if (info == null || !info.enabled) {
                return UpdateFetchResult.UpToDate
            }

            if (!isNewerVersion(currentVersion = currentVersion, serverVersion = info.version)) {
                return UpdateFetchResult.UpToDate
            }

            if (!isReleaseDateReached(releaseDateMillis = info.releaseDateMillis, nowMillis = nowMillis)) {
                return UpdateFetchResult.UpToDate
            }

            if (!isValidDownloadUrl(info.apkUrl)) {
                return UpdateFetchResult.Error("Invalid APK download URL")
            }

            return UpdateFetchResult.UpdateAvailable(info)
        }
    }

    suspend fun fetchUpdate(
        currentVersion: String = BuildConfig.VERSION_NAME,
        nowMillis: Long = System.currentTimeMillis()
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
            evaluateUpdate(
                info = info,
                currentVersion = currentVersion,
                nowMillis = nowMillis
            )
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
