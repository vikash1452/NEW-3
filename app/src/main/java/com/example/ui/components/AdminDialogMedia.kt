package com.example.ui.components

import android.os.Build
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.R
import com.example.update.AdminAnimationType
import com.example.update.AdminMediaType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun parseAdminColor(hex: String?, fallback: Color): Color {
    val raw = hex?.trim().orEmpty()
    if (raw.isEmpty()) return fallback
    val named = when (raw.lowercase()) {
        "red" -> Color(0xFFFF5252)
        "blue" -> Color(0xFF2979FF)
        "cyan" -> Color(0xFF00E5FF)
        "green", "emerald" -> Color(0xFF00E676)
        "yellow", "gold" -> Color(0xFFFFD54F)
        "amber", "orange" -> Color(0xFFFFAB00)
        "purple", "violet" -> Color(0xFF7C4DFF)
        "pink" -> Color(0xFFFF4081)
        "white" -> Color.White
        "black" -> Color.Black
        "dark", "navy" -> Color(0xFF121826)
        "transparent" -> Color.Transparent
        else -> null
    }
    if (named != null) return named

    val cleanHex = raw.removePrefix("#")
    return runCatching {
        when (cleanHex.length) {
            6 -> Color(android.graphics.Color.parseColor("#$cleanHex"))
            8 -> Color(android.graphics.Color.parseColor("#$cleanHex"))
            else -> fallback
        }
    }.getOrElse { fallback }
}

fun contrastContentColor(background: Color): Color {
    return if (background.luminance() > 0.55f) Color(0xFF1A1A2E) else Color.White
}

fun resolveAdminIcon(name: String?): ImageVector? {
    val key = name?.trim()?.lowercase().orEmpty()
    if (key.isEmpty()) return null
    return when (key) {
        "download", "apk", "update" -> Icons.Default.Download
        "rocket", "launch", "boost" -> Icons.Default.RocketLaunch
        "play", "watch", "stream" -> Icons.Default.PlayArrow
        "video", "youtube", "movie" -> Icons.Default.Videocam
        "image", "photo", "gallery" -> Icons.Default.Image
        "telegram", "send", "message" -> Icons.AutoMirrored.Filled.Send
        "whatsapp", "share" -> Icons.Default.Share
        "link", "url", "website", "instagram" -> Icons.Default.Link
        "open", "external", "browser" -> Icons.Default.OpenInNew
        "copy", "clipboard" -> Icons.Default.ContentCopy
        "star", "rate", "vip" -> Icons.Default.Star
        "gift", "offer", "reward", "promo" -> Icons.Default.CardGiftcard
        "info", "about", "details" -> Icons.Default.Info
        "bolt", "flash", "power", "battery" -> Icons.Default.Bolt
        "settings", "gear", "config" -> Icons.Default.Settings
        "close", "cancel", "dismiss" -> Icons.Default.Close
        "check", "done", "ok", "verified" -> Icons.Default.CheckCircle
        "sparkles", "magic", "new", "ai" -> Icons.Default.AutoAwesome
        "warning", "alert" -> Icons.Default.Warning
        "bell", "notify", "announcement" -> Icons.Default.NotificationsActive
        "campaign", "megaphone", "notice" -> Icons.Default.Campaign
        "phone", "mobile", "app" -> Icons.Default.PhoneAndroid
        else -> null
    }
}

@Composable
fun rememberGifEnabledImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }
}

@Composable
fun AdminMediaSlot(
    mediaType: AdminMediaType,
    mediaUrl: String,
    thumbnailUrl: String,
    animationType: AdminAnimationType,
    accentColor: Color,
    contentScaleMode: String,
    autoplay: Boolean,
    loop: Boolean,
    muted: Boolean,
    showControls: Boolean,
    cornerRadiusDp: Int,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    when (mediaType) {
        AdminMediaType.NONE -> Unit
        AdminMediaType.VIDEO -> {
            if (mediaUrl.isNotBlank()) {
                AdminVideoView(
                    videoUrl = mediaUrl,
                    thumbnailUrl = thumbnailUrl,
                    contentScaleMode = contentScaleMode,
                    autoplay = autoplay,
                    loop = loop,
                    initialMuted = muted,
                    showControls = showControls,
                    accentColor = accentColor,
                    modifier = modifier
                        .clip(shape)
                        .testTag("admin_dialog_video_player")
                )
            } else {
                AdminAnimatedShowcase(
                    animationType = animationType,
                    accentColor = accentColor,
                    modifier = modifier
                )
            }
        }
        AdminMediaType.IMAGE, AdminMediaType.GIF -> {
            if (mediaUrl.isNotBlank()) {
                AdminImageOrGifView(
                    url = mediaUrl,
                    contentScaleMode = contentScaleMode,
                    accentColor = accentColor,
                    modifier = modifier
                        .clip(shape)
                        .testTag(if (mediaType == AdminMediaType.GIF) "admin_dialog_gif_view" else "admin_dialog_image_view")
                )
            } else {
                AdminAnimatedShowcase(
                    animationType = animationType,
                    accentColor = accentColor,
                    modifier = modifier
                )
            }
        }
        AdminMediaType.ANIMATION, AdminMediaType.AUTO -> {
            AdminAnimatedShowcase(
                animationType = animationType,
                accentColor = accentColor,
                modifier = modifier
            )
        }
    }
}

@Composable
fun AdminImageOrGifView(
    url: String,
    contentScaleMode: String = "crop",
    accentColor: Color = Color(0xFFFF5252),
    contentDescriptionText: String = "Admin dialog visual media",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val gifLoader = rememberGifEnabledImageLoader()
    val scale = if (contentScaleMode.equals("fit", ignoreCase = true)) {
        ContentScale.Fit
    } else {
        ContentScale.Crop
    }

    SubcomposeAsyncImage(
        model = remember(url, context) {
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .build()
        },
        imageLoader = gifLoader,
        contentDescription = contentDescriptionText,
        contentScale = scale,
        modifier = modifier.background(Color(0xFF161925).copy(alpha = 0.08f)),
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFEFEAFF)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = accentColor,
                    strokeWidth = 2.8.dp
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF3EEFF)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = accentColor.copy(alpha = 0.75f),
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        text = "Media unavailable offline",
                        fontSize = 12.sp,
                        color = Color(0xFF5F6368),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    )
}

@OptIn(UnstableApi::class)
@Composable
fun AdminVideoView(
    videoUrl: String,
    thumbnailUrl: String = "",
    contentScaleMode: String = "crop",
    autoplay: Boolean = true,
    loop: Boolean = true,
    initialMuted: Boolean = false,
    showControls: Boolean = true,
    accentColor: Color = Color(0xFFFF5252),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember(videoUrl) { mutableStateOf(autoplay) }
    var isMuted by remember(videoUrl, initialMuted) { mutableStateOf(initialMuted) }
    var isBuffering by remember(videoUrl) { mutableStateOf(true) }
    var hasRenderedFrame by remember(videoUrl) { mutableStateOf(false) }
    var hasError by remember(videoUrl) { mutableStateOf(false) }
    var retryCounter by remember(videoUrl) { mutableIntStateOf(0) }

    val exoPlayer = remember(videoUrl, retryCounter) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            volume = if (isMuted) 0f else 1f
            playWhenReady = autoplay
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
        }
    }

    LaunchedEffect(exoPlayer, loop) {
        exoPlayer.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    LaunchedEffect(exoPlayer, isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    hasError = false
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFrame = true
                isBuffering = false
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                isBuffering = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val resizeModeInt = if (contentScaleMode.equals("fit", ignoreCase = true)) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT
    } else {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0E1220))
            .clickable(enabled = !hasError) {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                } else {
                    if (exoPlayer.playbackState == Player.STATE_ENDED) {
                        exoPlayer.seekTo(0L)
                    }
                    exoPlayer.play()
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_video_player, null, false) as PlayerView
                view.player = exoPlayer
                view.resizeMode = resizeModeInt
                view.useController = false
                view
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
                view.resizeMode = resizeModeInt
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!hasRenderedFrame && thumbnailUrl.isNotBlank() && !hasError) {
            AdminImageOrGifView(
                url = thumbnailUrl,
                contentScaleMode = contentScaleMode,
                accentColor = accentColor,
                contentDescriptionText = "Video preview thumbnail",
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isBuffering && !hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = accentColor,
                    strokeWidth = 3.dp
                )
            }
        }

        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141827)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(R.string.update_video_error),
                        color = Color.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    TextButton(
                        onClick = {
                            hasError = false
                            hasRenderedFrame = false
                            isBuffering = true
                            retryCounter++
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.update_video_retry),
                            color = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (showControls) {
            // Clean floating Play/Pause & Mute/Unmute pills
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.58f),
                    shape = CircleShape
                ) {
                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    exoPlayer.seekTo(0L)
                                }
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("admin_video_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.update_video_play_toggle),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.58f),
                    shape = CircleShape
                ) {
                    IconButton(
                        onClick = { isMuted = !isMuted },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("admin_video_mute_button")
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.update_video_mute_toggle),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AdminAnimatedShowcase(
    animationType: AdminAnimationType,
    accentColor: Color,
    iconName: String = "",
    modifier: Modifier = Modifier
) {
    if (animationType == AdminAnimationType.NONE) return

    val infiniteTransition = rememberInfiniteTransition(label = "admin_showcase_anim")
    val floatY by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "showcase_float_y"
    )
    val tiltDeg by infiniteTransition.animateFloat(
        initialValue = -2.6f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "showcase_tilt"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "showcase_pulse"
    )
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "showcase_orbit"
    )

    val rocketDescription = stringResource(R.string.update_rocket_content_desc)

    when (animationType) {
        AdminAnimationType.ROCKET -> {
            Rocket3DIllustration(
                flamePulse = pulse,
                accentColor = accentColor,
                modifier = modifier
                    .graphicsLayer {
                        translationY = floatY
                        rotationZ = tiltDeg
                    }
                    .semantics { contentDescription = rocketDescription }
                    .testTag("app_update_rocket_icon")
            )
        }
        AdminAnimationType.PULSE_BADGE -> {
            val icon = resolveAdminIcon(iconName) ?: Icons.Default.NotificationsActive
            Box(
                modifier = modifier.testTag("admin_anim_pulse_badge"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val maxR = size.minDimension / 2f
                    drawCircle(
                        color = accentColor.copy(alpha = 0.16f * (1.25f - pulse)),
                        radius = maxR * 0.88f * (0.88f + 0.12f * pulse),
                        center = c
                    )
                    drawCircle(
                        color = accentColor.copy(alpha = 0.28f),
                        radius = maxR * 0.64f,
                        center = c
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = accentColor,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .size(66.dp)
                        .graphicsLayer {
                            scaleX = 0.94f + 0.08f * pulse
                            scaleY = 0.94f + 0.08f * pulse
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "Animated highlight badge",
                            tint = contrastContentColor(accentColor),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }
        }
        AdminAnimationType.GLOW_RING -> {
            val icon = resolveAdminIcon(iconName) ?: Icons.Default.Bolt
            Box(
                modifier = modifier.testTag("admin_anim_glow_ring"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension * 0.38f
                    rotate(degrees = orbitAngle, pivot = c) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    accentColor,
                                    Color(0xFFFFD54F),
                                    Color.Transparent
                                ),
                                center = c
                            ),
                            startAngle = 0f,
                            sweepAngle = 220f,
                            useCenter = false,
                            topLeft = Offset(c.x - r, c.y - r),
                            size = Size(r * 2f, r * 2f),
                            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.95f),
                    shadowElevation = 10.dp,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "Orbital highlight badge",
                            tint = contrastContentColor(accentColor),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
        AdminAnimationType.SPARKLE_WAVE, AdminAnimationType.CONFETTI -> {
            val icon = resolveAdminIcon(iconName) ?: Icons.Default.AutoAwesome
            Box(
                modifier = modifier.testTag("admin_anim_sparkle_showcase"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension * 0.36f
                    val confettiColors = listOf(
                        accentColor,
                        Color(0xFFFFD54F),
                        Color(0xFF00E5FF),
                        Color(0xFF00E676),
                        Color(0xFFFF4081)
                    )
                    for (i in 0 until 10) {
                        val angle = ((i * 36f) + orbitAngle * 0.45f) * PI / 180.0
                        val dist = radius * (0.85f + 0.18f * sin(angle * 2).toFloat())
                        val pos = Offset(
                            x = c.x + (dist * cos(angle)).toFloat(),
                            y = c.y + (dist * sin(angle)).toFloat()
                        )
                        drawCircle(
                            color = confettiColors[i % confettiColors.size],
                            radius = (4.dp.toPx() * (0.75f + 0.25f * pulse)),
                            center = pos
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = accentColor,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .size(66.dp)
                        .graphicsLayer { translationY = floatY * 0.6f }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "Celebration badge",
                            tint = contrastContentColor(accentColor),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }
        }
        AdminAnimationType.BOUNCE_ICON -> {
            val icon = resolveAdminIcon(iconName) ?: Icons.Default.CardGiftcard
            Box(
                modifier = modifier.testTag("admin_anim_bounce_icon"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = accentColor,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            translationY = floatY * 1.4f
                            rotationZ = tiltDeg * 1.6f
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "Animated feature icon",
                            tint = contrastContentColor(accentColor),
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            }
        }
        AdminAnimationType.NONE -> Unit
    }
}

@Composable
fun AdminConfettiOverlay(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "admin_confetti")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "confetti_fall_phase"
    )

    val palette = remember(accentColor) {
        listOf(
            accentColor,
            Color(0xFFFFD54F),
            Color(0xFF00E5FF),
            Color(0xFF00E676),
            Color(0xFFFF4081),
            Color(0xFF7C4DFF)
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val count = 28
        for (i in 0 until count) {
            val xFrac = ((i * 37 + 13) % 100) / 100f
            val speed = 0.7f + (i % 5) * 0.16f
            val yFrac = ((phase * speed + i * 0.11f) % 1.15f) - 0.1f
            val sway = sin((phase * 2f * PI + i).toDouble()).toFloat() * 14.dp.toPx()
            val pos = Offset(w * xFrac + sway, h * yFrac)
            val particleSize = (4.5f + (i % 4) * 1.8f).dp.toPx()
            val color = palette[i % palette.size].copy(alpha = 0.72f)
            if (i % 2 == 0) {
                drawCircle(color = color, radius = particleSize * 0.5f, center = pos)
            } else {
                drawRect(
                    color = color,
                    topLeft = pos,
                    size = Size(particleSize, particleSize * 0.6f)
                )
            }
        }
    }
}

/**
 * Custom 3D-shaded Rocket illustration with volumetric gradients, glossy glass porthole,
 * swept fins, animated exhaust flame, and soft floating shadow.
 */
@Composable
fun Rocket3DIllustration(
    flamePulse: Float,
    accentColor: Color = Color(0xFFFF5252),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.50f

        drawOval(
            color = Color(0xFF9E6B00).copy(alpha = 0.20f),
            topLeft = Offset(cx - w * 0.19f, h * 0.87f),
            size = Size(w * 0.38f, h * 0.08f)
        )

        val outerFlame = Path().apply {
            moveTo(cx - w * 0.095f, h * 0.67f)
            quadraticTo(cx - w * 0.12f, h * 0.78f, cx, h * (0.84f + 0.05f * flamePulse))
            quadraticTo(cx + w * 0.12f, h * 0.78f, cx + w * 0.095f, h * 0.67f)
            close()
        }
        drawPath(
            path = outerFlame,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFFF6D00), Color(0xFFFF3D00), Color(0xFFFFAB00)),
                startY = h * 0.66f,
                endY = h * 0.90f
            )
        )

        val innerFlame = Path().apply {
            moveTo(cx - w * 0.052f, h * 0.68f)
            quadraticTo(cx - w * 0.06f, h * 0.75f, cx, h * (0.79f + 0.03f * flamePulse))
            quadraticTo(cx + w * 0.06f, h * 0.75f, cx + w * 0.052f, h * 0.68f)
            close()
        }
        drawPath(
            path = innerFlame,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFFFEA00), Color(0xFFFF9100)),
                startY = h * 0.68f,
                endY = h * 0.83f
            )
        )

        val finDark = Color(
            red = (accentColor.red * 0.65f).coerceIn(0f, 1f),
            green = (accentColor.green * 0.65f).coerceIn(0f, 1f),
            blue = (accentColor.blue * 0.65f).coerceIn(0f, 1f),
            alpha = 1f
        )

        val leftFin = Path().apply {
            moveTo(cx - w * 0.16f, h * 0.48f)
            cubicTo(cx - w * 0.31f, h * 0.54f, cx - w * 0.34f, h * 0.67f, cx - w * 0.27f, h * 0.74f)
            quadraticTo(cx - w * 0.20f, h * 0.68f, cx - w * 0.13f, h * 0.65f)
            close()
        }
        drawPath(
            path = leftFin,
            brush = Brush.linearGradient(
                colors = listOf(accentColor.copy(alpha = 0.85f), accentColor, finDark),
                start = Offset(cx - w * 0.34f, h * 0.50f),
                end = Offset(cx - w * 0.12f, h * 0.74f)
            )
        )

        val rightFin = Path().apply {
            moveTo(cx + w * 0.16f, h * 0.48f)
            cubicTo(cx + w * 0.31f, h * 0.54f, cx + w * 0.34f, h * 0.67f, cx + w * 0.27f, h * 0.74f)
            quadraticTo(cx + w * 0.20f, h * 0.68f, cx + w * 0.13f, h * 0.65f)
            close()
        }
        drawPath(
            path = rightFin,
            brush = Brush.linearGradient(
                colors = listOf(accentColor, finDark, finDark),
                start = Offset(cx + w * 0.12f, h * 0.50f),
                end = Offset(cx + w * 0.34f, h * 0.74f)
            )
        )

        val nozzle = Path().apply {
            moveTo(cx - w * 0.115f, h * 0.62f)
            lineTo(cx + w * 0.115f, h * 0.62f)
            lineTo(cx + w * 0.095f, h * 0.695f)
            lineTo(cx - w * 0.095f, h * 0.695f)
            close()
        }
        drawPath(
            path = nozzle,
            brush = Brush.horizontalGradient(
                colors = listOf(Color(0xFF546E7A), Color(0xFF90A4AE), Color(0xFF37474F)),
                startX = cx - w * 0.12f,
                endX = cx + w * 0.12f
            )
        )

        val body = Path().apply {
            moveTo(cx, h * 0.08f)
            cubicTo(cx + w * 0.22f, h * 0.22f, cx + w * 0.24f, h * 0.47f, cx + w * 0.135f, h * 0.64f)
            lineTo(cx - w * 0.135f, h * 0.64f)
            cubicTo(cx - w * 0.24f, h * 0.47f, cx - w * 0.22f, h * 0.22f, cx, h * 0.08f)
            close()
        }
        drawPath(
            path = body,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFFE3E8F4),
                    Color(0xFFFFFFFF),
                    Color(0xFFF6F8FC),
                    Color(0xFFCFD8EC)
                ),
                startX = cx - w * 0.22f,
                endX = cx + w * 0.22f
            )
        )

        val noseCone = Path().apply {
            moveTo(cx, h * 0.08f)
            cubicTo(cx + w * 0.095f, h * 0.145f, cx + w * 0.145f, h * 0.22f, cx + w * 0.16f, h * 0.275f)
            quadraticTo(cx, h * 0.305f, cx - w * 0.16f, h * 0.275f)
            cubicTo(cx - w * 0.145f, h * 0.22f, cx - w * 0.095f, h * 0.145f, cx, h * 0.08f)
            close()
        }
        drawPath(
            path = noseCone,
            brush = Brush.horizontalGradient(
                colors = listOf(accentColor.copy(alpha = 0.85f), accentColor, finDark),
                startX = cx - w * 0.16f,
                endX = cx + w * 0.16f
            )
        )

        val highlightStreak = Path().apply {
            moveTo(cx - w * 0.085f, h * 0.32f)
            quadraticTo(cx - w * 0.115f, h * 0.45f, cx - w * 0.075f, h * 0.58f)
        }
        drawPath(
            path = highlightStreak,
            color = Color.White.copy(alpha = 0.85f),
            style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
        )

        val windowCenter = Offset(cx, h * 0.435f)
        val outerWindowRadius = w * 0.102f
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFB0BEC5), Color(0xFFECEFF1), Color(0xFF546E7A)),
                start = Offset(windowCenter.x - outerWindowRadius, windowCenter.y - outerWindowRadius),
                end = Offset(windowCenter.x + outerWindowRadius, windowCenter.y + outerWindowRadius)
            ),
            radius = outerWindowRadius,
            center = windowCenter
        )

        val glassRadius = w * 0.076f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF80E8FF), Color(0xFF29B6F6), Color(0xFF0277BD)),
                center = Offset(windowCenter.x - glassRadius * 0.30f, windowCenter.y - glassRadius * 0.30f),
                radius = glassRadius * 1.45f
            ),
            radius = glassRadius,
            center = windowCenter
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.88f),
            radius = w * 0.022f,
            center = Offset(windowCenter.x - glassRadius * 0.36f, windowCenter.y - glassRadius * 0.36f)
        )

        val centerFin = Path().apply {
            moveTo(cx, h * 0.56f)
            lineTo(cx + w * 0.024f, h * 0.715f)
            lineTo(cx - w * 0.024f, h * 0.715f)
            close()
        }
        drawPath(
            path = centerFin,
            brush = Brush.horizontalGradient(
                colors = listOf(accentColor, finDark),
                startX = cx - w * 0.024f,
                endX = cx + w * 0.024f
            )
        )
    }
}
