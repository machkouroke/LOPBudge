package com.lop.budget.ui.screens.goals

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.lop.budget.domain.usecase.financial.FinancialItemSummary
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
    /** Ouvre le détail du type et de l'identifiant de la carte, pas son édition (LOP-203, CA-01). */
    onOpenItem: (FinancialItemKind, Long) -> Unit = { _, _ -> },
    /** Type d'une création qui vient de réussir : son onglet s'ouvre (CA-07, CA-05 de LOP-202). */
    createdKind: FinancialItemKind? = null,
    onCreatedKindShown: () -> Unit = {},
    /** Onglet visible. Sauvegardé : le retour d'un détail ou d'une section le retrouve (CA-01). */
    selectedKind: MutableState<FinancialItemKind> = rememberSaveable { mutableStateOf(FinancialItemKind.GOAL) },
    /** Filtre Archives, commun aux trois onglets et gardé comme l'onglet (LOP-206, CA-03, P-5). */
    showArchives: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    var selected by selectedKind
    var archivesShown by showArchives
    // Collectées seulement filtre Archives affiché : la liste normale n'en déclenche pas la lecture.
    val archives = if (archivesShown) vm.archives.collectAsStateWithLifecycle().value else null
    LaunchedEffect(createdKind) {
        if (createdKind != null) {
            // CA-07 de LOP-22 : une création réussie s'affiche dans la liste normale de son type.
            selected = createdKind
            archivesShown = false
            onCreatedKindShown()
        }
    }

    LopScreenScaffold(
        title = stringResource(R.string.nav_goals),
        onBack = null,
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

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !archivesShown,
                    onClick = { archivesShown = false },
                    label = { Text(stringResource(R.string.tracking_filter_normal)) },
                    modifier = Modifier.testTag(TestTags.GOAL_FILTER_NORMAL),
                )
                FilterChip(
                    selected = archivesShown,
                    onClick = { archivesShown = true },
                    label = { Text(stringResource(R.string.tracking_filter_archives)) },
                    modifier = Modifier.testTag(TestTags.GOAL_FILTER_ARCHIVES),
                )
            }
        }

        val lists = archives ?: state
        val (load, emptyText) = when (selected) {
            FinancialItemKind.GOAL -> lists.goals to
                if (archives != null) R.string.tracking_empty_archived_goals else R.string.tracking_empty_goals
            FinancialItemKind.DEBT -> lists.debts to
                if (archives != null) R.string.tracking_empty_archived_debts else R.string.tracking_empty_debts
            FinancialItemKind.RECEIVABLE -> lists.receivables to
                if (archives != null) R.string.tracking_empty_archived_receivables else R.string.tracking_empty_receivables
        }
        // Une archive ouvre le même détail que la liste normale (CA-03).
        listContent(load, emptyText, vm::retry) { item ->
            val accent = if (item.kind == FinancialItemKind.DEBT) ext.expense else ext.income
            FinancialItemCard(item, lists.currency, accent) { onOpenItem(item.kind, item.id) }
        }
    }
}

/**
 * CA-06, I-3 : chargement sans message d'absence ; vide réussi avec son texte ; échec avec
 * Réessayer, sans ligne ni montant de repli.
 */
private fun LazyListScope.listContent(
    load: ListLoad<FinancialItemSummary>,
    @StringRes emptyText: Int,
    onRetry: () -> Unit,
    card: @Composable (FinancialItemSummary) -> Unit,
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
            else items(load.items, key = { "${it.kind}_${it.id}" }) { card(it) }
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
 * CA-03 : une carte = une ligne du use case de liste, lue d'un seul tenant (I-3). Restant et
 * pourcentage y sont déjà calculés par LOP-79, jamais par la carte (P-10) : non calculables, ils se
 * lisent « — ». L'avancement garde sa valeur réelle, négative comprise.
 */
@Composable
private fun FinancialItemCard(
    item: FinancialItemSummary,
    currency: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val progress = item.progress
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    FloatingCard(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable { onClick() }
            .testTag("${cardTag(item.kind)}_${item.id}")
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(IconMapper.get(item.icon), Color(item.colorArgb), Color(item.colorArgb).copy(alpha = 0.18f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(statusLabel(item.kind, item.status)), style = MaterialTheme.typography.labelMedium, color = secondary)
                }
                Text(percentLabel(progress.percent), style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${stringResource(progressLabel(item.kind))} ${Format.money(progress.reachedCents, currency)} / ${Format.money(item.targetCents, currency)}",
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
