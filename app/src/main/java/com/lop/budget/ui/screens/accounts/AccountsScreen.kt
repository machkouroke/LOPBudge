package com.lop.budget.ui.screens.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.ui.components.CircleIcon
import androidx.compose.ui.platform.testTag
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.screens.manage.libelle
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    vm: AccountsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    LopScreenScaffold(
        title = stringResource(R.string.accounts_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_ACCOUNT_BALANCES)
    ) {
        when (val s = state) {

            AccountsUiState.Loading -> item {
                val loading = stringResource(R.string.loading)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp)
                        .testTag(TestTags.ACCOUNTS_LOADING)
                        .semantics { contentDescription = loading },
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }

            AccountsUiState.Error -> item {
                Text(
                    stringResource(R.string.accounts_load_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .testTag(TestTags.ACCOUNTS_ERROR)
                )
            }

            is AccountsUiState.Loaded -> {
                item {
                    FloatingCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.accounts_total_balance),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                Format.money(s.totalBalance, s.currency),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag(TestTags.ACCOUNTS_TOTAL)
                            )
                        }
                    }
                }

                if (s.accounts.isEmpty()) {
                    // CA-05
                    item {
                        Text(
                            stringResource(R.string.accounts_empty_active),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                                .testTag(TestTags.ACCOUNTS_EMPTY)
                        )
                    }
                }

                items(s.accounts, key = { it.account.id }) { ab ->
                    val account = ab.account
                    FloatingCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("${TestTags.ACCOUNTS_ROW}_${account.id}")
                            .clickableNoRipple { onOpenDetail(account.id) },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircleIcon(
                                icon = IconMapper.get(account.icon),
                                tint = Color(account.colorArgb),
                                background = Color(account.colorArgb).copy(alpha = 0.18f)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(account.name, style = MaterialTheme.typography.titleMedium)
                                // CA-02 / P-2 : type, et banque seulement si elle est renseignée.
                                Text(
                                    listOfNotNull(
                                        account.type.libelle(),
                                        account.bankName?.takeIf { it.isNotBlank() }
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // CA-03
                                if (!account.includeInTotal) {
                                    Text(
                                        stringResource(R.string.accounts_excluded_from_total),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                Format.money(ab.balance, s.currency),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
