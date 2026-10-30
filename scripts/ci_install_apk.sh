#!/usr/bin/env bash
#
# Installe l'APK sur l'emulateur de la CI et verifie que l'installation est utilisable.
#
# Usage :
#   bash scripts/ci_install_apk.sh <apk>
#
# `-change-locale` redemarre le framework apres le premier demarrage (voir ci_wait_locale.sh), et
# `sys.boot_completed` reste a 1 pendant ce second demarrage : aucune propriete ne dit quand il est
# fini. Une installation lancee trop tot echoue de deux facons, constatees les 13, 28 et 29 octobre
# 2026 :
#   - refusee : NullPointerException sur PackageManagerInternal.freeStorage, shard mort sans flow ;
#   - « Success », mais sans dossier de donnees (logcat : « inode == 0 ») : chaque
#     `launchApp clearState` plante ensuite sur SQLITE_CANTOPEN et tous les flows du shard tombent.
# On verifie donc le resultat lui-meme : `run-as` echoue tant que le dossier de donnees n'existe pas.
set -u

apk="${1:?chemin de l APK attendu}"
package=com.lop.budget

for attempt in $(seq 6); do
  if adb install -r "$apk" && adb shell run-as "$package" true; then
    exit 0
  fi
  echo "::warning::Installation inutilisable (tentative $attempt) : le framework redemarre encore. Nouvel essai dans 10 s."
  # Desinstaller efface les donnees : sans risque sur l'AVD neuf de chaque job, jamais ailleurs
  # (le telephone de developpement porte de vraies donnees).
  if [ "${CI:-}" != true ]; then
    echo "Hors CI : pas de desinstallation, arret."
    exit 1
  fi
  adb uninstall "$package" > /dev/null 2>&1 || true
  sleep 10
done

echo "::error::$package installe sans dossier de donnees utilisable apres 6 tentatives."
exit 1
