package com.lop.budget.ui.screens.goals

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.lop.budget.R
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.usecase.financial.DeleteFinancialItemUseCase
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.ConfirmDeleteSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Suppression demandée (LOP-204) : l'élément visé, l'écriture en cours, son échec (CA-07). */
data class DeleteRequest(
    val kind: FinancialItemKind,
    val id: Long,
    val name: String,
    val running: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Confirmation de suppression partagée par la liste de Suivi et le détail : une seule machine, pour
 * qu'ils ne se comportent jamais différemment (CA-01, P-2).
 *
 * Demander ou annuler n'écrit rien (I-1). Seul [confirm] appelle le use case, une fois à la fois ;
 * rien n'est retiré de l'écran avant la fin de l'écriture, et un échec laisse la confirmation
 * ouverte pour réessayer (CA-07, P-4).
 */
class DeleteConfirmation(
    private val scope: CoroutineScope,
    private val deleteItem: DeleteFinancialItemUseCase,
) {
    private val _request = MutableStateFlow<DeleteRequest?>(null)

    /** `null` : aucune confirmation ouverte. */
    val request: StateFlow<DeleteRequest?> = _request.asStateFlow()

    /** Ouvre la confirmation ; sans effet si une autre est déjà ouverte. */
    fun ask(kind: FinancialItemKind, id: Long, name: String) {
        if (_request.value == null) _request.value = DeleteRequest(kind, id, name)
    }

    /** Annuler, voile ou retour : ferme sans rien écrire, sauf pendant l'écriture (P-4). */
    fun dismiss() {
        if (_request.value?.running != true) _request.value = null
    }

    /** Un appui pendant l'écriture est ignoré (CA-07). [onDeleted] ne suit qu'un succès. */
    fun confirm(onDeleted: () -> Unit = {}) {
        val request = _request.value ?: return
        if (request.running) return
        _request.value = request.copy(running = true, failed = false)
        scope.launch {
            try {
                deleteItem(request.kind, request.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _request.value = request.copy(running = false, failed = true)
                return@launch
            }
            _request.value = null
            onDeleted()
        }
    }
}

/**
 * La confirmation commune : titre selon le type et le nom, avertissement du CA-01 mot pour mot,
 * exactement Supprimer et Annuler. Aucun choix de portée récurrente.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FinancialItemDeleteSheet(
    request: DeleteRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDeleteSheet(
        title = stringResource(deleteTitle(request.kind), request.name),
        message = stringResource(R.string.financial_delete_message),
        confirmLabel = stringResource(R.string.financial_delete),
        dismissLabel = stringResource(R.string.financial_delete_cancel),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmTag = TestTags.FINANCIAL_DELETE_CONFIRM,
        dismissTag = TestTags.FINANCIAL_DELETE_CANCEL,
        busy = request.running,
        error = if (request.failed) stringResource(R.string.financial_delete_error) else null,
        errorTag = TestTags.FINANCIAL_DELETE_ERROR,
        // Feuille modale = racine de composition à part : ses tags ne sont pas hérités.
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(TestTags.FINANCIAL_DELETE_SHEET),
    )
}

@StringRes
private fun deleteTitle(kind: FinancialItemKind) = when (kind) {
    FinancialItemKind.GOAL -> R.string.financial_delete_title_goal
    FinancialItemKind.DEBT -> R.string.financial_delete_title_debt
    FinancialItemKind.RECEIVABLE -> R.string.financial_delete_title_receivable
}
