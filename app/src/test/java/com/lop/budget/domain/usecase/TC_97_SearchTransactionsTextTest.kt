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
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-97 — Recherche : correspondance texte (titre, note, tag, montant).
 * **Niveau use case avec doublure.**
 *
 * Chaîne exercée : `SearchTransactionsUseCase` (réel) → `ObserveTransactionsUseCase` (doublure).
 * Source de vérité : CA-01 à CA-05 et CA-10 de LOP-70 « Préparer le moteur de recherche
 * transaction », plus les conventions P-2 (casse et accents ignorés, forme NFD marques retirées)
 * et P-4 (montant interprété seulement si **tout** le texte trimé parse en nombre d'euros).
 * **Le comportement actuel du use case ne constitue pas l'oracle** : plusieurs de ces CA ne sont
 * pas implémentés, les rouges sont attendus (voir ANO ci-dessous).
 *
 * ### cas → CA / invariant → production
 * ```
 * T-01          CA-01 (I-1, I-4)   matchesQuery — court-circuit de la requête vide
 * T-02          CA-02 + CA-03      matchesQuery — title.contains / note.contains
 * T-03          CA-02 (I-5)        matchesQuery — insensibilité à la casse
 * T-04          CA-03 (I-5)        matchesQuery — fouille de la note seule
 * T-05          CA-02 (I-5)        matchesQuery — aucun match
 * T-06 à T-08   CA-04 (I-5)        matchesQuery — normalisation des accents (P-2)
 * T-09, T-10    CA-10 (I-5)        matchesQuery — fouille du nom des tags
 * T-11 à T-13   CA-05 (I-5)        matchesQuery — texte numérique → montant en centimes (P-4)
 * T-14          CA-05 (P-4)        matchesQuery — texte non entièrement numérique
 * ```
 *
 * ### Doublure
 * La doublure rend **la même liste quelles que soient les bornes** : la fenêtre est le sujet de
 * TC-98, pas de cette fiche. Les bornes reçues sont néanmoins capturées et `assertEmpty` vérifie
 * que la source a bien été appelée exactement une fois — sans ce garde-fou, T-01, T-05 et T-14
 * seraient verts pour un use case qui n'interrogerait jamais la source : oracle vacant.
 *
 * ### Décision d'oracle tranchée à l'écriture (demandée par la fiche, cas T-02)
 * **T-02 attend 3 éléments**, L-loyer inclus. Sa note contient « carb*é* », donc la sous-chaîne
 * « carb », et CA-03 énonce qu'un texte présent dans la seule note retourne la ligne. Le « et
 * seulement celles-là » de CA-02 se lit **sur l'axe titre** : lu comme un cardinal global il
 * contredirait CA-03. Le périmètre de l'US (« le titre, la note, le nom d'un tag, et — si tout le
 * texte est un nombre — le montant ») confirme l'union des axes.
 *
 * ### ANO attendues — une par cause racine, pas une par cas
 * - **ANO-A — normalisation d'accents absente (P-2 / CA-04).** `matchesQuery` compare les chaînes
 *   brutes : T-06, T-07, T-08 et T-10 rouges.
 * - **ANO-B — le nom des tags n'est pas fouillé (CA-10).** `matchesQuery` ne lit que `title` et
 *   `note` : T-09 et T-10 rouges.
 * - **ANO-C — le texte numérique n'est pas interprété comme montant (CA-05 / P-4).** T-11, T-12
 *   et T-13 rouges. `Format.centsOrNull` fournit déjà la conversion half-up attendue.
 * - **ANO-D — requête vide : le use case rend toute la fenêtre au lieu d'une liste vide
 *   (CA-01).** `matchesQuery` renvoie `true` sur `query.isBlank()`. T-01 rouge. `SearchViewModel`
 *   court-circuite la saisie vide côté écran (court-circuit toléré par I-4), ce qui limite la
 *   sévérité **utilisateur** sans changer l'oracle : CA-01 est énoncé au niveau du moteur.
 *
 * ### Sensibilité des verts, prouvée par mutation (11 septembre 2026)
 * Cinq cas sont verts du premier coup. Chacun a été mis en échec par une mutation temporaire de
 * `matchesQuery`, appliquée puis **retirée** — un vert non falsifiable ne prouverait rien.
 * ```
 * M1  disjoint `note` retiré        → T-02 rend [201, 202] (2), T-03 idem, T-04 rend [] — 3 rouges
 * M2  `ignoreCase = false`          → T-02 rend [203], T-03 rend [202] — 2 rouges
 * M3  `matchesQuery` toujours vrai  → T-05 et T-14 rendent les 13 lignes — 2 rouges
 * M4  montant parsé sur le *premier token* au lieu du texte entier
 *                                   → T-11, T-12, T-13 passent au vert ET T-14 rend [212],
 *                                     soit « 12 euros » lu comme 1200 centimes : exactement ce
 *                                     que P-4 interdit. La mutation prouve d'un coup que les
 *                                     quatre cas montant se départagent mutuellement.
 * ```
 * T-02 et T-03 tombent ensemble sous M2 : « carb » minuscule n'atteint « Carburant » que par
 * l'insensibilité à la casse. Les deux cas sont donc bien sensibles à cette clause, et aucune
 * mutation ne peut casser T-03 seul avec ce JDD.
 *
 * ### Limites relevées à l'écriture — spec à compléter
 * - **CA-05, disjonction ou exclusion ?** L'US met le montant en **union** avec les axes texte,
 *   CA-05 dit « uniquement les lignes dont le montant vaut 1234 centimes ». Le JDD de la fiche ne
 *   tranche pas : aucune ligne n'a « 12 » dans son titre. Le départager demanderait une ligne
 *   titrée p. ex. « Facture 12 » à un montant autre que 1200 — **amendement du JDD**, pas une
 *   liberté prise ici. Les cas ci-dessous passent sous les deux lectures.
 * - **Arrondi half-up de P-4 non exercé** : aucun cas à trois décimales (« 10,505 » → 1051).
 * - **Normalisation hors marques combinantes** (« œ », « ß ») : ni P-2 ni le JDD ne l'exercent.
 * - Les noms de compte et de catégorie du JDD ne sont pas spécifiés par la fiche. Ils sont choisis
 *   ici de sorte qu'**aucune requête du tableau ne s'y retrouve** : une implémentation qui
 *   fouillerait aussi ces champs ne passerait pas en silence.
 *
 * ### Hors périmètre
 * - Filtres compte / catégorie / type / statut / tag, fenêtre par défaut, bornes et tri → TC-98.
 * - Occurrences virtuelles réelles, tombstones, **I-1 (aucune écriture)** → fiche d'intégration
 *   Room : sans base, ce niveau ne peut rien prouver sur l'absence d'écriture.
 * - Debounce, états d'écran, `SearchScreen` / `SearchViewModel` → ticket 71.
 *
 * Commande : `./gradlew :app:testDebugUnitTest --tests "*SearchTransactionsTextTest"`
 */
class SearchTransactionsTextTest {

    // --- Déterminisme -----------------------------------------------------------------------

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    // --- Fenêtre --------------------------------------------------------------------------------
    // Déclarée localement et non dans un fixture partagé : toutes les cardinalités attendues
    // supposent que les treize lignes du jeu sont dans la fenêtre demandée.

    /** 2026-03-01 00:00:00.000 Europe/Paris. */
    private val windowStart: Long =
        LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** 2026-03-31 23:59:59.999 Europe/Paris. */
    private val windowEnd: Long =
        LocalDate.of(2026, 3, 31).atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant()
            .toEpochMilli()

    /** Dates distinctes, à midi, du 1er au 13 mars 2026 : aucune égalité à départager ici. */
    private fun at(day: Int): Long =
        LocalDate.of(2026, 3, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // --- Jeu de données (tableau « Données à créer » de TC-97) ----------------------------------
    // Toutes les lignes : persistées, PAID, EXPENSE, deleted = false, même compte, même catégorie.
    // Montants en centimes.

    private val account = AccountEntity(
        id = 1L, name = "Compte courant", type = AccountType.CHECKING,
        initialBalance = 0L, colorArgb = 0xFF2196F3.toInt(), icon = "wallet",
    )

    private val category = CategoryEntity(
        id = 10L, name = "Quotidien", type = TransactionType.EXPENSE,
        colorArgb = 0xFFFF9800.toInt(), icon = "restaurant",
    )

    private val tagLoisirs = TagEntity(id = 20L, name = "Loisirs", colorArgb = 0xFF9C27B0.toInt())
    private val tagActivites =
        TagEntity(id = 21L, name = "Activités", colorArgb = 0xFF4CAF50.toInt())

    private val idCarb = 201L
    private val idCarbMaj = 202L
    private val idLoyer = 203L
    private val idNote = 204L
    private val idElec = 205L
    private val idCafe = 206L
    private val idNonAcc = 207L
    private val idTag = 208L
    private val idTagAcc = 209L
    private val id1234 = 210L
    private val id1234b = 211L
    private val id12 = 212L
    private val id123400 = 213L

    private fun row(
        id: Long,
        title: String,
        date: Long,
        amount: Long,
        note: String? = null,
        tags: List<TagEntity> = emptyList(),
    ) = TransactionWithRelations(
        TransactionEntity(
            id = id,
            title = title,
            amount = amount,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = date,
            accountId = account.id,
            categoryId = category.id,
            note = note,
            paidAt = date,
            deleted = false,
        ),
        category,
        account,
        tags,
    )

    private val jdd = listOf(
        row(idCarb, "Carburant", at(1), amount = 5_000L),
        row(idCarbMaj, "CARBURANT autoroute", at(2), amount = 6_000L),
        row(idLoyer, "Loyer", at(3), amount = 82_000L, note = "Paiement carbé hors sujet"),
        row(idNote, "Restaurant", at(4), amount = 3_200L, note = "Dîner avec Marc"),
        row(idElec, "Électricité", at(5), amount = 7_500L),
        row(idCafe, "Café", at(6), amount = 250L),
        row(idNonAcc, "Electricite bis", at(7), amount = 7_600L),
        row(idTag, "Abonnement", at(8), amount = 1_290L, tags = listOf(tagLoisirs)),
        row(idTagAcc, "Sortie", at(9), amount = 4_000L, tags = listOf(tagActivites)),
        row(id1234, "Divers A", at(10), amount = 1_234L),
        row(id1234b, "Divers B", at(11), amount = 1_234L),
        row(id12, "Divers C", at(12), amount = 1_200L),
        row(id123400, "Divers D", at(13), amount = 123_400L),
    )

    // --- Doublure et système testé --------------------------------------------------------------

    private val observeTransactionsUseCase = mockk<ObserveTransactionsUseCase>(relaxed = false)

    /**
     * Horloge figée au 15 mars 2026. Seul T-01 passe des dates nulles et fait donc calculer la
     * fenêtre par défaut (P-1) ; la figer retire au fichier entier toute dépendance au jour
     * d'exécution. La règle P-1 elle-même est l'oracle de TC-98, pas d'ici.
     */
    private val clock: Clock =
        Clock.fixed(LocalDate.of(2026, 3, 15).atTime(12, 0).atZone(zone).toInstant(), zone)

    private val sut = SearchTransactionsUseCase(observeTransactionsUseCase, clock)

    /** Bornes reçues par la doublure : enregistrées pour le diagnostic, jamais l'oracle du texte. */
    private val startSeen = slot<Long>()
    private val endSeen = slot<Long>()

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Locale.setDefault(Locale.FRANCE)

        // Même liste quelles que soient les bornes (montage imposé par la fiche).
        every {
            observeTransactionsUseCase(capture(startSeen), capture(endSeen))
        } returns flowOf(jdd)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
        unmockkAll()
    }

    // --- Appel et oracles -----------------------------------------------------------------------

    /** Recherche texte seule, sur une fenêtre explicite qui contient tout le jeu. */
    private suspend fun search(query: String): List<TransactionWithRelations> = sut(
        query = query,
        accountId = null,
        categoryId = null,
        startDate = windowStart,
        endDate = windowEnd,
    ).first()

    private fun ids(rows: List<TransactionWithRelations>) = rows.map { it.transaction.id }

    private fun readable(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime().toString()

    /** Cardinalité exacte **et** ensemble exact : ne passe ni avec un doublon, ni avec du bruit. */
    private fun assertIdSet(
        case: String,
        ca: String,
        expected: Set<Long>,
        actual: List<TransactionWithRelations>,
    ) {
        val observed = ids(actual)
        assertEquals(
            "$case / $ca — cardinalité : attendu ${expected.size}, observé ${observed.size} " +
                    "(ids observés $observed)",
            expected.size, observed.size,
        )
        assertEquals(
            "$case / $ca — ensemble d'identifiants : attendu $expected, observé ${observed.toSet()}",
            expected, observed.toSet(),
        )
    }

    /**
     * Vacuité explicite. La vérification d'appel n'est pas décorative : sans elle, un use case qui
     * n'interrogerait jamais la source rendrait ces cas verts sans rien prouver.
     */
    private fun assertEmpty(case: String, ca: String, actual: List<TransactionWithRelations>) {
        verify(exactly = 1) { observeTransactionsUseCase(any(), any()) }
        assertEquals(
            "$case / $ca — liste vide attendue, observé ${ids(actual)} ; la source a bien été " +
                    "interrogée sur [${readable(startSeen.captured)} .. ${readable(endSeen.captured)}]",
            0, actual.size,
        )
    }

    // --- T-01 — CA-01 requête vide --------------------------------------------------------------

    @Test
    fun `given texte blank sans filtre ni date when recherche then liste vide et pas null`() =
        runTest {
            val result = sut(
                query = "",
                accountId = null,
                categoryId = null,
                startDate = null,
                endDate = null,
            ).first()

            assertEmpty("T-01", "CA-01", result)
        }

    // --- T-02 / T-03 — CA-02 et CA-03 correspondance partielle et casse -------------------------

    /** Ensemble attendu pour « carb », quelle que soit la casse. Voir la décision d'oracle T-02. */
    private val carbExpected = setOf(idCarb, idCarbMaj, idLoyer)

    @Test
    fun `given texte carb when recherche then les deux titres et la note en carbe`() = runTest {
        val result = search("carb")

        assertIdSet("T-02", "CA-02 + CA-03", carbExpected, result)
    }

    @Test
    fun `given texte carb en majuscules puis en casse melangee when recherche then meme ensemble`() =
        runTest {
            assertIdSet("T-03 (CARB)", "CA-02", carbExpected, search("CARB"))
            assertIdSet("T-03 (CaRb)", "CA-02", carbExpected, search("CaRb"))
        }

    // --- T-04 — CA-03 match par la note seule ---------------------------------------------------

    @Test
    fun `given texte present dans la seule note when recherche then exactement cette ligne`() =
        runTest {
            val result = search("Marc")

            assertIdSet("T-04", "CA-03", setOf(idNote), result)
        }

    // --- T-05 — CA-02 aucun match ---------------------------------------------------------------

    @Test
    fun `given texte absent de tout le jeu when recherche then liste vide`() = runTest {
        val result = search("zzz")

        assertEmpty("T-05", "CA-02", result)
    }

    // --- T-06 / T-07 / T-08 — CA-04 accents (P-2) -----------------------------------------------

    @Test
    fun `given saisie sans accent when recherche then le titre accentue et le titre sans accent`() =
        runTest {
            val result = search("electricite")

            assertIdSet("T-06", "CA-04", setOf(idElec, idNonAcc), result)
        }

    @Test
    fun `given saisie accentuee when recherche then le meme ensemble que la saisie sans accent`() =
        runTest {
            val result = search("Électricité")

            assertIdSet("T-07", "CA-04", setOf(idElec, idNonAcc), result)
        }

    @Test
    fun `given saisie cafe sans accent when recherche then exactement le titre Cafe accentue`() =
        runTest {
            val result = search("cafe")

            assertIdSet("T-08", "CA-04", setOf(idCafe), result)
        }

    // --- T-09 / T-10 — CA-10 nom de tag ---------------------------------------------------------

    @Test
    fun `given texte partiel du nom d un tag when recherche then exactement la ligne qui le porte`() =
        runTest {
            val result = search("loisir")

            assertIdSet("T-09", "CA-10", setOf(idTag), result)
        }

    @Test
    fun `given texte sans accent d un tag accentue when recherche then exactement la ligne qui le porte`() =
        runTest {
            // Cumule ANO-A (normalisation) et ANO-B (tags non fouillés) : deux causes racines,
            // un seul cas. Rouge tant que l'une des deux subsiste.
            val result = search("activites")

            assertIdSet("T-10", "CA-10 + CA-04", setOf(idTagAcc), result)
        }

    // --- T-11 / T-12 / T-13 — CA-05 montant (P-4) -----------------------------------------------

    @Test
    fun `given texte numerique a virgule when recherche then exactement les lignes a 1234 centimes`() =
        runTest {
            val result = search("12,34")

            assertIdSet("T-11", "CA-05", setOf(id1234, id1234b), result)
        }

    @Test
    fun `given texte numerique a point when recherche then le meme ensemble qu a virgule`() =
        runTest {
            val result = search("12.34")

            assertIdSet("T-12", "CA-05", setOf(id1234, id1234b), result)
        }

    @Test
    fun `given texte numerique entier when recherche then exactement la ligne a 1200 centimes`() =
        runTest {
            // 1234 (12,34 €) et 123400 (1 234,00 €) sont exclus : « 12 » vaut 1200 centimes.
            val result = search("12")

            assertIdSet("T-13", "CA-05", setOf(id12), result)
        }

    // --- T-14 — CA-05 / P-4 texte non entièrement numérique -------------------------------------

    @Test
    fun `given texte partiellement numerique when recherche then aucune interpretation de montant`() =
        runTest {
            // P-4 : le montant n'est interprété que si **tout** le texte trimé parse en nombre.
            // « 12 euros » n'en est pas un, la recherche reste purement textuelle et ne trouve rien.
            val result = search("12 euros")

            assertEmpty("T-14", "CA-05", result)
        }
}
