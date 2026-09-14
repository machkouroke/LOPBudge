package com.lop.budget.notifications

import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyse d'un [NotificationSnapshot] : montant en centimes, libellé, carte, clé de regroupement.
 *
 * Calcul pur : ni `Context`, ni `StatusBarNotification`, ni base, ni réseau (I-4, I-10, CA-25).
 *
 * **Ne porte plus aucune règle de format (P-12).** Son travail tient en quatre gestes : écarter un
 * instantané vide, choisir le parseur de la source à partir du paquet, demander au classifieur si
 * c'est un paiement, puis déléguer l'extraction à ce parseur. Le format de Google Wallet et celui
 * de Samsung Wallet sont déclarés chacun dans leur fichier, et ajouter une source consiste à
 * ajouter un parseur au registre ci-dessous.
 *
 * Risque résiduel assumé : la valeur absolue est appliquée au montant, donc un crédit qui
 * franchirait le classifieur deviendrait une dépense. Le garde-fou est en amont — le vocabulaire de
 * crédit est rejeté par [HeuristicNotificationClassifier] (CA-11) — et il est lexical, donc
 * faillible. Le jour où les remboursements auront un traitement métier (EVOL), c'est le signe du
 * montant qui devra porter la distinction, pas une liste de mots.
 */
@Singleton
class PaymentNotificationParser @Inject constructor(
    private val classifier: NotificationClassifier
) : PaymentParser {

    /**
     * Registre des formats connus (P-12, P-8). Une source de plus = une ligne de plus.
     *
     * Liste en dur plutôt qu'un multibinding Hilt : la liste des sources est fixe en MVP et le
     * registre n'a aucune dépendance à injecter. À basculer en multibinding le jour où une source
     * aura besoin de collaborateurs.
     */
    private val sourceParsers: List<NotificationSourceParser> = listOf(
        GoogleWalletParser(),
        SamsungWalletParser(),
    )

    override suspend fun parse(snapshot: NotificationSnapshot): ParseResult {
        val title = snapshot.title.orEmpty().trim()
        val text = snapshot.text.orEmpty().trim()

        val raw = listOf(title, text).filter { it.isNotBlank() }.joinToString(" • ")
        if (raw.isBlank()) return ParseResult.Rejected("notification_vide")

        // P-12 : le format vient du paquet source, il n'est jamais déduit du texte.
        val source = sourceParsers.firstOrNull { it.handles(snapshot.sourcePackage) }
            ?: return ParseResult.Rejected("source_sans_parseur")

        val classification = classifier.classify(raw)
        if (classification.status == ClassificationResult.Status.IGNORE) {
            return ParseResult.Rejected(classification.reason ?: "classe_ignore")
        }

        val extracted = when (val extraction = source.extract(title, text)) {
            is SourceExtraction.Failed -> return ParseResult.Rejected(extraction.reason)
            is SourceExtraction.Extracted -> extraction
        }

        val payment = ParsedPayment(
            amountCents = kotlin.math.abs(extracted.amountCents),
            currency = extracted.currency,
            label = extracted.label,
            cardName = extracted.cardName,
            normalizedText = normalizeForDedupe(raw),
        )

        // P-12 : c'est le format qui atteste le paiement, pas le vocabulaire. Une extraction
        // complète — un montant **et** sa devise — vaut certitude ; un nombre nu reste une
        // hypothèse et part en proposition incertaine (CA-10). Un classifieur capable de dire son
        // propre doute (ML Kit) garde le dernier mot pour dégrader, jamais pour rehausser.
        val certain = extracted.currency != null &&
            classification.status != ClassificationResult.Status.UNCERTAIN

        val confidence =
            if (certain) maxOf(classification.confidence, CERTAIN_CONFIDENCE) else classification.confidence

        return if (certain) ParseResult.Payment(payment, confidence)
        else ParseResult.Uncertain(payment, confidence)
    }

    override fun dedupeKey(sourcePackage: String, payment: ParsedPayment): String =
        "$sourcePackage|${payment.amountCents}|${payment.currency ?: ""}|${payment.normalizedText}"

    fun normalizeForDedupe(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val normalized = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return normalized
            // CA-14 : les chiffres sont retirés. Le montant est déjà dans la clé, en centimes ;
            // laisser son écriture dans le texte faisait diverger « 12,5 » et « 12,50 », et faisait
            // entrer le masque de carte ou un numéro de commande dans une clé censée identifier le
            // paiement, pas la notification qui le décrit.
            .replace("[^a-z€$ ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private companion object {
        /**
         * Seuil de certitude, repris de l'échelle du classifieur pour ne pas en inventer une
         * seconde. Plancher de confiance d'un paiement dont le format a été lu en entier.
         */
        const val CERTAIN_CONFIDENCE = 0.7f
    }
}
