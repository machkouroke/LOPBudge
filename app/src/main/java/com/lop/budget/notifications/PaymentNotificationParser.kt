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
 * Écarts connus **volontairement reconduits**, pour qu'ils restent visibles et décidables plutôt
 * que refermés au passage par un refactoring :
 * - la valeur absolue est appliquée au montant, donc un crédit deviendrait une dépense s'il
 *   franchissait le classifieur (TC-108 / T-05) ;
 * - le seuil et les mots négatifs du classifieur décident seuls du rejet, si bien qu'un paiement
 *   sans mot-clé positif est écarté (TC-108 / T-01, cas Samsung) ;
 * - la clé de regroupement embarque le texte brut normalisé, donc l'écriture du montant
 *   (TC-108 / T-07).
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

        return when (classification.status) {
            ClassificationResult.Status.UNCERTAIN -> ParseResult.Uncertain(payment, classification.confidence)
            else -> ParseResult.Payment(payment, classification.confidence)
        }
    }

    override fun dedupeKey(sourcePackage: String, payment: ParsedPayment): String =
        "$sourcePackage|${payment.amountCents}|${payment.currency ?: ""}|${payment.normalizedText}"

    fun normalizeForDedupe(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val normalized = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return normalized
            .replace("[^a-z0-9€$ ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
