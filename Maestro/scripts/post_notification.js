// post_notification.js
// Demande a la passerelle locale de poster une notification de paiement sur l'appareil.
//
// Pourquoi passer par HTTP : le moteur JS de Maestro tourne en bac a sable. Sonde du
// 14 septembre 2026 sur Maestro 2.8.0 -> `java:undefined  http:object  proc:undefined`.
// Sans `Java`, pas de `Runtime.exec`, donc aucun moyen de lancer adb ici. `http` expose
// get, put, delete, post, request : c'est le seul pont disponible.
//
// Prerequis : la passerelle doit tourner.
//     python3 scripts/adb_notification_bridge.py   (Linux/CI)
//     python  scripts/adb_notification_bridge.py   (Windows)
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

// Le script echoue en levant, pas en publiant un booleen a faux. Un `assertTrue` sur un booleen
// affiche « false is true » et ne dit pas pourquoi : verifie le 14 septembre 2026, ou un flow a
// echoue dix minutes durant sans reveler que la passerelle n'etait simplement pas demarree.
var response;
try {
    response = http.post(bridge + '/notify', {
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: title, text: text, tag: tag })
    });
} catch (error) {
    throw new Error(
        'Passerelle injoignable sur ' + bridge + '. Demarrer, dans un terminal a part : ' +
        '`python3 scripts/adb_notification_bridge.py` sous Linux, `python ...` sous Windows. ' +
        'Puis relancer. (' + error + ')'
    );
}

var payload = {};
try {
    payload = JSON.parse(response.body);
} catch (parseError) {
    throw new Error('Reponse illisible de la passerelle (HTTP ' + response.status + ') : ' + response.body);
}

if (response.status !== 200 || payload.ok !== true) {
    throw new Error(
        'La passerelle n\'a pas poste la notification (HTTP ' + response.status + ') : ' +
        (payload.error || payload.detail || 'sans detail')
    );
}

output.notificationPosted = true;
output.bridgeDetail = payload.detail;
