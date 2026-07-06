# LOP-41 — Transactions : payé/non payé + swipe actions + undo

## Spécifications (ticket Notion a4dcb0cf)

### Gestes
- **Swipe droite** : toggle PAID/PLANNED (si PLANNED → PAID, si PAID → PLANNED)
- **Swipe gauche** : suppression avec garde-fou

### Indicateur visuel
- Case cochée verte sur la ligne
- Opacité réduite pour les transactions PAID
- Fond vert (swipe droite) / rouge (swipe gauche) pendant le swipe

### Undo
- Snackbar native Android après chaque action
- Délai 3-5s puis disparition
- Restaure l'état précédent (ou restaure la transaction supprimée)

### Fichiers concernés (selon ticket)
- ui/components/SwipeableTransactionRow.kt (NOUVEAU)
- ui/screens/home/HomeScreen.kt
- ui/screens/home/HomeViewModel.kt
- ui/screens/monthly/MonthlyTransactionsScreen.kt
- ui/screens/monthly/MonthlyTransactionsViewModel.kt
- ui/navigation/LopNavHost.kt
- data/local/dao/TransactionDao.kt
- data/repository/BudgetRepository.kt

## Modèle existant
- `TransactionStatus` : enum { PLANNED, PAID }
- `TransactionEntity.status` : champ existant
- `TransactionDao.updateStatus(id, status)` : méthode existante
- `BudgetRepository.setStatus(id, status)` : méthode existante
- `BudgetRepository.deleteTransaction(id)` : méthode existante

## Structure HomeScreen
- LazyColumn global avec items plats (PERF FIX #2)
- Transactions dans `items(day.transactions, key = { "tx_${it.transaction.id}" })`
- Chaque transaction = `FloatingCard` avec `.clickableNoRipple`

## Structure MonthlyTransactionsScreen
- `items(state.transactions, key = { it.transaction.id })`
- Chaque transaction = `FloatingCard` avec `.clickableNoRipple`

## Approche technique
- `SwipeToDismissBox` (Material3) pour les gestes
- `SnackbarHostState` dans LopNavHost (déjà présent ?)
- Soft-delete pour undo suppression : garder l'item en mémoire dans le ViewModel
- `toggleStatus()` dans HomeViewModel et MonthlyTransactionsViewModel
- `deleteWithUndo()` dans les ViewModels
