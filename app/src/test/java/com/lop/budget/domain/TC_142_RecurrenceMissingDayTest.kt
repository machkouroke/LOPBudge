package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.MissingDay
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.MissingDayBehavior.LAST_VALID_DAY
import com.lop.budget.domain.model.MissingDayBehavior.SKIP_PERIOD
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-142 — Jour absent : grille, détection bornée et ancrage reporté (LOP-88).
 *
 * Fiche : https://app.notion.com/p/b3ee9f15017a4be3aa12d500fe681703
 * US : https://app.notion.com/p/6233b396328445f4924475e394af6e79 — CA-01, CA-04, CA-05, CA-08,
 * CA-09, CA-11 ; I-1, P-1, P-4. Code lu au commit d7d7c49.
 *
 * ## Niveau
 * Unitaire JVM : ni Android, ni base, ni doublure.
 *
 * ## Chaîne exercée
 * `RecurrenceEngine` seul, sur des séries construites en mémoire (`id = 101`, identité locale au
 * calcul, jamais allouée par une base).
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * G-01  CA-04, I-1   generateOccurrences  mensuel au 31, dernier jour
 * G-02  CA-05, I-1   generateOccurrences  mensuel au 31, sauter
 * G-03  CA-04/05     generateOccurrences  annuel au 29 février, deux comportements
 * G-04  CA-09, P-1   generateOccurrences  sauter + maxOccurrences=4, fenêtre entière et réduite
 * G-05  CA-01        firstMissingDay      indépendant du choix ; intervalle 2 / limite 3 → null
 * G-06  CA-01        firstMissingDay      bornes endDate et maxOccurrences
 * G-07  CA-01/08     firstMissingDay      D/W/N, mensuel au 28, annuel 2096 → 2100
 * G-08  CA-11, P-4   generateOccurrences + firstMissingDay  série ancrée au 31 depuis le 28 février
 * G-09  CA-11, P-4   carriedAnchorDay     report depuis une date rabattue ou non
 * G-10  CA-05/09     nextOccurrences      sauter, sans et avec limite
 * G-11  CA-12, P-6   generateOccurrences, nextOccurrences, firstMissingDay  aucune période n'a le jour (LOP-193)
 * ```
 *
 * ## Oracles
 * Toutes les dates attendues sont écrites en clair à 09:00 Paris, jamais produites par le moteur.
 * Chaque occurrence est comparée **entière** à l'occurrence virtuelle attendue, ID mis à part ;
 * l'ID est jugé sur ses propriétés (négatif, unique, stable entre deux lectures), jamais recalculé
 * par `calculateVirtualId`.
 *
 * ## Hypothèses levées
 * - Montage validé le 5 octobre 2026 par `RecurrenceEngineGridTest` (14/14).
 * - Une méthode par cas et par sous-variante plutôt qu'un exécuteur paramétré : chaque variante
 *   déclare ses propres bornes de fenêtre et limites, et son nom porte l'ID.
 *
 * ## ANO connues
 * Aucune sur la matrice : les 24 cas sont verts au premier passage, le 5 octobre 2026.
 *
 * Constat hors matrice, révélé par la mutation de l'ancrage reporté : une grille « sauter » dont
 * aucune période ne garde le jour d'ancrage ne produit jamais de slot, et `generateOccurrences`
 * boucle sans fin. Sonde jetable, code intact : mensuel ancré au 31, début 28 février 2026,
 * intervalle 12, SKIP_PERIOD → `firstMissingDay` rend (31, 28 février 2027), donc l'avertissement
 * s'affiche et « sauter » s'enregistre, puis la lecture de février 2026 → décembre 2027 ne rend
 * jamais la main. D'où le délai de 10 s par cas : un blocage devient un échec.
 * Ouvert en **LOP-193** — https://app.notion.com/p/3f150f34a8c581b88209c0e16c44508f — tranché le
 * 6 octobre 2026 (P-6, CA-12 de LOP-88 : « sauter » refusé et expliqué) et corrigé : le moteur
 * s'arrête après 400 périodes sautées d'affilée, `MissingDay.canSkip` signale le refus. G-11 grille
 * et prochaines rouges avant le correctif (délai dépassé : la boucle elle-même), verts après.
 *
 * ## Preuves de sensibilité (5 octobre 2026)
 * Une mutation de `RecurrenceEngine` à la fois, retirée aussitôt, puis 24/24 au rejeu.
 * ```
 * Compteur de limite avancé après le saut           → G-04 ×2, G-10 limite 4
 * Départ plus protégé contre « jour absent »         → G-08 sauter, G-08 détection
 * Ancrage reporté ignoré                             → G-08 ×3 (sauter par délai expiré)
 * Slot calculé depuis le slot précédent (dérive)     → G-01, G-02, G-03 ×2, G-04 ×2, G-10 ×2
 * Détection selon le choix de la série               → G-05 sauter
 * Détection sans les limites de série                → G-05 intervalle 2, G-06 fin 27, G-06 limite 1
 * Report sans condition de rabattement               → G-09 M31 (31 mars)
 * Plafond de détection à 1 période                   → G-05 ×2, G-06 ×2, G-07 Y100, G-08 détection
 * Statut virtuel PAID                                → les 10 cas de grille
 * take(count) avant le filtre « après »              → G-10 sans limite
 * Annuel jamais absent                               → G-03 sauter, G-07 Y100
 * « Sauter » toujours possible (canSkip = true)       → G-11 détection
 * Arrêt après 400 périodes sautées retiré            → G-11 grille, G-11 prochaines (délai dépassé)
 * ```
 * Limite connue : la garde `maxOccurrences == null` du raccourci de fenêtre n'est pas prouvée.
 * Avec les fenêtres de G-04 et G-10, le raccourci calcule de toute façon le pas 0.
 *
 * ## Hors périmètre
 * Persistance, migration (TC-145, à la corbeille), réactivité des lecteurs et portées d'écriture
 * (TC-144), décision avant sauvegarde (TC-143), rendu de l'avertissement (TC-141). Aucun cas sur
 * des entrées invalides hors contrat.
 *
 * ## Exécution
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*RecurrenceMissingDayTest"
 * ./gradlew :app:testDebugUnitTest
 * ```
 */
class RecurrenceMissingDayTest {

    /**
     * Une grille « sauter » dont aucune période ne garde le jour d'ancrage ne produit jamais de
     * slot : le moteur boucle alors sans fin. Le délai transforme ce blocage en échec du cas.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // G-01 à G-04 — grille produite
    // =============================================================================================

    @Test
    fun `G-01 - given M31 dernier jour, when fenetre 31 janvier a 31 mai, then 31 janvier, 28 fevrier, 31 mars, 30 avril, 31 mai`() {
        val m31 = monthly31(behavior = LAST_VALID_DAY)
        val from = at(2026, 1, 31)
        val to = at(2026, 5, 31)

        assertOccurrences(
            "G-01 / CA-04, I-1",
            listOf(at(2026, 1, 31), at(2026, 2, 28), at(2026, 3, 31), at(2026, 4, 30), at(2026, 5, 31)),
        ) { RecurrenceEngine.generateOccurrences(m31, from, to) }
    }

    @Test
    fun `G-02 - given M31 sauter, when fenetre 31 janvier a 31 mai, then 31 janvier, 31 mars, 31 mai sans report`() {
        val m31 = monthly31(behavior = SKIP_PERIOD)
        val from = at(2026, 1, 31)
        val to = at(2026, 5, 31)

        assertOccurrences(
            "G-02 / CA-05, I-1",
            listOf(at(2026, 1, 31), at(2026, 3, 31), at(2026, 5, 31)),
        ) { RecurrenceEngine.generateOccurrences(m31, from, to) }
    }

    @Test
    fun `G-03 dernier jour - given Y29, when fenetre 2024 a 2028, then 29 fevrier 2024, 28 fevrier 2025 a 2027, 29 fevrier 2028`() {
        val y29 = yearly29(behavior = LAST_VALID_DAY)
        val from = at(2024, 2, 29)
        val to = at(2028, 2, 29)

        assertOccurrences(
            "G-03 dernier jour / CA-04, I-1",
            listOf(at(2024, 2, 29), at(2025, 2, 28), at(2026, 2, 28), at(2027, 2, 28), at(2028, 2, 29)),
        ) { RecurrenceEngine.generateOccurrences(y29, from, to) }
    }

    @Test
    fun `G-03 sauter - given Y29, when fenetre 2024 a 2028, then 29 fevrier 2024 et 29 fevrier 2028`() {
        val y29 = yearly29(behavior = SKIP_PERIOD)
        val from = at(2024, 2, 29)
        val to = at(2028, 2, 29)

        assertOccurrences(
            "G-03 sauter / CA-05, I-1",
            listOf(at(2024, 2, 29), at(2028, 2, 29)),
        ) { RecurrenceEngine.generateOccurrences(y29, from, to) }
    }

    @Test
    fun `G-04 fenetre entiere - given M31 sauter limite 4, when 31 janvier a 31 mai, then 31 janvier et 31 mars, mai absent`() {
        val m31 = monthly31(behavior = SKIP_PERIOD, maxOccurrences = 4)
        val from = at(2026, 1, 31)
        val to = at(2026, 5, 31)

        assertOccurrences(
            "G-04 fenêtre entière / CA-09, P-1 — février et avril comptent dans la limite",
            listOf(at(2026, 1, 31), at(2026, 3, 31)),
        ) { RecurrenceEngine.generateOccurrences(m31, from, to) }
    }

    @Test
    fun `G-04 fenetre reduite - given M31 sauter limite 4, when 1er mars a 31 mai, then 31 mars seul`() {
        val m31 = monthly31(behavior = SKIP_PERIOD, maxOccurrences = 4)
        val from = at(2026, 3, 1)
        val to = at(2026, 5, 31)

        assertOccurrences(
            "G-04 fenêtre réduite / CA-09, P-1 — la limite reste celle de la série, pas de la fenêtre",
            listOf(at(2026, 3, 31)),
        ) { RecurrenceEngine.generateOccurrences(m31, from, to) }
    }

    // =============================================================================================
    // G-05 à G-07 — premier jour absent
    // =============================================================================================

    @Test
    fun `G-05 dernier jour - given M31 sans limite, when detection, then ancrage 31 et exemple 28 fevrier 2026`() {
        assertMissingDay(
            "G-05 dernier jour / CA-01",
            MissingDay(anchorDay = 31, date = at(2026, 2, 28)),
            monthly31(behavior = LAST_VALID_DAY),
        )
    }

    @Test
    fun `G-05 sauter - given M31 sans limite, when detection, then meme resultat que dernier jour`() {
        assertMissingDay(
            "G-05 sauter / CA-01 — la détection ne dépend pas du choix enregistré",
            MissingDay(anchorDay = 31, date = at(2026, 2, 28)),
            monthly31(behavior = SKIP_PERIOD),
        )
    }

    @Test
    fun `G-05 intervalle 2 limite 3 - given M31 janvier mars mai, when detection, then null quel que soit le choix`() {
        MissingDayBehavior.entries.forEach { behavior ->
            assertMissingDay(
                "G-05 intervalle 2 / limite 3, $behavior / CA-01 — janvier, mars, mai ont tous un 31",
                null,
                monthly31(behavior = behavior, interval = 2, maxOccurrences = 3),
            )
        }
    }

    @Test
    fun `G-06 fin 27 fevrier - given M31, when detection, then null`() {
        assertMissingDay(
            "G-06 fin 27 février / CA-01 — février est hors de la règle",
            null,
            monthly31(behavior = LAST_VALID_DAY, endDate = at(2026, 2, 27)),
        )
    }

    @Test
    fun `G-06 fin 28 fevrier - given M31, when detection, then ancrage 31 et exemple 28 fevrier`() {
        assertMissingDay(
            "G-06 fin 28 février / CA-01 — borne de fin incluse",
            MissingDay(anchorDay = 31, date = at(2026, 2, 28)),
            monthly31(behavior = LAST_VALID_DAY, endDate = at(2026, 2, 28)),
        )
    }

    @Test
    fun `G-06 limite 1 - given M31, when detection, then null`() {
        assertMissingDay(
            "G-06 limite 1 / CA-01 — seul le 31 janvier est visé",
            null,
            monthly31(behavior = LAST_VALID_DAY, maxOccurrences = 1),
        )
    }

    @Test
    fun `G-06 limite 2 - given M31, when detection, then ancrage 31 et exemple 28 fevrier`() {
        assertMissingDay(
            "G-06 limite 2 / CA-01 — février est la 2e période visée",
            MissingDay(anchorDay = 31, date = at(2026, 2, 28)),
            monthly31(behavior = LAST_VALID_DAY, maxOccurrences = 2),
        )
    }

    @Test
    fun `G-07 quotidien hebdomadaire ponctuel - given debut 31 janvier, when detection, then null`() {
        listOf(RecurrenceFrequency.DAILY, RecurrenceFrequency.WEEKLY, RecurrenceFrequency.NONE).forEach { frequency ->
            assertMissingDay(
                "G-07 $frequency / CA-01, CA-08 — aucun avertissement hors mensuel/annuel",
                null,
                series(frequency = frequency, start = at(2026, 1, 31), behavior = LAST_VALID_DAY),
            )
        }
    }

    @Test
    fun `G-07 mensuel au 28 - given debut 28 janvier, when detection, then null`() {
        assertMissingDay(
            "G-07 mensuel au 28 / CA-01, CA-08 — chaque mois a un 28",
            null,
            series(frequency = RecurrenceFrequency.MONTHLY, start = at(2026, 1, 28), behavior = LAST_VALID_DAY),
        )
    }

    @Test
    fun `G-07 Y100 - given annuel 29 fevrier 2096 intervalle 4, when detection, then ancrage 29 et exemple 28 fevrier 2100`() {
        assertMissingDay(
            "G-07 Y100 / CA-01 — 2100 n'est pas bissextile malgré l'intervalle 4",
            MissingDay(anchorDay = 29, date = at(2100, 2, 28)),
            series(
                frequency = RecurrenceFrequency.YEARLY,
                start = at(2096, 2, 29),
                interval = 4,
                behavior = LAST_VALID_DAY,
            ),
        )
    }

    // =============================================================================================
    // G-08 et G-09 — ancrage reporté (P-4)
    // =============================================================================================

    @Test
    fun `G-08 dernier jour - given C31, when fenetre 28 fevrier a 31 mai, then 28 fevrier, 31 mars, 30 avril, 31 mai`() {
        val c31 = carried31(behavior = LAST_VALID_DAY)
        val from = at(2026, 2, 28)
        val to = at(2026, 5, 31)

        assertOccurrences(
            "G-08 dernier jour / CA-11, P-4",
            listOf(at(2026, 2, 28), at(2026, 3, 31), at(2026, 4, 30), at(2026, 5, 31)),
        ) { RecurrenceEngine.generateOccurrences(c31, from, to) }
    }

    @Test
    fun `G-08 sauter - given C31, when fenetre 28 fevrier a 31 mai, then 28 fevrier, 31 mars, 31 mai, le depart reste produit`() {
        val c31 = carried31(behavior = SKIP_PERIOD)
        val from = at(2026, 2, 28)
        val to = at(2026, 5, 31)

        assertOccurrences(
            "G-08 sauter / CA-11, P-4 — l'occurrence éditée (départ) est produite même avec sauter",
            listOf(at(2026, 2, 28), at(2026, 3, 31), at(2026, 5, 31)),
        ) { RecurrenceEngine.generateOccurrences(c31, from, to) }
    }

    @Test
    fun `G-08 detection - given C31, when detection, then ancrage 31 et exemple 30 avril`() {
        assertMissingDay(
            "G-08 détection / CA-11, P-4 — le départ du 28 février n'est pas un jour absent",
            MissingDay(anchorDay = 31, date = at(2026, 4, 30)),
            carried31(behavior = LAST_VALID_DAY),
        )
    }

    @Test
    fun `G-09 - given M31, when report depuis 28 fevrier puis 31 mars, then 31 puis null`() {
        val m31 = monthly31(behavior = LAST_VALID_DAY)

        assertEquals(
            "G-09 M31 depuis le 28 février / CA-11, P-4 — date rabattue : le 31 est repris",
            31,
            RecurrenceEngine.carriedAnchorDay(m31, at(2026, 2, 28)),
        )
        assertEquals(
            "G-09 M31 depuis le 31 mars / CA-11, P-4 — date non rabattue : rien à reporter",
            null,
            RecurrenceEngine.carriedAnchorDay(m31, at(2026, 3, 31)),
        )
    }

    @Test
    fun `G-09 - given Y29, when report depuis 28 fevrier 2025, then 29`() {
        assertEquals(
            "G-09 Y29 depuis le 28 février 2025 / CA-11, P-4",
            29,
            RecurrenceEngine.carriedAnchorDay(yearly29(behavior = LAST_VALID_DAY), at(2025, 2, 28)),
        )
    }

    @Test
    fun `G-09 - given quotidien et hebdomadaire, when report depuis 28 fevrier, then null`() {
        listOf(RecurrenceFrequency.DAILY, RecurrenceFrequency.WEEKLY).forEach { frequency ->
            assertEquals(
                "G-09 $frequency depuis le 28 février / CA-11 — aucun ancrage hors mensuel/annuel",
                null,
                RecurrenceEngine.carriedAnchorDay(
                    series(frequency = frequency, start = at(2026, 1, 31), behavior = LAST_VALID_DAY),
                    at(2026, 2, 28),
                ),
            )
        }
    }

    // =============================================================================================
    // G-10 — prochaines échéances
    // =============================================================================================

    @Test
    fun `G-10 sans limite - given M31 sauter, when 2 prochaines apres 31 janvier, then 31 mars et 31 mai`() {
        val m31 = monthly31(behavior = SKIP_PERIOD)
        val after = at(2026, 1, 31)

        assertOccurrences(
            "G-10 sans limite / CA-05",
            listOf(at(2026, 3, 31), at(2026, 5, 31)),
        ) { RecurrenceEngine.nextOccurrences(m31, after, count = 2) }
    }

    @Test
    fun `G-10 limite 4 - given M31 sauter, when 2 prochaines apres 31 janvier, then 31 mars seul`() {
        val m31 = monthly31(behavior = SKIP_PERIOD, maxOccurrences = 4)
        val after = at(2026, 1, 31)

        assertOccurrences(
            "G-10 limite 4 / CA-09, P-1 — la limite ne repart pas au début de la demande",
            listOf(at(2026, 3, 31)),
        ) { RecurrenceEngine.nextOccurrences(m31, after, count = 2) }
    }

    // =============================================================================================
    // G-11 — LOP-193 : aucune période de la règle n'a le jour d'ancrage
    // =============================================================================================

    @Test
    fun `G-11 grille - given ancre 31 depuis le 28 fevrier, intervalle 12, sauter, when fenetre 2026 a 2027, then le depart seul et le calcul s'arrete`() {
        val dead = carried31(behavior = SKIP_PERIOD).copy(interval = 12)
        val from = at(2026, 2, 1)
        val to = at(2027, 12, 31)

        assertOccurrences(
            "G-11 grille / LOP-193 — seuls des 28/29 février : rien après le départ, sans boucle infinie",
            listOf(at(2026, 2, 28)),
        ) { RecurrenceEngine.generateOccurrences(dead, from, to) }
    }

    @Test
    fun `G-11 detection - given ancre 31 depuis le 28 fevrier, intervalle 12, when detection, then 28 fevrier 2027 et sauter refuse`() {
        assertMissingDay(
            "G-11 détection / LOP-193, P-6 — aucune période n'a de 31 : « sauter » n'est pas proposé",
            MissingDay(anchorDay = 31, date = at(2027, 2, 28), canSkip = false),
            carried31(behavior = LAST_VALID_DAY).copy(interval = 12),
        )
    }

    @Test
    fun `G-11 prochaines - given ancre 31 depuis le 28 fevrier, intervalle 12, sauter, when 2 prochaines, then aucune et le calcul s'arrete`() {
        val dead = carried31(behavior = SKIP_PERIOD).copy(interval = 12)
        val after = at(2026, 2, 28)

        assertOccurrences(
            "G-11 prochaines / LOP-193 — aucune échéance, vacuité explicite, sans boucle infinie",
            emptyList(),
        ) { RecurrenceEngine.nextOccurrences(dead, after, count = 2) }
    }

    // =============================================================================================
    // Jeu de données
    // =============================================================================================

    /** M31 : mensuel depuis le 31 janvier 2026, ancrage reporté null. */
    private fun monthly31(
        behavior: MissingDayBehavior,
        interval: Int = 1,
        endDate: Long? = null,
        maxOccurrences: Int? = null,
    ) = series(
        frequency = RecurrenceFrequency.MONTHLY,
        start = at(2026, 1, 31),
        behavior = behavior,
        interval = interval,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
    )

    /** Y29 : annuel depuis le 29 février 2024, ancrage reporté null. */
    private fun yearly29(behavior: MissingDayBehavior) = series(
        frequency = RecurrenceFrequency.YEARLY,
        start = at(2024, 2, 29),
        behavior = behavior,
    )

    /** C31 : mensuel depuis le 28 février 2026, ancrage reporté 31. */
    private fun carried31(behavior: MissingDayBehavior) = series(
        frequency = RecurrenceFrequency.MONTHLY,
        start = at(2026, 2, 28),
        behavior = behavior,
        anchorDayOfMonth = 31,
    )

    private fun series(
        frequency: RecurrenceFrequency,
        start: Long,
        behavior: MissingDayBehavior,
        interval: Int = 1,
        endDate: Long? = null,
        maxOccurrences: Int? = null,
        anchorDayOfMonth: Int? = null,
    ) = RecurringSeriesEntity(
        id = SERIES_ID,
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        categoryId = CATEGORY_ID,
        accountId = ACCOUNT_ID,
        frequency = frequency,
        interval = interval,
        startDate = start,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
        daysOfWeek = null,
        isCancelled = false,
        note = null,
        linkedGoalId = null,
        linkedLoanId = null,
        missingDayBehavior = behavior,
        anchorDayOfMonth = anchorDayOfMonth,
    )

    /** Occurrence virtuelle attendue à [date], écrite en clair. L'ID est jugé à part. */
    private fun virtualAt(date: Long) = TransactionEntity(
        id = 0,
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD,
        date = date,
        accountId = ACCOUNT_ID,
        categoryId = CATEGORY_ID,
        note = null,
        paidAt = null,
        seriesId = SERIES_ID,
        seriesDate = date,
        isException = false,
        linkedGoalId = null,
        linkedLoanId = null,
        cardId = null,
        deleted = false,
    )

    // =============================================================================================
    // Oracles
    // =============================================================================================

    /**
     * Liste exacte (cardinalité, ordre, champs), IDs négatifs et uniques, puis seconde lecture :
     * mêmes IDs dans le même ordre.
     */
    private fun assertOccurrences(label: String, expectedDates: List<Long>, read: () -> List<TransactionEntity>) {
        val first = read()
        assertEquals(
            "$label — dates produites, cardinalité et ordre exacts ; observé ${describe(first)}",
            expectedDates.map(::text),
            first.map { text(it.date) },
        )
        first.forEachIndexed { index, occurrence ->
            assertEquals(
                "$label [${index + 1}] — occurrence virtuelle complète, ID mis à part",
                virtualAt(expectedDates[index]),
                occurrence.copy(id = 0),
            )
            assertTrue("$label [${index + 1}] — ID virtuel négatif ; observé ${occurrence.id}", occurrence.id < 0)
        }
        val ids = first.map { it.id }
        assertEquals("$label — IDs virtuels uniques : $ids", ids.size, ids.toSet().size)
        assertEquals("$label — IDs virtuels stables entre deux lectures", ids, read().map { it.id })
    }

    private fun assertMissingDay(label: String, expected: MissingDay?, series: RecurringSeriesEntity) {
        val actual = RecurrenceEngine.firstMissingDay(series)
        assertEquals(
            "$label — attendu ${describe(expected)} ; observé ${describe(actual)}",
            expected,
            actual,
        )
    }

    private fun text(millis: Long): String = Instant.ofEpochMilli(millis).atZone(PARIS).toLocalDateTime().toString()

    private fun describe(list: List<TransactionEntity>) = list.joinToString(prefix = "[", postfix = "]") { text(it.date) }

    private fun describe(missingDay: MissingDay?) =
        missingDay?.let { "{ancrage=${it.anchorDay}, exemple=${text(it.date)}, sauter possible=${it.canSkip}}" } ?: "null"

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        const val SERIES_ID = 101L
        const val ACCOUNT_ID = 201L
        const val CATEGORY_ID = 301L
        const val TITLE = "ZZ_L88_grille"
        const val AMOUNT = 12_345L

        /** Instant à 09:00 heure de Paris. */
        fun at(year: Int, month: Int, day: Int): Long =
            LocalDateTime.of(year, month, day, 9, 0).atZone(PARIS).toInstant().toEpochMilli()
    }
}
