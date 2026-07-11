package com.lop.budget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.ui.navigation.LopNavHost
import com.lop.budget.ui.screens.settings.SettingsViewModel
import com.lop.budget.ui.theme.LopBudgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            // IMPORTANT: sans ça, LopBudgeTheme utilise ses valeurs par défaut et les changements
            // dans l'écran Réglages n'impactent jamais le thème de l'app.
            val settingsVm: SettingsViewModel = hiltViewModel()
            val settings by settingsVm.uiState.collectAsStateWithLifecycle()

            LopBudgeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                LopNavHost(startRoute = intent.getStringExtra("route"))
            }
        }
    }
}
