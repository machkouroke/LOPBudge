# 🧪 Rapport de Test : BalanceEngineTest

**Date** : 31/07/2026 22:40:10
**Package** : `com.lop.budget.domain`
**Durée totale** : 68ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `calculateTotalBalance should only include accounts with includeInTotal set to true` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:40:10.902] Début du test : calculateTotalBalance should only include accounts with includeInTotal set to true
[22:40:10.904] Vérification du calcul du solde total consolidé
[22:40:10.938] Total obtenu : 1000.0 (A2 doit être ignoré)
[22:40:10.938] Test réussi
```

---

### `TC1 - calculateBalances should sum paid income and subtract paid expenses` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:40:10.944] Début du test : TC1 - calculateBalances should sum paid income and subtract paid expenses
[22:40:10.944] TC1 : Calcul cumulatif simple
[22:40:10.949] Solde calculé pour le compte A1 : 1300.0, Attendu: 1300
[22:40:10.950] Test réussi
```

---

### `TC3 - calculateBalances should ignore planned transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:40:10.951] Début du test : TC3 - calculateBalances should ignore planned transactions
[22:40:10.951] TC3 : Exclusion des transactions PLANNED
[22:40:10.951] Solde calculé pour le compte A1 : 1000.0, Attendu: 1000
[22:40:10.952] Test réussi
```

---

### `TC2 - calculateBalances should include adjustment transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:40:10.952] Début du test : TC2 - calculateBalances should include adjustment transactions
[22:40:10.953] TC2 : Prise en compte des ajustements
[22:40:10.953] Solde calculé pour le compte A1 : 1300.0, Attendu: 1300
[22:40:10.953] Test réussi
```

---

