package com.lop.budget.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header year switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Année précédente",
                    modifier = Modifier
                        .size(28.dp)
                        .clickableNoRipple { year -= 1 },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Année suivante",
                    modifier = Modifier
                        .size(28.dp)
                        .clickableNoRipple { year += 1 },
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(Month.values().toList()) { month ->
                    MonthCell(
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

            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun MonthCell(
    month: Month,
    selected: YearMonth,
    year: Int,
    locale: Locale,
    onClick: () -> Unit,
) {
    val isSelected = selected.year == year && selected.month == month

    val shape = MaterialTheme.shapes.large
    val container = if (isSelected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    val content = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Surface(
        color = container,
        shape = shape,
        modifier = Modifier
            .clip(shape)
            .clickableNoRipple(onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(
            text = month.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.uppercase(locale) },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
