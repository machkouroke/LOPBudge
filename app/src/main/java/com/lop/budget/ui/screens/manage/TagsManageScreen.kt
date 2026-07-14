package com.lop.budget.ui.screens.manage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.lop.budget.ui.components.ConfirmDeleteSheet
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.HapticIntent
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.pressScaleClickable

@Composable
fun TagsManageScreen(
    onBack: () -> Unit,
    vm: TagsManageViewModel = hiltViewModel(),
) {
    val tags by vm.tags.collectAsStateWithLifecycle()
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var tagToDelete by remember { mutableStateOf<TagEntity?>(null) }

    LopScreenScaffold(
        title = "Gestion des tags",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        bottomBar = {
            Button(
                onClick = { showCreateSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Créer un tag", fontWeight = FontWeight.Bold)
            }
        }
    ) {
        if (tags.isEmpty()) {
            item {
                Text(
                    "Aucun tag créé pour le moment.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(tags, key = { it.id }) { tag ->
            TagItem(
                tag = tag,
                onEdit = { editingTag = tag },
                onDelete = { tagToDelete = tag }
            )
        }
        
        item { Spacer(Modifier.height(100.dp)) }
    }

    if (editingTag != null) {
        TagEditSheet(
            tag = editingTag!!,
            onDismiss = { editingTag = null },
            onSave = { name, color ->
                vm.updateTag(editingTag!!, name, color)
                editingTag = null
            }
        )
    }

    if (showCreateSheet) {
        TagEditSheet(
            tag = null,
            onDismiss = { showCreateSheet = false },
            onSave = { name, color ->
                vm.createTag(name, color)
                showCreateSheet = false
            }
        )
    }

    if (tagToDelete != null) {
        ConfirmDeleteSheet(
            title = "Supprimer le tag ?",
            message = "Le tag \"${tagToDelete?.name}\" sera définitivement supprimé et retiré de toutes les transactions.",
            confirmLabel = stringResource(R.string.delete),
            onDismiss = { tagToDelete = null },
            onConfirm = {
                tagToDelete?.id?.let { vm.deleteTag(it) }
                tagToDelete = null
            }
        )
    }
}

@Composable
private fun TagItem(
    tag: TagEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = Color(tag.colorArgb)
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                tag.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Modifier", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Supprimer", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagEditSheet(
    tag: TagEntity?,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(tag?.name ?: "") }
    val colors = listOf(
        Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
        Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1),
        Color(0xFF00897B), Color(0xFF00838F), Color(0xFF43A047), Color(0xFF2E7D32),
        Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835), Color(0xFFFFB300),
        Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF546E7A)
    )
    var selectedColor by remember { mutableStateOf(tag?.let { Color(it.colorArgb) } ?: colors[0]) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Text(
                if (tag == null) "Créer un tag" else "Modifier le tag",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))
            
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom du tag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(Modifier.height(20.dp))
            
            Text("Couleur", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(colors) { color ->
                    val isSelected = selectedColor == color
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { selectedColor = color }
                            .border(
                                if (isSelected) 3.dp else 0.dp,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(32.dp))
            
            Button(
                onClick = { onSave(name, selectedColor.toArgb()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Enregistrer", fontWeight = FontWeight.Bold)
            }
            
            Spacer(Modifier.height(32.dp))
        }
    }
}
