# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 01/08/2026 00:02:18
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 3536ms

## 📋 Résumé

- **Total** : 5
- **Succès** : 4
- **Échecs** : 1

## 🔍 Détails par Test

### `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:02:18.207] Début du test : TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range
[00:02:18.687] TC_REC_01 : Test de génération sur 3 mois pour une série infinie
[00:02:18.826] Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)
[00:02:18.827] Test réussi
```

---

### `TC_REC_02 - should not generate occurrences after series endDate` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:02:18.836] Début du test : TC_REC_02 - should not generate occurrences after series endDate
[00:02:18.841] TC_REC_02 : Respect de la date de fin (endDate)
[00:02:18.851] Vérification : La série s'arrête en Juin, donc 0 en Juillet
[00:02:18.852] Test réussi
```

---

### `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée

#### 📝 Logs du test
```text
[00:02:18.853] Début du test : TC_REC_04 - a deleted exception must hide the virtual occurrence entirely
[00:02:18.858] TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'
[00:02:18.867] Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted')
[00:02:18.868] ERREUR : L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée
```

---

### `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:02:18.878] Début du test : TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)
[00:02:18.885] TC_REC_03 : Une exception réelle en base remplace le virtuel
[00:02:18.894] Vérification : on doit voir l'ID 500 (30€), et PAS l'occurrence virtuelle (20€)
[00:02:18.895] Test réussi
```

---

### `TC_REC_06 - should stop generating after maxOccurrences is reached` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[00:02:18.897] Début du test : TC_REC_06 - should stop generating after maxOccurrences is reached
[00:02:18.902] TC_REC_06 : Respect de la limite maxOccurrences
[00:02:18.912] Vérification : 0 occurrence en Juillet car limite de 2 atteinte en Février
[00:02:18.912] Test réussi
```

---

