# LOPBudge - Scripts d'Automatisation des Tests

Ce répertoire contient les outils pour automatiser le cycle de build et de test Maestro.

## 🚀 Script Principal : `run_tests.ps1`

Ce script PowerShell gère tout le cycle de vie du test : de la compilation à l'exécution sur émulateur.

### Fonctionnalités
- **Build Auto** : Compile la version la plus récente de l'APK debug.
- **Gestion AVD** : Crée automatiquement l'émulateur `Maestro_Test_API_36` s'il n'existe pas.
- **Mode Instant-Reload** : Détecte si l'émulateur tourne déjà pour sauter la phase de boot (gain de 2-3 min).
- **Clean State** : Réinitialise les données de l'application (`pm clear`) avant chaque test.
- **Reporting** : Génère un rapport HTML détaillé et des captures d'écran dans `maestro_results/`.
- **Chronomètre** : Affiche le temps total d'exécution.

### Utilisation

#### 1. Mode Développement (Recommandé)
L'émulateur reste ouvert pour que le prochain lancement soit instantané.
```powershell
.\scripts\run_tests.ps1
```

#### 2. Mode Nettoyage (Fin de session)
L'émulateur est fermé immédiatement après les tests pour libérer la RAM.
```powershell
.\scripts\run_tests.ps1 -Close
```

---

## ⚓ Automatisation Git (Hook pre-push)

Un hook Git a été installé dans `.git/hooks/pre-push`. 

**Son rôle** : 
Dès que vous tapez `git push`, ce script se lance automatiquement. Si les tests Maestro échouent, le push est annulé. Cela garantit que vous ne poussez jamais de code cassé sur le dépôt.

---

## 🔍 Débogage des tests
Si un test échoue :
1. Allez dans le dossier `maestro_results/`.
2. Ouvrez `report.html` pour voir l'étape exacte du crash.
3. Consultez les captures d'écran (`.png`) pour voir l'état de l'interface à ce moment-là.

---

## ⚙️ Configuration Technique
- **AVD Name** : `Maestro_Test_API_36`
- **Profil** : Pixel 6
- **Accélération** : GPU Host (Intel Iris Xe)
- **RAM** : 3 Go
