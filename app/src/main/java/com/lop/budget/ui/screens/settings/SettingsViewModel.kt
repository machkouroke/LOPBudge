package com.lop.budget.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.ui.theme.ThemeMode
import com.lop.budget.ui.util.UiEvent
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val uiEvents = UiEvent.Emitter()

    val uiState = combine(
        settings.currency, settings.geminiKey, settings.themeMode, settings.dynamicColor,
    ) { currency, key, theme, dynamic ->
        SettingsUiState(currency, key, theme, dynamic)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setCurrency(v: String) = viewModelScope.launch {
        try {
            settings.setCurrency(v)
        } catch (e: Exception) {
            Log.e("Settings", "Failed to save currency", e)
            uiEvents.send(UiEvent.ShowSnackbar("Erreur lors de la sauvegarde de la devise : ${e.localizedMessage}"))
        }
    }

    fun setGeminiKey(v: String) = viewModelScope.launch {
        try {
            settings.setGeminiKey(v)
        } catch (e: Exception) {
            Log.e("Settings", "Failed to save Gemini key", e)
            uiEvents.send(UiEvent.ShowSnackbar("Erreur lors de la sauvegarde de la clé : ${e.localizedMessage}"))
        }
    }

    fun setThemeMode(m: ThemeMode) = viewModelScope.launch {
        try {
            settings.setThemeMode(m)
        } catch (e: Exception) {
            Log.e("Settings", "Failed to save theme mode", e)
            uiEvents.send(UiEvent.ShowSnackbar("Erreur lors du changement de thème : ${e.localizedMessage}"))
        }
    }

    fun setDynamicColor(b: Boolean) = viewModelScope.launch {
        try {
            settings.setDynamicColor(b)
        } catch (e: Exception) {
            Log.e("Settings", "Failed to save dynamic color setting", e)
            uiEvents.send(UiEvent.ShowSnackbar("Erreur lors du changement de couleur : ${e.localizedMessage}"))
        }
    }
}
