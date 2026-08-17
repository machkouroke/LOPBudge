# Instructions aux agents — Tests Maestro

Ces règles s’ajoutent au `AGENTS.md` racine et s’appliquent à tout `Maestro/**`.

## 1. Sources obligatoires

Avant toute modification :

1. lire `Maestro/GUIDE_BONNES_PRATIQUES.md` ;
2. lire `Maestro/config.yaml` ;
3. lire le flow et les subflows voisins ;
4. lire le parcours de production et `DatabaseSeeder.kt` ;
5. vérifier toute commande ou option nouvelle dans la documentation officielle `https://docs.maestro.dev/`.

Ne jamais inventer une commande, un paramètre ou un comportement Maestro. Un exemple trouvé dans un ancien flow n’est pas une preuve de validité s’il contredit la documentation actuelle.

## 2. Organisation imposée

- `tests/<CAS_DE_TEST>/` : un dossier par cas de test.
- Un fichier YAML exécutable par scénario ; ne pas regrouper plusieurs scénarios indépendants dans un seul flow.
- `.maestro/subflows/` : uniquement les parcours partagés, atomiques et paramétrables.
- `scripts/` : calculs JavaScript réutilisables, notamment les dates.
- Ne pas déplacer un fichier ou renommer un test sans mettre à jour tous ses `runFlow` et la découverte.

Chaque flow exécutable comporte au minimum `appId`, un nom descriptif, des tags et le séparateur `---`.

## 3. Lecture du ticket et du parcours réel

- Transformer chaque scénario Gherkin en préconditions, actions et assertions observables.
- Relier chaque assertion à un critère d’acceptation.
- Lire les écrans Compose, la navigation et les `testTag` actuels avant de choisir un sélecteur.
- Utiliser les données exactes du seeder ; ne pas deviner un titre, un montant, une catégorie ou une date.
- Si le parcours du ticket n’existe pas dans l’application, signaler le blocage au lieu d’inventer une navigation.

## 4. Isolation

- Chaque scénario critique démarre depuis un état connu, généralement via le subflow de bootstrap adapté avec `clearState: true`.
- Aucun test ne dépend de l’état laissé par un autre test.
- Le setup doit être minimal mais suffisant ; ne pas copier tout un scénario précédent.
- Les dates sont calculées relativement avec `Maestro/scripts/dates.js`, jamais codées en dur si elles dépendent du mois courant.

## 5. Sélecteurs

Ordre de préférence :

1. texte unique lorsqu’il constitue le contrat visible ;
2. `id`/`testTag` stable pour une action précise ;
3. relation structurée (`below`, `above`, `rightOf`, `leftOf`) si nécessaire.

Interdictions : coordonnées fixes, index fragiles, texte partiel ambigu, sélection « à l’aveugle ». Si un élément métier essentiel n’est pas sélectionnable, demander ou ajouter un `testTag` stable plutôt que bricoler le flow.

Toujours vérifier le contexte écran/modale avant de cliquer.

## 6. Attentes et saisies

- Pas de `sleep` arbitraire.
- Utiliser les mécanismes Maestro documentés, notamment `extendedWaitUntil` lorsqu’une attente explicite est nécessaire.
- Nettoyer réellement un champ avant `inputText` et fermer le clavier après la saisie lorsque celui-ci peut masquer l’action suivante.
- Faire défiler explicitement avant d’asserter un élément hors viewport.

## 7. Assertions métier

- Prouver le résultat final, pas seulement la fermeture d’une modale.
- Ajouter des contrôles d’isolation : transaction ponctuelle, autre série, mois adjacent ou autre donnée non ciblée selon le risque.
- Vérifier les cardinalités lorsque les doublons sont possibles.
- Une assertion négative doit être exécutée dans un contexte où l’élément aurait été visible s’il existait.
- Ne jamais supprimer, assouplir ou remplacer une assertion métier pour faire passer un flow.

## 8. Subflows

Créer un subflow uniquement si un bloc est réellement partagé ou possède une responsabilité stable. Il doit :

- avoir une seule responsabilité ;
- recevoir ses variations via `env` ;
- expliciter ses préconditions et son état de sortie ;
- rester compréhensible sans connaître le scénario appelant ;
- ne pas contenir l’oracle métier propre à un seul test.

La factorisation ne doit pas cacher les étapes déterminantes du scénario.

## 9. Validation

Depuis la racine `Maestro/`, commencer par le fichier ciblé, puis exécuter les tests du cas et enfin la suite/tag pertinent. Vérifier que `config.yaml` est chargé et que les subflows ne sont pas découverts comme tests autonomes.

Restituer : fichiers modifiés, scénarios/CA couverts, subflows réutilisés ou créés, commandes, résultats, captures/artefacts utiles et tout sélecteur ou comportement bloquant.

## 10. Git

Sauf demande explicite de l’utilisateur pour la tâche en cours, ne créer aucun commit, branche, push ou pull request. Laisser les fichiers modifiés pour revue humaine.
