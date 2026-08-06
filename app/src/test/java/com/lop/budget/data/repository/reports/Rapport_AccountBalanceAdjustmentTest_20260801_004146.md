# 🧪 Rapport de Test : AccountBalanceAdjustmentTest

**Date** : 01/08/2026 00:41:46
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 3407ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `TC5 - adjustAccountBalance should create INCOME adjustment when target is higher` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:45.731] Début du test : TC5 - adjustAccountBalance should create INCOME adjustment when target is higher
[00:41:45.817] TC5 : Création d'un ajustement de type REVENU
[00:41:46.750] État initial : Solde actuel = 1000.0
[00:41:46.755] Action : Ajustement vers un solde cible de 1200.0
[00:41:46.843] Vérification : Une transaction technique de +200.0 a été créée
[00:41:46.848] Transaction réellement créée: [montant=200.0 , type=INCOME , kind=BALANCE_ADJUSTMENT
[00:41:46.849] Test réussi
```

---

### `TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:46.857] Début du test : TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower
[00:41:46.859] TC6 : Création d'un ajustement de type DÉPENSE
[00:41:46.866] État initial : Solde actuel = 1000.0
[00:41:46.866] Action : Ajustement vers un solde cible de 850.0
[00:41:46.872] Vérification : Une transaction technique de -150.0 a été créée
[00:41:46.872] Transaction réellement créée: [montant=150.0 , type=EXPENSE , kind=BALANCE_ADJUSTMENT
[00:41:46.873] Test réussi
```

---

### `TC7 - adjustAccountBalance should create nothing when target equals current` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:46.874] Début du test : TC7 - adjustAccountBalance should create nothing when target equals current
[00:41:46.876] TC7 : Aucun ajustement si le solde ne change pas
[00:41:46.882] Action : Ajustement vers 1000.0 (déjà la valeur actuelle)
[00:41:46.887] Vérification : Aucune transaction n'a été générée
[00:41:46.888] Test réussi
```

---

### `TC8 - observeBusinessTransactions should filter out adjustment transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:46.889] Début du test : TC8 - observeBusinessTransactions should filter out adjustment transactions
[00:41:46.892] TC8 : Filtrage des ajustements dans la vue business
[00:41:46.894] Action : Observation des transactions via la vue 'Business'
[00:41:46.902] Vérification : Seule la transaction STANDARD est conservée (1 trouvée)
[00:41:46.902] Test réussi
```

---

