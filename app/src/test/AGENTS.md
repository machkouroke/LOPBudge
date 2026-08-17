# Instructions JUnit et Robolectric

Ces règles s’ajoutent au `AGENTS.md` racine et s’appliquent à tout `app/src/test/**`.

## 1. Avant d’écrire le test

- Identifier une seule classe ou un seul chemin composant comme système testé.
- Lire le code de production courant, ses interfaces, ses appelants et les requêtes Room concernées.
- Repartir du contrat du ticket ; ne pas recopier aveuglément un ancien test.
- Définir pour chaque scénario : préconditions, action unique, état final attendu, données de
  contrôle et appels interdits.
- Si la signature demandée n’existe pas, signaler l’écart au lieu d’inventer la fonction.

## 2. Structure des classes

- Une classe de test porte une responsabilité claire.
- Nommer les méthodes par comportement : `given ... when ... then ...` ou une phrase métier
  équivalente.
- Utiliser `given / when / then` dans le corps lorsque cela améliore la lecture.
- Centraliser uniquement les fixtures répétitives. Un helper ne doit pas cacher les valeurs
  déterminantes du scénario.
- Les dates et IDs importants restent nommés dans le test (`februarySlot`, `movedDisplayDate`,
  `targetSeriesId`).

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
- Vérifications exactes : `coVerify(exactly = 1)`, `coVerify(exactly = 0)`, `verifyOrder`/
  `coVerifyOrder` lorsque l’ordre est contractuel.
- `confirmVerified` lorsque cela renforce l’absence d’effets parasites.

### Interdit

- `relaxed = true` ou `relaxUnitFun = true` sur une frontière métier.
- `spyk` de la classe testée.
- `any()` pour un identifiant, une portée, une date pivot, un statut ou toute autre valeur métier
  décisive.
- Mocker un `StateFlow` produit par le système testé.
- Mocker les DAO dans un test censé prouver un effet repository/Room.
- Mocker à la fois le repository, le DAO et le moteur sur le même chemin métier.
- Faire retourner par un mock l’état final que le système testé est censé produire.

## 5. Tests de use case

- Instancier le vrai use case.
- Doubler strictement le repository et les services secondaires uniquement pour vérifier
  l’orchestration.
- Vérifier les arguments complets, les appels interdits, les no-op spécifiés et les synchronisations
  secondaires.
- Vérifier l’ordre lorsque le contrat exige de lire avant d’écrire ou d’écrire avant de recalculer.
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
- Si l’implémentation est déjà correcte, effectuer une mutation locale temporaire de l’invariant et
  montrer que le test devient rouge.
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
