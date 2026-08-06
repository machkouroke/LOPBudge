# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 02/08/2026 18:55:14
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 1553ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:55:13.784] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[18:55:14.002] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[18:55:14.063] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[18:55:14.064] Dates réellement générées : [2026-07-05, 2026-08-05, 2026-09-05]
[18:55:14.064] IDs générés (doivent être < 0 et uniques) : [-289070818, -1333308952, -163524452]
[18:55:14.065] Test réussi
```

---

### `TC_REC_02 - should respect series startDate and endDate strictly` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:55:14.071] Début du test : TC_REC_02 - should respect series startDate and endDate strictly
[18:55:14.078] TC_REC_02 : Respect strict des bornes startDate et endDate
[18:55:14.081] Action 1 : Demande des transactions pour Février 2026 (Avant startDate)
[18:55:14.088] Obtenu : 0 transactions
[18:55:14.088] Action 2 : Demande des transactions de Mars à Mai 2026 (Période active)
[18:55:14.092] Dates générées : [2026-03-10, 2026-04-10, 2026-05-10] (Attendu: 10/03, 10/04, 10/05)
[18:55:14.093] Action 3 : Demande des transactions pour Juin 2026 (Après endDate)
[18:55:14.096] Obtenu : 0 transactions
[18:55:14.096] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:55:14.098] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[18:55:14.103] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[18:55:14.106] Action : Observation avec une exception 'deleted' présente en base
[18:55:14.111] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : 0 items.
[18:55:14.112] Test réussi
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[18:55:14.114] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[18:55:14.118] TC_REC_03 : Une exception réelle en base remplace le virtuel
[18:55:14.123] Action 1 : Observation de Juillet (Période avec exception)
[18:55:14.132] Valeurs RÉELLES en Juillet : [id=500, titre='Sport (Séance longue Juillet)', montant=30.0, isException=true]
[18:55:14.132] Vérification Juillet : On doit voir l'exception de 30€ (ID 500)
[18:55:14.133] Action 2 : Observation d'Août (Période sans exception)
[18:55:14.135] Valeurs RÉELLES en Août : [id=-1530460901, titre='Sport', montant=20.0, isException=false]
[18:55:14.135] Vérification Août : On doit voir le virtuel original de 20€ (ID < 0)
[18:55:14.136] Test réussi
```

---

