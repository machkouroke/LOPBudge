package com.lop.budget.ui.screens.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.transaction.DaySelection
import com.lop.budget.domain.usecase.transaction.MonthOccurrences
import com.lop.budget.domain.usecase.transaction.ObserveRecurringOccurrencesUseCase
import com.lop.budget.domain.usecase.transaction.OccurrenceRef
import com.lop.budget.domain.usecase.transaction.SeriesContext
import com.lop.budget.domain.usecase.transaction.TargetResolution
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-139 — Calendrier récurrent : état, concurrence et restauration du ViewModel (LOP-7).
 *
 * Fiche : https://app.notion.com/p/4e86fef453a249bcad60327c4327b970
 * US : https://app.notion.com/p/38550f34a8c58167861ec6e2eb86cb20 — CA-02 à CA-07, I-1 à I-3.
 * Commit visé : 70396a76fee364839ca0d168d13d710238969d6a.
 *
 * ## Niveau
 * ViewModel JVM. Système testé : `SeriesCalendarViewModel`, avec un vrai `SavedStateHandle` et ses
 * vraies transformations de flux.
 *
 * ## Chaîne réelle / doublée
 * Seule doublure : `ObserveRecurringOccurrencesUseCase`, en MockK strict. Contexte et mois sont des
 * flux pilotés à la main ; chaque requête, abonnement et annulation est journalisé. Les données
 * qu'ils portent ne sont **pas** un oracle métier : ce que Room lit réellement est prouvé par TC-140.
 * Ici on vérifie les arguments reçus, l'état traduit et les événements.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * V-01  CA-02          uiState initial, ouverture sur le contexte, goToNextDue sans échéance
 * V-02  CA-03          previousMonth / nextMonth / pickMonth / goToNextDue
 * V-03  CA-03, CA-07   flatMapLatest du mois, garde du mois discordant, selectDay pendant Loading
 * V-04  CA-04, CA-05   selectDay (unique → ouverture), openOccurrence, events
 * V-05  CA-07          contexte absent, erreurs de contexte et de mois, retry
 * V-06  CA-07          réactivité de l'état sans retry ni navigation spontanée
 * V-07  CA-06          clés calendar.month / calendar.day du SavedStateHandle, reconstruction
 * ```
 *
 * ## Doublure stricte et pièges
 * Chaque méthode du use case reçoit d'abord un **piège** : tout appel non prévu par le cas (autre
 * série, autre zone, autre mois, couple (mois, jour) non déclaré, revalidation non attendue) est
 * consigné dans [forbidden] et le cas échoue sur ce journal, avec un message métier. Les pièges
 * emploient `any()` parce qu'ils ne produisent jamais le résultat attendu : ils ne servent qu'à
 * refuser. Les stubs exacts du cas, déclarés après, les masquent (MockK retient le dernier stub qui
 * correspond).
 *
 * ## Synchronisation
 * Temps virtuel uniquement : `advanceUntilIdle()` puis `runCurrent()`, car les collecteurs vivent
 * dans `backgroundScope`. Aucune attente réelle n'existe à ce niveau : un état absent échoue tout de
 * suite sur l'assertion, avec requêtes, événements et appels interdits dans le message.
 *
 * ## Hypothèses levées
 * - Montage validé le 4 octobre 2026 par `TC_116_TransactionDetailQuickEditTest` (7/7).
 * - Le fuseau Paris est forcé avant construction : le ViewModel capture `ZoneId.systemDefault()`.
 * - Clés sauvegardées réelles : `startId`, `calendar.month`, `calendar.day`, chaînes ISO.
 *
 * ## ANO connues
 * Aucune : les 17 cas sont verts au premier passage, le 4 octobre 2026.
 *
 * ## Preuves de sensibilité
 * Une mutation de `SeriesCalendarViewModel` à la fois, retirée aussitôt.
 * ```
 * Jour non effacé au changement de mois       → V-02, V-03a/b, V-04e–g, V-07
 * Garde du mois discordant retirée            → V-03b
 * flatMapLatest → flatMapMerge (mois)         → V-03a, par le seul journal d'annulation : la garde
 *                                               du mois discordant protège encore l'état affiché
 * Loading retiré au relancement du mois       → V-05c
 * Ouverture avec l'ID de la ligne touchée     → V-04b, V-04e
 * Jour à occurrence unique n'ouvre plus       → V-04e–g
 * Retry qui change de mois                    → V-05c
 * Départ introuvable affiché en Loading       → V-05a
 * Jour d'ouverture réimposé à chaque contexte → V-06, V-07
 * Ouverture automatique d'une occurrence seule→ V-01b, V-02, V-03a/b, V-04e–g, V-06, V-07
 * Clé calendar.month renommée                 → V-07
 * ```
 * Limite : le clic sur un jour pendant Loading (V-03a) est protégé par trois défenses successives
 * (Loading émis, garde du mois discordant, contrôle du mois dans selectDay). Aucune mutation
 * ponctuelle ne le fait rougir seule ; sa sensibilité n'est démontrée qu'à travers ces défenses.
 *
 * ## Hors périmètre
 * Lectures Room, génération et fusion (TC-140) ; rendu localisé, pile de navigation, défilement,
 * recréation d'activité et mort du processus (TC-138, réserve de l'écart 1). Le ViewModel n'expose
 * pas la position de liste : aucune règle de défilement ne lui est attribuée.
 *
 * ## Exécution
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*SeriesCalendarViewModelTest"
 * ./gradlew :app:testDebugUnitTest
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesCalendarViewModelTest {

    private val useCase = mockk<ObserveRecurringOccurrencesUseCase>()
    private val store = ViewModelStore()
    private val collectors = mutableListOf<Job>()

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    /** Appels qu'aucun cas ne prévoit. Doit rester vide. */
    private val forbidden = mutableListOf<String>()
    private val navigation = mutableListOf<CalendarEvent>()
    private val history = mutableListOf<SeriesCalendarUiState>()

    private val contexts = MutableSharedFlow<SeriesContext?>(replay = 1, extraBufferCapacity = 4)
    private val contextLog = mutableListOf<String>()
    private val months = Months()

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        Dispatchers.setMain(StandardTestDispatcher())

        every { useCase.observeContext(any(), any()) } answers {
            forbidden += "observeContext(${firstArg<Long>()}, ${secondArg<ZoneId>()})"
            emptyFlow()
        }
        every { useCase.observeMonth(any(), any(), any()) } answers {
            forbidden += "observeMonth(${firstArg<Long>()}, ${secondArg<YearMonth>()}, ${thirdArg<ZoneId>()})"
            emptyFlow()
        }
        every { useCase.selectDay(any(), any()) } answers {
            forbidden += "selectDay(${firstArg<MonthOccurrences>()}, ${secondArg<LocalDate>()})"
            DaySelection.Empty
        }
        coEvery { useCase.resolveTarget(any(), any()) } answers {
            forbidden += "resolveTarget(${firstArg<TransactionWithRelations>().transaction.id}, ${secondArg<OccurrenceRef?>()})"
            TargetResolution.Unavailable
        }
        every { useCase.observeUpcoming(any(), any()) } answers {
            forbidden += "observeUpcoming(${firstArg<TransactionWithRelations>().transaction.id})"
            emptyFlow()
        }
    }

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        store.clear()
        Dispatchers.resetMain()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // V-01 — CA-02 : état initial et sélection d'ouverture
    // =============================================================================================

    @Test
    fun `V-01a - given le contexte nominal, when ouverture, then mars, 2 mars, choix multiple et aucune navigation`() =
        runTest {
            allowContext()
            months.allow(MARCH)
            every { useCase.selectDay(MARCH_LOADED, MAR_2) } returns DaySelection.Multiple(listOf(E1, E2))

            val vm = open()
            settle()
            vm.assertState("V-01a / CA-02 — avant tout contexte", SeriesCalendarUiState())

            contexts.emit(CTX_NOMINAL)
            settle()
            vm.assertState("V-01a / CA-02 — contexte reçu, mois en attente", state(MARCH, MAR_2, CalendarContent.Loading, null, true))

            months.answer(1, MARCH_LOADED)
            settle()
            vm.assertState(
                "V-01a / CA-02 — ouverture stabilisée",
                state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Multiple(listOf(E1, E2)), true),
            )
            assertEquals("V-01a / CA-02 — mois demandés", listOf(MARCH), months.requested)
            assertEquals("V-01a / CA-04 — aucune navigation à l'ouverture", emptyList<CalendarEvent>(), navigation)
            assertNoForbidden("V-01a")
        }

    @Test
    fun `V-01b - given aucune echeance future, when ouverture puis prochaine echeance, then janvier et commande inerte`() =
        runTest {
            allowContext()
            months.allow(JANUARY)
            every { useCase.selectDay(JANUARY_LOADED, JAN_31) } returns DaySelection.Single(START)

            val vm = open()
            contexts.emit(CTX_NO_FUTURE)
            settle()
            months.answer(1, JANUARY_LOADED)
            settle()
            val opened = state(JANUARY, JAN_31, CalendarContent.Loaded(JANUARY_LOADED), DaySelection.Single(START), false)
            vm.assertState("V-01b / CA-02 — ouverture sur le départ", opened)

            vm.goToNextDue()
            settle()

            vm.assertState("V-01b / CA-03 — « Prochaine échéance » sans échéance ne change rien", opened)
            assertEquals("V-01b / CA-03 — aucune nouvelle requête", listOf(JANUARY), months.requested)
            assertEquals("V-01b / CA-04 — aucun événement, même pour une occurrence unique à l'ouverture", emptyList<CalendarEvent>(), navigation)
            assertNoForbidden("V-01b")
        }

    // =============================================================================================
    // V-02 — CA-03 : commandes de mois et prochaine échéance lointaine
    // =============================================================================================

    @Test
    fun `V-02 - given mars charge, when precedent, suivant, choix 2036 puis prochaine echeance, then mois exacts et jour efface`() =
        runTest {
            val vm = openNominalMarch(extraMonths = arrayOf(FEBRUARY, FEBRUARY_2036))
            every { useCase.selectDay(FEBRUARY_2036_LOADED, FAR_DAY) } returns DaySelection.Single(V_FAR)

            vm.previousMonth()
            settle()
            vm.assertState("V-02 / CA-03 — précédent : février, jour effacé", state(FEBRUARY, null, CalendarContent.Loading, null, true))
            months.answer(2, FEBRUARY_LOADED)
            settle()
            vm.assertState("V-02 / CA-03 — février livré", state(FEBRUARY, null, CalendarContent.Loaded(FEBRUARY_LOADED), null, true))

            vm.nextMonth()
            settle()
            vm.assertState("V-02 / CA-03 — suivant : mars, jour effacé", state(MARCH, null, CalendarContent.Loading, null, true))
            months.answer(3, MARCH_LOADED)
            settle()
            vm.assertState("V-02 / CA-03 — mars livré", state(MARCH, null, CalendarContent.Loaded(MARCH_LOADED), null, true))

            vm.pickMonth(FEBRUARY_2036)
            settle()
            vm.assertState("V-02 / CA-03 — choix direct : février 2036", state(FEBRUARY_2036, null, CalendarContent.Loading, null, true))
            months.answer(4, FEBRUARY_2036_LOADED)
            settle()
            vm.assertState(
                "V-02 / CA-03 — février 2036 livré",
                state(FEBRUARY_2036, null, CalendarContent.Loaded(FEBRUARY_2036_LOADED), null, true),
            )

            contexts.emit(CTX_FAR)
            settle()
            vm.goToNextDue()
            settle()

            vm.assertState(
                "V-02 / CA-03 — prochaine échéance lointaine",
                state(FEBRUARY_2036, FAR_DAY, CalendarContent.Loaded(FEBRUARY_2036_LOADED), DaySelection.Single(V_FAR), true),
            )
            assertEquals(
                "V-02 / CA-03 — mois demandés, sans mois intermédiaire",
                listOf(MARCH, FEBRUARY, MARCH, FEBRUARY_2036),
                months.requested,
            )
            assertEquals("V-02 / CA-04 — aucune ouverture automatique", emptyList<CalendarEvent>(), navigation)
            assertNoForbidden("V-02")
        }

    // =============================================================================================
    // V-03 — CA-03, CA-07 : réponses tardives, mois discordant, clic pendant le chargement
    // =============================================================================================

    @Test
    fun `V-03a - given mars puis avril demandes, when reponses tardives et clic pendant Loading, then avril seul et rien d'ouvert`() =
        runTest {
            val vm = openFebruaryThenRequestApril()
            val fromApril = history.size

            months.answer(1, FEBRUARY_LOADED)
            months.answer(2, MARCH_LOADED)
            settle()
            vm.assertState("V-03a / CA-03 — réponses tardives ignorées", state(APRIL, null, CalendarContent.Loading, null, true))

            vm.selectDay(APR_1)
            settle()
            vm.assertState("V-03a / CA-07 — clic pendant Loading : jour retenu, rien d'ouvrable", state(APRIL, APR_1, CalendarContent.Loading, null, true))

            months.answer(3, APRIL_LOADED)
            settle()

            vm.assertState(
                "V-03a / CA-03 — avril livré",
                state(APRIL, APR_1, CalendarContent.Loaded(APRIL_LOADED), DaySelection.Empty, true),
            )
            assertEquals(
                "V-03a / CA-03 — les anciennes collectes sont annulées, seule avril reste active",
                setOf("#1 2026-02 abonné", "#1 2026-02 annulé", "#2 2026-03 abonné", "#2 2026-03 annulé", "#3 2026-04 abonné"),
                months.log.toSet(),
            )
            assertNoStaleState("V-03a", history.drop(fromApril))
            coVerify(exactly = 0) { useCase.resolveTarget(any(), any()) } // aucune revalidation, avec quelque cible que ce soit
            assertEquals("V-03a / CA-07 — aucune ouverture", emptyList<CalendarEvent>(), navigation)
            assertNoForbidden("V-03a")
        }

    @Test
    fun `V-03b - given avril demande, when le flux courant rend fevrier, then jamais Loaded pour avril avant le vrai avril`() =
        runTest {
            val vm = openFebruaryThenRequestApril()
            val fromApril = history.size

            months.answer(3, FEBRUARY_LOADED)
            settle()
            vm.assertState("V-03b / CA-03 — réponse d'un autre mois : toujours en chargement", state(APRIL, null, CalendarContent.Loading, null, true))

            months.answer(3, APRIL_LOADED)
            settle()

            vm.assertState("V-03b / CA-07 — le chargement se termine", state(APRIL, null, CalendarContent.Loaded(APRIL_LOADED), null, true))
            assertNoStaleState("V-03b", history.drop(fromApril))
            assertEquals("V-03b — mois demandés", listOf(FEBRUARY, MARCH, APRIL), months.requested)
            assertNoForbidden("V-03b")
        }

    // =============================================================================================
    // V-04 — CA-04, CA-05 : sélection, choix explicite, revalidation
    // =============================================================================================

    @Test
    fun `V-04a - given mars charge, when jour vide puis jour multiple, then selection exacte et aucune ouverture`() =
        runTest {
            val vm = openNominalMarch()
            every { useCase.selectDay(MARCH_LOADED, MAR_15) } returns DaySelection.Empty

            vm.selectDay(MAR_15)
            settle()
            vm.assertState("V-04a / CA-04 — jour vide", state(MARCH, MAR_15, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Empty, true))

            vm.selectDay(MAR_2)
            settle()
            vm.assertState(
                "V-04a / CA-04 — jour multiple",
                state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Multiple(listOf(E1, E2)), true),
            )
            coVerify(exactly = 0) { useCase.resolveTarget(any(), any()) } // aucune revalidation avant un choix explicite
            assertEquals("V-04a / CA-04 — aucune navigation", emptyList<CalendarEvent>(), navigation)
            assertNoForbidden("V-04a")
        }

    @Test
    fun `V-04b - given mars multiple, when E2 touchee et revalidee disponible, then ouverture de l'ID revalide 104`() =
        runTest { rowTouched("V-04b", TargetResolution.Available(REVALIDATED_ID), CalendarEvent.OpenOccurrence(REVALIDATED_ID)) }

    @Test
    fun `V-04c - given mars multiple, when E2 touchee et revalidee comme depart, then retour au depart`() =
        runTest { rowTouched("V-04c", TargetResolution.Start, CalendarEvent.ReturnToStart) }

    @Test
    fun `V-04d - given mars multiple, when E2 touchee et indisponible, then occurrence indisponible`() =
        runTest { rowTouched("V-04d", TargetResolution.Unavailable, CalendarEvent.OccurrenceUnavailable) }

    @Test
    fun `V-04e - given fevrier, when 28 unique selectionne et revalide disponible, then ouverture de l'ID revalide 104`() =
        runTest { singleDaySelected("V-04e", TargetResolution.Available(REVALIDATED_ID), CalendarEvent.OpenOccurrence(REVALIDATED_ID)) }

    @Test
    fun `V-04f - given fevrier, when 28 unique selectionne et revalide comme depart, then retour au depart`() =
        runTest { singleDaySelected("V-04f", TargetResolution.Start, CalendarEvent.ReturnToStart) }

    @Test
    fun `V-04g - given fevrier, when 28 unique selectionne et indisponible, then occurrence indisponible`() =
        runTest { singleDaySelected("V-04g", TargetResolution.Unavailable, CalendarEvent.OccurrenceUnavailable) }

    /** Choix explicite d'une ligne : la cible exacte, revalidée une fois, traduite en un seul événement. */
    private fun TestScope.rowTouched(label: String, resolution: TargetResolution, expected: CalendarEvent) {
        val vm = openNominalMarch()
        coEvery { useCase.resolveTarget(E2, START_REF) } returns resolution

        vm.openOccurrence(E2)
        settle()

        coVerify(exactly = 1) { useCase.resolveTarget(E2, START_REF) }
        assertEquals("$label / CA-05 — un seul événement, issu de la revalidation de E2", listOf(expected), navigation)
        vm.assertState(
            "$label / CA-04 — la sélection ne bouge pas",
            state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Multiple(listOf(E1, E2)), true),
        )
        assertNoForbidden(label)
    }

    /** Jour à occurrence unique : ouverture directe, mais toujours après revalidation. */
    private fun TestScope.singleDaySelected(label: String, resolution: TargetResolution, expected: CalendarEvent) {
        val vm = openNominalMarch(extraMonths = arrayOf(FEBRUARY))
        every { useCase.selectDay(FEBRUARY_LOADED, FEB_28) } returns DaySelection.Single(V_FEB)
        coEvery { useCase.resolveTarget(V_FEB, START_REF) } returns resolution
        vm.previousMonth()
        settle()
        months.answer(2, FEBRUARY_LOADED)
        settle()

        vm.selectDay(FEB_28)
        settle()

        coVerify(exactly = 1) { useCase.resolveTarget(V_FEB, START_REF) }
        assertEquals("$label / CA-04 — un seul événement, issu de la revalidation de V_FEB", listOf(expected), navigation)
        vm.assertState(
            "$label / CA-04 — jour unique sélectionné",
            state(FEBRUARY, FEB_28, CalendarContent.Loaded(FEBRUARY_LOADED), DaySelection.Single(V_FEB), true),
        )
        assertNoForbidden(label)
    }

    // =============================================================================================
    // V-05 — CA-07 : absence, erreurs et reprise
    // =============================================================================================

    @Test
    fun `V-05a - given un contexte absent, when ouverture, then Unavailable et pas Loading permanent`() = runTest {
        allowContext()
        val vm = open()

        contexts.emit(null)
        settle()

        vm.assertState("V-05a / CA-07 — départ introuvable", state(null, null, CalendarContent.Unavailable, null, false))
        assertEquals("V-05a — aucun mois demandé", emptyList<YearMonth>(), months.requested)
        assertNoForbidden("V-05a")
    }

    @Test
    fun `V-05b - given un contexte en erreur, when retry, then contexte puis mois charges`() = runTest {
        allowContext(failFirst = true)
        months.allow(MARCH)
        every { useCase.selectDay(MARCH_LOADED, MAR_2) } returns DaySelection.Multiple(listOf(E1, E2))
        val vm = open()
        settle()
        vm.assertState("V-05b / CA-07 — erreur de contexte, pas un calendrier vide", state(null, null, CalendarContent.Error, null, false))

        contexts.emit(CTX_NOMINAL)
        vm.retry()
        settle()
        vm.assertState("V-05b / CA-07 — retry : contexte obtenu, mois en attente", state(MARCH, MAR_2, CalendarContent.Loading, null, true))
        months.answer(1, MARCH_LOADED)
        settle()

        vm.assertState(
            "V-05b / CA-07 — reprise complète",
            state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Multiple(listOf(E1, E2)), true),
        )
        assertEquals("V-05b — contexte relu après l'échec", listOf("contexte abonné (échec)", "contexte abonné"), contextLog)
        assertNoForbidden("V-05b")
    }

    @Test
    fun `V-05c - given mars en erreur, when retry, then mars redemande, jour conserve et charge`() = runTest {
        allowContext()
        months.allow(MARCH)
        months.failNext(MARCH)
        every { useCase.selectDay(MARCH_LOADED, MAR_2) } returns DaySelection.Multiple(listOf(E1, E2))
        val vm = open()
        contexts.emit(CTX_NOMINAL)
        settle()
        vm.assertState("V-05c / CA-07 — erreur de mois : mars conservé, aucune sélection", state(MARCH, MAR_2, CalendarContent.Error, null, true))

        vm.retry()
        settle()
        vm.assertState("V-05c / CA-07 — retry : même mois, même jour", state(MARCH, MAR_2, CalendarContent.Loading, null, true))
        months.answer(2, MARCH_LOADED)
        settle()

        vm.assertState(
            "V-05c / CA-07 — reprise",
            state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Multiple(listOf(E1, E2)), true),
        )
        assertEquals("V-05c / CA-07 — mars redemandé, aucun mois de repli", listOf(MARCH, MARCH), months.requested)
        assertNoForbidden("V-05c")
    }

    // =============================================================================================
    // V-06 — CA-07 : données actualisées sous le même mois et le même jour
    // =============================================================================================

    @Test
    fun `V-06 - given mars et 2 mars, when donnees puis contexte changent, then etat actualise sans navigation ni changement de mois`() =
        runTest {
            val vm = openNominalMarch()
            every { useCase.selectDay(MARCH_UPDATED, MAR_2) } returns DaySelection.Single(E1_UPDATED)
            every { useCase.selectDay(MARCH_EMPTY, MAR_2) } returns DaySelection.Empty

            months.answer(1, MARCH_UPDATED)
            settle()
            vm.assertState(
                "V-06 / CA-07 — E1 à 121000, E2 retirée",
                state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_UPDATED), DaySelection.Single(E1_UPDATED), true),
            )
            assertEquals("V-06 / CA-04 — une occurrence devenue unique ne s'ouvre pas d'elle-même", emptyList<CalendarEvent>(), navigation)

            months.answer(1, MARCH_EMPTY)
            settle()
            vm.assertState("V-06 / CA-07 — jour devenu vide", state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_EMPTY), DaySelection.Empty, true))

            contexts.emit(CTX_FEB)
            settle()

            vm.assertState(
                "V-06 / CA-07 — une autre prochaine échéance ne change ni le mois ni le jour",
                state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_EMPTY), DaySelection.Empty, true),
            )
            assertEquals("V-06 — aucune nouvelle requête", listOf(MARCH), months.requested)
            assertEquals("V-06 — aucune navigation", emptyList<CalendarEvent>(), navigation)
            assertNoForbidden("V-06")
        }

    // =============================================================================================
    // V-07 — CA-06 : sauvegarde et reconstruction
    // =============================================================================================

    @Test
    fun `V-07 - given mars et 2 mars choisis, when ViewModel detruit puis reconstruit, then mars et 2 mars relus`() =
        runTest {
            allowContext()
            months.allow(JANUARY, MARCH)
            every { useCase.selectDay(JANUARY_LOADED, JAN_31) } returns DaySelection.Single(START)
            every { useCase.selectDay(MARCH_LOADED, MAR_2) } returns DaySelection.Multiple(listOf(E1, E2))
            val handle = SavedStateHandle(mapOf(SeriesCalendarViewModel.ARG_START_ID to START_ID))
            val first = open(handle)
            contexts.emit(CTX_NO_FUTURE)
            settle()
            months.answer(1, JANUARY_LOADED)
            settle()
            first.pickMonth(MARCH)
            settle()
            months.answer(2, MARCH_LOADED)
            settle()
            first.selectDay(MAR_2)
            settle()
            first.assertState(
                "V-07 — préparation : mars et 2 mars choisis",
                state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Multiple(listOf(E1, E2)), false),
            )

            val saved = handle.keys().associateWith { handle.get<Any>(it) }
            assertEquals(
                "V-07 / CA-06 — valeurs sauvegardées exactes",
                mapOf("startId" to START_ID, "calendar.month" to "2026-03", "calendar.day" to "2026-03-02"),
                saved,
            )
            collectors.forEach { it.cancel() }
            store.clear()
            settle()
            assertTrue("V-07 — la lecture du premier ViewModel est annulée : ${months.log}", "#2 2026-03 annulé" in months.log)

            val reopened = MutableSharedFlow<SeriesContext?>(replay = 1, extraBufferCapacity = 4)
            allowContext(source = reopened)
            every { useCase.selectDay(MARCH_RELOADED, MAR_2) } returns DaySelection.Multiple(listOf(E1_UPDATED, E2))
            val second = open(SavedStateHandle(saved))
            settle()
            second.assertState("V-07 / CA-06 — restauré avant tout contexte", state(MARCH, MAR_2, CalendarContent.Loading, null, false))

            reopened.emit(CTX_FEB)
            settle()
            second.assertState(
                "V-07 / CA-06 — le nouveau jour d'ouverture ne s'impose pas",
                state(MARCH, MAR_2, CalendarContent.Loading, null, true),
            )
            months.answer(3, MARCH_RELOADED)
            settle()

            second.assertState(
                "V-07 / CA-06 — données relues, pas une liste mémorisée",
                state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_RELOADED), DaySelection.Multiple(listOf(E1_UPDATED, E2)), true),
            )
            assertEquals("V-07 / CA-06 — mars relu (série 201, Paris)", listOf(JANUARY, MARCH, MARCH), months.requested)
            assertEquals("V-07 — aucune navigation", emptyList<CalendarEvent>(), navigation)
            assertNoForbidden("V-07")
        }

    // =============================================================================================
    // Montage
    // =============================================================================================

    private fun TestScope.open(
        handle: SavedStateHandle = SavedStateHandle(mapOf(SeriesCalendarViewModel.ARG_START_ID to START_ID)),
    ): SeriesCalendarViewModel {
        val vm = ViewModelProvider(
            store,
            viewModelFactory { initializer { SeriesCalendarViewModel(handle, useCase, CLOCK) } },
        )[SeriesCalendarViewModel::class.java]
        // Les flux partagés du ViewModel ne démarrent qu'avec un abonné.
        collectors += backgroundScope.launch { vm.uiState.collect { history += it } }
        collectors += backgroundScope.launch { vm.events.collect { navigation += it } }
        return vm
    }

    /** `advanceUntilIdle()` ne fait pas tourner `backgroundScope` : `runCurrent()` le complète. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    /** Ouverture nominale : mars chargé, 2 mars sélectionné (choix multiple E1, E2). */
    private fun TestScope.openNominalMarch(extraMonths: Array<YearMonth> = emptyArray()): SeriesCalendarViewModel {
        allowContext()
        months.allow(MARCH, *extraMonths)
        every { useCase.selectDay(MARCH_LOADED, MAR_2) } returns DaySelection.Multiple(listOf(E1, E2))
        val vm = open()
        contexts.tryEmit(CTX_NOMINAL)
        settle()
        months.answer(1, MARCH_LOADED)
        settle()
        vm.assertState(
            "préparation — mars chargé",
            state(MARCH, MAR_2, CalendarContent.Loaded(MARCH_LOADED), DaySelection.Multiple(listOf(E1, E2)), true),
        )
        return vm
    }

    /** V-03 : février chargé, puis mars demandé, puis avril demandé avant la réponse de mars. */
    private fun TestScope.openFebruaryThenRequestApril(): SeriesCalendarViewModel {
        allowContext()
        months.allow(FEBRUARY, MARCH, APRIL)
        every { useCase.selectDay(FEBRUARY_LOADED, FEB_28) } returns DaySelection.Single(V_FEB)
        every { useCase.selectDay(APRIL_LOADED, APR_1) } returns DaySelection.Empty
        val vm = open()
        contexts.tryEmit(CTX_FEB)
        settle()
        months.answer(1, FEBRUARY_LOADED)
        settle()
        vm.assertState(
            "préparation — février chargé",
            state(FEBRUARY, FEB_28, CalendarContent.Loaded(FEBRUARY_LOADED), DaySelection.Single(V_FEB), true),
        )
        vm.nextMonth()
        settle()
        vm.nextMonth()
        settle()
        vm.assertState("V-03 — avril demandé avant la réponse de mars", state(APRIL, null, CalendarContent.Loading, null, true))
        return vm
    }

    private fun allowContext(failFirst: Boolean = false, source: MutableSharedFlow<SeriesContext?> = contexts) {
        val live = source.onSubscription { contextLog += "contexte abonné" }
        if (failFirst) {
            val failing = flow<SeriesContext?> {
                contextLog += "contexte abonné (échec)"
                throw IOException("contexte illisible")
            }
            every { useCase.observeContext(START_ID, PARIS) } returnsMany listOf(failing, live)
        } else {
            every { useCase.observeContext(START_ID, PARIS) } returns live
        }
    }

    /** Mois pilotés à la main : chaque appel à `observeMonth` est une requête numérotée. */
    private inner class Months {
        val requested = mutableListOf<YearMonth>()
        val log = mutableListOf<String>()
        private val sources = mutableMapOf<Int, MutableSharedFlow<MonthOccurrences>>()
        private val failures = mutableSetOf<YearMonth>()

        /** Seule la série 201 dans la zone de Paris, et seulement pour ces mois. */
        fun allow(vararg allowed: YearMonth) = allowed.forEach { month ->
            every { useCase.observeMonth(SERIES_A, month, PARIS) } answers { request(month) }
        }

        fun failNext(month: YearMonth) {
            failures += month
        }

        /** Réponse à la requête n° [request] (1 = la première), même si elle a été annulée depuis. */
        fun answer(request: Int, value: MonthOccurrences) {
            val source = checkNotNull(sources[request]) { "requête n° $request inexistante ; requêtes : $requested" }
            check(source.tryEmit(value))
        }

        private fun request(month: YearMonth): Flow<MonthOccurrences> {
            requested += month
            val n = requested.size
            if (failures.remove(month)) {
                return flow {
                    log += "#$n $month abonné (échec)"
                    throw IOException("lecture de $month impossible")
                }
            }
            val source = MutableSharedFlow<MonthOccurrences>(replay = 1, extraBufferCapacity = 4)
            sources[n] = source
            return source.onSubscription { log += "#$n $month abonné" }.onCompletion { log += "#$n $month annulé" }
        }
    }

    // =============================================================================================
    // Oracles
    // =============================================================================================

    private fun state(
        month: YearMonth?,
        day: LocalDate?,
        content: CalendarContent,
        selection: DaySelection?,
        canGoToNextDue: Boolean,
    ) = SeriesCalendarUiState(month, day, content, selection, canGoToNextDue)

    private fun SeriesCalendarViewModel.assertState(label: String, expected: SeriesCalendarUiState) = assertEquals(
        "$label — (mois, jour, contenu, sélection, prochaine échéance) ; requêtes : ${months.log} ; " +
            "événements : $navigation ; appels interdits : $forbidden",
        expected,
        uiState.value,
    )

    private fun assertNoForbidden(label: String) =
        assertEquals("$label — I-2 / I-3 : aucun appel hors du scénario (série, zone, mois, cible)", emptyList<String>(), forbidden)

    /** CA-03 / CA-07 : une fois avril demandé, aucun état n'affiche un autre mois ni la ligne du 28 février. */
    private fun assertNoStaleState(label: String, states: List<SeriesCalendarUiState>) {
        states.forEachIndexed { index, s ->
            val loadedMonth = (s.content as? CalendarContent.Loaded)?.occurrences?.month
            assertEquals("$label / CA-07 — état ${index + 1} : mois demandé ; état : $s", APRIL, s.month)
            assertTrue("$label / CA-03 — état ${index + 1} : contenu d'un autre mois ; état : $s", loadedMonth == null || loadedMonth == APRIL)
            val shown = when (val selection = s.selection) {
                is DaySelection.Single -> listOf(selection.occurrence)
                is DaySelection.Multiple -> selection.occurrences
                else -> emptyList()
            }
            assertTrue("$label / CA-07 — état ${index + 1} : la ligne du 28 février est ouvrable ; état : $s", V_FEB !in shown)
        }
    }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** Horloge figée : « aujourd'hui » n'est pas un oracle de cette fiche, mais ne dépend pas du jour d'exécution. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-03-02T11:00:00Z"), PARIS)

        const val START_ID = 101L
        const val SERIES_A = 201L
        const val E1_ID = 102L
        const val E2_ID = 103L
        const val V_FEB_ID = -102L
        const val V_FAR_ID = -103L

        /** Réponse de la revalidation, volontairement différente de l'ID de la ligne touchée (103). */
        const val REVALIDATED_ID = 104L

        fun at(year: Int, month: Int, day: Int, hour: Int = 9): Long =
            LocalDateTime.of(year, month, day, hour, 0).atZone(PARIS).toInstant().toEpochMilli()

        val ACCOUNT_A = AccountEntity(
            id = 11, name = "ZZ_TC7_compte_A", type = AccountType.CHECKING, initialBalance = 100_000,
            balanceUpdatedAt = 0L, colorArgb = 0xFF2196F3.toInt(), icon = "wallet", archived = false,
        )
        val ACCOUNT_B = AccountEntity(
            id = 12, name = "ZZ_TC7_compte_B", type = AccountType.CHECKING, initialBalance = 100_000,
            balanceUpdatedAt = 0L, colorArgb = 0xFF455A64.toInt(), icon = "wallet", archived = false,
        )
        val C1 = CategoryEntity(id = 21, name = "ZZ_TC7_logement", type = TransactionType.EXPENSE, colorArgb = 0xFF4CAF50.toInt(), icon = "home")
        val C2 = CategoryEntity(id = 22, name = "ZZ_TC7_ajustement", type = TransactionType.EXPENSE, colorArgb = 0xFF6D4C41.toInt(), icon = "shopping_cart")
        val TA = TagEntity(id = 31, name = "ZZ_TC7_fixe", colorArgb = 0xFF4CAF50.toInt())
        val TE1 = TagEntity(id = 32, name = "ZZ_TC7_exception_1", colorArgb = 0xFF6D4C41.toInt())
        val TE2 = TagEntity(id = 33, name = "ZZ_TC7_exception_2", colorArgb = 0xFF7B1FA2.toInt())

        val JAN31_09 = at(2026, 1, 31)
        val FEB28_09 = at(2026, 2, 28)
        val MAR31_09 = at(2026, 3, 31)
        val MAR02_09 = at(2026, 3, 2)
        val MAR02_10 = at(2026, 3, 2, hour = 10)
        val FAR_09 = at(2036, 2, 10)

        /** Occurrence de la série A aux valeurs de Start. */
        fun seriesA(id: Long, slot: Long, isException: Boolean) = TransactionWithRelations(
            TransactionEntity(
                id = id, title = "ZZ_TC7_base", amount = 82_000, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, kind = TransactionKind.STANDARD, date = slot,
                accountId = ACCOUNT_A.id, categoryId = C1.id, note = "contrat A", paidAt = null,
                seriesId = SERIES_A, seriesDate = slot, isException = isException,
            ),
            C1, ACCOUNT_A, listOf(TA),
        )

        val START = seriesA(START_ID, JAN31_09, isException = true)
        val START_REF = OccurrenceRef(START_ID, SERIES_A, JAN31_09)
        val V_FEB = seriesA(V_FEB_ID, FEB28_09, isException = false)
        val V_FAR = seriesA(V_FAR_ID, FAR_09, isException = false)
        val E1 = TransactionWithRelations(
            TransactionEntity(
                id = E1_ID, title = "ZZ_TC7_exception_1", amount = 91_000, type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID, kind = TransactionKind.STANDARD, date = MAR02_09,
                accountId = ACCOUNT_B.id, categoryId = C2.id, note = "exception 1", paidAt = MAR02_09,
                seriesId = SERIES_A, seriesDate = FEB28_09, isException = true,
            ),
            C2, ACCOUNT_B, listOf(TE1),
        )
        val E1_UPDATED = E1.copy(transaction = E1.transaction.copy(amount = 121_000))
        val E2 = TransactionWithRelations(
            TransactionEntity(
                id = E2_ID, title = "ZZ_TC7_exception_2", amount = 73_000, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, kind = TransactionKind.STANDARD, date = MAR02_10,
                accountId = NO_ACCOUNT_ID, categoryId = NO_CATEGORY_ID, note = "exception 2", paidAt = null,
                seriesId = SERIES_A, seriesDate = MAR31_09, isException = true,
            ),
            null, null, listOf(TE2),
        )

        val JANUARY: YearMonth = YearMonth.of(2026, 1)
        val FEBRUARY: YearMonth = YearMonth.of(2026, 2)
        val MARCH: YearMonth = YearMonth.of(2026, 3)
        val APRIL: YearMonth = YearMonth.of(2026, 4)
        val FEBRUARY_2036: YearMonth = YearMonth.of(2036, 2)

        val JAN_31: LocalDate = LocalDate.of(2026, 1, 31)
        val FEB_28: LocalDate = LocalDate.of(2026, 2, 28)
        val MAR_2: LocalDate = LocalDate.of(2026, 3, 2)
        val MAR_15: LocalDate = LocalDate.of(2026, 3, 15)
        val APR_1: LocalDate = LocalDate.of(2026, 4, 1)
        val FAR_DAY: LocalDate = LocalDate.of(2036, 2, 10)

        val JANUARY_LOADED = MonthOccurrences(JANUARY, mapOf(JAN_31 to listOf(START)))
        val FEBRUARY_LOADED = MonthOccurrences(FEBRUARY, mapOf(FEB_28 to listOf(V_FEB)))
        val MARCH_LOADED = MonthOccurrences(MARCH, mapOf(MAR_2 to listOf(E1, E2)))
        val APRIL_LOADED = MonthOccurrences(APRIL, emptyMap())
        val FEBRUARY_2036_LOADED = MonthOccurrences(FEBRUARY_2036, mapOf(FAR_DAY to listOf(V_FAR)))
        val MARCH_UPDATED = MonthOccurrences(MARCH, mapOf(MAR_2 to listOf(E1_UPDATED)))
        val MARCH_EMPTY = MonthOccurrences(MARCH, emptyMap())
        val MARCH_RELOADED = MonthOccurrences(MARCH, mapOf(MAR_2 to listOf(E1_UPDATED, E2)))

        val CTX_NOMINAL = SeriesContext(START_REF, nextDue = E1, nextDueDay = MAR_2, openingDay = MAR_2)
        val CTX_NO_FUTURE = SeriesContext(START_REF, nextDue = null, nextDueDay = null, openingDay = JAN_31)
        val CTX_FEB = SeriesContext(START_REF, nextDue = V_FEB, nextDueDay = FEB_28, openingDay = FEB_28)
        val CTX_FAR = SeriesContext(START_REF, nextDue = V_FAR, nextDueDay = FAR_DAY, openingDay = FAR_DAY)
    }
}
