Développe une User Story Notion : du critère d'acceptation au code livré, couche par couche.

Entrée reçue : $ARGUMENTS

## 0. Résoudre l'entrée

L'entrée est l'adresse d'une page Notion. Récupère-la et tranche d'après sa **base parente** :

- **« User Story »** → c'est l'US à développer ;
- **« Cas de test »** → ce n'est pas une US : remonte à l'US via « US couvertes » et dis-le ;
- autre chose → arrête-toi et demande.

Lis aussi ce qui conditionne le travail :

- les pages de « Dépend de » : une dépendance qui n'est pas « Terminé » est une décision du bloc
  « À décider » ;
- la page « Notes techniques » et la propriété « Fichiers concernés », s'il y en a.

Les tests de l'US ne te concernent pas : un autre agent les écrit, avant ou après toi.

**Si le code de l'US existe déjà en partie**, ce n'est pas le bon outil : propose `/conform-us`
dans le bloc « À décider ».

## 1. Extraire la spec

Relève, sans les reformuler :

- les invariants (I-x) et ce qu'ils interdisent ;
- les critères d'acceptation (CA-xx), un par un ;
- le journal des décisions (P-x). Une « Décision » s'applique. Une « Proposition … à valider » n'est
  **pas** une décision : c'est une question pour moi ;
- le périmètre **et** le hors-périmètre, aussi contraignant que le périmètre ;
- le vocabulaire défini par l'US : le code reprend ces mots.

## 2. Lire le code réel

Lis le code avant de concevoir. Utilise `graft` pour situer les symboles et leurs appelants. Relève :

- où la fonctionnalité se branche, et ce qui existe déjà et se réutilise (use case, composant,
  requête, `TestTags`) ;
- les signatures réelles. Si l'US cite une API qui n'existe pas, c'est un écart à signaler, pas un
  nom à inventer.

Respecte l'architecture d'`AGENTS.md` :

- `data/local` → `data/repository` → `domain/usecase` → `ui` ;
- un ViewModel passe toujours par le use case existant qui couvre l'opération ;
- aucun repository « god mode », aucune couche ni API créée seulement pour faciliter un test.

## 3. Le plan détaillé : un fichier, pas la réponse

Écris-le dans `build/plans/<réf de l'US>-dev.md` (dossier ignoré par git), en langage simple.
Il contient :

- la table `CA / invariant → ce qui change → fichier et fonction → couche` ;
- le découpage en **étapes livrables** : chacune compile et laisse la suite existante verte ;
- les changements de base Room : version, migration, test de migration, schéma exporté ;
- les identifiants stables (`TestTags`) posés sur les nouveaux éléments d'écran, selon la convention
  de `TestTags.kt` ;
- les risques et ce qui reste hors périmètre.

## 4. La première réponse : courte, centrée sur ce que j'ai à décider

Elle doit tenir sur un écran. Quatre blocs, dans cet ordre, et rien d'autre :

1. **L'US** — une ligne : réf, titre, statut, nombre de CA, dépendances non terminées.
2. **En bref** — trois phrases au plus : ce que tu vas construire, en combien d'étapes, ce qui ne
   demande rien de ma part. Lien vers le plan détaillé.
3. **À décider** — uniquement ce qui bloque ou change le résultat : propositions P-x non validées,
   trous de la spec, dépendances non terminées, choix d'interface que l'US laisse ouverts. Pour
   chaque décision :
   - une question fermée ;
   - deux ou trois options, la recommandée en premier ;
   - **un exemple concret**, avec les données ou l'écran de l'app, qui montre ce que change chaque
     option.

   Pose-les aussi avec l'outil de question. Sans décision : « Rien à décider ».
4. **À savoir** — trois points au plus, une phrase chacun.

Ne mets pas dans la réponse les tables, les signatures ni le découpage détaillé : ils sont dans le
plan.

Bonne décision :

> **1. Une catégorie supprimée garde-t-elle ses transactions ?** L'US ne le dit pas.
> - A (recommandé) : elles passent « Sans catégorie ».
> - B : la suppression est refusée tant que des transactions l'utilisent.
>
> Exemple : « Courses » porte 42 transactions. Avec A, elles s'affichent « Sans catégorie » à
> l'accueil ; avec B, le bouton Supprimer affiche « 42 transactions utilisent cette catégorie ».

**Attends ma validation avant de modifier le moindre fichier de production.**

## 5. Développer

- **Étape par étape, dans l'ordre du plan.** Après chaque étape : compilation, tests de la zone
  touchée, puis suite unitaire complète. Un rouge doit être attribuable à l'étape qui l'a produit.
- **Une décision prise devient une règle écrite.** Ajoute une ligne P-xx au journal de l'US, avec
  le CA ajouté ou modifié qui en découle. Sinon aucune fiche de test ne la couvrira.
- **Un trou de la spec découvert en codant ne se tranche pas en silence.** Arrête l'étape, décris
  le trou et les options avec un exemple, et attends ma réponse.
- **Ne modifie pas un test existant pour le faire passer.** Si un test contredit l'US, c'est une
  décision : le test était-il faux, ou l'US change-t-elle le contrat ?
- **N'écris pas les tests de l'US et ne les propose pas** : un autre agent s'en charge. Les tests
  existants doivent rester verts.
- **Environnement** : préfixe Gradle `TMP`/`TEMP` d'`AGENTS.md`. Pour voir l'écran, le téléphone,
  jamais un émulateur. Installation par `adb install -r`, jamais de désinstallation.
- Ajoute les fichiers créés et modifiés à l'index git, **sans commit**.

## 6. Restitution

Même principe que la première réponse : ce qui demande mon attention d'abord.

1. **Fait** — une ligne par CA : où il est implémenté.
2. **À décider ou à faire de ma part** — décisions restantes, vérification visuelle.
3. **Preuves** — commandes lancées et résultats : compilation, suite unitaire, écran vérifié.
4. **Notion** — décisions inscrites (P-xx, CA), et proposition de passer l'US en « Testing ». Ne
   change le statut qu'après mon accord.
