# LOPBudge

Application Android de gestion de budget personnel, inspirée de l'application **Budge** et entièrement repensée :
charte graphique sobre **Material You dynamique** (Material 3) avec accents inspirés de **One UI (8.5/9)** —
**bottom bar flottante** en pilule, panneaux et actions « flottants », coins très arrondis, thème sombre OLED par défaut.

Construite en **Kotlin + Jetpack Compose**, architecture **MVVM** + **Room** + **Hilt** + **DataStore**, assistant IA **Gemini** configurable et **widgets Glance**.

> Ce projet est conçu pour être **repris et étendu** facilement, par vous ou par une autre IA. Ce README décrit l'architecture, les conventions et la marche à suivre.

---

## 1. Prérequis et ouverture du projet

| Élément | Version recommandée |
|---|---|
| Android Studio | Ladybug (2024.2) ou plus récent |
| JDK | 17 |
| Gradle | 8.7 (via wrapper) |
| AGP (Android Gradle Plugin) | 8.5.x |
| Kotlin | 2.0.x |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` / `compileSdk` | 35 (Android 15) |

### Étapes
1. Ouvrir le dossier `LOPBudge/` dans Android Studio (**File > Open**).
2. Laisser Android Studio télécharger le **wrapper Gradle** et synchroniser (le `gradle-wrapper.jar` n'est pas inclus ; Android Studio le régénère, ou exécuter `gradle wrapper`).
3. Créer un fichier `local.properties` à la racine avec le chemin du SDK :
   ```
   sdk.dir=/chemin/vers/Android/sdk
   ```
4. Lancer sur un émulateur **API 31+** (recommandé pour la couleur dynamique Material You) ou un appareil physique.

> **Couleur dynamique** : effective sur Android 12+ (API 31). En dessous, l'app retombe sur la palette lavande par défaut (voir `ui/theme/Color.kt`).

---

## 2. Architecture

Le projet suit une architecture **MVVM** en couches, package racine `com.lop.budget` :

```
com.lop.budget
├── LopBudgeApp.kt          # Application Hilt (@HiltAndroidApp)
├── MainActivity.kt         # Point d'entrée Compose, applique le thème
│
├── ai/                     # Client Gemini (réseau, conseil-only)
│   └── GeminiClient.kt
│
├── data/
│   ├── local/
│   │   ├── LopDatabase.kt      # Base Room
│   │   ├── Converters.kt       # TypeConverters (enums)
│   │   ├── entity/             # Entités + relations + POJO enrichis
│   │   └── dao/                # DAO (Transaction + autres)
│   ├── repository/
│   │   ├── BudgetRepository.kt # Façade unique sur les DAO
│   │   └── SettingsRepository.kt # DataStore (devise, clé Gemini, thème)
│   └── seed/
│       └── DatabaseSeeder.kt   # Données d'exemple au 1er lancement
│
├── di/
│   └── AppModule.kt        # Modules Hilt (DB, DAO, seed)
│
├── domain/
│   ├── model/Enums.kt      # TransactionType, Status, RecurrenceFrequency…
│   └── recurrence/RecurrenceEngine.kt  # Calcul des occurrences à venir
│
├── ui/
│   ├── theme/              # Color, Theme (dynamic color), Shape, Type, ExtendedColors
│   ├── components/         # FloatingCard, FloatingBottomBar, DonutChart, PillTag…
│   ├── navigation/         # Routes + LopNavHost (Scaffold + bottom bar)
│   └── screens/
│       ├── home/           # Accueil (hub) : solde, revenus/dépenses du mois, à venir
│       ├── transaction/    # Ajout/édition (pavé numérique, récurrence, tags, liens)
│       ├── detail/         # Détail (édition catégorie même payé, occurrences)
│       ├── analytics/      # Donut + répartition par catégorie
│       ├── goals/          # Objectifs & dettes
│       ├── accounts/       # Comptes & soldes
│       ├── ai/             # Assistant IA (chat)
│       └── settings/       # Devise, clé Gemini, thème, dynamic color
│
├── util/                   # Format (devise/date), IconMapper, haptique
└── widget/                 # BalanceWidget (Glance)
```

### Flux de données
`Composable` → observe un `StateFlow` exposé par un `ViewModel` (Hilt) → qui lit/écrit via `BudgetRepository` / `SettingsRepository` → Room / DataStore. Tout est réactif via Kotlin **Flow**.

---

## 3. Modèle de données (Room)

- **AccountEntity** — comptes (nom, icône, couleur, solde initial).
- **CategoryEntity** — catégories typées (revenu/dépense), icône + couleur.
- **TagEntity** + **TransactionTagCrossRef** — tags réutilisables (relation N-N).
- **TransactionEntity** — cœur du modèle :
  - montant, type, statut (`PLANNED`/`PAID`), date, compte, catégorie, note ;
  - **récurrence fine** : `recurrenceFrequency`, `recurrenceInterval`, `recurrenceDaysOfWeek`, `recurrenceEndDate`, `recurrenceMaxOccurrences`, `seriesId` ;
  - **rattachement** : `linkedGoalId`, `linkedDebtId`.
- **GoalEntity** / **DebtEntity** — objectifs d'épargne et dettes (montant cible, progression).
- **TransactionWithRelations** — POJO `@Relation` agrégeant transaction + catégorie + compte + tags.

> Lors d'un changement de schéma, incrémentez `version` dans `LopDatabase` et fournissez une `Migration` (ou `fallbackToDestructiveMigration` en dev).

---

## 4. Fonctionnalités (état actuel)

### Conservées de Budge
- Logique d'ajustement : transactions **planifiées** vs **payées**, calendrier mensuel, objectifs, comptes, analyses.
- Disposition générale et hub central d'accueil.

### Ajoutées / améliorées (demandes utilisateur)
- **Vue Revenus / Dépenses du mois en cours** sur l'accueil.
- **Tags** sur les transactions (en plus des catégories).
- **Options de répétition avancées** (fréquence, intervalle, jours de la semaine, fin/occurrences max).
- **Rattachement** d'une transaction à une **dette** ou un **objectif**.
- **Édition de la catégorie même après paiement** (`changeCategory`, écran détail).
- **Affichage des prochaines occurrences** d'une transaction récurrente (`RecurrenceEngine.upcomingDates`).
- **Retour haptique** sur les interactions clés (pavé numérique, validation, changement de catégorie).
- **Icône dédiée** aux transactions récurrentes (`Icons.Filled.Repeat`).
- **Couleurs pilotées par Material You** (dynamic color), activables dans les réglages.

### Assistant IA (Gemini) — *conseil uniquement*
- Clé API saisie par l'utilisateur dans **Réglages** (stockée localement via DataStore).
- Périmètre **volontairement limité au conseil/questionnement** : l'IA reçoit un **résumé budgétaire en lecture seule** et **n'exécute aucune opération** (cf. `AiViewModel` + prompt système dans `GeminiClient`).
- Obtenir une clé gratuite : [Google AI Studio](https://aistudio.google.com/app/apikey).

### Widgets
- **BalanceWidget** (Glance) : solde net + nombre de transactions à venir. Première brique du système de widgets.

---

## 5. Conventions de code

- **Compose-first**, pas de XML de layout (hors thème/manifest/widget).
- Couleurs sémantiques **revenu/dépense** exposées via `LopTheme.extended` (voir `ExtendedColors.kt`) — ne **jamais** coder un vert/rouge en dur dans les écrans.
- Composants réutilisables dans `ui/components` : privilégier `FloatingCard`, `PillTag`, `CircleIcon`, `FloatingBottomBar`.
- Interactions sans halo : `Modifier.clickableNoRipple { }` (voir `Modifiers.kt`).
- Formatage centralisé dans `util/Format.kt` (montants, dates) — respecte la devise des réglages.
- Icônes : `IconMapper.get("nom")` pour mapper un nom stocké en base vers un `ImageVector`.

---

## 6. Pistes d'extension (roadmap suggérée)

1. **Écrans CRUD** pour catégories, comptes, tags, objectifs et dettes (les DAO `saveX` existent déjà).
2. **Génération automatique** des occurrences récurrentes en transactions réelles à l'échéance (worker `WorkManager`).
3. **Calendrier mensuel** interactif (vue par jour).
4. **Configuration de widgets** supplémentaires (prochaines dépenses, objectif, etc.).
5. **Migrations Room** et tests unitaires (`RecurrenceEngine` est un bon point de départ, logique pure).
6. **Export/Import** CSV.

---

## 7. Notes importantes

- Le `gradle-wrapper.jar` binaire n'est pas inclus dans la livraison ; Android Studio le régénère à l'ouverture, ou exécuter `gradle wrapper --gradle-version 8.7`.
- Les icônes de lancement sont des vecteurs adaptatifs simples (`res/drawable`, `res/mipmap-anydpi-v26`) — à remplacer par votre identité visuelle.
- La clé Gemini n'est **jamais** committée : elle vit uniquement dans le DataStore de l'appareil.

---

*Projet généré comme base de travail éditable. Bonne continuation sur LOPBudge.*
