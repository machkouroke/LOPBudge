package com.lop.budget.ui.screens.transaction

import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus

/** Fin d'une règle (LOP-245, CA-04) : une seule à la fois. */
enum class RecurrenceEnd { NEVER, DATE, COUNT }

/**
 * Brouillon de la feuille « Répéter » (LOP-245). Rien n'atteint le formulaire avant « Valider »
 * (I-1, P-3). Les règles de saisie de CA-02 et CA-04 vivent ici, en Kotlin pur.
 */
data class RecurrenceDraft(
    val frequency: RecurrenceFrequency,
    val interval: Int,
    /** 1 = lundi … 7 = dimanche, comme `TransactionForm.daysOfWeek`. */
    val daysOfWeek: Set<Int>,
    val endDate: Long?,
    val maxOccurrences: Int?,
    val missingDayBehavior: MissingDayBehavior,
) {
    val end: RecurrenceEnd
        get() = when {
            endDate != null -> RecurrenceEnd.DATE
            maxOccurrences != null -> RecurrenceEnd.COUNT
            else -> RecurrenceEnd.NEVER
        }

    /**
     * CA-02 : l'intervalle repart de 1 (P-6) ; en hebdomadaire, seul le jour de la date du formulaire
     * est coché (P-4). Rechoisir la fréquence en cours ne change rien.
     */
    fun withFrequency(target: RecurrenceFrequency, formDayOfWeek: Int): RecurrenceDraft =
        if (target == frequency) this
        else copy(
            frequency = target,
            interval = 1,
            daysOfWeek = if (target == RecurrenceFrequency.WEEKLY) setOf(formDayOfWeek) else emptySet(),
        )

    fun withInterval(value: Int) = copy(interval = value.coerceIn(1, MAX_INTERVAL))

    /** CA-02 : la dernière pastille cochée ne se décoche pas. */
    fun toggleDay(day: Int) = when {
        day !in daysOfWeek -> copy(daysOfWeek = daysOfWeek + day)
        daysOfWeek.size > 1 -> copy(daysOfWeek = daysOfWeek - day)
        else -> this
    }

    /** CA-04 : une seule fin ; « À une date » part de la date du formulaire, « Après… » de 12. */
    fun withEnd(target: RecurrenceEnd, formDate: Long) = when (target) {
        RecurrenceEnd.NEVER -> copy(endDate = null, maxOccurrences = null)
        RecurrenceEnd.DATE -> copy(endDate = endDate ?: formDate, maxOccurrences = null)
        RecurrenceEnd.COUNT -> copy(endDate = null, maxOccurrences = maxOccurrences ?: DEFAULT_COUNT)
    }

    fun withEndDate(date: Long) = copy(endDate = date, maxOccurrences = null)

    fun withCount(value: Int) = copy(endDate = null, maxOccurrences = value.coerceIn(1, MAX_COUNT))

    fun withMissingDay(behavior: MissingDayBehavior) = copy(missingDayBehavior = behavior)

    companion object {
        const val MAX_INTERVAL = 99
        const val MAX_COUNT = 999
        const val DEFAULT_COUNT = 12

        fun of(form: TransactionForm) = RecurrenceDraft(
            frequency = form.frequency,
            interval = form.interval,
            daysOfWeek = form.daysOfWeek,
            endDate = form.endDate,
            maxOccurrences = form.maxOccurrences,
            missingDayBehavior = form.missingDayBehavior,
        )
    }
}

/**
 * Le formulaire après « Valider » (LOP-245, CA-06). Avec une récurrence, le statut passe à Planifiée
 * comme avec `setFrequency` ; sans, la règle est vidée et l'abonnement retiré (CA-01 de LOP-244).
 */
fun TransactionForm.applying(draft: RecurrenceDraft): TransactionForm =
    if (draft.frequency == RecurrenceFrequency.NONE) copy(
        frequency = RecurrenceFrequency.NONE,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        isSubscription = false,
    ) else copy(
        frequency = draft.frequency,
        interval = draft.interval,
        daysOfWeek = draft.daysOfWeek,
        endDate = draft.endDate,
        maxOccurrences = draft.maxOccurrences,
        missingDayBehavior = draft.missingDayBehavior,
        status = TransactionStatus.PLANNED,
    )
