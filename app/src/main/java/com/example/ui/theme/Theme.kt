package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.browser.ThemeMode

private val DarkColors = darkColorScheme(
    primary = ElectricTeal,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = ElectricTealContainerDark,
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = CyanAccent,
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF13293D),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = ShieldEmerald,
    onTertiary = Color(0xFF022C22),
    background = ObsidianBg,
    onBackground = InkWhite,
    surface = ObsidianSurface,
    onSurface = InkWhite,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = InkMutedDark,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    outline = ObsidianBorder,
    outlineVariant = Color(0xFF1E293B),
    error = DangerCoral,
    onError = Color.White
)

private val LightColors = lightColorScheme(
    primary = ElectricTealLight,
    onPrimary = Color.White,
    primaryContainer = ElectricTealContainerLight,
    onPrimaryContainer = Color(0xFF115E59),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = ShieldEmerald,
    onTertiary = Color.White,
    background = AlabasterBg,
    onBackground = InkSlate,
    surface = AlabasterSurface,
    onSurface = InkSlate,
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = InkMutedLight,
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = AlabasterBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = DangerCoral,
    onError = Color.White
)

private val PrivateStealthColors = darkColorScheme(
    primary = StealthAccent,
    onPrimary = StealthOnAccent,
    primaryContainer = StealthAccentContainer,
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = ElectricTeal,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF1E1B4B),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = ShieldEmerald,
    onTertiary = Color(0xFF022C22),
    background = StealthBg,
    onBackground = Color(0xFFF5F3FF),
    surface = StealthSurface,
    onSurface = Color(0xFFF5F3FF),
    surfaceVariant = StealthSurfaceVariant,
    onSurfaceVariant = Color(0xFFC4B5FD),
    surfaceContainerHigh = Color(0xFF2E254D),
    outline = Color(0xFF3F3366),
    outlineVariant = Color(0xFF2B214A),
    error = DangerCoral,
    onError = Color.White
)

@Composable
fun VeloBrowserTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    isPrivateMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when {
        isPrivateMode -> true
        themeMode == ThemeMode.DARK -> true
        themeMode == ThemeMode.LIGHT -> false
        else -> systemDark
    }

    val colorScheme = when {
        isPrivateMode -> PrivateStealthColors
        useDark -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !useDark
                    isAppearanceLightNavigationBars = !useDark
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VeloTypography,
        content = content
    )
}
