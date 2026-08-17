# Instructions aux agents — LOPBudge

Ce fichier s’applique à tout le dépôt. Un fichier `AGENTS.md` plus proche du fichier modifié ajoute
ou précise les règles locales.

## 1. Ordre de lecture obligatoire

Avant d’écrire ou de modifier du code :

1. lire ce fichier puis le `AGENTS.md` le plus proche de la zone concernée ;
2. lire intégralement le ticket et les critères d’acceptation ;
3. lire les signatures, les implémentations et les appelants réellement présents sur la branche
   courante ;
4. lire les tests existants de la même couche, sans les considérer automatiquement comme corrects ;
5. établir une matrice
   `exigence → fonction → niveau de test → dépendances réelles/doublées → oracle`.

Le code courant est la source de vérité pour les signatures existantes. Si le ticket et le code
divergent, ne pas inventer d’API ni réintroduire une ancienne architecture : signaler précisément
l’écart avant de modifier le contrat de production.

## 2. Périmètre et architecture

Le projet est organisé en couches :

- `data/local` : Room, entités et DAO ;
- `data/repository` : repositories ciblés, généralement délégataires des opérations DAO ;
- `domain/usecase` : orchestration métier ;
- `ui` : ViewModels et Compose ;
- `app/src/test` : JUnit/Robolectric ;
- `app/src/androidTest` : tests instrumentés Android/Compose ;
- `Maestro` : parcours UI de bout en bout.

Ne pas recréer de repository « god mode », de résolveur, de commande ou de couche supplémentaire
uniquement pour faciliter un test. Toute nouvelle abstraction de production doit répondre à un
besoin fonctionnel ou architectural explicitement validé.

## 3. Choix obligatoire du niveau de test

- **Logique Kotlin pure** : test unitaire sans Android ni Room.
- **Use case d’orchestration** : vraie instance du use case ; doubles stricts uniquement aux
  frontières de repositories ou de services secondaires.
- **Repository/DAO/persistance** : Room en mémoire, vrais DAO et vrai repository. Un mock de DAO ne
  prouve jamais un effet persistant.
- **ViewModel** : vrai ViewModel ; doubles stricts des use cases/repositories à sa frontière ;
  assertions sur les états et les appels exacts.
- **UI Compose** : `androidTest` lorsque les semantics, la navigation Compose ou l’intégration
  Android sont l’objet du test.
- **Parcours utilisateur** : Maestro, avec état initial maîtrisé et assertions métier visibles.

Un même invariant peut nécessiter deux tests complémentaires, par exemple un test unitaire de
mapping et un test Room des effets réels. Ne pas prétendre qu’un mock prouve un état en base.

## 4. Règles de qualité non négociables

- Tester un comportement observable et un invariant métier, pas seulement qu’une méthode a été
  appelée.
- Utiliser des valeurs métier explicites et réalistes ; ne pas masquer les arguments importants avec
  `any()`.
- Interdiction de `relaxed = true` sur le système testé ou sur une frontière métier.
- Interdiction de mocker la classe testée, ses états, Room ou plusieurs couches internes d’un même
  invariant.
- Ne jamais fabriquer le résultat attendu dans un `flowOf(...)` pour prétendre tester le repository
  qui devrait le produire.
- Vérifier les cardinalités exactes, l’état final, les effets persistés et les données de contrôle
  non affectées.
- Couvrir les chemins nominaux, les limites pertinentes, les no-op définis, l’idempotence attendue
  et les erreurs réellement spécifiées.
- Dates, fuseau, IDs et dispatchers doivent être déterministes. Pas de `System.currentTimeMillis()`
  dans un scénario reproductible.
- Aucun `Thread.sleep`, délai arbitraire ou retry destiné à cacher une race.
- Ne pas modifier, supprimer ou affaiblir une assertion pour faire passer un test rouge.
- Ne pas ajouter de code de production uniquement pour rendre un mock possible.

## 5. Processus RED → GREEN obligatoire

1. Écrire le test depuis le contrat et l’oracle définis dans le ticket.
2. Exécuter d’abord la classe ciblée.
3. Conserver la preuve RED lorsque le test expose un défaut attendu.
4. Si le code est déjà correct, prouver la sensibilité du test par une mutation locale temporaire de
   l’invariant ; retirer immédiatement cette mutation.
5. Appliquer uniquement le correctif de production validé, si le périmètre le permet.
6. Réexécuter la classe, la couche concernée puis les suites de non-régression indiquées.

Un échec de compilation, un mock non configuré ou une fixture invalide n’est pas une preuve RED
métier.

## 6. Restitution obligatoire

À la fin, fournir :

- fichiers créés ou modifiés ;
- matrice `scénario → CA → fonction → assertion principale` ;
- composants réels et doubles utilisés, avec justification ;
- commandes exécutées et résultats RED/GREEN ;
- incohérences statiques ou comportements non spécifiés découverts ;
- confirmation qu’aucun test n’a été affaibli.

Ne pas corriger silencieusement une incohérence hors périmètre : la documenter et demander une
décision.

## 7. Opérations Git

Sauf demande explicite de l’utilisateur pour la tâche en cours, ne créer aucun commit, branche, push
ou pull request et ne lancer aucun `git add`. Les agents chargés d’implémenter un ticket doivent
laisser les changements dans l’espace de travail pour revue humaine.

## 8. Routage local

- Pour JUnit/Robolectric : lire `app/src/test/AGENTS.md`.
- Pour les tests instrumentés : lire `app/src/androidTest/AGENTS.md`.
- Pour Maestro : lire `Maestro/AGENTS.md` et `Maestro/GUIDE_BONNES_PRATIQUES.md`.
