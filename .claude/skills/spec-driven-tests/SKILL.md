---
name: spec-driven-tests
description: Écrire des cas de test à partir d'un ticket / d'une user story (Notion ou autre) — analyse des invariants et critères d'acceptation, choix du niveau de test, rédaction des oracles, gestion des tests rouges légitimes. À utiliser dès qu'une tâche consiste à écrire, compléter ou relire des tests adossés à une spécification. Ne pas utiliser pour du debug de test cassé sans spec de référence.
---

# Écrire des tests pilotés par la spécification

## Principe fondateur

**La source de vérité est le ticket / la user story. Jamais le code existant, jamais les tests existants.**

Le patrimoine de tests du dépôt est une *référence de forme* (conventions de nommage,
outillage, imports) et rien d'autre. Il n'est ni un oracle, ni une preuve que le
comportement actuel est correct, ni un gabarit à recopier. Trois corollaires :

- Un test existant qui contredit la spec est un test à corriger, pas un modèle à suivre.
- Une pratique répandue dans le dépôt mais douteuse (oracle mou, `assertTrue(any { })`,
  dépendance à `now()`) ne se propage pas au nouveau test sous prétexte de cohérence.
- Si le ticket ne dit rien sur un point, la référence est **la bonne pratique de test**,
  pas « ce que font les autres fichiers ».

Ce qu'on emprunte légitimement au dépôt : les helpers réellement réutilisables, le
harnais technique (runner, config), et **la vérification des noms de symboles réels**.

## Déroulé

### 1 · Lire la spec en entier, et remonter la chaîne

Un ticket de test référence presque toujours une US parente. Les **invariants** (I-x) et
les **critères d'acceptation** (CA-xx) vivent dans l'US, pas dans le ticket. Récupérer les
deux avant d'écrire une ligne. Extraire explicitement :

- les invariants applicables et ce qu'ils interdisent ;
- les CA couverts par ce ticket (souvent listés en propriété du ticket) ;
- le **jeu de données** imposé (JDD) et la **matrice d'oracles** si le ticket en fournit une ;
- le **hors-périmètre** — il est aussi contraignant que le périmètre.

Si le ticket fournit déjà une matrice de cas avec oracles exacts, **c'est le contrat** :
on l'implémente tel quel, on ne le réinvente pas et on ne l'élargit pas.

### 2 · Vérifier les noms réels dans le code

Le texte du ticket peut être périmé. Avant d'écrire, confirmer dans le code :
noms de classes, signatures, champs d'entités, valeurs de retour réelles.

> Vécu : un ticket désignait `GetTransactionsUseCase.observeBetween` ; la classe réelle
> s'appelait `ObserveTransactionsUseCase`. Le ticket disait lui-même « lire le nom réel ».

C'est de la **vérification**, pas de l'inspiration : on confirme que le symbole existe et
ce qu'il fait, on n'en déduit pas ce que le test devrait attendre.

#### Étudier la structure de l'app pour en déduire les **actions**

Avant d'écrire un test de parcours, lire la structure réelle de l'écran : quels widgets
existent, quelles branches d'affichage, quels états intermédiaires s'interposent entre deux
étapes du scénario. Le ticket décrit *ce qu'on veut prouver* ; il ne décrit presque jamais
*le chemin exact pour y arriver*.

La ligne de partage est stricte :

| Ce qu'on tire de la structure | Ce qu'on n'en tire **jamais** |
|---|---|
| Le chemin : quel sélecteur, quel ordre, quel écran intermédiaire | L'oracle : ce que le test doit attendre |
| Les branches d'UI à éviter ou à traverser | La justification qu'un comportement est correct |
| Le libellé réel d'un bouton, d'un placeholder, d'un statut | Le périmètre du test |

> Vécu : le picker de catégorie s'ouvre **seul** en mode ajout, et un tap sur une catégorie
> ayant des enfants ouvre un drill-down (« Sélectionner la catégorie principale » /
> « Sous-catégories ») au lieu de la sélectionner. Rien de tout cela n'est dans le ticket.
> En lisant le composable, on voit que le mode recherche court-circuite cette branche
> (`if (parent && !isSearching)`) : la sélection par recherche devient le chemin robuste.
> Choix d'**action**, déduit de la structure ; les assertions, elles, n'ont pas bougé.

**Préférer systématiquement le chemin le moins dépendant de la mise en page** : recherche
plutôt que scroll dans une grille, sélecteur stable plutôt que position, saisie plutôt que
navigation. Un élément hors viewport, une section « Récente » qui duplique un libellé ou un
ordre de grille qui change sont des sources de flake évitables.

**Cette phase peut révéler des bugs — c'est un résultat, pas un obstacle.** Si la structure
contredit la spec (libellé absent, branche impossible, navigation qui n'atterrit pas où le
ticket l'annonce), on le **signale** et on ouvre une ANO si c'est un défaut. On ne réécrit
pas l'oracle pour épouser la structure : la spec reste la source de vérité, et l'écart
constaté est précisément l'information à remonter.

### 3 · Choisir le niveau de test d'après ce qu'on veut prouver

| On veut prouver… | Niveau | Pourquoi |
|---|---|---|
| Quelle branche/dépendance est appelée | Unitaire avec doublures | Rapide, cible la logique de décision |
| Ce qui est **réellement écrit** (lignes, cardinalités, jointures) | Composant / intégration, vraie base | **Un mock ne voit pas les INSERT** |
| L'état d'un écran, la validation d'un formulaire | ViewModel / UI unitaire | Pas de persistance en jeu |
| Le parcours visible bout en bout | E2E | Coûteux : réserver aux flux critiques |

Un même CA peut légitimement exiger **deux** tickets (les appels *et* les lignes). Ce n'est
pas de la redondance.

### 4 · Écrire des oracles qui ne peuvent pas mentir

- **Cardinalités exactes** : `assertEquals(1, rows.size)`, `single()`. Jamais
  `assertTrue(list.any { … })`, qui passe avec des doublons ou du bruit.
- **Tous les champs nommés par le CA**, pas un échantillon. Si le CA dit « type, montant,
  libellé, date, catégorie, compte et statut persistés tels que saisis », les sept sont
  assertés.
- **Jamais d'oracle vacant.** Une boucle sur une collection potentiellement vide n'assert
  rien. Si un cas devient vide quand le code est correct, soit on assert explicitement la
  vacuité (`assertEquals(0, rows.size)`), soit on déporte l'oracle réel sur un cas où
  l'objet observé existe toujours — et on le documente.
- **Messages d'assertion porteurs** : citer l'invariant/CA et le symbole en cause. Un
  échec doit se diagnostiquer sans ouvrir le fichier.
- **Déterminisme** : dates fixes, fuseau forcé, pas de `now()` (sauf si le SUT l'impose,
  alors passer la date explicitement), ressources fermées au teardown.
- **Ne pas dépendre d'un fixture partagé pour un paramètre qui conditionne une cardinalité.**
  Une fenêtre d'observation dont dépend un « exactement 3 » se déclare **localement**, même
  si le helper hérité a aujourd'hui la bonne valeur : un autre ticket peut le changer.
- **Aucune API de production ajoutée pour le confort du test.** Besoin de lire une table
  sans DAO dédié ? Requête SQL brute dans le test.

### 5 · Assumer les tests rouges légitimes

Quand l'oracle correct de la spec échoue contre le code actuel, **c'est le résultat
attendu** : le test a fait son travail.

- On **n'assouplit jamais** l'oracle pour faire passer le test.
- On ouvre une anomalie et on la référence dans le kdoc du test.
- On documente dans le fichier : quel invariant est violé, par quel appel, et que le rouge
  est attendu tant que l'ANO n'est pas traitée.
- On le signale explicitement dans le compte rendu, avec le message d'échec réel.

Un test vert obtenu en pliant l'oracle au bug est une régression de couverture déguisée.

### 6 · Respecter le périmètre, y compris quand c'est frustrant

Un invariant hors niveau ne se teste pas « à peu près » au niveau courant. Une règle d'UI
(« ce toggle disparaît ») n'a pas d'équivalent en base : la couvrir par un proxy de
persistance produit un test qui rassure sans rien prouver.

La bonne réponse : ne pas la couvrir ici, l'écrire dans le kdoc comme explicitement hors
périmètre, et indiquer le ticket/niveau qui doit la porter.

### 7 · Exécuter, et lire le résultat en détail

Lancer la suite ciblée et **vérifier que chaque échec est celui qu'on attend**, via le
XML/HTML de résultats (`build/test-results/...`), pas seulement le compte agrégé. Un
échec au bon endroit pour la mauvaise raison est un test faux.

## Traçabilité

Chaque fichier de test porte en en-tête : le niveau, la chaîne réellement exercée, la
table `cas → CA/invariant → fonction de production`, les ANO connues, et le hors-périmètre.
Chaque test nomme dans son intitulé le Given/When/Then. Le lecteur doit relier un test à
son CA sans ouvrir Notion.

## Relire une revue de test

Une revue reçue (humaine ou agent) est une hypothèse à vérifier, pas une consigne.

- **Vérifier chaque affirmation factuelle avant d'agir.** Une remarque du type « ta classe
  parente a peut-être un `@Before` qui pollue » se tranche par un `grep`, pas par une
  refonte préventive.
- Corriger ce qui est fondé, **dire ce qui ne l'est pas** avec la preuve.
- Un durcissement peu coûteux vaut d'être appliqué même si le risque n'est pas avéré
  aujourd'hui (cf. la fenêtre déclarée localement).

## Checklist avant de rendre

- [ ] Invariants et CA lus dans l'US parente, pas seulement dans le ticket
- [ ] Noms de symboles vérifiés dans le code
- [ ] Tous les cas de la matrice du ticket implémentés, aucun ajouté hors périmètre
- [ ] Cardinalités exactes, aucun `any { }`, aucun oracle vacant
- [ ] Tous les champs nommés par le CA assertés
- [ ] Paramètres conditionnant une cardinalité déclarés localement
- [ ] Déterminisme : dates fixes, fuseau forcé, ressources fermées
- [ ] Aucune API de prod ajoutée pour le test
- [ ] Oracles non assouplis ; rouges légitimes documentés + ANO
- [ ] Hors-périmètre explicité dans le kdoc
- [ ] Suite lancée, chaque échec vérifié individuellement
