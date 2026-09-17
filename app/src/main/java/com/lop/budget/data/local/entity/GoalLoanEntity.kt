package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection

/**
 * Objectif d'épargne.
 *
 * Montants **en centimes**, comme ceux des transactions (I-3, P-2) : aucune frontière euros au
 * milieu du modèle, donc aucune division à faire nulle part.
 *
 * [savedAmountCents] est la *progression enregistrée* : elle ne vient que du moteur de calcul et
 * n'est jamais saisie (I-2). Elle reste distincte de [startingBalanceCents], qui est une donnée de
 * création : l'une se recalcule, l'autre jamais (P-3).
 *
 * [accountId] est informatif et ne donne au compte aucun rôle dans le calcul (P-4).
 */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmountCents: Long,
    val startingBalanceCents: Long = 0,
    /** Progression enregistrée : écrite par le moteur seul (I-2). */
    val savedAmountCents: Long = 0,
    val status: ItemStatus = ItemStatus.ACTIVE,
    /** Échéance optionnelle (epoch millis). */
    val dueDate: Long? = null,
    /** Compte rattaché, informatif uniquement (P-4). */
    val accountId: Long? = null,
    val comment: String? = null,
    val colorArgb: Int,
    val icon: String,
)

/**
 * Prêt : somme engagée avec une contrepartie, portant une [direction].
 *
 * Dette (`BORROWED`) et créance (`LENT`) sont **un seul objet** : mêmes champs, même cycle de vie.
 * La direction est fixée à la création et ne change jamais (I-6) — la garde vit dans
 * `LoanRepository.update`, pas ici, car l'entité doit rester relisible telle qu'elle est en base.
 *
 * [debtType] et [interestRate] sont propres à la dette et restent à leur valeur par défaut pour une
 * créance : contrepartie assumée de la fusion (décision du 17 septembre 2026).
 *
 * [repaidAmountCents] est la progression enregistrée : moteur seul, jamais saisie (I-2).
 */
@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Contrepartie : créancier d'une dette, débiteur d'une créance. */
    val counterpartyName: String? = null,
    val direction: LoanDirection,
    val debtType: DebtType = DebtType.OTHER,
    val totalAmountCents: Long,
    val startingBalanceCents: Long = 0,
    /** Progression enregistrée : écrite par le moteur seul (I-2). */
    val repaidAmountCents: Long = 0,
    /** Taux annuel en pourcentage — un taux n'est pas un montant, I-3 ne s'y applique pas. */
    val interestRate: Double = 0.0,
    val status: ItemStatus = ItemStatus.ACTIVE,
    /** Échéance optionnelle (epoch millis). */
    val dueDate: Long? = null,
    /** Compte rattaché, informatif uniquement (P-4). */
    val accountId: Long? = null,
    val comment: String? = null,
    val colorArgb: Int,
    val icon: String,
)
