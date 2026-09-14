package com.lop.budget.notifications

/**
 * Format des notifications postées depuis adb, pour injecter un paiement sur un appareil réel.
 *
 * Android attribue le paquet émetteur à partir de l'**uid appelant** : une notification postée par
 * `adb shell cmd notification post` arrive toujours sous [SHELL_PACKAGE], jamais sous celui d'un
 * portefeuille. Ce n'est pas contournable, et c'est pour cette raison qu'une source dédiée est
 * nécessaire — vérifié le 14 septembre 2026 sur Android 16 :
 *
 * ```
 * $ adb shell "cmd notification post -t 'MG ORGANISATION' LOPTEST '6,50 € avec la carte Curve Card ••6088'"
 * $ adb shell cmd notification list
 * 0|com.android.shell|2020|LOPTEST|2000
 * ```
 *
 * **Debug uniquement.** Ce parseur n'est inscrit au registre que si `BuildConfig.DEBUG`, et
 * `com.android.shell` n'est autorisé comme source que dans les mêmes conditions
 * (`SettingsRepository.isAllowedNotificationSource`). En release, la liste des sources reste celle
 * de P-8 : autoriser le shell y ouvrirait la détection à toute notification postée par adb (I-5).
 *
 * Le format délégué est celui de **Google Wallet** — titre = commerçant, texte = montant puis
 * « avec la carte X ••NNNN » — parce que c'est exactement la forme que les commandes de test
 * postent. Les extras tombent au même endroit que pour une vraie notification : `android.title` et
 * `android.text`, sans `android.bigText` tant qu'on n'utilise pas `-S bigtext`.
 */
class ShellNotificationParser(
    private val format: NotificationSourceParser = GoogleWalletParser(),
) : NotificationSourceParser {

    override fun handles(sourcePackage: String): Boolean = sourcePackage == SHELL_PACKAGE

    override fun extract(title: String, text: String): SourceExtraction = format.extract(title, text)

    companion object {
        /** Paquet sous lequel Android publie toute notification postée depuis adb (uid 2000). */
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
