package com.lop.budget.ui.screens.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.ui.components.PressScale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagsBottomSheet(
    tags: List<TagEntity>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.tx_tags_sheet_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                stringResource(R.string.tx_tags_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // Liste des tags existants
            PressScale(
                modifier = Modifier.fillMaxWidth(),
                onClick = {}
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        val selected = selectedTagIds.contains(tag.id)
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleTag(tag.id) },
                            label = { Text(tag.name) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Création d'un tag
            Text(
                stringResource(R.string.tx_tags_create_title),
                style = MaterialTheme.typography.titleMedium
            )
            var newTagName by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.tx_tags_name_label)) },
                    singleLine = true
                )
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = {
                        if (newTagName.isNotBlank()) {
                            onCreateTag(newTagName, 0xFF9C27B0.toInt())
                            newTagName = ""
                        }
                    },
                    enabled = newTagName.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun BalanceImpactDialog(
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("impact_alert_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.impact_balance_title)) },
        text = { Text(stringResource(R.string.impact_balance_msg)) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(true) },
                modifier = Modifier.testTag("impact_alert_confirm")
            ) {
                Text(stringResource(R.string.impact_balance_account_now))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onConfirm(false) },
                modifier = Modifier.testTag("impact_alert_dismiss")
            ) {
                Text(stringResource(R.string.impact_balance_do_not_account))
            }
        }
    )
}
