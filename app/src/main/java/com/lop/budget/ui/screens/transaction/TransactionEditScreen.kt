package com.lop.budget.ui.screens.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.HapticIntent
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.components.pressScaleClickable
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditScreen(
    onBack: () -> Unit,
    onNavigateToCreateCategory: () -> Unit,
    vm: TransactionEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    
    var showTagsSheet by remember { mutableStateOf(false) }
    
    val ext = LopTheme.extended
    val accent = if (form.type == TransactionType.INCOME) ext.income else ext.expense
    val typeCategories = categories.filter { it.type == form.type }

    // IMPORTANT : ne pas utiliser fillMaxHeight() ici.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        // En-tête
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (vm.isEditing) stringResource(R.string.tx_edit_title) else stringResource(R.string.tx_new_title), style = MaterialTheme.typography.titleLarge)
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.close),
                modifier = Modifier
                    .size(28.dp)
                    .clickableNoRipple(onBack),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Sélecteur de type
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TypeSegment(stringResource(R.string.tx_type_expense), form.type == TransactionType.EXPENSE, ext.expense, Modifier.weight(1f)) {
                vm.setType(TransactionType.EXPENSE)
            }
            TypeSegment(stringResource(R.string.tx_type_income), form.type == TransactionType.INCOME, ext.income, Modifier.weight(1f)) {
                vm.setType(TransactionType.INCOME)
            }
        }
        
        Spacer(Modifier.height(24.dp))

        // Contenu défilant : champs + options
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Montant (Clavier système)
            item {
                OutlinedTextField(
                    value = form.amountInput.takeIf { it != "0" } ?: "",
                    onValueChange = { newValue ->
                        // Filtrer : chiffres + une seule virgule/point
                        val filtered = newValue.filter { it.isDigit() || it == ',' || it == '.' }
                        val normalized = filtered.replace(',', '.')
                        if (normalized.count { it == '.' } <= 1 && normalized.length <= 12) {
                            vm.setAmountRaw(normalized)
                        }
                    },
                    label = { Text(stringResource(R.string.tx_amount_label)) },
                    leadingIcon = {
                        Text(
                            text = if (form.type == TransactionType.INCOME) "+" else "−",
                            color = accent,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    },
                    trailingIcon = {
                        Text(
                            text = "€",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
            }

            // 2. Date de paiement
            item {
                var showDatePicker by remember { mutableStateOf(false) }
                val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy", java.util.Locale.getDefault())
                val formattedDate = java.time.Instant.ofEpochMilli(form.date)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .format(dateFormatter)

                if (showDatePicker) {
                    val datePickerState = androidx.compose.material3.rememberDatePickerState(
                        initialSelectedDateMillis = form.date
                    )
                    androidx.compose.material3.DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let { vm.setDate(it) }
                                showDatePicker = false
                            }) {
                                Text("OK")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showDatePicker = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    ) {
                        androidx.compose.material3.DatePicker(state = datePickerState)
                    }
                }

                DropdownSelector(
                    label = stringResource(R.string.tx_date_label),
                    value = formattedDate,
                    icon = androidx.compose.material.icons.Icons.Filled.DateRange,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    expanded = false,
                    onClick = { showDatePicker = true }
                ) {}
            }

            // 3. Intitulé
            item {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = vm::setTitle,
                    label = { Text(stringResource(R.string.tx_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
            }

            // 4. Catégorie (Dropdown)
            item {
                var expanded by remember { mutableStateOf(false) }
                val selectedCat = typeCategories.find { it.id == form.categoryId }
                
                DropdownSelector(
                    label = stringResource(R.string.tx_category_label),
                    value = selectedCat?.name ?: stringResource(R.string.tx_select_category),
                    icon = selectedCat?.let { IconMapper.get(it.icon) },
                    iconTint = selectedCat?.let { Color(it.colorArgb) },
                    expanded = expanded,
                    onClick = { expanded = true }
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        typeCategories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                leadingIcon = {
                                    CircleIcon(
                                        icon = IconMapper.get(cat.icon),
                                        tint = Color(cat.colorArgb),
                                        background = Color(cat.colorArgb).copy(alpha = 0.14f),
                                        size = 32.dp,
                                    )
                                },
                                onClick = {
                                    vm.setCategory(cat.id)
                                    expanded = false
                                }
                            )
                        }
                        
                        // Bouton "Créer une catégorie"
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().clickable { 
                                expanded = false
                                onNavigateToCreateCategory()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.tx_create_category), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 5. Compte (Dropdown)
            item {
                var expanded by remember { mutableStateOf(false) }
                val selectedAcc = accounts.find { it.id == form.accountId }
                
                DropdownSelector(
                    label = stringResource(R.string.tx_account_label),
                    value = selectedAcc?.name ?: stringResource(R.string.tx_select_account),
                    expanded = expanded,
                    onClick = { expanded = true }
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text(acc.name) },
                                onClick = {
                                    vm.setAccount(acc.id)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // 6. Étiquettes (Tags)
            item {
                val selectedTags = tags.filter { it.id in form.tagIds }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showTagsSheet = true },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.tx_tags_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        if (selectedTags.isEmpty()) {
                            Text(stringResource(R.string.tx_no_tags), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                selectedTags.forEach { tag ->
                                    com.lop.budget.ui.components.PillTag(text = tag.name, color = Color(tag.colorArgb))
                                }
                            }
                        }
                    }
                }
            }

            // 7. Notes
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = form.note,
                    onValueChange = vm::setNote,
                    label = { Text(stringResource(R.string.tx_notes_label)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
            }

            // 8. Récurrence
            item {
                var expanded by remember { mutableStateOf(false) }
                val freqs = listOf(
                    RecurrenceFrequency.NONE to stringResource(R.string.tx_repeat_none),
                    RecurrenceFrequency.DAILY to stringResource(R.string.tx_repeat_daily),
                    RecurrenceFrequency.WEEKLY to stringResource(R.string.tx_repeat_weekly),
                    RecurrenceFrequency.MONTHLY to stringResource(R.string.tx_repeat_monthly),
                    RecurrenceFrequency.YEARLY to stringResource(R.string.tx_repeat_yearly),
                )
                val selectedFreqLabel = freqs.find { it.first == form.frequency }?.second ?: stringResource(R.string.tx_repeat_none)

                DropdownSelector(
                    label = stringResource(R.string.tx_repeat_label),
                    value = selectedFreqLabel,
                    expanded = expanded,
                    onClick = { expanded = true }
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        freqs.forEach { (freq, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    vm.setFrequency(freq)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // Options avancées si récurrent
                AnimatedVisibility(visible = form.frequency != RecurrenceFrequency.NONE) {
                    Column(Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tx_repeat_every), style = MaterialTheme.typography.bodyLarge)
                            OutlinedTextField(
                                value = form.interval.toString(),
                                onValueChange = { vm.setInterval(it.toIntOrNull() ?: 1) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(80.dp),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Text(stringResource(R.string.tx_repeat_intervals), style = MaterialTheme.typography.bodyMedium)
                        }
                        
                        if (form.frequency == RecurrenceFrequency.WEEKLY) {
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.tx_repeat_days_of_week), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val days = listOf("L" to 1, "M" to 2, "M" to 3, "J" to 4, "V" to 5, "S" to 6, "D" to 7)
                                days.forEach { (lbl, num) ->
                                    val sel = num in form.daysOfWeek
                                    Surface(
                                        shape = CircleShape,
                                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .pressScaleClickable(intent = HapticIntent.Selection) { vm.toggleDayOfWeek(num) },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                lbl,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.tx_repeat_ends), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        // Options de fin
                        val endPolicy = when {
                            form.endDate != null -> "date"
                            form.maxOccurrences != null -> "occurrences"
                            else -> "never"
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(
                                selected = endPolicy == "never",
                                onClick = { 
                                    vm.setEndDate(null)
                                    vm.setMaxOccurrences(null)
                                }
                            )
                            Text(stringResource(R.string.tx_repeat_ends_never), modifier = Modifier.clickable { 
                                vm.setEndDate(null)
                                vm.setMaxOccurrences(null)
                            })
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(
                                selected = endPolicy == "date",
                                onClick = { 
                                    vm.setEndDate(form.date + 86400000L * 30) // +30 jours par défaut
                                    vm.setMaxOccurrences(null)
                                }
                            )
                            Text(stringResource(R.string.tx_repeat_ends_on), modifier = Modifier.clickable { 
                                if (endPolicy != "date") {
                                    vm.setEndDate(form.date + 86400000L * 30)
                                    vm.setMaxOccurrences(null)
                                }
                            })
                            Spacer(Modifier.width(8.dp))
                            if (endPolicy == "date") {
                                var showEndDatePicker by remember { mutableStateOf(false) }
                                val endFormatted = java.time.Instant.ofEpochMilli(form.endDate ?: System.currentTimeMillis())
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toLocalDate()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.getDefault()))
                                
                                if (showEndDatePicker) {
                                    val endDatePickerState = androidx.compose.material3.rememberDatePickerState(
                                        initialSelectedDateMillis = form.endDate ?: System.currentTimeMillis()
                                    )
                                    androidx.compose.material3.DatePickerDialog(
                                        onDismissRequest = { showEndDatePicker = false },
                                        confirmButton = {
                                            androidx.compose.material3.TextButton(onClick = {
                                                endDatePickerState.selectedDateMillis?.let { vm.setEndDate(it) }
                                                showEndDatePicker = false
                                            }) { Text(stringResource(R.string.ok)) }
                                        },
                                        dismissButton = {
                                            androidx.compose.material3.TextButton(onClick = { showEndDatePicker = false }) { Text(stringResource(R.string.cancel)) }
                                        }
                                    ) {
                                        androidx.compose.material3.DatePicker(state = endDatePickerState)
                                    }
                                }
                                
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable { showEndDatePicker = true }
                                ) {
                                    Text(
                                        endFormatted, 
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(
                                selected = endPolicy == "occurrences",
                                onClick = { 
                                    vm.setMaxOccurrences(10) // 10 par défaut
                                    vm.setEndDate(null)
                                }
                            )
                            Text(stringResource(R.string.tx_repeat_ends_after), modifier = Modifier.clickable { 
                                if (endPolicy != "occurrences") {
                                    vm.setMaxOccurrences(10)
                                    vm.setEndDate(null)
                                }
                            })
                            Spacer(Modifier.width(8.dp))
                            if (endPolicy == "occurrences") {
                                OutlinedTextField(
                                    value = form.maxOccurrences?.toString() ?: "",
                                    onValueChange = { 
                                        val n = it.filter { c -> c.isDigit() }.toIntOrNull()
                                        if (n != null && n > 0) vm.setMaxOccurrences(n)
                                    },
                                    modifier = Modifier.width(80.dp).height(50.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.tx_repeat_occurrences_label))
                            }
                        }
                    }
                }
            }
            // Espace pour le bouton
            item { Spacer(Modifier.height(80.dp)) }
        }

        // Bouton Enregistrer flottant en bas
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Button(
                onClick = { vm.save(onDone = onBack) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                enabled = form.amount > 0.0 && form.categoryId != null && form.accountId != null
            ) {
                Text(if (vm.isEditing) stringResource(R.string.save) else stringResource(R.string.create), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showTagsSheet) {
        TagsBottomSheet(
            tags = tags,
            selectedTagIds = form.tagIds,
            onToggleTag = { id ->
                if (form.tagIds.contains(id) || form.tagIds.size < 3) {
                    vm.toggleTag(id)
                }
            },
            onCreateTag = { name, color -> vm.createTag(name, color) },
            onDismiss = { showTagsSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagsBottomSheet(
    tags: List<com.lop.budget.data.local.entity.TagEntity>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newTagName by remember { mutableStateOf("") }
    val colors = listOf(
        Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
        Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1),
        Color(0xFF00897B), Color(0xFF00838F), Color(0xFF43A047), Color(0xFF2E7D32),
        Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835), Color(0xFFFFB300),
        Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF546E7A)
    )
    var selectedColor by remember { mutableStateOf(colors[0]) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth()) {
            Text(stringResource(R.string.tx_tags_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.tx_tags_sheet_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            // Liste des tags existants
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tags.forEach { tag ->
                        val isSelected = selectedTagIds.contains(tag.id)
                        val color = Color(tag.colorArgb)
                        Surface(
                            modifier = Modifier.clickable { onToggleTag(tag.id) },
                            shape = CircleShape,
                            color = if (isSelected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isSelected) BorderStroke(1.dp, color) else BorderStroke(1.dp, Color.Transparent)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                                Spacer(Modifier.width(8.dp))
                                Text(tag.name, style = MaterialTheme.typography.labelMedium, color = if (isSelected) color else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Création d'un nouveau tag
            Text(stringResource(R.string.tx_tags_create_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newTagName,
                onValueChange = { newTagName = it },
                label = { Text(stringResource(R.string.tx_tags_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(16.dp))
            
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(colors) { color ->
                    val isSelected = selectedColor == color
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { selectedColor = color }
                            .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            
            Button(
                onClick = {
                    onCreateTag(newTagName, selectedColor.toArgb())
                    newTagName = ""
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = newTagName.isNotBlank() && (selectedTagIds.size < 3),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.add), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TypeSegment(label: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.pressScaleClickable(intent = HapticIntent.Selection, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (selected) BorderStroke(1.dp, color.copy(alpha = 0.5f)) else null
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun DropdownSelector(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color? = null,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f)
    
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .border(
                    1.dp,
                    if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(16.dp)
                ),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null && iconTint != null) {
                    CircleIcon(
                        icon = icon,
                        tint = iconTint,
                        background = iconTint.copy(alpha = 0.14f),
                        size = 32.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        content()
    }
}
