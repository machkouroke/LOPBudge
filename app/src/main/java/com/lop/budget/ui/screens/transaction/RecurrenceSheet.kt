package com.lop.budget.ui.screens.transaction

import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.RulePreview
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.LopBottomSheet
import com.lop.budget.ui.components.LopDatePicker
import com.lop.budget.util.Format
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Feuille « Répéter » (LOP-245) : toute la règle au même endroit, son résumé et ses prochaines dates,
 * puis Annuler ou Valider. Elle ne modifie que [draft] ; le formulaire ne change qu'à « Valider »
 * (I-1, P-3).
 */
@Composable
fun RecurrenceSheet(
    draft: RecurrenceDraft,
    formDate: Long,
    /** Aperçu de la règle du brouillon ; `null` tant qu'il n'est pas lu. */
    preview: RulePreview?,
    onEdit: ((RecurrenceDraft) -> RecurrenceDraft) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val date = remember(formDate) { localDate(formDate) }
    var pickEndDate by remember { mutableStateOf(false) }

    LopBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(TestTags.TX_EDIT_RECURRENCE_SHEET),
        maxHeightFraction = 0.9f,
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(stringResource(R.string.tx_repeat_label), style = MaterialTheme.typography.titleLarge)
                Text(
                    recurrenceSummary(draft, formDate, preview?.anchorDay, withEnd = true),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.TX_EDIT_RECURRENCE_SUMMARY),
                )
            }

            Segmented(
                options = RecurrenceFrequency.entries,
                selected = draft.frequency,
                label = { stringResource(frequencyLabel(it)) },
                tag = { TestTags.TX_EDIT_RECURRENCE_FREQUENCY_PREFIX + it.name.lowercase() },
                onSelect = { f -> onEdit { it.withFrequency(f, date.dayOfWeek.value) } },
            )

            if (draft.frequency != RecurrenceFrequency.NONE) {
                Section(R.string.tx_recurrence_interval) {
                    Stepper(
                        value = pluralStringResource(intervalUnit(draft.frequency), draft.interval, draft.interval),
                        canDecrease = draft.interval > 1,
                        canIncrease = draft.interval < RecurrenceDraft.MAX_INTERVAL,
                        onDecrease = { onEdit { it.withInterval(it.interval - 1) } },
                        onIncrease = { onEdit { it.withInterval(it.interval + 1) } },
                        tagPrefix = TestTags.TX_EDIT_RECURRENCE_INTERVAL_PREFIX,
                    )
                }

                when (draft.frequency) {
                    RecurrenceFrequency.WEEKLY -> Section(R.string.tx_recurrence_days) {
                        DayChips(draft.daysOfWeek) { day -> onEdit { it.toggleDay(day) } }
                    }
                    RecurrenceFrequency.MONTHLY, RecurrenceFrequency.YEARLY ->
                        AnchorAndMissingDay(draft, date, preview, onEdit)
                    else -> {}
                }

                Section(R.string.tx_recurrence_end) {
                    Segmented(
                        options = RecurrenceEnd.entries,
                        selected = draft.end,
                        label = { stringResource(endLabel(it)) },
                        tag = { TestTags.TX_EDIT_RECURRENCE_END_PREFIX + it.name.lowercase() },
                        onSelect = { end -> onEdit { it.withEnd(end, formDate) } },
                    )
                    when (draft.end) {
                        RecurrenceEnd.DATE -> EndDateRow(draft.endDate ?: formDate) { pickEndDate = true }
                        RecurrenceEnd.COUNT -> {
                            val count = draft.maxOccurrences ?: RecurrenceDraft.DEFAULT_COUNT
                            Stepper(
                                value = pluralStringResource(R.plurals.tx_recurrence_count, count, count),
                                canDecrease = count > 1,
                                canIncrease = count < RecurrenceDraft.MAX_COUNT,
                                onDecrease = { onEdit { it.withCount(count - 1) } },
                                onIncrease = { onEdit { it.withCount(count + 1) } },
                                tagPrefix = TestTags.TX_EDIT_RECURRENCE_COUNT_PREFIX,
                            )
                        }
                        RecurrenceEnd.NEVER -> {}
                    }
                }

                Section(R.string.tx_recurrence_preview) {
                    // Rien d'affiché tant que l'aperçu n'est pas lu : jamais de dates d'une autre règle.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag(TestTags.TX_EDIT_RECURRENCE_PREVIEW),
                    ) {
                        preview?.dates.orEmpty().forEach { millis ->
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text(
                                    Format.dayMonth(millis),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag(TestTags.TX_EDIT_RECURRENCE_CANCEL),
            ) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onApply,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag(TestTags.TX_EDIT_RECURRENCE_APPLY),
            ) { Text(stringResource(R.string.tx_recurrence_apply)) }
        }
    }

    if (pickEndDate) {
        LopDatePicker(
            initialDateMillis = draft.endDate ?: formDate,
            minDateMillis = formDate,
            onDateSelected = { picked -> picked?.let { d -> onEdit { it.withEndDate(d) } } },
            onDismiss = { pickEndDate = false },
        )
    }
}

/**
 * Résumé d'une règle (CA-01, CA-05) : « Tous les 2 mois, le 31 », puis « · 12 échéances » quand
 * [withEnd]. [anchorDay] : jour d'ancrage reporté, sinon celui de [date].
 */
@Composable
fun recurrenceSummary(draft: RecurrenceDraft, date: Long, anchorDay: Int?, withEnd: Boolean): String {
    val local = localDate(date)
    val n = draft.interval
    val rule = when (draft.frequency) {
        RecurrenceFrequency.NONE -> return stringResource(R.string.tx_repeat_none)
        RecurrenceFrequency.DAILY -> pluralStringResource(R.plurals.tx_repeat_summary_daily, n, n)
        RecurrenceFrequency.WEEKLY -> pluralStringResource(R.plurals.tx_repeat_summary_weekly, n, n) +
            " · " + draft.daysOfWeek.sorted().joinToString(" ") {
                DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.FRANCE)
            }
        RecurrenceFrequency.MONTHLY -> pluralStringResource(R.plurals.tx_repeat_summary_monthly, n, n) +
            stringResource(R.string.tx_repeat_summary_on_day, dayLabel(anchorDay ?: local.dayOfMonth))
        RecurrenceFrequency.YEARLY -> pluralStringResource(R.plurals.tx_repeat_summary_yearly, n, n) +
            stringResource(R.string.tx_repeat_summary_on_date, Format.dayMonth(local))
    }
    if (!withEnd) return rule
    val end = when (draft.end) {
        RecurrenceEnd.NEVER -> stringResource(R.string.tx_repeat_summary_forever)
        RecurrenceEnd.DATE -> stringResource(R.string.tx_repeat_summary_until, Format.dayMonthYear(localDate(draft.endDate!!)))
        RecurrenceEnd.COUNT -> pluralStringResource(R.plurals.tx_recurrence_count, draft.maxOccurrences!!, draft.maxOccurrences)
    }
    return "$rule · $end"
}

/** CA-03 : l'ancrage, puis la ligne des mois sans ce jour quand la règle en rencontre (P-2, P-5). */
@Composable
private fun AnchorAndMissingDay(
    draft: RecurrenceDraft,
    date: LocalDate,
    preview: RulePreview?,
    onEdit: ((RecurrenceDraft) -> RecurrenceDraft) -> Unit,
) {
    val yearly = draft.frequency == RecurrenceFrequency.YEARLY
    val anchor = preview?.anchorDay ?: date.dayOfMonth
    Text(
        if (yearly) stringResource(R.string.tx_recurrence_anchor_year, date.format(DAY_MONTH_LONG))
        else stringResource(R.string.tx_recurrence_anchor_month, dayLabel(anchor)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val missing = preview?.missingDay ?: return
    Section(
        title = if (yearly) stringResource(R.string.tx_recurrence_missing_year, date.format(DAY_MONTH_LONG))
        else stringResource(R.string.tx_recurrence_missing_month, missing.anchorDay),
    ) {
        Segmented(
            options = listOf(MissingDayBehavior.LAST_VALID_DAY, MissingDayBehavior.SKIP_PERIOD),
            selected = draft.missingDayBehavior,
            label = {
                stringResource(
                    if (it == MissingDayBehavior.LAST_VALID_DAY) R.string.tx_recurrence_missing_last
                    else R.string.tx_recurrence_missing_skip
                )
            },
            tag = {
                if (it == MissingDayBehavior.LAST_VALID_DAY) TestTags.TX_EDIT_RECURRENCE_MISSING_LAST
                else TestTags.TX_EDIT_RECURRENCE_MISSING_SKIP
            },
            // LOP-88, CA-12 : sans aucune période qui ait ce jour, « Sauter » arrêterait la série.
            enabled = { it == MissingDayBehavior.LAST_VALID_DAY || missing.canSkip },
            onSelect = { b -> onEdit { it.withMissingDay(b) } },
        )
        if (!missing.canSkip) {
            Text(
                stringResource(R.string.tx_missing_day_skip_unavailable, missing.anchorDay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Libellé de section, puis son contenu. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) = Section(stringResource(title), content)

/**
 * Choix exclusif en segment, comme la bascule Dépense / Revenu : le choisi est rempli (CA-08, la
 * sélection ne se lit pas qu'à la couleur). Chaque choix garde sa largeur naturelle, l'espace restant
 * est réparti ; quand la police est grande, ils passent à la ligne au lieu d'être coupés.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    val shape = RoundedCornerShape(50)
    val colors = MaterialTheme.colorScheme

    @Composable
    fun Item(option: T, modifier: Modifier) {
        val isSelected = option == selected
        val isEnabled = enabled(option)
        Box(
            modifier = modifier
                .clip(shape)
                .background(if (isSelected) colors.primary else Color.Transparent)
                .selectable(isSelected, enabled = isEnabled, role = Role.Tab) { onSelect(option) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag(tag(option)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = when {
                    isSelected -> colors.onPrimary
                    isEnabled -> colors.onSurface
                    else -> colors.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, RoundedCornerShape(24.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { Item(it, Modifier) }
    }
}

/** Compteur sans clavier (CA-02, CA-04) ; la valeur porte son unité, « 2 mois ». */
@Composable
private fun Stepper(
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    tagPrefix: String,
) {
    val decrease = stringResource(R.string.tx_recurrence_decrease)
    val increase = stringResource(R.string.tx_recurrence_increase)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton(Icons.Default.Remove, "$decrease, $value", canDecrease, onDecrease, tagPrefix + "minus")
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 96.dp)
                .testTag(tagPrefix + "value"),
        )
        StepButton(Icons.Default.Add, "$increase, $value", canIncrease, onIncrease, tagPrefix + "plus")
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .testTag(tag),
    ) { Icon(icon, contentDescription = description) }
}

/** CA-02 : sept pastilles L M M J V S D ; chaque case fait toute la largeur qui lui revient. */
@Composable
private fun DayChips(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        (1..7).forEach { day ->
            val isSelected = day in selected
            val name = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.FRANCE)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(isSelected) { onToggle(day) }
                    .semantics { contentDescription = name }
                    .testTag(TestTags.TX_EDIT_RECURRENCE_DAY_PREFIX + day),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(if (isSelected) colors.primary else colors.surfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.first().uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) colors.onPrimary else colors.onSurface,
                    )
                }
            }
        }
    }
}

/** CA-04 : la date de fin ; la toucher ouvre le sélecteur de l'app. */
@Composable
private fun EndDateRow(endDate: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(TestTags.TX_EDIT_RECURRENCE_END_DATE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.tx_recurrence_end_until), modifier = Modifier.weight(1f))
        Text(Format.dayMonthYear(localDate(endDate)), style = MaterialTheme.typography.titleSmall)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun frequencyLabel(f: RecurrenceFrequency) = when (f) {
    RecurrenceFrequency.NONE -> R.string.tx_recurrence_freq_none
    RecurrenceFrequency.DAILY -> R.string.tx_recurrence_freq_daily
    RecurrenceFrequency.WEEKLY -> R.string.tx_recurrence_freq_weekly
    RecurrenceFrequency.MONTHLY -> R.string.tx_recurrence_freq_monthly
    RecurrenceFrequency.YEARLY -> R.string.tx_recurrence_freq_yearly
}

private fun endLabel(e: RecurrenceEnd) = when (e) {
    RecurrenceEnd.NEVER -> R.string.tx_recurrence_end_never
    RecurrenceEnd.DATE -> R.string.tx_recurrence_end_date
    RecurrenceEnd.COUNT -> R.string.tx_recurrence_end_count
}

@PluralsRes
private fun intervalUnit(f: RecurrenceFrequency) = when (f) {
    RecurrenceFrequency.WEEKLY -> R.plurals.tx_recurrence_unit_week
    RecurrenceFrequency.MONTHLY -> R.plurals.tx_recurrence_unit_month
    RecurrenceFrequency.YEARLY -> R.plurals.tx_recurrence_unit_year
    else -> R.plurals.tx_recurrence_unit_day
}

/** « 1er », puis « 2 », « 31 » : le premier du mois s'écrit en ordinal. */
private fun dayLabel(day: Int): String = if (day == 1) "1er" else day.toString()

private fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** « 11 novembre » : ancrage annuel, en français comme le reste de l'écran. */
private val DAY_MONTH_LONG = DateTimeFormatter.ofPattern("d MMMM", Locale.FRANCE)
