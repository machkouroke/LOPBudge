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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.toArgb
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

/**
 * Redesign "Material expressive" : lisible, hiérarchie claire, surfaces M3.
 * Pas d'effet glass/transparent.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    val canSave = form.amount > 0.0 && form.categoryId != null && form.accountId != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (vm.isEditing) stringResource(R.string.tx_edit_title) else stringResource(R.string.tx_new_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(26.dp)
                            .clickableNoRipple(onBack),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            // Bouton d'action principal, fixe, ergonomique.
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Button(
                        onClick = { vm.save(onDone = onBack) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            if (vm.isEditing) stringResource(R.string.save) else stringResource(R.string.create),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (!canSave) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tx_required_fields_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                // Segmented control (expressive)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TypeSegment(
                        label = stringResource(R.string.tx_type_expense),
                        selected = form.type == TransactionType.EXPENSE,
                        color = ext.expense,
                        modifier = Modifier.weight(1f),
                    ) { vm.setType(TransactionType.EXPENSE) }

                    TypeSegment(
                        label = stringResource(R.string.tx_type_income),
                        selected = form.type == TransactionType.INCOME,
                        color = ext.income,
                        modifier = Modifier.weight(1f),
                    ) { vm.setType(TransactionType.INCOME) }
                }
            }

            item {
                SectionTitle(stringResource(R.string.tx_section_main))
            }

            item {
                ElevatedCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = form.amountInput.takeIf { it != "0" } ?: "",
                            onValueChange = { newValue ->
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
                                    modifier = Modifier.padding(start = 14.dp)
                                )
                            },
                            trailingIcon = {
                                Text(
                                    text = "€",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(end = 14.dp)
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                color = accent,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accent,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            )
                        )

                        // Date
                        var showDatePicker by remember { mutableStateOf(false) }
                        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern(
                            "dd MMMM yyyy",
                            java.util.Locale.getDefault(),
                        )
                        val formattedDate = java.time.Instant.ofEpochMilli(form.date)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .format(dateFormatter)

                        if (showDatePicker) {
                            val state = rememberDatePickerState(initialSelectedDateMillis = form.date)
                            androidx.compose.material3.DatePickerDialog(
                                onDismissRequest = { showDatePicker = false },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        state.selectedDateMillis?.let { vm.setDate(it) }
                                        showDatePicker = false
                                    }) { Text(stringResource(R.string.ok)) }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { showDatePicker = false }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                },
                            ) {
                                androidx.compose.material3.DatePicker(state = state)
                            }
                        }

                        DropdownSelector(
                            label = stringResource(R.string.tx_date_label),
                            value = formattedDate,
                            icon = Icons.Filled.DateRange,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            expanded = false,
                            onClick = { showDatePicker = true },
                        ) {}

                        OutlinedTextField(
                            value = form.title,
                            onValueChange = vm::setTitle,
                            label = { Text(stringResource(R.string.tx_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.tx_section_classification))
            }

            item {
                ElevatedCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Catégorie
                        var expandedCategory by remember { mutableStateOf(false) }
                        val selectedCat = typeCategories.find { it.id == form.categoryId }

                        DropdownSelector(
                            label = stringResource(R.string.tx_category_label),
                            value = selectedCat?.name ?: stringResource(R.string.tx_select_category),
                            icon = selectedCat?.let { IconMapper.get(it.icon) },
                            iconTint = selectedCat?.let { Color(it.colorArgb) },
                            expanded = expandedCategory,
                            onClick = { expandedCategory = true },
                        ) {
                            DropdownMenu(
                                expanded = expandedCategory,
                                onDismissRequest = { expandedCategory = false },
                                modifier = Modifier.fillMaxWidth(0.92f),
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
                                            expandedCategory = false
                                        },
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedCategory = false
                                            onNavigateToCreateCategory()
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            stringResource(R.string.tx_create_category),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }

                        // Compte
                        var expandedAccount by remember { mutableStateOf(false) }
                        val selectedAcc = accounts.find { it.id == form.accountId }
                        DropdownSelector(
                            label = stringResource(R.string.tx_account_label),
                            value = selectedAcc?.name ?: stringResource(R.string.tx_select_account),
                            expanded = expandedAccount,
                            onClick = { expandedAccount = true },
                        ) {
                            DropdownMenu(
                                expanded = expandedAccount,
                                onDismissRequest = { expandedAccount = false },
                                modifier = Modifier.fillMaxWidth(0.92f),
                            ) {
                                accounts.forEach { acc ->
                                    DropdownMenuItem(
                                        text = { Text(acc.name) },
                                        onClick = {
                                            vm.setAccount(acc.id)
                                            expandedAccount = false
                                        },
                                    )
                                }
                            }
                        }

                        // Tags
                        val selectedTags = tags.filter { it.id in form.tagIds }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showTagsSheet = true },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.tx_tags_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(8.dp))
                                if (selectedTags.isEmpty()) {
                                    Text(
                                        stringResource(R.string.tx_no_tags),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    androidx.compose.foundation.layout.FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        selectedTags.forEach { tag ->
                                            com.lop.budget.ui.components.PillTag(
                                                text = tag.name,
                                                color = Color(tag.colorArgb),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.tx_section_optional))
            }

            item {
                ElevatedCard {
                    OutlinedTextField(
                        value = form.note,
                        onValueChange = vm::setNote,
                        label = { Text(stringResource(R.string.tx_notes_label)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }

            // Recurrence (gardée, mais dans une carte lisible)
            item {
                SectionTitle(stringResource(R.string.tx_repeat_label))
            }

            item {
                ElevatedCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        var expanded by remember { mutableStateOf(false) }
                        val freqs = listOf(
                            RecurrenceFrequency.NONE to stringResource(R.string.tx_repeat_none),
                            RecurrenceFrequency.DAILY to stringResource(R.string.tx_repeat_daily),
                            RecurrenceFrequency.WEEKLY to stringResource(R.string.tx_repeat_weekly),
                            RecurrenceFrequency.MONTHLY to stringResource(R.string.tx_repeat_monthly),
                            RecurrenceFrequency.YEARLY to stringResource(R.string.tx_repeat_yearly),
                        )
                        val selectedFreqLabel = freqs.find { it.first == form.frequency }?.second
                            ?: stringResource(R.string.tx_repeat_none)

                        DropdownSelector(
                            label = stringResource(R.string.tx_repeat_label),
                            value = selectedFreqLabel,
                            expanded = expanded,
                            onClick = { expanded = true },
                        ) {
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.fillMaxWidth(0.92f),
                            ) {
                                freqs.forEach { (freq, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            vm.setFrequency(freq)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Le reste des options avancées est conservé tel quel pour l'instant.
                        // L'objectif ici est surtout de remettre une hiérarchie + lisibilité.
                        AnimatedVisibility(visible = form.frequency != RecurrenceFrequency.NONE) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.tx_repeat_every))
                                    Spacer(Modifier.width(10.dp))
                                    OutlinedTextField(
                                        value = form.interval.toString(),
                                        onValueChange = { vm.setInterval(it.toIntOrNull() ?: 1) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(90.dp),
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.tx_repeat_intervals))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTagsSheet) {
        // On garde la bottom sheet tags pour l'instant (fonctionnelle), mais le screen principal
        // est désormais lisible et entièrement Material M3.
        TagsBottomSheet(
            tags = tags,
            selectedTagIds = form.tagIds,
            onToggleTag = { id ->
                if (form.tagIds.contains(id) || form.tagIds.size < 3) {
                    vm.toggleTag(id)
                }
            },
            onCreateTag = { name, color -> vm.createTag(name, color) },
            onDismiss = { showTagsSheet = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ElevatedCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun TypeSegment(
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.pressScaleClickable(intent = HapticIntent.Selection, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) color.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
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
    content: @Composable () -> Unit,
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
            color = MaterialTheme.colorScheme.surfaceVariant,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

// --- Tags sheet kept as-is (existing UI). ---
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagsBottomSheet(
    tags: List<com.lop.budget.data.local.entity.TagEntity>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep existing implementation by delegating to the original file content.
    // NOTE: In this repo, TagsBottomSheet is defined in this file historically.
    // For now we keep the previous code path by reusing ModalBottomSheet.
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newTagName by remember { mutableStateOf("") }

    val colors = listOf(
        Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
        Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1),
        Color(0xFF00897B), Color(0xFF00838F), Color(0xFF43A047), Color(0xFF2E7D32),
        Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835), Color(0xFFFFB300),
        Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF546E7A)
    )
    var selectedColor by remember { mutableStateOf(colors[0]) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.tx_tags_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.tx_tags_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            if (tags.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
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
                                Text(
                                    tag.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) color else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            Text(
                stringResource(R.string.tx_tags_create_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
