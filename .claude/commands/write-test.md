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

Une fois la liste établie, **annonce-la avant tout travail** : référence, titre, statut Notion,
type de test annoncé, CA couverts, et si un fichier de test correspondant existe déjà dans le dépôt.

Les statuts sont `À écrire`, `À exécuter`, `En cours`, `OK`, `KO`. Une fiche en `OK`, ou déjà
couverte par un fichier existant, ne se réimplémente pas d'office : dis-le et demande s'il faut la
refaire, la relire ou l'ignorer.

**Vérifie aussi que la référence est libre** avant de nommer un fichier ou un dossier de test : les
`réf` sont attribuées par Notion et un numéro qui semble disponible peut déjà porter une autre
fiche, parfois d'un niveau de test différent.

Si la liste dépasse ce qui tient raisonnablement en une fois, propose un découpage en lots et
laisse le choix. Ne réduis jamais le périmètre en silence.

## Pour chaque fiche de la liste

Applique rigoureusement la skill `spec-driven-tests`
(`.claude/skills/spec-driven-tests/SKILL.md`) et les règles des fichiers `AGENTS.md` du projet.

1. **Extraction de la spec** — Lis la fiche et remonte jusqu'à l'US parente pour extraire tous les
   invariants ($I-x$), critères d'acceptation ($CA-xx$), jeux de données (JDD), conventions ($P-x$)
   et oracles prescrits. Une US lue une fois sert à toutes ses fiches : ne la relis pas N fois.
2. **Vérification du code réel** — Confirme dans le code l'existence et les noms réels des classes,
   méthodes, entités et DAO. Signale immédiatement tout écart entre la spec Notion et le code.
3. **Plan d'implémentation** — Voir ci-dessous. Rien n'est écrit avant validation.

## Le plan : un seul, couvrant tout le lot

Ne demande **pas** une validation par fiche. Produis un plan unique, avec une section par fiche,
plus deux sections transverses.

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

**Forme du plan — écris en langage simple.** Commence par expliquer en prose ce que tu vas faire et
pourquoi, avant tout tableau. Définis chaque terme technique à sa première apparition (oracle, SUT,
fixture discriminante, mutation de sensibilité). Chaque écart spec/code tient en une phrase : ce que
le ticket dit, ce que le code fait, ce que ça change pour le test.

**Attends ma validation sur le plan avant d'écrire ou de modifier le moindre fichier de test.**

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
