# Walkthrough - Résolution du Problème de Détection (Rechercher)

J'ai identifié et corrigé la raison pour laquelle Maestro ne trouvait pas le bouton "Rechercher" sur l'émulateur.

## Analyse du Problème

- **La Cause** : Bien que le bouton ait une description "Rechercher", Maestro peinait à le faire correspondre uniquement par texte sur l'émulateur (probablement dû à une différence de rendu ou de focus par rapport à votre smartphone).
- **Le Bouton** : Dans le code (`HomeScreen.kt`), ce bouton possède un **testTag** dédié nommé `nav_search`.

## Solution Appliquée

J'ai modifié le fichier de test [Suppression.yaml](file:///C:/Users/machk.GALAXYBOOKPRO.000/Downloads/LOPBudge/LOPBudge/Maestro/Suppression.yaml) pour utiliser l'identifiant technique plutôt que le texte :

```diff
- - tapOn: "Rechercher"
+ - tapOn:
+     id: "nav_search"
```

**Pourquoi c'est mieux ?**
L'identifiant (`id`) est une cible "invisible" et immuable dans le code. Contrairement au texte qui peut changer selon la langue ou la police, l'identifiant technique est le moyen le plus fiable et le plus rapide pour Maestro de trouver un élément, surtout sur un émulateur.

## Utilisation

Relancez simplement le script. Le test devrait maintenant cliquer instantanément sur la loupe de recherche :
```powershell
.\scripts\run_tests.ps1
```

---

## Résumé technique
- **Ciblage** : Passage de `text` à `id` (`testTag`).
- **Fiabilité** : Élimine les erreurs de détection liées à la reconnaissance de texte (regex).
- **Stabilité** : Moins sensible aux délais d'affichage de l'UI.
