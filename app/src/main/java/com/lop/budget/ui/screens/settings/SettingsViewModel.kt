package com.lop.budget.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AppCurrency
import com.lop.budget.domain.model.CurrencyCatalog
import com.lop.budget.domain.usecase.SearchCurrenciesUseCase
import com.lop.budget.notifications.QwenDownloadManager
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    /** Devise complète, et pas seulement son code : la ligne des réglages affiche aussi son nom, son symbole et son drapeau (CA-02). */
    val currency: AppCurrency = CurrencyCatalog.default,
    val geminiKey: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val notificationDetectionEnabled: Boolean = false,
    val useLocalLlm: Boolean = false,
    val isModelInstalled: Boolean = false,
    val downloadStatus: QwenDownloadManager.DownloadStatus = QwenDownloadManager.DownloadStatus.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val downloadManager: QwenDownloadManager,
    private val searchCurrencies: SearchCurrenciesUseCase,
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
            currency = CurrencyCatalog.byCodeOrDefault(args[0] as String),
            geminiKey = args[1] as String,
            themeMode = args[2] as ThemeMode,
            dynamicColor = args[3] as Boolean,
            notificationDetectionEnabled = args[4] as Boolean,
            useLocalLlm = args[5] as Boolean,
            isModelInstalled = downloadManager.isModelInstalled()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _downloadStatus = MutableStateFlow<QwenDownloadManager.DownloadStatus>(QwenDownloadManager.DownloadStatus.Idle)
    val downloadStatus = _downloadStatus.asStateFlow()

    init {
        viewModelScope.launch {
            settings.llmDownloadId.collect { id ->
                if (id != null) {
                    downloadManager.getDownloadProgress(id).collect { status ->
                        _downloadStatus.value = status
                        if (status is QwenDownloadManager.DownloadStatus.Success) {
                            settings.setLlmDownloadId(null)
                        }
                    }
                }
            }
        }
    }

    fun startModelDownload() {
        viewModelScope.launch {
            val id = downloadManager.startDownload()
            settings.setLlmDownloadId(id)
        }
    }

    private val _currencyQuery = MutableStateFlow("")

    /** Saisie du champ de recherche de la feuille. N'écrit rien dans les préférences (I-5). */
    val currencyQuery: StateFlow<String> = _currencyQuery.asStateFlow()

    /**
     * Catalogue filtré par [currencyQuery], dans l'ordre de P-3.
     *
     * Le catalogue complet sert de valeur initiale : la feuille l'affiche entier dès l'ouverture,
     * sans attendre une première émission (CA-03).
     */
    val currencyResults: StateFlow<List<AppCurrency>> = _currencyQuery
        .map { searchCurrencies(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CurrencyCatalog.all)

    fun onCurrencyQueryChange(query: String) { _currencyQuery.value = query }

    /**
     * Referme la feuille sans rien persister (CA-07). La saisie est remise à zéro pour que la
     * prochaine ouverture reparte du catalogue complet.
     */
    fun onCurrencySheetDismissed() { _currencyQuery.value = "" }

    /**
     * Seul chemin d'écriture de la devise : un choix **explicite** dans la liste (I-5).
     *
     * Le paramètre est une [AppCurrency] et non un code libre — l'appelant ne peut proposer que ce
     * que le catalogue contient (I-1), la saisie au clavier a disparu avec le champ texte.
     */
    fun setCurrency(currency: AppCurrency) = viewModelScope.launch {
        settings.setCurrency(currency.code)
        _currencyQuery.value = ""
    }
    fun setGeminiKey(v: String) = viewModelScope.launch { settings.setGeminiKey(v) }
    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { settings.setThemeMode(m) }
    fun setDynamicColor(b: Boolean) = viewModelScope.launch { settings.setDynamicColor(b) }

    fun setNotificationDetectionEnabled(b: Boolean) =
        viewModelScope.launch { settings.setNotificationDetectionEnabled(b) }

    fun setUseLocalLlm(b: Boolean) =
        viewModelScope.launch { settings.setUseLocalLlm(b) }
}
