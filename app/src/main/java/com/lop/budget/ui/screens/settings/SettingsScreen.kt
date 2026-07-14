package com.lop.budget.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToTags: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    var keyInput by remember(state.geminiKey) { mutableStateOf(state.geminiKey) }
    var currencyInput by remember(state.currency) { mutableStateOf(state.currency) }

    LopScreenScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack
    ) {
        // Apparence
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        stringResource(R.string.settings_appearance),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val modes = listOf(
                            ThemeMode.SYSTEM to R.string.settings_theme_system,
                            ThemeMode.LIGHT to R.string.settings_theme_light,
                            ThemeMode.DARK to R.string.settings_theme_dark
                        )
                        items(modes, key = { it.first.name }) { (mode, labelRes) ->
                            PillTag(
                                text = stringResource(labelRes),
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
                                stringResource(R.string.settings_dynamic_color_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_dynamic_color_desc),
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
                    Text(
                        stringResource(R.string.settings_auto_detection),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_auto_detection_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.settings_detect_via_notif),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = state.notificationDetectionEnabled,
                            onCheckedChange = vm::setNotificationDetectionEnabled,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                )
                            )
                        },
                    ) {
                        Text(stringResource(R.string.settings_allow_notif_access))
                    }
                }
            }
        }

        // Devise
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        stringResource(R.string.settings_currency),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = currencyInput,
                        onValueChange = {
                            currencyInput = it.uppercase().take(3); vm.setCurrency(currencyInput)
                        },
                        label = { Text(stringResource(R.string.settings_currency_label)) },
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
                    Text(
                        stringResource(R.string.settings_ai_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_ai_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; vm.setGeminiKey(it.trim()) },
                        label = { Text(stringResource(R.string.settings_ai_key_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Gestion des données (Tags)
        item {
            FloatingCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoRipple { onNavigateToTags() }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Gestion des tags",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Renommer ou supprimer vos étiquettes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
