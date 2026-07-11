package com.lop.budget.ui.components

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Immutable
data class MonthPickerConfig(
    val locale: Locale = Locale.FRANCE,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthPickerBottomSheet(
    selected: YearMonth,
    onSelect: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    config: MonthPickerConfig = MonthPickerConfig(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var year by remember(selected) { mutableStateOf(selected.year) }

    // IMPORTANT: on ne peut pas laisser le bottom sheet transparent, sinon sur un fond sombre
    // (et avec blur) le contenu derrière "mange" la lisibilité.
    val sheetContainer = MaterialTheme.colorScheme.surface
    val scrim = Color.Black.copy(alpha = 0.55f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = sheetContainer,
        scrimColor = scrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.month_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Header glass : année + nav + today
            YearHeaderPill(
                year = year,
                selected = selected,
                locale = config.locale,
                onPrev = { year -= 1 },
                onNext = { year += 1 },
                onToday = {
                    val now = YearMonth.now()
                    year = now.year
                    onSelect(now)
                    onDismiss()
                },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(Month.values().toList()) { month ->
                    MonthPill(
                        month = month,
                        selected = selected,
                        year = year,
                        locale = config.locale,
                        onClick = {
                            val newMonth = YearMonth.of(year, month)
                            onSelect(newMonth)
                            onDismiss()
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun YearHeaderPill(
    year: Int,
    selected: YearMonth,
    locale: Locale,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val shape = RoundedCornerShape(32.dp)

    val borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val surfaceTop = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
    val surfaceBottom = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Surface(
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(surfaceTop, surfaceBottom),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = Icons.Filled.ChevronLeft,
                contentDescription = stringResource(R.string.month_picker_prev_year),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(32.dp)
                    .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.92f, onClick = onPrev),
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val isCurrentYear = selected.year == year
                val subtitle = if (isCurrentYear) {
                    selected.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase(locale) }
                } else {
                    ""
                }

                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    modifier = Modifier
                        .size(34.dp)
                        .pressScaleClickable(intent = HapticIntent.Selection, pressedScale = 0.94f, onClick = onToday),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Today,
                            contentDescription = stringResource(R.string.month_picker_today),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.month_picker_next_year),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(32.dp)
                        .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.92f, onClick = onNext),
                )
            }
        }
    }
}

@Composable
private fun MonthPill(
    month: Month,
    selected: YearMonth,
    year: Int,
    locale: Locale,
    onClick: () -> Unit,
) {
    val isSelected = selected.year == year && selected.month == month

    val shape = RoundedCornerShape(28.dp)

    val container = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)
    }

    val border = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    }

    val content = if (isSelected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    }

    Surface(
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, border),
        tonalElevation = 0.dp,
        shadowElevation = if (isSelected) 8.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        container,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isSelected) 0.20f else 0.16f),
                    ),
                ),
            )
            .pressScaleClickable(intent = HapticIntent.Selection, pressedScale = 0.97f, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = month.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.uppercase(locale) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }
}
