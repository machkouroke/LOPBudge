# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 01/08/2026 00:13:24
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 4242ms

## 📋 Résumé

- **Total** : 5
- **Succès** : 4
- **Échecs** : 1

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:13:23.891] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[00:13:24.331] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[00:13:24.489] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[00:13:24.490] Dates réellement générées : [2026-07-05, 2026-08-05, 2026-09-05]
[00:13:24.492] IDs générés (doivent être < 0 et uniques) : [-289070818, -1333308952, -163524452]
[00:13:24.492] Test réussi
```

---

### `TC_REC_02 - should not generate occurrences after series endDate` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:13:24.500] Début du test : TC_REC_02 - should not generate occurrences after series endDate
[00:13:24.505] TC_REC_02 : Respect de la date de fin (endDate)
[00:13:24.513] Action : Demande des transactions pour Juillet 2026 (Série finie en Juin)
[00:13:24.519] Vérification : La série s'arrête en Juin, donc 0 attendu. Obtenu : 0
[00:13:24.520] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée

#### 📝 Logs du test
```text
[00:13:24.521] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[00:13:24.527] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[00:13:24.535] Action : Observation avec une exception 'deleted' présente en base
[00:13:24.539] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : 1 items.
[00:13:24.547] ALERTE : Une occurrence a fuité ! [id=600, isException=true, seriesDate=1784066400000]
[00:13:24.548] ERREUR : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:13:24.559] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[00:13:24.564] TC_REC_03 : Une exception réelle en base remplace le virtuel
[00:13:24.572] Action : Observation entre 1782856800000 et 1785535199000
[00:13:24.576] Vérification : on doit voir l'ID 500 (30€), et PAS l'occurrence virtuelle (20€)
[00:13:24.583] Transaction finale trouvée : [id=500, amount=30.0, isException=true]
[00:13:24.584] Test réussi
```

---

### `TC_REC_06 - should stop generating after maxOccurrences is reached` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:13:24.585] Début du test : TC_REC_06 - should stop generating after maxOccurrences is reached
[00:13:24.592] TC_REC_06 : Respect de la limite maxOccurrences
[00:13:24.602] Action : Demande de Juillet 2026 pour une série limitée à 2 occurrences (Janvier/Février)
[00:13:24.606] Vérification : 0 occurrence attendue en Juillet. Obtenu : 0
[00:13:24.607] Test réussi
```

---

