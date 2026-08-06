# 🧪 Rapport de Test : BalanceEngineTest

**Date** : 02/08/2026 18:23:14
**Package** : `com.lop.budget.domain`
**Durée totale** : 50ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `calculateTotalBalance should only include accounts with includeInTotal set to true` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:23:14.280] Début du test : calculateTotalBalance should only include accounts with includeInTotal set to true
[18:23:14.282] Vérification du calcul du solde total consolidé
[18:23:14.306] Total obtenu : 1000.0 (A2 doit être ignoré), Attendu: 1000.0
[18:23:14.306] Test réussi
```

---

### `TC1 - calculateBalances should sum paid income and subtract paid expenses` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:23:14.310] Début du test : TC1 - calculateBalances should sum paid income and subtract paid expenses
[18:23:14.310] TC1 : Calcul cumulatif simple
[18:23:14.312] Solde calculé pour le compte A1 : 1300.0, Attendu: 1300
[18:23:14.312] Test réussi
```

---

### `TC3 - calculateBalances should ignore planned transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:23:14.313] Début du test : TC3 - calculateBalances should ignore planned transactions
[18:23:14.313] TC3 : Exclusion des transactions PLANNED
[18:23:14.313] Solde calculé pour le compte A1 : 1000.0, Attendu: 1000
[18:23:14.314] Test réussi
```

---

### `TC2 - calculateBalances should include adjustment transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:23:14.314] Début du test : TC2 - calculateBalances should include adjustment transactions
[18:23:14.314] TC2 : Prise en compte des ajustements
[18:23:14.315] Solde calculé pour le compte A1 : 1300.0, Attendu: 1300
[18:23:14.315] Test réussi
```

---

