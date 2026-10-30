package com.lop.budget.ui.screens.goals

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.progressView
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun GoalsScreen(
    vm: GoalsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    /** Ouvre le détail du type et de l'identifiant de la carte, pas son édition (LOP-203, CA-01). */
    onOpenItem: (FinancialItemKind, Long) -> Unit = { _, _ -> },
    /** Type d'une création qui vient de réussir : son onglet s'ouvre (CA-07, CA-05 de LOP-202). */
    createdKind: FinancialItemKind? = null,
    onCreatedKindShown: () -> Unit = {},
    /** Onglet visible. Sauvegardé : le retour d'un détail ou d'une section le retrouve (CA-01). */
    selectedKind: MutableState<FinancialItemKind> = rememberSaveable { mutableStateOf(FinancialItemKind.GOAL) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    var selected by selectedKind
    LaunchedEffect(createdKind) {
        if (createdKind != null) {
            selected = createdKind
            onCreatedKindShown()
        }
    }

    LopScreenScaffold(
        title = stringResource(R.string.nav_goals),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_GOALS)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FinancialItemKind.entries.forEach { kind ->
                    TabItem(
                        text = stringResource(sectionTitle(kind)),
                        selected = selected == kind,
                        modifier = Modifier.weight(1f).testTag(tabTag(kind))
                    ) { selected = kind }
                }
            }
        }

        // Aucun bouton d'ajout ici : le seul est le FAB de la barre, contextualisé par l'onglet (CA-04).
        item {
            Text(
                text = stringResource(sectionTitle(selected)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        when (selected) {
            FinancialItemKind.GOAL -> listContent(state.goals, R.string.tracking_empty_goals, vm::retry, { "g${it.id}" }) { g ->
                FinancialItemCard(
                    FinancialItemKind.GOAL, g.id, g.name, g.icon, g.colorArgb, g.status,
                    g.targetAmountCents, g.savedAmountCents, state.currency, ext.income,
                ) { onOpenItem(FinancialItemKind.GOAL, g.id) }
            }
            FinancialItemKind.DEBT -> listContent(state.debts, R.string.tracking_empty_debts, vm::retry, { "d${it.id}" }) { l ->
                FinancialItemCard(
                    FinancialItemKind.DEBT, l.id, l.name, l.icon, l.colorArgb, l.status,
                    l.totalAmountCents, l.repaidAmountCents, state.currency, ext.expense,
                ) { onOpenItem(FinancialItemKind.DEBT, l.id) }
            }
            FinancialItemKind.RECEIVABLE -> listContent(state.receivables, R.string.tracking_empty_receivables, vm::retry, { "r${it.id}" }) { l ->
                FinancialItemCard(
                    FinancialItemKind.RECEIVABLE, l.id, l.name, l.icon, l.colorArgb, l.status,
                    l.totalAmountCents, l.repaidAmountCents, state.currency, ext.income,
                ) { onOpenItem(FinancialItemKind.RECEIVABLE, l.id) }
            }
        }
    }
}

/**
 * CA-06, I-3 : chargement sans message d'absence ; vide réussi avec son texte ; échec avec
 * Réessayer, sans ligne ni montant de repli.
 */
private fun <T> LazyListScope.listContent(
    load: ListLoad<T>,
    @StringRes emptyText: Int,
    onRetry: () -> Unit,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    when (load) {
        ListLoad.Loading -> item {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag(TestTags.GOAL_LIST_LOADING))
            }
        }
        ListLoad.Error -> item {
            Column(
                Modifier.fillMaxWidth().padding(48.dp).testTag(TestTags.GOAL_LIST_ERROR),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.tracking_load_error), color = MaterialTheme.colorScheme.error)
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TestTags.GOAL_LIST_RETRY),
                ) { Text(stringResource(R.string.tracking_retry)) }
            }
        }
        is ListLoad.Loaded ->
            if (load.items.isEmpty()) item { EmptyState(stringResource(emptyText)) }
            else items(load.items, key = key) { card(it) }
    }
}

@StringRes
private fun sectionTitle(kind: FinancialItemKind) = when (kind) {
    FinancialItemKind.GOAL -> R.string.goals_title
    FinancialItemKind.DEBT -> R.string.debts_title
    FinancialItemKind.RECEIVABLE -> R.string.receivables_title
}

/** CA-04 : libellé accessible du FAB sur l'onglet [kind] ; c'est aussi le titre du formulaire ouvert. */
@StringRes
internal fun creationLabel(kind: FinancialItemKind) = when (kind) {
    FinancialItemKind.GOAL -> R.string.goal_new_title
    FinancialItemKind.DEBT -> R.string.debt_new_title
    FinancialItemKind.RECEIVABLE -> R.string.receivable_new_title
}

/** CA-04, P-3 : repère de type du FAB — cible, flèche sortante, flèche entrante. */
internal fun creationIcon(kind: FinancialItemKind): ImageVector = when (kind) {
    FinancialItemKind.GOAL -> Icons.Filled.TrackChanges
    FinancialItemKind.DEBT -> Icons.AutoMirrored.Filled.CallMade
    FinancialItemKind.RECEIVABLE -> Icons.AutoMirrored.Filled.CallReceived
}

private fun tabTag(kind: FinancialItemKind) = when (kind) {
    FinancialItemKind.GOAL -> TestTags.GOAL_TAB_OBJECTIVES
    FinancialItemKind.DEBT -> TestTags.GOAL_TAB_DEBTS
    FinancialItemKind.RECEIVABLE -> TestTags.GOAL_TAB_RECEIVABLES
}

@Composable
private fun TabItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            // L'onglet ouvert s'annonce comme sélectionné.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * CA-03 : une ligne = une seule ligne en base, lue d'un seul tenant (I-3). Restant et pourcentage
 * viennent du calcul unique de LOP-79 (`progressView`), jamais d'un calcul de carte : non
 * calculables, ils se lisent « — ». L'avancement garde sa valeur réelle, négative comprise.
 */
@Composable
private fun FinancialItemCard(
    kind: FinancialItemKind,
    id: Long,
    name: String,
    icon: String,
    colorArgb: Int,
    status: ItemStatus,
    targetCents: Long,
    reachedCents: Long,
    currency: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val progress = progressView(reachedCents, targetCents)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    FloatingCard(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable { onClick() }
            .testTag("${cardTag(kind)}_$id")
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(IconMapper.get(icon), Color(colorArgb), Color(colorArgb).copy(alpha = 0.18f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(statusLabel(kind, status)), style = MaterialTheme.typography.labelMedium, color = secondary)
                }
                Text(percentLabel(progress.percent), style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${stringResource(progressLabel(kind))} ${Format.money(reachedCents, currency)} / ${Format.money(targetCents, currency)}",
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
            Text(
                "${stringResource(R.string.financial_remaining)} ${progress.remainingCents?.let { Format.money(it, currency) } ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
            Spacer(Modifier.height(8.dp))
            ProgressBar((progress.percent ?: 0) / 100f, accent)
        }
    }
}

private fun cardTag(kind: FinancialItemKind) = when (kind) {
    FinancialItemKind.GOAL -> TestTags.GOAL_CARD
    FinancialItemKind.DEBT -> TestTags.DEBT_CARD
    FinancialItemKind.RECEIVABLE -> TestTags.RECEIVABLE_CARD
}

/** « — » quand le pourcentage n'est pas calculable : cible ou montant initial ≤ 0 (CA-07). */
internal fun percentLabel(percent: Int?): String = percent?.let { "$it %" } ?: "—"

@Composable
internal fun ProgressBar(progress: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        )
    }
}
