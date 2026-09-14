// post_notification.js
// Demande a la passerelle locale de poster une notification de paiement sur l'appareil.
//
// Pourquoi passer par HTTP : le moteur JS de Maestro tourne en bac a sable. Sonde du
// 14 septembre 2026 sur Maestro 2.8.0 -> `java:undefined  http:object  proc:undefined`.
// Sans `Java`, pas de `Runtime.exec`, donc aucun moyen de lancer adb ici. `http` expose
// get, put, delete, post, request : c'est le seul pont disponible.
//
// Prerequis : la passerelle doit tourner.
//     python scripts/adb_notification_bridge.py
//
// Parametres recus par `env` :
//     NOTIF_TITLE, NOTIF_TEXT, NOTIF_TAG, BRIDGE_URL   (tous facultatifs)
//
// Sorties lisibles depuis le flow :
//     output.notificationPosted   true si la passerelle a bien poste
//     output.bridgeDetail         message de diagnostic, affiche en cas d'echec

var bridge = (typeof BRIDGE_URL !== 'undefined' && BRIDGE_URL) ? BRIDGE_URL : 'http://127.0.0.1:8787';

// Forme relevee sur capture reelle : titre = commercant, texte = montant puis la carte.
var title = (typeof NOTIF_TITLE !== 'undefined' && NOTIF_TITLE) ? NOTIF_TITLE : 'MG ORGANISATION';
var text = (typeof NOTIF_TEXT !== 'undefined' && NOTIF_TEXT) ? NOTIF_TEXT : '6,50 € avec la carte Curve Card ••6088';
var tag = (typeof NOTIF_TAG !== 'undefined' && NOTIF_TAG) ? NOTIF_TAG : 'LOPTEST';

output.notificationPosted = false;
output.bridgeDetail = '';

try {
    var response = http.post(bridge + '/notify', {
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: title, text: text, tag: tag })
    });

    var payload = {};
    try {
        payload = JSON.parse(response.body);
    } catch (parseError) {
        payload = { detail: 'reponse illisible: ' + response.body };
    }

    output.notificationPosted = (response.status === 200 && payload.ok === true);
    output.bridgeDetail = 'HTTP ' + response.status + ' - ' + (payload.detail || payload.error || '');
} catch (error) {
    // Cas de loin le plus frequent : la passerelle n'est pas demarree. Le flow doit echouer sur une
    // assertion lisible, pas sur une exception de script.
    output.bridgeDetail = 'passerelle injoignable sur ' + bridge +
        ' - demarrer `python scripts/adb_notification_bridge.py` (' + error + ')';
}
