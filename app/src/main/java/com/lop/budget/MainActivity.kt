package com.lop.budget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lop.budget.ui.navigation.LopNavHost
import com.lop.budget.ui.screens.settings.SettingsViewModel
import com.lop.budget.ui.theme.LopBudgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsVm: SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val settings by settingsVm.uiState.collectAsState()
            LopBudgeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                LopNavHost(startRoute = intent.getStringExtra("route"))
            }
        }
    }}