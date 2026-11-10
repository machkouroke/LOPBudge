Rédige ou remet aux normes une ou plusieurs fiches de test exécutables dans la base Notion **« Cas de test »**, rattachées à une User Story via `US couvertes` et `CA couverts`.

Entrée reçue : $ARGUMENTS

## Ce que cette commande fait, et ce qu'elle ne fait pas

Elle **conçoit et rédige le contrat de test complet dans Notion** qu'un développeur ou un agent peut implémenter sans rien inventer :
- choix rigoureux du niveau de test selon ce qu'on veut prouver ;
- découpage minimal en fiches (1 fiche = 1 niveau + 1 système testé) ;
- définition d'un jeu de données (JDD) explicite et déterministe ;
- matrice de cas avec oracles exacts et assertions obligatoires ;
- contrôles de faisabilité statiques (vérification des symboles et signatures réelles dans le code via `graft`) ;
- publication de la fiche dans la base Notion `Cas de test`.

Elle **n'implémente pas le code de test Kotlin** (utiliser `/write-test` pour implémenter les fiches écrites).

---

## 0. Résoudre l'entrée

L'entrée $ARGUMENTS est soit :
1. **L'URL ou l'identifiant d'une User Story** dans la base **« User Story »** :
   - récupérer l'US avec `notion-fetch` ;
   - extraire ses invariants ($I-x$), ses critères d'acceptation ($CA-xx$), son périmètre et hors-périmètre ;
   - vérifier s'il existe déjà des fiches liées dans la base `Cas de test`.
2. **L'URL d'une fiche existante** dans la base **« Cas de test »** :
   - récupérer la fiche et remonter à son US parente via `US couvertes` ;
   - analyser la fiche pour mise aux normes ou enrichissement.

**Prérequis** : L'US parente existe et ses CA sont testables. Si l'US manque de clarté ou d'oracles, proposer d'abord `/write-us`.

---

## 1. Principe fondateur

> **La fiche est le contrat. Le code existant n'est jamais l'oracle.**

Chaque fiche doit obligatoirement comporter dans son Objectif la phrase :
> *« Les comportements actuels de [SUT] ne constituent pas l'oracle. »*

Sans cette phrase, l'implémenteur lit le code, en déduit l'attendu, et produit un test qui valide le bug.
Une fiche qui fournit une matrice de cas avec oracles exacts doit être implémentée **telle quelle**. Ni élargie, ni réduite.

---

## 2. Choisir le niveau d'après ce qu'on veut prouver

| On veut prouver... | Niveau | Justification |
|---|---|---|
| Quelle branche ou dépendance est appelée | **Unitaire avec doublures** | Rapide, cible la logique de décision |
| Un calcul pur (grille, bornes, format, règle calendaire) | **Unitaire JVM**, sans Android | Ne dépend d'aucun framework ni persistance |
| Ce qui est **réellement écrit** (lignes, cardinalités, jointures) | **Intégration, vraie base Room** | **Un mock ne voit pas les INSERT** |
| L'état exposé d'un écran, validation formulaire | **ViewModel** | Pas de persistance directe en jeu |
| Ce que **seul l'écran rendu** montre : absence d'une zone, état sémantique, rotation | **UI instrumenté** (Android/Compose) | Aucune couche inférieure ne voit ces 3 aspects |
| Parcours visible bout en bout, écran atteignable | **E2E / Maestro** | Coûteux : réserver aux flux critiques |

*Un même CA peut légitimement exiger deux fiches (ex : les appels use case ET les lignes écrites en base Room). Ce n'est pas de la redondance.*

### Test d'admission UI
Pour tout cas candidat au niveau UI, poser la question :
> *Un lecteur d'écran pourrait-il constater ce que j'affirme ?*
- Si **Oui** → un niveau moins cher suffit presque toujours (ViewModel).
- Si **Non** → c'est un CA d'interface (absence d'élément, état sémantique, rotation, navigation réelle). Vérifier que l'élément visé porte un texte, un libellé ou un état sémantique dans l'arbre d'accessibilité.

---

## 3. Combien de fiches et combien de cas

### Découpage en fiches
- **Partir des CA**, jamais des écrans ni des fichiers de production.
- **Une fiche = un niveau + un système testé (SUT).**
- Appliquer le test de suppression à la fiche :
  > *Si je supprime cette fiche, quel CA n'est plus couvert par aucune autre ?*
- **Ordre de grandeur** : 2 à 3 fiches pour une US ordinaire.

### Découpage en cas dans une fiche
- **Règle : un cas doit porter une assertion qu'aucun autre cas ne porte.**
- Appliquer le test de suppression au cas :
  > *Si je supprime ce cas, quelle régression passerait désormais inaperçue ?*
- **Le témoin et la donnée de contrôle vivent DANS le cas**, pas à côté comme un cas distinct.
- Deux valeurs testant la même branche = 1 cas avec 2 jeux de données.
- **Ordre de grandeur** : 1 cas par CA, plus un cas par limite ou no-op explicitement nommé dans l'US.

---

## 4. Contrôles de faisabilité avant publication

1. **Aucun cas sur une API inexistante** : Si une méthode ou un paramètre manque dans le code, mentionner la signature cible complète et l'US comme ticket livrant. Si les règles vivent dans un ViewModel alors que le bon niveau est UseCase/Room, planifier l'extraction d'un use case sans laisser de second chemin.
2. **JDD cohérent avec les oracles** : Relire le JDD contre les oracles. Pas de tri strict sur des clés égales dans la fenêtre de test.
3. **Pas d'oracle sur ce que la doublure fabrique** : Si une doublure filtre ou fabrique la donnée, l'oracle porte sur les arguments reçus par la doublure (stub strict).
4. **Recenser les appelants réels** : Vérifier avec `graft` que le SUT est bien le point d'entrée réel et qu'aucun appelant ne réimplémente le comportement.
5. **Isoler l'horloge** : Dates fixes indépendantes de l'heure courante, fuseau et locale forcés puis restaurés.
6. **Distinguer synchronisation et oracle** : Le point de synchronisation initial attend l'identité de l'objet observé ; l'oracle complet est réservé à l'état final vérifié.
7. **Aucune couleur annoncée** : Les observations statiques sur le code de production sont des points d'attention localisés (fichier, ligne) « à vérifier à l'exécution ». Jamais de « rouge attendu » ni de « cas gagné d'avance ».

---

## 5. Structure obligatoire d'une fiche

```markdown
## Objectif
[Ce que la campagne garantit, 3 lignes.]
Source de vérité : [CA-xx] et invariants de [lien US].
Les comportements actuels de [SUT] ne constituent pas l'oracle.

## Niveau et cible
- Niveau : [Unitaire JVM | Use case doublé | Intégration Room | ViewModel | UI instrumenté | Maestro]
- Système testé : [Nom de la classe SUT]
- API à couvrir séparément : [Signatures réelles vérifiées dans le code]
- Fichiers de production : [Chemins relatifs exacts]
- Fichier proposé : app/src/test/java/.../TC_<réf>_<NomTest>.kt (classe : <NomTest>)

## Montage technique
- Runner, base en mémoire (Room neuve par variante si intégration), doubles stricts aux frontières.
- Interdictions : pas de relaxed, pas de mocks sur Room/DAO, pas de délai arbitraire (Thread.sleep).
- Déterminisme : fuseau UTC, dates fixes, dispatchers de test déterministes.

## Données à créer (JDD)
| Référence | Données métier exactes | Rôle dans le test |
[Valeurs exactes. IDs alloués sous noms symboliques (ex: ID_COMPTE_A).]

## Cas à couvrir
| ID | Action et état initial | Résultat attendu (oracle précis) |
|---|---|---|
| T-01 | [Action métier] | [Cardinalités exactes, champs exhaustifs, état final] |
| T-02 | [Cas limite ou erreur] | [Comportement d'échec strict, aucun effet de bord] |

## Assertions obligatoires
- Cardinalités exactes : « exactement 1 ligne », « liste vide ».
- Champs à vérifier : énumérer tous les champs nommés par le CA.
- Identifiants dérivés assertés par propriétés (unicité, signe), jamais en rappelant la fonction de prod.

## Risques identifiés à la lecture
Observations statiques dans le code de production (fichier, ligne) : points d'attention à vérifier à l'exécution.
Aucune couleur annoncée.

## Hors périmètre
[Repris de l'US + ce que ce niveau de test ne peut pas prouver.]

## Restitution attendue
Commande exacte : ./gradlew :app:testDebugUnitTest --tests "*NomTest"
Format : résultats par ID, messages réels des rouges, preuves de sensibilité pour les verts initiaux.
```

---

## 6. Déroulé de l'intervention

### Étape 1 : Analyse de la spec et du code
- Récupérer l'US parente dans Notion avec `notion-fetch`.
- Explorer le code réel avec `graft` : classes SUT, collaborateurs, DAO, entités Room, signatures existantes.
- Répartir les CA par niveau et identifier les fiches nécessaires.

### Étape 2 : Plan et première réponse (Validation utilisateur)
Rédiger le plan du lot de fiches dans `build/plans/<ref-us>-test-cards.md`.
Fournir une réponse courte en 4 blocs :
1. **Le lot proposé** : Tableau des fiches (réf provisoire, titre court, niveau, SUT, CA couverts).
2. **En bref** : Résumé en 3 phrases du découpage et de la stratégie. Lien vers le plan détaillé.
3. **À décider** : Questions d'arbitrage (ex: frontière de test, niveau d'un CA ambigu) avec options fermées et **exemples concrets**. Utiliser l'outil de question si disponible.
4. **À savoir** : Signatures cibles à créer par l'US, écarts de structure constatés.

**Attendre la validation de l'utilisateur avant de créer les pages Notion.**

### Étape 3 : Publication dans Notion
Après validation :
- Créer chaque fiche dans la base **« Cas de test »** (id: `9522aba9-c0c2-4428-a80c-3274fc281ab0`) via MCP `notion-create-pages`.
- Propriétés à renseigner :
  - `Nom` : Titre de la fiche
  - `US couvertes` : Relation vers l'US parente
  - `CA couverts` : Multi-select ou texte des CA
  - `Type de test` : `Unitaire`, `Intégration`, `ViewModel`, `UI`
  - `Statut` : `À écrire`
- Remplir le corps de chaque page avec la structure obligatoire.
- Restituer la synthèse du lot publié avec liens directs vers les fiches Notion.
