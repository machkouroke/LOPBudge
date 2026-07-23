package com.lop.budget.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "lop_settings")

/**
 * Préférences persistées : devise, clé API Gemini, mode de thème, etc.
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

        // Notifications
        val NOTIF_DETECTION = stringPreferencesKey("notif_tx_detection")
        val USE_LOCAL_LLM = stringPreferencesKey("use_local_llm")
        val LLM_DOWNLOAD_ID = stringPreferencesKey("llm_download_id")

        // UX helpers
        val LAST_ACCOUNT_ID = stringPreferencesKey("last_account_id")
    }

    val currency: Flow<String> = context.dataStore.data.map { it[Keys.CURRENCY] ?: "EUR" }
    val geminiKey: Flow<String> = context.dataStore.data.map { it[Keys.GEMINI_KEY] ?: "" }
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        runCatching { ThemeMode.valueOf(it[Keys.THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { (it[Keys.DYNAMIC_COLOR] ?: "true").toBoolean() }

    val notificationDetectionEnabled: Flow<Boolean> =
        context.dataStore.data.map { (it[Keys.NOTIF_DETECTION] ?: "false").toBoolean() }

    val useLocalLlm: Flow<Boolean> =
        context.dataStore.data.map { (it[Keys.USE_LOCAL_LLM] ?: "false").toBoolean() }

    val llmDownloadId: Flow<Long?> =
        context.dataStore.data.map { it[Keys.LLM_DOWNLOAD_ID]?.toLongOrNull() }

    /** Dernier compte utilisé (pour pré-sélection à l'ajout). */
    val lastAccountId: Flow<Long?> = context.dataStore.data.map {
        it[Keys.LAST_ACCOUNT_ID]?.toLongOrNull()
    }

    suspend fun setCurrency(value: String) = context.dataStore.edit { it[Keys.CURRENCY] = value }
    suspend fun setGeminiKey(value: String) = context.dataStore.edit { it[Keys.GEMINI_KEY] = value }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled.toString() }

    suspend fun setNotificationDetectionEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.NOTIF_DETECTION] = enabled.toString() }

    suspend fun setUseLocalLlm(enabled: Boolean) =
        context.dataStore.edit { it[Keys.USE_LOCAL_LLM] = enabled.toString() }

    suspend fun setLlmDownloadId(id: Long?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.LLM_DOWNLOAD_ID) else it[Keys.LLM_DOWNLOAD_ID] = id.toString()
    }

    suspend fun setLastAccountId(id: Long?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.LAST_ACCOUNT_ID) else it[Keys.LAST_ACCOUNT_ID] = id.toString()
    }

    suspend fun isNotificationDetectionEnabledOnce(): Boolean = notificationDetectionEnabled.first()

    suspend fun lastAccountIdOnce(): Long? = lastAccountId.first()

    fun isAllowedNotificationSource(packageName: String): Boolean {
        // MVP : sources fixes
        return packageName in setOf(
            "com.google.android.apps.walletnfcrel", // Google Wallet/Pay
            "com.samsung.android.spay", // Samsung Wallet
        )
    }
}
