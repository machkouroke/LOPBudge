# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 01/08/2026 00:41:27
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 2882ms

## 📋 Résumé

- **Total** : 5
- **Succès** : 5
- **Échecs** : 0

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:26.416] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[00:41:26.925] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[00:41:27.036] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[00:41:27.037] Dates réellement générées : [2026-07-05, 2026-08-05, 2026-09-05]
[00:41:27.037] IDs générés (doivent être < 0 et uniques) : [-289070818, -1333308952, -163524452]
[00:41:27.038] Test réussi
```

---

### `TC_REC_02 - should respect series startDate and endDate strictly` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:27.045] Début du test : TC_REC_02 - should respect series startDate and endDate strictly
[00:41:27.049] TC_REC_02 : Respect strict des bornes startDate et endDate
[00:41:27.055] Action 1 : Demande des transactions pour Février 2026 (Avant startDate)
[00:41:27.061] Obtenu : 0 transactions
[00:41:27.061] Action 2 : Demande des transactions de Mars à Mai 2026 (Période active)
[00:41:27.066] Dates générées : [2026-03-10, 2026-04-10, 2026-05-10] (Attendu: 10/03, 10/04, 10/05)
[00:41:27.066] Action 3 : Demande des transactions pour Juin 2026 (Après endDate)
[00:41:27.068] Obtenu : 0 transactions
[00:41:27.069] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:27.070] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[00:41:27.075] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[00:41:27.082] Action : Observation avec une exception 'deleted' présente en base
[00:41:27.084] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : 0 items.
[00:41:27.084] Test réussi
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:27.086] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[00:41:27.091] TC_REC_03 : Une exception réelle en base remplace le virtuel
[00:41:27.101] Action 1 : Observation de Juillet (Période avec exception)
[00:41:27.113] Valeurs RÉELLES en Juillet : [id=500, titre='Sport (Séance longue Juillet)', montant=30.0, isException=true]
[00:41:27.114] Vérification Juillet : On doit voir l'exception de 30€ (ID 500)
[00:41:27.114] Action 2 : Observation d'Août (Période sans exception)
[00:41:27.116] Valeurs RÉELLES en Août : [id=-1530460901, titre='Sport', montant=20.0, isException=false]
[00:41:27.117] Vérification Août : On doit voir le virtuel original de 20€ (ID < 0)
[00:41:27.117] Test réussi
```

---

### `TC_REC_06 - should stop generating after maxOccurrences is reached` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:41:27.119] Début du test : TC_REC_06 - should stop generating after maxOccurrences is reached
[00:41:27.123] TC_REC_06 : Respect de la limite maxOccurrences
[00:41:27.129] Action : Demande de Juillet 2026 pour une série limitée à 2 occurrences (Janvier/Février)
[00:41:27.132] Vérification : 0 occurrence attendue en Juillet. Obtenu : 0
[00:41:27.132] Test réussi
```

---

