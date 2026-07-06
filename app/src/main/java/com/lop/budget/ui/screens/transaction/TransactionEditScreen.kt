package com.lop.budget.ui.screens.transaction

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.HapticIntent
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.components.pressScaleClickable
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.IconMapper

/**
 * Contenu du formulaire d'ajout de transaction.
 * Conçu pour être affiché dans un ModalBottomSheet expansible.
 */
@Composable
fun TransactionEditScreen(
    onBack: () -> Unit,
    vm: TransactionEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val debts by vm.debts.collectAsStateWithLifecycle()
    val ext = LopTheme.extended

    val accent = if (form.type == TransactionType.INCOME) ext.income else ext.expense
    val typeCategories = categories.filter { it.type == form.type }

    // IMPORTANT : ne pas utiliser fillMaxHeight() ici.
    // Le ModalBottomSheet gère lui-même sa hauteur selon son état (PartiallyExpanded / Expanded).
    // fillMaxHeight() force une hauteur fixe qui entre en conflit avec l'animation du sheet
    // et provoque des oscillations (vibrations) lors du glissement entre les deux états.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        // En-tête simplifié pour le bottom sheet (icône de fermeture au lieu de retour)
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
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

        Spacer(Modifier.height(8.dp))

        // Sélecteur de type (segmenté capsule)
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

        Spacer(Modifier.height(20.dp))

        // Montant affiché
        Text(
            (if (form.type == TransactionType.INCOME) "+" else "−") + form.amountInput.replace('.', ',') + " €",
            style = MaterialTheme.typography.displaySmall,
            color = accent,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // Contenu défilant : champs + options
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = vm::setTitle,
                    label = { Text("Intitulé") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Catégories
            item {
                Text("Catégorie", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(typeCategories, key = { it.id }) { cat ->
                        val selected = form.categoryId == cat.id
                        val c = Color(cat.colorArgb)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.pressScaleClickable(intent = HapticIntent.Selection) { vm.setCategory(cat.id) },
                        ) {
                            CircleIcon(
                                icon = IconMapper.get(cat.icon),
                                tint = c,
                                background = if (selected) c.copy(alpha = 0.32f) else c.copy(alpha = 0.14f),
                                size = 52.dp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(cat.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Tags
            item {
                Text("Tags", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tags, key = { it.id }) { tag ->
                        val selected = tag.id in form.tagIds
                        val c = Color(tag.colorArgb)
                        Surface(
                            color = if (selected) c.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = CircleShape,
                            modifier = Modifier.pressScaleClickable(intent = HapticIntent.Tap) { vm.toggleTag(tag.id) },
                        ) {
                            Text(
                                "#${tag.name}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) c else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            // Comptes
            item {
                Text("Compte", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(accounts, key = { it.id }) { acc ->
                        val selected = form.accountId == acc.id
                        PillTag(
                            text = acc.name,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.pressScaleClickable(intent = HapticIntent.Selection) { vm.setAccount(acc.id) },
                        )
                    }
                }
            }

            // Récurrence avancée
            item {
                RecurrenceSection(form, vm)
            }

            // Rattachement objectif / dette (uniquement dépense)
            if (form.type == TransactionType.EXPENSE && (goals.isNotEmpty() || debts.isNotEmpty())) {
                item {
                    Text("Rattacher à", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(goals, key = { "g${it.id}" }) { g ->
                            val selected = form.linkedGoalId == g.id
                            PillTag(
                                text = "🎯 ${g.name}",
                                color = if (selected) ext.income else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.pressScaleClickable(intent = HapticIntent.Selection) {
                                    vm.setLinkedGoal(if (selected) null else g.id)
                                },
                            )
                        }
                        items(debts, key = { "d${it.id}" }) { d ->
                            val selected = form.linkedDebtId == d.id
                            PillTag(
                                text = "💳 ${d.name}",
                                color = if (selected) ext.expense else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.pressScaleClickable(intent = HapticIntent.Selection) {
                                    vm.setLinkedDebt(if (selected) null else d.id)
                                },
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = form.note,
                    onValueChange = vm::setNote,
                    label = { Text("Note (optionnel)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Pavé numérique (reste fixé en bas du bottom sheet)
        NumericKeypad(
            onDigit = { digit -> vm.appendDigit(digit) },
            onDelete = { vm.deleteDigit() },
            onValidate = { vm.save(onBack) },
            accent = accent,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onValidate: () -> Unit,
    accent: Color,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(",", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .pressScaleClickable(intent = HapticIntent.Tap) {
                                when (key) {
                                    "⌫" -> onDelete()
                                    else -> onDigit(key)
                                }
                            },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (key == "⌫") Icon(Icons.Filled.Backspace, "Effacer")
                            else Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .pressScaleClickable(intent = HapticIntent.Confirm, onClick = onValidate),
            shape = MaterialTheme.shapes.large,
            color = accent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Enregistrer", color = Color.Black, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TypeSegment(label: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.pressScaleClickable(intent = HapticIntent.Selection, onClick = onClick),
        shape = CircleShape,
        color = if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
private fun RecurrenceSection(form: TransactionForm, vm: TransactionEditViewModel) {
    val freqs = listOf(
        RecurrenceFrequency.NONE to "Jamais",
        RecurrenceFrequency.DAILY to "Quotidien",
        RecurrenceFrequency.WEEKLY to "Hebdo",
        RecurrenceFrequency.MONTHLY to "Mensuel",
        RecurrenceFrequency.YEARLY to "Annuel",
    )
    Column {
        Text("Répétition", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(freqs, key = { it.first.name }) { (f, label) ->
                val selected = form.frequency == f
                PillTag(
                    text = label,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.pressScaleClickable(intent = HapticIntent.Selection) { vm.setFrequency(f) },
                )
            }
        }
        if (form.frequency != RecurrenceFrequency.NONE) {
            Spacer(Modifier.height(10.dp))
            FloatingCard(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tous les ", style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(
                            value = form.interval.toString(),
                            onValueChange = { vm.setInterval(it.toIntOrNull() ?: 1) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                        Text("  intervalle(s)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (form.frequency == RecurrenceFrequency.WEEKLY) {
                        Spacer(Modifier.height(10.dp))
                        Text("Jours", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val days = listOf("L" to 1, "M" to 2, "M" to 3, "J" to 4, "V" to 5, "S" to 6, "D" to 7)
                            days.forEach { (lbl, num) ->
                                val sel = num in form.daysOfWeek
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .pressScaleClickable(intent = HapticIntent.Selection) { vm.toggleDayOfWeek(num) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(36.dp),
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
        }
    }
}
