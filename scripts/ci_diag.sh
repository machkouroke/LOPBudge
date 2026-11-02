#!/usr/bin/env bash
#
# Diagnostics de l'emulateur de la CI, pour comprendre une mort silencieuse : port 5554 ferme,
# « device not found », aucun message (runs des 30 et 31 octobre 2026, pendant le redemarrage de
# -change-locale et une fois en cours de tests). Tout est ecrit sous build/ci-diag, que le workflow
# publie en artefact quand l'etape de l'emulateur echoue.
#
# Usage :
#   bash scripts/ci_diag.sh start    # avant l'emulateur : memoire et charge de l'hote toutes les 5 s
#   bash scripts/ci_diag.sh logcat   # emulateur demarre : logcat complet dans un fichier de l'hote,
#                                    # lisible meme apres la mort de l'emulateur
#   bash scripts/ci_diag.sh collect  # apres : noyau de l'hote (tueur OOM), rapports de plantage
#
# Les processus de fond ont leurs flux rediriges : l'etape qui les lance rend la main aussitot.
set -u

out=build/ci-diag
mkdir -p "$out"

case "${1:-}" in
  start)
    nohup bash -c 'while true; do
        date -u +%T; uptime; free -m
        ps -eo pid,rss,pcpu,etime,comm --sort=-rss | head -6
        q=$(pgrep -f qemu-system | head -1)
        [ -n "$q" ] && ps -L -o tid,stat,pcpu,wchan:24,comm -p "$q" | sort -k3 -nr | head -12
        echo
        sleep 5
      done' > "$out/host.txt" 2>&1 < /dev/null &
    ;;
  logcat)
    # Un fichier par tentative : le workflow peut relancer l'emulateur une fois.
    nohup adb logcat -v threadtime -b all > "$out/logcat-$(date -u +%H%M%S).txt" 2>&1 < /dev/null &
    ;;
  collect)
    sudo dmesg -T > "$out/dmesg.txt" 2>&1 || dmesg > "$out/dmesg.txt" 2>&1
    adb devices -l > "$out/adb-devices.txt" 2>&1
    pgrep -af 'emulator|qemu' > "$out/emulator-processes.txt" 2>&1
    cp -r /tmp/android-runner "$out/" 2>/dev/null
    cp "$HOME/.android/avd/test.avd/config.ini" "$out/avd-config.ini" 2>/dev/null
    ls -la "$out" > "$out/index.txt"
    ;;
  *)
    echo "usage : ci_diag.sh start|logcat|collect" >&2
    exit 2
    ;;
esac
exit 0
