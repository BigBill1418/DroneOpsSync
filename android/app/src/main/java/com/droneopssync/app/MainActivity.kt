package com.droneopssync.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.droneopssync.app.ui.screens.DiagScreen
import com.droneopssync.app.ui.screens.HomeScreen
import com.droneopssync.app.ui.screens.SettingsScreen
import com.droneopssync.app.ui.screens.SplashScreen
import com.droneopssync.app.ui.theme.DroneOpsSyncTheme
import com.droneopssync.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request MANAGE_EXTERNAL_STORAGE on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        val prefs = getSharedPreferences("droneopssync_prefs", MODE_PRIVATE)
        viewModel.loadSettings(prefs)
        viewModel.checkServerHealth()

        setContent {
            DroneOpsSyncTheme {
                val navController = rememberNavController()
                val sharedPrefs = remember { prefs }

                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen(
                            onFinished = {
                                navController.navigate("home") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToDiag = { navController.navigate("diag") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            prefs = sharedPrefs,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("diag") {
                        DiagScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
