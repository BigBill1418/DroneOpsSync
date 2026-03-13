package com.dronedump.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DroneDarkColors = darkColorScheme(
    primary        = DronePrimary,
    onPrimary      = DroneBackground,
    background     = DroneBackground,
    onBackground   = DroneTextPrimary,
    surface        = DroneSurface,
    onSurface      = DroneTextPrimary,
    surfaceVariant = DroneCard,
    error          = DroneError,
    onError        = DroneTextPrimary,
)

@Composable
fun DroneDumpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DroneDarkColors,
        content = content
    )
}
