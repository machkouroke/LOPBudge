Lis la fiche de test Notion disponible à l'adresse suivante (en utilisant les outils MCP Notion) : $ARGUMENTS

En appliquant rigoureusement les directives de la skill `spec-driven-tests` (`.claude/skills/spec-driven-tests/SKILL.md`) et les règles des fichiers `AGENTS.md` du projet :

1. **Extraction de la spec** : Lis la fiche de test et remonte jusqu'à la User Story (US) parente dans Notion si nécessaire pour extraire tous les invariants ($I-x$), critères d'acceptation ($CA-xx$), jeux de données (JDD) et oracles prescrits.
2. **Vérification du code réel** : Confirme dans le code (`find_declaration`, `grep`) l'existence et les noms réels des classes, méthodes, entités et DAO. Signale immédiatement tout écart entre la spec Notion et le code.
3. **Plan d'implémentation** : Avant d'écrire du code, propose un plan d'implémentation détaillé incluant :
   - La matrice de couverture (`Scénario -> CA/Invariant -> SUT -> Niveau de test -> Oracle`).
   - Le niveau de test choisi (Unitaire, UseCase, Room composant, ViewModel, UI instrumenté, Maestro) en respectant la grille `AGENTS.md`.
   - La structure des fichiers de test et leur nommage (`given_when_then`).
   - La stratégie de fixtures (dont fixtures discriminantes pour valider la provenance des données).
   - Le cadre de mocking strict (pas de `relaxed`, pas de `spyk`, pas de `any()` sur les champs métier, `verifyOrder` pour l'ordre causal).
   - Le déroulé RED -> GREEN (avec plan de mutation de sensibilité si le code est déjà vert).
   - Les ambiguïtés, dépendances manquantes ou éléments hors-périmètre.

**Forme du plan — écris en langage simple.** Commence par expliquer en prose ce que tu vas faire et pourquoi, avant tout tableau. Définis chaque terme technique à sa première apparition (oracle, SUT, fixture discriminante, mutation de sensibilité). Chaque écart spec/code tient en une phrase : ce que le ticket dit, ce que le code fait, ce que ça change pour le test.

Attends ma validation sur le plan d'implémentation avant d'écrire ou de modifier les fichiers de test.
