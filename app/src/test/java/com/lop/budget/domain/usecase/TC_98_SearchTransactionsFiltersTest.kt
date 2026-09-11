package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.util.Locale
import java.util.TimeZone

/**
 * TC-98 — Recherche : filtres, fenêtre et tri. **Niveau use case avec doublure.**
 *
 * Chaîne exercée : `SearchTransactionsUseCase` (réel) → `ObserveTransactionsUseCase` (doublure).
 * Source de vérité : CA-06 à CA-09, CA-11 à CA-13, CA-16 et CA-17 de LOP-70
 * « Préparer le moteur de recherche transaction ».
 * **Le comportement actuel du use case ne constitue pas l'oracle.**
 *
 * ### cas → CA / invariant → production
 * ```
 * G-01, G-02   CA-06 (I-5)   filtre accountId
 * G-03, G-04   CA-07 (I-5)   filtre categoryId
 * G-05, G-06   CA-08 (I-5)   filtre type
 * G-07, G-08   CA-09 (I-5)   filtre status
 * G-09, G-10   CA-11 (I-5)   filtre tagName
 * G-11         CA-12 (I-5)   bornes transmises à observeTransactionsUseCase
 * G-12         CA-13 (I-5)   fenêtre par défaut (P-1) quand les dates sont absentes
 * G-13, G-14   CA-17 (I-5)   combinaison ET texte + filtres
 * G-15, G-16   CA-16 (I-6)   sortedBy { date } et départage à date égale
 * ```
 *
 * ### Doublure
 * Stub à **arguments exacts**. Si le use case demande d'autres bornes que celles du cas, MockK ne
 * trouve aucune réponse et le test échoue : c'est l'oracle « les bornes sont transmises telles
 * quelles » (CA-12), obtenu sans `any()`. **G-12 est le seul cas où les bornes sont l'inconnue
 * mesurée**, donc le seul à employer `any()` — il les capture, c'est précisément son sujet.
 *
 * ### ANO relevées par cette campagne — les deux corrigées depuis
 * - ~~**ANO-1 (G-12)**~~ — **corrigée le 11 septembre 2026.** La fenêtre par défaut violait
 *   P-1 / CA-13 sur deux points : `LocalDate.now().minusMonths(1)` donnait *aujourd'hui moins un
 *   mois* au lieu du **1er jour** du mois calendaire précédent, et `plusMonths(6).atTime(23, 59,
 *   59)` donnait *le même jour du mois* à la milliseconde 000 au lieu du **dernier jour** de M+6
 *   à `.999`. L'horloge était en outre lue **deux fois**, si bien qu'un appel à cheval sur minuit
 *   produisait une fenêtre dont les deux bornes ne parlaient pas du même jour. G-12 est vert,
 *   sensibilité prouvée par mutation M9 (règle ramenée à l'ancienne expression → G-12 rouge).
 * - ~~**ANO-2 (G-16)**~~ — **corrigée le 11 septembre 2026.** Il n'existait aucune règle de
 *   départage à date égale : `sortedBy` étant stable, l'ordre rendu était celui de l'entrée,
 *   donc de la source et non d'une règle. Le use case trie désormais avec un comparateur total
 *   (date, puis persisté avant virtuel, puis id croissant). G-16 est vert, sensibilité prouvée
 *   par mutation M8 (comparateur ramené à `sortedBy { date }` → G-16 rouge).
 *
 * CA-16 a été corrigé le 11 septembre 2026 : **dates croissantes**, puis id persisté **croissant**,
 * les virtuels après les persistés de même date. La fenêtre par défaut couvrant un mois de passé
 * pour six de futur, l'ordre décroissant d'origine plaçait l'échéance la plus lointaine en tête.
 *
 * ### Limites relevées à l'exécution
 * - **CA-17 / G-13 et G-14** — avec le JDD de la fiche, la clause `type` n'est pas falsifiable
 *   indépendamment : la seule ligne INCOME (« Salaire mars ») est déjà écartée par le texte
 *   « courses ». Neutraliser le filtre type ne fait rougir aucun des deux cas (contre-épreuve
 *   M3), alors que les clauses compte (M1) et statut (M4) sont bien discriminantes. La couvrir
 *   demanderait une ligne INCOME du compte A dont le titre contient « courses » — c'est un
 *   amendement du **JDD de la fiche**, pas une liberté à prendre ici.
 * - **G-12** — depuis que la production applique la règle P-1, réécrire son expression dans le
 *   test rendrait l'oracle **tautologique** : il passerait quelle que soit la règle, du moment
 *   que le test la recopie. L'oracle porte donc sur les **propriétés** de chaque borne — mois
 *   calendaire, rang du jour dans le mois, heure — et non sur une valeur recalculée.
 * - **G-12** — il subsiste une dépendance à l'horloge : le mois de référence vient de
 *   `LocalDate.now(zone)`. Le cas resterait faux s'il s'exécutait à cheval sur minuit d'un
 *   changement de mois. Une **horloge injectable** dans le use case supprimerait ce dernier
 *   reste et permettrait des attendus littéraux ; c'est une amélioration de testabilité, pas un
 *   défaut fonctionnel, et elle n'est pas portée par cette campagne.
 *
 * ### Hors périmètre
 * - Correspondance texte détaillée (casse, accents, montant, tag *par le texte*) → TC-97.
 * - Virtuels réellement générés, tombstones, absence d'écriture → TC-99 (intégration Room).
 *   Ici `F-egv` est une **donnée d'entrée**, pas une preuve de fusion.
 * - Écran, debounce, filtres d'UI → ticket 71.
 * - **Ce niveau ne prouve pas que la source applique des bornes incluses** : il prouve que le use
 *   case les transmet sans les rétrécir et ne re-filtre pas ce qu'elle rend. L'inclusivité réelle
 *   des bornes est portée par TC-99.
 *
 * Commande : `./gradlew :app:testDebugUnitTest --tests "*SearchTransactionsFiltersTest"`
 */
class SearchTransactionsFiltersTest {

    // --- Déterminisme -------------------------------------------------------------------------

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun forceZoneAndLocale() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
        unmockkAll()
    }

    // --- Bornes de la fenêtre -----------------------------------------------------------------
    // Déclarées ici et non dans un fixture partagé : plusieurs cardinalités attendues en dépendent.

    /** D1 = 2026-03-01 00:00:00.000 Europe/Paris. */
    private val d1: Long = LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** D2 = 2026-03-31 23:59:59.999 Europe/Paris. */
    private val d2: Long =
        LocalDate.of(2026, 3, 31).atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant()
            .toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(2026, 3, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Date partagée par F-eg1, F-eg2 et F-egv : le seul point d'égalité du jeu. */
    private val eqDate: Long = at(day = 18, hour = 9, minute = 0)

    // --- Jeu de données -----------------------------------------------------------------------

    private val accountA = AccountEntity(
        id = 1L, name = "A", type = AccountType.CHECKING,
        initialBalance = 0L, colorArgb = 0xFF2196F3.toInt(), icon = "wallet",
    )
    private val accountB = accountA.copy(id = 2L, name = "B")
    private val absentAccountId = 999L

    private val catAlim = CategoryEntity(
        id = 10L, name = "Alim", type = TransactionType.EXPENSE,
        colorArgb = 0xFFFF9800.toInt(), icon = "restaurant",
    )
    private val catRevenus = CategoryEntity(
        id = 11L, name = "Revenus", type = TransactionType.INCOME,
        colorArgb = 0xFF4CAF50.toInt(), icon = "payments",
    )
    private val catLoisirs = CategoryEntity(
        id = 12L, name = "Loisirs", type = TransactionType.EXPENSE,
        colorArgb = 0xFF9C27B0.toInt(), icon = "movie",
    )
    private val absentCategoryId = 998L

    private val tagLoisirs = TagEntity(id = 20L, name = "T-loisirs", colorArgb = 0xFF9C27B0.toInt())

    /**
     * Identifiant de l'occurrence virtuelle. Écrit en dur, **négatif** : la production le
     * calculerait par `RecurrenceEngine.calculateVirtualId`, mais l'appeler ici ferait dépendre
     * l'attendu de G-16 de la fonction de production. Seul le signe porte du sens pour le tri.
     */
    private val virtualEqId = -777_001L

    private val seriesEqId = 777L

    private fun row(
        id: Long,
        title: String,
        date: Long,
        account: AccountEntity,
        category: CategoryEntity,
        type: TransactionType,
        status: TransactionStatus,
        tags: List<TagEntity> = emptyList(),
        seriesId: Long? = null,
    ) = TransactionWithRelations(
        TransactionEntity(
            id = id,
            title = title,
            amount = 1_000L,
            type = type,
            status = status,
            date = date,
            accountId = account.id,
            categoryId = category.id,
            paidAt = if (status == TransactionStatus.PAID) date else null,
            seriesId = seriesId,
            // Occurrence non déplacée : le slot d'origine est la date d'affichage.
            seriesDate = seriesId?.let { date },
        ),
        category,
        account,
        tags,
    )

    private val fA1 = row(
        101L,
        "Courses mars",
        at(10, 12, 0),
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fB1 = row(
        102L,
        "Courses mars bis",
        at(11, 12, 0),
        accountB,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fA2 = row(
        103L,
        "Salaire mars",
        at(5, 12, 0),
        accountA,
        catRevenus,
        TransactionType.INCOME,
        TransactionStatus.PAID
    )
    private val fA3 = row(
        104L,
        "Courses prévues",
        at(20, 12, 0),
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PLANNED
    )
    private val fA4 = row(
        105L,
        "Cinéma",
        at(15, 12, 0),
        accountA,
        catLoisirs,
        TransactionType.EXPENSE,
        TransactionStatus.PAID,
        tags = listOf(tagLoisirs)
    )
    private val fBorneD1 = row(
        106L,
        "Borne début",
        d1,
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fBorneD2 = row(
        107L,
        "Borne fin",
        d2,
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fAvant = row(
        108L,
        "Hors avant",
        d1 - 1,
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fApres = row(
        109L,
        "Hors après",
        d2 + 1,
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fEg1 = row(
        501L,
        "Égalité persistée",
        eqDate,
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fEg2 = row(
        502L,
        "Égalité persistée",
        eqDate,
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PAID
    )
    private val fEgv = row(
        virtualEqId,
        "Égalité virtuelle",
        eqDate,
        accountA,
        catAlim,
        TransactionType.EXPENSE,
        TransactionStatus.PLANNED,
        seriesId = seriesEqId
    )

    /**
     * Ordre d'entrée **volontairement adverse** pour G-16 : les trois lignes de même date sont
     * fournies dans l'ordre exactement inverse de l'ordre attendu. Sans cela, un tri stable sans
     * règle de départage passerait par accident et le cas ne prouverait rien.
     */
    private val jdd = listOf(
        fEgv, fEg2, fEg1,
        fA1, fB1, fA2, fA3, fA4,
        fBorneD1, fBorneD2, fAvant, fApres,
    )

    // --- Doublure et système testé ------------------------------------------------------------

    private val observeTransactionsUseCase = mockk<ObserveTransactionsUseCase>(relaxed = false)
    private val sut = SearchTransactionsUseCase(observeTransactionsUseCase)

    /**
     * Programme la doublure sur des **bornes exactes**. Elle ne rend que les lignes du jeu dont la
     * `date` tombe dans ces bornes, incluses — c'est ce qui rend CA-12 observable à ce niveau.
     */
    private fun givenWindow(start: Long, end: Long) {
        every { observeTransactionsUseCase(start, end) } returns
                flowOf(jdd.filter { it.transaction.date in start..end })
    }

    // --- Oracles ------------------------------------------------------------------------------

    private fun ids(rows: List<TransactionWithRelations>) = rows.map { it.transaction.id }

    /** Un instant epoch ne se diagnostique pas à l'œil : le message d'échec le rend lisible. */
    private fun readable(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime().toString()

    /** Cardinalité exacte **et** ensemble exact : passe pas avec un doublon ni avec du bruit. */
    private fun assertIdSet(
        ca: String,
        expected: Set<Long>,
        actual: List<TransactionWithRelations>
    ) {
        val observed = ids(actual)
        assertEquals(
            "$ca — cardinalité : attendu ${expected.size}, observé ${observed.size} (ids observés $observed)",
            expected.size, observed.size,
        )
        assertEquals(
            "$ca — ensemble d'identifiants : attendu $expected, observé ${observed.toSet()}",
            expected, observed.toSet(),
        )
    }

    private fun assertIdSequence(
        ca: String,
        expected: List<Long>,
        actual: List<TransactionWithRelations>
    ) {
        assertEquals(
            "$ca — séquence d'identifiants : attendue $expected, observée ${ids(actual)}",
            expected, ids(actual),
        )
    }

    private fun assertEmpty(ca: String, actual: List<TransactionWithRelations>) {
        assertEquals(
            "$ca — la liste doit être vide, observé ${ids(actual)}",
            0, actual.size,
        )
    }

    // --- G-01 / G-02 — CA-06 filtre compte ----------------------------------------------------

    @Test
    fun `given fenetre D1 D2 when filtre compte A then aucune ligne du compte B`() = runTest {
        givenWindow(d1, d2)

        val result = sut(
            query = "",
            accountId = accountA.id,
            categoryId = null,
            startDate = d1,
            endDate = d2
        ).first()

        assertIdSet(
            "CA-06",
            setOf(101L, 103L, 104L, 105L, 106L, 107L, 501L, 502L, virtualEqId),
            result,
        )
    }

    @Test
    fun `given fenetre D1 D2 when filtre compte inexistant then liste vide`() = runTest {
        givenWindow(d1, d2)

        val result = sut(
            query = "",
            accountId = absentAccountId,
            categoryId = null,
            startDate = d1,
            endDate = d2
        ).first()

        assertEmpty("CA-06", result)
    }

    // --- G-03 / G-04 — CA-07 filtre catégorie -------------------------------------------------

    @Test
    fun `given fenetre D1 D2 when filtre categorie Alim then Revenus et Loisirs absents`() =
        runTest {
            givenWindow(d1, d2)

            val result = sut(
                query = "",
                accountId = null,
                categoryId = catAlim.id,
                startDate = d1,
                endDate = d2
            ).first()

            assertIdSet(
                "CA-07",
                setOf(101L, 102L, 104L, 106L, 107L, 501L, 502L, virtualEqId),
                result,
            )
        }

    @Test
    fun `given fenetre D1 D2 when filtre categorie inexistante then liste vide`() = runTest {
        givenWindow(d1, d2)

        val result = sut(
            query = "",
            accountId = null,
            categoryId = absentCategoryId,
            startDate = d1,
            endDate = d2
        ).first()

        assertEmpty("CA-07", result)
    }

    // --- G-05 / G-06 — CA-08 filtre type ------------------------------------------------------

    @Test
    fun `given fenetre D1 D2 when filtre type EXPENSE then aucun revenu`() = runTest {
        givenWindow(d1, d2)

        val result = sut(
            query = "", accountId = null, categoryId = null, startDate = d1, endDate = d2,
            type = TransactionType.EXPENSE,
        ).first()

        assertIdSet(
            "CA-08",
            setOf(101L, 102L, 104L, 105L, 106L, 107L, 501L, 502L, virtualEqId),
            result,
        )
    }

    @Test
    fun `given fenetre D1 D2 when filtre type INCOME then exactement le salaire`() = runTest {
        givenWindow(d1, d2)

        val result = sut(
            query = "", accountId = null, categoryId = null, startDate = d1, endDate = d2,
            type = TransactionType.INCOME,
        ).first()

        assertIdSequence("CA-08", listOf(103L), result)
    }

    // --- G-07 / G-08 — CA-09 filtre statut ----------------------------------------------------

    @Test
    fun `given fenetre D1 D2 when filtre statut PAID then aucune planifiee y compris virtuelle`() =
        runTest {
            givenWindow(d1, d2)

            val result = sut(
                query = "", accountId = null, categoryId = null, startDate = d1, endDate = d2,
                status = TransactionStatus.PAID,
            ).first()

            assertIdSet(
                "CA-09",
                setOf(101L, 102L, 103L, 105L, 106L, 107L, 501L, 502L),
                result,
            )
        }

    @Test
    fun `given fenetre D1 D2 when filtre statut PLANNED then exactement les deux planifiees`() =
        runTest {
            givenWindow(d1, d2)

            val result = sut(
                query = "", accountId = null, categoryId = null, startDate = d1, endDate = d2,
                status = TransactionStatus.PLANNED,
            ).first()

            // F-egv au 18 mars précède F-A3 au 20 mars : dates distinctes, donc ordre assertable.
            assertIdSequence("CA-09", listOf(virtualEqId, 104L), result)
        }

    // --- G-09 / G-10 — CA-11 filtre tag -------------------------------------------------------

    @Test
    fun `given fenetre D1 D2 when filtre tag T-loisirs then exactement le cinema`() = runTest {
        givenWindow(d1, d2)

        val result = sut(
            query = "", accountId = null, categoryId = null, startDate = d1, endDate = d2,
            tagName = tagLoisirs.name,
        ).first()

        assertIdSequence("CA-11", listOf(105L), result)
    }

    @Test
    fun `given fenetre D1 D2 when filtre tag inexistant then liste vide`() = runTest {
        givenWindow(d1, d2)

        val result = sut(
            query = "", accountId = null, categoryId = null, startDate = d1, endDate = d2,
            tagName = "T-inexistant",
        ).first()

        assertEmpty("CA-11", result)
    }

    // --- G-11 — CA-12 bornes incluses ---------------------------------------------------------

    @Test
    fun `given fenetre D1 D2 when aucun autre critere then les deux bornes presentes et les hors-fenetre absents`() =
        runTest {
            givenWindow(d1, d2)

            val result = sut(
                query = "",
                accountId = null,
                categoryId = null,
                startDate = d1,
                endDate = d2
            ).first()

            assertIdSet(
                "CA-12",
                setOf(101L, 102L, 103L, 104L, 105L, 106L, 107L, 501L, 502L, virtualEqId),
                result,
            )
        }

    // --- G-12 — CA-13 fenêtre par défaut (P-1) ------------------------------------------------

    @Test
    fun `given aucune date fournie when recherche then fenetre du 1er de M moins 1 au dernier jour de M plus 6`() =
        runTest {
            // Seul cas du fichier qui dépend de l'horloge : c'est son sujet. Le use case lit
            // `LocalDate.now(zone)`, faute d'horloge injectable. Figer l'horloge par
            // `mockkStatic(LocalDate::class)` échoue ici — `java.time` est un module JDK fermé
            // (`InaccessibleObjectException`) et le forcer demanderait un `--add-opens` dans le
            // build, c'est-à-dire modifier la production pour le confort du test.
            //
            // L'oracle porte donc sur les **propriétés** de chaque borne — mois calendaire, rang
            // du jour dans le mois, heure — et non sur une valeur recalculée. Depuis que la
            // production applique la règle P-1, réécrire son expression ici rendrait l'oracle
            // tautologique : il passerait quelle que soit la règle, du moment que le test la
            // recopie. Décomposée en propriétés, l'assertion reste fausse si la production
            // change de règle.
            val today = LocalDate.now(zone)

            // Seul cas à programmer la doublure sur `any()` : les bornes sont l'inconnue mesurée.
            val startSeen = slot<Long>()
            val endSeen = slot<Long>()
            every {
                observeTransactionsUseCase(
                    capture(startSeen),
                    capture(endSeen)
                )
            } returns flowOf(emptyList())

            sut(
                query = "",
                accountId = null,
                categoryId = null,
                startDate = null,
                endDate = null
            ).first()

            val start = Instant.ofEpochMilli(startSeen.captured).atZone(zone).toLocalDateTime()
            val end = Instant.ofEpochMilli(endSeen.captured).atZone(zone).toLocalDateTime()

            val previousMonth = YearMonth.from(today).minusMonths(1)
            val sixthMonthAhead = YearMonth.from(today).plusMonths(6)

            // Une assertion par borne, chacune portant ses trois propriétés d'un bloc : comparées
            // séparément, un écart sur la première masquerait celui sur la seconde.
            assertEquals(
                "CA-13 — début de fenêtre : attendu le 1er jour de $previousMonth à 00:00:00.000, " +
                        "observé ${readable(startSeen.captured)}",
                Triple(previousMonth, 1, LocalTime.MIDNIGHT),
                Triple(YearMonth.from(start), start.dayOfMonth, start.toLocalTime()),
            )
            assertEquals(
                "CA-13 — fin de fenêtre : attendu le dernier jour de $sixthMonthAhead " +
                        "(${sixthMonthAhead.lengthOfMonth()}) à 23:59:59.999, " +
                        "observé ${readable(endSeen.captured)}",
                Triple(sixthMonthAhead, sixthMonthAhead.lengthOfMonth(), LocalTime.of(23, 59, 59, 999_000_000)),
                Triple(YearMonth.from(end), end.dayOfMonth, end.toLocalTime()),
            )
        }

    // --- G-13 / G-14 — CA-17 combinaison ET ---------------------------------------------------

    @Test
    fun `given texte courses et compte A et type EXPENSE when statut non filtre then exactement les deux depenses du compte A`() =
        runTest {
            givenWindow(d1, d2)

            val result = sut(
                query = "courses",
                accountId = accountA.id,
                categoryId = null,
                startDate = d1,
                endDate = d2,
                type = TransactionType.EXPENSE,
            ).first()

            // F-B1 exclu par le compte, F-A2 par le type. Dates distinctes : ordre assertable.
            assertIdSequence("CA-17", listOf(101L, 104L), result)
        }

    @Test
    fun `given texte courses et compte A et type EXPENSE when statut PAID then exactement la depense payee`() =
        runTest {
            givenWindow(d1, d2)

            val result = sut(
                query = "courses",
                accountId = accountA.id,
                categoryId = null,
                startDate = d1,
                endDate = d2,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
            ).first()

            assertIdSequence("CA-17", listOf(101L), result)
        }

    // --- G-15 / G-16 — CA-16 tri --------------------------------------------------------------

    @Test
    fun `given plusieurs resultats de dates distinctes when recherche then dates strictement croissantes`() =
        runTest {
            givenWindow(d1, d2)

            // Le texte « courses » isole trois lignes de dates distinctes (10, 11 et 20 mars) : sans
            // cela le jeu contient trois lignes de même date et « strictement croissant » serait
            // inatteignable. L'égalité de dates est le sujet du cas suivant.
            val result = sut(
                query = "courses",
                accountId = null,
                categoryId = null,
                startDate = d1,
                endDate = d2
            ).first()

            val expectedDates = listOf(at(10, 12, 0), at(11, 12, 0), at(20, 12, 0))
            assertEquals(
                "CA-16 — séquence des dates : attendue $expectedDates, observée ${result.map { it.transaction.date }}",
                expectedDates, result.map { it.transaction.date },
            )
        }

    @Test
    fun `given trois lignes de meme date when recherche then id persiste croissant puis le virtuel`() =
        runTest {
            givenWindow(d1, d2)

            // Le texte « égalité » isole exactement F-eg1, F-eg2 et F-egv, seules lignes du jeu qui
            // partagent une date.
            val result = sut(
                query = "égalité",
                accountId = null,
                categoryId = null,
                startDate = d1,
                endDate = d2
            ).first()

            assertIdSequence("CA-16", listOf(501L, 502L, virtualEqId), result)
        }
}
