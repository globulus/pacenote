package com.theglobulus.pacenote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Amber = Color(0xFFF2A541)
val Good = Color(0xFF5BB974)
val Bad = Color(0xFFE5675A)

private val Dark = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF4A3510),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondaryContainer = Color(0xFF34312B),
    onSecondaryContainer = Color(0xFFEDE6DA),
    background = Color(0xFF141312),
    onBackground = Color(0xFFF2EFE9),
    surface = Color(0xFF141312),
    onSurface = Color(0xFFF2EFE9),
    surfaceVariant = Color(0xFF2A2825),
    onSurfaceVariant = Color(0xFFB3AEA5),
    surfaceContainerLowest = Color(0xFF0F0E0D),
    surfaceContainerLow = Color(0xFF1B1A18),
    surfaceContainer = Color(0xFF201F1C),
    surfaceContainerHigh = Color(0xFF2A2825),
    surfaceContainerHighest = Color(0xFF34322E),
    outline = Color(0xFF4A4741),
    outlineVariant = Color(0xFF34322E),
    error = Bad,
)

private val Light = lightColorScheme(
    primary = Color(0xFF8F5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2C1A00),
    secondaryContainer = Color(0xFFF0E6D6),
    onSecondaryContainer = Color(0xFF241C10),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1E1C19),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1E1C19),
    surfaceVariant = Color(0xFFEDE7DD),
    onSurfaceVariant = Color(0xFF5E5850),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2EA),
    surfaceContainer = Color(0xFFF2ECE3),
    surfaceContainerHigh = Color(0xFFECE6DC),
    surfaceContainerHighest = Color(0xFFE6E0D6),
    outline = Color(0xFF8C857A),
    outlineVariant = Color(0xFFD8D1C6),
    error = Color(0xFFB3261E),
)

@Composable
fun PacenoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
