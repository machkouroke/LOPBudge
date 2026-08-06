# 🧪 Rapport de Test : RecurrenceArchitectureTest

**Date** : 31/07/2026 23:39:06
**Package** : `com.lop.budget.data.repository`
**Durée totale** : 2936ms

## 📋 Résumé

- **Total** : 4
- **Succès** : 3
- **Échecs** : 1

## 🔍 Détails par Test

### `TC_REC_01 - should generate virtual occurrences for active series` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[23:39:06.244] Début du test : TC_REC_01 - should generate virtual occurrences for active series
[23:39:06.697] TC_REC_01 : Génération d'occurrences virtuelles
[23:39:06.841] Vérification : Une seule occurrence attendue le 5 Juillet
[23:39:06.849] Données reçues : [id=-289070818, title=Abonnement Netflix, date=1783202400000]
[23:39:06.850] Test réussi
```

---

### `TC_REC_02 - should respect series endDate` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[23:39:06.859] Début du test : TC_REC_02 - should respect series endDate
[23:39:06.864] TC_REC_02 : Respect de la date de fin (endDate)
[23:39:06.872] Vérification : Aucune occurrence en Juillet car finie en Juin
[23:39:06.872] Test réussi
```

---

### `TC_REC_03 - real exception should replace virtual occurrence` - ✅ SUCCÈS
#### 📝 Logs du test
```text
[23:39:06.874] Début du test : TC_REC_03 - real exception should replace virtual occurrence
[23:39:06.879] TC_REC_03 : Une exception réelle remplace l'occurrence virtuelle
[23:39:06.886] Vérification : On doit trouver UNIQUEMENT l'exception (30€), pas le virtuel (20€)
[23:39:06.894] Trouvé : [id=500, title=Sport (Séance longue), amount=30.0]
[23:39:06.894] Test réussi
```

---

### `TC_REC_04 - deleted exception should hide the occurrence entirely` - ❌ ÉCHEC
> [!CAUTION]
> **Erreur** : La liste doit être vide car l'occurrence est supprimée

#### 📝 Logs du test
```text
[23:39:06.896] Début du test : TC_REC_04 - deleted exception should hide the occurrence entirely
[23:39:06.900] TC_REC_04 : Une exception supprimée masque l'occurrence virtuelle
[23:39:06.907] Vérification : L'occurrence de Juillet doit être absente
[23:39:06.907] ERREUR : La liste doit être vide car l'occurrence est supprimée
```

---

