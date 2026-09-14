#!/usr/bin/env python3
"""Passerelle HTTP -> adb, pour que Maestro puisse poster une notification en plein milieu d'un flow.

Pourquoi elle existe
--------------------
Le moteur JavaScript de Maestro tourne en bac a sable. Sonde du 14 septembre 2026, Maestro 2.8.0 :

    java:undefined   http:object   proc:undefined   maestro:object

`Java` etant absent, il n'y a ni `Runtime.exec` ni acces processus : impossible de lancer adb
depuis un `runScript`. Seul `http` est expose (get, put, delete, post, request). Cette passerelle
est donc le seul pont entre un flow Maestro et l'appareil.

Elle tourne sur la machine hote, la ou adb est deja installe, et n'ecoute que sur la boucle locale.

Usage
-----
    python3 scripts/adb_notification_bridge.py           # Linux / CI
    python  scripts/adb_notification_bridge.py           # Windows
    python3 scripts/adb_notification_bridge.py --port 9000

Aucun des deux noms n'est universel : Debian et Ubuntu ne fournissent que `python3`, une
installation python.org sous Windows ne fournit que `python`. D'ou les deux formes partout.

Points d'entree
---------------
    GET  /health   -> 200 si un appareil est branche ; dit aussi si l'ecoute est autorisee
    POST /grant    -> autorise l'ecoute des notifications pour LOPBudge (idempotent)
    POST /notify   -> {"title": "...", "text": "...", "tag": "..."} poste la notification

`/notify` **arme l'ecoute lui-meme** avant de poster. C'est deliberé : accorder l'acces par l'UI
oblige a piloter l'ecran systeme « Acces aux notifications », dont la mise en page varie d'un
constructeur a l'autre (Samsung One UI, Pixel, emulateur). Par adb la commande est la meme partout,
et le flow Maestro n'a aucune precondition manuelle a satisfaire.

Perimetre assume
----------------
Outil de test local. Aucune authentification : l'ecoute est volontairement limitee a 127.0.0.1,
et les champs contenant une apostrophe sont refuses parce qu'ils traversent le shell de
l'appareil entre quotes simples. Ne pas exposer ce port.
"""

import argparse
import json
import socket
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

LISTENER = "com.lop.budget/com.lop.budget.notifications.LopNotificationListenerService"

# Forme relevee sur capture reelle : titre = commercant, texte = montant puis la carte.
DEFAULT_TITLE = "MG ORGANISATION"
DEFAULT_TEXT = "6,50 € avec la carte Curve Card ••6088"
DEFAULT_TAG = "LOPTEST"


def adb(args):
    """Lance adb sans passer par un shell hote : les arguments ne sont jamais reinterpretes ici."""
    return subprocess.run(
        ["adb"] + args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def device_connected():
    result = adb(["devices"])
    lines = [line for line in result.stdout.splitlines()[1:] if line.strip().endswith("device")]
    return len(lines) > 0


def listener_enabled():
    result = adb(["shell", "settings", "get", "secure", "enabled_notification_listeners"])
    return LISTENER in (result.stdout or "")


def listener_bound():
    """Le reglage accorde ne suffit pas : Android doit encore lier le service.

    Mesure du 14 septembre 2026 (SM-S938B / Android 16) : la liaison suit le reglage en moins
    d'une seconde. Mais poster avant qu'elle soit faite perdrait la notification en silence.
    """
    result = adb(["shell", "dumpsys", "activity", "services", "com.lop.budget"])
    return "LopNotificationListenerService" in (result.stdout or "")


def ensure_listener(timeout_s=6.0):
    """Garantit que LOPBudge a le droit d'ecouter les notifications, et rend (ok, detail).

    C'est ce qui rend le montage portable : accorder l'ecoute par l'UI oblige a piloter l'ecran
    systeme « Acces aux notifications », dont la mise en page change d'un constructeur a l'autre
    (Samsung One UI vs Pixel). Par adb, la commande est la meme partout.

    L'ecriture du reglage est **asynchrone** : une relecture immediate renvoie encore l'ancienne
    valeur. Mesure du 14 septembre 2026 sur SM-S938B / Android 16 : une a deux secondes de
    latence, d'ou l'attente active ci-dessous plutot qu'un simple appel suivi d'un `get`.
    """
    already = listener_enabled()
    if not already:
        adb(["shell", "cmd", "notification", "allow_listener", LISTENER])

    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if listener_enabled() and listener_bound():
            return True, "ecoute deja autorisee" if already else "ecoute autorisee par adb"
        time.sleep(0.25)

    return False, (
        "l'ecoute n'est pas operationnelle apres %.0fs (reglage=%s, service lie=%s). "
        "Verifier `adb shell settings get secure enabled_notification_listeners`."
        % (timeout_s, listener_enabled(), listener_bound())
    )


def post_notification(title, text, tag):
    """Poste la notification et rend (ok, detail).

    Toute la commande distante tient dans UNE seule chaine : adb recolle ses arguments avec des
    espaces et le shell de l'appareil les redecoupe, donc un titre non quote partirait en morceaux.

    Pas de `-S bigtext` : ce style remplit aussi `android.bigText`, et le service d'ecoute
    concatene `android.text` et `android.bigText`. Le texte arriverait en double dans l'instantane.
    """
    for name, value in (("title", title), ("text", text), ("tag", tag)):
        if "'" in value:
            return False, "apostrophe interdite dans '%s' : le champ traverse le shell de l'appareil" % name

    remote = "cmd notification post -t '%s' '%s' '%s'" % (title, tag, text)
    result = adb(["shell", remote])
    if result.returncode != 0:
        return False, (result.stderr or result.stdout).strip()
    return True, "poste sous com.android.shell : %s / %s" % (title, text)


class BridgeHandler(BaseHTTPRequestHandler):

    def _reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self):
        if self.path != "/health":
            self._reply(404, {"ok": False, "error": "inconnu: %s" % self.path})
            return
        if not device_connected():
            self._reply(503, {"ok": False, "detail": "aucun appareil (adb devices)"})
            return
        self._reply(200, {"ok": True, "detail": "appareil branche", "listener": listener_enabled()})

    def do_POST(self):
        if not device_connected():
            self._reply(503, {"ok": False, "error": "aucun appareil branche (adb devices)"})
            return

        if self.path == "/grant":
            ok, detail = ensure_listener()
            self._reply(200 if ok else 500, {"ok": ok, "detail": detail})
            return

        if self.path == "/notify":
            try:
                payload = self._read_json()
            except ValueError as error:
                self._reply(400, {"ok": False, "error": "corps JSON invalide: %s" % error})
                return

            # Auto-armement : sans l'autorisation d'ecoute, la notification part bien mais le
            # service ne la voit jamais, et le flow echouerait plus tard sur un timeout muet.
            granted, grant_detail = ensure_listener()
            if not granted:
                self._reply(503, {"ok": False, "error": grant_detail})
                return

            ok, detail = post_notification(
                payload.get("title") or DEFAULT_TITLE,
                payload.get("text") or DEFAULT_TEXT,
                payload.get("tag") or DEFAULT_TAG,
            )
            self._reply(200 if ok else 500, {"ok": ok, "detail": detail})
            return

        self._reply(404, {"ok": False, "error": "inconnu: %s" % self.path})

    def log_message(self, fmt, *args):
        # Une ligne par requete, sur stderr, pour suivre ce que le flow declenche.
        sys.stderr.write("[bridge] %s - %s\n" % (self.address_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()

    # Sous Windows, `allow_reuse_address` laisse deux instances se lier au meme port sans erreur :
    # la plus ancienne rafle les connexions, et on croit dialoguer avec la nouvelle. Verifie le
    # 14 septembre 2026, apres avoir passe dix minutes a debugger du vieux code qui repondait.
    with socket.socket() as probe:
        probe.settimeout(0.3)
        if probe.connect_ex(("127.0.0.1", args.port)) == 0:
            sys.stderr.write(
                "[bridge] le port %d est deja pris. Arreter l'autre instance, ou --port autre.\n" % args.port
            )
            sys.exit(1)

    server = HTTPServer(("127.0.0.1", args.port), BridgeHandler)
    sys.stderr.write("[bridge] en ecoute sur http://127.0.0.1:%d (Ctrl+C pour arreter)\n" % args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write("[bridge] arret\n")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
