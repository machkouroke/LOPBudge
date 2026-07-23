package com.lop.budget.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val currency: String = "EUR",
    val geminiKey: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val notificationDetectionEnabled: Boolean = false,
    val useLocalLlm: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val uiState = combine(
        settings.currency,
        settings.geminiKey,
        settings.themeMode,
        settings.dynamicColor,
        settings.notificationDetectionEnabled,
        settings.useLocalLlm,
    ) { args ->
        SettingsUiState(
            currency = args[0] as String,
            geminiKey = args[1] as String,
            themeMode = args[2] as ThemeMode,
            dynamicColor = args[3] as Boolean,
            notificationDetectionEnabled = args[4] as Boolean,
            useLocalLlm = args[5] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setCurrency(v: String) = viewModelScope.launch { settings.setCurrency(v) }
    fun setGeminiKey(v: String) = viewModelScope.launch { settings.setGeminiKey(v) }
    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { settings.setThemeMode(m) }
    fun setDynamicColor(b: Boolean) = viewModelScope.launch { settings.setDynamicColor(b) }

    fun setNotificationDetectionEnabled(b: Boolean) =
        viewModelScope.launch { settings.setNotificationDetectionEnabled(b) }

    fun setUseLocalLlm(b: Boolean) =
        viewModelScope.launch { settings.setUseLocalLlm(b) }
}
