# Instructions JUnit et Robolectric

Ces règles s’ajoutent au `AGENTS.md` racine et s’appliquent à tout `app/src/test/**`.

## 1. Avant d’écrire le test

- Identifier une seule classe ou un seul chemin composant comme système testé.
- Lire le code de production courant, ses interfaces, ses appelants et les requêtes Room concernées.
- Repartir du contrat du ticket ; ne pas recopier aveuglément un ancien test.
- Définir pour chaque scénario : préconditions, action unique, état final attendu, données de
  contrôle et appels interdits.
- Construire une table de décision pour toute condition composée avec `&&`, `||` ou plusieurs
  valeurs nullables. Chaque clause doit pouvoir être rendue fausse indépendamment des autres.
- Toute formulation du ticket contenant « A ou B », « réel ou matérialisé » ou plusieurs variantes
  impose une variante de test par alternative. Il est interdit de choisir une seule alternative ou
  de ne tester que le cas où toutes sont simultanément absentes.
- Si la signature demandée n’existe pas, signaler l’écart au lieu d’inventer la fonction, notamment
  `resolveDeleteDecision` ou toute couche de production uniquement destinée au test.

## 2. Structure des classes et fixtures

- Une classe de test porte une responsabilité claire.
- Nommer les méthodes par comportement : `given ... when ... then ...` ou une phrase métier
  équivalente.
- Utiliser `given / when / then` dans le corps lorsque cela améliore la lecture.
- Centraliser uniquement les fixtures répétitives. Un helper ne doit pas cacher les valeurs
  déterminantes du scénario.
- Toutes les fixtures doivent représenter un état métier valide, sauf la propriété volontairement
  rendue invalide par le scénario.
- Les valeurs par défaut des builders ne doivent pas être sémantiquement impossibles : pas de date
  métier à `0L`, d’ID d’un format incompatible ou d’exception matérialisée incohérente.
- Les dates et IDs importants restent nommés dans le test (`februarySlot`, `movedDisplayDate`,
  `targetSeriesId`). Les timestamps opaques répétés sont interdits.
- Pour une occurrence non déplacée, utiliser `date == seriesDate`. Réserver `date != seriesDate` aux
  scénarios qui testent explicitement une occurrence déplacée.

### Fixtures discriminantes

Lorsque le système testé reçoit un objet en entrée puis recharge un état depuis un repository, au
moins un test doit rendre ces deux valeurs différentes pour prouver la source réellement utilisée.

Exemple : l’entrée a `linkedGoalId = null`, `getById` retourne l’état courant avec
`linkedGoalId = 7L`, et l’oracle attend `recalculateGoalProgress(7L)`. Le test doit devenir rouge si
la production utilise par erreur l’objet d’entrée.

Il est interdit d’utiliser systématiquement le même objet comme entrée et comme réponse du mock
lorsque la provenance de la donnée fait partie du contrat.

## 3. Coroutines et Flow

- Utiliser `runTest`, pas `runBlocking`.
- Pour un ViewModel, installer un `StandardTestDispatcher` sur `Dispatchers.Main`, appeler
  `advanceUntilIdle()` lorsque nécessaire, puis restaurer Main dans le teardown.
- `UnconfinedTestDispatcher` est interdit comme raccourci pour rendre le test implicitement
  synchrone ; toute exception doit être justifiée dans le ticket.
- Ne jamais utiliser `Thread.sleep` ni un délai arbitraire.
- Réabonner le `Flow` après une mutation quand le contrat porte sur un refresh ou une nouvelle
  observation.
- Avec Turbine, consommer explicitement les événements utiles et annuler proprement la collecte.

## 4. Politique MockK

### Autorisé

- Mock strict d’une dépendance située à la frontière du système testé.
- `coEvery`/`every` uniquement pour les appels possibles dans le scénario.
- Vérifications exactes : `coVerify(exactly = 1)`, `coVerify(exactly = 0)`, `verifyOrder` ou
  `coVerifyOrder` lorsque l’ordre est contractuel.
- `confirmVerified` lorsque cela renforce l’absence d’effets parasites.

### Interdit

- `relaxed = true` ou `relaxUnitFun = true` sur une frontière métier.
- `spyk` de la classe testée.
- `any()` dans un stub positif ou une vérification positive pour un identifiant, une portée, une
  date pivot, un statut ou toute autre valeur métier décisive.
- Mocker un `StateFlow` produit par le système testé.
- Mocker les DAO dans un test censé prouver un effet repository/Room.
- Mocker à la fois le repository, le DAO et le moteur sur le même chemin métier.
- Faire retourner par un mock l’état final que le système testé est censé produire.

### Exception limitée pour `any()`

`any()` est autorisé uniquement dans une vérification `exactly = 0` lorsque l’intention est de
prouver que la méthode ne doit être appelée avec aucun argument possible. Cette vérification doit
être accompagnée de `confirmVerified` ou d’un contrôle équivalent des appels parasites.

Si le mock complet ne doit recevoir aucun appel, préférer `wasNot Called`.

### Ordre causal obligatoire

Des `verify` ou `coVerify` indépendants ne prouvent jamais l’ordre. Utiliser `verifyOrder` ou
`coVerifyOrder` lorsque le contrat impose notamment :

- lire avant de modifier ;
- matérialiser avant de relire ;
- supprimer avant de recalculer ;
- persister avant de publier un nouvel état.

Ne figer que les relations causales métier. L’ordre de deux opérations indépendantes ne doit pas
être testé sans invariant explicite. `confirmVerified` ne remplace pas `coVerifyOrder`.

## 5. Tests de use case

- Instancier le vrai use case.
- Doubler strictement le repository et les services secondaires uniquement pour vérifier
  l’orchestration.
- Vérifier les arguments complets, les appels interdits, les no-op spécifiés et les synchronisations
  secondaires.
- Vérifier l’ordre lorsque le contrat exige de lire avant d’écrire ou d’écrire avant de recalculer.
- Au moins un scénario doit distinguer l’objet d’entrée de l’état relu lorsque le use case effectue
  une nouvelle lecture avant l’action.
- Un test de use case mocké ne remplace pas le test composant Room des effets persistés.

## 6. Tests repository/DAO/Room

- Utiliser `Room.inMemoryDatabaseBuilder(...)` avec le vrai `LopDatabase`.
- Utiliser les vrais DAO, repositories et moteurs impliqués ; aucun spy.
- Insérer un jeu de données minimal et visible dans le test. Ne pas utiliser le seeder de
  production.
- Créer les entités parentes obligatoires avant les lignes qui les référencent.
- Récupérer les IDs réellement générés par Room ; ne pas inventer un format incompatible.
- Vérifier à la fois : état persistant, résultat observable, cardinalité, isolation et absence de
  doublon.
- Un test composant peut couvrir une fonction bas niveau à travers le chemin réel sans créer un
  second test isolé, à condition qu’une mutation de cette fonction fasse échouer l’oracle métier.
- Fermer la base après chaque test.
- Si les API DAO publiques masquent les lignes soft-deleted, une requête SQL de lecture limitée au
  code de test est autorisée ; ne pas ajouter une API de production uniquement pour inspecter le
  test.

## 7. Dates et déterminisme

- Fixer le fuseau lorsque le calcul dépend du calendrier et restaurer sa valeur dans le teardown.
- Construire les instants avec `java.time` et un `ZoneId` explicite.
- Ne pas dépendre de l’heure actuelle, de l’ordre global des tests ou d’une base laissée par un
  autre scénario.
- Éviter les nombres opaques comme `1000L` lorsqu’une date métier lisible est attendue.

## 8. Oracles minimaux

Une assertion `any { ... }` seule est insuffisante pour une liste métier. Vérifier selon le cas :

- collection exacte des IDs ou slots attendus ;
- `single`, `count`, absence et unicité ;
- propriétés persistées exactes ;
- état des données de contrôle ;
- appels secondaires exacts et appels interdits ;
- état des `StateFlow` avant et après exécution.

## 9. Preuve de sensibilité

- Un test destiné à révéler un bug doit échouer sur une assertion métier avant le correctif.
- Si l’implémentation est déjà correcte, effectuer une mutation locale temporaire de chaque
  invariant critique indiqué dans le ticket et montrer quel test devient rouge.
- La restitution doit préciser la mutation, le scénario rouge, l’assertion qui la détecte, le retrait
  de la mutation et le résultat GREEN après restauration.
- Retirer la mutation immédiatement et ne jamais la committer.
- Ne pas accepter comme RED un crash de fixture, une erreur Room de schéma ou un mock non configuré.

## 10. Exécution

Commencer par la classe ciblée, par exemple :

```bash
./gradlew testDebugUnitTest --tests "*NomDeLaClasse*"
```

Puis exécuter au minimum :

```bash
./gradlew testDebugUnitTest
```

Si Room, Robolectric ou le code Android est impliqué, mentionner explicitement l’environnement
utilisé et recopier le résumé utile de la sortie.

## 11. Infrastructure de test et reporters

Ne pas ajouter un reporter personnalisé, une Rule globale ou un générateur de fichiers uniquement
parce qu’un ancien test le fait. Les rapports JUnit/Gradle existants sont préférés.

Un reporter personnalisé n’est autorisé que s’il est explicitement imposé par la stratégie de test.
Dans ce cas :

- aucun état mutable global partagé entre classes ;
- réinitialisation garantie pour chaque classe ;
- compatibilité avec l’exécution parallèle ;
- aucun fichier généré sous `src/` ;
- artefacts écrits uniquement sous `build/reports/` ou `build/test-results/` ;
- le reporter ne participe jamais à l’oracle fonctionnel ;
- ne pas instancier artificiellement une classe de test pour générer un rapport.

## 12. Audit final obligatoire

Avant de déclarer le ticket terminé, contrôler et restituer explicitement :

- chaque scénario et chacune de ses variantes ;
- les clauses indépendantes des conditions composées ;
- la validité métier des fixtures ;
- les fixtures discriminantes utilisées pour prouver la provenance des données ;
- les ordres causaux vérifiés ;
- les appels positifs et interdits ;
- chaque usage restant de `any()`, avec justification ;
- les usages de `relaxed`, `spyk`, `runBlocking`, `UnconfinedTestDispatcher`, `Thread.sleep` et des
  dates opaques ;
- les effets de bord ou fichiers produits par les tests ;
- les commandes et résultats RED, mutation et GREEN ;
- toute API ou architecture de production ajoutée alors qu’elle n’existait pas dans le code.

Une seule règle obligatoire non satisfaite rend le travail incomplet. L’agent doit le déclarer au
lieu de présenter le ticket comme terminé.
