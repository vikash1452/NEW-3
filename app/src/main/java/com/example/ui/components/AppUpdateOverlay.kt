package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.update.AdminAnimationType
import com.example.update.AdminButtonAction
import com.example.update.AdminButtonStyle
import com.example.update.AdminDialogButton
import com.example.update.AdminDialogLayoutStyle
import com.example.update.AdminDialogMode
import com.example.update.AdminDialogUiConfig
import com.example.update.AdminEntranceAnimation
import com.example.update.AdminMediaType
import com.example.update.AdminUiBlock
import com.example.update.AppUpdateInfo
import kotlinx.coroutines.launch

// Signature default palette when Admin does not override colors
private val DefaultPastelPurpleBackdrop = Color(0xFFE0D4FF)
private val DefaultPastelBackdropMid = Color(0xFFE7DEFF)
private val DefaultPastelCircleLight = Color(0xFFF3EDFF)
private val DefaultPastelCircleAccent = Color(0xFFCBB8FF)
private val DefaultHeaderYellowMain = Color(0xFFFFD54F)
private val DefaultHeaderYellowLight = Color(0xFFFFE57F)
private val DefaultHeaderYellowDeep = Color(0xFFFFCA28)
private val DefaultAccentRed = Color(0xFFFF5252)
private val DefaultTitleBlack = Color(0xFF1A1A2E)
private val DefaultDescriptionGray = Color(0xFF5F6368)
private val DefaultNotNowGray = Color(0xFF7A7E8A)

@Composable
fun AppUpdateOverlay(
    updateInfo: AppUpdateInfo,
    onUpdateClick: (String) -> Unit,
    onDismiss: () -> Unit,
    onButtonAction: (AdminDialogButton) -> Unit = { button ->
        when (button.action) {
            AdminButtonAction.DISMISS -> onDismiss()
            AdminButtonAction.OPEN_URL -> onUpdateClick(button.url)
            else -> onUpdateClick(button.url)
        }
    },
    modifier: Modifier = Modifier
) {
    val ui = updateInfo.uiConfig
    val effectiveLayout = ui.resolveEffectiveLayoutStyle()
    val effectiveMedia = ui.resolveEffectiveMediaType()

    val isDarkPosterMode = effectiveLayout == AdminDialogLayoutStyle.FULL_CARD_MEDIA ||
        effectiveLayout == AdminDialogLayoutStyle.FULLSCREEN_POSTER

    val accentColor = parseAdminColor(ui.accentColorHex, DefaultAccentRed)
    val backdropTop = parseAdminColor(
        ui.backdropColorHex,
        if (isDarkPosterMode) Color(0xFF0E1220) else DefaultPastelPurpleBackdrop
    )
    val backdropBottom = parseAdminColor(
        ui.backdropGradientEndHex,
        if (ui.backdropColorHex.isNotBlank()) backdropTop.copy(alpha = 0.92f)
        else if (isDarkPosterMode) Color(0xFF161C30) else DefaultPastelBackdropMid
    )
    val cardBgStart = parseAdminColor(
        ui.cardColorHex,
        if (effectiveLayout == AdminDialogLayoutStyle.FULLSCREEN_POSTER) Color(0xEE141928)
        else if (effectiveLayout == AdminDialogLayoutStyle.FULL_CARD_MEDIA) Color(0xFF141928)
        else Color.White
    )
    val cardBgEnd = parseAdminColor(ui.cardGradientEndHex, cardBgStart)
    val cardIsDark = cardBgStart.luminanceOrDarkFallback() < 0.45f
    val titleColor = parseAdminColor(
        ui.titleColorHex,
        if (cardIsDark) Color.White else DefaultTitleBlack
    )
    val subtitleColor = parseAdminColor(
        ui.subtitleColorHex,
        if (cardIsDark) Color(0xFFB8C2D9) else DefaultDescriptionGray
    )
    val descriptionColor = parseAdminColor(
        ui.descriptionColorHex,
        if (cardIsDark) Color(0xFFD5DCEB) else DefaultDescriptionGray
    )
    val versionColor = parseAdminColor(ui.versionColorHex, accentColor)
    val headerTop = parseAdminColor(ui.headerColorHex, DefaultHeaderYellowLight)
    val headerBottom = parseAdminColor(
        ui.headerGradientEndHex,
        if (ui.headerColorHex.isNotBlank()) parseAdminColor(ui.headerColorHex, DefaultHeaderYellowDeep)
        else DefaultHeaderYellowDeep
    )

    val canDismiss = !updateInfo.mandatory && !updateInfo.isUpdateLockRequired
    val showDismissButton = ui.showDismissButton ?: canDismiss

    // Entrance animations driven by Admin `entrance_animation`
    val cardScale = remember(updateInfo.dialogId, ui.entranceAnimation) {
        Animatable(
            when (ui.entranceAnimation) {
                AdminEntranceAnimation.NONE -> 1f
                AdminEntranceAnimation.FADE_ZOOM -> 0.90f
                else -> 0.86f
            }
        )
    }
    val cardAlpha = remember(updateInfo.dialogId, ui.entranceAnimation) {
        Animatable(if (ui.entranceAnimation == AdminEntranceAnimation.NONE) 1f else 0f)
    }
    val cardOffsetY = remember(updateInfo.dialogId, ui.entranceAnimation) {
        Animatable(
            when (ui.entranceAnimation) {
                AdminEntranceAnimation.SLIDE_UP -> 160f
                AdminEntranceAnimation.SPRING_POP -> 40f
                else -> 0f
            }
        )
    }
    val cardRotationX = remember(updateInfo.dialogId, ui.entranceAnimation) {
        Animatable(if (ui.entranceAnimation == AdminEntranceAnimation.FLIP_IN) -22f else 0f)
    }

    LaunchedEffect(updateInfo.dialogId, ui.entranceAnimation) {
        if (ui.entranceAnimation == AdminEntranceAnimation.NONE) return@LaunchedEffect
        launch {
            cardAlpha.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
        launch {
            cardScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        launch {
            cardOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        launch {
            cardRotationX.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "update_overlay_motion")
    val sparklePulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "header_sparkle_pulse"
    )
    val circleDrift by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "backdrop_circle_drift"
    )
    val ctaPulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cta_button_pulse"
    )

    val defaultTitle = stringResource(R.string.update_default_title)
    val resolvedTitle = updateInfo.title.ifBlank { defaultTitle }
    val cardShape = RoundedCornerShape(ui.cardCornerRadiusDp.dp)
    val cardBorder = ui.cardBorderColorHex.takeIf { it.isNotBlank() }?.let {
        BorderStroke(1.5.dp, parseAdminColor(it, accentColor))
    }

    Dialog(
        onDismissRequest = {
            if (canDismiss) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = canDismiss
        )
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(colors = listOf(backdropTop, backdropBottom, backdropTop))
                )
                .testTag("app_update_overlay_root"),
            contentAlignment = if (effectiveLayout == AdminDialogLayoutStyle.FULLSCREEN_POSTER) {
                Alignment.BottomCenter
            } else {
                Alignment.Center
            }
        ) {
            // Optional Fullscreen Backdrop Image / GIF or Fullscreen Poster Video/Image
            if (effectiveLayout == AdminDialogLayoutStyle.FULLSCREEN_POSTER &&
                (effectiveMedia == AdminMediaType.IMAGE || effectiveMedia == AdminMediaType.GIF || effectiveMedia == AdminMediaType.VIDEO) &&
                ui.mediaUrl.isNotBlank()
            ) {
                AdminMediaSlot(
                    mediaType = effectiveMedia,
                    mediaUrl = ui.mediaUrl,
                    thumbnailUrl = ui.thumbnailUrl,
                    animationType = ui.animationType,
                    accentColor = accentColor,
                    contentScaleMode = ui.mediaContentScale,
                    autoplay = ui.videoAutoplay,
                    loop = ui.videoLoop,
                    muted = ui.videoMuted,
                    showControls = ui.videoShowControls,
                    cornerRadiusDp = 0,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = ui.backdropDimAlpha * 0.6f),
                                    Color.Black.copy(alpha = (ui.backdropDimAlpha + 0.35f).coerceAtMost(0.92f))
                                )
                            )
                        )
                )
            } else if (ui.backdropImageUrl.isNotBlank()) {
                val bgMediaType = AdminDialogUiConfig.inferMediaTypeFromUrl(ui.backdropImageUrl)
                AdminImageOrGifView(
                    url = ui.backdropImageUrl,
                    contentScaleMode = "crop",
                    accentColor = accentColor,
                    contentDescriptionText = "Dialog backdrop media",
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = ui.backdropDimAlpha))
                )
                if (bgMediaType == AdminMediaType.NONE) Unit
            } else {
                // Subtle decorative pastel / theme circles on backdrop
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val lightCircle = if (ui.backdropColorHex.isNotBlank()) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        DefaultPastelCircleLight.copy(alpha = 0.58f)
                    }
                    val accentCircle = if (ui.backdropColorHex.isNotBlank()) {
                        accentColor.copy(alpha = 0.16f)
                    } else {
                        DefaultPastelCircleAccent.copy(alpha = 0.48f)
                    }
                    drawCircle(
                        color = lightCircle,
                        radius = w * 0.34f,
                        center = Offset(w * 0.08f, h * 0.12f + circleDrift)
                    )
                    drawCircle(
                        color = accentCircle,
                        radius = w * 0.22f,
                        center = Offset(w * 0.90f, h * 0.16f - circleDrift * 0.8f)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.30f),
                        radius = w * 0.065f,
                        center = Offset(w * 0.78f, h * 0.29f + circleDrift * 1.1f)
                    )
                    drawCircle(
                        color = accentCircle.copy(alpha = 0.35f),
                        radius = w * 0.28f,
                        center = Offset(w * 0.12f, h * 0.86f - circleDrift)
                    )
                    drawCircle(
                        color = lightCircle.copy(alpha = 0.48f),
                        radius = w * 0.30f,
                        center = Offset(w * 0.92f, h * 0.82f + circleDrift * 0.9f)
                    )
                }
            }

            // Optional celebratory confetti overlay
            if (ui.showConfetti || ui.animationType == AdminAnimationType.CONFETTI) {
                AdminConfettiOverlay(accentColor = accentColor)
            }

            val showFloatingShowcaseOnWave = effectiveLayout == AdminDialogLayoutStyle.WAVE_ROCKET &&
                effectiveMedia != AdminMediaType.NONE &&
                ui.animationType != AdminAnimationType.NONE

            val cardTopPadding = if (showFloatingShowcaseOnWave &&
                (effectiveMedia == AdminMediaType.ANIMATION || effectiveMedia == AdminMediaType.AUTO || ui.mediaUrl.isBlank())
            ) {
                54.dp
            } else {
                0.dp
            }

            Box(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .widthIn(max = 410.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = cardScale.value
                        scaleY = cardScale.value
                        alpha = cardAlpha.value
                        translationY = cardOffsetY.value
                        rotationX = cardRotationX.value
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier
                        .padding(top = cardTopPadding)
                        .fillMaxWidth()
                        .shadow(
                            elevation = 22.dp,
                            shape = cardShape,
                            ambientColor = accentColor.copy(alpha = 0.24f),
                            spotColor = accentColor.copy(alpha = 0.24f)
                        )
                        .testTag("app_update_dialog_card"),
                    shape = cardShape,
                    color = Color.Transparent,
                    border = cardBorder
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(cardBgStart, cardBgEnd)))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 1. Header section based on Layout Style
                            when (effectiveLayout) {
                                AdminDialogLayoutStyle.WAVE_ROCKET, AdminDialogLayoutStyle.AUTO -> {
                                    if (ui.showHeaderWave) {
                                        WaveHeaderCanvas(
                                            headerTop = headerTop,
                                            headerBottom = headerBottom,
                                            cornerRadiusDp = ui.cardCornerRadiusDp,
                                            showSparkles = ui.showSparkles,
                                            sparklePulse = sparklePulse
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.height(if (showFloatingShowcaseOnWave) 64.dp else 16.dp))
                                    }
                                    if (effectiveMedia in listOf(AdminMediaType.IMAGE, AdminMediaType.GIF, AdminMediaType.VIDEO) &&
                                        ui.mediaUrl.isNotBlank()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp)
                                                .padding(top = 6.dp)
                                        ) {
                                            AdminMediaSlot(
                                                mediaType = effectiveMedia,
                                                mediaUrl = ui.mediaUrl,
                                                thumbnailUrl = ui.thumbnailUrl,
                                                animationType = ui.animationType,
                                                accentColor = accentColor,
                                                contentScaleMode = ui.mediaContentScale,
                                                autoplay = ui.videoAutoplay,
                                                loop = ui.videoLoop,
                                                muted = ui.videoMuted,
                                                showControls = ui.videoShowControls,
                                                cornerRadiusDp = ui.mediaCornerRadiusDp,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(ui.mediaHeightDp.dp)
                                            )
                                        }
                                    }
                                }
                                AdminDialogLayoutStyle.HERO_MEDIA -> {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        if (effectiveMedia in listOf(AdminMediaType.IMAGE, AdminMediaType.GIF, AdminMediaType.VIDEO) &&
                                            ui.mediaUrl.isNotBlank()
                                        ) {
                                            AdminMediaSlot(
                                                mediaType = effectiveMedia,
                                                mediaUrl = ui.mediaUrl,
                                                thumbnailUrl = ui.thumbnailUrl,
                                                animationType = ui.animationType,
                                                accentColor = accentColor,
                                                contentScaleMode = ui.mediaContentScale,
                                                autoplay = ui.videoAutoplay,
                                                loop = ui.videoLoop,
                                                muted = ui.videoMuted,
                                                showControls = ui.videoShowControls,
                                                cornerRadiusDp = 0,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(ui.mediaHeightDp.dp)
                                                    .clip(
                                                        RoundedCornerShape(
                                                            topStart = ui.cardCornerRadiusDp.dp,
                                                            topEnd = ui.cardCornerRadiusDp.dp
                                                        )
                                                    )
                                            )
                                        } else if (effectiveMedia != AdminMediaType.NONE) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(144.dp)
                                                    .background(
                                                        Brush.verticalGradient(listOf(headerTop, headerBottom))
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                AdminAnimatedShowcase(
                                                    animationType = ui.animationType,
                                                    accentColor = accentColor,
                                                    iconName = ui.primaryButtonIcon,
                                                    modifier = Modifier.size(116.dp)
                                                )
                                            }
                                        }

                                        if (ui.badgeText.isNotBlank()) {
                                            AdminBadgePill(
                                                text = ui.badgeText,
                                                bgColor = parseAdminColor(ui.badgeColorHex, accentColor),
                                                textColor = parseAdminColor(
                                                    ui.badgeTextColorHex,
                                                    contrastContentColor(parseAdminColor(ui.badgeColorHex, accentColor))
                                                ),
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(14.dp)
                                            )
                                        }

                                        if (canDismiss && showDismissButton) {
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.45f),
                                                shape = CircleShape,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(10.dp)
                                            ) {
                                                IconButton(
                                                    onClick = onDismiss,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .testTag("app_update_close_icon_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.update_close_dialog_desc),
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                AdminDialogLayoutStyle.FULL_CARD_MEDIA -> {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        if (ui.mediaUrl.isNotBlank() && effectiveMedia != AdminMediaType.NONE) {
                                            AdminMediaSlot(
                                                mediaType = effectiveMedia,
                                                mediaUrl = ui.mediaUrl,
                                                thumbnailUrl = ui.thumbnailUrl,
                                                animationType = ui.animationType,
                                                accentColor = accentColor,
                                                contentScaleMode = ui.mediaContentScale,
                                                autoplay = ui.videoAutoplay,
                                                loop = ui.videoLoop,
                                                muted = ui.videoMuted,
                                                showControls = ui.videoShowControls,
                                                cornerRadiusDp = 0,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height((ui.mediaHeightDp + 40).coerceAtMost(360).dp)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(72.dp)
                                                    .align(Alignment.BottomCenter)
                                                    .background(
                                                        Brush.verticalGradient(
                                                            colors = listOf(Color.Transparent, cardBgStart)
                                                        )
                                                    )
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(140.dp)
                                                    .background(
                                                        Brush.verticalGradient(
                                                            listOf(accentColor.copy(alpha = 0.25f), cardBgStart)
                                                        )
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                AdminAnimatedShowcase(
                                                    animationType = ui.animationType,
                                                    accentColor = accentColor,
                                                    modifier = Modifier.size(112.dp)
                                                )
                                            }
                                        }
                                        if (canDismiss && showDismissButton) {
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.45f),
                                                shape = CircleShape,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(10.dp)
                                            ) {
                                                IconButton(
                                                    onClick = onDismiss,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .testTag("app_update_close_icon_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.update_close_dialog_desc),
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                AdminDialogLayoutStyle.MINIMAL -> {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    if (effectiveMedia in listOf(AdminMediaType.IMAGE, AdminMediaType.GIF, AdminMediaType.VIDEO) &&
                                        ui.mediaUrl.isNotBlank()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp)
                                        ) {
                                            AdminMediaSlot(
                                                mediaType = effectiveMedia,
                                                mediaUrl = ui.mediaUrl,
                                                thumbnailUrl = ui.thumbnailUrl,
                                                animationType = ui.animationType,
                                                accentColor = accentColor,
                                                contentScaleMode = ui.mediaContentScale,
                                                autoplay = ui.videoAutoplay,
                                                loop = ui.videoLoop,
                                                muted = ui.videoMuted,
                                                showControls = ui.videoShowControls,
                                                cornerRadiusDp = ui.mediaCornerRadiusDp,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(ui.mediaHeightDp.dp)
                                            )
                                        }
                                    } else if (effectiveMedia == AdminMediaType.ANIMATION && ui.animationType != AdminAnimationType.NONE) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            AdminAnimatedShowcase(
                                                animationType = ui.animationType,
                                                accentColor = accentColor,
                                                iconName = ui.primaryButtonIcon,
                                                modifier = Modifier.size(100.dp)
                                            )
                                        }
                                    }
                                }
                                AdminDialogLayoutStyle.FULLSCREEN_POSTER -> {
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                            }

                            // 2. Card Body Content (either ordered `ui_blocks` OR default rich layout)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp)
                                    .padding(top = 12.dp, bottom = 20.dp)
                            ) {
                                if (ui.blocks.isNotEmpty()) {
                                    AdminOrderedBlocksBody(
                                        blocks = ui.blocks,
                                        updateInfo = updateInfo,
                                        accentColor = accentColor,
                                        defaultTitleColor = titleColor,
                                        defaultDescriptionColor = descriptionColor,
                                        defaultVersionColor = versionColor,
                                        canDismiss = canDismiss,
                                        onButtonAction = onButtonAction
                                    )
                                } else {
                                    AdminStandardDialogBody(
                                        updateInfo = updateInfo,
                                        ui = ui,
                                        effectiveLayout = effectiveLayout,
                                        resolvedTitle = resolvedTitle,
                                        titleColor = titleColor,
                                        subtitleColor = subtitleColor,
                                        descriptionColor = descriptionColor,
                                        versionColor = versionColor,
                                        accentColor = accentColor,
                                        cardIsDark = cardIsDark,
                                        ctaPulseScale = ctaPulseScale,
                                        canDismiss = canDismiss,
                                        showDismissButton = showDismissButton,
                                        onUpdateClick = onUpdateClick,
                                        onDismiss = onDismiss,
                                        onButtonAction = onButtonAction
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating Showcase on top of Wave Header (Rocket or Admin animation)
                if (showFloatingShowcaseOnWave &&
                    (effectiveMedia == AdminMediaType.ANIMATION || effectiveMedia == AdminMediaType.AUTO || ui.mediaUrl.isBlank())
                ) {
                    AdminAnimatedShowcase(
                        animationType = ui.animationType,
                        accentColor = accentColor,
                        iconName = ui.primaryButtonIcon,
                        modifier = Modifier.size(134.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminStandardDialogBody(
    updateInfo: AppUpdateInfo,
    ui: AdminDialogUiConfig,
    effectiveLayout: AdminDialogLayoutStyle,
    resolvedTitle: String,
    titleColor: Color,
    subtitleColor: Color,
    descriptionColor: Color,
    versionColor: Color,
    accentColor: Color,
    cardIsDark: Boolean,
    ctaPulseScale: Float,
    canDismiss: Boolean,
    showDismissButton: Boolean,
    onUpdateClick: (String) -> Unit,
    onDismiss: () -> Unit,
    onButtonAction: (AdminDialogButton) -> Unit
) {
    val isCentered = ui.titleAlign.equals("center", ignoreCase = true)

    // Badge pill (when not already overlaid on HERO_MEDIA banner)
    if (ui.badgeText.isNotBlank() && effectiveLayout != AdminDialogLayoutStyle.HERO_MEDIA) {
        val badgeBg = parseAdminColor(ui.badgeColorHex, accentColor.copy(alpha = 0.16f))
        val badgeFg = parseAdminColor(ui.badgeTextColorHex, accentColor)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = if (isCentered) Alignment.Center else Alignment.CenterStart
        ) {
            AdminBadgePill(text = ui.badgeText, bgColor = badgeBg, textColor = badgeFg)
        }
    }

    // Title + Version Row
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isCentered) Arrangement.Center else Arrangement.SpaceBetween
    ) {
        Text(
            text = resolvedTitle,
            color = titleColor,
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .then(if (isCentered) Modifier else Modifier.weight(1f))
                .testTag("app_update_title")
        )
        if (updateInfo.displayVersion.isNotEmpty()) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = updateInfo.displayVersion,
                color = versionColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.testTag("app_update_version")
            )
        }
    }

    // Optional Subtitle
    if (ui.subtitle.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = ui.subtitle,
            color = subtitleColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Scrollable Description / Bullets + Optional Highlight Box
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        val bulletItems = updateInfo.bulletPoints
        if (bulletItems.isEmpty()) {
            val fallbackDesc = if (updateInfo.dialogMode == AdminDialogMode.UPDATE) {
                "A new version of Battery Boss is ready with performance improvements and bug fixes."
            } else {
                ""
            }
            if (fallbackDesc.isNotEmpty()) {
                Text(
                    text = fallbackDesc,
                    color = descriptionColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (bulletItems.size == 1 && !updateInfo.description.contains("\n")) {
            Text(
                text = bulletItems.first(),
                color = descriptionColor,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            bulletItems.forEach { point ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.90f))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = point,
                        color = descriptionColor,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (ui.highlightText.isNotBlank()) {
            val hlBg = parseAdminColor(
                ui.highlightBgColorHex,
                if (cardIsDark) accentColor.copy(alpha = 0.18f) else accentColor.copy(alpha = 0.10f)
            )
            val hlFg = parseAdminColor(
                ui.highlightTextColorHex,
                if (cardIsDark) Color.White else DefaultTitleBlack
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = hlBg,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (ui.highlightTitle.isNotBlank()) {
                            Text(
                                text = ui.highlightTitle,
                                color = hlFg,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = ui.highlightText,
                            color = hlFg.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(18.dp))

    // Primary Action Button
    if (ui.showPrimaryButton) {
        val primaryLabel = ui.primaryButtonText.ifBlank {
            if (updateInfo.dialogMode == AdminDialogMode.UPDATE) {
                stringResource(R.string.update_button_label)
            } else {
                "Open"
            }
        }
        val primaryStart = parseAdminColor(ui.primaryButtonColorHex, accentColor)
        val primaryEnd = parseAdminColor(ui.primaryButtonGradientEndHex, primaryStart)
        val primaryTextCol = parseAdminColor(
            ui.primaryButtonTextColorHex,
            contrastContentColor(primaryStart)
        )
        val primaryIcon = resolveAdminIcon(ui.primaryButtonIcon)
        val targetUrl = ui.primaryButtonUrl.ifBlank { updateInfo.apkUrl }
        val primaryBtnModel = AdminDialogButton(
            id = "primary",
            text = primaryLabel,
            url = targetUrl,
            action = ui.primaryButtonAction,
            style = if (ui.primaryButtonGradientEndHex.isNotBlank()) AdminButtonStyle.GRADIENT else AdminButtonStyle.FILLED,
            bgColorHex = ui.primaryButtonColorHex,
            gradientEndHex = ui.primaryButtonGradientEndHex,
            textColorHex = ui.primaryButtonTextColorHex,
            iconName = ui.primaryButtonIcon
        )

        val pulseModifier = if (ui.primaryButtonPulse) {
            Modifier.graphicsLayer {
                scaleX = ctaPulseScale
                scaleY = ctaPulseScale
            }
        } else {
            Modifier
        }

        if (ui.primaryButtonGradientEndHex.isNotBlank()) {
            Surface(
                onClick = {
                    if (ui.primaryButtonAction == AdminButtonAction.OPEN_URL && targetUrl == updateInfo.apkUrl) {
                        onUpdateClick(targetUrl)
                    } else {
                        onButtonAction(primaryBtnModel)
                    }
                },
                shape = RoundedCornerShape(50),
                color = Color.Transparent,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .then(pulseModifier)
                    .testTag("app_update_button")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(primaryStart, primaryEnd))),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (primaryIcon != null) {
                            Icon(
                                imageVector = primaryIcon,
                                contentDescription = null,
                                tint = primaryTextCol,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = primaryLabel,
                            color = primaryTextCol,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                    }
                }
            }
        } else {
            Button(
                onClick = {
                    if (ui.primaryButtonAction == AdminButtonAction.OPEN_URL && targetUrl == updateInfo.apkUrl) {
                        onUpdateClick(targetUrl)
                    } else {
                        onButtonAction(primaryBtnModel)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .then(pulseModifier)
                    .testTag("app_update_button"),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryStart,
                    contentColor = primaryTextCol
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp
                ),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp)
            ) {
                if (primaryIcon != null) {
                    Icon(
                        imageVector = primaryIcon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = primaryLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp
                )
            }
        }
    }

    // Dynamic Admin Extra Buttons (supports full-width or grouped side-by-side rows!)
    if (ui.buttons.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        AdminDynamicButtonsGroup(
            buttons = ui.buttons,
            accentColor = accentColor,
            cardIsDark = cardIsDark,
            canDismiss = canDismiss,
            onButtonAction = onButtonAction
        )
    }

    // Dismiss / "Not now" button
    if (showDismissButton && canDismiss) {
        Spacer(modifier = Modifier.height(6.dp))
        val dismissText = ui.dismissButtonText.ifBlank { stringResource(R.string.update_not_now_label) }
        val dismissColor = parseAdminColor(
            ui.dismissButtonColorHex,
            if (cardIsDark) Color(0xFFB0B8CC) else DefaultNotNowGray
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("app_update_not_now_button")
        ) {
            Text(
                text = dismissText,
                color = dismissColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // Optional Footer note
    if (ui.footerText.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = ui.footerText,
            color = parseAdminColor(
                ui.footerColorHex,
                if (cardIsDark) Color(0xFF8C96AD) else Color(0xFF888D99)
            ),
            fontSize = 11.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AdminOrderedBlocksBody(
    blocks: List<AdminUiBlock>,
    updateInfo: AppUpdateInfo,
    accentColor: Color,
    defaultTitleColor: Color,
    defaultDescriptionColor: Color,
    defaultVersionColor: Color,
    canDismiss: Boolean,
    onButtonAction: (AdminDialogButton) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            val align = when (block.align.lowercase()) {
                "center" -> TextAlign.Center
                "end", "right" -> TextAlign.End
                else -> TextAlign.Start
            }
            val blockAccent = parseAdminColor(block.accentColorHex, accentColor)
            when (block.type) {
                "spacer" -> Spacer(modifier = Modifier.height(block.heightDp.coerceIn(4, 48).dp))
                "divider" -> HorizontalDivider(
                    color = parseAdminColor(block.bgColorHex, defaultDescriptionColor.copy(alpha = 0.22f)),
                    thickness = block.heightDp.coerceIn(1, 4).dp
                )
                "badge" -> {
                    val bg = parseAdminColor(block.bgColorHex, blockAccent.copy(alpha = 0.16f))
                    val fg = parseAdminColor(block.textColorHex, blockAccent)
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart
                    ) {
                        AdminBadgePill(text = block.text, bgColor = bg, textColor = fg)
                    }
                }
                "title" -> {
                    val col = parseAdminColor(block.textColorHex, defaultTitleColor)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (align == TextAlign.Center) Arrangement.Center else Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = block.text.ifBlank { updateInfo.title },
                            color = col,
                            fontSize = (if (block.fontSizeSp > 0) block.fontSizeSp else 19).sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = align,
                            modifier = Modifier
                                .then(if (align == TextAlign.Center) Modifier else Modifier.weight(1f))
                                .testTag(if (index == 0) "app_update_title" else "admin_block_title_$index")
                        )
                        if (block.showVersion && updateInfo.displayVersion.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = updateInfo.displayVersion,
                                color = defaultVersionColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                "subtitle", "text" -> {
                    val col = parseAdminColor(block.textColorHex, defaultDescriptionColor)
                    val defaultSz = if (block.type == "subtitle") 13 else 14
                    Text(
                        text = block.text,
                        color = col,
                        fontSize = (if (block.fontSizeSp > 0) block.fontSizeSp else defaultSz).sp,
                        lineHeight = ((if (block.fontSizeSp > 0) block.fontSizeSp else defaultSz) + 6).sp,
                        fontWeight = if (block.type == "subtitle") FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "bullets" -> {
                    val col = parseAdminColor(block.textColorHex, defaultDescriptionColor)
                    val bulletList = if (block.items.isNotEmpty()) {
                        block.items
                    } else {
                        block.text.replace("\\n", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        bulletList.forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(blockAccent)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = line,
                                    color = col,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                "highlight_box" -> {
                    val bg = parseAdminColor(block.bgColorHex, blockAccent.copy(alpha = 0.12f))
                    val fg = parseAdminColor(block.textColorHex, defaultTitleColor)
                    val border = parseAdminColor(block.borderColorHex, blockAccent.copy(alpha = 0.35f))
                    Surface(
                        shape = RoundedCornerShape(block.cornerRadiusDp.dp),
                        color = bg,
                        border = BorderStroke(1.dp, border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = resolveAdminIcon(block.iconName) ?: Icons.Default.Info,
                                contentDescription = null,
                                tint = blockAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (block.title.isNotBlank()) {
                                    Text(
                                        text = block.title,
                                        color = fg,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (block.text.isNotBlank()) {
                                    Text(
                                        text = block.text,
                                        color = fg.copy(alpha = 0.9f),
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
                "media" -> {
                    val resolvedType = if (block.mediaType == AdminMediaType.AUTO) {
                        AdminDialogUiConfig.inferMediaTypeFromUrl(block.mediaUrl)
                    } else {
                        block.mediaType
                    }
                    AdminMediaSlot(
                        mediaType = resolvedType,
                        mediaUrl = block.mediaUrl,
                        thumbnailUrl = block.thumbnailUrl,
                        animationType = block.animationType,
                        accentColor = blockAccent,
                        contentScaleMode = block.contentScale,
                        autoplay = block.autoplay,
                        loop = block.loop,
                        muted = block.muted,
                        showControls = block.showControls,
                        cornerRadiusDp = block.cornerRadiusDp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(block.heightDp.takeIf { it > 0 }?.coerceIn(80, 420)?.dp ?: 190.dp)
                    )
                }
                "animation" -> {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AdminAnimatedShowcase(
                            animationType = block.animationType,
                            accentColor = blockAccent,
                            iconName = block.iconName,
                            modifier = Modifier.size(block.heightDp.takeIf { it > 0 }?.coerceIn(64, 180)?.dp ?: 110.dp)
                        )
                    }
                }
                "button" -> {
                    block.button?.let { btn ->
                        if (btn.action != AdminButtonAction.DISMISS || canDismiss) {
                            AdminSingleButton(
                                button = btn,
                                accentColor = blockAccent,
                                cardIsDark = defaultTitleColor == Color.White,
                                onClick = { onButtonAction(btn) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                "button_row" -> {
                    val visibleBtns = block.buttons.filter { it.action != AdminButtonAction.DISMISS || canDismiss }
                    if (visibleBtns.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            visibleBtns.forEach { btn ->
                                AdminSingleButton(
                                    button = btn,
                                    accentColor = blockAccent,
                                    cardIsDark = defaultTitleColor == Color.White,
                                    onClick = { onButtonAction(btn) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminDynamicButtonsGroup(
    buttons: List<AdminDialogButton>,
    accentColor: Color,
    cardIsDark: Boolean,
    canDismiss: Boolean,
    onButtonAction: (AdminDialogButton) -> Unit
) {
    val visibleButtons = buttons.filter { it.action != AdminButtonAction.DISMISS || canDismiss }
    if (visibleButtons.isEmpty()) return

    val renderedIndices = mutableSetOf<Int>()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visibleButtons.forEachIndexed { idx, btn ->
            if (idx in renderedIndices) return@forEachIndexed
            if (btn.rowGroup.isNotBlank()) {
                val groupItems = visibleButtons.withIndex()
                    .filter { (i, item) -> i !in renderedIndices && item.rowGroup == btn.rowGroup }
                groupItems.forEach { renderedIndices.add(it.index) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupItems.forEach { (_, groupBtn) ->
                        AdminSingleButton(
                            button = groupBtn,
                            accentColor = accentColor,
                            cardIsDark = cardIsDark,
                            onClick = { onButtonAction(groupBtn) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                renderedIndices.add(idx)
                AdminSingleButton(
                    button = btn,
                    accentColor = accentColor,
                    cardIsDark = cardIsDark,
                    onClick = { onButtonAction(btn) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AdminSingleButton(
    button: AdminDialogButton,
    accentColor: Color,
    cardIsDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = parseAdminColor(button.bgColorHex, accentColor)
    val textColor = parseAdminColor(
        button.textColorHex,
        when (button.style) {
            AdminButtonStyle.OUTLINED, AdminButtonStyle.TEXT -> {
                if (button.bgColorHex.isNotBlank()) bgColor
                else if (cardIsDark) Color.White else accentColor
            }
            AdminButtonStyle.TONAL -> if (cardIsDark) Color.White else DefaultTitleBlack
            AdminButtonStyle.FILLED, AdminButtonStyle.GRADIENT -> contrastContentColor(bgColor)
        }
    )
    val icon = resolveAdminIcon(button.iconName)
    val shape = RoundedCornerShape(button.cornerRadiusDp.dp)
    val tag = "admin_extra_button_${button.id}"

    when (button.style) {
        AdminButtonStyle.GRADIENT -> {
            val endColor = parseAdminColor(button.gradientEndHex, bgColor.copy(alpha = 0.8f))
            Surface(
                onClick = onClick,
                shape = shape,
                color = Color.Transparent,
                shadowElevation = 4.dp,
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .testTag(tag)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(Brush.horizontalGradient(listOf(bgColor, endColor)))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (icon != null) {
                            Icon(imageVector = icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = button.text,
                            color = textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        AdminButtonStyle.OUTLINED -> {
            OutlinedButton(
                onClick = onClick,
                shape = shape,
                border = BorderStroke(1.5.dp, bgColor),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .testTag(tag)
            ) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = button.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        AdminButtonStyle.TONAL -> {
            FilledTonalButton(
                onClick = onClick,
                shape = shape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = bgColor.copy(alpha = 0.16f),
                    contentColor = textColor
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .testTag(tag)
            ) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, tint = bgColor, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = button.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        AdminButtonStyle.TEXT -> {
            TextButton(
                onClick = onClick,
                shape = shape,
                colors = ButtonDefaults.textButtonColors(contentColor = textColor),
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .testTag(tag)
            ) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = button.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        AdminButtonStyle.FILLED -> {
            Button(
                onClick = onClick,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = bgColor,
                    contentColor = textColor
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .testTag(tag)
            ) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = button.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AdminBadgePill(
    text: String,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(50),
        modifier = modifier.testTag("admin_dialog_badge")
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun WaveHeaderCanvas(
    headerTop: Color,
    headerBottom: Color,
    cornerRadiusDp: Int,
    showSparkles: Boolean,
    sparklePulse: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(topStart = cornerRadiusDp.dp, topEnd = cornerRadiusDp.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val backWave = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h * 0.74f)
                cubicTo(w * 0.72f, h * 1.06f, w * 0.30f, h * 0.56f, 0f, h * 0.88f)
                close()
            }
            drawPath(path = backWave, color = headerTop.copy(alpha = 0.55f))

            val wavePath = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h * 0.68f)
                cubicTo(w * 0.70f, h * 1.02f, w * 0.32f, h * 0.46f, 0f, h * 0.80f)
                close()
            }
            drawPath(
                path = wavePath,
                brush = Brush.verticalGradient(colors = listOf(headerTop, headerBottom))
            )

            if (showSparkles) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.24f),
                    radius = 18.dp.toPx(),
                    center = Offset(w * 0.14f, h * 0.32f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.20f),
                    radius = 10.dp.toPx(),
                    center = Offset(w * 0.86f, h * 0.42f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.28f + 0.35f * sparklePulse),
                    radius = 4.dp.toPx() * (0.8f + 0.35f * sparklePulse),
                    center = Offset(w * 0.79f, h * 0.24f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f + 0.45f * (1.35f - sparklePulse)),
                    radius = 3.5.dp.toPx() * (0.85f + 0.3f * (1.35f - sparklePulse)),
                    center = Offset(w * 0.25f, h * 0.22f)
                )
            }
        }
    }
}

private fun Color.luminanceOrDarkFallback(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}
