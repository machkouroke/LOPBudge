package com.lop.budget.ui.screens.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import com.lop.budget.domain.usecase.transaction.DaySelection
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.rememberShowSkeleton
import com.lop.budget.ui.components.skeleton
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.OccurrenceRow
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * Calendrier mensuel d'une seule série (LOP-7, CA-02 à CA-08).
 *
 * Aucun glissement vertical n'y est ajouté : il concurrencerait la fermeture du détail.
 */
@Composable
fun SeriesCalendarScreen(
    onBack: () -> Unit,
    onOpenOccurrence: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    /**
     * L'occurrence de départ est touchée (CA-04 de LOP-7). Ouvert depuis son détail, c'est un
     * retour ; ouvert depuis Analyses (LOP-241, P-7), aucun détail n'est dessous : l'hôte l'ouvre.
     */
    onReturnToStart: () -> Unit = onBack,
    vm: SeriesCalendarViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.series_calendar_unavailable)
    var showMonthPicker by rememberSaveable { mutableStateOf(false) }
    val showSkeleton = rememberShowSkeleton(
        loading = state.month == null && state.content !is CalendarContent.Error && state.content !is CalendarContent.Unavailable
    )
    val showDaySkeleton = rememberShowSkeleton(loading = state.selectedDay != null && state.selection == null)

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is CalendarEvent.OpenOccurrence -> onOpenOccurrence(event.id)
                CalendarEvent.ReturnToStart -> onReturnToStart()
                // CA-05 : rester sur l'écran ; les données, observées, sont déjà à jour.
                CalendarEvent.OccurrenceUnavailable -> scope.launch { snackbarHostState.showSnackbar(unavailable) }
            }
        }
    }

    LopScreenScaffold(
        title = stringResource(R.string.series_calendar_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_SERIES_CALENDAR),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        val month = state.month
        val content = state.content
        if (content is CalendarContent.Unavailable) {
            item { MessageText(unavailable) }
            return@LopScreenScaffold
        }
        if (showSkeleton) {
            skeleton()
            return@LopScreenScaffold
        }
        if (month == null) {
            if (content is CalendarContent.Error) item { ErrorBlock(vm::retry) }
            return@LopScreenScaffold
        }

        item {
            MonthHeader(
                month = month,
                onPrevious = vm::previousMonth,
                onNext = vm::nextMonth,
                onPick = { showMonthPicker = true },
                tags = MonthCalendarTags.Series,
            )
        }
        item {
            OutlinedButton(
                onClick = vm::goToNextDue,
                enabled = state.canGoToNextDue,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(TestTags.SERIES_CALENDAR_NEXT_DUE),
            ) { Text(stringResource(R.string.series_calendar_next_due)) }
        }
        item {
            FloatingCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                MonthGrid(
                    month = month,
                    byDay = (content as? CalendarContent.Loaded)?.occurrences?.byDay,
                    selectedDay = state.selectedDay,
                    today = vm.today,
                    onDayClick = vm::selectDay,
                    tags = MonthCalendarTags.Series,
                    dayCountPlural = R.plurals.series_calendar_day_count,
                )
            }
        }

        val selection = state.selection
        when {
            content is CalendarContent.Error -> item { ErrorBlock(vm::retry) }
            state.selectedDay == null -> item {
                MessageText(stringResource(R.string.series_calendar_select_day), TestTags.SERIES_CALENDAR_SELECT_HINT)
            }
            selection == null -> if (showDaySkeleton) skeleton(cards = 1)
            selection is DaySelection.Empty -> item {
                MessageText(stringResource(R.string.series_calendar_empty_day), TestTags.SERIES_CALENDAR_EMPTY_DAY)
            }
            else -> {
                val rows = when (selection) {
                    is DaySelection.Single -> listOf(selection.occurrence)
                    is DaySelection.Multiple -> selection.occurrences
                    DaySelection.Empty -> emptyList()
                }
                items(rows.size, key = { rows[it].transaction.id }) { index ->
                    val occurrence = rows[index]
                    OccurrenceRow(
                        occurrence = occurrence,
                        onClick = { vm.openOccurrence(occurrence) },
                        modifier = Modifier.testTag(TestTags.SERIES_CALENDAR_ROW),
                    )
                }
            }
        }
    }

    val month = state.month
    if (showMonthPicker && month != null) {
        MonthPickerBottomSheet(
            selected = month,
            currentMonth = YearMonth.from(vm.today),
            onSelect = vm::pickMonth,
            onDismiss = { showMonthPicker = false },
        )
    }
}


@Composable
private fun MessageText(text: String, testTag: String? = null) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ErrorBlock(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(TestTags.SERIES_CALENDAR_ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.series_calendar_load_error), color = MaterialTheme.colorScheme.error)
        TextButton(
            onClick = onRetry,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(TestTags.SERIES_CALENDAR_RETRY),
        ) { Text(stringResource(R.string.series_calendar_retry)) }
    }
}
