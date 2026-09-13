package com.lop.budget.domain.model

/** Nature d'un mouvement. */
enum class TransactionType { INCOME, EXPENSE }

/** Type de transaction (standard ou ajustement technique). */
enum class TransactionKind { STANDARD, BALANCE_ADJUSTMENT }

/**
 * Absence de catégorie sur une transaction (LOP-87, P-5).
 *
 * `categoryId` n'est pas nullable — une transaction métier porte toujours une catégorie — mais un
 * ajustement de solde, lui, n'en porte aucune (I-9). Valeur réservée : `0` ne peut désigner aucune
 * ligne de `categories`, dont la clé auto-générée démarre à 1. La jointure Room rend donc `null`
 * et l'affichage retombe sur son libellé de repli.
 *
 * Ce n'est **pas** une catégorie fantôme : écrire ici l'identifiant d'une catégorie *existante*
 * reste interdit.
 */
const val NO_CATEGORY_ID = 0L

/** Cycle de vie d'une transaction planifiée. */
enum class TransactionStatus { PLANNED, PAID }

/** Fréquence de base d'une règle de récurrence. */
enum class RecurrenceFrequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

/** Type de compte. */
enum class AccountType { CHECKING, CASH, SAVINGS, CARD, CRYPTO, INVESTMENT, OTHER }

/** Mode d'annulation d'une série récurrente. */
sealed interface SeriesCancelMode {
    data object All : SeriesCancelMode
    data class Future(val fromDate: Long) : SeriesCancelMode
}

/** Portée de modification d'une transaction récurrente. */
enum class EditScope { SINGLE, FUTURE, ALL }
