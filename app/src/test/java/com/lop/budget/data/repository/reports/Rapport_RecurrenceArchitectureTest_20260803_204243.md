# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 03/08/2026 20:42:43
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 3749ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 4
- **Échecs** : 0

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[20:42:42.014] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[20:42:42.545] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[20:42:42.733] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[20:42:42.733] Dates réellement générées : [2026-07-05, 2026-08-05, 2026-09-05]
[20:42:42.735] IDs générés (doivent être < 0 et uniques) : [-289070818, -1333308952, -163524452]
[20:42:42.735] Test réussi
```

---

### `TC_REC_02 - should respect series startDate and endDate strictly` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[20:42:42.743] Début du test : TC_REC_02 - should respect series startDate and endDate strictly
[20:42:42.769] TC_REC_02 : Respect strict des bornes startDate et endDate
[20:42:42.814] Action 1 : Demande des transactions pour Février 2026 (Avant startDate)
[20:42:42.826] Obtenu : 0 transactions
[20:42:42.826] Action 2 : Demande des transactions de Mars à Mai 2026 (Période active)
[20:42:42.833] Dates générées : [2026-03-10, 2026-04-10, 2026-05-10] (Attendu: 10/03, 10/04, 10/05)
[20:42:42.833] Action 3 : Demande des transactions pour Juin 2026 (Après endDate)
[20:42:42.843] Obtenu : 0 transactions
[20:42:42.843] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[20:42:42.845] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[20:42:42.875] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[20:42:42.922] Action : Observation avec une exception 'deleted' présente en base
[20:42:42.932] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : 0 items.
[20:42:42.932] Test réussi
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[20:42:42.934] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[20:42:42.964] TC_REC_03 : Une exception réelle en base remplace le virtuel
[20:42:43.013] Action 1 : Observation de Juillet (Période avec exception)
[20:42:43.025] Valeurs RÉELLES en Juillet : [id=500, titre='Sport (Séance longue Juillet)', montant=30.0, isException=true]
[20:42:43.025] Vérification Juillet : On doit voir l'exception de 30€ (ID 500)
[20:42:43.027] Action 2 : Observation d'Août (Période sans exception)
[20:42:43.029] Valeurs RÉELLES en Août : [id=-1530460901, titre='Sport', montant=20.0, isException=false]
[20:42:43.031] Vérification Août : On doit voir le virtuel original de 20€ (ID < 0)
[20:42:43.033] Test réussi
```

---

