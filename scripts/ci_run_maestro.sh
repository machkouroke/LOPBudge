#!/usr/bin/env bash
#
# Orchestration CI d'un shard Maestro : passerelle adb, sharding, execution, arret.
#
# Usage :
#   bash scripts/ci_run_maestro.sh <index_shard> [nb_shards]
#
# Pourquoi un fichier plutot que des lignes dans le workflow
# ----------------------------------------------------------
# `reactivecircus/android-emulator-runner` execute le bloc `script:` **une ligne a la fois**,
# chacune dans son propre `/usr/bin/sh -c`. Consequences, constatees le 15 septembre 2026 apres
# que les quatre shards soient tombes dessus :
#   - un `for ... done` ou un `if ... fi` est coupe en morceaux et echoue sur
#     « Syntax error: end of file unexpected (expecting "done") » ;
#   - une variable n'survit pas d'une ligne a la suivante, donc pas de `BRIDGE_PID=$!` ;
#   - c'est `sh` (dash sur Ubuntu), pas bash : meme une bashism sur une seule ligne casserait.
# Tout ce qui demande plus d'une ligne doit donc vivre ici, et le workflow n'appelle qu'un script.
#
# La passerelle est requise par TC-109 : le moteur JS de Maestro est en bac a sable, `Java` y est
# indefini, donc pas de `Runtime.exec` et aucun moyen de lancer adb depuis un `runScript`. Seul
# `http` est expose. Elle tourne donc ici, sur l'hote, la ou adb vit deja.
#
set -euo pipefail

SHARD_INDEX="${1:?index de shard attendu}"
SHARD_COUNT="${2:-4}"
BRIDGE_PORT="${BRIDGE_PORT:-8787}"
BRIDGE_URL="http://127.0.0.1:${BRIDGE_PORT}"

python3 scripts/adb_notification_bridge.py --port "$BRIDGE_PORT" > /tmp/adb-bridge.log 2>&1 &
BRIDGE_PID=$!

# Sur un runner la VM est detruite ensuite, mais en local la passerelle survivrait a un echec et
# garderait le port : chaque relance parlerait alors a l'ancienne instance sans le dire.
trap 'kill "$BRIDGE_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 30); do
    if curl -sf "$BRIDGE_URL/health" > /dev/null; then break; fi
    sleep 1
done

if ! curl -sf "$BRIDGE_URL/health"; then
    echo ""
    echo "::error::Passerelle adb injoignable sur $BRIDGE_URL, TC-109 echouerait. Journal :"
    cat /tmp/adb-bridge.log || true
    exit 1
fi
echo ""

python3 Maestro/scripts/shard_flows.py \
    --index "$SHARD_INDEX" \
    --count "$SHARD_COUNT" \
    --write-config /tmp/maestro-shard.yaml
cat /tmp/maestro-shard.yaml

JUNIT="build/maestro-junit-shard-${SHARD_INDEX}.xml"

run_shard() {
    maestro test --config /tmp/maestro-shard.yaml Maestro/ \
        --format junit \
        --output "$JUNIT" \
        --test-output-dir build/maestro-results 2>&1 | tee "$1"
}

# Vrai seulement si CHAQUE echec du shard porte la signature d'une mort du serveur Maestro sur
# l'emulateur. Constate les 22 et 23 septembre 2026 : le pilote meurt des le premier `deviceInfo`
# et les flows du shard tombent, sans avoir execute une commande. Un echec d'assertion, lui, ne
# correspond jamais et n'est donc jamais relance.
is_infra_failure() {
    python3 - "$JUNIT" "$1" build/maestro-results <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

junit, log, results = sys.argv[1], sys.argv[2], sys.argv[3]
signatures = ("DeviceServerDiedException", "StatusRuntimeException: UNAVAILABLE")
console = open(log, encoding="utf-8", errors="replace").read()
try:
    cases = list(ET.parse(junit).getroot().iter("testcase"))
except (OSError, ET.ParseError):
    sys.exit(0 if any(s in console for s in signatures) else 1)


def key(name):
    # Maestro remplace « : » par « _ » dans le dossier d'un flow : on normalise les deux cotes.
    return "".join(c if c.isalnum() else "_" for c in name)


# Le JUnit ne porte que « Unknown error » et la console ne montre que « [Failed] » : la trace de la
# mort n'est que dans le maestro.log du flow (constate sur le shard 5 du run 35895348604).
died = {
    key(p.parent.parent.name)
    for p in Path(results).glob("*/*/logs/maestro.log")
    if any(s in p.read_text(encoding="utf-8", errors="replace") for s in signatures)
}


def failure_of(case):
    return case.find("failure") if case.find("failure") is not None else case.find("error")


def is_infra(case):
    failure = failure_of(case)
    text = (failure.text or "") + (failure.get("message") or "")
    return any(s in text for s in signatures) or key(case.get("name", "")) in died


failed = [c for c in cases if failure_of(c) is not None]
sys.exit(0 if failed and all(is_infra(c) for c in failed) else 1)
PY
}

# `|| MAESTRO_EXIT=$?` plutot qu'un appel nu : sous `set -e`, un echec de Maestro couperait le
# script avant l'arret de la passerelle et masquerait le code de sortie reel.
MAESTRO_EXIT=0
run_shard /tmp/maestro-attempt-1.log || MAESTRO_EXIT=$?

# Maestro Cloud relance d'office les incidents d'infrastructure ; le CLI ne le fait pas. Une seule
# nouvelle tentative, signalee en avertissement, pour que l'incident reste visible sans rougir la CI.
if [ "$MAESTRO_EXIT" -ne 0 ] && is_infra_failure /tmp/maestro-attempt-1.log; then
    echo "::warning::Shard ${SHARD_INDEX} : le serveur Maestro de l'emulateur est mort (DeviceServerDiedException). Shard relance une fois ; un echec de test reel n'est jamais relance."
    rm -rf build/maestro-results "$JUNIT"
    MAESTRO_EXIT=0
    run_shard /tmp/maestro-attempt-2.log || MAESTRO_EXIT=$?
fi

exit "$MAESTRO_EXIT"
