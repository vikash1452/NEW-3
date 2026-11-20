package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BatteryBossDarkColorScheme = darkColorScheme(
    primary = EmeraldCharge,
    onPrimary = Color(0xFF00210E),
    primaryContainer = Color(0xFF00391C),
    onPrimaryContainer = Color(0xFF6BFFAC),
    secondary = ElectricCyan,
    onSecondary = Color(0xFF001F2A),
    secondaryContainer = Color(0xFF0B2E42),
    onSecondaryContainer = Color(0xFFB8EAFF),
    tertiary = PurpleVoiceAccent,
    onTertiary = Color.White,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = NavyCardBorder,
    error = ThermalCrimson,
    onError = Color.White
)

@Composable
fun BatteryBossTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BatteryBossDarkColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    BatteryBossTheme(content = content)
}
