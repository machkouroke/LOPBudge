#!/usr/bin/env bash
#
# Attend que l'emulateur de la CI ait applique sa langue, puis la verifie.
#
# Usage :
#   bash scripts/ci_wait_locale.sh [locale]     # defaut : fr-FR
#
# L'app et ses tests sont en francais par choix (13 octobre 2026) : l'emulateur doit l'etre
# aussi, sinon les composants Material (calendrier, etc.) s'affichent en anglais. La langue est
# posee par l'option `-change-locale` de l'emulateur, qui redemarre le framework apres le
# premier demarrage : le runner peut donc lancer le `script:` pendant ce second demarrage.
#
# Avant ce script, les `-prop persist.sys.*` du job Maestro etaient refuses par l'emulateur
# (« only 'qemu.*' properties are supported ») et la CI tournait en en-US sans le dire.
# Echouer ici vaut mieux qu'un rouge de test dont la cause serait la langue.
set -u

locale="${1:-fr-FR}"
config="${locale%%-*}-r${locale#*-}" # fr-FR -> fr-rFR, forme de `am get-config`

for _ in $(seq 90); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] &&
    adb shell am get-config 2>/dev/null | grep -qE -- "[ -]$config[-,]" &&
    adb shell pm path android > /dev/null 2>&1; then
    echo "Emulateur en $locale."
    exit 0
  fi
  sleep 2
done

echo "::error::L'emulateur n'est pas passe en $locale en 3 minutes."
adb shell am get-config 2>&1 | head -3
exit 1
