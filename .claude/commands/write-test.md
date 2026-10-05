Implémente une ou plusieurs fiches de test Notion, en utilisant les outils MCP Notion.

Entrée reçue : $ARGUMENTS

## 0. Résoudre l'entrée en une liste de fiches

L'entrée est une ou plusieurs adresses Notion, séparées par des espaces ou des retours à la ligne.
Chacune est soit une **fiche de test**, soit une **User Story**. Récupère chaque page et tranche
d'après le nom de sa **base parente**, jamais d'après l'URL :

- base parente **« Cas de test »** → c'est une fiche, elle entre telle quelle dans la liste ;
- base parente **« User Story »** → c'est une US : récupère toutes ses fiches associées.

**Pour lister les fiches d'une US, interroge la base de données.** Récupérer la base « Cas de test »
en ligne sur la page de l'US ne rend que son **schéma**, pas ses lignes — vérifié, c'est un
faux ami. La manœuvre qui marche, en mode `rows` :

```
data_source_url : celle de la base « Cas de test »
                  (lue dans le <data-source url="collection://..."> de la base en ligne)
filter          : propriété « US couvertes », type relation,
                  opérateur relation_contains, valeur exacte = l'URL de l'US
```

Chaque ligne rend `réf`, `Nom`, `Type de test`, `Statut`, `CA couverts` et `url` — tout ce qu'il
faut pour annoncer le lot sans ouvrir une page de plus.

Mélange autorisé : deux fiches et une US dans le même appel donnent une seule liste dédoublonnée.

Une fois la liste établie, elle devient le bloc « Le lot » de la première réponse (voir plus bas) :
référence, titre, statut Notion, type de test annoncé, et si un fichier de test correspondant existe
déjà dans le dépôt. Les CA couverts vont dans le plan détaillé.

Les statuts sont `À écrire`, `À exécuter`, `En cours`, `OK`, `KO`. Une fiche en `OK`, ou déjà
couverte par un fichier existant, ne se réimplémente pas d'office : c'est une décision du bloc
« À décider » (la refaire, la relire ou l'ignorer).

**Vérifie aussi que la référence est libre** avant de nommer un fichier ou un dossier de test : les
`réf` sont attribuées par Notion et un numéro qui semble disponible peut déjà porter une autre
fiche, parfois d'un niveau de test différent.

Si la liste dépasse ce qui tient raisonnablement en une fois, le découpage en lots est aussi une
décision du bloc « À décider ». Ne réduis jamais le périmètre en silence.

## Pour chaque fiche de la liste

Applique rigoureusement la skill `spec-driven-tests`
(`.claude/skills/spec-driven-tests/SKILL.md`) et les règles des fichiers `AGENTS.md` du projet.

1. **Extraction de la spec** — Lis la fiche et remonte jusqu'à l'US parente pour extraire tous les
   invariants ($I-x$), critères d'acceptation ($CA-xx$), jeux de données (JDD), conventions ($P-x$)
   et oracles prescrits. Une US lue une fois sert à toutes ses fiches : ne la relis pas N fois.
2. **Vérification du code réel** — Confirme dans le code l'existence et les noms réels des classes,
   méthodes, entités et DAO. Signale immédiatement tout écart entre la spec Notion et le code.
3. **Plan d'implémentation** — Voir ci-dessous. Rien n'est écrit avant validation.

## Le plan détaillé : un fichier, pas la réponse

Ne demande **pas** une validation par fiche. Produis un plan unique pour tout le lot et écris-le dans
`build/plans/<réf de l'US ou de la fiche>.md` (dossier ignoré par git). Il n'est **pas** recopié dans
la réponse : celle-ci le cite par un lien, et je l'ouvre si je veux le détail.

Par fiche :

- la matrice de couverture (`Scénario -> CA/Invariant -> SUT -> Niveau de test -> Oracle`) ;
- le niveau de test choisi (Unitaire, UseCase, Room composant, ViewModel, UI instrumenté, Maestro),
  en respectant la grille `AGENTS.md` ;
- la structure des fichiers et leur nommage (`given_when_then`) ;
- la stratégie de fixtures, dont les fixtures discriminantes qui prouvent la provenance des données ;
- le cadre de mocking strict : pas de `relaxed`, pas de `spyk`, pas de `any()` sur un champ métier,
  `verifyOrder` pour l'ordre causal ;
- le déroulé RED → GREEN, avec plan de mutation de sensibilité si le code est déjà vert ;
- les ambiguïtés, dépendances manquantes et éléments hors-périmètre.

Transverse au lot :

- **l'ordre d'implémentation et les dépendances** entre fiches — une fiche unitaire avant la fiche
  d'intégration qui s'appuie sur le même contrat, un prérequis de forme avant ce qui en dépend ;
- **ce qui est mis en commun et ce qui ne l'est pas.** Un helper n'est partagé que s'il est
  réellement réutilisable ; un paramètre qui conditionne une cardinalité reste déclaré localement
  dans chaque fiche, même si une autre fiche a aujourd'hui la même valeur.

Le fichier s'écrit en langage simple : prose avant tableau, jargon défini à sa première apparition,
chaque écart spec/code en une phrase (ce que le ticket dit, ce que le code fait, ce que ça change).

## La première réponse : courte, centrée sur ce que j'ai à décider

Je lis cette réponse pour donner mon feu vert. Elle doit tenir sur un écran, une trentaine de lignes
hors tableau du lot, et je dois pouvoir y répondre en quelques mots. Quatre blocs, dans cet ordre,
et rien d'autre :

1. **Le lot** — un tableau, une ligne par fiche : réf, titre court, niveau, statut Notion, fichier
   déjà présent ou non.
2. **En bref** — trois phrases au plus : ce que tu vas faire, dans quel ordre, ce qui ne demande rien
   de ma part. Lien vers le plan détaillé.
3. **À décider** — uniquement ce qui bloque le travail ou change le résultat. Pour chaque décision :
   - une question fermée, en une phrase ;
   - deux ou trois options, la recommandée en premier ;
   - **un exemple concret**, tiré du jeu de données ou de l'écran, qui montre ce que change chaque
     option. Un exemple abstrait (« la couverture serait partielle ») ne compte pas.

   Sans décision à prendre, écris « Rien à décider » et propose de démarrer. Si les décisions se
   prêtent à des options fermées, pose-les **aussi** avec l'outil de question, une par décision.
4. **À savoir** — trois points au plus : un écart spec/code ou une limite d'environnement qui change
   ce que le test prouvera, une phrase chacun. Le reste attend la restitution.

Ne mets **pas** dans la réponse : matrices, fixtures, listes de mutations, détails de montage, noms
de classes internes, définitions de jargon. Ils sont dans le plan détaillé.

Bonne décision :

> **1. Police à 200 % : sur votre téléphone ?** La fiche demande un émulateur, vous voulez votre
> téléphone.
> - A (recommandé) : je règle le téléphone sur un écran de 360 dp et une police à 200 % pendant
>   les tests, puis je remets la police à 1,15.
> - B : je marque ces cas « non exécutés ».
>
> Exemple : avec B, on ne saura pas si « Prochaine échéance » se coupe sur un petit écran.

Mauvaise décision, à ne pas reproduire :

> Écart É-3 : l'oracle de U-05 dépend de `LocalDate.now()` non injectable ; la fixture
> discriminante ne couvre pas le cas, d'où une couverture partielle de CA-08.

Elle ne pose aucune question, n'offre aucun choix, ne donne aucun exemple : je ne sais pas quoi
répondre.

**Attends ma validation avant d'écrire ou de modifier le moindre fichier de test.**

## Implémentation et restitution

Implémente fiche par fiche, dans l'ordre annoncé, et lance la suite ciblée après chaque fiche plutôt
qu'une seule fois à la fin : un rouge doit être attribuable à la fiche qui l'a produit.

Deux règles propres au traitement par lot :

- **Une anomalie par cause racine à l'échelle du lot**, pas par fiche. Si deux fiches butent sur le
  même défaut, c'est une seule ANO, citée par les deux.
- **Aucun correctif porté par une fiche ne doit refermer en silence le rouge d'une autre.** Si une
  correction rend vert un cas d'une autre fiche, dis-le explicitement et rattache-le à sa cause.

Restitue ensuite : un compte rendu par fiche selon la skill `spec-driven-tests`, puis une synthèse
du lot — fiches traitées, fiches écartées et pourquoi, rouges restants par cause, et ce qui reste à
décider.
