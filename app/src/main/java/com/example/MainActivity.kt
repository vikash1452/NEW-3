package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.example.ui.screens.BatteryHealthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HardwareAndOemScreen
import com.example.ui.screens.HistoryAndAboutScreen
import com.example.ui.screens.SmartBatteryTrackerScreen
import com.example.ui.screens.VoiceAndAlertsScreen
import com.example.ui.theme.BatteryBossTheme
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldCharge
import com.example.ui.theme.NavyCardBorder
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceElevated
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.components.AppUpdateOverlay
import com.example.ui.theme.ThermalCrimson
import com.example.service.BatteryTelemetryHub
import com.example.update.AppUpdateInfo
import com.example.update.UpdateFetchResult
import com.example.update.UpdateLockManager
import com.example.update.UpdateRepository
import com.example.viewmodel.BatteryBossViewModel

enum class BatteryBossTab(
    val routeId: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("dashboard", "Home", Icons.Filled.Speed, Icons.Outlined.Speed),
    SMART_TRACKER("smart_tracker", "Tracker", Icons.Filled.BatterySaver, Icons.Outlined.BatterySaver),
    VOICE_ALERTS("voice_alerts", "Voice Studio", Icons.Filled.RecordVoiceOver, Icons.Outlined.RecordVoiceOver),
    BATTERY_HEALTH("battery_health", "Health", Icons.Filled.HealthAndSafety, Icons.Outlined.HealthAndSafety),
    HARDWARE_OEM("hardware_oem", "OEM & Diag", Icons.Filled.DeveloperBoard, Icons.Outlined.DeveloperBoard),
    HISTORY_ABOUT("history_about", "Analytics", Icons.Filled.History, Icons.Outlined.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryBossTheme {
                BatteryBossEntryScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BatteryTelemetryHub.setAppInForeground(true)
        BatteryTelemetryHub.setAccessibilityRadioHelperEnabled(
            com.example.hardware.ChargerRadioController.isAccessibilityAutoToggleEnabled(applicationContext)
        )
    }

    override fun onResume() {
        super.onResume()
        BatteryTelemetryHub.setAccessibilityRadioHelperEnabled(
            com.example.hardware.ChargerRadioController.isAccessibilityAutoToggleEnabled(applicationContext)
        )
    }

    override fun onStop() {
        BatteryTelemetryHub.setAppInForeground(false)
        super.onStop()
    }
}

@Composable
fun BatteryBossEntryScreen(
    viewModel: BatteryBossViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateRepository = remember { UpdateRepository() }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    var pendingUpdate by remember {
        mutableStateOf(UpdateLockManager.getCachedUpdateInfo(context))
    }

    LaunchedEffect(Unit) {
        if (showSplash) {
            delay(2_500L)
            showSplash = false
        }
    }

    // Non-blocking background check against Firebase Realtime Database (never crashes if offline)
    LaunchedEffect(Unit) {
        val result = updateRepository.fetchUpdate(currentVersion = BuildConfig.VERSION_NAME)
        UpdateLockManager.syncFromFetchResult(context, result)
        when (result) {
            is UpdateFetchResult.UpdateAvailable -> {
                pendingUpdate = result.info
            }
            is UpdateFetchResult.UpToDate -> {
                pendingUpdate = null
            }
            is UpdateFetchResult.Error -> {
                // Keep cached lock state if offline after an update was previously pushed
            }
        }
    }

    val activeUpdate = pendingUpdate
    var isHandlingUpdateClick by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = showSplash,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)) togetherWith
                (fadeOut(animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)) +
                    scaleOut(targetScale = 1.08f, animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)))
        },
        label = "splash_to_dashboard_transition"
    ) { isSplashVisible ->
        if (isSplashVisible) {
            BatteryBossSplashScreen()
        } else if (activeUpdate == null) {
            BatteryBossApp(viewModel = viewModel)
        } else {
            // App functions remain locked while a remote update is active
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBackground)
            )
        }
    }

    if (!showSplash && activeUpdate != null) {
        AppUpdateOverlay(
            updateInfo = activeUpdate.copy(mandatory = true),
            onUpdateClick = { apkUrl ->
                if (isHandlingUpdateClick) return@AppUpdateOverlay
                isHandlingUpdateClick = true

                coroutineScope.launch {
                    try {
                        withTimeoutOrNull(2_000L) {
                            runCatching { updateRepository.incrementDownloadClicks() }
                        }

                        if (apkUrl.isNotBlank()) {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.update_open_link_error),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } finally {
                        isHandlingUpdateClick = false
                    }
                }
            },
            onDismiss = { /* Locked while remote update is active */ }
        )
    }
}

@Composable
fun BatteryBossSplashScreen(
    modifier: Modifier = Modifier
) {
    // Choreographed entrance animatables (start after brief settle so cold-start window never clips them)
    val emblemAlpha = remember { Animatable(0f) }
    val emblemScale = remember { Animatable(0.20f) }
    val emblemRotation = remember { Animatable(-28f) }

    val titleAlpha = remember { Animatable(0f) }
    val titleScale = remember { Animatable(0.72f) }
    val titleOffsetY = remember { Animatable(52f) }

    val chargeProgress = remember { Animatable(0f) }

    val creatorAlpha = remember { Animatable(0f) }
    val creatorScale = remember { Animatable(0.80f) }
    val creatorOffsetY = remember { Animatable(38f) }
    val dividerWidthFraction = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(100L)

        // Stage 1: Dramatic Emblem Pop + Rotate
        launch {
            emblemAlpha.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
        }
        launch {
            emblemScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            emblemRotation.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 0.65f,
                    stiffness = Spring.StiffnessLow
                )
            )
        }

        // Stage 2: Title Kinetic Slide-Up + Scale + Fade
        launch {
            delay(200L)
            launch {
                titleAlpha.animateTo(1f, tween(durationMillis = 620, easing = FastOutSlowInEasing))
            }
            launch {
                titleScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                titleOffsetY.animateTo(0f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
            }
        }

        // Stage 3: Live Energy Charge Surge (0% -> 100%)
        launch {
            delay(240L)
            chargeProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1_850, easing = FastOutSlowInEasing)
            )
        }

        // Stage 4: Bottom Creator Credit Rise + Expanding Neon Divider
        launch {
            delay(420L)
            launch {
                dividerWidthFraction.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
            }
            launch {
                creatorAlpha.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
            }
            launch {
                creatorScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.76f,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                creatorOffsetY.animateTo(0f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
            }
        }
    }

    // Continuous live motion throughout the 2.5s splash
    val infiniteTransition = rememberInfiniteTransition(label = "splash_live_motion")

    val outerOrbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_orbit_angle"
    )
    val innerOrbitAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_orbit_angle"
    )
    val pulseWaveOne by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_wave_one"
    )
    val pulseWaveTwo by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, delayMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_wave_two"
    )
    val coreBreathingScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_breathing_scale"
    )
    val shimmerSweep by infiniteTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "title_shimmer_sweep"
    )

    val titleShimmerBrush = Brush.linearGradient(
        colorStops = arrayOf(
            (shimmerSweep - 0.35f).coerceIn(0f, 1f) to TextPrimary,
            shimmerSweep.coerceIn(0f, 1f) to EmeraldCharge,
            (shimmerSweep + 0.18f).coerceIn(0f, 1f) to ElectricCyan,
            (shimmerSweep + 0.40f).coerceIn(0f, 1f) to TextPrimary
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        EmeraldCharge.copy(alpha = 0.20f * emblemAlpha.value.coerceAtLeast(0.35f)),
                        ElectricCyan.copy(alpha = 0.08f),
                        Color.Transparent
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("splash_screen")
    ) {
        // Center hero: Live Orbital Energy Emblem + Shimmering BATTERY BOSS 2.0 + Energy Surge Bar
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .graphicsLayer {
                        alpha = emblemAlpha.value
                        scaleX = emblemScale.value
                        scaleY = emblemScale.value
                    },
                contentAlignment = Alignment.Center
            ) {
                // Live Canvas: Sonar Pulse Waves + Dual Counter-Rotating Neon Arcs + Orbiting Sparks
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.minDimension / 2f

                    // Expanding Pulse Wave 1
                    val r1 = maxRadius * (0.48f + 0.50f * pulseWaveOne)
                    drawCircle(
                        color = EmeraldCharge.copy(alpha = (1f - pulseWaveOne) * 0.45f),
                        radius = r1,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Expanding Pulse Wave 2
                    val r2 = maxRadius * (0.48f + 0.50f * pulseWaveTwo)
                    drawCircle(
                        color = ElectricCyan.copy(alpha = (1f - pulseWaveTwo) * 0.40f),
                        radius = r2,
                        center = center,
                        style = Stroke(width = 1.8.dp.toPx())
                    )

                    // Outer Rotating Emerald Comet Energy Arcs + Orbiting Spark Tip
                    val outerR = maxRadius * 0.78f
                    val outerTopLeft = Offset(center.x - outerR, center.y - outerR)
                    val outerSize = Size(outerR * 2f, outerR * 2f)
                    rotate(degrees = outerOrbitAngle, pivot = center) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.22f to EmeraldCharge,
                                    0.32f to ElectricCyan,
                                    0.35f to Color.Transparent,
                                    1.0f to Color.Transparent
                                ),
                                center = center
                            ),
                            startAngle = 0f,
                            sweepAngle = 115f,
                            useCenter = false,
                            topLeft = outerTopLeft,
                            size = outerSize,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = EmeraldCharge.copy(alpha = 0.50f),
                            startAngle = 185f,
                            sweepAngle = 70f,
                            useCenter = false,
                            topLeft = outerTopLeft,
                            size = outerSize,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Bright Orbiting Spark at 115° tip
                        val outerTipRad = 115.0 * PI / 180.0
                        val outerSpark = Offset(
                            x = center.x + (outerR * cos(outerTipRad)).toFloat(),
                            y = center.y + (outerR * sin(outerTipRad)).toFloat()
                        )
                        drawCircle(
                            color = ElectricCyan.copy(alpha = 0.40f),
                            radius = 8.dp.toPx(),
                            center = outerSpark
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3.2.dp.toPx(),
                            center = outerSpark
                        )
                    }

                    // Inner Counter-Rotating Cyan Energy Arcs
                    val innerR = maxRadius * 0.64f
                    val innerTopLeft = Offset(center.x - innerR, center.y - innerR)
                    val innerSize = Size(innerR * 2f, innerR * 2f)
                    drawArc(
                        color = ElectricCyan.copy(alpha = 0.85f),
                        startAngle = innerOrbitAngle,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = ElectricCyan.copy(alpha = 0.35f),
                        startAngle = innerOrbitAngle + 180f,
                        sweepAngle = 60f,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Center Emblem Disc with Breathing Pulse + Rotation Pop
                Surface(
                    modifier = Modifier
                        .size(98.dp)
                        .graphicsLayer {
                            scaleX = coreBreathingScale
                            scaleY = coreBreathingScale
                            rotationZ = emblemRotation.value
                        },
                    shape = CircleShape,
                    color = NavySurface,
                    border = BorderStroke(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(EmeraldCharge, ElectricCyan)
                        )
                    ),
                    shadowElevation = 16.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        EmeraldCharge.copy(alpha = 0.24f),
                                        NavySurface
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = "Battery Boss 2.0 Emblem",
                            tint = EmeraldCharge,
                            modifier = Modifier.size(50.dp)
                        )
                    }
                }
            }

            // Kinetic Title + Tagline + Live Energy Progress Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = titleAlpha.value
                        scaleX = titleScale.value
                        scaleY = titleScale.value
                        translationY = titleOffsetY.value
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "BATTERY BOSS 2.0",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 31.sp,
                        letterSpacing = 2.4.sp,
                        brush = titleShimmerBrush
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("splash_title_text")
                )

                Surface(
                    shape = RoundedCornerShape(50),
                    color = NavySurfaceElevated,
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            colors = listOf(
                                EmeraldCharge.copy(alpha = 0.55f),
                                ElectricCyan.copy(alpha = 0.55f)
                            )
                        )
                    )
                ) {
                    Text(
                        text = "SMART VOICE BATTERY ASSISTANT",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Live Energy Charge Bar (0% -> 100%)
                Column(
                    modifier = Modifier.width(210.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NavySurfaceElevated)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = chargeProgress.value.coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(50))
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(EmeraldCharge, ElectricCyan)
                                    )
                                )
                        )
                    }
                    Text(
                        text = "POWERING TELEMETRY • ${(chargeProgress.value * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
                        color = TextSecondary
                    )
                }
            }
        }

        // Bottom credit: Expanding Neon Line + Created by BHAVESH MOD
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .graphicsLayer {
                    alpha = creatorAlpha.value
                    scaleX = creatorScale.value
                    scaleY = creatorScale.value
                    translationY = creatorOffsetY.value
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width((96f * dividerWidthFraction.value).dp)
                    .height(2.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                EmeraldCharge,
                                ElectricCyan,
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(50)
                    )
            )
            Text(
                text = "Created by BHAVESH MOD",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.3.sp),
                color = TextPrimary.copy(alpha = 0.90f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("splash_creator_text")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryBossApp(
    viewModel: BatteryBossViewModel = viewModel()
) {
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val telemetryHistory by viewModel.telemetryHistory.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val isForegroundNotificationVisible by viewModel.isForegroundNotificationVisible.collectAsStateWithLifecycle()
    val isSilentBackgroundActive by viewModel.isSilentBackgroundActive.collectAsStateWithLifecycle()
    val isJobSchedulerRegistered by viewModel.isJobSchedulerRegistered.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val previewingPreset by viewModel.previewingPreset.collectAsStateWithLifecycle()
    val engineStatusText by viewModel.engineStatusText.collectAsStateWithLifecycle()
    val voiceModelPacks by viewModel.voiceModelPacks.collectAsStateWithLifecycle()
    val lastSpokenBanner by viewModel.lastSpokenBanner.collectAsStateWithLifecycle()
    val audioDiagnostics by viewModel.audioDiagnostics.collectAsStateWithLifecycle()
    val notificationChannelDiagnostics by viewModel.notificationChannelDiagnostics.collectAsStateWithLifecycle()
    val chargingSessions by viewModel.chargingSessions.collectAsStateWithLifecycle()
    val announcementLogs by viewModel.announcementLogs.collectAsStateWithLifecycle()
    val batteryUsageHistory by viewModel.batteryUsageHistory.collectAsStateWithLifecycle()
    val autoManagementReports by viewModel.autoManagementReports.collectAsStateWithLifecycle()
    val smartTrackerState by viewModel.smartTrackerState.collectAsStateWithLifecycle()
    val selectedAppForDetails by viewModel.selectedAppForDetails.collectAsStateWithLifecycle()
    val selectedAppHistoryRecords by viewModel.selectedAppHistoryRecords.collectAsStateWithLifecycle()
    val isIgnoringOptimizations by viewModel.isIgnoringBatteryOptimizations.collectAsStateWithLifecycle()
    val isAccessibilityRadioHelperEnabled by viewModel.isAccessibilityRadioHelperEnabled.collectAsStateWithLifecycle()
    val lastRadioAutomationStatus by viewModel.lastRadioAutomationStatus.collectAsStateWithLifecycle()

    LaunchedEffect(
        settings.silentNotificationEnabled,
        settings.backgroundMonitoringEnabled,
        settings.backgroundMonitoringMode,
        isServiceRunning
    ) {
        viewModel.refreshNotificationChannelDiagnostics()
    }

    var currentTab by rememberSaveable { mutableStateOf(BatteryBossTab.DASHBOARD) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && settings.backgroundMonitoringEnabled) {
            viewModel.toggleBackgroundMonitoring(true)
        }
    }

    val bluetoothConnectPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission outcome is handled safely by ChargerRadioController when charger connects.
    }

    LaunchedEffect(settings.backgroundMonitoringMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.backgroundMonitoringMode.requiresPersistentNotification
        ) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (currentTab != BatteryBossTab.DASHBOARD && selectedAppForDetails == null) {
        BackHandler {
            currentTab = BatteryBossTab.DASHBOARD
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 600.dp

        val disableAnimations = settings.batterySaverMode.disableAnimations
        val topBarPulseTransition = rememberInfiniteTransition(label = "topbar_charge_pulse")
        val rawChargeScale by topBarPulseTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "topbar_logo_scale"
        )
        val topBarLogoScale = if (snapshot.isCharging && !disableAnimations) rawChargeScale else 1f
        val pillContainerColor by animateColorAsState(
            targetValue = if (snapshot.isCharging) EmeraldCharge.copy(alpha = 0.16f) else NavySurfaceElevated,
            animationSpec = tween(durationMillis = 300),
            label = "topbar_pill_bg"
        )
        val pillBorderColor by animateColorAsState(
            targetValue = if (snapshot.isCharging) EmeraldCharge else NavyCardBorder,
            animationSpec = tween(durationMillis = 300),
            label = "topbar_pill_border"
        )
        val pillTextColor by animateColorAsState(
            targetValue = if (snapshot.isCharging) EmeraldCharge else TextPrimary,
            animationSpec = tween(durationMillis = 300),
            label = "topbar_pill_text"
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = "Battery Boss Logo",
                                tint = EmeraldCharge,
                                modifier = Modifier
                                    .size(26.dp)
                                    .graphicsLayer {
                                        scaleX = topBarLogoScale
                                        scaleY = topBarLogoScale
                                    }
                            )
                            Column {
                                Text(
                                    text = "BATTERY BOSS 2.0",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${settings.activePreset.title} • ${(settings.voiceVolume * 100).toInt()}% Vol • ${settings.batterySaverMode.badge}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        Surface(
                            color = pillContainerColor,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, pillBorderColor)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AnimatedContent(
                                    targetState = snapshot.percentage,
                                    transitionSpec = {
                                        if (disableAnimations) {
                                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                        } else if (targetState >= initialState) {
                                            (slideInVertically(tween(220)) { -it / 2 } + fadeIn(tween(220))) togetherWith
                                                (slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(180)))
                                        } else {
                                            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(180)))
                                        }
                                    },
                                    label = "topbar_pct_roll"
                                ) { pct ->
                                    Text(
                                        text = "$pct%",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = pillTextColor
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        AnimatedContent(
                            targetState = isSpeaking,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.78f, animationSpec = tween(200)) + fadeIn(tween(200))) togetherWith
                                    (scaleOut(targetScale = 0.78f, animationSpec = tween(160)) + fadeOut(tween(160)))
                            },
                            label = "topbar_speak_toggle"
                        ) { speaking ->
                            if (speaking) {
                                IconButton(
                                    onClick = { viewModel.stopSpeaking() },
                                    modifier = Modifier.testTag("topbar_stop_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop speaking",
                                        tint = ThermalCrimson
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { viewModel.testVoiceNow() },
                                    modifier = Modifier.testTag("topbar_speak_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Speak battery status",
                                        tint = ElectricCyan
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NavySurface
                    )
                )
            },
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar(
                        containerColor = NavySurface,
                        tonalElevation = 8.dp
                    ) {
                        BatteryBossTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            val iconScale by animateFloatAsState(
                                targetValue = if (selected && !disableAnimations) 1.14f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "bottom_nav_scale_${tab.routeId}"
                            )
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (tab == BatteryBossTab.SMART_TRACKER) {
                                        viewModel.refreshSmartBatteryTracker(forcePersist = false)
                                    }
                                    currentTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        }
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF00210E),
                                    selectedTextColor = EmeraldCharge,
                                    indicatorColor = EmeraldCharge,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_tab_${tab.routeId}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedScreen) {
                    NavigationRail(
                        containerColor = NavySurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        BatteryBossTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            val railIconScale by animateFloatAsState(
                                targetValue = if (selected && !disableAnimations) 1.14f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "rail_nav_scale_${tab.routeId}"
                            )
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    if (tab == BatteryBossTab.SMART_TRACKER) {
                                        viewModel.refreshSmartBatteryTracker(forcePersist = false)
                                    }
                                    currentTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = railIconScale
                                            scaleY = railIconScale
                                        }
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color(0xFF00210E),
                                    selectedTextColor = EmeraldCharge,
                                    indicatorColor = EmeraldCharge,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("rail_tab_${tab.routeId}")
                            )
                        }
                    }
                }

                val contentModifier = Modifier.fillMaxSize()

                AnimatedContent(
                    targetState = currentTab,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    transitionSpec = {
                        if (disableAnimations) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            val movingRight = targetState.ordinal >= initialState.ordinal
                            val slideOffset = if (movingRight) 48 else -48
                            (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { slideOffset } +
                                scaleIn(initialScale = 0.985f, animationSpec = tween(220))) togetherWith
                                (fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                                    slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -slideOffset / 2 })
                        }
                    },
                    label = "tab_screen_transition"
                ) { activeTab ->
                    when (activeTab) {
                    BatteryBossTab.DASHBOARD -> DashboardScreen(
                        snapshot = snapshot,
                        telemetryHistory = telemetryHistory,
                        settings = settings,
                        isServiceRunning = isServiceRunning,
                        isForegroundNotificationVisible = isForegroundNotificationVisible,
                        isSilentBackgroundActive = isSilentBackgroundActive,
                        isSpeaking = isSpeaking,
                        engineStatusText = engineStatusText,
                        lastSpokenBanner = lastSpokenBanner,
                        onToggleMonitoring = { enable ->
                            if (enable &&
                                settings.backgroundMonitoringMode.requiresPersistentNotification &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            viewModel.toggleBackgroundMonitoring(enable)
                        },
                        onSelectMonitoringMode = { mode ->
                            if (mode.requiresPersistentNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            viewModel.setBackgroundMonitoringMode(mode)
                        },
                        onToggleSilentNotification = { enable ->
                            viewModel.toggleSilentNotificationEnabled(enable)
                        },
                        onToggleHourlyStatusNotification = { enable ->
                            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            viewModel.updateHourlyStatusNotificationEnabled(enable)
                        },
                        isAccessibilityRadioHelperEnabled = isAccessibilityRadioHelperEnabled,
                        lastRadioAutomationStatus = lastRadioAutomationStatus,
                        onUpdateAutoDisableRadios = { enabled, disableBt, disableWifiData, restoreOnUnplug ->
                            val willBeEnabled = enabled ?: settings.autoDisableRadiosOnChargeEnabled
                            val willDisableBt = disableBt ?: settings.autoDisableBluetoothOnCharge
                            val willPromptInternet = disableWifiData ?: settings.autoDisableWifiAndDataOnCharge
                            if (willBeEnabled && willDisableBt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val hasBtPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasBtPerm) {
                                    bluetoothConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                }
                            }
                            if (willBeEnabled && willPromptInternet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasNotifPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasNotifPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            viewModel.updateAutoDisableRadiosSettings(
                                enabled = enabled,
                                disableBluetooth = disableBt,
                                disableWifiAndData = disableWifiData,
                                restoreOnUnplug = restoreOnUnplug
                            )
                        },
                        onOpenInternetPanel = { viewModel.openInternetConnectivityPanel() },
                        onOpenAccessibilitySettings = { viewModel.openAccessibilitySettingsForRadioHelper() },
                        onOpenAppDetailsSettings = { viewModel.openAppDetailsForRestrictedSettings() },
                        onToggleVoiceAnnouncements = { enable ->
                            viewModel.toggleVoiceAnnouncementsEnabled(enable)
                        },
                        onTriggerNotificationAction = { action ->
                            viewModel.triggerNotificationAction(action)
                        },
                        onSpeakStatusNow = { viewModel.testVoiceNow() },
                        onStopSpeaking = { viewModel.stopSpeaking() },
                        onOpenHealthScreen = { currentTab = BatteryBossTab.BATTERY_HEALTH },
                        onRefreshTelemetry = {
                            viewModel.refreshHardwareTelemetry()
                            viewModel.refreshSmartBatteryTracker(forcePersist = false)
                        },
                        smartTrackerState = smartTrackerState,
                        onOpenSmartTracker = {
                            viewModel.refreshSmartBatteryTracker(forcePersist = false)
                            currentTab = BatteryBossTab.SMART_TRACKER
                        },
                        modifier = contentModifier
                    )

                    BatteryBossTab.SMART_TRACKER -> SmartBatteryTrackerScreen(
                        snapshot = snapshot,
                        trackerState = smartTrackerState,
                        settings = settings,
                        telemetryHistory = telemetryHistory,
                        usageHistoryRecords = batteryUsageHistory,
                        autoManagementReports = autoManagementReports,
                        selectedAppForDetails = selectedAppForDetails,
                        selectedAppHistoryRecords = selectedAppHistoryRecords,
                        onRefreshTracker = { viewModel.refreshSmartBatteryTracker(forcePersist = true) },
                        onSelectGraphWindow = { viewModel.selectGraphWindow(it) },
                        onSelectHistoryPeriod = { viewModel.selectHistoryPeriod(it) },
                        onSelectAppSection = { viewModel.selectAppSection(it) },
                        onOpenAppDetails = { viewModel.openAppDetails(it) },
                        onSetBatterySaverMode = { viewModel.setBatterySaverMode(it) },
                        onUpdateAutoSaverSettings = { enabled, thresh, targetMode, restoreNormal, recPct ->
                            viewModel.updateAutoBatterySaverSettings(
                                enabled = enabled,
                                thresholdPercent = thresh,
                                targetMode = targetMode,
                                restoreNormalEnabled = restoreNormal,
                                recoveryPercent = recPct
                            )
                        },
                        onUpdateAutoRestrictionSettings = { enabled, thresh ->
                            viewModel.updateAutoRestrictionSettings(enabled, thresh)
                        },
                        onToggleAppProtected = { pkg, prot -> viewModel.toggleAppProtected(pkg, prot) },
                        onToggleAppExcluded = { pkg, excl -> viewModel.toggleAppExcluded(pkg, excl) },
                        onToggleAppUserPermittedRestrict = { pkg, perm ->
                            viewModel.toggleAppUserPermittedRestrict(pkg, perm)
                        },
                        onUpdateSmartAlertToggles = { highApp, highBg, rapid, highTemp, saverRec, screenDrain ->
                            viewModel.updateSmartAlertToggles(
                                highAppUsage = highApp,
                                highBackgroundActivity = highBg,
                                rapidDrain = rapid,
                                highBatteryTemp = highTemp,
                                saverRecommendation = saverRec,
                                abnormalScreenDrain = screenDrain
                            )
                        },
                        onRecordPowerAction = { app, desc, ok ->
                            viewModel.recordUserAppPowerAction(app, desc, ok)
                        },
                        onDeleteOldHistory = { days -> viewModel.deleteOldUsageHistory(days) },
                        onClearTrackerHistory = { viewModel.clearSmartTrackerHistory() },
                        onExportCsv = { viewModel.exportSmartTrackerCsv() },
                        modifier = contentModifier
                    )

                    BatteryBossTab.VOICE_ALERTS -> VoiceAndAlertsScreen(
                        settings = settings,
                        voiceModelPacks = voiceModelPacks,
                        audioDiagnostics = audioDiagnostics,
                        isSpeaking = isSpeaking,
                        previewingPreset = previewingPreset,
                        onSelectPreset = { viewModel.selectVoicePreset(it) },
                        onPreviewPreset = { preset, phrase -> viewModel.previewVoicePreset(preset, phrase) },
                        onStopSpeaking = { viewModel.stopSpeaking() },
                        onUpdateLanguage = { viewModel.updateLanguage(it) },
                        onUpdateGender = { viewModel.updateGender(it) },
                        onUpdateVoiceParameters = { speed, pitch, vol, prosody ->
                            viewModel.updateVoiceParameters(speed, pitch, vol, prosody)
                        },
                        onLiveVolumeChange = { vol -> viewModel.updateLiveIndependentVolume(vol) },
                        onUpdateIndependentVolumeSettings = { indepEnabled, vol, rememberLast, applyAll, ducking, repeat, autoNorm, usageOption ->
                            viewModel.updateIndependentVolumeSettings(
                                independentEnabled = indepEnabled,
                                volume = vol,
                                rememberLastVolume = rememberLast,
                                applyToAllAnnouncements = applyAll,
                                mediaDuckingLevel = ducking,
                                repeatPolicy = repeat,
                                autoLoudnessNormalization = autoNorm,
                                audioStreamUsageOption = usageOption
                            )
                        },
                        onTestVoice = { customPhrase -> viewModel.testVoiceNow(customPhrase) },
                        onTestChargerConnected = { viewModel.testChargerConnectedNow() },
                        onTestPriorityBurst = { viewModel.testPriorityQueueBurst() },
                        onRefreshAudioDiagnostics = { viewModel.refreshAudioDiagnostics() },
                        onInstallOrVerifyModel = { modelId -> viewModel.installOrVerifyModelPack(modelId) },
                        onUpdateChargingAlerts = { intervalPercent, intervalEnabled, smartMilestones, fullCharge, connected, disconnected, lowBat, lowThresh, tempWarn, tempThresh ->
                            viewModel.updateChargingAlerts(
                                intervalPercent = intervalPercent,
                                intervalEnabled = intervalEnabled,
                                smartMilestones = smartMilestones,
                                fullCharge = fullCharge,
                                chargerConnected = connected,
                                chargerDisconnected = disconnected,
                                lowBattery = lowBat,
                                lowBatteryThreshold = lowThresh,
                                tempWarning = tempWarn,
                                tempWarningCelsius = tempThresh
                            )
                        },
                        onUpdateChargerPercentToggles = { connPct, discPct ->
                            viewModel.updateChargingAlerts(
                                percentOnConnect = connPct,
                                percentOnDisconnect = discPct
                            )
                        },
                        onUpdateQuietHoursAndRinger = { qEnabled, qStart, qEnd, ringer ->
                            viewModel.updateQuietHoursAndRinger(qEnabled, qStart, qEnd, ringer)
                        },
                        onSaveCustomMessages = { isHindi, conn, disc, m20, m50, m80, inter, full, low, temp ->
                            viewModel.saveCustomMessages(isHindi, conn, disc, m20, m50, m80, inter, full, low, temp)
                        },
                        onResetCustomMessages = { viewModel.resetCustomMessages() },
                        modifier = contentModifier
                    )

                    BatteryBossTab.BATTERY_HEALTH -> BatteryHealthScreen(
                        snapshot = snapshot,
                        modifier = contentModifier
                    )

                    BatteryBossTab.HARDWARE_OEM -> HardwareAndOemScreen(
                        snapshot = snapshot,
                        settings = settings,
                        audioDiagnostics = audioDiagnostics,
                        notificationChannelDiagnostics = notificationChannelDiagnostics,
                        isServiceRunning = isServiceRunning,
                        isForegroundNotificationVisible = isForegroundNotificationVisible,
                        isSilentBackgroundActive = isSilentBackgroundActive,
                        isJobSchedulerRegistered = isJobSchedulerRegistered,
                        isIgnoringBatteryOptimizations = isIgnoringOptimizations,
                        oemGuide = viewModel.oemGuide,
                        onToggleMonitoring = { viewModel.toggleBackgroundMonitoring(it) },
                        onSelectMonitoringMode = { mode ->
                            if (mode.requiresPersistentNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            viewModel.setBackgroundMonitoringMode(mode)
                        },
                        onToggleSilentNotification = { viewModel.toggleSilentNotificationEnabled(it) },
                        onToggleHourlyStatusNotification = { enable ->
                            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            viewModel.updateHourlyStatusNotificationEnabled(enable)
                        },
                        onToggleVoiceAnnouncements = { viewModel.toggleVoiceAnnouncementsEnabled(it) },
                        onTriggerNotificationAction = { viewModel.triggerNotificationAction(it) },
                        onUpdateBackgroundSettings = { boot, notif ->
                            viewModel.updateBackgroundSettings(boot, notif)
                        },
                        onRefreshOptimizationState = { viewModel.refreshHardwareTelemetry() },
                        onRunQuickVoiceTest = { viewModel.testVoiceNow() },
                        onRunQueueBurstTest = { viewModel.testPriorityQueueBurst() },
                        modifier = contentModifier
                    )

                    BatteryBossTab.HISTORY_ABOUT -> HistoryAndAboutScreen(
                        snapshot = snapshot,
                        chargingSessions = chargingSessions,
                        announcementLogs = announcementLogs,
                        onReplayAnnouncement = { msg -> viewModel.testVoiceNow(msg) },
                        onClearHistory = { viewModel.clearAllHistory() },
                        modifier = contentModifier
                    )
                    }
                }
            }
        }
    }
}
