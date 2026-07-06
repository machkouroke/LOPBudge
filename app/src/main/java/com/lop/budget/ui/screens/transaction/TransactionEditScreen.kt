package com.lop.budget.ui.screens.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.HapticIntent
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.components.pressScaleClickable
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.IconMapper

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
            Text("Nouvelle transaction", style = MaterialTheme.typography.titleLarge)
            Icon(
                Icons.Filled.Close,
                contentDescription = "Fermer",
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
            TypeSegment("Dépense", form.type == TransactionType.EXPENSE, ext.expense, Modifier.weight(1f)) {
                vm.setType(TransactionType.EXPENSE)
            }
            TypeSegment("Revenu", form.type == TransactionType.INCOME, ext.income, Modifier.weight(1f)) {
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
                    label = { Text("Montant") },
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

            // 2. Intitulé
            item {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = vm::setTitle,
                    label = { Text("Nom de la transaction") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
            }

            // 3. Catégorie (Dropdown)
            item {
                var expanded by remember { mutableStateOf(false) }
                val selectedCat = typeCategories.find { it.id == form.categoryId }
                
                DropdownSelector(
                    label = "Catégorie",
                    value = selectedCat?.name ?: "Sélectionner une catégorie",
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
                                Text("Créer une nouvelle catégorie", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 4. Compte (Dropdown)
            item {
                var expanded by remember { mutableStateOf(false) }
                val selectedAcc = accounts.find { it.id == form.accountId }
                
                DropdownSelector(
                    label = "Compte",
                    value = selectedAcc?.name ?: "Sélectionner un compte",
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

            // 5. Notes
            item {
                OutlinedTextField(
                    value = form.note,
                    onValueChange = vm::setNote,
                    label = { Text("Notes (optionnel)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
            }

            // 6. Récurrence
            item {
                var expanded by remember { mutableStateOf(false) }
                val freqs = listOf(
                    RecurrenceFrequency.NONE to "Ne se répète pas",
                    RecurrenceFrequency.DAILY to "Tous les jours",
                    RecurrenceFrequency.WEEKLY to "Toutes les semaines",
                    RecurrenceFrequency.MONTHLY to "Tous les mois",
                    RecurrenceFrequency.YEARLY to "Tous les ans",
                )
                val selectedFreqLabel = freqs.find { it.first == form.frequency }?.second ?: "Ne se répète pas"

                DropdownSelector(
                    label = "Répéter",
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
                            Text("Tous les ", style = MaterialTheme.typography.bodyLarge)
                            OutlinedTextField(
                                value = form.interval.toString(),
                                onValueChange = { vm.setInterval(it.toIntOrNull() ?: 1) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(80.dp),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Text(" intervalle(s)", style = MaterialTheme.typography.bodyMedium)
                        }
                        
                        if (form.frequency == RecurrenceFrequency.WEEKLY) {
                            Spacer(Modifier.height(16.dp))
                            Text("Jours de la semaine", style = MaterialTheme.typography.labelLarge)
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
                    }
                }
            }

            // Espace pour le bouton
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Bouton Enregistrer flottant en bas
    Box(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Button(
            onClick = { vm.save(onDone = onBack) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            enabled = form.amount > 0.0 && form.categoryId != null && form.accountId != null
        ) {
            Text("Créer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
