# 🧪 Rapport de Test : AccountBalanceAdjustmentTest

**Date** : 31/07/2026 22:58:50
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 2301ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `TC5 - adjustAccountBalance should create INCOME adjustment when target is higher` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:58:49.987] Début du test : TC5 - adjustAccountBalance should create INCOME adjustment when target is higher
[22:58:50.053] TC5 : Création d'un ajustement de type REVENU
[22:58:50.456] État initial : Solde actuel = 1000.0
[22:58:50.463] Action : Ajustement vers un solde cible de 1200.0
[22:58:50.558] Vérification : Une transaction technique de +200.0 a été créée
[22:58:50.563] Transaction réellement créée: [montant=200.0 , type=INCOME , kind=BALANCE_ADJUSTMENT
[22:58:50.563] Test réussi
```

---

### `TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:58:50.569] Début du test : TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower
[22:58:50.572] TC6 : Création d'un ajustement de type DÉPENSE
[22:58:50.576] État initial : Solde actuel = 1000.0
[22:58:50.577] Action : Ajustement vers un solde cible de 850.0
[22:58:50.580] Vérification : Une transaction technique de -150.0 a été créée
[22:58:50.580] Transaction réellement créée: [montant=150.0 , type=EXPENSE , kind=BALANCE_ADJUSTMENT
[22:58:50.580] Test réussi
```

---

### `TC7 - adjustAccountBalance should create nothing when target equals current` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:58:50.582] Début du test : TC7 - adjustAccountBalance should create nothing when target equals current
[22:58:50.584] TC7 : Aucun ajustement si le solde ne change pas
[22:58:50.591] Action : Ajustement vers 1000.0 (déjà la valeur actuelle)
[22:58:50.595] Vérification : Aucune transaction n'a été générée
[22:58:50.595] Test réussi
```

---

### `TC8 - observeBusinessTransactions should filter out adjustment transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[22:58:50.596] Début du test : TC8 - observeBusinessTransactions should filter out adjustment transactions
[22:58:50.598] TC8 : Filtrage des ajustements dans la vue business
[22:58:50.601] Action : Observation des transactions via la vue 'Business'
[22:58:50.607] Vérification : Seule la transaction STANDARD est conservée (1 trouvée)
[22:58:50.607] Test réussi
```

---

