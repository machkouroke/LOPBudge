# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 01/08/2026 00:38:37
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 3160ms

## 📋 Résumé

- **Total** : 5
- **Succès** : 0
- **Échecs** : 5

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : Expected at least one element

#### 📝 Logs du test
```text
[00:38:36.646] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[00:38:37.089] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[00:38:37.207] ERREUR : Expected at least one element
```

---

### `TC_REC_02 - should respect series startDate and endDate strictly` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : Expected at least one element

#### 📝 Logs du test
```text
[00:38:37.219] Début du test : TC_REC_02 - should respect series startDate and endDate strictly
[00:38:37.223] TC_REC_02 : Respect strict des bornes startDate et endDate
[00:38:37.232] Action 1 : Demande des transactions pour Février 2026 (Avant startDate)
[00:38:37.236] ERREUR : Expected at least one element
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : Expected at least one element

#### 📝 Logs du test
```text
[00:38:37.239] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[00:38:37.244] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[00:38:37.258] Action : Observation avec une exception 'deleted' présente en base
[00:38:37.263] ERREUR : Expected at least one element
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : Expected at least one element

#### 📝 Logs du test
```text
[00:38:37.266] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[00:38:37.271] TC_REC_03 : Une exception réelle en base remplace le virtuel
[00:38:37.285] Action 1 : Observation de Juillet (Période avec exception)
[00:38:37.289] ERREUR : Expected at least one element
```

---

### `TC_REC_06 - should stop generating after maxOccurrences is reached` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : Expected at least one element

#### 📝 Logs du test
```text
[00:38:37.291] Début du test : TC_REC_06 - should stop generating after maxOccurrences is reached
[00:38:37.298] TC_REC_06 : Respect de la limite maxOccurrences
[00:38:37.308] Action : Demande de Juillet 2026 pour une série limitée à 2 occurrences (Janvier/Février)
[00:38:37.313] ERREUR : Expected at least one element
```

---

