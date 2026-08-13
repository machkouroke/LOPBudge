# 🧪 Implémentation des tests Maestro et Correction du bug de récurrence

Ce document résume l'implémentation du cas de test **TC-40** et la correction du bug provoquant la disparition de l'icône de récurrence lors du marquage payé.

## 📂 Tests Maestro créés
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-1_Swipe_Droite.yaml`
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-2_Popup_Long_Press.yaml`
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-3_Ecran_Detail.yaml`
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-4_Menu_Edition.yaml`

## 🛠️ Correction du bug : Disparition de l'icône de récurrence

### Problème
Lorsqu'une occurrence récurrente était marquée comme "payée" via une action rapide (swipe, popup), elle perdait son lien `seriesId`. Cela arrivait car `TransactionActionViewModel.confirmEdit` forçait par défaut une fréquence `NONE`, ce qui déclenchait une conversion en transaction ponctuelle dans le Repository.

### Solution appliquée
Modification de `TransactionActionViewModel.confirmEdit` :
- Les paramètres de récurrence sont désormais optionnels (`null`).
- S'ils ne sont pas fournis (cas des actions rapides), le ViewModel charge les valeurs existantes de la série parente avant de sauvegarder.
- Cela garantit que la transaction matérialisée conserve son `seriesId` et reste marquée comme une exception de la série (`isException = true`).

## 📊 Correspondance Gherkin → Maestro

| Étape Gherkin | Implémentation Maestro |
| :--- | :--- |
| `Given l’application est ouverte` | `runFlow: bootstrap_monthly.yaml` |
| `When l’utilisateur déclenche le marquage payé` | `swipe RIGHT`, `longPress` + `tap "Payer"`, etc. |
| `Then aucune bottom sheet de choix de portée...` | `assertNotVisible: { id: "recurring.edit.scope.sheet" }` |
| `And l’icône de série récurrente est visible` | `assertVisible: { accessibilityText: "Récurrent" }` |

## 🚀 Vérification
Le test Maestro `TC-40-1_Swipe_Droite.yaml` doit maintenant passer avec succès, confirmant que l'icône de récurrence reste visible après le marquage payé.
