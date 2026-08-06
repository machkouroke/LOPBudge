# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 01/08/2026 00:25:35
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 2805ms

## 📋 Résumé

- **Total** : 5
- **Succès** : 4
- **Échecs** : 1

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:25:34.382] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[00:25:34.791] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[00:25:34.909] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[00:25:34.911] Dates réellement générées : [2026-07-05, 2026-08-05, 2026-09-05]
[00:25:34.911] IDs générés (doivent être < 0 et uniques) : [-289070818, -1333308952, -163524452]
[00:25:34.912] Test réussi
```

---

### `TC_REC_02 - should respect series startDate and endDate strictly` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:25:34.918] Début du test : TC_REC_02 - should respect series startDate and endDate strictly
[00:25:34.923] TC_REC_02 : Respect strict des bornes startDate et endDate
[00:25:34.928] Action 1 : Demande des transactions pour Février 2026 (Avant startDate)
[00:25:34.934] Obtenu : 0 transactions
[00:25:34.934] Action 2 : Demande des transactions de Mars à Mai 2026 (Période active)
[00:25:34.939] Dates générées : [2026-03-10, 2026-04-10, 2026-05-10] (Attendu: 10/03, 10/04, 10/05)
[00:25:34.939] Action 3 : Demande des transactions pour Juin 2026 (Après endDate)
[00:25:34.942] Obtenu : 0 transactions
[00:25:34.942] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée

#### 📝 Logs du test
```text
[00:25:34.944] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[00:25:34.949] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[00:25:34.954] Action : Observation avec une exception 'deleted' présente en base
[00:25:34.956] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : 1 items.
[00:25:34.961] ALERTE : Une occurrence a fuité ! [id=600, isException=true, seriesDate=1784066400000]
[00:25:34.962] ERREUR : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:25:34.970] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[00:25:34.973] TC_REC_03 : Une exception réelle en base remplace le virtuel
[00:25:34.983] Action 1 : Observation de Juillet (Période avec exception)
[00:25:34.985] Vérification Juillet : On doit voir l'exception de 30€ (ID 500)
[00:25:34.986] Action 2 : Observation d'Août (Période sans exception)
[00:25:34.987] Vérification Août : On doit voir le virtuel original de 20€ (ID < 0)
[00:25:34.988] Test réussi
```

---

### `TC_REC_06 - should stop generating after maxOccurrences is reached` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:25:34.989] Début du test : TC_REC_06 - should stop generating after maxOccurrences is reached
[00:25:34.993] TC_REC_06 : Respect de la limite maxOccurrences
[00:25:34.998] Action : Demande de Juillet 2026 pour une série limitée à 2 occurrences (Janvier/Février)
[00:25:35.000] Vérification : 0 occurrence attendue en Juillet. Obtenu : 0
[00:25:35.001] Test réussi
```

---

