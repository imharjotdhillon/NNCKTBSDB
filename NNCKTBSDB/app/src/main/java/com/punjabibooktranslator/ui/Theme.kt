package com.punjabibooktranslator.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.punjabibooktranslator.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A4E2D), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0DFD0), onPrimaryContainer = Color(0xFF2B170A),
    secondary = Color(0xFF6F5B4B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADBCB), onSecondaryContainer = Color(0xFF2A1F14),
    tertiary = Color(0xFF5B6B4F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE8CF), onTertiaryContainer = Color(0xFF18220F),
    inverseSurface = Color(0xFF34302A), inverseOnSurface = Color(0xFFF8F0E8), inversePrimary = Color(0xFFE8B98F),
    background = Color(0xFFFBF8F3), onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFBF8F3), onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEDE4DA), onSurfaceVariant = Color(0xFF4F4539),
    outline = Color(0xFF81756A), outlineVariant = Color(0xFFD3C4B5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F1EA),
    surfaceContainer = Color(0xFFF1EBE3), surfaceContainerHigh = Color(0xFFEBE5DC),
    surfaceContainerHighest = Color(0xFFE5DFD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8B98F), onPrimary = Color(0xFF442B12),
    primaryContainer = Color(0xFF5E4127), onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFD7C3B3), onSecondary = Color(0xFF3B2E22),
    secondaryContainer = Color(0xFF54453A), onSecondaryContainer = Color(0xFFF2E0D0),
    tertiary = Color(0xFFBFCDB0), onTertiary = Color(0xFF2B3720),
    tertiaryContainer = Color(0xFF415036), onTertiaryContainer = Color(0xFFDBE9CB),
    inverseSurface = Color(0xFFE6E1DA), inverseOnSurface = Color(0xFF34302A), inversePrimary = Color(0xFF7A4E2D),
    background = Color(0xFF14120F), onBackground = Color(0xFFE6E1DA),
    surface = Color(0xFF14120F), onSurface = Color(0xFFE6E1DA),
    surfaceVariant = Color(0xFF4F4539), onSurfaceVariant = Color(0xFFD3C4B5),
    outline = Color(0xFF9C8F80), outlineVariant = Color(0xFF4F4539),
    surfaceContainerLowest = Color(0xFF0F0D0A), surfaceContainerLow = Color(0xFF1B1814),
    surfaceContainer = Color(0xFF1F1C18), surfaceContainerHigh = Color(0xFF2A2621),
    surfaceContainerHighest = Color(0xFF35312B),
)

@Composable
fun BookTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = BookTypography, shapes = BookShapes, content = content)
}

/** Softer, consistent corners: cards and dialogs rounder than the Material default, small controls unchanged. */
private val BookShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * A calmer type scale: fewer weights, generous line height. Gurmukhi needs more vertical room than Latin, so body text uses
 * roomy line heights. No custom font family is set so Android's own Gurmukhi fallback keeps working everywhere except the reader.
 */
private val BookTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

/** The flat tinted fill used by every card in the app. */
@Composable
fun tonalCardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** The standard back arrow for top bars. */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}
