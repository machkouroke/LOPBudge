package com.lop.budget.domain.model

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity

/** Unique point de conversion Set<Int> -> CSV Room (trié, null si vide). */
fun Set<Int>.toDaysOfWeekCsv(): String? =
    takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")

/** Unique point de parsing CSV Room -> Set<Int> (null ou blanc = ensemble vide). */
fun String?.toDaysOfWeekSet(): Set<Int> =
    this?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()

/**
 * Unique lieu de construction d'une RecurringSeriesEntity depuis une édition.
 * startDate = date de l'édition ; isCancelled = false (nouvelle série active).
 */
fun TransactionEdition.toSeriesEntity(): RecurringSeriesEntity = RecurringSeriesEntity(
    title = title,
    amount = amount,
    type = type,
    categoryId = categoryId,
    accountId = accountId,
    frequency = frequency,
    interval = interval,
    startDate = date,
    endDate = endDate,
    maxOccurrences = maxOccurrences,
    daysOfWeek = daysOfWeek.toDaysOfWeekCsv(),
    isCancelled = false,
    note = note,
    linkedGoalId = linkedGoalId,
    linkedDebtId = linkedDebtId,
)

/**
 * Unique lieu de construction d'une TransactionEntity depuis une édition.
 * Les 7 paramètres sont volontairement obligatoires (pas de défauts) : chaque site d'appel
 * doit expliciter l'identité, le statut, le paiement, le rattachement série et le type technique.
 *
 * [kind] a longtemps été omis, et le défaut de l'entité (`STANDARD`) s'appliquait : toute
 * réécriture d'un ajustement de solde le reconvertissait silencieusement en transaction métier,
 * ce que I-5 et CA-23 de LOP-87 interdisent. Le paramètre est obligatoire précisément pour qu'un
 * futur site d'appel ne puisse pas réintroduire l'oubli : une création passe `STANDARD`, une
 * édition reconduit le type de la ligne existante.
 */
fun TransactionEdition.toTransactionEntity(
    id: Long,
    status: TransactionStatus,
    paidAt: Long?,
    seriesId: Long?,
    seriesDate: Long?,
    isException: Boolean,
    kind: TransactionKind,
): TransactionEntity = TransactionEntity(
    id = id,
    title = title,
    amount = amount,
    type = type,
    status = status,
    kind = kind,
    date = date,
    accountId = accountId,
    categoryId = categoryId,
    note = note,
    paidAt = paidAt,
    seriesId = seriesId,
    seriesDate = seriesDate,
    isException = isException,
    linkedGoalId = linkedGoalId,
    linkedDebtId = linkedDebtId,
)
