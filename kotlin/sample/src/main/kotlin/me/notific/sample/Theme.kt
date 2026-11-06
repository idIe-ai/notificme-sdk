package me.notific.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette (brand/README.md). Dark theme is neutral black, no blue tint.
private val Navy = Color(0xFF0B1F44)
private val Blue = Color(0xFF1E3A8A)
private val Orange = Color(0xFFFF6A00)
private val OrangeLight = Color(0xFFFF8A3D)
private val Black = Color(0xFF0A0A0F)

private val Light = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE7D6),
    onPrimaryContainer = Navy,
    secondaryContainer = Color(0xFFFFE7D6),
    onSecondaryContainer = Navy,
    tertiaryContainer = Color(0xFFDCE3F5),
    onTertiaryContainer = Navy,
    secondary = Navy,
    onSecondary = Color.White,
    tertiary = Blue,
    background = Color.White,
    onBackground = Navy,
    surface = Color.White,
    onSurface = Navy,
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F9),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFF3F4F6),
    surfaceContainerHighest = Color(0xFFE5E7EB),
    outlineVariant = Color(0xFFE5E7EB),
)

private val Dark = darkColorScheme(
    primary = OrangeLight,
    onPrimary = Black,
    primaryContainer = Color(0xFF3A2414),
    onPrimaryContainer = Color(0xFFFFD7BD),
    secondaryContainer = Color(0xFF3A2414),
    onSecondaryContainer = Color(0xFFFFD7BD),
    tertiaryContainer = Color(0xFF1E2640),
    onTertiaryContainer = Color(0xFFDCE3F5),
    secondary = Color(0xFFE5E7EB),
    tertiary = Color(0xFF93A4D6),
    background = Black,
    onBackground = Color(0xFFF3F4F6),
    surface = Black,
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF1C1C24),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainerLowest = Black,
    surfaceContainerLow = Color(0xFF15151C),
    surfaceContainer = Color(0xFF15151C),
    surfaceContainerHigh = Color(0xFF1C1C24),
    surfaceContainerHighest = Color(0xFF24242E),
    outlineVariant = Color(0xFF2A2A33),
)

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
