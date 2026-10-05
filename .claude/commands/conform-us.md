Met en conformité le code d'une User Story Notion déjà en partie développée : audit CA par CA, structure et manques traités, défauts signalés sans être corrigés.

Entrée reçue : $ARGUMENTS

## Ce que cette commande fait, et ce qu'elle ne fait pas

Elle **met le code aux normes de l'US** sur trois plans : la structure, ce qui manque, et les points
que la spec ne tranche pas.

Elle **ne corrige aucun défaut de comportement** et **n'écrit ni ne modifie aucun test**. Un autre
agent écrit les tests, puis un autre corrige les défauts qu'ils révèlent. Corriger un défaut ici
effacerait la preuve dont ils ont besoin.

## 0. Résoudre l'entrée

L'entrée est l'adresse d'une page Notion de la base **« User Story »**. Une fiche « Cas de test » se
remonte à son US par « US couvertes ». Lis aussi :

- les fiches de test liées, en mode `rows` avec le filtre `US couvertes` / `relation_contains` =
  l'URL de l'US. Note leur statut et si un fichier de test existe : cela décide de ce que tu pourras
  prouver ;
- les anomalies rattachées (relation « Bloque » vers l'US). Une page en corbeille ne compte pas ;
- les dépendances (« Dépend de »), les « Notes techniques » et la propriété « Fichiers concernés ».

**Si aucun code de l'US n'existe**, ce n'est pas le bon outil : propose `/dev-us`.

## 1. Extraire la spec

Relève les invariants (I-x), les CA, le journal des décisions (P-x ; une « Proposition à valider »
n'est pas une décision), le périmètre et le hors-périmètre, le vocabulaire. La spec est la source de
vérité. Ni le code existant ni les tests existants ne le sont.

## 2. Auditer, CA par CA

Pour chaque CA, invariant et décision P-x, trouve le code qui le porte. Utilise `graft` pour les
symboles et leurs appelants. Range-le dans **une seule** de ces cases :

| Case | Sens | Exemple | Ce que fait la commande |
|---|---|---|---|
| **Conforme** | le code fait ce que dit la spec | CA-03 : « Mois suivant » avance d'un mois | rien |
| **Forme** | bon comportement, mauvaise structure au regard d'`AGENTS.md` ou de l'US | le ViewModel lit le repository alors qu'un use case couvre la lecture | corrige, à comportement constant |
| **Absent** | rien n'implémente le CA | aucun message « Impossible de charger » | développe, comme `/dev-us` |
| **Spec** | la spec ne tranche pas, ou le code fait autre chose, peut-être volontairement | un glissement terminé sur la ligne l'ouvre ; l'US n'en dit rien | me fait décider, puis l'inscrit dans l'US |
| **Comportement** | le code contredit un CA ou un invariant | la grille lit la date du téléphone au lieu de l'horloge injectée | **ne corrige pas** : signale, ouvre une ANO présumée si c'est prouvé |

Chaque écart tient en une phrase : ce que l'US dit, ce que le code fait, ce que ça change pour
l'utilisateur. Relève aussi le code qui dépasse le périmètre de l'US.

## 3. Les règles de chaque case

**Forme.**
- Refonte à comportement constant : signatures, découpage en use cases, ports, horloge injectée,
  types.
- Un défaut de comportement rencontré en chemin est **reconduit tel quel**, avec un commentaire qui
  nomme le CA violé. On ne le corrige pas au passage.
- La suite existante doit compter le même nombre de tests verts avant et après.

**Absent.**
- Se développe comme dans `/dev-us` : couches d'`AGENTS.md`, identifiants stables sur les nouveaux
  éléments d'écran, aucun test écrit.

**Spec.**
- Je décide.
- La décision devient une ligne P-xx du journal de l'US, avec le CA ajouté ou modifié. Sinon aucune
  fiche de test ne la couvrira.

**Comportement.**
- Ouvre une **ANO présumée**, une par cause racine, seulement si l'une de ces preuves existe :
  - un test déjà écrit qui échoue, une fois lancé ;
  - une exécution rapide qui montre le défaut : le test de la fiche, ou l'app sur le téléphone ;
  - une analyse statique sans ambiguïté, qui cite la ligne fautive.
- L'ANO suit le modèle des ANO existantes : Résumé, Localisation, Reproduction (avec le **niveau
  de preuve**), Correctif attendu, Couverture de test. Statut « Backlog », relation « Bloque » vers
  l'US.
- Sans preuve, pas d'ANO : le soupçon va dans le plan, pour l'agent de test.

## 4. Le plan détaillé : un fichier, pas la réponse

Écris-le dans `build/plans/<réf de l'US>-conformite.md` (dossier ignoré par git), en langage simple.
Il contient :

- le tableau d'audit complet : CA, case, preuve dans le code (`fichier:ligne`), action prévue ;
- les refontes de forme et les développements prévus, en étapes ;
- les ANO présumées à ouvrir, avec leur niveau de preuve ;
- les soupçons sans preuve, à l'intention de l'agent de test.

## 5. La première réponse : courte, centrée sur ce que j'ai à décider

Elle doit tenir sur un écran. Quatre blocs, et rien d'autre :

1. **L'US** — une ligne : réf, titre, statut, fiches de test et leur statut, ANO ouvertes.
2. **L'audit en chiffres** — une ligne par case : nombre de CA et deux exemples parlants.
   Exemple : « Comportement : 2 — la grille ignore l'horloge injectée ; un jour sélectionné perd son
   repère aujourd'hui. » Lien vers le plan détaillé.
3. **À décider** — les écarts de la case « Spec », et les arbitrages d'ordre s'il y en a. Pour chaque
   décision :
   - une question fermée ;
   - deux ou trois options, la recommandée en premier ;
   - **un exemple concret**, avec les données ou l'écran de l'app.

   Pose-les aussi avec l'outil de question.
4. **À savoir** — trois points au plus : par exemple les ANO présumées que tu ouvriras, ou ce que
   l'audit n'a pas pu trancher.

Bonne décision :

> **1. Un glissement qui se termine sur la ligne ouvre le détail. On le garde ?** L'US n'en parle pas.
> - A (recommandé) : non, seul un toucher ouvre le détail.
> - B : oui, c'est le comportement de l'accueil.
>
> Exemple : on glisse pour payer E2 mais on relâche avant le seuil. Avec A, rien ne se passe ; avec
> B, le détail de E2 s'ouvre.

**Attends ma validation avant de modifier le moindre fichier ou d'ouvrir une ANO.**

## 6. Exécuter et restituer

- Ordre : la forme, puis les décisions de spec inscrites, puis les absents, puis les ANO présumées.
- Après chaque étape : compilation, puis suite unitaire complète. Ajoute la suite instrumentée si
  l'interface a bougé. Aucun test existant ne doit passer du vert au rouge.
- Environnement : préfixe Gradle `TMP`/`TEMP` d'`AGENTS.md`. Le téléphone, jamais un émulateur.
  `adb install -r`, jamais de désinstallation.
- Ajoute les fichiers créés et modifiés à l'index git, **sans commit**.

Restitution, ce qui demande mon attention d'abord :

1. **Reste à décider de ma part.**
2. **Pour l'agent de test** — ANO présumées (liens), soupçons sans preuve, CA ajoutés ou modifiés.
3. **Mis en conformité** — une ligne par écart traité : case, ce qui a changé, preuve que rien n'a
   régressé.
4. **Preuves** — commandes et résultats.
5. **Notion** — ANO ouvertes, P-xx et CA inscrits. Proposition de statut pour l'US, appliquée
   seulement après mon accord.
