package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-85 : bornes et ancrage des récurrences.
 *
 * ## Niveau et chaîne exercée
 * Test unitaire JUnit 4 JVM pur. Aucune Room, aucun Android, aucun mock ni spy.
 * Chaîne : `RecurrenceEngine.generateOccurrences(series, startRange, endRange)` seul,
 * production dans `app/src/main/java/com/lop/budget/domain/RecurrenceEngine.kt`.
 *
 * ## Traçabilité cas -> CA / invariant -> fonction de production
 * | Cas             | CA / convention        | Fonction de production visée              |
 * |-----------------|------------------------|-------------------------------------------|
 * | B-01..B-03      | CA-02                  | `generateOccurrences` (filtre de fenêtre)  |
 * | B-04            | CA-11                  | `generateOccurrences` (`endDate`)          |
 * | B-05            | CA-02, I-1             | `generateOccurrences` (absence d'état)     |
 * | B-06a/b/c       | CA-11                  | `generateOccurrences` (`maxOccurrences`)   |
 * | B-07, B-08      | CA-11                  | `generateOccurrences` (fin vs maximum)     |
 * | B-09            | CA-11                  | `generateOccurrences` (branche WEEKLY)     |
 * | B-10            | CA-12                  | `generateOccurrences` (`isCancelled`)      |
 * | B-11, B-12      | CA-11                  | `moveCalendar` (DAILY, changements d'heure)|
 * | F-01, F-02      | CA-02, P-1             | `generateOccurrences` (bornes incluses)    |
 * | F-03            | CA-11, P-1             | `generateOccurrences` (fin de série incl.) |
 * | F-04, F-04-bis  | CA-11, P-2             | `moveCalendar` (MONTHLY, ancrage)          |
 * | F-05, F-05-bis  | CA-11, P-2             | `moveCalendar` (YEARLY, 29 février)        |
 *
 * ## Anomalies ouvertes — rouges légitimes attendus (7 sur 21)
 * - **LOP-115** : `maxOccurrences` et les jours sélectionnés sont décomptés depuis le début
 *   de la fenêtre consultée au lieu du début de série. `count++` n'est atteint que sous
 *   `if (currentDate >= startRange)` (RecurrenceEngine.kt:74-82), donc les slots antérieurs
 *   à la fenêtre ne consomment pas le quota. Viole CA-11.
 *   Rouges attendus : **B-06b, B-06c, B-09**.
 * - **LOP-116** : l'ancrage calendaire dérive après un rabattement. `moveCalendar` fait
 *   `Calendar.add(MONTH/YEAR, interval)` sur la date déjà rabattue (RecurrenceEngine.kt:140-148),
 *   le jour d'origine n'est jamais conservé. Viole CA-11 / convention P-2.
 *   Rouges attendus : **F-04, F-04-bis, F-05, F-05-bis**.
 *
 * Ces oracles ne sont volontairement pas assouplis : ils portent la règle validée le
 * 8 septembre 2026, pas le comportement actuel de `java.util.Calendar`. Ils doivent virer
 * au vert par correction de la production, jamais par ajustement de l'attendu.
 *
 * ## Sensibilité vérifiée
 * Les 14 cas verts ont été éprouvés par mutation temporaire du moteur : fin de série
 * ignorée casse B-04, B-07 et F-03 ; ajout fixe de 86 400 000 ms casse B-11 et B-12 ;
 * borne haute exclusive casse F-01 et F-02. Mutations retirées après contrôle.
 *
 * ## Hors périmètre de ce fichier
 * - Persistance, fusion série/exception/ponctuelle, soft-delete, marqueurs de suppression
 *   (CA-05, CA-07 à CA-10, CA-14) : niveau intégration Room, tickets dédiés.
 * - Création ponctuelle et matérialisation (CA-01, CA-06) : tickets d'écriture.
 * - `status` et `kind` des occurrences virtuelles : aucun contrat défini dans TC-85 ni
 *   dans LOP-49, donc non assertés ici plutôt qu'assertés d'après le code.
 * - Mode « saut de période » et choix utilisateur : EVOL séparée.
 * - Heures locales inexistantes ou ambiguës, fréquence `NONE`, intervalle nul ou négatif,
 *   données invalides : aucun scénario ajouté sans exigence définissant leur traitement.
 */
class RecurrenceEngineBoundsTest {

    private val zoneId = ZoneId.of("Europe/Paris")
    private var defaultTimeZone: TimeZone? = null
    private var defaultLocale: Locale? = null

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        defaultTimeZone?.let { TimeZone.setDefault(it) }
        defaultLocale?.let { Locale.setDefault(it) }
    }

    // --- Helpers de dates : construction explicite, aucune dépendance à aujourd'hui ---

    private fun at09(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth)
            .atTime(LocalTime.of(9, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    private fun atTime(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int): Long =
        LocalDate.of(year, month, dayOfMonth)
            .atTime(LocalTime.of(hour, minute))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    private fun startOfDay(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

    private fun endOfDay(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth)
            .atTime(LocalTime.MAX)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    /** Instant UTC écrit explicitement, pour les cas de changement d'heure. */
    private fun utc(isoInstant: String): Long = Instant.parse(isoInstant).toEpochMilli()

    private fun localDateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()

    private fun localTimeOf(millis: Long): LocalTime =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalTime()

    // --- Fixture de base imposée par le JDD du ticket ---

    /**
     * Série de base TC-85 : ID 201, « Abonnement limites », 20.0, EXPENSE, compte 11,
     * catégorie 21, début 2026-01-05 à 09:00, DAILY, intervalle 1, jours null, fin null,
     * maximum null, non annulée, note et rattachements null.
     *
     * Chaque variante repart de cette fixture. Tout paramètre conditionnant une cardinalité
     * (fenêtre, `endDate`, `maxOccurrences`) est déclaré localement dans le test concerné.
     */
    private fun baseSeries(
        frequency: RecurrenceFrequency = RecurrenceFrequency.DAILY,
        interval: Int = 1,
        startDate: Long = at09(2026, 1, 5),
        endDate: Long? = null,
        maxOccurrences: Int? = null,
        daysOfWeek: String? = null,
        isCancelled: Boolean = false
    ): RecurringSeriesEntity = RecurringSeriesEntity(
        id = 201L,
        title = "Abonnement limites",
        amount = 2_000,
        type = TransactionType.EXPENSE,
        categoryId = 21L,
        accountId = 11L,
        frequency = frequency,
        interval = interval,
        startDate = startDate,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
        daysOfWeek = daysOfWeek,
        isCancelled = isCancelled,
        note = null,
        linkedGoalId = null,
        linkedDebtId = null
    )

    // --- Oracle commun ---

    /**
     * Oracle exact appliqué à chaque cas : cardinalité, séquence de dates, ordre,
     * identité de slot (I-2) et non-mutation de la série d'entrée (I-1).
     *
     * `expectedDates` est toujours écrit depuis le contrat, jamais produit par le moteur
     * testé ni par une copie de sa boucle.
     */
    private fun assertOccurrences(
        caseId: String,
        seriesBefore: RecurringSeriesEntity,
        seriesAfter: RecurringSeriesEntity,
        occurrences: List<TransactionEntity>,
        expectedDates: List<Long>
    ) {
        assertEquals(
            "$caseId — I-1 : generateOccurrences ne doit pas modifier la série d'entrée",
            seriesBefore,
            seriesAfter
        )
        assertEquals(
            "$caseId — cardinalité exacte attendue",
            expectedDates.size,
            occurrences.size
        )
        assertEquals(
            "$caseId — séquence exacte des dates d'occurrence (ordre chronologique)",
            expectedDates.map { describe(it) },
            occurrences.map { describe(it.date) }
        )

        val ids = occurrences.map { it.id }
        assertTrue(
            "$caseId — I-2 : tout ID d'occurrence virtuelle doit être strictement négatif, obtenu $ids",
            ids.all { it < 0 }
        )
        assertEquals(
            "$caseId — I-2 : un ID virtuel distinct par slot",
            occurrences.size,
            ids.toSet().size
        )

        occurrences.forEachIndexed { index, tx ->
            assertEquals(
                "$caseId — I-2 : lien vers la série parente sur l'occurrence #$index",
                seriesBefore.id,
                tx.seriesId
            )
            assertEquals(
                "$caseId — I-2 : seriesDate du slot #$index",
                expectedDates[index],
                tx.seriesDate
            )
        }
        assertEquals(
            "$caseId — I-2 : unicité du couple (seriesId, seriesDate)",
            occurrences.size,
            occurrences.map { it.seriesId to it.seriesDate }.toSet().size
        )
    }

    /** Représentation lisible d'un instant, pour que l'échec se diagnostique sans debugger. */
    private fun describe(millis: Long): String =
        "${Instant.ofEpochMilli(millis).atZone(zoneId)} ($millis)"

    // =====================================================================================
    // Limites de génération (B-01 à B-10) — CA-02, CA-11, CA-12
    // =====================================================================================

    /** B-01 : fenêtre entièrement antérieure au début de série -> liste vide. (CA-02) */
    @Test
    fun givenWindowBeforeSeriesStart_whenGenerateOccurrences_thenReturnsEmptyList_B01() {
        val series = baseSeries()
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 1)
        val rangeEnd = endOfDay(2026, 1, 4)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        assertOccurrences("B-01", seriesCopy, series, result, emptyList())
    }

    /**
     * B-02 : fenêtre postérieure au début de série -> les 6, 7 et 8 janvier, exactement 3.
     * Seul cas où la charge utile complète est assertée (CA-02 « dates et valeurs attendues ») ;
     * les autres cas portent sur les dates et la cardinalité.
     */
    @Test
    fun givenWindowAfterSeriesStart_whenGenerateOccurrences_thenReturnsThreeDatesWithSeriesPayload_B02() {
        val series = baseSeries()
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 6)
        val rangeEnd = endOfDay(2026, 1, 8)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 6),
            at09(2026, 1, 7),
            at09(2026, 1, 8)
        )
        assertOccurrences("B-02", seriesCopy, series, result, expectedDates)

        result.forEachIndexed { index, tx ->
            assertEquals("B-02 — CA-02 : titre repris de la série (occurrence #$index)", "Abonnement limites", tx.title)
            assertEquals("B-02 — CA-02 : montant repris de la série (occurrence #$index)", 2_000L, tx.amount)
            assertEquals("B-02 — CA-02 : type repris de la série (occurrence #$index)", TransactionType.EXPENSE, tx.type)
            assertEquals("B-02 — CA-02 : compte repris de la série (occurrence #$index)", 11L, tx.accountId)
            assertEquals("B-02 — CA-02 : catégorie reprise de la série (occurrence #$index)", 21L, tx.categoryId)
            assertNull("B-02 — CA-02 : note nulle dans le JDD (occurrence #$index)", tx.note)
            assertNull("B-02 — CA-02 : rattachement objectif nul dans le JDD (occurrence #$index)", tx.linkedGoalId)
            assertNull("B-02 — CA-02 : rattachement dette nul dans le JDD (occurrence #$index)", tx.linkedDebtId)
            assertEquals(
                "B-02 — I-2 : une occurrence non déplacée affiche sa date de slot (occurrence #$index)",
                tx.seriesDate,
                tx.date
            )
            assertFalse(
                "B-02 — I-2 : un virtuel n'est pas une exception persistée (occurrence #$index)",
                tx.isException
            )
        }
    }

    /** B-03 : fenêtre intra-journée hors du slot de 09:00 -> liste vide. (CA-02) */
    @Test
    fun givenIntraDayWindowNotCoveringSlot_whenGenerateOccurrences_thenReturnsEmptyList_B03() {
        val series = baseSeries()
        val seriesCopy = series.copy()
        val rangeStart = atTime(2026, 1, 6, 10, 0)
        val rangeEnd = atTime(2026, 1, 6, 11, 0)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        assertOccurrences("B-03", seriesCopy, series, result, emptyList())
    }

    /**
     * B-04 : `endDate` au 8 janvier 12:00, volontairement non alignée sur un slot.
     * -> 5, 6, 7 et 8 janvier, aucune occurrence après la fin de série. (CA-11)
     */
    @Test
    fun givenSeriesEndDateNotAlignedOnSlot_whenGenerateOccurrences_thenStopsAtLastSlotBeforeEnd_B04() {
        val series = baseSeries(endDate = atTime(2026, 1, 8, 12, 0))
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 1)
        val rangeEnd = endOfDay(2026, 1, 12)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 5),
            at09(2026, 1, 6),
            at09(2026, 1, 7),
            at09(2026, 1, 8)
        )
        assertOccurrences("B-04", seriesCopy, series, result, expectedDates)
    }

    /**
     * B-05 : sans fin ni maximum, une première fenêtre consultée ne doit imposer aucune
     * limite implicite à la suivante. (CA-02, I-1)
     */
    @Test
    fun givenSeriesWithoutEndAndWithoutMax_whenGeneratingASecondWindow_thenNoImplicitLimitFromFirstWindow_B05() {
        val series = baseSeries()
        val seriesCopy = series.copy()

        // Première consultation, volontairement antérieure : elle ne doit rien mémoriser.
        val firstWindow = RecurrenceEngine.generateOccurrences(
            series,
            startOfDay(2026, 1, 1),
            endOfDay(2026, 1, 8)
        )
        assertOccurrences(
            "B-05 (première fenêtre)",
            seriesCopy,
            series,
            firstWindow,
            listOf(at09(2026, 1, 5), at09(2026, 1, 6), at09(2026, 1, 7), at09(2026, 1, 8))
        )

        val rangeStart = startOfDay(2026, 1, 9)
        val rangeEnd = endOfDay(2026, 1, 11)
        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 9),
            at09(2026, 1, 10),
            at09(2026, 1, 11)
        )
        assertOccurrences("B-05 (seconde fenêtre)", seriesCopy, series, result, expectedDates)
    }

    /**
     * Variantes homogènes de B-06 : `maxOccurrences = 3`, même série, fenêtres différentes.
     * Le compteur part du 5 janvier, jamais du début de la fenêtre consultée. (CA-11)
     */
    private fun runB06Variant(
        caseId: String,
        rangeStart: Long,
        rangeEnd: Long,
        expectedDates: List<Long>
    ) {
        val maxOccurrences = 3
        val series = baseSeries(maxOccurrences = maxOccurrences)
        val seriesCopy = series.copy()

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        assertOccurrences(caseId, seriesCopy, series, result, expectedDates)
    }

    /** B-06a : fenêtre englobant le début de série -> 5, 6, 7 janvier. (CA-11) */
    @Test
    fun givenMax3AndWindowCoveringSeriesStart_whenGenerateOccurrences_thenReturnsFirstThreeSlots_B06a() {
        runB06Variant(
            caseId = "B-06a",
            rangeStart = startOfDay(2026, 1, 1),
            rangeEnd = endOfDay(2026, 1, 12),
            expectedDates = listOf(at09(2026, 1, 5), at09(2026, 1, 6), at09(2026, 1, 7))
        )
    }

    /**
     * B-06b : fenêtre démarrant après le 1er slot -> 6 et 7 janvier seulement.
     * Le slot du 5 janvier, hors fenêtre, a déjà consommé une occurrence du quota.
     *
     * ROUGE ATTENDU (LOP-115) : le moteur produit aujourd'hui 6, 7 et 8 janvier.
     */
    @Test
    fun givenMax3AndWindowStartingAfterFirstSlot_whenGenerateOccurrences_thenQuotaCountsFromSeriesStart_B06b() {
        runB06Variant(
            caseId = "B-06b",
            rangeStart = startOfDay(2026, 1, 6),
            rangeEnd = endOfDay(2026, 1, 12),
            expectedDates = listOf(at09(2026, 1, 6), at09(2026, 1, 7))
        )
    }

    /**
     * B-06c : fenêtre postérieure à l'épuisement du quota -> liste vide.
     *
     * ROUGE ATTENDU (LOP-115) : le moteur produit aujourd'hui 8, 9 et 10 janvier.
     */
    @Test
    fun givenMax3AndWindowAfterQuotaExhausted_whenGenerateOccurrences_thenReturnsEmptyList_B06c() {
        runB06Variant(
            caseId = "B-06c",
            rangeStart = startOfDay(2026, 1, 8),
            rangeEnd = endOfDay(2026, 1, 12),
            expectedDates = emptyList()
        )
    }

    /** B-07 : maximum 5 et fin au 7 janvier 12:00 -> la fin de série limite avant le maximum. (CA-11) */
    @Test
    fun givenMax5AndEarlierEndDate_whenGenerateOccurrences_thenEndDateLimitsFirst_B07() {
        val series = baseSeries(
            endDate = atTime(2026, 1, 7, 12, 0),
            maxOccurrences = 5
        )
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 1)
        val rangeEnd = endOfDay(2026, 1, 12)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 5),
            at09(2026, 1, 6),
            at09(2026, 1, 7)
        )
        assertOccurrences("B-07", seriesCopy, series, result, expectedDates)
    }

    /** B-08 : maximum 2 et fin au 10 janvier 12:00 -> le maximum limite avant la fin de série. (CA-11) */
    @Test
    fun givenMax2AndLaterEndDate_whenGenerateOccurrences_thenMaxOccurrencesLimitsFirst_B08() {
        val series = baseSeries(
            endDate = atTime(2026, 1, 10, 12, 0),
            maxOccurrences = 2
        )
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 1)
        val rangeEnd = endOfDay(2026, 1, 12)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 5),
            at09(2026, 1, 6)
        )
        assertOccurrences("B-08", seriesCopy, series, result, expectedDates)
    }

    /**
     * B-09 : WEEKLY intervalle 2, lundi et mercredi (« 1,3 »), début lundi 5 janvier,
     * maximum 3, fenêtre du 8 au 31 janvier -> seulement le 19 janvier.
     * Les slots des 5 et 7 janvier, hors fenêtre, ont déjà consommé deux occurrences ;
     * le 21 janvier ne doit donc pas être produit. (CA-11)
     *
     * ROUGE ATTENDU (LOP-115) : le moteur produit aujourd'hui les 19 et 21 janvier.
     */
    @Test
    fun givenWeeklyEveryTwoWeeksWithMax3_whenWindowStartsAfterFirstWeek_thenReturnsOnlyJan19_B09() {
        val series = baseSeries(
            frequency = RecurrenceFrequency.WEEKLY,
            interval = 2,
            startDate = at09(2026, 1, 5),
            maxOccurrences = 3,
            daysOfWeek = "1,3"
        )
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 8)
        val rangeEnd = endOfDay(2026, 1, 31)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(at09(2026, 1, 19))
        assertOccurrences("B-09", seriesCopy, series, result, expectedDates)
    }

    /** B-10 : série annulée -> aucun virtuel, quelle que soit la fenêtre. (CA-12) */
    @Test
    fun givenCancelledSeries_whenGenerateOccurrences_thenReturnsEmptyList_B10() {
        val series = baseSeries(isCancelled = true)
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 1)
        val rangeEnd = endOfDay(2026, 1, 31)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        assertOccurrences("B-10", seriesCopy, series, result, emptyList())
    }

    // =====================================================================================
    // Changements d'heure (B-11, B-12) — CA-11
    // Instants attendus écrits explicitement, jamais par ajout répétitif de 86 400 000 ms.
    // =====================================================================================

    /**
     * B-11 : passage à l'heure d'été (nuit du 28 au 29 mars 2026).
     * L'heure locale de 09:00 est conservée ; l'écart réel est de 23 h puis 24 h.
     */
    @Test
    fun givenDailySeriesAcrossSpringForward_whenGenerateOccurrences_thenKeepsLocalNineAmAndShifts23hThen24h_B11() {
        val series = baseSeries(startDate = at09(2026, 3, 28))
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 3, 28)
        val rangeEnd = endOfDay(2026, 3, 30)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedInstants = listOf(
            utc("2026-03-28T08:00:00Z"), // 09:00 +01:00
            utc("2026-03-29T07:00:00Z"), // 09:00 +02:00
            utc("2026-03-30T07:00:00Z")  // 09:00 +02:00
        )
        assertOccurrences("B-11", seriesCopy, series, result, expectedInstants)

        assertLocalNineAmOn("B-11", result, listOf(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 30)))
        assertEquals(
            "B-11 — écart réel entre le 28 et le 29 mars : 23 h à cause du passage à l'heure d'été",
            23L * 3_600_000L,
            result[1].date - result[0].date
        )
        assertEquals(
            "B-11 — écart réel entre le 29 et le 30 mars : 24 h",
            24L * 3_600_000L,
            result[2].date - result[1].date
        )
    }

    /**
     * B-12 : retour à l'heure d'hiver (nuit du 24 au 25 octobre 2026).
     * L'heure locale de 09:00 est conservée ; l'écart réel est de 25 h puis 24 h.
     */
    @Test
    fun givenDailySeriesAcrossFallBack_whenGenerateOccurrences_thenKeepsLocalNineAmAndShifts25hThen24h_B12() {
        val series = baseSeries(startDate = at09(2026, 10, 24))
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 10, 24)
        val rangeEnd = endOfDay(2026, 10, 26)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedInstants = listOf(
            utc("2026-10-24T07:00:00Z"), // 09:00 +02:00
            utc("2026-10-25T08:00:00Z"), // 09:00 +01:00
            utc("2026-10-26T08:00:00Z")  // 09:00 +01:00
        )
        assertOccurrences("B-12", seriesCopy, series, result, expectedInstants)

        assertLocalNineAmOn("B-12", result, listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26)))
        assertEquals(
            "B-12 — écart réel entre le 24 et le 25 octobre : 25 h à cause du retour à l'heure d'hiver",
            25L * 3_600_000L,
            result[1].date - result[0].date
        )
        assertEquals(
            "B-12 — écart réel entre le 25 et le 26 octobre : 24 h",
            24L * 3_600_000L,
            result[2].date - result[1].date
        )
    }

    /** Double contrôle imposé par le ticket : instant exact ET représentation locale. */
    private fun assertLocalNineAmOn(
        caseId: String,
        occurrences: List<TransactionEntity>,
        expectedLocalDates: List<LocalDate>
    ) {
        assertEquals(
            "$caseId — dates locales attendues",
            expectedLocalDates,
            occurrences.map { localDateOf(it.date) }
        )
        occurrences.forEachIndexed { index, tx ->
            assertEquals(
                "$caseId — heure locale de l'occurrence #$index inchangée par le changement d'heure",
                LocalTime.of(9, 0),
                localTimeOf(tx.date)
            )
        }
    }

    // =====================================================================================
    // P-1 : bornes inclusives (F-01 à F-03) — CA-02, CA-11
    // =====================================================================================

    /** F-01 : fenêtre alignée sur deux slots -> les deux bornes sont incluses. (CA-02, P-1) */
    @Test
    fun givenWindowBoundsExactlyOnSlots_whenGenerateOccurrences_thenBothBoundsAreIncluded_F01() {
        val series = baseSeries()
        val seriesCopy = series.copy()
        val rangeStart = at09(2026, 1, 6)
        val rangeEnd = at09(2026, 1, 8)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 6),
            at09(2026, 1, 7),
            at09(2026, 1, 8)
        )
        assertOccurrences("F-01", seriesCopy, series, result, expectedDates)
    }

    /** F-02 : fenêtre réduite à un instant coïncidant avec un slot -> 1 occurrence. (CA-02, P-1) */
    @Test
    fun givenWindowReducedToASingleInstantOnASlot_whenGenerateOccurrences_thenReturnsThatSingleSlot_F02() {
        val series = baseSeries()
        val seriesCopy = series.copy()
        val instant = at09(2026, 1, 7)

        val result = RecurrenceEngine.generateOccurrences(series, instant, instant)

        assertOccurrences("F-02", seriesCopy, series, result, listOf(at09(2026, 1, 7)))
    }

    /**
     * F-03 : fin de série exactement sur un slot -> ce slot est inclus, rien après.
     * Chemin indépendant de F-01/F-02 : ici c'est `endDate` qui est éprouvée,
     * pas les bornes de lecture. (CA-11, P-1)
     */
    @Test
    fun givenSeriesEndDateExactlyOnASlot_whenGenerateOccurrences_thenLastSlotIsIncluded_F03() {
        val series = baseSeries(endDate = at09(2026, 1, 7))
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 1)
        val rangeEnd = endOfDay(2026, 1, 10)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 5),
            at09(2026, 1, 6),
            at09(2026, 1, 7)
        )
        assertOccurrences("F-03", seriesCopy, series, result, expectedDates)
    }

    // =====================================================================================
    // P-2 : dernier jour valide, sans dérive (F-04, F-05) — CA-11
    // =====================================================================================

    /**
     * F-04 : MONTHLY intervalle 1 démarrée le 31 janvier 2026.
     * Février se rabat au 28, mais mars et mai reviennent au 31 : le rabattement ne
     * déplace pas l'ancrage d'origine. (CA-11, P-2)
     *
     * ROUGE ATTENDU (LOP-116) : le moteur produit aujourd'hui 31/01, 28/02, puis 28/03,
     * 28/04 et 28/05 — cardinalité correcte, dates fausses à partir de mars.
     */
    @Test
    fun givenMonthlySeriesStartedOn31st_whenGenerateOccurrences_thenClampedMonthDoesNotMoveTheAnchor_F04() {
        val series = baseSeries(
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = at09(2026, 1, 31)
        )
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2026, 1, 1)
        val rangeEnd = endOfDay(2026, 5, 31)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2026, 1, 31),
            at09(2026, 2, 28),
            at09(2026, 3, 31),
            at09(2026, 4, 30),
            at09(2026, 5, 31)
        )
        assertOccurrences("F-04", seriesCopy, series, result, expectedDates)
    }

    /**
     * F-04-bis : même série, fenêtre démarrant après le rabattement de février.
     * Le début de fenêtre ne réinitialise jamais l'ancrage : on attend 31/03, 30/04, 31/05,
     * et les mêmes slots (mêmes IDs) que dans la grande fenêtre. (CA-11, CA-03, P-2)
     *
     * ROUGE ATTENDU (LOP-116) : le moteur produit aujourd'hui 28/03, 28/04 et 28/05.
     */
    @Test
    fun givenMonthlySeriesStartedOn31st_whenWindowStartsAfterTheClampedMonth_thenAnchorIsPreserved_F04bis() {
        val series = baseSeries(
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = at09(2026, 1, 31)
        )
        val seriesCopy = series.copy()

        val wideWindow = RecurrenceEngine.generateOccurrences(
            series,
            startOfDay(2026, 1, 1),
            endOfDay(2026, 5, 31)
        )
        assertEquals(
            "F-04-bis — la grande fenêtre doit contenir exactement 5 occurrences avant comparaison des slots",
            5,
            wideWindow.size
        )

        val rangeStart = startOfDay(2026, 3, 1)
        val rangeEnd = endOfDay(2026, 5, 31)
        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        // Stabilité de slot d'abord : les 3 derniers slots de la grande fenêtre sont les mêmes.
        assertEquals(
            "F-04-bis — CA-03 : les slots de la fenêtre réduite doivent porter les mêmes IDs que dans la grande fenêtre",
            wideWindow.takeLast(3).map { it.id },
            result.map { it.id }
        )

        // Puis les dates attendues, écrites depuis le contrat et non dérivées de la grande fenêtre.
        val expectedDates = listOf(
            at09(2026, 3, 31),
            at09(2026, 4, 30),
            at09(2026, 5, 31)
        )
        assertOccurrences("F-04-bis", seriesCopy, series, result, expectedDates)
    }

    /**
     * F-05 : YEARLY intervalle 1 démarrée le 29 février 2024.
     * Les années non bissextiles se rabattent au 28, mais 2028 retrouve le 29. (CA-11, P-2)
     *
     * ROUGE ATTENDU (LOP-116) : le moteur produit aujourd'hui le 28 février 2028.
     */
    @Test
    fun givenYearlySeriesStartedOnFeb29_whenGenerateOccurrences_thenLeapDayIsRecoveredIn2028_F05() {
        val series = baseSeries(
            frequency = RecurrenceFrequency.YEARLY,
            startDate = at09(2024, 2, 29)
        )
        val seriesCopy = series.copy()
        val rangeStart = startOfDay(2024, 1, 1)
        val rangeEnd = endOfDay(2028, 12, 31)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            at09(2024, 2, 29),
            at09(2025, 2, 28),
            at09(2026, 2, 28),
            at09(2027, 2, 28),
            at09(2028, 2, 29)
        )
        assertOccurrences("F-05", seriesCopy, series, result, expectedDates)
    }

    /**
     * F-05-bis : même série, fenêtre réduite à l'année bissextile 2028.
     * Une seule occurrence, le 29 février 2028, et le même slot (même ID) que dans la
     * grande fenêtre. (CA-11, CA-03, P-2)
     *
     * ROUGE ATTENDU (LOP-116) : le moteur produit aujourd'hui le 28 février 2028.
     */
    @Test
    fun givenYearlySeriesStartedOnFeb29_whenWindowIsLimitedToLeapYear2028_thenReturnsFeb29Only_F05bis() {
        val series = baseSeries(
            frequency = RecurrenceFrequency.YEARLY,
            startDate = at09(2024, 2, 29)
        )
        val seriesCopy = series.copy()

        val wideWindow = RecurrenceEngine.generateOccurrences(
            series,
            startOfDay(2024, 1, 1),
            endOfDay(2028, 12, 31)
        )
        assertEquals(
            "F-05-bis — la grande fenêtre doit contenir exactement 5 occurrences avant comparaison des slots",
            5,
            wideWindow.size
        )

        val rangeStart = startOfDay(2028, 1, 1)
        val rangeEnd = endOfDay(2028, 12, 31)
        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        assertEquals(
            "F-05-bis — CA-03 : le slot de 2028 doit porter le même ID que dans la grande fenêtre",
            wideWindow.takeLast(1).map { it.id },
            result.map { it.id }
        )

        assertOccurrences("F-05-bis", seriesCopy, series, result, listOf(at09(2028, 2, 29)))
    }
}
