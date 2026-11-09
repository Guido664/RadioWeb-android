package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val RadioWebDarkColorScheme = darkColorScheme(
    primary = Amber500,
    onPrimary = Slate950,
    primaryContainer = AmberContainerDark,
    onPrimaryContainer = OnAmberContainerDark,
    secondary = Amber400,
    onSecondary = Slate950,
    secondaryContainer = Slate800,
    onSecondaryContainer = Slate100,
    tertiary = Orange500,
    onTertiary = Color.White,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    outline = Slate700,
    outlineVariant = Slate800,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainerDark,
    onErrorContainer = Color(0xFFFECACA)
)

val RadioWebShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RadioWebDarkColorScheme,
        typography = Typography,
        shapes = RadioWebShapes,
        content = content
    )
}
