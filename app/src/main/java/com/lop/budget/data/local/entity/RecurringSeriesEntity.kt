package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType

/**
 * Série récurrente : le modèle depuis lequel les occurrences sont générées.
 *
 * Elle porte les mêmes rattachements qu'une transaction, et donc les mêmes règles. Les aligner
 * n'est pas cosmétique : [linkedGoalId] et [linkedLoanId] sont recopiés de la série vers chaque
 * occurrence qu'elle produit. Une série laissée hors contrat serait un second chemin de
 * rattachement, et un second producteur d'occurrences désignant un élément supprimé
 * (I-5, P-6 de LOP-80).
 */
@Entity(
    tableName = "recurring_series",
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
    ],
    indices = [Index("linkedGoalId"), Index("linkedLoanId")],
)
data class RecurringSeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Montant en centimes, toujours positif : le sens est porté par [type]. */
    val amount: Long,
    val type: TransactionType,
    val categoryId: Long,
    val accountId: Long,
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val startDate: Long,
    val endDate: Long? = null,
    val maxOccurrences: Int? = null,
    val daysOfWeek: String? = null, // pour WEEKLY, ex: "1,3,5"
    val isCancelled: Boolean = false,
    val note: String? = null,
    val linkedGoalId: Long? = null,
    val linkedLoanId: Long? = null,
    /**
     * Comportement pour jour absent (LOP-88). Une série antérieure à l'EVOL reçoit
     * [MissingDayBehavior.LAST_VALID_DAY] par `MIGRATION_23_24` : son calendrier ne change pas (I-3).
     */
    val missingDayBehavior: MissingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
    /**
     * Jour d'ancrage quand il diffère de celui de [startDate] (P-4 de LOP-88). Cas d'origine : une
     * série « Cette occurrence et les suivantes » ouverte sur une occurrence rabattue démarre le
     * 28 février mais garde le 31. `null` : le jour de [startDate] fait foi.
     */
    val anchorDayOfMonth: Int? = null,
)
