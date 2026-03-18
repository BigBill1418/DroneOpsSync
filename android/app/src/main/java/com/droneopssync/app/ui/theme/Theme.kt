package com.droneopssync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BhqColorScheme = darkColorScheme(
    primary        = BhqCyan,
    onPrimary      = BhqNavy,
    background     = BhqNavy,
    onBackground   = BhqWhite,
    surface        = BhqNavyMid,
    onSurface      = BhqWhite,
    surfaceVariant = BhqNavyLight,
    error          = BhqRed,
    onError        = BhqWhite,
)

@Composable
fun DroneOpsSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BhqColorScheme,
        content = content
    )
}
