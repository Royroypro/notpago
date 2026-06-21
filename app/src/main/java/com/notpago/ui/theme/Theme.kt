package com.notpago.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    secondary = Accent2,
    tertiary = Accent3,
    background = Ink,
    surface = Ink,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Ink,
    onBackground = Color.White,
    onSurface = Color.White,
    outline = Border2
)

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    secondary = Accent2,
    tertiary = Accent3,
    background = Surface2,
    surface = Surface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Ink,
    onBackground = Ink,
    onSurface = Ink,
    outline = Border,
    surfaceVariant = Surface3
)

@Composable
fun NotPagoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = com.notpago.ui.theme.Typography,
        content = content
    )
}
