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

/**
 * Absence de compte sur une transaction. Décision produit du 15 septembre 2026.
 *
 * **Le compte n'est pas obligatoire.** Une transaction peut n'être rattachée à aucun compte : on
 * note une dépense avant d'avoir créé ses comptes, on saisit un paiement en espèces qu'on ne suit
 * nulle part. La règle précédente refusait la sauvegarde dans ce cas, ce qui bloquait la saisie au
 * lieu de l'aider.
 *
 * Même convention que [NO_CATEGORY_ID], et pour la même raison : `accountId` n'est pas nullable,
 * `0` ne peut désigner aucune ligne de `accounts` dont la clé auto-générée démarre à 1, et la
 * jointure Room rend donc `account = null`. Aucune migration n'est nécessaire.
 *
 * Conséquences, toutes déjà tenues par le code de lecture :
 * - la transaction n'entre dans **aucun** solde de compte — `BalanceEngine` ignore un identifiant
 *   qui ne correspond à aucun compte — ni dans le solde total, qui somme les comptes ;
 * - filtrer une liste sur un compte réel ne la fait pas apparaître ;
 * - sans filtre de compte, elle apparaît normalement dans les listes, la recherche et les analyses.
 *
 * Ce n'est **pas** un compte fantôme : écrire ici l'identifiant d'un compte *existant* reste
 * interdit, et un identifiant non nul qui ne désigne aucun compte reste une donnée invalide.
 */
const val NO_ACCOUNT_ID = 0L

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
