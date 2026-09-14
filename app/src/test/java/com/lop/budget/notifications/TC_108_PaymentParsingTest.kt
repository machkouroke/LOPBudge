package com.lop.budget.notifications

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * TC-108 — Analyse des notifications de paiement : montant, libellé, clé de regroupement.
 * **Niveau unitaire JVM pur.** Ni Android, ni Room, ni réseau, ni doublure.
 *
 * Chaîne exercée : `PaymentNotificationParser.parse` / `.dedupeKey` (réels) →
 * `HeuristicNotificationClassifier` (réel) → `GoogleWalletParser` / `SamsungWalletParser` (réels).
 * Aucun mock : tous ces collaborateurs sont des calculs purs, donc la politique MockK de
 * `app/src/test/AGENTS.md` n'a rien à arbitrer ici.
 *
 * Source de vérité : CA-06, CA-07, CA-08, CA-09, CA-11, CA-14, CA-21 et les invariants I-1, I-3,
 * I-10 de l'US LOP-54, plus les conventions P-1 (centimes entiers) et P-2 (clé = paquet, centimes,
 * devise, texte normalisé).
 * **Le comportement actuel du parseur et du classifieur ne constitue pas l'oracle.** Le seuil de
 * confiance, la liste de mots négatifs et la valeur absolue appliquée au montant sont des choix
 * d'implémentation. Les attendus ci-dessous viennent de la fiche, jamais du code.
 *
 * ### cas → CA / invariant → production
 * ```
 * T-01  N-GW-1    CA-06, CA-07, P-12   GoogleWalletParser.extract — capture réelle
 * T-01  N-GW-2    CA-06, CA-07, P-12   GoogleWalletParser.extract — capture réelle
 * T-01  N-SW-1    CA-07 (I-1)          SamsungWalletParser.extract — forme décrite par la fiche
 * T-01  N-SW-2    CA-06, CA-07, P-12   SamsungWalletParser.extract — capture réelle
 * T-02  N-PROMO   CA-09 (I-1)          parse — rejet d'une promotion
 * T-02  N-RELEVE  CA-09 (I-1)          parse — rejet d'un relevé de solde
 * T-02  N-PLAFOND CA-09 (I-1)          parse — rejet d'une alerte de plafond
 * T-03  N-SOLDE   T-03 fiche, CA-11    parse — paiement citant le solde restant
 * T-04  N-BRUIT   CA-11 (P-1)          parse — bruit numérique (carte, commande)
 * T-05  N-REMB    CA-11                parse — un crédit ne produit pas de dépense
 * T-06  N-SANS    CA-08 (I-1)          parse — aucun montant exploitable
 * T-06  N-VIDE    CA-08 (I-1)          parse — instantané dégénéré
 * T-06  N-HORS    T-06 fiche, P-1      parse — montant hors capacité
 * T-07  N-125/1250 CA-14 (I-3, I-7)    dedupeKey — écritures équivalentes
 * ```
 *
 * ### Écarts fiche / code relevés avant écriture
 * - La fiche annonce un prérequis (« tant que `parse` prend un `StatusBarNotification` et un
 *   `Context` ») : **il est levé**. `NotificationSnapshot`, `ParsedPayment`, `ParseResult`,
 *   `parse(snapshot)` et `dedupeKey(pkg, payment)` existent sans aucun type Android (CA-25, I-10).
 * - `parse` est `suspend` (le classifieur l'est) : appel sous `runTest`, aucun oracle déplacé.
 * - En production, `NotificationModule` câble un classifieur **composite** heuristique + ML Kit.
 *   Cette branche ML Kit est hors périmètre ici : la fiche désigne `NotificationClassifier.kt`.
 * - T-03 exige `Payment` là où CA-09 se contenterait d'« une proposition », ce que `Uncertain` est
 *   aussi. Arbitrage tranché le 14 septembre 2026 : **la fiche fait foi**, on attend `Payment`.
 *   Ce cas ne prouve donc **pas** une violation de CA-09 (voir ANO-C).
 * - N-GW-1 et N-GW-2 portent des **captures réelles** (14 septembre 2026). Les textes inventés par
 *   la première version de la fiche (« Paiement effectué » / « 12,50 € chez Carrefour ») ne sont
 *   pas ce que Google Wallet émet : la fiche Notion a été corrigée en conséquence, et N-SW-2 y a
 *   été ajouté comme unique capture Samsung disponible.
 * - Le refactoring P-12 (un parseur par source) a été demandé et livré le 14 septembre 2026, après
 *   cette campagne. Il ferme ANO-A, ANO-F et ANO-G ; les quatre autres restent ouvertes et rouges.
 *
 * ### Captures réelles disponibles au 14 septembre 2026
 * ```
 * Google Wallet   titre « MG ORGANISATION »    texte « 6,50 € avec la carte Curve Card ••6088 »
 * Google Wallet   titre « SP HOLY ENERGY FR »  texte « 40,99 € avec la carte Revolut Visa ••5239 »
 * Samsung Wallet  titre « Curve Card »         texte « SAS  LEGADIS 5,61 € »
 * ```
 * La capture Samsung **confirme la forme de N-SW-1** : carte au titre, commerçant puis montant au
 * texte. Le JDD de la fiche est donc réaliste sur cette source.
 *
 * ### ANO de cette campagne — une par cause racine
 * Trois d'entre elles ont été **fermées le 14 septembre 2026** par le refactoring P-12 (un parseur
 * par source). Elles sont conservées ici : ce sont elles qui ont motivé le refactoring, et leur
 * fermeture est la raison pour laquelle les cas correspondants sont verts.
 *
 * - **ANO-A — aucune règle de format par source (CA-07) — FERMÉE par P-12.** Une seule heuristique
 *   devinait le format : elle retenait le titre comme libellé sauf s'il ressemblait à un nom de
 *   portefeuille. Chaque source déclare désormais son format ; Google pose « le titre est le
 *   commerçant » au lieu de le supposer. N-GW-1 et N-GW-2 sont verts.
 * - **ANO-B — rejet d'un paiement sans mot-clé positif (CA-06, CA-09, cas N-SW-1) — OUVERTE.** Le
 *   classifieur exige un mot-clé positif au-dessus du seuil ; « Visa ••1234 / Monoprix 34,90 € »
 *   n'en contient aucun et se fait rejeter. **Atteignable** : N-SW-2 n'est sauvé que parce que la
 *   carte s'appelle « Curve **Card** ». Le sort d'un paiement dépend donc du nom de la carte.
 *   Hors du périmètre de P-12 : c'est le classifieur, pas le format.
 * - **ANO-C — un paiement citant le solde est déclassé en `Uncertain` (T-03, P-7) — OUVERTE.**
 *   **CA-09 n'est pas violé** : il exige « une proposition », et `Uncertain` en est une au sens de
 *   CA-10. Ce qui est violé est l'oracle de la fiche, et la conséquence utilisateur relève de P-7 :
 *   une incertaine n'émet aucune notification LOPBudge, donc le paiement passe inaperçu.
 * - **ANO-D — montant tronqué au lieu d'être rejeté (T-06, P-1) — OUVERTE, délibérément.**
 *   `\d{1,6}` lit « 123456789012,99 € » comme « 123456 » et produit 12 345 600 c. **I-3 ne
 *   l'interdit pas littéralement** — sa liste vise l'arrondi flottant, la conversion par
 *   représentation textuelle et le repli sur zéro, et aucun des trois n'est une troncature. Le
 *   fondement est P-1, « au lieu de produire un montant approché ». La borne a été **reconduite
 *   telle quelle** dans `AmountText` : la corriger demande de trancher un plafond métier et un
 *   motif de rejet, ce qui n'était pas dans le périmètre du refactoring.
 * - **ANO-E — la clé de regroupement embarque l'écriture du montant (CA-14, I-7) — OUVERTE.**
 *   `normalizeForDedupe` conserve les chiffres du texte brut : « 12 5 » et « 12 50 » donnent deux
 *   clés pour un même paiement.
 * - **ANO-F — montant lu sur la concaténation titre + texte (CA-11) — FERMÉE par P-12.**
 *   L'extraction avait lieu avant toute connaissance de la source, donc sur « titre • texte » : le
 *   masque de carte que Samsung place au titre était capté comme montant. Chaque parseur lit
 *   désormais le montant **dans le champ que son format désigne** — le texte, jamais le titre.
 * - **ANO-G — le masque de carte était perdu (CA-06) — FERMÉE par P-12.** L'ancienne expression
 *   capturait `[^•
,]+` et s'arrêtait au premier « • ». Le format Google lit désormais tout ce
 *   qui suit « avec la carte », masque compris : le masque est une donnée conservée sur la
 *   proposition, pas du bruit.
 *
 * ### Hors périmètre, porté ailleurs
 * Ce que le service Android transmet réellement et le rappel système · ce qui est écrit en base
 * (fiche d'intégration) · réglages, autorisations et fenêtre de regroupement (fiche du use case de
 * détection) · le filtrage des sources autorisées, qui vit dans `HandlePaymentNotificationUseCase`
 * et non dans le parseur · la branche ML Kit du classifieur composite · multilingue et couverture
 * de toutes les banques (hors périmètre de l'US).
 *
 * ### Règle d'oracle appliquée partout
 * Quand la fiche donne une valeur exacte, égalité avec un **littéral en centimes** écrit à la main.
 * Quand elle n'en donne pas, on assert la propriété que le CA impose au champ. Aucun des cinq
 * champs de `ParsedPayment` n'est laissé sans assertion, et aucun attendu n'est calculé par une
 * fonction de production — un oracle qui appellerait `Format.centsOrNull` serait tautologique.
 */
class PaymentParsingTest {

    private val paris = ZoneId.of("Europe/Paris")

    /** Horodatage de détection : constante nommée, jamais l'horloge courante (I-11, AGENTS §7). */
    private val detectedAt = ZonedDateTime.of(2026, 9, 11, 21, 0, 0, 0, paris).toInstant().toEpochMilli()

    // --- Corpus d'instantanés (JDD de la fiche), déclaré ici pour rester lisible sur place ---

    /** Captures réelles relevées le 14 septembre 2026 : chez Google, le commerçant est au **titre**. */
    private val nGw1 = NotificationSnapshot(GOOGLE_WALLET, "SP HOLY ENERGY FR", "40,99 € avec la carte Revolut Visa ••5239", detectedAt)
    private val nGw2 = NotificationSnapshot(GOOGLE_WALLET, "MG ORGANISATION", "6,50 € avec la carte Curve Card ••6088", detectedAt)

    /** Forme décrite par la fiche : carte au titre, commerçant puis montant au texte. */
    private val nSw1 = NotificationSnapshot(SAMSUNG_WALLET, "Visa ••1234", "Monoprix 34,90 €", detectedAt)

    /** Même forme, capture réelle du 14 septembre 2026. Deux espaces dans « SAS  LEGADIS », comme à l'écran. */
    private val nSw2 = NotificationSnapshot(SAMSUNG_WALLET, "Curve Card", "SAS  LEGADIS 5,61 €", detectedAt)
    private val nBruit = NotificationSnapshot(GOOGLE_WALLET, "Paiement", "Paiement de 8,00 € carte ••4321 commande 90210", detectedAt)
    private val nSolde = NotificationSnapshot(GOOGLE_WALLET, "Paiement", "15,00 € payés, solde restant 240,00 €", detectedAt)
    private val nPromo = NotificationSnapshot(GOOGLE_WALLET, "Offre", "10 % de remise chez Fnac", detectedAt)
    private val nReleve = NotificationSnapshot(GOOGLE_WALLET, "Votre solde", "Solde disponible 240,00 €", detectedAt)
    private val nPlafond = NotificationSnapshot(GOOGLE_WALLET, "Plafond", "Plafond mensuel de 2 000,00 € atteint", detectedAt)
    private val nRemb = NotificationSnapshot(GOOGLE_WALLET, "Remboursement", "Remboursement de 12,50 € de Carrefour", detectedAt)
    private val nSans = NotificationSnapshot(GOOGLE_WALLET, "Paiement effectué", "Paiement accepté", detectedAt)
    private val nVide = NotificationSnapshot(GOOGLE_WALLET, "", "", detectedAt)
    private val n125 = NotificationSnapshot(GOOGLE_WALLET, "Paiement", "12,5 € chez Carrefour", detectedAt)
    private val n1250 = NotificationSnapshot(GOOGLE_WALLET, "Paiement", "12,50 € chez Carrefour", detectedAt)
    private val nHors = NotificationSnapshot(GOOGLE_WALLET, "Paiement", "123456789012,99 € chez Carrefour", detectedAt)

    private lateinit var defaultLocale: Locale
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var parser: PaymentNotificationParser

    /** Locale et fuseau forcés : la lecture des séparateurs décimaux en dépend (montage de la fiche). */
    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        defaultTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.FRANCE)
        TimeZone.setDefault(TimeZone.getTimeZone(paris))
        parser = PaymentNotificationParser(HeuristicNotificationClassifier())
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        TimeZone.setDefault(defaultTimeZone)
    }

    // --- T-01 — un paiement reconnu produit montant, libellé et carte exploitables (CA-06, CA-07) ---

    @Test
    fun given_capture_google_wallet_commercant_au_titre_when_parse_then_montant_et_carte_du_texte() = runTest {
        val payment = paymentOf("N-GW-1", "CA-06", parser.parse(nGw1))

        assertEquals("N-GW-1 — CA-06/I-3 : montant en centimes entiers, lu dans le texte", 4099L, payment.amountCents)
        assertEquals("N-GW-1 — CA-06 : devise détectée depuis « € »", "EUR", payment.currency)
        assertEquals("N-GW-1 — CA-07/P-12 : libellé = commerçant, que le format Google place au titre", "SP HOLY ENERGY FR", payment.label)
        assertEquals("N-GW-1 — CA-06 : nom de carte repris en entier, masque compris", "Revolut Visa ••5239", payment.cardName)
        assertTrue("N-GW-1 — P-2 : `normalizedText` alimente la clé de regroupement, il ne peut pas être vide", payment.normalizedText.isNotBlank())
    }

    @Test
    fun given_capture_reelle_google_wallet_when_parse_then_commercant_et_carte_separes() = runTest {
        val payment = paymentOf("N-GW-2", "CA-06", parser.parse(nGw2))

        assertEquals("N-GW-2 — CA-06/I-3 : montant en centimes entiers", 650L, payment.amountCents)
        assertEquals("N-GW-2 — CA-06 : devise détectée depuis « € »", "EUR", payment.currency)
        assertEquals("N-GW-2 — CA-07/P-12 : libellé = commerçant, que le format Google place au titre", "MG ORGANISATION", payment.label)
        // Le masque « ••6088 » fait partie du nom de carte conservé, comme N-SW-1 attend « Visa ••1234 ».
        assertEquals(
            "N-GW-2 — CA-06 : nom de carte repris en entier, masque compris (donnée conservée sur la proposition)",
            "Curve Card ••6088", payment.cardName
        )
        assertTrue("N-GW-2 — P-2 : `normalizedText` ne peut pas être vide", payment.normalizedText.isNotBlank())
    }

    @Test
    fun given_paiement_samsung_carte_dans_le_titre_when_parse_then_carte_et_libelle_separes() = runTest {
        val payment = paymentOf("N-SW-1", "CA-06/CA-07", parser.parse(nSw1))

        assertEquals("N-SW-1 — CA-11/I-3 : montant payé (34,90 €), pas les quatre chiffres de la carte", 3490L, payment.amountCents)
        assertEquals("N-SW-1 — CA-06 : devise détectée depuis « € »", "EUR", payment.currency)
        assertEquals("N-SW-1 — CA-07 : libellé = commerçant seul, sans montant ni nom de carte", "Monoprix", payment.label)
        assertEquals("N-SW-1 — CA-07 : nom de carte repris du titre", "Visa ••1234", payment.cardName)
        assertTrue("N-SW-1 — P-2 : `normalizedText` ne peut pas être vide", payment.normalizedText.isNotBlank())
    }

    @Test
    fun given_capture_samsung_carte_dans_le_titre_when_parse_then_carte_et_libelle_separes() = runTest {
        val payment = paymentOf("N-SW-2", "CA-06/CA-07", parser.parse(nSw2))

        assertEquals("N-SW-2 — CA-11/I-3 : montant payé, lu dans le texte seul", 561L, payment.amountCents)
        assertEquals("N-SW-2 — CA-06 : devise détectée depuis « € »", "EUR", payment.currency)
        assertEquals("N-SW-2 — CA-07 : libellé = commerçant seul, montant retiré", "SAS  LEGADIS", payment.label)
        assertEquals("N-SW-2 — CA-07/P-12 : nom de carte, que le format Samsung place au titre", "Curve Card", payment.cardName)
        assertTrue("N-SW-2 — P-2 : `normalizedText` ne peut pas être vide", payment.normalizedText.isNotBlank())
    }

    // --- T-02 — ce qui n'est pas un paiement est rejeté, avec un motif (CA-09) ---

    @Test
    fun given_promotion_when_parse_then_rejete_avec_motif() = runTest {
        rejectionReasonOf("N-PROMO", "CA-09", parser.parse(nPromo))
    }

    @Test
    fun given_releve_de_solde_when_parse_then_rejete_avec_motif() = runTest {
        rejectionReasonOf("N-RELEVE", "CA-09", parser.parse(nReleve))
    }

    @Test
    fun given_alerte_de_plafond_when_parse_then_rejete_avec_motif() = runTest {
        rejectionReasonOf("N-PLAFOND", "CA-09", parser.parse(nPlafond))
    }

    // --- T-03 — citer le solde n'exclut pas un paiement, et n'en change pas le montant (CA-09, CA-11) ---

    @Test
    fun given_paiement_citant_le_solde_restant_when_parse_then_paiement_au_montant_paye() = runTest {
        val payment = paymentOf("N-SOLDE", "T-03 de la fiche (CA-09 se contenterait d'une proposition incertaine, la fiche exige une certaine)", parser.parse(nSolde))

        assertEquals(
            "N-SOLDE — CA-09/CA-11 : montant payé (15,00 € → 1500), jamais le solde restant (240,00 € → 24000)",
            1500L, payment.amountCents
        )
        assertEquals("N-SOLDE — CA-06 : devise détectée depuis « € »", "EUR", payment.currency)
        assertTrue("N-SOLDE — CA-06 : le libellé ne peut pas être vide", payment.label.isNotBlank())
        assertFalse(
            "N-SOLDE — CA-11 : le libellé ne doit pas véhiculer le montant du solde, obtenu «${payment.label}»",
            payment.label.contains("240")
        )
        assertNull("N-SOLDE — CA-06 : aucune carte n'est citée dans l'instantané, obtenu «${payment.cardName}»", payment.cardName)
        assertTrue("N-SOLDE — P-2 : `normalizedText` ne peut pas être vide", payment.normalizedText.isNotBlank())
    }

    // --- T-04 — le bruit numérique n'est jamais retenu comme montant (CA-11) ---

    @Test
    fun given_texte_avec_bruit_numerique_when_parse_then_seul_le_montant_paye_est_retenu() = runTest {
        val payment = paymentOf("N-BRUIT", "CA-06", parser.parse(nBruit))

        assertEquals(
            "N-BRUIT — CA-11/I-3 : montant payé (8,00 € → 800), ni la carte ••4321 ni la commande 90210",
            800L, payment.amountCents
        )
        assertEquals("N-BRUIT — CA-06 : devise détectée depuis « € »", "EUR", payment.currency)
        assertTrue("N-BRUIT — CA-06 : le libellé ne peut pas être vide", payment.label.isNotBlank())
        assertFalse(
            "N-BRUIT — CA-11 : le libellé ne doit contenir ni la carte ni le numéro de commande, obtenu «${payment.label}»",
            payment.label.contains("4321") || payment.label.contains("90210")
        )
        assertFalse(
            "N-BRUIT — CA-11 : le nom de carte ne doit pas capter le numéro de commande, obtenu «${payment.cardName}»",
            payment.cardName.orEmpty().contains("90210")
        )
        assertTrue("N-BRUIT — P-2 : `normalizedText` ne peut pas être vide", payment.normalizedText.isNotBlank())
    }

    // --- T-05 — un crédit ne produit jamais une dépense (CA-11) ---

    @Test
    fun given_remboursement_when_parse_then_aucune_depense() = runTest {
        val creditAttendu = "N-REMB — CA-11 : un remboursement ne peut pas produire une dépense ; " +
            "attendu un rejet motivé ou un montant explicitement créditeur (< 0)"

        when (val result = parser.parse(nRemb)) {
            is ParseResult.Rejected -> assertTrue(
                "$creditAttendu, obtenu un rejet au motif vide",
                result.reason.isNotBlank()
            )

            is ParseResult.Payment -> assertTrue(
                "$creditAttendu, obtenu ${describe(result)}",
                result.payment.amountCents < 0
            )

            is ParseResult.Uncertain -> assertTrue(
                "$creditAttendu, obtenu ${describe(result)}",
                result.payment.amountCents < 0
            )
        }
    }

    // --- T-06 — pas de montant exploitable, pas de proposition (CA-08, CA-21) ---

    @Test
    fun given_paiement_sans_montant_when_parse_then_rejete_avec_motif() = runTest {
        rejectionReasonOf("N-SANS", "CA-08", parser.parse(nSans))
    }

    @Test
    fun given_notification_vide_when_parse_then_rejete_avec_motif() = runTest {
        rejectionReasonOf("N-VIDE", "CA-08", parser.parse(nVide))
    }

    @Test
    fun given_montant_hors_capacite_when_parse_then_rejete_avec_motif_distinct() = runTest {
        val motifHorsCapacite = rejectionReasonOf("N-HORS", "T-06 de la fiche / P-1 (montant approché interdit)", parser.parse(nHors))
        val motifSansMontant = rejectionReasonOf("N-SANS", "CA-08", parser.parse(nSans))

        // « Le motif cite le dépassement » : sans littéral imposé par la fiche, l'oracle vérifiable
        // est que ce motif se distingue de celui d'une notification sans aucun montant.
        assertNotEquals(
            "N-HORS — CA-21 : le motif doit citer le dépassement de capacité, donc se distinguer du motif d'absence de montant (N-SANS)",
            motifSansMontant, motifHorsCapacite
        )
    }

    // --- T-07 — deux écritures du même montant, une seule clé (CA-14) ---

    @Test
    fun given_deux_ecritures_du_meme_montant_when_dedupeKey_then_cles_strictement_egales() = runTest {
        val paiement125 = paymentOf("N-125", "CA-06", parser.parse(n125))
        val paiement1250 = paymentOf("N-1250", "CA-06", parser.parse(n1250))

        assertEquals("N-125 — CA-14/I-3 : « 12,5 € » vaut 1250 centimes", 1250L, paiement125.amountCents)
        assertEquals("N-1250 — CA-14/I-3 : « 12,50 € » vaut 1250 centimes", 1250L, paiement1250.amountCents)

        val cle125 = parser.dedupeKey(n125.sourcePackage, paiement125)
        val cle1250 = parser.dedupeKey(n1250.sourcePackage, paiement1250)

        assertTrue("N-125 — P-2 : la clé doit contenir le paquet source, obtenue «$cle125»", cle125.contains(GOOGLE_WALLET))
        assertTrue("N-125 — P-2/I-3 : la clé doit contenir le montant en centimes (1250), obtenue «$cle125»", cle125.contains("1250"))
        assertTrue("N-1250 — P-2 : la clé doit contenir le paquet source, obtenue «$cle1250»", cle1250.contains(GOOGLE_WALLET))
        assertTrue("N-1250 — P-2/I-3 : la clé doit contenir le montant en centimes (1250), obtenue «$cle1250»", cle1250.contains("1250"))

        assertEquals(
            "CA-14/I-7 : « 12,5 » et « 12,50 » décrivent le même paiement, leurs clés de regroupement doivent être strictement égales",
            cle125, cle1250
        )
    }

    // --- Outillage d'assertion : aucun attendu ici, seulement des messages diagnostiquables ---

    private fun paymentOf(ref: String, ca: String, result: ParseResult): ParsedPayment {
        assertTrue(
            "$ref — $ca : attendu `ParseResult.Payment`, obtenu ${describe(result)}",
            result is ParseResult.Payment
        )
        return (result as ParseResult.Payment).payment
    }

    private fun rejectionReasonOf(ref: String, ca: String, result: ParseResult): String {
        assertTrue(
            "$ref — $ca : attendu `ParseResult.Rejected` sans aucun `ParsedPayment` produit, obtenu ${describe(result)}",
            result is ParseResult.Rejected
        )
        val reason = (result as ParseResult.Rejected).reason
        assertTrue("$ref — $ca : un rejet muet est un échec, le motif doit être non vide", reason.isNotBlank())
        return reason
    }

    private fun describe(result: ParseResult): String = when (result) {
        is ParseResult.Payment ->
            "Payment(montant=${result.payment.amountCents}c, devise=${result.payment.currency}, " +
                "libellé=«${result.payment.label}», carte=«${result.payment.cardName}», confiance=${result.confidence})"

        is ParseResult.Uncertain ->
            "Uncertain(montant=${result.payment.amountCents}c, devise=${result.payment.currency}, " +
                "libellé=«${result.payment.label}», carte=«${result.payment.cardName}», confiance=${result.confidence})"

        is ParseResult.Rejected -> "Rejected(motif=«${result.reason}»)"
    }

    private companion object {
        /** Sources autorisées en MVP (P-8), relevées dans `SettingsRepository`. */
        const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
        const val SAMSUNG_WALLET = "com.samsung.android.spay"
    }
}
