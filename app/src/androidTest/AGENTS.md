# Instructions des tests instrumentés Android / Compose

Ces règles s’ajoutent au `AGENTS.md` racine et s’appliquent à tout `app/src/androidTest/**`. Elles
consignent les pratiques des suites existantes (TC-125, TC-126, TC-132, TC-137).

## 1. Quand écrire un test instrumenté

Seulement quand l’objet du test est ce qu’un écran rendu peut seul prouver : une navigation réelle,
la présence ou l’absence d’un contrôle, un dialogue et son message, les semantics. Les règles
d’écriture, les cardinalités et l’état exposé se prouvent au niveau Room ou ViewModel, moins cher.

## 2. Montage

- `@HiltAndroidTest`, `HiltAndroidRule` en `order = 0`, `createEmptyComposeRule()` en `order = 1`,
  exécution par `HiltTestRunner`.
- `TestAppModule` remplace `AppModule` : base Room **en mémoire**, sans `DatabaseSeeder`. Ne pas
  réintroduire de peuplement automatique : aucune cardinalité exacte ne serait plus fiable.
- Injecter `LopDatabase`, appeler `clearAllTables()` puis semer par les **vrais DAO** dans le
  `@Before` ou dans le cas, **avant** de lancer l’activité. `runBlocking` n’est permis que pour cela.
- Une activité neuve par cas (`ActivityScenario.launch`), fermée dans le `@After`.
- Fuseau et locale : les forcer dans le processus (`TimeZone.setDefault`, `Locale.setDefault`) et
  les restaurer. Ne jamais modifier les réglages de l’appareil.
- L’horloge de `TestAppModule` est un `TestClock` : l’heure réelle par défaut, figeable par un cas
  (`@Inject lateinit var clock: TestClock` puis `clock.fixedAt = …`, remis à `null` au teardown).
  Hilt la recrée pour chaque cas, donc la figer ne touche aucune autre classe. Un écran qui lit
  `LocalDate.now()` ou `YearMonth.now()` directement échappe à cette horloge (cf. LOP-189).

## 3. Chemins d’action

- Ils se déduisent de la structure réelle des écrans ; les oracles, jamais. Lire le composable
  avant d’écrire le parcours.
- Cibler par `testTag`. Un libellé français ne sert que s’il est nommé par le critère d’acceptation,
  ou si c’est la valeur même qu’on vérifie.
- Une feuille modale ou un dialogue est une **racine de composition à part** : chercher avec
  `useUnmergedTree = true` et, pour UiAutomator ou Maestro, reposer `testTagsAsResourceId`.
- La barre du bas flottante peut masquer le dernier champ : un `performScrollTo` réussi ne garantit
  pas qu’un toucher atteigne le nœud.

## 4. Attentes et oracles

- Attendre par `composeRule.waitUntil` borné. Aucun `Thread.sleep`, aucune pause fixe.
- Joindre l’arbre sémantique (`onAllNodes(isRoot()).printToString()`) à **tout** échec, attente
  expirée comprise : une attente expirée seule ne dit rien.
- Chaque message cite l’ID du cas, le critère visé, l’attendu et ce qui a été observé.
- Cardinalités exactes sur les nœuds ; une absence s’asserte sur un **nœud** (tag, sélecteur), pas
  sur un simple texte, et après un témoin positif qui prouve que l’écran est bien chargé.
- Ne jamais lire la base pour construire un attendu : les valeurs attendues sont écrites en clair.

## 5. Exécution

```bash
export TMP='C:\gtmp' TEMP='C:\gtmp'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<classe complète>
./gradlew :app:connectedDebugAndroidTest
```

- Vérifier d’abord qu’un appareil est connecté (`adb devices`) et que l’application s’affiche : une
  boîte système (bibliothèque native non alignée sur 16 Ko, par exemple) peut voler le focus et
  faire échouer tous les cas. Regarder l’écran avant de toucher aux sélecteurs.
- Un échec d’environnement n’est pas une preuve métier : le dire avant tout résultat.
- Lire les résultats dans `app/build/outputs/androidTest-results/connected/`, cas par cas.

## 6. Preuve de sensibilité et restitution

Mêmes exigences que `app/src/test/AGENTS.md` §9 et §12 : chaque vert obtenu du premier coup est
prouvé par une mutation locale de la production, retirée aussitôt, et la restitution donne la
mutation, le cas devenu rouge et le retour au vert. Les mutations d’une même série ne doivent pas
se recouvrir : un rouge doit rester attribuable à une seule mutation.
