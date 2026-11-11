package com.lop.budget.ui.screens.subscriptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.usecase.subscription.SubscriptionsMonth
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.OccurrenceRow
import com.lop.budget.ui.components.SummaryFigure
import com.lop.budget.ui.components.rememberShowSkeleton
import com.lop.budget.ui.components.skeleton
import com.lop.budget.ui.screens.calendar.MonthCalendarTags
import com.lop.budget.ui.screens.calendar.MonthGrid
import com.lop.budget.ui.screens.calendar.MonthHeader
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format

/**
 * Calendrier mensuel des abonnements (LOP-244, P-3) : en-tête de mois, grille, synthèse
 * `Total` / `À venir`, puis la liste sous le calendrier.
 */
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onOpenOccurrence: (Long) -> Unit,
    vm: SubscriptionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val currency by vm.currency.collectAsStateWithLifecycle()
    var showMonthPicker by rememberSaveable { mutableStateOf(false) }
    // CA-08 : chargement initial et changement de mois, au-delà de 200 ms seulement (DESIGN.md § 13.4).
    val showSkeleton = rememberShowSkeleton(loading = state.content is SubscriptionsContent.Loading)

    LopScreenScaffold(
        title = stringResource(R.string.home_subscriptions),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_SUBSCRIPTIONS),
    ) {
        item {
            MonthHeader(
                month = state.month,
                onPrevious = vm::previousMonth,
                onNext = vm::nextMonth,
                onPick = { showMonthPicker = true },
                tags = MonthCalendarTags.Subscriptions,
            )
        }
        // P-7 : le retour au mois courant n'apparaît que hors de celui-ci.
        if (state.month != vm.currentMonth) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(
                    onClick = vm::goToCurrentMonth,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(TestTags.SUBSCRIPTIONS_CURRENT_MONTH),
                ) { Text(stringResource(R.string.month_picker_today)) }
            }
        }

        when (val content = state.content) {
            SubscriptionsContent.Error -> item { ErrorBlock(vm::retry) }
            // Attente courte : rien, ni grille vide ni 0,00 € (CA-08).
            SubscriptionsContent.Loading -> if (showSkeleton) skeleton(TestTags.SUBSCRIPTIONS_LOADING)
            is SubscriptionsContent.Loaded -> {
                item {
                    FloatingCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                        MonthGrid(
                            month = state.month,
                            byDay = content.data.byDay,
                            selectedDay = state.selectedDay,
                            today = vm.today,
                            onDayClick = vm::selectDay,
                            tags = MonthCalendarTags.Subscriptions,
                            dayCountPlural = R.plurals.subscriptions_day_count,
                        )
                    }
                }
                item { MonthSummary(content.data, currency) }

                val rows = state.rows.orEmpty()
                if (rows.isEmpty()) {
                    item {
                        if (state.selectedDay != null) {
                            MessageText(stringResource(R.string.subscriptions_empty_day), TestTags.SUBSCRIPTIONS_EMPTY_DAY)
                        } else {
                            // P-9 : rien à venir ce mois, payé ou sans abonnement.
                            MessageText(stringResource(R.string.subscriptions_empty_month), TestTags.SUBSCRIPTIONS_EMPTY_MONTH)
                        }
                    }
                } else {
                    items(rows.size, key = { rows[it].transaction.id }) { index ->
                        val row = rows[index]
                        // CA-07 : glissement payer/supprimer et aperçu viennent de la ligne de l'accueil.
                        OccurrenceRow(
                            occurrence = row,
                            onClick = { onOpenOccurrence(row.transaction.id) },
                            modifier = Modifier.testTag(TestTags.SUBSCRIPTIONS_ROW),
                        )
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerBottomSheet(
            selected = state.month,
            currentMonth = vm.currentMonth,
            onSelect = vm::pickMonth,
            onDismiss = { showMonthPicker = false },
        )
    }
}

/** CA-05 : `Total` (payées et planifiées) et `À venir` (planifiées) du mois consulté. */
@Composable
private fun MonthSummary(data: SubscriptionsMonth, currency: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    FloatingCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SummaryFigure(
                R.string.subscriptions_total, Format.money(data.totalAmount, currency),
                onSurface, TestTags.SUBSCRIPTIONS_TOTAL, Modifier.weight(1f),
            )
            SummaryFigure(
                R.string.subscriptions_upcoming, Format.money(data.upcomingAmount, currency),
                if (data.upcomingAmount > 0) LopTheme.extended.expense else onSurface,
                TestTags.SUBSCRIPTIONS_UPCOMING, Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MessageText(text: String, testTag: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        textAlign = TextAlign.Center,
    )
}

/** CA-08 : un message explicite et « Réessayer » à la place du contenu. */
@Composable
private fun ErrorBlock(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(TestTags.SUBSCRIPTIONS_ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.subscriptions_load_error), color = MaterialTheme.colorScheme.error)
        TextButton(
            onClick = onRetry,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(TestTags.SUBSCRIPTIONS_RETRY),
        ) { Text(stringResource(R.string.subscriptions_retry)) }
    }
}
