# Contributing (LOPBudge)

Objectif: éviter la prolifération de branches tout en gardant un historique propre.

## Principes
- `main` doit rester stable.
- **1 PR = 1 sujet** (feature ou fix).
- Les branches sont **courtes** et supprimées après merge.
- Les corrections d'une PR se font sur **la même branche** (pas de nouvelle branche).

## Nommage de branches
Toujours lier au ticket Notion:
- `feat/notion-<id>-<slug>`
- `fix/notion-<id>-<slug>`
- `chore/notion-<id>-<slug>`

Exemples:
- `feat/notion-394-liquid-glass-haze`
- `fix/notion-394-hilt-compiler`

## Stratégie de merge
Recommandé:
- **Squash & merge** pour garder `main` lisible.
  - titre: `[feat] ...` ou `[fix] ...`
  - body: lien Notion + points importants

## Nettoyage des branches
Après merge:
- supprimer la branche distante.
- supprimer la branche locale: `git branch -d <branch>`

## Workflow type (commandes)
1) Partir de main à jour:
```bash
git checkout main
git pull
```

2) Créer une branche:
```bash
git checkout -b feat/notion-394-liquid-glass-haze
```

3) Commits courts et descriptifs:
```bash
git commit -am "feat(ui): ..."
```

4) Push + PR:
```bash
git push -u origin feat/notion-394-liquid-glass-haze
```

5) Si un fix est nécessaire pendant la PR:
- on **reste sur la même branche**
- on push un commit de plus

6) Merge en squash puis suppression de la branche.
