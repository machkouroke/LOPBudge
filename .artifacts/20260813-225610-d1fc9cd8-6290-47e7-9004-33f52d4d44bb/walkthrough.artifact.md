# 🧪 Implémentation des tests Maestro et Corrections de bugs

Ce document résume l'implémentation du cas de test **TC-40** et les corrections apportées au moteur de récurrence et à l'écran d'édition.

## 📂 Tests Maestro créés
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-1_Swipe_Droite.yaml`
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-2_Popup_Long_Press.yaml`
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-3_Ecran_Detail.yaml`
- `Maestro/tests/TC-40_Marquage_Paye_Recurrent/TC-40-4_Menu_Edition.yaml`

## 🛠️ Corrections apportées

### 1. Conservation du lien de récurrence (Actions Rapides)
- **Problème** : Le marquage payé via swipe/popup détachait la transaction de sa série.
- **Cause** : `TransactionActionViewModel.confirmEdit` forçait une fréquence `NONE` par défaut.
- **Fix** : Le ViewModel charge désormais la règle de récurrence parente avant de sauvegarder.

### 2. Prise en compte du statut dans l'Écran d'Édition
- **Problème** : Le switch "Marquer comme payé" dans l'écran d'édition n'était pas persisté.
- **Cause** : `TransactionEditViewModel.performSave` oubliait de passer le champ `status` au repository.
- **Fix** : Ajout du paramètre `status = f.status` dans l'appel à `repo.saveWithTransition`.

## 📈 Améliorations des Tests Maestro
- **Scrolling bidirectionnel** : Ajout de `scrollUntilVisible` (UP/DOWN) pour garantir la visibilité des boutons d'édition et de statut.
- **Vérification multi-mois** : Validation que les occurrences des mois adjacents restent au statut "Planifié" et conservent leur icône de récurrence.
- **Sélecteurs robustes** : Utilisation de relations de position (`rightOf`) et nettoyage des fautes de frappe.

## 🚀 Vérification
Le test `TC-40-4_Menu_Edition.yaml` doit maintenant passer avec succès, confirmant que le statut togglé dans l'écran d'édition est bien sauvegardé.
