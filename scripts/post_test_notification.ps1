<#
.SYNOPSIS
    Poste une notification de paiement sur l'appareil branché, pour tester la détection à la main.

.DESCRIPTION
    Android attribue le paquet émetteur à partir de l'uid appelant : la notification arrive donc
    sous `com.android.shell` et non sous celui d'un portefeuille. C'est `ShellNotificationParser`
    qui la prend en charge, et il n'est inscrit qu'en build **debug**. Sur un APK release, la
    notification est écartée par le filtre de sources (P-8) — c'est voulu.

    Prérequis, une seule fois par appareil :
        .\scripts\post_test_notification.ps1 -Grant
    puis, dans l'app, activer la détection dans les Réglages — elle est inactive par défaut (I-5).

.PARAMETER Title
    Le commerçant. Chez Google Wallet c'est le titre qui le porte.

.PARAMETER Text
    Le corps : montant, puis éventuellement « avec la carte <nom> ••<masque> ».

.EXAMPLE
    .\scripts\post_test_notification.ps1
    Poste la capture Google Wallet réelle du 14 septembre 2026.

.EXAMPLE
    .\scripts\post_test_notification.ps1 -Title 'CARREFOUR CITY' -Text '12,50 € avec la carte Visa ••4321'
#>
[CmdletBinding()]
param(
    [string] $Title = 'MG ORGANISATION',
    [string] $Text = '6,50 € avec la carte Curve Card ••6088',
    [string] $Tag = 'LOPTEST',
    [switch] $Grant
)

$ErrorActionPreference = 'Stop'

# adb reçoit du texte accentué (« € », « •• ») : sans ça il arrive mutilé sur l'appareil.
$OutputEncoding = [System.Text.Encoding]::UTF8

$listener = 'com.lop.budget/com.lop.budget.notifications.LopNotificationListenerService'

if ($Grant) {
    adb shell cmd notification allow_listener $listener
    Write-Host "Ecoute autorisee pour $listener"
    Write-Host 'Pense a activer la detection dans les Reglages de l app : elle est inactive par defaut (I-5).'
    return
}

$connected = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\bdevice\s*$' }
if (-not $connected) {
    throw "Aucun appareil branche. Verifie 'adb devices'."
}

if ("$Title$Tag$Text" -match "'") {
    throw 'Apostrophe interdite : les arguments traversent le shell de l appareil entre quotes simples.'
}

# Toute la commande distante tient dans UNE seule chaine : adb recolle ses arguments avec des
# espaces et le shell de l appareil les redecoupe, donc un titre non quote partirait en morceaux.
# `-f` plutot que l interpolation : un montant en dollars contient un `$`, que PowerShell
# prendrait pour une variable.
#
# Pas de `-S bigtext` : ce style remplit aussi `android.bigText`, et le service d ecoute concatene
# `android.text` et `android.bigText`. Le texte arriverait en double dans l instantane.
$remote = "cmd notification post -t '{0}' '{1}' '{2}'" -f $Title, $Tag, $Text
adb shell $remote | Out-Null

Write-Host 'Notification postee sous com.android.shell :'
Write-Host "  titre : $Title"
Write-Host "  texte : $Text"
Write-Host ''
Write-Host "Attendu : une proposition dans la boite de reception, et le compteur d en-tete incremente."
Write-Host "Note : 'cmd notification' n a pas de sous-commande d annulation, balaye la notification a la main."
