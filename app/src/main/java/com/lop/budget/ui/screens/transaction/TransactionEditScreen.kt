package com.lop.budget.ui.screens.transaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.financial.LinkableItem
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.AccountBottomSheet
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.LopDatePicker
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.PickerBottomSheet
import com.lop.budget.ui.components.rememberShowSkeleton
import com.lop.budget.ui.components.skeleton
import com.lop.budget.util.IconMapper


enum class EditSheet { Category, Account, Tags, Goal, Debt, Date }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditScreen(
    onDone: (Long) -> Unit,
    /** CA-16 : la catégorie créée depuis le sélecteur prend le type de la transaction (P-14). */
    onNavigateToCreateCategory: (TransactionType) -> Unit,
    vm: TransactionEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val isLoaded by vm.loadedState.collectAsStateWithLifecycle()
    val showSkeleton = rememberShowSkeleton(loading = !isLoaded)
    val categories by vm.categories.collectAsStateWithLifecycle()
    val recentCategories by vm.recentCategories.collectAsStateWithLifecycle()
    val suggestedCategories by vm.suggestedCategories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val debts by vm.debts.collectAsStateWithLifecycle()
    val receivables by vm.receivables.collectAsStateWithLifecycle()
    val linkedGoal by vm.linkedGoal.collectAsStateWithLifecycle()
    val linkedLoan by vm.linkedLoan.collectAsStateWithLifecycle()
    // CA-01 de LOP-5 : prêts groupés, dettes puis créances. CA-02 : un rattachement archivé n'est pas
    // un nouveau choix, mais il reste en tête de son groupe, coché, pour pouvoir être conservé.
    val goalChoices = withArchivedLink(goals, linkedGoal, FinancialItemKind.GOAL)
    val loanChoices = withArchivedLink(debts, linkedLoan, FinancialItemKind.DEBT) +
        withArchivedLink(receivables, linkedLoan, FinancialItemKind.RECEIVABLE)
    val debtsTitle = stringResource(R.string.debts_title)
    val receivablesTitle = stringResource(R.string.receivables_title)
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()
    val currency by vm.currency.collectAsStateWithLifecycle()
    val fieldErrors by vm.fieldErrors.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val missingDayPrompt by vm.missingDayPrompt.collectAsStateWithLifecycle()

    // CA-04 : résolution des @StringRes au plus près de l'affichage ; le ViewModel ne
    // manipule que des identifiants de ressource, jamais de texte localisé.
    // Ni erreur de montant ni erreur de catégorie ici : l'un ou l'autre manquant grise Enregistrer
    // (P-6 de LOP-227).
    val accountError = fieldErrors[TransactionFormField.ACCOUNT]?.let { stringResource(it) }
    // CA-05 de LOP-5 : choix devenu indisponible, signalé sous son champ.
    val goalError = fieldErrors[TransactionFormField.GOAL]?.let { stringResource(it) }
    val loanError = fieldErrors[TransactionFormField.LOAN]?.let { stringResource(it) }
    // CA-05 : message de refus de création d'un tag, affiché dans la feuille de sélection.
    val tagNameError = fieldErrors[TransactionFormField.TAG_NAME]?.let { stringResource(it) }

    val snackbarHostState = remember { SnackbarHostState() }
    val saveErrorMessage = saveError?.let { stringResource(it) }
    // CA-10 : l'échec de sauvegarde est signalé, puis l'état est purgé pour qu'une nouvelle
    // tentative puisse le réafficher.
    LaunchedEffect(saveErrorMessage) {
        saveErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissSaveError()
        }
    }


    // LOP-227, P-7 : aucun sélecteur ne s'ouvre seul ; la catégorie se choisit dans la rangée de
    // suggestions, ou par « Plus ».
    var activeSheet by rememberSaveable { mutableStateOf<EditSheet?>(null) }
    // LOP-245 : brouillon de la feuille « Répéter », tenu par le ViewModel (I-1).
    val recurrenceDraft by vm.recurrenceDraft.collectAsStateWithLifecycle()
    val recurrencePreview by vm.recurrencePreview.collectAsStateWithLifecycle()
    val formAnchorDay by vm.formAnchorDay.collectAsStateWithLifecycle()

    LopScreenScaffold(
        title = if (vm.isEditing) stringResource(R.string.tx_edit_title) else stringResource(R.string.tx_new_title),
        onBack = { onDone(vm.editingTransactionId ?: 0L) },
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_EDIT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // CA-06 de LOP-227 : l'app est bord à bord, le clavier ne redimensionne rien. La marge
            // du clavier remonte le bouton au-dessus de lui, et la liste, qui réserve la hauteur de
            // cette barre, défile jusqu'à la dernière ligne.
            Box(Modifier.fillMaxWidth().imePadding().padding(20.dp)) {
                Button(
                    onClick = { vm.save(onDone) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TestTags.BTN_SAVE),
                    shape = MaterialTheme.shapes.medium,
                    enabled = form.isValid && !isSaving,
                    // Désactivé, le bouton reste opaque : les lignes qui défilent dessous ne se lisent
                    // pas au travers de « Enregistrer ».
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    ) {
        if (showSkeleton) return@LopScreenScaffold skeleton()
        if (!isLoaded) return@LopScreenScaffold // attente courte : rien (P-12)
        item {
            AmountHeader(
                form = form,
                currency = currency,
                onSetType = vm::setType,
                onSetAmount = vm::setAmountRaw,
            )
        }

        item {
            CategorySuggestions(
                suggestions = suggestedCategories,
                selectedId = form.categoryId,
                onSelect = vm::setCategory,
                onMore = { activeSheet = EditSheet.Category },
            )
        }

        item {
            EntryRowsCard(
                form = form,
                accounts = accounts,
                tags = tags,
                accountError = accountError,
                onSetTitle = vm::setTitle,
                onSetNote = vm::setNote,
                onOpenAccount = { activeSheet = EditSheet.Account },
                onOpenDate = { activeSheet = EditSheet.Date },
                onOpenTags = { activeSheet = EditSheet.Tags },
            )
        }

        item {
            OptionsCard(
                form = form,
                isPaidToggleVisible = vm.isPaidToggleVisible,
                linkedGoal = linkedGoal,
                linkedLoan = linkedLoan,
                goalError = goalError,
                loanError = loanError,
                onSetStatus = vm::setStatus,
                isSubscriptionToggleVisible = vm.isSubscriptionToggleVisible,
                onSetSubscription = vm::setSubscription,
                onOpenGoal = { activeSheet = EditSheet.Goal },
                onOpenDebt = { activeSheet = EditSheet.Debt },
                recurrence = if (vm.isRecurrenceSectionVisible) {
                    {
                        RecurrenceRow(
                            summary = recurrenceSummary(RecurrenceDraft.of(form), form.date, formAnchorDay, withEnd = false),
                            isNone = form.frequency == RecurrenceFrequency.NONE,
                            onOpen = vm::openRecurrence,
                        )
                    }
                } else null,
            )
        }
    }

    when (activeSheet) {
        EditSheet.Category -> {
            CategoryBottomSheet(
                title = stringResource(R.string.tx_category_sheet_title),
                categories = categories.filter { it.type == form.type },
                selectedId = form.categoryId,
                // P-7 : « Récente » montre les vraies dernières catégories utilisées.
                recents = recentCategories,
                onSelect = { id ->
                    vm.setCategory(id)
                    activeSheet = null
                },
                onCreate = {
                    activeSheet = null
                    onNavigateToCreateCategory(form.type)
                },
                onDismiss = { activeSheet = null },
            )
        }
        EditSheet.Account -> {
            // I-7 : « Sans compte » est une option de plein droit, pas un repli. Dans le formulaire
            // il n'y a que deux états — un compte, ou aucun — donc un `accountId` nul **est** le
            // choix « sans compte », et se traduit en NO_ACCOUNT_ID pour le sélecteur.
            AccountBottomSheet(
                title = stringResource(R.string.tx_account_sheet_title),
                accounts = accounts,
                selectedId = form.accountId ?: NO_ACCOUNT_ID,
                onSelect = { id ->
                    vm.setAccount(id.takeIf { it != NO_ACCOUNT_ID })
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
            )
        }
        EditSheet.Tags -> {
            TagsBottomSheet(
                tags = tags,
                selectedTagIds = form.tagIds,
                onToggleTag = vm::toggleTag,
                onCreateTag = { name, color -> vm.createTag(name, color) },
                onDismiss = { activeSheet = null },
                // CA-08 : la feuille reste ouverte après la suppression — le référentiel se met à
                // jour sous elle, et l'utilisateur peut enchaîner sur un autre tag.
                onDeleteTag = vm::deleteTag,
                tagNameError = tagNameError,
                onTagNameChanged = vm::clearTagNameError,
            )
        }
        EditSheet.Goal -> {
            PickerBottomSheet(
                title = stringResource(R.string.tx_linked_goal_label),
                items = goalChoices,
                isSelected = { it.summary.id == form.linkedGoalId },
                onSelect = { goal ->
                    vm.setGoal(goal?.summary?.id)
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
                itemLabel = { linkLabel(it) },
                allowNone = true,
                noneLabel = stringResource(R.string.tx_link_none),
                itemIcon = { IconMapper.get(it.summary.icon) },
                itemTint = { Color(it.summary.colorArgb) }
            )
        }
        EditSheet.Debt -> {
            PickerBottomSheet(
                title = stringResource(R.string.tx_linked_debt_label),
                items = loanChoices,
                isSelected = { it.summary.id == form.linkedLoanId },
                onSelect = { loan ->
                    vm.setDebt(loan?.summary?.id)
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
                itemLabel = { linkLabel(it) },
                allowNone = true,
                noneLabel = stringResource(R.string.tx_link_none),
                itemIcon = { IconMapper.get(it.summary.icon) },
                itemTint = { Color(it.summary.colorArgb) },
                itemSupportingText = { it.counterparty },
                itemGroup = { if (it.summary.kind == FinancialItemKind.DEBT) debtsTitle else receivablesTitle },
            )
        }
        EditSheet.Date -> {
            LopDatePicker(
                initialDateMillis = form.date,
                onDateSelected = { it?.let { vm.setDate(it) }; activeSheet = null },
                onDismiss = { activeSheet = null }
            )
        }
        null -> {}
    }

    recurrenceDraft?.let { draft ->
        RecurrenceSheet(
            draft = draft,
            formDate = form.date,
            preview = recurrencePreview,
            onEdit = vm::editRecurrence,
            onApply = vm::applyRecurrence,
            onDismiss = vm::dismissRecurrence,
        )
    }

    // LOP-88 : décision demandée avant d'écrire une règle qui rencontre un jour absent. Depuis
    // LOP-245 (P-7, P-9), seulement si la date a changé depuis le dernier choix connu.
    missingDayPrompt?.let { prompt ->
        MissingDaySheet(
            prompt = prompt,
            onChoose = { choice -> vm.confirmMissingDay(choice, onDone) },
            onDismiss = vm::dismissMissingDay,
        )
    }
}

/** Place [linked] en tête de [choices] s'il est archivé et de ce [kind] ; sinon [choices] tel quel. */
private fun withArchivedLink(choices: List<LinkableItem>, linked: LinkableItem?, kind: FinancialItemKind) =
    if (linked != null && linked.summary.kind == kind && linked.summary.status == ItemStatus.ARCHIVED) {
        listOf(linked) + choices
    } else choices
