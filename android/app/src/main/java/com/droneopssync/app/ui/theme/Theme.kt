package com.droneopssync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DocColorScheme = darkColorScheme(
    primary        = DocCyan,
    onPrimary      = DocDeep,
    background     = DocDeep,
    onBackground   = DocWhite,
    surface        = DocPanel,
    onSurface      = DocWhite,
    surfaceVariant = DocSurface,
    error          = DocRed,
    onError        = DocWhite,
)

@Composable
fun DroneOpsSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DocColorScheme,
        content = content
    )
}
