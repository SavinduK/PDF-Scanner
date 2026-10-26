package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ScannerDarkColorScheme =
    darkColorScheme(
        primary = ScannerPrimaryBlue,
        onPrimary = ScannerOnPrimaryBlue,
        primaryContainer = ScannerPrimaryContainer,
        onPrimaryContainer = ScannerOnPrimaryContainer,
        secondary = ScannerSkyBadge,
        onSecondary = ScannerOnSkyBadge,
        secondaryContainer = ScannerOceanContainer,
        onSecondaryContainer = ScannerOnOceanContainer,
        tertiary = ScannerPrimaryBlue,
        onTertiary = ScannerOnPrimaryBlue,
        tertiaryContainer = ScannerOceanContainer,
        onTertiaryContainer = ScannerOnOceanContainer,
        background = ScannerDarkBackground,
        onBackground = ScannerOnSurface,
        surface = ScannerSurface,
        onSurface = ScannerOnSurface,
        surfaceVariant = ScannerSurfaceContainerHigh,
        onSurfaceVariant = ScannerOnSurfaceVariant,
        surfaceContainerLowest = ScannerDarkCanvas,
        surfaceContainerLow = ScannerSurfaceContainerLow,
        surfaceContainer = ScannerSurfaceContainer,
        surfaceContainerHigh = ScannerSurfaceContainerHigh,
        surfaceContainerHighest = ScannerSurfaceContainerHighest,
        outline = ScannerOutline,
        outlineVariant = ScannerOutlineVariant,
    )

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ScannerDarkColorScheme,
        typography = Typography,
        content = content
    )
}
