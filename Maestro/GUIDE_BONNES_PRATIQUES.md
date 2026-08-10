# 🛠️ Guide des Bonnes Pratiques Maestro — LOPBudge

Ce guide définit les standards de qualité pour l'écriture des tests UI automatisés sur le projet.
L'objectif est d'assurer une suite de tests robuste, maintenable et compatible avec **Maestro Cloud**.

---

## 1. Rigueur Technique et Documentation

Il est strictement interdit d'utiliser des mots-clés ou des paramètres sans avoir préalablement
vérifié leur existence et leur syntaxe dans la [documentation officielle de Maestro](https://docs.maestro.dev/).

- **Règle** : Toujours se référer au `maestro cheat-sheet` ou à la doc officielle avant d'ajouter une commande.
- **Règle (Anti-Hallucination)** : Ne jamais inventer de paramètres (ex: `timeout` n'existe pas
  directement dans `assertVisible`). Utiliser `extendedWaitUntil` pour les attentes explicites.

## 2. Traçabilité et Logs

Chaque étape importante d'un scénario doit être tracée pour faciliter le débogage dans la console
Maestro (Local ou Cloud).

- **Règle** : Utiliser `evalScript` avec un bloc multi-ligne pour plus de robustesse syntaxique.

```yaml
- evalScript: |
    console.log("Étape 2 : Vérification via Détail")
```

## 3. Configuration et Identité du Script

Maestro Cloud nécessite de connaître le contexte de l'application pour chaque fichier YAML, y
compris les fragments appelés par d'autres scripts.

- **Règle** : Toujours inclure `appId: com.lop.budget` en haut de chaque fichier (Main Flow et
  Subflow).
- **Règle** : Séparer la configuration des étapes par `---`.

```yaml
appId: com.lop.budget
name: "Nom Descriptif du Test"
---
# Étapes ici...
```

## 4. Gestion des Variables (Scoping)

Lorsque des variables sont calculées ou injectées via un script JavaScript (`runScript`), elles
doivent être rendues visibles globalement.

- **Règle** : Préfixer systématiquement les variables par `output.` dans le code JS.

```javascript
// Dans un fichier .js
output.MONTH_NAME = "Janv.";
```

Usage dans le YAML : `text: ${output.MONTH_NAME}` (Maestro résout l'output automatiquement).

## 5. Stratégie de Sélection et Visibilité (Robustesse)

Pour éviter que les tests ne cassent lors d'un changement de design mineur.

- **Règle 1** : Privilégier la **sélection par texte unique** (`assertVisible: "Titre"`) pour valider l'ouverture d'un écran ou d'une modale.
- **Règle 2 (Robustesse IDs)** : Utiliser les **`id`** uniquement pour les éléments interactifs précis (boutons, champs) s'ils sont stables. Éviter les IDs sur les conteneurs globaux (`Scaffold`, `Box`) car Maestro a du mal à les détecter dans la hiérarchie Compose.
- **Règle 3 (Maîtrise du Scroll)** : **Ne jamais asserter un élément situé en bas de page sans avoir ordonné un scroll.** Utiliser `scrollUntilVisible` pour les assertions positives et `scroll` (simple) avant une assertion négative pour garantir que l'élément n'est pas juste "caché en bas".
- **Règle 4** : Si l'ID est insuffisant (doublons), utiliser les relations de position (`rightOf`, `below`, `above`, `leftOf`).
- **Règle 5** : Si un élément important n'a pas de tag, **le rajouter dans le code Kotlin** au lieu de bricoler un sélecteur fragile.

```yaml
# Exemple de scroll robuste
- scrollUntilVisible:
    element: { id: "transaction.edit.block.recurrence" }
    direction: DOWN
```

## 6. Vérification du Contexte (State Safety)

Maestro simule un humain ; il ne faut pas "cliquer à l'aveugle".

- **Règle** : Toujours vérifier que l'on se trouve sur le bon écran (via son ID `screen.*` ou son titre) avant d'interagir.

```yaml
# S'assurer d'être au bon endroit
- assertVisible: { id: "screen.monthly" }
```

## 7. Architecture DRY (Don't Repeat Yourself)

La logique de navigation ou d'action répétitive doit être isolée.

- **Règle** : Utiliser des **Subflows** dans le dossier `.maestro/subflows/`.
- **Règle** : Créer des subflows paramétrés (via `env`) pour les parcours communs (ex: `navigate_to_edit.yaml`).

```yaml
- runFlow:
    file: .maestro/subflows/navigate_to_edit.yaml
    env:
      transactionName: "Loyer"
      scopeSelection: "Cette occurrence uniquement"
```

## 8. Conformité aux Critères d'Acceptation (CA)

Un test ne sert pas juste à "cliquer partout", il doit prouver que l'exigence métier est remplie.

- **Règle** : Chaque assertion (`assertVisible`, `assertNotVisible`) doit correspondre à un point
  précis d'un **CA** défini sur Notion.
- **Règle** : Ne pas hésiter à vérifier qu'une donnée de contrôle (ex:Salaire) n'a **pas** bougé après une suppression.

## 9. Robustesse Temporelle

Les tests ne doivent pas dépendre de la date du jour "en dur".

- **Règle** : Utiliser le script `Maestro/scripts/dates.js` pour calculer les mois relatifs.
- **Règle** : Gérer les changements d'année via une logique de détection dans les subflows.

## 10. Intégrité des Données (JDD)

Les tests Maestro s'appuient sur un état stable garanti par le seeding de la base de données.

- **Règle** : Ne jamais deviner les valeurs de test. Toujours se référer au fichier [**`DatabaseSeeder.kt`**](file:///C:/Users/machk.GALAXYBOOKPRO.000/Downloads/LOPBudge/LOPBudge/app/src/main/java/com/lop/budget/data/seed/DatabaseSeeder.kt) pour utiliser les titres, montants et catégories exacts (ex: "Courses Hebdomadaires" et non "Courses").
- **Règle** : Utiliser `clearState: true` au lancement de chaque scénario critique pour repartir du JDD d'origine.
- **Règle (Vérification Exhaustive)** : Lors de la validation d'un formulaire de modification, vérifier TOUS les champs pré-remplis (Titre, Montant, Compte, Catégorie, Fréquence) pour garantir l'absence de régression de data-binding.

---
*Dernière mise à jour : 8 Août 2026 - Ingénieur QA LOPBudge*
