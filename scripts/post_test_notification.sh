#!/usr/bin/env bash
#
# Poste une notification de paiement sur l'appareil branche, pour tester la detection a la main.
# Equivalent bash de post_test_notification.ps1 : le .ps1 est pour le poste Windows, celui-ci pour
# les runners CI. Garder les deux alignes.
#
# Android attribue le paquet emetteur a partir de l'uid appelant : la notification arrive donc sous
# `com.android.shell` et non sous celui d'un portefeuille. C'est ShellNotificationParser qui la
# prend en charge, et il n'est inscrit qu'en build debug. Sur un APK release elle est ecartee par
# le filtre de sources (P-8) : c'est voulu.
#
# Usage :
#   ./scripts/post_test_notification.sh --grant                  # une fois par appareil
#   ./scripts/post_test_notification.sh
#   ./scripts/post_test_notification.sh "CARREFOUR CITY" "12,50 EUR avec la carte Visa 4321"
#
set -euo pipefail

LISTENER='com.lop.budget/com.lop.budget.notifications.LopNotificationListenerService'

if [ "${1:-}" = '--grant' ]; then
    adb shell cmd notification allow_listener "$LISTENER"
    echo "Ecoute autorisee pour $LISTENER"
    echo "Pense a activer la detection dans les Reglages de l'app : inactive par defaut (I-5)."
    exit 0
fi

# Forme relevee sur capture reelle le 14 septembre 2026 : titre = commercant, texte = montant puis
# « avec la carte <nom> ••<masque> ».
TITLE="${1:-MG ORGANISATION}"
TEXT="${2:-6,50 € avec la carte Curve Card ••6088}"
TAG="${3:-LOPTEST}"

if [ -z "$(adb devices | sed -n '2p')" ]; then
    echo "Aucun appareil branche. Verifie 'adb devices'." >&2
    exit 1
fi

case "$TITLE$TAG$TEXT" in
    *\'*)
        echo "Apostrophe interdite : les arguments traversent le shell de l'appareil entre quotes simples." >&2
        exit 1
        ;;
esac

# Toute la commande distante tient dans UNE seule chaine : adb recolle ses arguments avec des
# espaces et le shell de l'appareil les redecoupe, donc un titre non quote partirait en morceaux.
#
# Pas de `-S bigtext` : ce style remplit aussi `android.bigText`, et le service d'ecoute concatene
# `android.text` et `android.bigText`. Le texte arriverait en double dans l'instantane.
adb shell "cmd notification post -t '$TITLE' '$TAG' '$TEXT'" > /dev/null

echo "Notification postee sous com.android.shell :"
echo "  titre : $TITLE"
echo "  texte : $TEXT"
echo
echo "Attendu : une proposition dans la boite de reception, et le compteur d'en-tete incremente."
echo "Note : 'cmd notification' n'a pas de sous-commande d'annulation, balaye-la a la main."
