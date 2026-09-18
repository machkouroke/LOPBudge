package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType

/**
 * Transaction (revenu ou dépense), planifiée ou payée.
 *
 * Liens optionnels :
 * - [seriesId] : identifiant de la série récurrente parente (si applicable).
 * - [seriesDate] : date d'origine prévue dans la série (pour identifier l'occurrence).
 * - [isException] : true si cette transaction est une matérialisation modifiée d'une série.
 * - [linkedGoalId] : contribution à un objectif d'épargne.
 * - [linkedLoanId] : remboursement d'une dette, ou encaissement d'une créance.
 *
 * Les deux rattachements se cumulent librement (P-1 de LOP-80) : ils mesurent des choses
 * différentes, et un encaissement peut diminuer une créance tout en alimentant un objectif. Ce qui
 * est exclu — une dette *et* une créance sur la même ligne — l'est structurellement, puisque
 * [linkedLoanId] ne porte qu'une référence et que la direction appartient au prêt.
 *
 * Les deux colonnes portent une clé étrangère `ON DELETE SET NULL` : supprimer un élément financier
 * ne supprime aucune transaction et n'en laisse aucune désigner une ligne absente (I-5). La
 * suppression se traite ainsi à la source plutôt que par un nettoyage applicatif répété à chaque
 * endroit qui supprime (P-6).
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedGoalId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = LoanEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedLoanId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        // Supprimer une carte ne supprime aucune transaction : elle perd seulement son
        // rattachement. Traité à la source, comme les deux liens ci-dessus (P-6 de LOP-80).
        ForeignKey(
            entity = PaymentCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("accountId"),
        Index("categoryId"),
        Index("seriesId"),
        Index("date"),
        // `observeForMerge` filtre `date BETWEEN ... OR (seriesId IS NOT NULL AND seriesDate
        // BETWEEN ...)`. Sans index sur `seriesDate`, SQLite ne peut appliquer son optimisation
        // OR à aucune des deux branches et parcourt toute la table — sur la requête qui alimente
        // l'accueil, la vue mensuelle, les analytics et la recherche.
        Index("seriesDate"),
        Index("paidAt"),
        Index("status"),
        Index("kind"),
        Index("deleted"),
        // Exigés par les clés étrangères ci-dessus : sans index sur la colonne enfant, chaque
        // suppression d'objectif ou de prêt impose un parcours complet de `transactions`.
        Index("linkedGoalId"),
        Index("linkedLoanId"),
        Index("cardId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Montant en centimes, toujours positif : le sens est porté par [type]. */
    val amount: Long,
    val type: TransactionType,
    val status: TransactionStatus,
    val kind: TransactionKind = TransactionKind.STANDARD,
    /** Date d'échéance / de réalisation, epoch millis. */
    val date: Long,
    val accountId: Long,
    val categoryId: Long,
    val note: String? = null,
    /** Date effective de paiement, epoch millis. */
    val paidAt: Long? = null,

    // --- Lien vers série récurrente ---
    val seriesId: Long? = null,
    val seriesDate: Long? = null,
    val isException: Boolean = false,

    // --- Liens ---
    val linkedGoalId: Long? = null,
    val linkedLoanId: Long? = null,

    /**
     * Carte de paiement utilisée, si elle est connue.
     *
     * Nul par défaut : toute transaction saisie à la main en est dépourvue, et ce n'est pas un
     * défaut. Sans cette colonne, l'analyse par carte serait incalculable dès que deux cartes
     * pointent vers le même compte — elles afficheraient le même total.
     */
    val cardId: Long? = null,

    // --- Soft Delete ---
    val deleted: Boolean = false,
)
