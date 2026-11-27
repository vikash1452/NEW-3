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
    val releaseDate: String = "",
    val dialogMode: AdminDialogMode = AdminDialogMode.UPDATE,
    val dialogId: String = "",
    val showOnce: Boolean = false,
    val uiConfig: AdminDialogUiConfig = AdminDialogUiConfig(),
    val rawJson: String = ""
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

    val isUpdateLockRequired: Boolean
        get() = dialogMode == AdminDialogMode.UPDATE
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
 * - When `isUpdateLockActive(context) == false` (normal state, announcement-only dialog, or scheduled future release date):
 *   zero effect on app or voice.
 * - When `isUpdateLockActive(context) == true` (newer update live and release date reached):
 *   1. All normal app UI and background battery functions are locked/paused.
 *   2. Whenever the user plugs in the charger, instead of announcing battery percentage,
 *      the voice engine announces ONLY that an app update has arrived.
 */
object UpdateLockManager {
    private const val PREFS_NAME = "battery_boss_update_gate"
    private const val DISMISSED_PREFS_NAME = "battery_boss_admin_dialog_dismissed"
    private const val KEY_LOCK_ACTIVE = "lock_active"
    private const val KEY_VERSION = "cached_version"
    private const val KEY_TITLE = "cached_title"
    private const val KEY_DESCRIPTION = "cached_description"
    private const val KEY_APK_URL = "cached_apk_url"
    private const val KEY_MANDATORY = "cached_mandatory"
    private const val KEY_RELEASE_DATE_MILLIS = "cached_release_date_millis"
    private const val KEY_RELEASE_DATE = "cached_release_date"
    private const val KEY_RAW_JSON = "cached_raw_json"
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
        val rawJson = prefs.getString(KEY_RAW_JSON, "").orEmpty()
        if (rawJson.isNotBlank()) {
            val parsed = UpdateRepository.parseUpdateJson(rawJson)
            if (parsed != null && parsed.dialogMode == AdminDialogMode.UPDATE) {
                return parsed.copy(mandatory = true)
            }
        }
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
            releaseDate = prefs.getString(KEY_RELEASE_DATE, "").orEmpty(),
            dialogMode = AdminDialogMode.UPDATE
        )
    }

    fun syncFromFetchResult(context: Context, result: UpdateFetchResult) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        when (result) {
            is UpdateFetchResult.UpdateAvailable -> {
                if (result.info.isUpdateLockRequired) {
                    prefs.edit()
                        .putBoolean(KEY_LOCK_ACTIVE, true)
                        .putString(KEY_VERSION, result.info.version)
                        .putString(KEY_TITLE, result.info.title)
                        .putString(KEY_DESCRIPTION, result.info.description)
                        .putString(KEY_APK_URL, result.info.apkUrl)
                        .putBoolean(KEY_MANDATORY, true)
                        .putLong(KEY_RELEASE_DATE_MILLIS, result.info.releaseDateMillis)
                        .putString(KEY_RELEASE_DATE, result.info.releaseDate)
                        .putString(KEY_RAW_JSON, result.info.rawJson)
                        .putLong(KEY_LAST_CHECKED_MS, now)
                        .apply()
                } else {
                    // Announcement / custom UI dialog does not lock normal app functions or battery voice
                    prefs.edit()
                        .putBoolean(KEY_LOCK_ACTIVE, false)
                        .putLong(KEY_LAST_CHECKED_MS, now)
                        .apply()
                }
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
                    .remove(KEY_RAW_JSON)
                    .putLong(KEY_LAST_CHECKED_MS, now)
                    .apply()
            }
            is UpdateFetchResult.Error -> {
                // Keep existing persisted lock state unchanged on transient offline/network errors
            }
        }
    }

    fun isDialogDismissed(context: Context, info: AppUpdateInfo): Boolean {
        if (!info.showOnce || info.mandatory || info.isUpdateLockRequired) return false
        val id = info.dialogId.trim().ifEmpty {
            "${info.title}_${info.uiConfig.mediaUrl}_${info.version}".hashCode().toString()
        }
        val prefs = context.applicationContext.getSharedPreferences(DISMISSED_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("dismissed_$id", false)
    }

    fun markDialogDismissed(context: Context, info: AppUpdateInfo) {
        if (!info.showOnce) return
        val id = info.dialogId.trim().ifEmpty {
            "${info.title}_${info.uiConfig.mediaUrl}_${info.version}".hashCode().toString()
        }
        context.applicationContext
            .getSharedPreferences(DISMISSED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dismissed_$id", true)
            .apply()
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
                .remove(KEY_RAW_JSON)
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
 * Fetches app update & admin-controlled dynamic dialog UI metadata directly from Firebase RTDB:
 * https://admin-50dca-default-rtdb.firebaseio.com/battery_boss_update
 *
 * Supports:
 * - Full backward compatibility with existing update fields (`enabled`, `version`, `apk_url`, `release_date`, etc.)
 * - Admin-driven dynamic dialog UI (`ui_config` / `dialog_ui` / root fields): photos, animated GIFs,
 *   streaming videos, built-in animations, custom theme colors, layout styles, dynamic extra buttons,
 *   and ordered `ui_blocks`.
 * - Two dialog modes:
 *   1. `UPDATE` (default): triggers when `version` > installed version & release date reached; locks app & voice.
 *   2. `ANNOUNCEMENT` (`dialog_mode = "announcement"` / `"promo"` / `"custom"` or `show_custom_dialog = true`):
 *      triggers on any version when `enabled == true` & release date reached, with full admin UI & optional dismiss.
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
                val root = JSONObject(trimmed)
                val uiObj = root.optJSONObject("ui_config")
                    ?: root.optJSONObject("dialog_ui")
                    ?: root.optJSONObject("ui")

                val enabled = readBooleanAny(root, uiObj, "enabled", "is_enabled", defaultValue = false)
                val mandatory = readBooleanAny(root, uiObj, "mandatory", "is_mandatory", defaultValue = false)
                val apkUrl = readStringAny(root, uiObj, "apkUrl", "apk_url", "download_url", "url", "primary_url")
                val version = readStringAny(root, uiObj, "version", "latest_version", "app_version")
                val title = readStringAny(root, uiObj, "title", "update_title").ifEmpty { "ABOUT UPDATE" }
                val downloadClicks = readLongAny(root, uiObj, "download_clicks", "downloadClicks", defaultValue = 0L)
                val releaseDateStr = readStringAny(root, uiObj, "releaseDate", "release_date")

                val explicitReleaseMillis = readLongAny(
                    root,
                    uiObj,
                    "releaseDateMillis",
                    "release_date_millis",
                    defaultValue = 0L
                ).coerceAtLeast(0L)

                val resolvedReleaseDateMillis = if (explicitReleaseMillis > 0L) {
                    explicitReleaseMillis
                } else {
                    parseReleaseDateStringToMillis(releaseDateStr)
                }

                val description = parseDescription(root, uiObj)

                val rawDialogMode = readStringAny(root, uiObj, "dialog_mode", "dialogMode", "mode")
                val showCustomFlag = readBooleanAny(
                    root,
                    uiObj,
                    "show_custom_dialog",
                    "showCustomDialog",
                    "announcement_only",
                    "announcementOnly",
                    defaultValue = false
                )
                val dialogMode = when {
                    rawDialogMode.isNotEmpty() -> AdminDialogMode.fromRaw(rawDialogMode)
                    showCustomFlag -> AdminDialogMode.ANNOUNCEMENT
                    else -> AdminDialogMode.UPDATE
                }

                val dialogId = readStringAny(root, uiObj, "dialog_id", "dialogId", "campaign_id")
                val showOnce = readBooleanAny(root, uiObj, "show_once", "showOnce", defaultValue = false)

                val uiConfig = parseUiConfig(root, uiObj, apkUrl, dialogMode, mandatory)

                AppUpdateInfo(
                    enabled = enabled,
                    version = version,
                    title = title,
                    description = description,
                    apkUrl = apkUrl,
                    mandatory = mandatory,
                    downloadClicks = downloadClicks,
                    releaseDateMillis = resolvedReleaseDateMillis,
                    releaseDate = releaseDateStr,
                    dialogMode = dialogMode,
                    dialogId = dialogId,
                    showOnce = showOnce,
                    uiConfig = uiConfig,
                    rawJson = trimmed
                )
            }.onFailure { error ->
                runCatching { Log.w(TAG, "Failed to parse update JSON: ${error.message}") }
            }.getOrNull()
        }

        private fun parseDescription(root: JSONObject, uiObj: JSONObject?): String {
            val rawDesc = uiObj?.opt("description")
                ?: uiObj?.opt("message")
                ?: root.opt("description")
                ?: root.opt("message")
            return when (rawDesc) {
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
        }

        private fun parseUiConfig(
            root: JSONObject,
            uiObj: JSONObject?,
            apkUrl: String,
            dialogMode: AdminDialogMode,
            mandatory: Boolean
        ): AdminDialogUiConfig {
            val mediaObj = uiObj?.optJSONObject("media") ?: root.optJSONObject("media")

            val videoUrl = readStringAny(root, uiObj, mediaObj, "video_url", "videoUrl")
            val gifUrl = readStringAny(root, uiObj, mediaObj, "gif_url", "gifUrl")
            val imageUrl = readStringAny(root, uiObj, mediaObj, "image_url", "imageUrl", "photo_url", "photoUrl", "banner_url", "bannerUrl")
            val genericMediaUrl = readStringAny(root, uiObj, mediaObj, "media_url", "mediaUrl")

            val rawMediaType = readStringAny(root, uiObj, mediaObj, "media_type", "mediaType")
            val resolvedMediaUrl = when {
                genericMediaUrl.isNotEmpty() -> genericMediaUrl
                rawMediaType.equals("video", ignoreCase = true) && videoUrl.isNotEmpty() -> videoUrl
                rawMediaType.equals("gif", ignoreCase = true) && gifUrl.isNotEmpty() -> gifUrl
                videoUrl.isNotEmpty() -> videoUrl
                gifUrl.isNotEmpty() -> gifUrl
                imageUrl.isNotEmpty() -> imageUrl
                else -> ""
            }

            val parsedMediaType = if (rawMediaType.isNotEmpty()) {
                AdminMediaType.fromRaw(rawMediaType)
            } else {
                when {
                    videoUrl.isNotEmpty() && genericMediaUrl.isEmpty() -> AdminMediaType.VIDEO
                    gifUrl.isNotEmpty() && genericMediaUrl.isEmpty() -> AdminMediaType.GIF
                    else -> AdminMediaType.AUTO
                }
            }

            val rawLayoutStyle = readStringAny(root, uiObj, "layout_style", "layoutStyle", "header_style", "headerStyle")
            val rawEntrance = readStringAny(root, uiObj, "entrance_animation", "entranceAnimation")
            val rawAnimation = readStringAny(root, uiObj, "animation_type", "animationType", "animation")

            val showNotNowPresent = hasAnyKey(root, uiObj, "show_not_now", "showNotNow", "show_dismiss_button", "showDismissButton")
            val showDismissValue = if (showNotNowPresent) {
                readBooleanAny(root, uiObj, "show_not_now", "showNotNow", "show_dismiss_button", "showDismissButton", defaultValue = !mandatory)
            } else {
                null
            }

            val primaryBtnObj = uiObj?.optJSONObject("primary_button") ?: root.optJSONObject("primary_button")
            val primaryBtnUrl = readStringAny(root, uiObj, primaryBtnObj, "primary_button_url", "primaryButtonUrl", "button_url", "buttonUrl")
                .ifEmpty { apkUrl }
            val hasExplicitShowPrimary = hasAnyKey(root, uiObj, primaryBtnObj, "show_primary_button", "showPrimaryButton")
            val showPrimaryButton = if (hasExplicitShowPrimary) {
                readBooleanAny(root, uiObj, "show_primary_button", "showPrimaryButton", defaultValue = true)
            } else {
                dialogMode == AdminDialogMode.UPDATE || primaryBtnUrl.isNotBlank() ||
                    readStringAny(root, uiObj, primaryBtnObj, "primary_button_text", "primaryButtonText", "button_text", "buttonText", "cta_text").isNotBlank()
            }

            val buttonsArray = uiObj?.optJSONArray("buttons")
                ?: uiObj?.optJSONArray("extra_buttons")
                ?: uiObj?.optJSONArray("custom_buttons")
                ?: root.optJSONArray("buttons")
                ?: root.optJSONArray("extra_buttons")
                ?: root.optJSONArray("custom_buttons")
            val parsedButtons = parseButtonsArray(buttonsArray)

            val blocksArray = uiObj?.optJSONArray("ui_blocks")
                ?: uiObj?.optJSONArray("blocks")
                ?: uiObj?.optJSONArray("sections")
                ?: root.optJSONArray("ui_blocks")
                ?: root.optJSONArray("blocks")
                ?: root.optJSONArray("sections")
            val parsedBlocks = parseUiBlocksArray(blocksArray)

            return AdminDialogUiConfig(
                layoutStyle = AdminDialogLayoutStyle.fromRaw(rawLayoutStyle),
                entranceAnimation = AdminEntranceAnimation.fromRaw(rawEntrance),
                animationType = AdminAnimationType.fromRaw(rawAnimation),
                showHeaderWave = readBooleanAny(root, uiObj, "show_header_wave", "showHeaderWave", defaultValue = true),
                showSparkles = readBooleanAny(root, uiObj, "show_sparkles", "showSparkles", defaultValue = true),
                showConfetti = readBooleanAny(root, uiObj, "show_confetti", "showConfetti", defaultValue = false),
                titleAlign = readStringAny(root, uiObj, "title_align", "titleAlign").ifEmpty { "start" },

                backdropColorHex = readStringAny(root, uiObj, "backdrop_color", "backdropColor", "bg_color", "background_color"),
                backdropGradientEndHex = readStringAny(root, uiObj, "backdrop_gradient_end", "backdropGradientEnd", "bg_gradient_end"),
                backdropImageUrl = readStringAny(root, uiObj, "backdrop_image_url", "backdropImageUrl", "background_image_url"),
                backdropDimAlpha = readFloatAny(root, uiObj, "backdrop_dim_alpha", "backdropDimAlpha", defaultValue = 0.28f).coerceIn(0f, 0.92f),
                cardColorHex = readStringAny(root, uiObj, "card_color", "cardColor", "dialog_color"),
                cardGradientEndHex = readStringAny(root, uiObj, "card_gradient_end", "cardGradientEnd"),
                cardBorderColorHex = readStringAny(root, uiObj, "card_border_color", "cardBorderColor", "border_color"),
                cardCornerRadiusDp = readIntAny(root, uiObj, "card_corner_radius", "cardCornerRadius", defaultValue = 24).coerceIn(0, 44),
                headerColorHex = readStringAny(root, uiObj, "header_color", "headerColor", "wave_color"),
                headerGradientEndHex = readStringAny(root, uiObj, "header_gradient_end", "headerGradientEnd"),
                titleColorHex = readStringAny(root, uiObj, "title_color", "titleColor"),
                subtitleColorHex = readStringAny(root, uiObj, "subtitle_color", "subtitleColor"),
                descriptionColorHex = readStringAny(root, uiObj, "description_color", "descriptionColor", "text_color"),
                versionColorHex = readStringAny(root, uiObj, "version_color", "versionColor"),
                accentColorHex = readStringAny(root, uiObj, "accent_color", "accentColor"),

                subtitle = readStringAny(root, uiObj, "subtitle", "sub_title"),
                badgeText = readStringAny(root, uiObj, "badge_text", "badgeText", "tag"),
                badgeColorHex = readStringAny(root, uiObj, "badge_color", "badgeColor"),
                badgeTextColorHex = readStringAny(root, uiObj, "badge_text_color", "badgeTextColor"),
                highlightTitle = readStringAny(root, uiObj, "highlight_title", "highlightTitle"),
                highlightText = readStringAny(root, uiObj, "highlight_text", "highlightText", "notice_text"),
                highlightBgColorHex = readStringAny(root, uiObj, "highlight_bg_color", "highlightBgColor"),
                highlightTextColorHex = readStringAny(root, uiObj, "highlight_text_color", "highlightTextColor"),
                footerText = readStringAny(root, uiObj, "footer_text", "footerText"),
                footerColorHex = readStringAny(root, uiObj, "footer_color", "footerColor"),

                mediaType = parsedMediaType,
                mediaUrl = resolvedMediaUrl,
                thumbnailUrl = readStringAny(root, uiObj, mediaObj, "video_thumbnail_url", "thumbnail_url", "thumbnailUrl", "poster_url"),
                mediaHeightDp = readIntAny(root, uiObj, mediaObj, "media_height_dp", "mediaHeightDp", "media_height", defaultValue = 200).coerceIn(90, 460),
                mediaCornerRadiusDp = readIntAny(root, uiObj, mediaObj, "media_corner_radius_dp", "mediaCornerRadiusDp", defaultValue = 16).coerceIn(0, 36),
                mediaContentScale = readStringAny(root, uiObj, mediaObj, "media_content_scale", "mediaContentScale", "content_scale").ifEmpty { "crop" },
                videoAutoplay = readBooleanAny(root, uiObj, mediaObj, "video_autoplay", "videoAutoplay", "autoplay", defaultValue = true),
                videoLoop = readBooleanAny(root, uiObj, mediaObj, "video_loop", "videoLoop", "loop", defaultValue = true),
                videoMuted = readBooleanAny(root, uiObj, mediaObj, "video_muted", "videoMuted", "muted", defaultValue = false),
                videoShowControls = readBooleanAny(root, uiObj, mediaObj, "video_show_controls", "videoShowControls", "show_controls", defaultValue = true),

                showPrimaryButton = showPrimaryButton,
                primaryButtonText = readStringAny(root, uiObj, primaryBtnObj, "primary_button_text", "primaryButtonText", "button_text", "buttonText", "cta_text"),
                primaryButtonColorHex = readStringAny(root, uiObj, primaryBtnObj, "primary_button_color", "primaryButtonColor", "button_color", "buttonColor"),
                primaryButtonGradientEndHex = readStringAny(root, uiObj, primaryBtnObj, "primary_button_gradient_end", "primaryButtonGradientEnd", "button_gradient_end"),
                primaryButtonTextColorHex = readStringAny(root, uiObj, primaryBtnObj, "primary_button_text_color", "primaryButtonTextColor", "button_text_color"),
                primaryButtonIcon = readStringAny(root, uiObj, primaryBtnObj, "primary_button_icon", "primaryButtonIcon", "button_icon"),
                primaryButtonAction = AdminButtonAction.fromRaw(
                    readStringAny(root, uiObj, primaryBtnObj, "primary_button_action", "primaryButtonAction", "button_action")
                ),
                primaryButtonUrl = primaryBtnUrl,
                primaryButtonPulse = readBooleanAny(root, uiObj, primaryBtnObj, "primary_button_pulse", "primaryButtonPulse", "button_pulse", defaultValue = false),

                showDismissButton = showDismissValue,
                dismissButtonText = readStringAny(root, uiObj, "not_now_text", "notNowText", "dismiss_button_text", "dismissButtonText"),
                dismissButtonColorHex = readStringAny(root, uiObj, "not_now_color", "notNowColor", "dismiss_button_color", "dismissButtonColor"),

                buttons = parsedButtons,
                blocks = parsedBlocks
            )
        }

        private fun parseButtonsArray(array: JSONArray?): List<AdminDialogButton> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val parsed = parseButtonObject(obj, fallbackIndex = i)
                    if (parsed != null) add(parsed)
                }
            }
        }

        private fun parseButtonObject(obj: JSONObject, fallbackIndex: Int = 0): AdminDialogButton? {
            val text = readStringFlat(obj, "text", "label", "title")
            if (text.isEmpty()) return null
            return AdminDialogButton(
                id = readStringFlat(obj, "id", "key").ifEmpty { "btn_$fallbackIndex" },
                text = text,
                url = readStringFlat(obj, "url", "value", "action_value", "link", "target"),
                action = AdminButtonAction.fromRaw(readStringFlat(obj, "action", "type")),
                style = AdminButtonStyle.fromRaw(readStringFlat(obj, "style", "variant")),
                bgColorHex = readStringFlat(obj, "bg_color", "bgColor", "color"),
                gradientEndHex = readStringFlat(obj, "gradient_end", "gradientEnd"),
                textColorHex = readStringFlat(obj, "text_color", "textColor"),
                iconName = readStringFlat(obj, "icon", "icon_name", "iconName"),
                cornerRadiusDp = readIntFlat(obj, "corner_radius_dp", "cornerRadiusDp", defaultValue = 50).coerceIn(4, 50),
                rowGroup = readStringFlat(obj, "row_group", "rowGroup", "group"),
                shareText = readStringFlat(obj, "share_text", "shareText")
            )
        }

        private fun parseUiBlocksArray(array: JSONArray?): List<AdminUiBlock> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val type = readStringFlat(obj, "type", "block_type", "kind").lowercase()
                    if (type.isEmpty()) continue

                    val itemsList = buildList {
                        val arr = obj.optJSONArray("items")
                        if (arr != null) {
                            for (j in 0 until arr.length()) {
                                val item = arr.optString(j, "").trim()
                                if (item.isNotEmpty()) add(item)
                            }
                        }
                    }

                    val singleButton = obj.optJSONObject("button")?.let { parseButtonObject(it, i) }
                        ?: if (type == "button") parseButtonObject(obj, i) else null
                    val rowButtons = parseButtonsArray(obj.optJSONArray("buttons"))

                    add(
                        AdminUiBlock(
                            type = type,
                            text = readStringFlat(obj, "text", "body", "message"),
                            title = readStringFlat(obj, "title", "heading", "label"),
                            items = itemsList,
                            bgColorHex = readStringFlat(obj, "bg_color", "bgColor", "color"),
                            textColorHex = readStringFlat(obj, "text_color", "textColor"),
                            borderColorHex = readStringFlat(obj, "border_color", "borderColor"),
                            accentColorHex = readStringFlat(obj, "accent_color", "accentColor"),
                            align = readStringFlat(obj, "align", "alignment").ifEmpty { "start" },
                            fontSizeSp = readIntFlat(obj, "font_size", "fontSize", defaultValue = 0),
                            heightDp = readIntFlat(obj, "height_dp", "heightDp", "height", defaultValue = 0),
                            cornerRadiusDp = readIntFlat(obj, "corner_radius_dp", "cornerRadiusDp", defaultValue = 16).coerceIn(0, 44),
                            iconName = readStringFlat(obj, "icon", "icon_name"),
                            mediaType = AdminMediaType.fromRaw(readStringFlat(obj, "media_type", "mediaType")),
                            mediaUrl = readStringFlat(obj, "media_url", "mediaUrl", "url", "image_url", "gif_url", "video_url"),
                            thumbnailUrl = readStringFlat(obj, "thumbnail_url", "thumbnailUrl", "poster_url"),
                            contentScale = readStringFlat(obj, "content_scale", "contentScale").ifEmpty { "crop" },
                            autoplay = readBooleanFlat(obj, "autoplay", "video_autoplay", defaultValue = true),
                            loop = readBooleanFlat(obj, "loop", "video_loop", defaultValue = true),
                            muted = readBooleanFlat(obj, "muted", "video_muted", defaultValue = false),
                            showControls = readBooleanFlat(obj, "show_controls", "video_show_controls", defaultValue = true),
                            animationType = AdminAnimationType.fromRaw(readStringFlat(obj, "animation_type", "animationType", "animation")),
                            showVersion = readBooleanFlat(obj, "show_version", "showVersion", defaultValue = false),
                            button = singleButton,
                            buttons = rowButtons
                        )
                    )
                }
            }
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

        private fun hasAnyKey(root: JSONObject, nested: JSONObject?, vararg keys: String): Boolean {
            return hasAnyKey(root, nested, null, *keys)
        }

        private fun hasAnyKey(root: JSONObject, nested1: JSONObject?, nested2: JSONObject?, vararg keys: String): Boolean {
            for (key in keys) {
                if (nested2 != null && nested2.has(key) && !nested2.isNull(key)) return true
                if (nested1 != null && nested1.has(key) && !nested1.isNull(key)) return true
                if (root.has(key) && !root.isNull(key)) return true
            }
            return false
        }

        private fun readBooleanAny(root: JSONObject, nested: JSONObject?, vararg keys: String, defaultValue: Boolean): Boolean {
            return readBooleanAny(root, nested, null, *keys, defaultValue = defaultValue)
        }

        private fun readBooleanAny(
            root: JSONObject,
            nested1: JSONObject?,
            nested2: JSONObject?,
            vararg keys: String,
            defaultValue: Boolean
        ): Boolean {
            for (obj in listOfNotNull(nested2, nested1, root)) {
                for (key in keys) {
                    if (obj.has(key) && !obj.isNull(key)) {
                        return when (val value = obj.opt(key)) {
                            is Boolean -> value
                            is Number -> value.toInt() != 0
                            is String -> value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
                            else -> defaultValue
                        }
                    }
                }
            }
            return defaultValue
        }

        private fun readStringAny(root: JSONObject, nested: JSONObject?, vararg keys: String): String {
            return readStringAny(root, nested, null, *keys)
        }

        private fun readStringAny(root: JSONObject, nested1: JSONObject?, nested2: JSONObject?, vararg keys: String): String {
            for (obj in listOfNotNull(nested2, nested1, root)) {
                for (key in keys) {
                    if (obj.has(key) && !obj.isNull(key)) {
                        val value = obj.optString(key, "").trim()
                        if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) return value
                    }
                }
            }
            return ""
        }

        private fun readLongAny(root: JSONObject, nested: JSONObject?, vararg keys: String, defaultValue: Long): Long {
            for (obj in listOfNotNull(nested, root)) {
                for (key in keys) {
                    if (obj.has(key) && !obj.isNull(key)) {
                        return when (val value = obj.opt(key)) {
                            is Number -> value.toLong()
                            is String -> value.trim().toLongOrNull() ?: defaultValue
                            else -> defaultValue
                        }
                    }
                }
            }
            return defaultValue
        }

        private fun readIntAny(root: JSONObject, nested: JSONObject?, vararg keys: String, defaultValue: Int): Int {
            return readIntAny(root, nested, null, *keys, defaultValue = defaultValue)
        }

        private fun readIntAny(root: JSONObject, nested1: JSONObject?, nested2: JSONObject?, vararg keys: String, defaultValue: Int): Int {
            for (obj in listOfNotNull(nested2, nested1, root)) {
                for (key in keys) {
                    if (obj.has(key) && !obj.isNull(key)) {
                        return when (val value = obj.opt(key)) {
                            is Number -> value.toInt()
                            is String -> value.trim().toIntOrNull() ?: defaultValue
                            else -> defaultValue
                        }
                    }
                }
            }
            return defaultValue
        }

        private fun readFloatAny(root: JSONObject, nested: JSONObject?, vararg keys: String, defaultValue: Float): Float {
            for (obj in listOfNotNull(nested, root)) {
                for (key in keys) {
                    if (obj.has(key) && !obj.isNull(key)) {
                        return when (val value = obj.opt(key)) {
                            is Number -> value.toFloat()
                            is String -> value.trim().toFloatOrNull() ?: defaultValue
                            else -> defaultValue
                        }
                    }
                }
            }
            return defaultValue
        }

        private fun readStringFlat(obj: JSONObject, vararg keys: String): String {
            for (key in keys) {
                if (obj.has(key) && !obj.isNull(key)) {
                    val value = obj.optString(key, "").trim()
                    if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) return value
                }
            }
            return ""
        }

        private fun readIntFlat(obj: JSONObject, vararg keys: String, defaultValue: Int): Int {
            for (key in keys) {
                if (obj.has(key) && !obj.isNull(key)) {
                    return when (val value = obj.opt(key)) {
                        is Number -> value.toInt()
                        is String -> value.trim().toIntOrNull() ?: defaultValue
                        else -> defaultValue
                    }
                }
            }
            return defaultValue
        }

        private fun readBooleanFlat(obj: JSONObject, vararg keys: String, defaultValue: Boolean): Boolean {
            for (key in keys) {
                if (obj.has(key) && !obj.isNull(key)) {
                    return when (val value = obj.opt(key)) {
                        is Boolean -> value
                        is Number -> value.toInt() != 0
                        is String -> value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
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
         * Evaluates whether a parsed [AppUpdateInfo] should trigger the update/custom dialog at [nowMillis].
         * - In `UPDATE` mode: requires `enabled == true`, strictly newer `version`, release date reached, and valid `apkUrl`.
         * - In `ANNOUNCEMENT` mode: requires `enabled == true` and release date reached (any version, optional URL).
         */
        fun evaluateUpdate(
            info: AppUpdateInfo?,
            currentVersion: String = BuildConfig.VERSION_NAME,
            nowMillis: Long = System.currentTimeMillis()
        ): UpdateFetchResult {
            if (info == null || !info.enabled) {
                return UpdateFetchResult.UpToDate
            }

            if (!isReleaseDateReached(releaseDateMillis = info.releaseDateMillis, nowMillis = nowMillis)) {
                return UpdateFetchResult.UpToDate
            }

            if (info.dialogMode == AdminDialogMode.ANNOUNCEMENT) {
                return UpdateFetchResult.UpdateAvailable(info)
            }

            if (!isNewerVersion(currentVersion = currentVersion, serverVersion = info.version)) {
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
        patchIncrement("""{"download_clicks":{".sv":{"increment":1}}}""")
    }

    suspend fun trackButtonClick(buttonId: String): Boolean = withContext(Dispatchers.IO) {
        val safeKey = buttonId.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(48)
            .ifEmpty { "custom_btn" }
        patchIncrement("""{"button_clicks":{"$safeKey":{".sv":{"increment":1}}}}""")
    }

    private fun patchIncrement(payload: String): Boolean {
        val endpoint = "${databaseBaseUrl.trimEnd('/')}/${nodePath.trim('/')}.json"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            connection.responseCode in 200..299
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Failed to increment click telemetry: ${e.message}") }
            false
        } finally {
            connection?.disconnect()
        }
    }
}
