package com.lop.budget.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.SwipeableTransactionRow
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.ui.theme.ExpenseCoral
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HomeScreen(
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    onOpenTransaction: (Long) -> Unit,
    onOpenAi: () -> Unit,
    onOpenMonthly: (TransactionType, YearMonth) -> Unit,
    navController: androidx.navigation.NavController,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // LOP-49 : insets dynamiques pour header et contenu
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var isMonthPickerOpen by remember { mutableStateOf(false) }

    if (isMonthPickerOpen) {
        MonthPickerBottomSheet(
            selected = state.month,
            onSelect = vm::setMonth,
            onDismiss = { isMonthPickerOpen = false },
        )
    }

    val listState = rememberLazyListState()
    // LOP-50 : bouton visible dès que le premier item n'est plus visible
    val showScrollTop = listState.firstVisibleItemIndex > 0

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Contenu principal entièrement scrollable
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                // LOP-49 : top = status bar + hauteur header (~72 dp)
                start = 20.dp, end = 20.dp,
                top = statusBarPadding + 50.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Section Total dépensé + Budget Donut
            // PERF FIX #4 : clé stable pour éviter la recréation du Canvas à chaque recomposition
            item(key = "budget_summary") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Total dépensé en juin",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Format.money(208.0, state.currency),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBackIos,
                            null,
                            tint = ExpenseCoral,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${Format.money(1625.52, state.currency)} vs dernière période",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ExpenseCoral
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // Carte Budget global (Donut 2%)
                    val isPaid = tx.transaction.status == TransactionStatus.PAID
                    SwipeableTransactionRow(
                        isPaid = isPaid,
                        onTogglePaid = { viewModel.togglePaid(tx.transaction.id, tx.transaction.status) },
                        onDelete = { viewModel.deleteWithUndo(tx.transaction.id, snackbarHostState) }
                    ) {
                        FloatingCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Mini Donut Chart
                            Box(
                                modifier = Modifier.size(60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawArc(
                                        color = Color.DarkGray,
                                        startAngle = 0f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(12f)
                                    )
                                    drawArc(
                                        color = com.lop.budget.ui.theme.CategoryOrange,
                                        startAngle = -90f,
                                        sweepAngle = 360f * 0.02f, // 2%
                                        useCenter = false,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(12f)
                                    )
                                }
                                Text(
                                    "2%",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    "${
                                        Format.money(
                                            7792.0,
                                            state.currency
                                        )
                                    } restants dans le budget",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Juste au début. Votre budget\nest à peine touché.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Section Cartes Catégories
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Dépenses par catégorie",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Carte Restaurants
                        val isPaid = tx.transaction.status == TransactionStatus.PAID
                    SwipeableTransactionRow(
                        isPaid = isPaid,
                        onTogglePaid = { viewModel.togglePaid(tx.transaction.id, tx.transaction.status) },
                        onDelete = { viewModel.deleteWithUndo(tx.transaction.id, snackbarHostState) }
                    ) {
                        FloatingCard(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                        ) {
                            Column {
                                CircleIcon(
                                    icon = Icons.Filled.ChevronRight, // Placeholder restaurant
                                    tint = Color.Black,
                                    background = com.lop.budget.ui.theme.CategoryOrange,
                                    size = 48.dp
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Restaurants",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    Format.money(128.0, state.currency),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "/ ${Format.money(1372.0, state.currency)} restants",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Carte Épicerie
                        val isPaid = tx.transaction.status == TransactionStatus.PAID
                    SwipeableTransactionRow(
                        isPaid = isPaid,
                        onTogglePaid = { viewModel.togglePaid(tx.transaction.id, tx.transaction.status) },
                        onDelete = { viewModel.deleteWithUndo(tx.transaction.id, snackbarHostState) }
                    ) {
                        FloatingCard(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                        ) {
                            Column {
                                CircleIcon(
                                    icon = Icons.Filled.ChevronRight, // Placeholder groceries
                                    tint = Color.White,
                                    background = com.lop.budget.ui.theme.CategoryGreen,
                                    size = 48.dp
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Épicerie",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    Format.money(55.0, state.currency),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "/ ${Format.money(2445.0, state.currency)} restants",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Section Upcoming this month
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "À venir ce mois-ci",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val isPaid = tx.transaction.status == TransactionStatus.PAID
                    SwipeableTransactionRow(
                        isPaid = isPaid,
                        onTogglePaid = { viewModel.togglePaid(tx.transaction.id, tx.transaction.status) },
                        onDelete = { viewModel.deleteWithUndo(tx.transaction.id, snackbarHostState) }
                    ) {
                        FloatingCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { /* Open Upcoming Modal */ },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Stacked icons placeholder
                                Box(modifier = Modifier.width(48.dp)) {
                                    CircleIcon(
                                        Icons.Filled.ChevronRight,
                                        Color.White,
                                        com.lop.budget.ui.theme.CategoryRed,
                                        28.dp,
                                        Modifier.align(Alignment.CenterStart)
                                    )
                                    CircleIcon(
                                        Icons.Filled.ChevronRight,
                                        Color.White,
                                        com.lop.budget.ui.theme.CategoryBlue,
                                        28.dp,
                                        Modifier.align(Alignment.CenterEnd)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Expenses", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "3 transactions",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                Format.money(29.97, state.currency),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Section Accounts
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Accounts",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val isPaid = tx.transaction.status == TransactionStatus.PAID
                    SwipeableTransactionRow(
                        isPaid = isPaid,
                        onTogglePaid = { viewModel.togglePaid(tx.transaction.id, tx.transaction.status) },
                        onDelete = { viewModel.deleteWithUndo(tx.transaction.id, snackbarHostState) }
                    ) {
                        FloatingCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    Text(
                                        "Total",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        Format.money(672.36, state.currency),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                // Placeholder pour le mini graphique
                                Box(
                                    modifier = Modifier
                                        .size(80.dp, 30.dp)
                                        .background(com.lop.budget.ui.theme.IncomeGreen.copy(alpha = 0.2f))
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "R",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(
                                                Color.DarkGray,
                                                androidx.compose.foundation.shape.RoundedCornerShape(
                                                    4.dp
                                                )
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text("Revolut", style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(
                                    Format.money(622.36, state.currency),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "M",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(
                                                Color.DarkGray,
                                                androidx.compose.foundation.shape.RoundedCornerShape(
                                                    4.dp
                                                )
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text("Monobank", style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(
                                    Format.money(50.0, state.currency),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }

            // Section Recent transactions
            item {
                Text(
                    "Recent transactions",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // PERF FIX #2 : forEach remplacé par items plats avec clés stables.
            // Chaque en-tête de jour et chaque transaction sont des items LazyColumn indépendants,
            // ce qui permet au framework de recycler chaque ligne individuellement.
            state.dayGroups.forEach { day ->
                // En-tête du groupe de jour
                item(key = "day_header_${day.date}") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${day.date.dayOfMonth} ${
                                day.date.month.getDisplayName(
                                    TextStyle.SHORT,
                                    Locale.FRANCE
                                )
                            }",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = Format.money(day.total, state.currency),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Items de transaction individuels — recyclables par LazyColumn
                items(
                    items = day.transactions,
                    key = { tx -> "tx_${tx.transaction.id}" },
                ) { tx ->
                    val isIncome = tx.transaction.type == TransactionType.INCOME
                    val amountColor = if (isIncome) ext.income else ext.expense
                    val catColor = tx.category?.colorArgb?.let { Color(it) }
                        ?: MaterialTheme.colorScheme.primary
                    val recurring =
                        tx.transaction.recurrenceFrequency != RecurrenceFrequency.NONE

                    val isPaid = tx.transaction.status == TransactionStatus.PAID
                    SwipeableTransactionRow(
                        isPaid = isPaid,
                        onTogglePaid = { viewModel.togglePaid(tx.transaction.id, tx.transaction.status) },
                        onDelete = { viewModel.deleteWithUndo(tx.transaction.id, snackbarHostState) }
                    ) {
                        FloatingCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableNoRipple { onOpenTransaction(tx.transaction.id) }
                                .alpha(if (isPaid) 0.5f else 1f),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircleIcon(
                                icon = IconMapper.get(tx.category?.icon ?: "category"),
                                tint = catColor,
                                background = catColor.copy(alpha = 0.18f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        tx.transaction.title,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (recurring) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Filled.Repeat,
                                            "Récurrent",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                Text(
                                    tx.account?.name ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                (if (isIncome) "+" else "−") + Format.money(tx.transaction.amount, state.currency),
                                style = MaterialTheme.typography.titleMedium,
                                color = amountColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    }
                }
            }

            if (state.dayGroups.isEmpty()) {
                item {
                    Text(
                        "Aucune transaction ce mois-ci.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // LOP-50 : bouton scroll-to-top flottant (bas gauche, au-dessus de la bottom bar)
        AnimatedVisibility(
            visible = showScrollTop,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = 20.dp,
                    top = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 88.dp,
                ),
        ) {
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { listState.animateScrollToItem(0) }
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                shadowElevation = 4.dp,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "Retour en haut",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // 2. Header fixe en overlay avec effet gradient transparent
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        ),
                        startY = 0f,
                        endY = 300f
                    )
                )
                // LOP-49 : top padding dynamique = status bar + marge
                .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            androidx.compose.foundation.shape.RoundedCornerShape(50),
                        )
                        .clickableNoRipple { isMonthPickerOpen = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = "Changer de mois",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        Format.monthYear(state.month),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                // Boutons actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // LOP-51 : chip mois courant → ouvre le MonthPickerBottomSheet

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                androidx.compose.foundation.shape.RoundedCornerShape(50)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("€ 1,16", style = MaterialTheme.typography.titleSmall)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Wallet,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(20.dp)
                                .clickableNoRipple {
                                    navController.navigate(
                                        Routes.SETTINGS
                                    )
                                },

//
                        )
                    }
                }
            }
        }
    }
}
