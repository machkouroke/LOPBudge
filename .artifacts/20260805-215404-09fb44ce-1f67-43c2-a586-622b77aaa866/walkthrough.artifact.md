# Walkthrough - Optimisation Performance & Fiabilité

Cette mise à jour résout les problèmes de connexion prématurée de Maestro et optimise le temps de démarrage global de l'infrastructure de test.

## Améliorations de Performance

1.  **Accélération GPU Matérielle** :
    - Le script force l'utilisation de votre puce **Intel Iris Xe** (`-gpu host`).
    - Le rendu graphique n'est plus calculé par le processeur, ce qui rend l'interface Android beaucoup plus fluide et rapide à charger.
2.  **Profil Pixel 6** :
    - Utilisation d'un profil matériel plus récent, mieux adapté aux images système Android 14/15.
3.  **Fermeture Accélérée** :
    - Utilisation de `-no-snapshot-save` pour quitter instantanément l'émulateur sans attendre l'enregistrement de l'état (inutile puisque nous faisons un reset à chaque fois).

## Fiabilité du Démarrage (Triple Check)

Le script ne donne plus le "feu vert" à Maestro tant que ces 3 conditions ne sont pas remplies :
1.  **ADB Ready** : L'appareil est visible par les outils de développement.
2.  **Boot Completed** : Le système Android signale que son initialisation est finie.
3.  **Package Manager Ready** : Le script vérifie qu'il peut lister les applications installées (`pm list packages`). C'est la preuve que le système est **totalement interactif** et prêt à installer votre APK.

## Utilisation

La commande reste identique :
```powershell
.\scripts\run_tests.ps1
```

---

## Notes Techniques
- **Cold Boot** : Comme nous utilisons `-wipe-data` pour garantir un environnement propre, le premier démarrage reste un "démarrage à froid". Grâce au GPU host, ce temps est réduit au minimum possible.
- **Auto-Réparation** : Si l'AVD `Maestro_Test_API_36` est corrompu ou supprimé, le script le recréera proprement au prochain lancement.
