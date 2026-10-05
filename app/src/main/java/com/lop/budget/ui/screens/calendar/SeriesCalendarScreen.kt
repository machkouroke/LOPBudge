package com.lop.budget.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.usecase.transaction.DaySelection
import com.lop.budget.domain.usecase.transaction.MonthOccurrences
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.OccurrenceRow
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    vm: SeriesCalendarViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.series_calendar_unavailable)
    var showMonthPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is CalendarEvent.OpenOccurrence -> onOpenOccurrence(event.id)
                CalendarEvent.ReturnToStart -> onBack()
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
        if (month == null) {
            item {
                if (content is CalendarContent.Error) ErrorBlock(vm::retry)
                else MessageText(stringResource(R.string.loading))
            }
            return@LopScreenScaffold
        }

        item {
            MonthHeader(
                month = month,
                onPrevious = vm::previousMonth,
                onNext = vm::nextMonth,
                onPick = { showMonthPicker = true },
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
                    occurrences = (content as? CalendarContent.Loaded)?.occurrences,
                    selectedDay = state.selectedDay,
                    today = vm.today,
                    onDayClick = vm::selectDay,
                )
            }
        }

        val selection = state.selection
        when {
            content is CalendarContent.Error -> item { ErrorBlock(vm::retry) }
            state.selectedDay == null -> item {
                MessageText(stringResource(R.string.series_calendar_select_day), TestTags.SERIES_CALENDAR_SELECT_HINT)
            }
            selection == null -> item { MessageText(stringResource(R.string.loading)) }
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
            onSelect = vm::pickMonth,
            onDismiss = { showMonthPicker = false },
        )
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
) {
    val label = Format.monthYear(month)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag(TestTags.SERIES_CALENDAR_PREV_MONTH)) {
            Icon(Icons.Filled.ChevronLeft, stringResource(R.string.series_calendar_prev_month))
        }
        val pickDescription = stringResource(R.string.series_calendar_pick_month, label)
        TextButton(
            onClick = onPick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = pickDescription }
                .testTag(TestTags.SERIES_CALENDAR_MONTH_PICKER),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onNext, modifier = Modifier.testTag(TestTags.SERIES_CALENDAR_NEXT_MONTH)) {
            Icon(Icons.Filled.ChevronRight, stringResource(R.string.series_calendar_next_month))
        }
    }
}

/** Grille du lundi au dimanche ; le nombre d'occurrences n'est affiché qu'une fois le mois chargé. */
@Composable
private fun MonthGrid(
    month: YearMonth,
    occurrences: MonthOccurrences?,
    selectedDay: LocalDate?,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
) {
    val days: List<LocalDate?> =
        List(month.atDay(1).dayOfWeek.value - 1) { null } + (1..month.lengthOfMonth()).map(month::atDay)

    Column {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            stringArrayResource(R.array.tx_days_short).forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (week + List(7 - week.size) { null }).forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            DayCell(
                                day = day,
                                // `null` tant que le mois n'est pas chargé : ni icône ni nombre annoncé.
                                occurrences = occurrences?.let { it.byDay[day].orEmpty() },
                                selected = day == selectedDay,
                                isToday = day == today,
                                onClick = { onDayClick(day) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * CA-02 / P-7 : le jour porte l'icône de catégorie de son occurrence, et leur nombre s'il y en a
 * plusieurs. CA-08 : il annonce sa date complète, son nombre d'occurrences et ses états. Sélection
 * et aujourd'hui se distinguent aussi par la forme (fond plein, contour), pas seulement la couleur.
 */
@Composable
private fun DayCell(
    day: LocalDate,
    occurrences: List<TransactionWithRelations>?,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val count = occurrences?.size
    val description = listOfNotNull(
        Format.fullDate(day),
        count?.let { pluralStringResource(R.plurals.series_calendar_day_count, it, it) },
        stringResource(R.string.series_calendar_day_selected).takeIf { selected },
        stringResource(R.string.series_calendar_day_today).takeIf { isToday },
    ).joinToString(", ")
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(2.dp)
            .clip(shape)
            .background(if (selected) colors.primary else Color.Transparent)
            // LOP-190 : sur le fond plein d'un jour sélectionné, le contour d'aujourd'hui prend la couleur du
            // texte sélectionné ; de la couleur du fond, il disparaissait.
            .then(if (isToday) Modifier.border(BorderStroke(2.dp, if (selected) colors.onPrimary else colors.primary), shape) else Modifier)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(TestTags.SERIES_CALENDAR_DAY_PREFIX + day)
            // P-8 : les icônes ne touchent plus le bord de la case.
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val textColor = if (selected) colors.onPrimary else colors.onSurface
        Text(
            day.dayOfMonth.toString(),
            color = textColor,
            fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (!occurrences.isNullOrEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics {}) {
                StackedCategoryIcons(occurrences, ringColor = if (selected) colors.primary else colors.surface)
                if (occurrences.size > 1) {
                    Text(
                        "${occurrences.size}",
                        color = if (selected) colors.onPrimary else colors.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * P-8 : icônes des catégories distinctes du jour, dans l'ordre d'affichage, superposées, trois au
 * plus. Le nombre affiché à côté reste celui des occurrences (P-7).
 *
 * ponytail: largeur du nombre estimée à 10 dp pour décider combien d'icônes tiennent ; une case
 * étroite (petit écran) n'en montre que deux. Mesurer le texte si la police agrandie l'exige.
 */
@Composable
private fun StackedCategoryIcons(occurrences: List<TransactionWithRelations>, ringColor: Color) {
    val categories = occurrences.map { it.category }.distinctBy { it?.id }
    BoxWithConstraints {
        val countWidth = if (occurrences.size > 1) 10.dp else 0.dp
        val fitting = 1 + ((maxWidth - countWidth - STACK_ICON_SIZE) / (STACK_ICON_SIZE - STACK_OVERLAP)).toInt()
        Row(horizontalArrangement = Arrangement.spacedBy(-STACK_OVERLAP)) {
            categories.take(fitting.coerceIn(1, MAX_STACKED_ICONS)).forEach { category ->
                CircleIcon(
                    IconMapper.get(category?.icon ?: "category"),
                    Color.White,
                    category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
                    size = STACK_ICON_SIZE,
                    modifier = Modifier.border(1.dp, ringColor, CircleShape),
                )
            }
        }
    }
}

private const val MAX_STACKED_ICONS = 3
private val STACK_ICON_SIZE = 16.dp
private val STACK_OVERLAP = 7.dp

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
