package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.MissingDay
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Moteur de récurrence centralisé.
 * Gère la génération d'occurrences virtuelles à partir d'une série.
 */
object RecurrenceEngine {

    /**
     * Longueur du cycle de la grille : périodes examinées par [firstMissingDay], et nombre de
     * périodes sautées d'affilée au-delà duquel `validSlots` s'arrête (LOP-193).
     */
    private const val MISSING_DAY_SCAN = 400

    /**
     * Génère les occurrences virtuelles d'une série sur une période donnée.
     */
    fun generateOccurrences(
        series: RecurringSeriesEntity,
        startRange: Long,
        endRange: Long
    ): List<TransactionEntity> {
        if (series.isCancelled) return emptyList()

        return validSlots(series, notBefore = startRange)
            .takeWhile { it <= endRange }
            .filter { it >= startRange }
            .map { createVirtualTransaction(series, it) }
            .toList()
    }

    /**
     * Les [count] prochaines occurrences strictement postérieures à [after].
     *
     * Bornée par un **nombre**, jamais par une fenêtre : c'est ce dont a besoin un appelant qui
     * veut « les six prochaines échéances » et qui, sinon, devrait inventer un horizon calendaire
     * arbitraire. Les limites de série (`endDate`, `maxOccurrences`) s'appliquent normalement, donc
     * le résultat peut compter moins de [count] éléments.
     */
    fun nextOccurrences(
        series: RecurringSeriesEntity,
        after: Long,
        count: Int
    ): List<TransactionEntity> {
        if (series.isCancelled || count <= 0) return emptyList()

        return validSlots(series, notBefore = after)
            .filter { it > after }
            .take(count)
            .map { createVirtualTransaction(series, it) }
            .toList()
    }

    /**
     * Slots réellement produits par la série, dans l'ordre, une fois ses propres limites appliquées.
     *
     * Le compteur avance sur TOUS les slots depuis `startDate` : `endDate` et `maxOccurrences` sont
     * des limites de **série**, jamais de fenêtre ni de sous-ensemble demandé. C'est la seule règle
     * d'itération du moteur — [generateOccurrences] et [nextOccurrences] ne font qu'y appliquer
     * leur propre critère d'arrêt.
     */
    private fun validSlots(series: RecurringSeriesEntity, notBefore: Long? = null): Sequence<Long> = sequence {
        // Le compteur doit voir TOUS les slots depuis `startDate`, donc on ne peut avancer
        // directement au voisinage de [notBefore] que si `maxOccurrences` est absent — auquel cas
        // `count` n'est lu par personne. Une série bornée par `maxOccurrences` est de toute façon
        // bornée en nombre d'itérations, elle n'a pas le problème que ce raccourci corrige.
        val fromStep = if (series.maxOccurrences == null && notBefore != null) {
            stepBefore(series, notBefore)
        } else {
            0L
        }

        val skip = series.missingDayBehavior == MissingDayBehavior.SKIP_PERIOD
        var count = 0
        var skippedInARow = 0
        for (slot in slots(series, fromStep)) {
            if (series.endDate != null && slot > series.endDate) break
            if (series.maxOccurrences != null && count >= series.maxOccurrences) break

            // P-1 de LOP-88 : une période sautée consomme `maxOccurrences`, d'où le compteur
            // avancé avant le saut.
            count++
            if (skip && lacksAnchorDay(series, slot)) {
                // LOP-193 : le jour d'une période ne dépend que de son mois et, pour février, de
                // l'année. La grille revient sur ses pas en au plus 12 mois ou 400 ans : autant de
                // périodes sautées d'affilée prouvent qu'aucune ne sera plus jamais produite.
                if (++skippedInARow >= MISSING_DAY_SCAN) break
                continue
            }
            skippedInARow = 0
            yield(slot)
        }
    }

    /**
     * CA-01 de LOP-88 : premier slot de la règle dont la période ne possède pas le jour d'ancrage,
     * ou `null` si la règle n'en rencontre aucun.
     *
     * La grille est parcourue en mode « dernier jour valide », limites de série comprises
     * (intervalle, `endDate`, `maxOccurrences`) : ce sont les périodes **visées** par la règle,
     * quel que soit le comportement finalement retenu. La date rendue est la date rabattue, qui
     * sert d'exemple à l'avertissement.
     *
     * P-6 de LOP-88 : « sauter » n'est proposé que si au moins une période visée a le jour
     * d'ancrage ([MissingDay.canSkip]). Le départ compte pour son propre jour : un 28 février ancré
     * au 31 ne le possède pas.
     */
    fun firstMissingDay(series: RecurringSeriesEntity): MissingDay? {
        if (series.isCancelled) return null
        if (series.frequency != RecurrenceFrequency.MONTHLY && series.frequency != RecurrenceFrequency.YEARLY) {
            return null
        }
        // 400 périodes couvrent le cycle de la grille (12 mois, 400 ans grégoriens) : ce qui n'y
        // apparaît pas n'apparaîtra jamais. Même borne que l'arrêt de `validSlots` (LOP-193).
        val periods = validSlots(series.copy(missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY))
            .take(MISSING_DAY_SCAN)
            .toList()
        val missing = periods.firstOrNull { lacksAnchorDay(series, it) } ?: return null
        val anchor = anchorDay(series)
        return MissingDay(
            anchorDay = anchor,
            date = missing,
            canSkip = periods.any { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).dayOfMonth == anchor },
        )
    }

    /** Jour d'ancrage de la série : [RecurringSeriesEntity.anchorDayOfMonth], sinon celui de sa date de début. */
    private fun anchorDay(series: RecurringSeriesEntity): Int =
        series.anchorDayOfMonth ?: Instant.ofEpochMilli(series.startDate).atZone(ZoneId.systemDefault()).dayOfMonth

    /**
     * P-4 de LOP-88 : jour d'ancrage qu'une nouvelle série démarrée sur [from] doit reprendre de
     * [series], ou `null`.
     *
     * Non nul seulement si [from] est une date **rabattue** de la grille : dernier jour d'un mois
     * plus court que l'ancrage (28 février pour une série au 31) et, pour l'annuel, dans le mois
     * de la date de début. Sans ce report, « Cette occurrence et les suivantes » ouvert le
     * 28 février ancrerait la nouvelle série au 28 et perdrait le 31 sans avertir.
     */
    fun carriedAnchorDay(series: RecurringSeriesEntity, from: Long): Int? {
        if (series.frequency != RecurrenceFrequency.MONTHLY && series.frequency != RecurrenceFrequency.YEARLY) {
            return null
        }
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val start = Instant.ofEpochMilli(series.startDate).atZone(zone)
        val anchor = anchorDay(series)
        val clamped = date.dayOfMonth < anchor && date.dayOfMonth == date.lengthOfMonth()
        val sameMonth = series.frequency == RecurrenceFrequency.MONTHLY || date.month == start.month
        return anchor.takeIf { clamped && sameMonth }
    }

    /**
     * La période de [slot] ne possède pas le jour d'ancrage : le slot a été rabattu.
     *
     * Le slot de départ n'est jamais concerné. Il ne peut l'être que pour une série ancrée par
     * [carriedAnchorDay], dont le départ est justement l'occurrence que l'utilisateur édite.
     */
    private fun lacksAnchorDay(series: RecurringSeriesEntity, slot: Long): Boolean {
        if (series.frequency != RecurrenceFrequency.MONTHLY && series.frequency != RecurrenceFrequency.YEARLY) {
            return false
        }
        if (slot == series.startDate) return false
        return Instant.ofEpochMilli(slot).atZone(ZoneId.systemDefault()).dayOfMonth != anchorDay(series)
    }

    /**
     * Un pas dont on est certain qu'aucun slot d'indice inférieur n'atteint [target].
     *
     * Sans ce raccourci, afficher un mois coûte un `ZonedDateTime` par période écoulée **depuis le
     * début de la série** : une série quotidienne vieille de trois ans facture ~1100 décalages de
     * date pour produire 30 occurrences. Le calcul reste calendaire, jamais arithmétique sur les
     * millisecondes, pour ne pas réintroduire de dérive DST.
     *
     * Volontairement pessimiste : le quotient est tronqué, la borne est reculée d'un pas
     * supplémentaire, et pour une série hebdomadaire par jours choisis le pas est ancré sur le
     * lundi de la semaine de début, donc plus tôt que [series].`startDate`. Un pas de trop ne
     * produit que quelques slots écartés par l'appelant ; un pas de moins perdrait une occurrence.
     */
    private fun stepBefore(series: RecurringSeriesEntity, target: Long): Long {
        if (series.interval <= 0 || series.frequency == RecurrenceFrequency.NONE) return 0L
        if (target <= series.startDate) return 0L

        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(series.startDate).atZone(zone)
        val until = Instant.ofEpochMilli(target).atZone(zone)

        val elapsed = when (series.frequency) {
            RecurrenceFrequency.DAILY -> ChronoUnit.DAYS.between(start, until)
            RecurrenceFrequency.WEEKLY -> ChronoUnit.WEEKS.between(start, until)
            RecurrenceFrequency.MONTHLY -> ChronoUnit.MONTHS.between(start, until)
            RecurrenceFrequency.YEARLY -> ChronoUnit.YEARS.between(start, until)
            RecurrenceFrequency.NONE -> return 0L
        }

        return (elapsed / series.interval - 1).coerceAtLeast(0L)
    }

    /**
     * Suite paresseuse des instants de slot de la série, dans l'ordre chronologique croissant.
     *
     * Chaque slot est calculé en décalant la date de début d'origine, jamais le slot précédent.
     * C'est ce qui conserve l'ancrage calendaire quand un mois court impose un rabattement :
     * 31 janvier puis 28 février puis 31 mars, et 29 février retrouvé en année bissextile.
     */
    private fun slots(series: RecurringSeriesEntity, fromStep: Long = 0L): Sequence<Long> = sequence {
        val start = Instant.ofEpochMilli(series.startDate).atZone(ZoneId.systemDefault())
        val selectedDays = parseDaysOfWeek(series.daysOfWeek)
        val byWeekday = series.frequency == RecurrenceFrequency.WEEKLY && selectedDays.isNotEmpty()
        val firstMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())

        var step = fromStep
        while (true) {
            if (byWeekday) {
                val monday = firstMonday.plusWeeks(step * series.interval)
                for (isoDay in selectedDays) {
                    val slot = monday.plusDays((isoDay - 1).toLong())
                    if (!slot.isBefore(start)) yield(slot.toInstant().toEpochMilli())
                }
            } else {
                val slot = anchored(shift(start, series.frequency, step * series.interval), series)
                yield(slot.toInstant().toEpochMilli())
            }

            // Sécurité : une fréquence NONE ou un intervalle invalide ne produit pas de suite.
            if (series.frequency == RecurrenceFrequency.NONE || series.interval <= 0) return@sequence
            step++
        }
    }

    private fun shift(
        start: ZonedDateTime,
        frequency: RecurrenceFrequency,
        offset: Long
    ): ZonedDateTime = when (frequency) {
        RecurrenceFrequency.DAILY -> start.plusDays(offset)
        RecurrenceFrequency.WEEKLY -> start.plusWeeks(offset)
        RecurrenceFrequency.MONTHLY -> start.plusMonths(offset)
        RecurrenceFrequency.YEARLY -> start.plusYears(offset)
        RecurrenceFrequency.NONE -> start
    }

    /**
     * Ramène [slot] sur le jour d'ancrage reporté (P-4 de LOP-88), borné au dernier jour du mois.
     * Une série démarrée le 28 février et ancrée au 31 donne ainsi le 31 mars, puis le 30 avril.
     */
    private fun anchored(slot: ZonedDateTime, series: RecurringSeriesEntity): ZonedDateTime {
        val day = series.anchorDayOfMonth ?: return slot
        if (series.frequency != RecurrenceFrequency.MONTHLY && series.frequency != RecurrenceFrequency.YEARLY) {
            return slot
        }
        return slot.withDayOfMonth(minOf(day, slot.toLocalDate().lengthOfMonth()))
    }

    private fun parseDaysOfWeek(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .sorted()
    }

    /**
     * Crée une transaction virtuelle (non persistée) pour une occurrence.
     */
    private fun createVirtualTransaction(series: RecurringSeriesEntity, date: Long): TransactionEntity {
        return TransactionEntity(
            id = calculateVirtualId(series.id, date),
            title = series.title,
            amount = series.amount,
            type = series.type,
            status = TransactionStatus.PLANNED, // Une occurrence virtuelle est par défaut planifiée
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = series.accountId,
            categoryId = series.categoryId,
            seriesId = series.id,
            seriesDate = date,
            isException = false,
            note = series.note,
            linkedGoalId = series.linkedGoalId,
            linkedLoanId = series.linkedLoanId
        )
    }

    /**
     * Calcule un ID négatif stable et déterministe pour une occurrence virtuelle.
     * Utilise le seriesId et la date pour éviter les collisions.
     */
    fun calculateVirtualId(seriesId: Long, date: Long): Long {
        // Combinaison simple pour générer un hash stable négatif
        val hash = 31 * seriesId + date
        val virtualId = -(hash.coerceAtLeast(1)) // Toujours < 0
        return if (virtualId >= 0) -1 else virtualId
    }

    /**
     * Inverse algébrique de [calculateVirtualId] : la date de slot encodée dans [virtualId] **si**
     * celui-ci appartient à la série [seriesId].
     *
     * L'inversion seule ne prouve rien : elle rend une date pour n'importe quelle série, puisque
     * `date` est justement calculée pour satisfaire l'équation. C'est à l'appelant de départager les
     * candidates, en vérifiant qu'un slot persisté ou une occurrence générée existe réellement à
     * cette date pour cette série.
     *
     * Rend `null` lorsque l'aller-retour ne se referme pas — cas du clamp de [calculateVirtualId]
     * sur les dates antérieures à 1970, où l'ID est écrasé à -1 et n'encode plus rien.
     */
    fun slotDateOf(seriesId: Long, virtualId: Long): Long? {
        val date = -virtualId - 31 * seriesId
        return date.takeIf { calculateVirtualId(seriesId, it) == virtualId }
    }
}
