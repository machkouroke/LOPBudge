package com.lop.budget.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.common.TransactionActionViewModel
import com.lop.budget.ui.components.AccountBottomSheet
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopDatePicker
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.SwipeDownDismissWrapper
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transactionId: Long,
    onBack: () -> Unit,
    vm: TransactionDetailViewModel = hiltViewModel(),
    actionVm: TransactionActionViewModel = hiltViewModel(LocalContext.current as androidx.activity.ComponentActivity),
    snackbarHostState: SnackbarHostState,
) {
    LaunchedEffect(transactionId) { vm.load(transactionId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val pendingDeletes by actionVm.pendingDeletes.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current


    LaunchedEffect(state.transaction, state.isLoaded, pendingDeletes) {
        val currentId = state.transaction?.transaction?.id
        val shouldClose = (state.isLoaded && state.transaction == null) ||
                (currentId != null && currentId in pendingDeletes)
        if (shouldClose) {
            lifecycleOwner.lifecycle.currentStateFlow
                .first { it.isAtLeast(Lifecycle.State.RESUMED) }
            onBack()
        }
    }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }

    val twr = state.transaction
    val tx = twr?.transaction
    val scaffoldTitle = tx?.title ?: stringResource(R.string.tx_default_title)
    // CA-15 : l'état de sauvegarde vit dans l'orchestrateur qui écrit réellement, pas dans l'état
    // du détail — sinon le verrou ne protégerait que cet écran.
    val isBusy by actionVm.isSaving.collectAsStateWithLifecycle()

    SwipeDownDismissWrapper(onDismiss = onBack) {
        LopScreenScaffold(
            title = scaffoldTitle,
            onBack = onBack,
            navigationIcon = Icons.Filled.Close,
            modifier = Modifier.testTag(TestTags.SCREEN_DETAIL),
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
        ) {
            if (tx == null) {
                item {
                    Text(
                        stringResource(R.string.loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val isIncome = tx.type == TransactionType.INCOME
                val accent = if (isIncome) ext.income else ext.expense

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                                .clickableNoRipple {
                                    if (!isBusy && twr != null) {
                                        actionVm.requestEdit(twr)
                                    }
                                }
                                .testTag(TestTags.TRANSACTION_DETAIL_EDIT),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                stringResource(R.string.edit),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                                .clickableNoRipple {
                                    if (!isBusy && twr != null) {
                                        actionVm.requestDelete(twr)
                                    }
                                }
                                .testTag(TestTags.TRANSACTION_DETAIL_DELETE),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val catColor = twr.category?.colorArgb?.let { Color(it) }
                            ?: com.lop.budget.ui.theme.CategoryOrange
                        CircleIcon(
                            IconMapper.get(twr.category?.icon ?: "category"),
                            Color.White,
                            catColor,
                            size = 80.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            tx.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag(TestTags.TRANSACTION_DETAIL_TITLE)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            Format.money(tx.amount),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag(TestTags.TRANSACTION_DETAIL_AMOUNT)
                        )
                    }
                }

                item {
                    FloatingCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_category),
                                value = twr.category?.name ?: stringResource(R.string.other),
                                testTag = TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY,
                                leading = {
                                    val c = twr.category?.colorArgb?.let { Color(it) }
                                        ?: com.lop.budget.ui.theme.CategoryOrange
                                    CircleIcon(
                                        IconMapper.get(twr.category?.icon ?: "category"),
                                        Color.White,
                                        c,
                                        size = 34.dp
                                    )
                                },
                                onClick = { if (!isBusy) showCategorySheet = true },
                                trailing = {
                                    Icon(
                                        Icons.Filled.Edit,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )

                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_date),
                                value = Format.fullDate(tx.date),
                                testTag = TestTags.TRANSACTION_DETAIL_FIELD_DATE,
                                leading = {
                                    Icon(
                                        Icons.Filled.CalendarMonth,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                onClick = { if (!isBusy) showDatePicker = true },
                                trailing = {
                                    Icon(
                                        Icons.Filled.Edit,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )

                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_account),
                                value = twr.account?.name ?: stringResource(R.string.tx_no_account),
                                testTag = TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT,
                                leading = {
                                    val account = twr.account
                                    if (account != null) {
                                        val color = Color(account.colorArgb)
                                        CircleIcon(
                                            IconMapper.get(account.icon),
                                            Color.White,
                                            color,
                                            size = 34.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.AccountBalance,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                },
                                onClick = { if (!isBusy) showAccountSheet = true },
                                trailing = {
                                    Icon(
                                        Icons.Filled.Edit,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )

                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_type),
                                value = if (isIncome) stringResource(R.string.tx_type_income) else stringResource(
                                    R.string.tx_type_expense
                                ),
                                testTag = TestTags.TRANSACTION_DETAIL_FIELD_TYPE,
                                leading = {
                                    Icon(
                                        Icons.Filled.SyncAlt,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                onClick = null,
                                trailing = null
                            )
                        }
                    }
                }

                if (twr.tags.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(twr.tags, key = { it.id }) {
                                PillTag(
                                    "#${it.name}",
                                    Color(it.colorArgb)
                                )
                            }
                        }
                    }
                }

                if (tx.seriesId != null && state.upcomingDates.isNotEmpty()) {
                    item {
                        FloatingCard(Modifier.fillMaxWidth()) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.CalendarMonth,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.tx_detail_upcoming_occurrences),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                state.upcomingDates.forEach { d ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            Format.fullDate(d),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            (if (isIncome) "+" else "−") + Format.money(tx.amount),
                                            color = accent,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // CA-12 : l'inventaire des actions applicables est décidé par le ViewModel.
                val isPaid = DetailAction.MARK_AS_UNPAID in state.availableActions
                if (DetailAction.MARK_AS_PAID in state.availableActions || isPaid) {
                    item {
                        val buttonColor =
                            if (isPaid) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            else ext.incomeContainer.copy(alpha = 0.4f)
                        val tintColor =
                            if (isPaid) MaterialTheme.colorScheme.onSurfaceVariant else ext.income

                        FloatingCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableNoRipple {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    actionVm.togglePaid(twr)
                                }
                                .testTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID),
                            color = buttonColor,
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isPaid) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Check,
                                    null,
                                    tint = tintColor
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(if (isPaid) R.string.tx_detail_mark_as_unpaid else R.string.tx_detail_mark_as_paid),
                                    color = tintColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    if (showDatePicker && tx != null) {
        LopDatePicker(
            initialDateMillis = tx.date,
            onDateSelected = { newDate ->
                newDate?.let { actionVm.quickEditDate(tx = twr, date = it) }
            },
            onDismiss = { showDatePicker = false }
        )
    }

    if (showCategorySheet && tx != null) {
        CategoryBottomSheet(
            title = stringResource(R.string.tx_category_sheet_title),
            categories = state.availableCategories,
            selectedId = tx.categoryId,
            onSelect = { categoryId ->
                actionVm.quickEditCategory(tx = twr, categoryId = categoryId)
                showCategorySheet = false
            },
            onDismiss = { showCategorySheet = false }
        )
    }

    if (showAccountSheet && twr != null) {
        AccountBottomSheet(
            title = stringResource(R.string.tx_detail_account),
            accounts = state.availableAccounts,
            // L'identifiant brut, et non `twr.account?.id` : la jointure rend `null` aussi bien
            // pour « sans compte » que pour un compte disparu, alors que la colonne, elle, porte
            // NO_ACCOUNT_ID sans ambiguïté.
            selectedId = twr.transaction.accountId,
            onSelect = { accountId ->
                actionVm.quickEditAccount(tx = twr, accountId = accountId)
                showAccountSheet = false
            },
            onDismiss = { showAccountSheet = false },
        )
    }
}

/**
 * Ligne du bloc d'informations du détail.
 *
 * CA-11 : les trois champs modifiables partagent cette même mise en forme et cette même
 * affordance. [testTag] identifie la ligne ; son affordance de modification porte le même
 * identifiant suffixé de [TestTags.EDIT_AFFORDANCE_SUFFIX], et n'existe que sur une ligne
 * réellement modifiable.
 */
@Composable
private fun DetailFieldRow(
    label: String,
    value: String,
    testTag: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = shape,
        color = background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickableNoRipple(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.testTag(testTag + TestTags.EDIT_AFFORDANCE_SUFFIX)) { trailing() }
            }
        }
    }
}
