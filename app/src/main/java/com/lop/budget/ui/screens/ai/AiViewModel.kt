package com.lop.budget.ui.screens.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.ai.GeminiClient
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(val fromUser: Boolean, val text: String)

data class AiUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage(false, "Bonjour ! Je suis ton assistant budgétaire. Pose-moi des questions sur tes finances : je peux analyser et conseiller, mais je ne modifie rien moi-même.")
    ),
    val loading: Boolean = false,
    val hasKey: Boolean = true,
)

/**
 * Assistant en lecture seule : on prépare un résumé chiffré du budget courant
 * et on l'injecte dans le prompt système. L'IA conseille mais n'exécute rien.
 */
@HiltViewModel
class AiViewModel @Inject constructor(
    private val gemini: GeminiClient,
    private val repo: BudgetRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AiUiState())
    val state = _state.asStateFlow()

    private val systemPrompt = """
        Tu es l'assistant budgétaire de l'application LOPBudge.
        Règles strictes :
        - Tu réponds en français, de façon concise, bienveillante et actionnable.
        - Tu te limites au CONSEIL, à l'ANALYSE et au QUESTIONNEMENT.
        - Tu n'exécutes JAMAIS d'opération (pas de création, modification ou suppression de transaction, compte, objectif). Si on te le demande, explique comment l'utilisateur peut le faire lui-même dans l'app.
        - Base tes réponses sur le contexte financier fourni ci-dessous. Si une donnée manque, dis-le clairement.
    """.trimIndent()

    fun send(message: String) {
        if (message.isBlank()) return
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(true, message),
            loading = true,
        )
        viewModelScope.launch {
            val key = settings.geminiKey.first()
            val context = buildBudgetContext()
            val history = _state.value.messages
                .dropLast(1)
                .map { (if (it.fromUser) "user" else "model") to it.text }
            val result = gemini.ask(
                apiKey = key,
                systemPrompt = "$systemPrompt\n\n--- Contexte financier (lecture seule) ---\n$context",
                history = history,
                userMessage = message,
            )
            val reply = result.getOrElse { "⚠️ ${it.message}" }
            _state.value = _state.value.copy(
                messages = _state.value.messages + ChatMessage(false, reply),
                loading = false,
                hasKey = key.isNotBlank(),
            )
        }
    }

    private suspend fun buildBudgetContext(): String {
        val currency = settings.currency.first()
        val txs = repo.observeTransactions().first()
        val income = txs.filter { it.transaction.type == TransactionType.INCOME && it.transaction.status == TransactionStatus.PAID }
            .sumOf { it.transaction.amount }
        val expense = txs.filter { it.transaction.type == TransactionType.EXPENSE && it.transaction.status == TransactionStatus.PAID }
            .sumOf { it.transaction.amount }
        val planned = txs.filter { it.transaction.status == TransactionStatus.PLANNED }
        val byCat = txs.filter { it.transaction.type == TransactionType.EXPENSE }
            .groupBy { it.category?.name ?: "Autre" }
            .mapValues { e -> e.value.sumOf { it.transaction.amount } }
            .entries.sortedByDescending { it.value }
            .take(5)
            .joinToString(", ") { "${it.key}: ${Format.money(it.value, currency)}" }

        return buildString {
            appendLine("Devise: $currency")
            appendLine("Revenus payés (cumulés): ${Format.money(income, currency)}")
            appendLine("Dépenses payées (cumulées): ${Format.money(expense, currency)}")
            appendLine("Solde net actuel: ${Format.money(income - expense, currency)}")
            appendLine("Top catégories de dépense: $byCat")
            appendLine("Transactions planifiées à venir: ${planned.size}")
        }
    }
}
