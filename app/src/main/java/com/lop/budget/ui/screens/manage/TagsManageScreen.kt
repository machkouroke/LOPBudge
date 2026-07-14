package com.lop.budget.ui.screens.manage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.pressScaleClickable

@Composable
fun TagsManageScreen(
    onBack: () -> Unit,
    vm: TagsManageViewModel = hiltViewModel(),
) {
    val tags by vm.tags.collectAsStateWithLifecycle()

    var createName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(Color(0xFFE53935)) }

    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var deletingTag by remember { mutableStateOf<TagEntity?>(null) }

    LopScreenScaffold(
        title = stringResource(R.string.tags_manage_title),
        onBack = onBack,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.tags_manage_create_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text(stringResource(R.string.tags_manage_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tagColors) { c ->
                        val isSelected = selectedColor == c
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { selectedColor = c },
                            shape = CircleShape,
                            color = c,
                            border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                        ) {
                            if (isSelected) {
                                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        vm.createTag(createName, selectedColor.toArgb())
                        createName = ""
                    },
                    enabled = createName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.add), fontWeight = FontWeight.Bold)
                }
            }
        }
    ) {
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.tags_manage_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(tags, key = { it.id }) { tag ->
            FloatingCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(14.dp),
                        color = Color(tag.colorArgb),
                        shape = CircleShape,
                    ) {}
                    Spacer(Modifier.width(12.dp))
                    Text(tag.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .pressScaleClickable { editingTag = tag }
                            .padding(2.dp)
                    ) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.padding(10.dp).size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .pressScaleClickable { deletingTag = tag }
                            .padding(2.dp)
                    ) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.padding(10.dp).size(18.dp))
                    }
                }
            }
        }

        if (tags.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.tags_manage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Edit dialog
    if (editingTag != null) {
        var name by remember(editingTag) { mutableStateOf(editingTag?.name.orEmpty()) }
        var color by remember(editingTag) { mutableStateOf(Color(editingTag?.colorArgb ?: 0)) }

        AlertDialog(
            onDismissRequest = { editingTag = null },
            title = { Text(stringResource(R.string.tags_manage_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.tags_manage_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(tagColors) { c ->
                            val isSelected = color == c
                            Surface(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { color = c },
                                shape = CircleShape,
                                color = c,
                                border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                            ) {}
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        editingTag?.let { vm.updateTag(it.copy(name = name.trim(), colorArgb = color.toArgb())) }
                        editingTag = null
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Button(onClick = { editingTag = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Delete dialog
    if (deletingTag != null) {
        val tag = deletingTag
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(stringResource(R.string.tags_manage_delete_title)) },
            text = { Text(stringResource(R.string.tags_manage_delete_msg, tag?.name ?: "")) },
            confirmButton = {
                Button(
                    onClick = {
                        tag?.let { vm.deleteTag(it.id) }
                        deletingTag = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                Button(onClick = { deletingTag = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private val tagColors = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
    Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1),
    Color(0xFF00897B), Color(0xFF00838F), Color(0xFF43A047), Color(0xFF2E7D32),
    Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835), Color(0xFFFFB300),
    Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF546E7A)
)
