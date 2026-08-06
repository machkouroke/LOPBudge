# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 03/08/2026 13:42:15
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 2375ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[13:42:14.863] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[13:42:15.191] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[13:42:15.287] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[13:42:15.288] Dates réellement générées : [2026-07-05, 2026-08-05, 2026-09-05]
[13:42:15.289] IDs générés (doivent être < 0 et uniques) : [-289070818, -1333308952, -163524452]
[13:42:15.290] Test réussi
```

---

### `TC_REC_02 - should respect series startDate and endDate strictly` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[13:42:15.302] Début du test : TC_REC_02 - should respect series startDate and endDate strictly
[13:42:15.307] TC_REC_02 : Respect strict des bornes startDate et endDate
[13:42:15.312] Action 1 : Demande des transactions pour Février 2026 (Avant startDate)
[13:42:15.319] Obtenu : 0 transactions
[13:42:15.320] Action 2 : Demande des transactions de Mars à Mai 2026 (Période active)
[13:42:15.324] Dates générées : [2026-03-10, 2026-04-10, 2026-05-10] (Attendu: 10/03, 10/04, 10/05)
[13:42:15.325] Action 3 : Demande des transactions pour Juin 2026 (Après endDate)
[13:42:15.329] Obtenu : 0 transactions
[13:42:15.329] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[13:42:15.331] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[13:42:15.336] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[13:42:15.340] Action : Observation avec une exception 'deleted' présente en base
[13:42:15.343] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : 0 items.
[13:42:15.343] Test réussi
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[13:42:15.345] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[13:42:15.350] TC_REC_03 : Une exception réelle en base remplace le virtuel
[13:42:15.359] Action 1 : Observation de Juillet (Période avec exception)
[13:42:15.371] Valeurs RÉELLES en Juillet : [id=500, titre='Sport (Séance longue Juillet)', montant=30.0, isException=true]
[13:42:15.372] Vérification Juillet : On doit voir l'exception de 30€ (ID 500)
[13:42:15.373] Action 2 : Observation d'Août (Période sans exception)
[13:42:15.386] Valeurs RÉELLES en Août : [id=-1530460901, titre='Sport', montant=20.0, isException=false]
[13:42:15.387] Vérification Août : On doit voir le virtuel original de 20€ (ID < 0)
[13:42:15.387] Test réussi
```

---

