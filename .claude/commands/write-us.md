Rédige ou remet aux normes une User Story testable dans la base Notion **« User Story »** selon le standard du template **`US (Good Version)`**.

Entrée reçue : $ARGUMENTS

## Ce que cette commande fait, et ce qu'elle ne fait pas

Elle **clarifie, structure et rédige une User Story testable dans Notion** :
- formulation du bénéfice utilisateur vérifiable ;
- délimitation stricte du périmètre et du hors-périmètre (avec tickets porteurs) ;
- définition d'invariants ($I-x$) qui disent ce qu'ils **interdisent** et factorisent les règles ;
- rédaction de critères d'acceptation ($CA-xx$) observables, avec tous les champs énumérés et oracles précis ;
- arbitrage des ambiguïtés (règles posées dans le texte, trace datée dans le `Journal des décisions` $P-x$) ;
- relevé des points d'ancrage réels et des écarts dans la page « Notes techniques », vérifiés dans le code via `graft`.

Elle **ne développe aucun code de production** (utiliser `/dev-us` ou `/conform-us`) et **n'écrit pas les cas de test** (utiliser `/write-test-card` puis `/write-test`).

---

## 0. Résoudre l'entrée

L'entrée $ARGUMENTS est soit :
1. **L'URL ou l'identifiant d'une page Notion existante** dans la base **« User Story »** :
   - récupérer la page avec `notion-fetch` ;
   - si la base parente n'est pas « User Story » (ex : « Cas de test »), remonter à l'US via la relation `US couvertes` et le préciser ;
   - analyser l'existant pour une mise aux normes (audit, restructuration, ajouts).
2. **Un besoin exprimé ou un texte brut** (rôle, action, bénéfice souhaité, epic de rattachement) :
   - initier la création d'une nouvelle US dans la base **« User Story »** (id: `88002000-d885-4311-be4b-90f7c733da0d`).

Réunir avant d'écrire :
- l'epic de rattachement, le type (`Feature`, `Enabler`, `EVOL`), la priorité ;
- les dépendances : pages de `Dépend de` et `Bloque` ;
- les **noms réels des points d'entrée** du code concerné (classes, méthodes, signatures, entités) via `graft` et lecture directe du dépôt ;
- le glossaire du projet (`Glossaire LOPBudge`) pour réutiliser les termes métier existants sans les redéfinir.

---

## 1. Principes et règles de rédaction

### A. La phrase d'US porte un bénéfice vérifiable, jamais une solution
> **En tant que** [rôle], **je veux** [action], **afin de** [bénéfice].

- Le `afin de` doit être directement vérifiable par l'utilisateur.
- « Afin d'avoir un code plus propre » n'est pas un bénéfice utilisateur : c'est un `Enabler`, et il doit obligatoirement nommer l'US métier qu'il débloque ou sert.

### B. Le hors-périmètre est aussi contraignant que le périmètre
- Écrire explicitement chaque élément exclu.
- Pour chaque élément exclu, nommer le ticket ou l'US porteuse existante ou à créer (ex : « Les graphiques de répartition font l'objet de l'US LOP-xxx »).

### C. Un invariant dit ce qu'il INTERDIT
- Les invariants `I-x` sont les règles transverses stables du périmètre.
- **Formulation négative** obligatoire : un invariant doit dire ce qui est interdit ou ce qui constitue un échec.
  - ❌ *« Les occurrences virtuelles sont bien gérées. »* (Introuvable à tester)
  - ✅ *« Une lecture génère les virtuels en mémoire, sans écrire de transactions en base. »* (Oracle clair)
- **Règle de factorisation** : un invariant ne se justifie que s'il est cité par **au moins deux CA**, ou s'il contraint des chemins qu'aucun CA ne traverse. S'il n'est cité que par un seul CA, le fondre dans ce CA.
- Pas de contrainte d'architecture (« détenu par le ViewModel ») ni de simple autorisation (« zéro tag est valide » : c'est du périmètre).
- **Budget** : viser 3 invariants, au maximum 5.

### D. Un CA nomme son oracle et énumère ses champs
- Identifiants `CA-xx` **stables** : un CA supprimé n'est jamais renuméroté, son ID n'est jamais recyclé.
- Trois exigences absolues pour chaque CA :
  1. **Énumérer exhaustivement tous les champs concernés** : proscrire les « etc. ».
  2. **Donner la cardinalité exacte** : « exactement une occurrence », « liste vide », « un seul slot ».
  3. **Dire ce qui doit échouer** : « un identifiant inexistant retourne `null`, jamais une autre entité ».
- Citer entre parenthèses les invariants appliqués à la fin du CA : `(I-1, I-3)`.
- **Budget** : au maximum 8 CA. Appliquer le test de suppression : si la suppression d'un CA ne laisse aucun trou de vérification, le supprimer ou le fondre.

### E. Trancher les ambiguïtés : la règle où elle s'applique, la trace au journal
- **La règle** va là où on en a besoin : définition dans `Vocabulaire`, exclusion dans `Hors-périmètre`, condition dans le `CA` concerné.
- **La trace** va dans le `Journal des décisions` en fin de page : identifiant `P-x`, date, décision tranchée, section impactée.

### F. Notes techniques vérifiées dans le code
- L'US reste purement métier dans son corps.
- Les détails d'implémentation vivent dans la page liée **« Notes techniques »** :
  - **Points d'ancrage réels** : noms de classes, use cases, DAO, tables Room, composants Compose, vérifiés dans le dépôt.
  - **Écarts avec l'US** : ce qui, dans le code actuel, ne satisfait pas la spec (avec le CA mis en défaut).
  - **Ajustements à prévoir avant les tests** : réorganisations nécessaires (ex : extraction d'un use case sans second chemin).

### G. Vues liées obligatoires
Poser sur la page de l'US les deux vues liées :
1. **Cas de test** : vue liée de la base `Cas de test`, filtre `US couvertes` contient cette US.
2. **Anomalies** : vue liée de la base `User Story`, filtre `Type` = `Anomalie` ET `Bloque` contient cette US.
Chaque vue vide porte une ligne explicative rappelant qu'elle ne constitue pas une preuve de conformité.

---

## 2. Structure type d'une User Story (`US Good Version`)

```markdown
# [Titre métier explicite]

## En tant que [rôle], je veux [action], afin de [bénéfice vérifiable].

### Vocabulaire
- **[Terme métier]** : définition concise en une ligne avant utilisation dans les CA.

### Périmètre
- Ce qui est inclus dans cette US.

### Hors-périmètre
- Ce qui est exclu, avec référence au ticket porteur.

### Invariants
- **I-1** : [Règle transverse négative interdisant un comportement invalide]
- **I-2** : [Autre règle factorisée citée par au moins deux CA]

### Critères d'acceptation
| ID | Règle et conditions | Résultat attendu (oracle précis) | Invariants |
|---|---|---|---|
| CA-01 | Quand ... | Tous les champs [A, B, C] sont ..., cardinalité exacte = 1 | (I-1) |
| CA-02 | Quand l'ID est invalide ... | Retourne null, aucune écriture en base | (I-1, I-2) |

### Cas de test
- Vue liée vers la base `Cas de test` (filtre : US couvertes = cette page)
- Couverture revendiquée et trous assumés explicitement motivés.

### Anomalies
- Vue liée vers la base `User Story` (filtre : Type = Anomalie, Bloque = cette page)

### Notes techniques
Lien vers la sous-page Notes techniques :
- Points d'ancrage vérifiés dans le code
- Écarts constatés avec le code actuel
- Ajustements pré-requis avant les tests

### Journal des décisions
| Réf | Date | Arbitrage / Décision | Section impactée |
|---|---|---|---|
| P-01 | YYYY-MM-DD | [Décision explicite] | CA-02 |
```

---

## 3. Déroulé de l'intervention

### Étape 1 : Analyse et extraction
- Lire l'US existante ou le besoin fourni.
- Vérifier le code réel avec `graft` : identifier les fichiers existants, use cases, entités Room, ViewModels, signatures réelles.
- Relever les termes du glossaire et les incohérences existantes.

### Étape 2 : Plan et première réponse (Validation utilisateur)
Rédiger le contenu complet de l'US dans un fichier local de préparation :
`build/plans/<ref-us>-spec.md`.

Fournir à l'utilisateur une première réponse courte en 4 blocs :
1. **L'US** : Titre, type, epic, dépendances, statut.
2. **En bref** : Résumé en 3 phrases du besoin, des invariants clés et du nombre de CA. Lien vers le fichier de spec.
3. **À décider** : Uniquement les questions ouvertes ou arbitrages produit nécessaires, formulés avec options fermées et **exemples concrets** (données ou écrans réels). Utiliser l'outil de question si disponible.
4. **À savoir** : 2-3 constats majeurs sur le code actuel (ex : écarts structurels, use case manquant).

**Attendre la validation de l'utilisateur avant de créer ou mettre à jour la page Notion.**

### Étape 3 : Publication dans Notion
Après accord de l'utilisateur :
- Créer la page avec `notion-create-pages` dans la base `User Story` (ou mettre à jour avec `notion-update-page`).
- Renseigner les propriétés Notion (`Type`, `Statut` = `Prêt pour dev` ou `Spécification`, `Epic`, `Fichiers concernés`).
- Créer ou lier la sous-page « Notes techniques ».
- Restituer le résultat avec le lien Notion direct et la synthèse des invariants/CA.
