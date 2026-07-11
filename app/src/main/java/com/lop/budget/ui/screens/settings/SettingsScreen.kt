package com.lop.budget.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    var keyInput by remember(state.geminiKey) { mutableStateOf(state.geminiKey) }
    var currencyInput by remember(state.currency) { mutableStateOf(state.currency) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 20.dp,
            bottom = 60.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Retour",
                    modifier = Modifier
                        .size(26.dp)
                        .clickableNoRipple(onBack)
                )
                Spacer(Modifier.width(12.dp))
                Text("Réglages", style = MaterialTheme.typography.headlineMedium)
            }
        }

        // Apparence
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Apparence", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Thème",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val modes = listOf(
                            ThemeMode.SYSTEM to "Système",
                            ThemeMode.LIGHT to "Clair",
                            ThemeMode.DARK to "Sombre"
                        )
                        items(modes, key = { it.first.name }) { (mode, label) ->
                            PillTag(
                                text = label,
                                color = if (state.themeMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickableNoRipple { vm.setThemeMode(mode) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Couleurs dynamiques (Material You)",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Adapte la palette au thème du système (Android 12+)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.dynamicColor, onCheckedChange = vm::setDynamicColor)
                    }
                }
            }
        }

        // Détection automatique via notifications
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Détection automatique", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "LOPBudge peut analyser localement certaines notifications de paiement pour vous proposer une transaction à ajouter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Détecter via notifications", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = state.notificationDetectionEnabled,
                            onCheckedChange = vm::setNotificationDetectionEnabled,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                    ) {
                        Text("Autoriser l'accès aux notifications")
                    }
                }
            }
        }

        // Devise
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Devise", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = currencyInput,
                        onValueChange = {
                            currencyInput = it.uppercase().take(3); vm.setCurrency(currencyInput)
                        },
                        label = { Text("Code ISO (EUR, USD, GBP…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Assistant IA
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Assistant IA (Gemini)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Colle ta clé API Gemini (gratuite via Google AI Studio). Elle reste stockée localement sur l'appareil.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; vm.setGeminiKey(it.trim()) },
                        label = { Text("Clé API Gemini") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
