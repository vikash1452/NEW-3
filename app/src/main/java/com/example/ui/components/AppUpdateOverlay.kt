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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.update.AppUpdateInfo

// Palette strictly matching the custom App Update Overlay specification
private val PastelPurpleBackdrop = Color(0xFFE0D4FF)
private val PastelCircleLight = Color(0xFFF3EDFF)
private val PastelCircleAccent = Color(0xFFCBB8FF)
private val HeaderYellowMain = Color(0xFFFFD54F)
private val HeaderYellowLight = Color(0xFFFFE57F)
private val HeaderYellowDeep = Color(0xFFFFCA28)
private val UpdateButtonRed = Color(0xFFFF5252)
private val TitleBlack = Color(0xFF1A1A2E)
private val DescriptionGray = Color(0xFF5F6368)
private val NotNowTextGray = Color(0xFF7A7E8A)

@Composable
fun AppUpdateOverlay(
    updateInfo: AppUpdateInfo,
    onUpdateClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardScale = remember { Animatable(0.86f) }
    val cardAlpha = remember { Animatable(0f) }
    val cardOffsetY = remember { Animatable(40f) }

    LaunchedEffect(Unit) {
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
    }

    val infiniteTransition = rememberInfiniteTransition(label = "update_overlay_motion")

    // Gentle floating motion for the 3D rocket
    val rocketFloatOffsetY by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rocket_float_y"
    )

    // Subtle rocket launch hover tilt
    val rocketTiltDeg by infiniteTransition.animateFloat(
        initialValue = -2.6f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rocket_tilt_deg"
    )

    // Subtle animated jet flame pulse
    val flamePulse by infiniteTransition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rocket_flame_pulse"
    )

    // Twinkling sparkles in header
    val sparklePulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "header_sparkle_pulse"
    )

    // Subtle floating drift for decorative pastel circles
    val circleDrift by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "backdrop_circle_drift"
    )

    val rocketDescription = stringResource(R.string.update_rocket_content_desc)
    val defaultTitle = stringResource(R.string.update_default_title)
    val resolvedTitle = updateInfo.title.ifBlank { defaultTitle }
    val bulletItems = updateInfo.bulletPoints

    Dialog(
        onDismissRequest = {
            if (!updateInfo.mandatory) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !updateInfo.mandatory,
            dismissOnClickOutside = !updateInfo.mandatory
        )
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            PastelPurpleBackdrop,
                            Color(0xFFE7DEFF),
                            PastelPurpleBackdrop
                        )
                    )
                )
                .testTag("app_update_overlay_root"),
            contentAlignment = Alignment.Center
        ) {
            // Subtle floating decorative pastel circles on the purple backdrop
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Large top-left soft glow circle
                drawCircle(
                    color = PastelCircleLight.copy(alpha = 0.58f),
                    radius = w * 0.34f,
                    center = Offset(w * 0.08f, h * 0.12f + circleDrift)
                )
                // Medium top-right lavender accent circle
                drawCircle(
                    color = PastelCircleAccent.copy(alpha = 0.48f),
                    radius = w * 0.22f,
                    center = Offset(w * 0.90f, h * 0.16f - circleDrift * 0.8f)
                )
                // Small floating bubble upper-right
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f),
                    radius = w * 0.065f,
                    center = Offset(w * 0.78f, h * 0.29f + circleDrift * 1.1f)
                )
                // Medium-large bottom-left circle
                drawCircle(
                    color = PastelCircleAccent.copy(alpha = 0.42f),
                    radius = w * 0.28f,
                    center = Offset(w * 0.12f, h * 0.86f - circleDrift)
                )
                // Soft bottom-right circle
                drawCircle(
                    color = PastelCircleLight.copy(alpha = 0.62f),
                    radius = w * 0.30f,
                    center = Offset(w * 0.92f, h * 0.82f + circleDrift * 0.9f)
                )
                // Tiny accent dot mid-left
                drawCircle(
                    color = Color.White.copy(alpha = 0.50f),
                    radius = w * 0.045f,
                    center = Offset(w * 0.18f, h * 0.42f + circleDrift * 0.6f)
                )
            }

            // Centered Card Container with room at top for the floating 3D Rocket
            Box(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .widthIn(max = 390.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = cardScale.value
                        scaleY = cardScale.value
                        alpha = cardAlpha.value
                        translationY = cardOffsetY.value
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                // Main White Card (offset down by 54dp so the 3D rocket floats across the top edge)
                Surface(
                    modifier = Modifier
                        .padding(top = 54.dp)
                        .fillMaxWidth()
                        .shadow(
                            elevation = 20.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = Color(0xFF6B4EFF).copy(alpha = 0.24f),
                            spotColor = Color(0xFF6B4EFF).copy(alpha = 0.24f)
                        )
                        .testTag("app_update_dialog_card"),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Yellow Wavy Header (#FFD54F)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(118.dp)
                                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height

                                // Soft subtle secondary wave shadow behind primary yellow wave
                                val backWave = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(w, 0f)
                                    lineTo(w, h * 0.74f)
                                    cubicTo(
                                        w * 0.72f, h * 1.06f,
                                        w * 0.30f, h * 0.56f,
                                        0f, h * 0.88f
                                    )
                                    close()
                                }
                                drawPath(
                                    path = backWave,
                                    color = HeaderYellowLight.copy(alpha = 0.55f)
                                )

                                // Primary smooth yellow wavy header (#FFD54F)
                                val wavePath = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(w, 0f)
                                    lineTo(w, h * 0.68f)
                                    cubicTo(
                                        w * 0.70f, h * 1.02f,
                                        w * 0.32f, h * 0.46f,
                                        0f, h * 0.80f
                                    )
                                    close()
                                }
                                drawPath(
                                    path = wavePath,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(HeaderYellowLight, HeaderYellowMain, HeaderYellowDeep)
                                    )
                                )

                                // Subtle decorative circles & twinkling sparkles inside the yellow wave
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
                                    radius = (4.dp.toPx() * (0.8f + 0.35f * sparklePulse)),
                                    center = Offset(w * 0.79f, h * 0.24f)
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.25f + 0.45f * (1.35f - sparklePulse)),
                                    radius = (3.5.dp.toPx() * (0.85f + 0.3f * (1.35f - sparklePulse))),
                                    center = Offset(w * 0.25f, h * 0.22f)
                                )
                            }
                        }

                        // Card Content Body
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = 12.dp, bottom = 22.dp)
                        ) {
                            // Title Row: "ABOUT UPDATE" (Bold, Black) + Version (Red, right-aligned)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = resolvedTitle,
                                    color = TitleBlack,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.6.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("app_update_title")
                                )
                                if (updateInfo.displayVersion.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = updateInfo.displayVersion,
                                        color = UpdateButtonRed,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.testTag("app_update_version")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Bulleted Description List (Gray)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 210.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (bulletItems.isEmpty()) {
                                    Text(
                                        text = "A new version of Battery Boss is ready with performance improvements and bug fixes.",
                                        color = DescriptionGray,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
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
                                                    .background(UpdateButtonRed.copy(alpha = 0.85f))
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = point,
                                                color = DescriptionGray,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Large Pill-Shaped "Update" Button (Soft Red #FF5252)
                            Button(
                                onClick = { onUpdateClick(updateInfo.apkUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("app_update_button"),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = UpdateButtonRed,
                                    contentColor = Color.White
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 6.dp,
                                    pressedElevation = 2.dp
                                ),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.update_button_label),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            // "Not now" Text Button (hidden automatically when mandatory == true)
                            if (!updateInfo.mandatory) {
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .heightIn(min = 48.dp)
                                        .testTag("app_update_not_now_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_not_now_label),
                                        color = NotNowTextGray,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating 3D Rocket Illustration at Top Center
                Rocket3DIllustration(
                    flamePulse = flamePulse,
                    modifier = Modifier
                        .size(134.dp)
                        .graphicsLayer {
                            translationY = rocketFloatOffsetY
                            rotationZ = rocketTiltDeg
                        }
                        .semantics {
                            contentDescription = rocketDescription
                        }
                        .testTag("app_update_rocket_icon")
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
private fun Rocket3DIllustration(
    flamePulse: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.50f

        // Soft floating ground shadow below the rocket
        drawOval(
            color = Color(0xFF9E6B00).copy(alpha = 0.20f),
            topLeft = Offset(cx - w * 0.19f, h * 0.87f),
            size = Size(w * 0.38f, h * 0.08f)
        )

        // Outer fiery exhaust flame (animated)
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

        // Inner bright flame core
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

        // Left 3D Swept Fin
        val leftFin = Path().apply {
            moveTo(cx - w * 0.16f, h * 0.48f)
            cubicTo(cx - w * 0.31f, h * 0.54f, cx - w * 0.34f, h * 0.67f, cx - w * 0.27f, h * 0.74f)
            quadraticTo(cx - w * 0.20f, h * 0.68f, cx - w * 0.13f, h * 0.65f)
            close()
        }
        drawPath(
            path = leftFin,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFF6E6E), Color(0xFFE53935), Color(0xFFB71C1C)),
                start = Offset(cx - w * 0.34f, h * 0.50f),
                end = Offset(cx - w * 0.12f, h * 0.74f)
            )
        )

        // Right 3D Swept Fin
        val rightFin = Path().apply {
            moveTo(cx + w * 0.16f, h * 0.48f)
            cubicTo(cx + w * 0.31f, h * 0.54f, cx + w * 0.34f, h * 0.67f, cx + w * 0.27f, h * 0.74f)
            quadraticTo(cx + w * 0.20f, h * 0.68f, cx + w * 0.13f, h * 0.65f)
            close()
        }
        drawPath(
            path = rightFin,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFF5252), Color(0xFFD32F2F), Color(0xFF9A1717)),
                start = Offset(cx + w * 0.12f, h * 0.50f),
                end = Offset(cx + w * 0.34f, h * 0.74f)
            )
        )

        // Metallic Thruster Nozzle Base
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

        // Rocket Main 3D White Fuselage Body
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

        // Red 3D Nose Cone Cap
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
                colors = listOf(Color(0xFFFF7A7A), Color(0xFFFF5252), Color(0xFFD32F2F)),
                startX = cx - w * 0.16f,
                endX = cx + w * 0.16f
            )
        )

        // Glossy Specular Highlight Streak along Left Fuselage
        val highlightStreak = Path().apply {
            moveTo(cx - w * 0.085f, h * 0.32f)
            quadraticTo(cx - w * 0.115f, h * 0.45f, cx - w * 0.075f, h * 0.58f)
        }
        drawPath(
            path = highlightStreak,
            color = Color.White.copy(alpha = 0.85f),
            style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Metallic Bezel Ring around Porthole Window
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

        // Deep 3D Cyan Glass Porthole
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

        // Tiny Crisp Glass Reflection Dot
        drawCircle(
            color = Color.White.copy(alpha = 0.88f),
            radius = w * 0.022f,
            center = Offset(windowCenter.x - glassRadius * 0.36f, windowCenter.y - glassRadius * 0.36f)
        )

        // Center Red Tail Fin (3D depth accent)
        val centerFin = Path().apply {
            moveTo(cx, h * 0.56f)
            lineTo(cx + w * 0.024f, h * 0.715f)
            lineTo(cx - w * 0.024f, h * 0.715f)
            close()
        }
        drawPath(
            path = centerFin,
            brush = Brush.horizontalGradient(
                colors = listOf(Color(0xFFFF6E6E), Color(0xFFD32F2F)),
                startX = cx - w * 0.024f,
                endX = cx + w * 0.024f
            )
        )
    }
}
