package com.lop.budget.domain.model

/** Nature d'un mouvement. */
enum class TransactionType { INCOME, EXPENSE }

/** Cycle de vie d'une transaction planifiée. */
enum class TransactionStatus { PLANNED, PAID }

/** Fréquence de base d'une règle de récurrence. */
enum class RecurrenceFrequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

/** Type de compte. */
enum class AccountType { CHECKING, CASH, SAVINGS, CARD, OTHER }

/** Mode de suppression d'une série récurrente. */
enum class SeriesDeletionMode { ALL, FUTURE }
