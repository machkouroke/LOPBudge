# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 01/08/2026 00:16:47
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 2612ms

## 📋 Résumé

- **Total** : 5
- **Succès** : 4
- **Échecs** : 1

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:16:46.814] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[00:16:47.207] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[00:16:47.321] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[00:16:47.321] Dates réellement générées : [2026-07-05, 2026-08-05, 2026-09-05]
[00:16:47.323] IDs générés (doivent être < 0 et uniques) : [-289070818, -1333308952, -163524452]
[00:16:47.323] Test réussi
```

---

### `TC_REC_02 - should respect series startDate and endDate strictly` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:16:47.330] Début du test : TC_REC_02 - should respect series startDate and endDate strictly
[00:16:47.334] TC_REC_02 : Respect strict des bornes startDate et endDate
[00:16:47.340] Action 1 : Demande des transactions pour Février 2026 (Avant startDate)
[00:16:47.348] Obtenu : 0 transactions
[00:16:47.348] Action 2 : Demande des transactions de Mars à Mai 2026 (Période active)
[00:16:47.352] Dates générées : [2026-03-10, 2026-04-10, 2026-05-10] (Attendu: 10/03, 10/04, 10/05)
[00:16:47.353] Action 3 : Demande des transactions pour Juin 2026 (Après endDate)
[00:16:47.354] Obtenu : 0 transactions
[00:16:47.355] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée

#### 📝 Logs du test
```text
[00:16:47.356] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[00:16:47.361] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[00:16:47.366] Action : Observation avec une exception 'deleted' présente en base
[00:16:47.368] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : 1 items.
[00:16:47.376] ALERTE : Une occurrence a fuité ! [id=600, isException=true, seriesDate=1784066400000]
[00:16:47.377] ERREUR : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:16:47.385] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[00:16:47.390] TC_REC_03 : Une exception réelle en base remplace le virtuel
[00:16:47.397] Action : Observation entre 1782856800000 et 1785535199000
[00:16:47.398] Vérification : on doit voir l'ID 500 (30€), et PAS l'occurrence virtuelle (20€)
[00:16:47.405] Transaction finale trouvée : [id=500, amount=30.0, isException=true]
[00:16:47.406] Test réussi
```

---

### `TC_REC_06 - should stop generating after maxOccurrences is reached` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:16:47.410] Début du test : TC_REC_06 - should stop generating after maxOccurrences is reached
[00:16:47.416] TC_REC_06 : Respect de la limite maxOccurrences
[00:16:47.423] Action : Demande de Juillet 2026 pour une série limitée à 2 occurrences (Janvier/Février)
[00:16:47.425] Vérification : 0 occurrence attendue en Juillet. Obtenu : 0
[00:16:47.426] Test réussi
```

---

