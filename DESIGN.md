# Charte graphique — LOPBudge

Ce fichier décrit le système visuel de l'app : couleurs, surfaces, relief, typographie et les
composants qui les portent. Il s'applique à tout code d'interface (`app/src/main/java/com/lop/budget/ui`).

La source de vérité des règles reste l'US **LOP-229** (« Charte graphique — fonds neutres, accent
dynamique et relief ») et son journal de décisions P-1 à P-16. Ce fichier en est le mode d'emploi :
si une valeur diffère entre ce fichier et le code, le code fait foi — signale l'écart.

Référence d'intention : l'app **Silo** (captures dans l'US). On en reprend le rendu, pas l'agencement
des écrans.

---

## 1. Principes

1. **Fond neutre, accent dynamique.** La couleur du thème Android (Material You) ne remplace jamais un
   fond ni une surface. Elle teinte seulement le ciel en haut de l'écran et les éléments d'accent :
   bouton +, actions principales, onglet sélectionné, liens. (I-1)
2. **Revenu vert, dépense rouge, toujours.** Ces couleurs sémantiques ne dépendent ni de l'accent ni
   du mode. (I-2)
3. **Les cartes reprennent la couleur du fond.** Blanches en clair, noires en sombre. En clair, une
   ombre douce les détache ; en sombre, c'est un reflet sur le bord haut.
4. **Une carte dans une carte est une tuile** : ni ombre ni contour, teintée de la couleur de son
   compte ou de sa catégorie.
5. **Les préférences de l'utilisateur ne changent jamais en silence** : thème Système / Clair / Sombre
   et couleur dynamique.

---

## 2. Où vit le design

| Fichier | Contenu |
| --- | --- |
| `ui/theme/Color.kt` | Toutes les valeurs : neutres clairs et sombres, accent de repli, sémantiques, liseré |
| `ui/theme/Theme.kt` | `LopBudgeTheme` (schéma neutre + accent dynamique), `lopBackgroundBrush()` (ciel), `lopRimBrush()`, `lopSheenBrush()` |
| `ui/theme/ExtendedColors.kt` | `LopExtendedColors` : revenu, dépense, alerte, liseré, reflet — via `LopTheme.extended` |
| `ui/theme/Type.kt` | `LopTypography` |
| `ui/theme/Shape.kt` | `LopShapes` |
| `ui/components/Common.kt` | `FloatingCard`, `Modifier.surfaceRelief`, `Modifier.softShadow`, `LopScreenScaffold` |
| `ui/components/LopBottomSheet.kt` | La feuille modale de l'app |
| `ui/components/FloatingBottomBar.kt` | Barre du bas et bouton + |
| `ui/motion/MotionSpec.kt` | Ressorts et durées de toutes les animations (§ 13) |
| `ui/navigation/NavAnimations.kt` | Transitions entre écrans (§ 13) |
| `ui/components/Skeleton.kt` | Squelette des écrans qui chargent (§ 13.4) |

Ne crée pas de couleur en dur dans un écran : passe par un rôle de `MaterialTheme.colorScheme` ou par
`LopTheme.extended`. Si aucun rôle ne convient, ajoute-le dans `Color.kt` avec sa justification.

---

## 3. Couleurs

### 3.1 Rôles neutres

Chaque rôle a un usage précis. Respecte-le : c'est ce qui garde les aplats visibles sur les cartes et
les cartes visibles sur le fond.

| Rôle | Usage | Clair | Sombre |
| --- | --- | --- | --- |
| `background` | Fond principal, sous le ciel | `#FFFFFF` | `#0A0A0A` |
| `surfaceContainerLow` | **Cartes** (`FloatingCard`), popups-cartes | `#FFFFFF` | `#000000` |
| `surfaceContainerHigh` | **Contrôles en relief** : boutons ronds, barre du bas, pilule du mois | `#FFFFFF` | `#000000` |
| `surface` | **Feuilles** (`LopBottomSheet`), menu Ajouter | `#F7F8FA` | `#141414` |
| `surfaceVariant` | **Aplats** posés sur une carte : pastilles d'icône, puces, tuiles neutres | `#F1F2F5` | `#262626` |
| `surfaceDim` | Onglet sélectionné de la barre du bas | `#EEF0F3` | `#1C1C1E` |
| `surfaceContainer` / `Highest` / `Lowest` | Composants Material | `#F4F5F7` / `#E9EBEF` / `#FFFFFF` | `#161616` / `#2E2E2E` / `#050505` |
| `onBackground`, `onSurface` | Texte principal | `#15171A` | `#F5F6F8` |
| `onSurfaceVariant` | Texte secondaire | `#555962` | `#A1A1A6` |
| `outline` | Contours d'interaction (champs, interrupteurs) | `#7A7E87` | `#8E8E93` |
| `outlineVariant` | Séparateurs | `#E6E8EC` | `#2A2A2A` |

`surfaceTint` vaut la couleur de `surface` : l'élévation tonale Material ne teinte donc pas les surfaces.

### 3.2 Accent

- Couleur dynamique activée (Android 12 ou plus) : seuls `primary`, `secondary`, `tertiary`, leurs
  `on…`, leurs `…Container` et `inversePrimary` viennent d'Android. Tout le reste reste neutre (code
  dans `LopBudgeTheme`).
- Couleur dynamique désactivée ou indisponible : accent **lavande** de repli (`#B69DF8` en sombre,
  `#6750A4` en clair).
- Une future US permettra de choisir la couleur. Tant qu'elle n'existe pas, l'accent vient d'Android.

### 3.3 Sémantiques — `LopTheme.extended`

| | Clair | Sombre |
| --- | --- | --- |
| `income` (revenu) | `#1B873F` | `#4CAF50` |
| `expense` (dépense) | `#C62828` | `#E53935` |
| `warning` | `#B26A00` | `#FFB74D` |

Il existe aussi `incomeContainer`, `expenseContainer`, `onIncome` et `onExpense`. N'utilise jamais
`primary` pour un montant.

### 3.4 Détecter le mode sombre

Teste le thème **réellement appliqué** :

```kotlin
val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
```

Ne teste jamais `settings.themeMode == ThemeMode.DARK` : en mode Système sur un téléphone sombre, ce
test répond faux. Ce bug a existé dans le menu Ajouter et l'aperçu de transaction.

---

## 4. Le ciel (dégradé du haut)

`lopBackgroundBrush(fromWindowTop: Dp = 0.dp)` dans `Theme.kt`.

- Teinte d'accent en haut : `primary` à 20 % en clair, 22 % en sombre, composé sur `background`.
- Il s'efface jusqu'à `background` à **470 dp** du haut de la fenêtre, avec des paliers à 35 % (55 % de
  la teinte) et 70 % (20 %).
- Le pinceau est calé sur le **haut de la fenêtre**. Un élément posé plus bas passe sa distance en
  `fromWindowTop`, et le dégradé continue sans couture.

Il est déjà posé à trois endroits :

| Où | Comment |
| --- | --- |
| `LopNavHost` | Fond de l'accueil et de la bande de la barre d'état |
| `LopScreenScaffold` | Conteneur et barre de titre, avec `fromWindowTop` = hauteur de la barre d'état |
| `HomeOverlay` | Voile sous l'en-tête de l'accueil (même pinceau, masque `DstIn`) |

**Un nouvel écran passe par `LopScreenScaffold`** et hérite du ciel. Un écran racine qui peint son
propre fond opaque casse le dégradé. Les écrans restent opaques : sans cela, les transitions glissées
se superposeraient.

Le plafond de 20 / 22 % garde le texte secondaire à au moins 4,5:1 en haut du ciel. Ne l'augmente pas
sans revérifier les contrastes (§ 9).

---

## 5. Surfaces et relief

### 5.1 `FloatingCard` — la carte de l'app

```kotlin
FloatingCard(
    modifier = …,
    color = Color.Unspecified,   // teinte du contour, posée à 50 % — sert à porter un état
    cornerRadius = 28.dp,
    contentPadding = PaddingValues(18.dp),
    tint = Color.Unspecified,    // teinte du fond quand la carte devient une tuile
) { … }
```

**Posée seule** sur la page : c'est une carte.
- fond `surfaceContainerLow` ;
- liseré `lopRimBrush()` (sombre uniquement) ;
- ombre `softShadow` (visible en clair seulement).

**Posée dans une autre `FloatingCard`** : elle devient automatiquement une **tuile** (P-14), grâce à
`LocalInsideCard`.
- ni ombre ni contour ;
- fond `tint` (à défaut `color`) à 8 % en clair et 16 % en sombre, composé sur la carte parente ;
- sans teinte, l'aplat `surfaceVariant`.

Toujours passer la couleur de l'entité en `tint` :

```kotlin
FloatingCard(tint = Color(account.colorArgb)) { … }   // tuile de compte
FloatingCard(tint = categoryColor) { … }               // ligne de transaction
```

`color` sert à porter un **état** par le contour, par exemple le bouton « payé » du détail de
transaction ou les sous-catégories teintées. Ne l'utilise pas pour la couleur de fond.

### 5.2 Les briques

| API | Effet | Quand |
| --- | --- | --- |
| `Modifier.softShadow(shape, elevation = 16.dp)` | Ombre large et pâle (≈ 5 %) | Toute surface en relief |
| `Modifier.surfaceRelief(shape)` | `softShadow` + liseré | Surfaces qui ne sont pas une `Surface` Material : boutons ronds, pilule du mois. À chaîner **avant** le `background` |
| `lopRimBrush()` | Reflet du bord haut, blanc 10 % éteint à mi-hauteur (sombre), transparent (clair) | Bordure des cartes et contrôles |
| `lopSheenBrush()` | Reflet du haut de carte, désactivé aujourd'hui (transparent dans les deux modes) | Réservé |

### 5.3 Règles

- Une surface opaque seulement. Une ombre sous un fond translucide fait une tache.
- Pas d'imbrication carte-dans-carte à la main : `FloatingCard` gère la tuile.
- En sombre, l'ombre est invisible (environ 1 % d'écart) : n'en fais jamais le seul repère de relief.
- En clair, pas de contour sur les cartes : l'ombre suffit (P-11). Sans elle, une carte blanche
  disparaît sur le fond blanc.

---

## 6. Contrôles

### Boutons ronds d'en-tête

Cercle de 44 dp, fond `surfaceContainerHigh`, `surfaceRelief(CircleShape)`, icône **contour**
(`Icons.Outlined.*`) de 20 dp en `onSurface`. La pilule du mois suit le même relief avec
`RoundedCornerShape(50)`.

### Barre du bas — `FloatingBottomBar`

- Pilule de 64 dp de haut, fond `surfaceContainerHigh`, liseré, `softShadow(CircleShape)`, effet haze.
- **Onglet sélectionné** : un indicateur unique `surfaceDim` qui coulisse d'un onglet à l'autre
  (§ 13), icône **pleine**, icône et libellé en `primary`,
  libellé en gras. On la reconnaît sans la couleur (forme, remplissage, graisse : CA-04).
- **Onglet non sélectionné** : icône contour et libellé en `onSurface` à 80 %, libellé SemiBold 12 sp.
- Tout nouvel onglet fournit deux icônes, `icon` (contour) et `selectedIcon` (pleine).

### Bouton +

Cercle de 56 dp rempli de `primary` (l'accent, P-7), reflet blanc de bord (35 % → 0), ombre de 8 dp,
icône en `onPrimary`.

---

## 7. Feuilles modales

**Utilise toujours `LopBottomSheet`, jamais `ModalBottomSheet`** (P-13).

Dans Material 3 1.3.1, le `modifier` de `ModalBottomSheet` est posé avant le décalage de la feuille :
une bordure ou une ombre passée par lui se dessine en haut de l'écran. Et la feuille coupe tout ce qui
dépasse de sa forme, donc pas d'ombre possible.

```kotlin
LopBottomSheet(
    onDismiss = onDismiss,
    modifier = Modifier.testTag(TestTags.MA_FEUILLE),
    maxHeightFraction = 0.75f,
    dismissible = !busy,   // false : ni voile, ni retour, ni glissement ne ferment (écriture en cours)
) { /* ColumnScope */ }
```

- Fond `surface`, coins de 28 dp en haut, `surfaceRelief`, voile noir à 55 %, poignée de glissement.
- La feuille est une `Dialog` : `testTagsAsResourceId` est déjà reposé à sa racine.
- Hauteur plafonnée à `maxHeightFraction`. Une liste paresseuse à l'intérieur doit être bornée :
  `Modifier.weight(1f, fill = false)` ou `heightIn(max = …)`. Sinon elle passe sous la barre de
  navigation.
- `onDismiss` est appelé **après** l'animation de fermeture.

---

## 8. Typographie

Police du **système**, aucune police embarquée (P-8). Le caractère vient des graisses et de l'approche
resserrée des grands styles (`ui/theme/Type.kt`) :

| Style | Graisse | Taille / interligne | Approche |
| --- | --- | --- | --- |
| `displayLarge` | Black | 57 / 64 sp | −1,5 sp |
| `displayMedium` | Black | 45 / 52 | −1 |
| `displaySmall` | Black | 34 / 40 | −1 |
| `headlineLarge` | ExtraBold | 32 / 40 | −0,75 |
| `headlineMedium` | ExtraBold | 26 / 32 | −0,5 |
| `headlineSmall` | ExtraBold | 24 / 32 | −0,5 |
| `titleLarge` | Bold | 20 / 26 | −0,3 |
| `titleMedium` | Bold | 16 / 22 | — |
| `titleSmall` | SemiBold | 14 / 20 | — |
| `bodyLarge` / `bodyMedium` | Normal | 16 / 24, 14 / 20 | — |
| `labelLarge` / `labelMedium` / `labelSmall` | SemiBold | 14 / 20, 12 / 16, 11 / 16 | — |

- Le gros montant de l'accueil utilise `displayLarge`.
- Préfère un style de la typographie à un `fontWeight` explicite : environ 80 surcharges existent
  déjà et échappent à la charte.
- Tailles en `sp`, jamais en `dp`, et jamais de hauteur fixe autour d'un texte : le texte agrandi
  doit rester lisible (CA-05).

### Formes — `LopShapes`

`extraSmall` 10 dp · `small` 16 · `medium` 22 · `large` 28 · `extraLarge` 36. Les cartes utilisent
28 dp par défaut, les feuilles 28 dp en haut.

---

## 9. Accessibilité et contrastes

Seuils validés (P-3) :
- **texte normal ≥ 4,5:1** ;
- **composant nécessaire à l'interaction ≥ 3:1** avec son fond adjacent.

Paires vérifiées :

| Paire | Ratio |
| --- | --- |
| `onSurfaceVariant` sombre `#A1A1A6` sur aplat `#262626` | 5,88 |
| `onSurfaceVariant` sombre sur haut du ciel chaud (≈ `#3A3330`) | 4,81 |
| `onSurfaceVariant` clair `#555962` sur haut du ciel bleu (≈ `#C9DAF5`) | 4,96 |
| `outline` sombre `#8E8E93` sur fond | > 3 |
| `outline` clair `#7A7E87` sur feuille `#F7F8FA` | 3,83 |

Toute nouvelle couleur de texte ou de contour se vérifie contre **chacun** de ses fonds réels : carte,
aplat, haut du ciel, en clair **et** en sombre.

Une sélection ne se signale jamais par la couleur seule : ajoute une forme, un remplissage, une icône
pleine ou une graisse.

> Écart connu, non traité : le choix Système / Clair / Sombre des Réglages ne se distingue que par la
> couleur.

---

## 10. Pièges connus

| Piège | Conséquence | Faire plutôt |
| --- | --- | --- |
| `themeMode == ThemeMode.DARK` pour savoir si l'on est en sombre | Faux en mode Système | `background.luminance() < 0.5f` |
| `Color.White` / `Color.Black` comme fond | Ignore le thème | Un rôle du schéma |
| `ModalBottomSheet` + `modifier.border/shadow` | Dessiné en haut de l'écran | `LopBottomSheet` |
| `FloatingCard` dans `FloatingCard` avec une couleur de fond forcée | Carte sur carte | `tint = couleur de l'entité` |
| `surfaceVariant` comme fond de carte | Les aplats deviennent invisibles sur la carte | `surfaceContainerLow` |
| Fond opaque sur un écran racine hors `LopScreenScaffold` | Le ciel s'arrête net | `LopScreenScaffold` ou `lopBackgroundBrush` |
| Fond translucide + ombre | Tache grise | Fond opaque |
| Laisser la couleur dynamique toucher `background` / `surface*` | Viole I-1 | Ne copier que les rôles d'accent |
| Fond sombre teinté (bleuté…) | Sous un ciel chaud, la transition tire vers le vert | Noir neutre `#0A0A0A` |

---

## 11. Checklist d'un nouvel écran ou composant

- [ ] Écran racine dans `LopScreenScaffold` (ou ciel posé via `lopBackgroundBrush`).
- [ ] Sections en `FloatingCard` ; éléments imbriqués avec `tint` = couleur de leur compte ou catégorie.
- [ ] Contrôles flottants : `surfaceContainerHigh` + `surfaceRelief`, icônes contour.
- [ ] Feuilles en `LopBottomSheet`, listes bornées, `dismissible` si une écriture peut être en cours.
- [ ] Aucune couleur en dur ; montants en `LopTheme.extended.income` / `expense`.
- [ ] Styles de `MaterialTheme.typography` plutôt que des graisses forcées.
- [ ] Contrastes vérifiés en clair et en sombre (§ 9) ; sélection lisible sans la couleur.
- [ ] Animations sur les jetons de `MotionSpec` (§ 13), aucune durée ni courbe locale.
- [ ] Écran qui charge : `rememberShowSkeleton` + `skeleton(tag)` (§ 13.4), jamais un indicateur tournant.
- [ ] Vérifié à l'écran, sur le téléphone, dans les quatre combinaisons : clair / sombre × couleur
      dynamique activée / désactivée. Réglages de l'utilisateur remis à leur valeur d'origine.

---

## 12. Hors de cette charte (pistes d'autres US)

Inspirés de Silo, mais relevant de l'agencement des écrans, pas de la charte :
- grands titres alignés à gauche (« Comptes », « Analyses ») plutôt que la barre de titre centrée ;
- intertitres en petites capitales espacées (« À VENIR ») ;
- listes groupées dans une seule carte, avec séparateurs et icônes dans des pastilles teintées ;
- choix de la couleur d'accent dans l'app.

---

## 13. Mouvement

Source : US **LOP-226** (P-3 à P-11). Toute animation part de `MotionSpec` : aucune durée, courbe ni
ressort défini localement dans un écran.

### 13.1 Les jetons

| Jeton | Réglage | Sert à |
| --- | --- | --- |
| `screenTween()` = `SCREEN_MS` + `screenEasing` | 400 ms, `FastOutSlowInEasing` | ouverture et retour d'un écran, recul de l'écran recouvert |
| `TAB_OUT_MS` / `TAB_IN_MS` + `exitAccelerate` / `enterDecelerate` | 200 puis 300 ms, courbes Material 3 « emphasized » | fondu enchaîné entre onglets |
| `spatialFast` | ressort 0,9 / 400 (~0,3 s) | bord avant de l'indicateur |
| `spatialSlow` | ressort 0,9 / 150 (~0,5 s) | bord arrière de l'indicateur, qui traîne pour l'étirer |
| `spatialBouncy` | ressort 0,6 / 800 | rebond visible, petits éléments seulement : icône de l'onglet choisi |
| `FAST_MS` / `MEDIUM_MS` / `SLOW_MS` + `easeOut` | 90 / 180 / 260 ms | fondus et apparitions à durée : feuilles, barre du bas |

### 13.2 Durée ou ressort

- Un **écran** prend une durée avec un départ doux (`screenTween`). La première image d'un écran
  neuf coûte cher (~65 ms mesurés en debug) : avec un départ doux, elle n'avance presque pas le
  mouvement. Un ressort part à pleine accélération : cette image avalait le début, l'écran semblait
  sauter et l'ensemble paraissait trop rapide (P-8, essais du 5 novembre 2026 sur le téléphone).
- Un **élément qu'on peut réorienter en plein vol** prend un ressort (`spatial*`) : interrompu, il
  repart de sa position et de sa vitesse, sans à-coup. C'est le cas de l'indicateur sous des taps
  rapides.
- Un **fondu entre deux écrans transparents** prend une durée : il faut retarder l'entrant, sinon
  les deux pages se superposent. Courbes appariées : l'ancien tient puis part, le nouveau est visible
  dès son départ ; une même courbe lente des deux côtés laisse l'écran vide entre les deux.

### 13.3 Les règles

- **Jamais de rebond sur un élément plein écran.** Le rebond (`spatialBouncy`) est réservé aux
  éléments de la taille d'une icône.
- **Ouverture d'un écran** : il entre par le bord droit ; l'écran recouvert recule d'un quart de
  largeur vers la gauche en s'effaçant. **Retour** : miroir exact.
- **Entre onglets** : aucun glissement. L'ancien s'efface, puis le nouveau apparaît en passant de
  96 % à 100 %.
- **Indicateur de la barre** : un seul, qui suit la pile de navigation et non le tap. Bord avant
  `spatialFast`, bord arrière `spatialSlow` : il s'étire vers l'onglet visé puis se resserre.
- **Barre du bas** : elle sort et revient par le bas, comme si elle quittait le bord du téléphone.
- **Feuilles et popups** : elles montent du bas (`LopBottomSheet`, § 7) ; elles ne prennent pas le
  mouvement horizontal des écrans.
- **Animations système désactivées** : Compose suit l'échelle d'animation du système, chaque
  mouvement atteint aussitôt sa position finale. Une animation ne porte jamais d'état : la
  destination et la sélection existent sans elle.
- Les écrans restent opaques (§ 4) : le recul de l'écran recouvert ne se voit que sur la bande que
  l'écran entrant n'a pas encore couverte.
- **Juger un mouvement sur le téléphone**, pas sur le papier : les valeurs ci-dessus viennent
  d'essais à l'écran.

### 13.4 Chargement : le squelette

Un écran qui charge des données montre un **squelette** à la place de son contenu (CA-06, P-9) :
des cartes grises fixes (`onSurface` à 8 %) aux formes d'une ligne de l'app — pastille, libellé,
montant. Pas d'indicateur tournant, pas de scintillement : un squelette ne coûte presque rien à
dessiner, la transition d'entrée reste fluide.

```kotlin
val showSkeleton = rememberShowSkeleton(loading = state is Loading)
LopScreenScaffold(…) {
    if (showSkeleton) return@LopScreenScaffold skeleton(TestTags.X_LOADING)
    …
}
```

- **Pendant la transition d'entrée**, un écran qui chargeait à son ouverture garde son squelette
  même si ses données arrivent avant la fin : le contenu lourd n'est jamais construit pendant le
  glissement. Navigation tient l'écran qui entre à `STARTED` et ne le passe `RESUMED` qu'à la fin
  de son animation : c'est ce signal qui est lu.
- **Un écran sans chargement** (formulaire de création) n'a jamais de squelette.
- **Un retour** sur un écran déjà affiché n'en remontre pas : l'état est sauvegardé.
- **Un rechargement** (Mensuel, Recherche, Analyses : période, filtre ou texte changé) remet le
  squelette tout de suite, sans délai, à la place d'un « Actualisation… » ou de résultats estompés
  (P-10). Un ancien résultat n'est jamais montré sous de nouveaux critères.
- **Un ViewModel sans état de chargement** expose `isLoaded: StateFlow<Boolean>`, posé par
  `onEach { loaded.value = true }` sur sa source : son flux existant ne change ni de type, ni de
  valeur initiale, ni d'émissions (P-11).
- **Une section** qui recharge sous des contrôles toujours utilisables (onglets de Suivi) met le
  squelette à la place de la section seulement.
- Le squelette porte le repère de test de l'état de chargement de l'écran, une seule fois, et
  s'annonce « Chargement… » à TalkBack. À l'arrivée du contenu, il se dissout dessus.
- Un indicateur d'**action** en cours (Enregistrer, recherche d'icône, génération IA) reste un
  indicateur : ce n'est pas un contenu qui charge.
