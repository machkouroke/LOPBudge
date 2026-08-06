# 🧪 Rapport de Test : BalanceEngineTest

**Date** : 01/08/2026 00:42:02
**Package** : `com.lop.budget.domain`
**Durée totale** : 105ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `calculateTotalBalance should only include accounts with includeInTotal set to true` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:42:02.863] Début du test : calculateTotalBalance should only include accounts with includeInTotal set to true
[00:42:02.877] Vérification du calcul du solde total consolidé
[00:42:02.926] Total obtenu : 1000.0 (A2 doit être ignoré), Attendu: 1000.0
[00:42:02.926] Test réussi
```

---

### `TC1 - calculateBalances should sum paid income and subtract paid expenses` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:42:02.933] Début du test : TC1 - calculateBalances should sum paid income and subtract paid expenses
[00:42:02.933] TC1 : Calcul cumulatif simple
[00:42:02.939] Solde calculé pour le compte A1 : 1300.0, Attendu: 1300
[00:42:02.939] Test réussi
```

---

### `TC3 - calculateBalances should ignore planned transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:42:02.941] Début du test : TC3 - calculateBalances should ignore planned transactions
[00:42:02.941] TC3 : Exclusion des transactions PLANNED
[00:42:02.942] Solde calculé pour le compte A1 : 1000.0, Attendu: 1000
[00:42:02.942] Test réussi
```

---

### `TC2 - calculateBalances should include adjustment transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:42:02.943] Début du test : TC2 - calculateBalances should include adjustment transactions
[00:42:02.944] TC2 : Prise en compte des ajustements
[00:42:02.944] Solde calculé pour le compte A1 : 1300.0, Attendu: 1300
[00:42:02.945] Test réussi
```

---

