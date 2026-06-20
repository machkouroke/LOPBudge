package com.lop.budget.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "lop_settings")

/**
 * Préférences persistées : devise (EUR par défaut, configurable), clé API Gemini
 * saisie par l'utilisateur, mode de thème et activation de la couleur dynamique.
 *
 * La clé API Gemini est stockée dans EncryptedSharedPreferences (chiffrement AES-256)
 * plutôt qu'en clair dans DataStore.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val CURRENCY = stringPreferencesKey("currency")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = stringPreferencesKey("dynamic_color")
    }

    private companion object {
        const val ENCRYPTED_PREFS_NAME = "lop_secure_prefs"
        const val KEY_GEMINI_API = "gemini_api_key"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            ENCRYPTED_PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _geminiKey = MutableStateFlow(
        runCatching { encryptedPrefs.getString(KEY_GEMINI_API, "") ?: "" }.getOrDefault("")
    )

    val currency: Flow<String> = context.dataStore.data.map { it[Keys.CURRENCY] ?: "EUR" }
    val geminiKey: Flow<String> = _geminiKey.asStateFlow()
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        runCatching { ThemeMode.valueOf(it[Keys.THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { (it[Keys.DYNAMIC_COLOR] ?: "true").toBoolean() }

    suspend fun setCurrency(value: String) = context.dataStore.edit { it[Keys.CURRENCY] = value }

    suspend fun setGeminiKey(value: String) {
        encryptedPrefs.edit().putString(KEY_GEMINI_API, value).apply()
        _geminiKey.value = value
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled.toString() }
}
