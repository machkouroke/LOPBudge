package com.lop.budget.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lop.budget.domain.model.CurrencyCatalog
import com.lop.budget.domain.usecase.DetectionSettings
import com.lop.budget.domain.usecase.InboxSettings
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
) : DetectionSettings, InboxSettings {
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

    /**
     * Devise d'affichage de l'application : **source de vérité unique**, lue par tous les écrans
     * qui affichent un montant (I-3).
     *
     * La valeur stockée est confrontée au catalogue à la lecture, et pas seulement à l'écriture :
     * une préférence écrite par une version antérieure — chaîne libre, casse différente, code
     * retiré depuis — ne doit pas remonter jusqu'au formatage des montants. Elle est ignorée au
     * profit de l'euro plutôt que propagée (CA-11), et l'absence de préférence donne la même
     * valeur (CA-01).
     */
    val currency: Flow<String> = context.dataStore.data.map {
        CurrencyCatalog.byCodeOrDefault(it[Keys.CURRENCY]).code
    }
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

    /**
     * N'écrit que si [value] est **exactement** un code du catalogue (I-1).
     *
     * Le refus est silencieux : la seule entrée de l'application est une liste fermée, si bien
     * qu'un code absent ne vient pas de l'utilisateur mais du code appelant. La garde tient ici
     * plutôt que dans le ViewModel — elle vaut alors pour tout futur appelant, et rien ne peut
     * persister une devise qu'aucun écran ne saurait afficher.
     */
    suspend fun setCurrency(value: String) {
        val currency = CurrencyCatalog.byCodeOrNull(value) ?: return
        context.dataStore.edit { it[Keys.CURRENCY] = currency.code }
    }
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

    override suspend fun isNotificationDetectionEnabledOnce(): Boolean = notificationDetectionEnabled.first()

    suspend fun lastAccountIdOnce(): Long? = lastAccountId.first()

    /**
     * Compte de destination des propositions acceptées (P-5).
     *
     * Le réglage dédié n'existe pas encore : on retombe sur le dernier compte utilisé, qui est la
     * donnée la plus proche déjà persistée. Tant qu'aucun compte n'a servi, la valeur est nulle et
     * CA-20 s'applique — l'enregistrement doit être refusé avec un message, jamais complété par un
     * identifiant choisi par le code (I-8).
     */
    override suspend fun defaultAccountIdOnce(): Long? = lastAccountIdOnce()

    override fun isAllowedNotificationSource(packageName: String): Boolean {
        // MVP : sources fixes
        return packageName in setOf(
            "com.google.android.apps.walletnfcrel", // Google Wallet/Pay
            "com.samsung.android.spay", // Samsung Wallet
        )
    }
}
