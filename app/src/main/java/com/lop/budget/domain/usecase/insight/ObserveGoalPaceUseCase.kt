package com.lop.budget.domain.usecase.insight

import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.model.progressView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Situation d'un objectif non terminé face à sa date d'échéance (LOP-243). */
enum class GoalSituation {
    /** En retard : échéance à venir, date d'atteinte estimée après son mois ou non définie (P-3). */
    LATE,

    /** Dans les temps : date d'atteinte estimée au plus tard dans le mois d'échéance. */
    ON_TIME,

    /** Échéance dépassée : date d'échéance antérieure à aujourd'hui (CA-04, P-4). */
    OVERDUE,
}

/**
 * Une ligne de la carte de LOP-243. [progress] et [status] sont ceux de LOP-79, tels quels (I-2).
 *
 * - [paceCents] : rythme, en centimes par mois ; `null` pour un objectif Terminé (CA-05).
 * - [reachMonth] : date d'atteinte estimée ; `null` si Terminé, rythme nul ou négatif, ou restant
 *   non calculable (cible nulle ou négative, LOP-79 CA-07).
 * - [situation] : `null` sans date d'échéance, pour un Terminé ou un restant non calculable.
 * - [effortCents] : effort nécessaire, en centimes par mois ; seulement pour [GoalSituation.LATE].
 */
data class GoalPace(
    val id: Long,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val targetCents: Long,
    val progress: ProgressView,
    val status: ItemStatus,
    val paceCents: Long?,
    val reachMonth: YearMonth?,
    val situation: GoalSituation?,
    val effortCents: Long?,
)

/**
 * Objectifs non archivés à leur rythme actuel (LOP-243). Hors de toute période d'Analyses (CA-06).
 * Lit l'avancement et le statut que LOP-79 a écrits et les contributions des trois derniers mois
 * civils complets, sans rien recalculer ni écrire (I-1, I-2). Réactif à toute écriture sur les
 * objectifs et les transactions (CA-07).
 */
@Singleton
class ObserveGoalPaceUseCase @Inject constructor(
    private val goalRepo: GoalRepository,
    private val transactionRepo: TransactionRepository,
) {
    /** [today] lu par l'appelant sur l'horloge injectée ; mois et échéances lus dans [zone]. */
    operator fun invoke(today: LocalDate, zone: ZoneId): Flow<List<GoalPace>> {
        val current = YearMonth.from(today)
        // Du 1er de M−3 inclus au 1er de M exclu : le mois courant, incomplet, ne compte pas (CA-01).
        val start = current.minusMonths(PACE_MONTHS).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = current.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(goalRepo.observeActive(), transactionRepo.observeGoalContributions(start, end)) { goals, sums ->
            goalPaces(goals, sums.associate { it.goalId to it.cents }, today, zone)
        }.flowOn(Dispatchers.Default)
    }
}

private const val PACE_MONTHS = 3L

/**
 * Calcul de la carte, sans Android ni Room. [contributions] : somme signée des trois derniers mois
 * complets par objectif ; un objectif absent n'a rien reçu.
 *
 * Tri (CA-05, P-5) : en retard ou échéance dépassée, puis avec date d'atteinte, puis sans
 * progression, puis atteints ; dans chaque groupe, date d'atteinte croissante (absente en dernier),
 * puis nom.
 */
internal fun goalPaces(goals: List<GoalEntity>, contributions: Map<Long, Long>, today: LocalDate, zone: ZoneId): List<GoalPace> {
    val collator = Collator.getInstance(Locale.FRANCE)
    return goals
        .map { goalPace(it, contributions[it.id] ?: 0L, today, zone) }
        .sortedWith(compareBy<GoalPace> { rank(it) }.thenBy(nullsLast()) { it.reachMonth }.thenBy(collator) { it.name })
}

private fun goalPace(goal: GoalEntity, contributed: Long, today: LocalDate, zone: ZoneId): GoalPace {
    val progress = progressView(goal.savedAmountCents, goal.targetAmountCents)
    val base = GoalPace(
        goal.id, goal.name, goal.icon, goal.colorArgb, goal.targetAmountCents, progress, goal.status,
        paceCents = null, reachMonth = null, situation = null, effortCents = null,
    )
    if (goal.status == ItemStatus.COMPLETED) return base
    // Moyenne des trois mois, mois vides compris, tronquée au centime (division entière).
    val pace = contributed / PACE_MONTHS
    val remaining = progress.remainingCents ?: return base.copy(paceCents = pace)
    val current = YearMonth.from(today)
    val reach = if (pace > 0) current.plusMonths(ceilDiv(remaining, pace)) else null
    val due = goal.dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val situation = when {
        due == null -> null
        due < today -> GoalSituation.OVERDUE
        reach != null && reach <= YearMonth.from(due) -> GoalSituation.ON_TIME
        else -> GoalSituation.LATE
    }
    // Du mois suivant au mois d'échéance inclus, au moins un mois ; arrondi au centime supérieur.
    val effort = if (situation == GoalSituation.LATE) {
        ceilDiv(remaining, maxOf(1L, current.until(YearMonth.from(due), ChronoUnit.MONTHS)))
    } else null
    return base.copy(paceCents = pace, reachMonth = reach, situation = situation, effortCents = effort)
}

private fun rank(goal: GoalPace): Int = when {
    goal.status == ItemStatus.COMPLETED -> 3
    goal.situation == GoalSituation.LATE || goal.situation == GoalSituation.OVERDUE -> 0
    goal.reachMonth != null -> 1
    else -> 2
}

/** Division arrondie à l'entier supérieur, pour [a] ≥ 0 et [b] > 0. */
private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
