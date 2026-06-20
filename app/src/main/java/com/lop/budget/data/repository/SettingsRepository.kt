package com.lop.budget.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "lop_settings")

/**
 * Préférences persistées : devise (EUR par défaut, configurable), clé API Gemini
 * saisie par l'utilisateur, mode de thème et activation de la couleur dynamique.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val CURRENCY = stringPreferencesKey("currency")
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = stringPreferencesKey("dynamic_color")
    }

    val currency: Flow<String> = context.dataStore.data.map { it[Keys.CURRENCY] ?: "EUR" }
    val geminiKey: Flow<String> = context.dataStore.data.map { it[Keys.GEMINI_KEY] ?: "" }
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        runCatching { ThemeMode.valueOf(it[Keys.THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { (it[Keys.DYNAMIC_COLOR] ?: "true").toBoolean() }

    suspend fun setCurrency(value: String) = context.dataStore.edit { it[Keys.CURRENCY] = value }
    suspend fun setGeminiKey(value: String) = context.dataStore.edit { it[Keys.GEMINI_KEY] = value }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled.toString() }
}
