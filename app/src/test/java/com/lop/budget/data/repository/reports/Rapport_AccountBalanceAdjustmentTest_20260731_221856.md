# 🧪 Rapport de Test : AccountBalanceAdjustmentTest

**Date** : 31/07/2026 22:18:56
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 2890ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `TC5 - adjustAccountBalance should create INCOME adjustment when target is higher` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:18:55.516] Début du test : TC5 - adjustAccountBalance should create INCOME adjustment when target is higher
[22:18:55.590] TC5 : Création d'un ajustement de type REVENU
[22:18:56.115] Test réussi
```

---

### `TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:18:56.122] Début du test : TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower
[22:18:56.125] TC6 : Création d'un ajustement de type DÉPENSE
[22:18:56.137] Test réussi
```

---

### `TC7 - adjustAccountBalance should create nothing when target equals current` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:18:56.138] Début du test : TC7 - adjustAccountBalance should create nothing when target equals current
[22:18:56.141] TC7 : Aucun ajustement si le solde ne change pas
[22:18:56.152] Test réussi
```

---

### `TC8 - observeBusinessTransactions should filter out adjustment transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:18:56.155] Début du test : TC8 - observeBusinessTransactions should filter out adjustment transactions
[22:18:56.157] TC8 : Filtrage des ajustements dans la vue business
[22:18:56.166] Test réussi
```

---

