#!/usr/bin/env bash
#
# Lance un shard des tests instrumentes de la CI sous surveillance de l'emulateur.
#
# Usage :
#   bash scripts/ci_run_instrumented.sh <index_shard> [tc162ForceB2]
#
# Pourquoi une surveillance
# -------------------------
# Un emulateur fige, et non mort, laisse Gradle attendre la fin d'un test qui ne viendra jamais :
# run 43053856222, shard 0 bloque 22 min sur le 15e test, jusqu'a l'annulation (sinon 45 min, la
# limite du job). Un Android vivant ecrit dans son logcat plusieurs fois par seconde : un logcat
# muet depuis SILENCE_S secondes ET un adb sans reponse designent un emulateur fige. On le tue
# alors ; Gradle ecrit « Test run failed to complete » et « device '...' not found », les deux
# marqueurs que l'etape « Detect emulator lost during tests » du workflow attend pour relancer le
# shard sur un emulateur neuf. Si adb repond, seule la capture du logcat est morte : on la relance,
# l'emulateur reste.
#
# Le bloc `script:` de l'action emulateur s'execute ligne par ligne en `sh` (voir
# ci_run_maestro.sh) : la boucle et son arret vivent donc ici.
set -u

SHARD="${1:?index de shard attendu}"
FORCE_B2="${2:-false}"
SILENCE_S=90
DIAG=build/ci-diag

watch_emulator() {
  while sleep 15; do
    log=$(ls -t "$DIAG"/logcat-*.txt 2>/dev/null | head -1)
    [ -n "$log" ] || continue
    idle=$(( $(date +%s) - $(stat -c %Y "$log") ))
    [ "$idle" -ge "$SILENCE_S" ] || continue
    if timeout 15 adb shell true > /dev/null 2>&1; then
      echo "$(date -u +%T) logcat muet ${idle} s, adb repond : capture du logcat relancee" >> "$DIAG/watchdog.txt"
      bash scripts/ci_diag.sh logcat
      continue
    fi
    echo "$(date -u +%T) logcat muet ${idle} s, adb sans reponse : emulateur tue" >> "$DIAG/watchdog.txt"
    echo "::warning::Emulateur fige (logcat muet depuis ${idle} s, adb sans reponse) : arret, le workflow relance le shard."
    pkill -9 -f qemu-system || true
    return
  done
}

mkdir -p "$DIAG"
watch_emulator &
WATCHDOG=$!
trap 'kill "$WATCHDOG" 2>/dev/null || true' EXIT

./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.numShards=3 \
  -Pandroid.testInstrumentationRunnerArguments.shardIndex="$SHARD" \
  -Pandroid.testInstrumentationRunnerArguments.tc162ForceB2="$FORCE_B2"
