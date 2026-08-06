# 🧪 Rapport de Test : AccountBalanceAdjustmentTest

**Date** : 02/08/2026 18:24:30
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 1684ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `TC5 - adjustAccountBalance should create INCOME adjustment when target is higher` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:24:30.395] Début du test : TC5 - adjustAccountBalance should create INCOME adjustment when target is higher
[18:24:30.433] TC5 : Création d'un ajustement de type REVENU
[18:24:30.738] État initial : Solde actuel = 1000.0
[18:24:30.741] Action : Ajustement vers un solde cible de 1200.0
[18:24:30.791] Vérification : Une transaction technique de +200.0 a été créée
[18:24:30.801] Transaction réellement créée: [montant=200.0 , type=INCOME , kind=BALANCE_ADJUSTMENT
[18:24:30.802] Test réussi
```

---

### `TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:24:30.806] Début du test : TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower
[18:24:30.807] TC6 : Création d'un ajustement de type DÉPENSE
[18:24:30.811] État initial : Solde actuel = 1000.0
[18:24:30.811] Action : Ajustement vers un solde cible de 850.0
[18:24:30.813] Vérification : Une transaction technique de -150.0 a été créée
[18:24:30.814] Transaction réellement créée: [montant=150.0 , type=EXPENSE , kind=BALANCE_ADJUSTMENT
[18:24:30.814] Test réussi
```

---

### `TC7 - adjustAccountBalance should create nothing when target equals current` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:24:30.815] Début du test : TC7 - adjustAccountBalance should create nothing when target equals current
[18:24:30.816] TC7 : Aucun ajustement si le solde ne change pas
[18:24:30.819] Action : Ajustement vers 1000.0 (déjà la valeur actuelle)
[18:24:30.822] Vérification : Aucune transaction n'a été générée
[18:24:30.822] Test réussi
```

---

### `TC8 - observeBusinessTransactions should filter out adjustment transactions` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:24:30.824] Début du test : TC8 - observeBusinessTransactions should filter out adjustment transactions
[18:24:30.825] TC8 : Filtrage des ajustements dans la vue business
[18:24:30.827] Action : Observation des transactions via la vue 'Business'
[18:24:30.829] Vérification : Seule la transaction STANDARD est conservée (1 trouvée)
[18:24:30.830] Test réussi
```

---

