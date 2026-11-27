package com.example.update

import androidx.compose.runtime.Immutable

enum class AdminDialogMode {
    UPDATE,
    ANNOUNCEMENT;

    companion object {
        fun fromRaw(raw: String?): AdminDialogMode {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "announcement", "promo", "custom", "dialog", "banner", "ui_only", "notice" -> ANNOUNCEMENT
                else -> UPDATE
            }
        }
    }
}

enum class AdminDialogLayoutStyle {
    AUTO,
    WAVE_ROCKET,
    HERO_MEDIA,
    FULL_CARD_MEDIA,
    MINIMAL,
    FULLSCREEN_POSTER;

    companion object {
        fun fromRaw(raw: String?): AdminDialogLayoutStyle {
            return when (raw?.trim()?.lowercase().orEmpty()) {
                "wave_rocket", "wave", "rocket", "classic" -> WAVE_ROCKET
                "hero_media", "hero", "banner", "media_top" -> HERO_MEDIA
                "full_card_media", "full_card", "cinematic", "overlay_card" -> FULL_CARD_MEDIA
                "minimal", "clean", "card", "compact" -> MINIMAL
                "fullscreen_poster", "fullscreen", "poster", "full_screen" -> FULLSCREEN_POSTER
                else -> AUTO
            }
        }
    }
}

enum class AdminMediaType {
    AUTO,
    NONE,
    ANIMATION,
    IMAGE,
    GIF,
    VIDEO;

    companion object {
        fun fromRaw(raw: String?): AdminMediaType {
            return when (raw?.trim()?.lowercase().orEmpty()) {
                "none", "off", "hidden" -> NONE
                "animation", "anim", "vector", "lottie" -> ANIMATION
                "image", "photo", "img", "picture", "png", "jpg", "jpeg", "webp" -> IMAGE
                "gif", "animated_gif" -> GIF
                "video", "mp4", "stream", "movie" -> VIDEO
                else -> AUTO
            }
        }
    }
}

enum class AdminAnimationType {
    ROCKET,
    PULSE_BADGE,
    CONFETTI,
    SPARKLE_WAVE,
    GLOW_RING,
    BOUNCE_ICON,
    NONE;

    companion object {
        fun fromRaw(raw: String?): AdminAnimationType {
            return when (raw?.trim()?.lowercase().orEmpty()) {
                "pulse_badge", "pulse", "radar" -> PULSE_BADGE
                "confetti", "celebration", "party" -> CONFETTI
                "sparkle_wave", "sparkle", "wave", "aurora" -> SPARKLE_WAVE
                "glow_ring", "ring", "orbit", "orbital" -> GLOW_RING
                "bounce_icon", "bounce", "gift", "star" -> BOUNCE_ICON
                "none", "off" -> NONE
                else -> ROCKET
            }
        }
    }
}

enum class AdminEntranceAnimation {
    SPRING_POP,
    SLIDE_UP,
    FADE_ZOOM,
    FLIP_IN,
    NONE;

    companion object {
        fun fromRaw(raw: String?): AdminEntranceAnimation {
            return when (raw?.trim()?.lowercase().orEmpty()) {
                "slide_up", "slide", "bottom_up" -> SLIDE_UP
                "fade_zoom", "fade", "zoom" -> FADE_ZOOM
                "flip_in", "flip", "rotate" -> FLIP_IN
                "none", "instant" -> NONE
                else -> SPRING_POP
            }
        }
    }
}

enum class AdminButtonStyle {
    FILLED,
    OUTLINED,
    TONAL,
    TEXT,
    GRADIENT;

    companion object {
        fun fromRaw(raw: String?): AdminButtonStyle {
            return when (raw?.trim()?.lowercase().orEmpty()) {
                "outlined", "outline", "border" -> OUTLINED
                "tonal", "soft", "secondary" -> TONAL
                "text", "link", "ghost" -> TEXT
                "gradient", "grad" -> GRADIENT
                else -> FILLED
            }
        }
    }
}

enum class AdminButtonAction {
    OPEN_URL,
    DISMISS,
    SHARE,
    COPY,
    OPEN_TAB;

    companion object {
        fun fromRaw(raw: String?): AdminButtonAction {
            return when (raw?.trim()?.lowercase().orEmpty()) {
                "dismiss", "close", "cancel", "not_now" -> DISMISS
                "share", "share_text", "share_app" -> SHARE
                "copy", "copy_text", "clipboard" -> COPY
                "open_tab", "navigate", "tab", "screen" -> OPEN_TAB
                else -> OPEN_URL
            }
        }
    }
}

@Immutable
data class AdminDialogButton(
    val id: String = "",
    val text: String = "",
    val url: String = "",
    val action: AdminButtonAction = AdminButtonAction.OPEN_URL,
    val style: AdminButtonStyle = AdminButtonStyle.FILLED,
    val bgColorHex: String = "",
    val gradientEndHex: String = "",
    val textColorHex: String = "",
    val iconName: String = "",
    val cornerRadiusDp: Int = 50,
    val rowGroup: String = "",
    val shareText: String = ""
)

@Immutable
data class AdminUiBlock(
    val type: String = "",
    val text: String = "",
    val title: String = "",
    val items: List<String> = emptyList(),
    val bgColorHex: String = "",
    val textColorHex: String = "",
    val borderColorHex: String = "",
    val accentColorHex: String = "",
    val align: String = "start",
    val fontSizeSp: Int = 0,
    val heightDp: Int = 0,
    val cornerRadiusDp: Int = 16,
    val iconName: String = "",
    val mediaType: AdminMediaType = AdminMediaType.AUTO,
    val mediaUrl: String = "",
    val thumbnailUrl: String = "",
    val contentScale: String = "crop",
    val autoplay: Boolean = true,
    val loop: Boolean = true,
    val muted: Boolean = false,
    val showControls: Boolean = true,
    val animationType: AdminAnimationType = AdminAnimationType.ROCKET,
    val showVersion: Boolean = false,
    val button: AdminDialogButton? = null,
    val buttons: List<AdminDialogButton> = emptyList()
)

@Immutable
data class AdminDialogUiConfig(
    // Layout & Motion
    val layoutStyle: AdminDialogLayoutStyle = AdminDialogLayoutStyle.AUTO,
    val entranceAnimation: AdminEntranceAnimation = AdminEntranceAnimation.SPRING_POP,
    val animationType: AdminAnimationType = AdminAnimationType.ROCKET,
    val showHeaderWave: Boolean = true,
    val showSparkles: Boolean = true,
    val showConfetti: Boolean = false,
    val titleAlign: String = "start",

    // Backdrop & Card Theme
    val backdropColorHex: String = "",
    val backdropGradientEndHex: String = "",
    val backdropImageUrl: String = "",
    val backdropDimAlpha: Float = 0.28f,
    val cardColorHex: String = "",
    val cardGradientEndHex: String = "",
    val cardBorderColorHex: String = "",
    val cardCornerRadiusDp: Int = 24,
    val headerColorHex: String = "",
    val headerGradientEndHex: String = "",
    val titleColorHex: String = "",
    val subtitleColorHex: String = "",
    val descriptionColorHex: String = "",
    val versionColorHex: String = "",
    val accentColorHex: String = "",

    // Texts, Badge, Highlight Box & Footer
    val subtitle: String = "",
    val badgeText: String = "",
    val badgeColorHex: String = "",
    val badgeTextColorHex: String = "",
    val highlightTitle: String = "",
    val highlightText: String = "",
    val highlightBgColorHex: String = "",
    val highlightTextColorHex: String = "",
    val footerText: String = "",
    val footerColorHex: String = "",

    // Media (Photo / GIF / Video)
    val mediaType: AdminMediaType = AdminMediaType.AUTO,
    val mediaUrl: String = "",
    val thumbnailUrl: String = "",
    val mediaHeightDp: Int = 200,
    val mediaCornerRadiusDp: Int = 16,
    val mediaContentScale: String = "crop",
    val videoAutoplay: Boolean = true,
    val videoLoop: Boolean = true,
    val videoMuted: Boolean = false,
    val videoShowControls: Boolean = true,

    // Primary & Dismiss Buttons
    val showPrimaryButton: Boolean = true,
    val primaryButtonText: String = "",
    val primaryButtonColorHex: String = "",
    val primaryButtonGradientEndHex: String = "",
    val primaryButtonTextColorHex: String = "",
    val primaryButtonIcon: String = "",
    val primaryButtonAction: AdminButtonAction = AdminButtonAction.OPEN_URL,
    val primaryButtonUrl: String = "",
    val primaryButtonPulse: Boolean = false,
    val showDismissButton: Boolean? = null,
    val dismissButtonText: String = "",
    val dismissButtonColorHex: String = "",

    // Dynamic Custom Buttons & Optional Server-Driven Ordered Blocks
    val buttons: List<AdminDialogButton> = emptyList(),
    val blocks: List<AdminUiBlock> = emptyList()
) {
    fun resolveEffectiveMediaType(): AdminMediaType {
        if (mediaType != AdminMediaType.AUTO) {
            return if ((mediaType == AdminMediaType.IMAGE || mediaType == AdminMediaType.GIF || mediaType == AdminMediaType.VIDEO) && mediaUrl.isBlank()) {
                AdminMediaType.ANIMATION
            } else {
                mediaType
            }
        }
        return inferMediaTypeFromUrl(mediaUrl)
    }

    fun resolveEffectiveLayoutStyle(): AdminDialogLayoutStyle {
        if (layoutStyle != AdminDialogLayoutStyle.AUTO) return layoutStyle
        val effectiveMedia = resolveEffectiveMediaType()
        return if (effectiveMedia == AdminMediaType.IMAGE || effectiveMedia == AdminMediaType.GIF || effectiveMedia == AdminMediaType.VIDEO) {
            AdminDialogLayoutStyle.HERO_MEDIA
        } else {
            AdminDialogLayoutStyle.WAVE_ROCKET
        }
    }

    companion object {
        fun inferMediaTypeFromUrl(rawUrl: String): AdminMediaType {
            val cleaned = rawUrl.trim().lowercase().substringBefore('?').substringBefore('#')
            if (cleaned.isEmpty()) return AdminMediaType.ANIMATION
            return when {
                cleaned.endsWith(".mp4") ||
                    cleaned.endsWith(".webm") ||
                    cleaned.endsWith(".m3u8") ||
                    cleaned.endsWith(".mov") ||
                    cleaned.endsWith(".mkv") ||
                    cleaned.endsWith(".3gp") -> AdminMediaType.VIDEO
                cleaned.endsWith(".gif") -> AdminMediaType.GIF
                cleaned.startsWith("http://") || cleaned.startsWith("https://") -> AdminMediaType.IMAGE
                else -> AdminMediaType.ANIMATION
            }
        }
    }
}
