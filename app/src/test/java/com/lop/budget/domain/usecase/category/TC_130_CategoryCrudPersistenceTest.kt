package com.lop.budget.domain.usecase.category

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionType.EXPENSE
import com.lop.budget.domain.model.TransactionType.INCOME
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-130 — CRUD catégories : persistance Room et règles métier (US LOP-19).
 *
 * ## Niveau
 * Intégration sur base Room réelle, en mémoire (Robolectric SDK 33, application Android neutre).
 * **Aucun mock, aucun fake, aucun spy.** Ce fichier prouve des lignes écrites, des cardinalités et
 * des rattachements : une doublure ne voit ni un `UPSERT`, ni une réaffectation, ni un `DELETE`.
 *
 * ## Chaîne réellement exercée
 * ```
 * Observation   ObserveCategoriesUseCase() / .observeGroupedByType() → CategoryRepository → CategoryDao
 * Sélecteur     CategoryRepository.observeByType(type)  — le flux que lit TransactionEditViewModel
 * Création      CreateCategoryUseCase                 → CategoryRepository.getById + upsert
 * Modification  UpdateCategoryUseCase                 → GetCategoryUsageUseCase + CategoryRepository
 * Suppression   DeleteCategoryUseCase                 → TransactionRepository.reassign* + CategoryRepository.delete*
 * Lecture       ObserveTransactionDetailUseCase       — lecture métier d'une transaction ou d'une occurrence
 * ```
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 * ```
 * O-01  CA-01            ObserveCategoriesUseCase.invoke / observeGroupedByType (référentiel vide)
 * O-02  CA-01            idem, trois parentes de deux types
 * C-01  CA-02            CreateCategoryUseCase (trim, champs persistés, id rendu)
 * C-02  CA-03            CreateCategoryUseCase, branche nom vide ou blanc
 * C-03  CA-10, I-5       CreateCategoryUseCase, garde « parent de l'autre type »
 * U-01  CA-04, I-2       UpdateCategoryUseCase (même ligne, rattachements conservés)
 * U-02  CA-05, I-2       UpdateCategoryUseCase, branche nom blanc
 * D-01  CA-06, I-1       DeleteCategoryUseCase (catégorie non utilisée)
 * D-02  CA-07, I-1       DeleteCategoryUseCase (catégorie utilisée : réaffectation puis DELETE)
 * D-03  CA-15, I-1, I-7  DeleteCategoryUseCase (parente et ses deux filles)
 * P-01  CA-09, I-4       UpdateCategoryUseCase, garde « a des sous-catégories » sur le parent
 * P-02  CA-10, I-5       UpdateCategoryUseCase, garde « parent de l'autre type »
 * T-01  CA-12, I-6       UpdateCategoryUseCase, garde « utilisée » sur le type
 * T-02  CA-12, I-6       UpdateCategoryUseCase, garde « a des sous-catégories » sur le type
 * T-03  CA-13, I-6       UpdateCategoryUseCase, changement de type autorisé
 * ```
 *
 * ## Décisions qui fixent les oracles
 * - **Refus en bloc, avec sa raison** (décision du 22 septembre 2026 sur TC-130, option D1-b) :
 *   une écriture qui viole I-4, I-5 ou I-6 n'écrit rien et rend [CategoryWriteResult.Refused]
 *   avec une [CategoryRefusal] stable. Les types de résultat ont été livrés à comportement
 *   constant avant ce fichier ; les gardes, elles, n'ont pas été touchées.
 * - **« Sans catégorie » = sentinelle [NO_CATEGORY_ID]**, pas `null` : `categoryId` n'est jamais
 *   nullable (décision du 13 septembre 2026, LOP-87). La fiche disait « devient nulle » ; l'oracle
 *   porte donc sur `categoryId == NO_CATEGORY_ID`, sur l'absence de toute ligne `categories` à cet
 *   identifiant, et sur la lecture métier qui rend `category == null`. Le libellé affiché est
 *   l'affaire de TC-132.
 *
 * ## Fixtures discriminantes
 * - « ␣␣Courses␣␣ » en C-01 : sans `trim`, la chaîne brute serait persistée.
 * - [COLOR_INTRUS] n'est soumise **que** dans des écritures qui doivent être refusées. Sa présence
 *   n'importe où dans `categories` prouve qu'un refus a laissé passer une écriture partielle.
 * - CAT-TEMOIN n'est jamais ciblée : réassertée dans chaque cas où elle est semée.
 * - Les rattachements sont lus par identifiant rendu par Room, jamais par nom.
 * - D-01 sème aussi CAT-UTIL et ses deux porteurs : sans eux, « nombre de transactions et de séries
 *   inchangé » comparerait 0 à 0 et ne pourrait pas échouer.
 * - D-03 sème TX-TEMOIN : une réaffectation trop large, qui toucherait une autre catégorie que la
 *   parente et ses filles, la ferait tomber à la sentinelle.
 *
 * ## Observation déjà ouverte (C-01, U-01, D-01, D-02, T-03)
 * Les flux sont ouverts **avant** l'action. Leur première émission ne sert qu'à se synchroniser sur
 * l'identité du jeu initial. [settle] saute seulement les répétitions strictes de l'état initial,
 * qui ne portent aucun changement ; la première émission différente est l'émission finale, et c'est
 * elle qui porte l'oracle complet. [assertNoLateEmission] exige que toute émission reçue ensuite lui
 * soit identique : une émission fautive tardive n'est pas absorbée. Tous les flux sont clos avant le
 * premier oracle, pour que `turbineScope` ne remplace pas le message du cas par un rapport
 * d'événements non consommés.
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - Base neuve par cas, fermée en `@After`. `Europe/Paris` et `Locale.FRANCE` forcés puis restaurés.
 * - Date métier fixe [marsSlot] (10 mars 2026, 09:00) ; aucune horloge n'est lue.
 * - Turbine, attente bornée à [TIMEOUT] en temps réel. Aucun `Thread.sleep`, aucun `withTimeout`.
 * - [categoryRows], [transactionRows] et [seriesRows] lisent les tables par SQL brut limité au test.
 *   Aucune API de production n'a été ajoutée pour ce fichier.
 * - `RecurrenceEngine.calculateVirtualId` sert uniquement à **adresser** l'occurrence de la série
 *   pour la lire ; aucun attendu n'en est tiré.
 * - `observeAll` et `observeByType` trient par nom : aucun oracle ne porte sur une position.
 *
 * ## Anomalie — corrigée le 22 septembre 2026
 * - **LOP-175** — https://app.notion.com/p/3e350f34a8c58146bbf1c43419db9603
 *   `UpdateCategoryUseCase` et `CreateCategoryUseCase` corrigeaient en silence au lieu de refuser
 *   (I-4, I-5, I-6) : le champ interdit était ignoré, le reste écrit, et l'appel rendait `Success`.
 *   P-01, P-02, T-01, T-02 et C-03 étaient rouges, la couleur intruse en base. Ils refusent
 *   désormais en bloc, avant toute écriture (P-13) : les cinq cas sont verts. C-03 a été ajouté
 *   avec la correction, parce qu'elle touchait une branche de création qu'aucun cas ne couvrait ;
 *   il était rouge avant elle.
 *
 * ## Résultat — 22 septembre 2026 : 15 verts sur 15.
 *
 * ## Preuve de sensibilité des verts (22 septembre 2026, mutations retirées)
 * ```
 * M1  création sans trim                    → C-01 rouge (« ␣␣Courses␣␣ » persisté)
 * M2  création sans garde nom blanc         → C-02 rouge
 * M3  modification sur une nouvelle ligne   → U-01 rouge, T-03 rouge
 * M4  modification sans garde nom blanc     → U-02 rouge
 * M5  suppression sans réaffectation        → D-02 et D-03 rouges
 * M6  filles non supprimées                 → D-03 rouge
 * M7  type toujours réécrit                 → T-01 et T-02 rouges sur la garde de type
 * M8  type jamais réécrit                   → T-03 rouge
 * M9  parent écrit malgré les filles        → P-01 rouge sur la garde de parent
 * M10 parent sans filtre de type            → P-02 rouge sur la garde de parent
 *     (M7, M9, M10 portaient sur les gardes d'avant LOP-175 ; les gardes réécrites sont prouvées
 *     par le passage du rouge au vert de P-01, P-02, T-01, T-02 et C-03.)
 * M11 listes dépenses / revenus inversées   → O-02 rouge
 * M12 réaffectation trop large (DAO)        → D-01 rouge, D-03 rouge sur TX-TEMOIN
 * ```
 * O-01 n'a pas de mutation réaliste : il asserte explicitement qu'un référentiel vide reste vide.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Confirmation, message différencié, annulation de suppression, zones masquées, sélecteur rendu,
 *   libellé « Sans catégorie » à l'écran → **TC-132**.
 * - État exposé par les ViewModels, initialisation selon la section → **TC-131**.
 * - Catalogue par défaut, ordre utilisateur, archivage, compteur de transactions, CRUD des
 *   sous-catégories depuis le détail d'une catégorie.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*CategoryCrudPersistenceTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CategoryCrudPersistenceTest {

    // --- Composants réels -----------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var observeCategories: ObserveCategoriesUseCase
    private lateinit var createCategory: CreateCategoryUseCase
    private lateinit var updateCategory: UpdateCategoryUseCase
    private lateinit var deleteCategory: DeleteCategoryUseCase
    private lateinit var observeDetail: ObserveTransactionDetailUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    // --- Identifiants, tous rendus par Room -------------------------------------------------------

    private var accountId = 0L
    private var catAlimId = 0L
    private var catLogtId = 0L
    private var catSalId = 0L
    private var subCoursesId = 0L
    private var subCantineId = 0L
    private var catVideId = 0L
    private var catUtilId = 0L
    private var catTemoinId = 0L
    private var txUtilId = 0L
    private var serieUtilId = 0L
    private var txTemoinId = 0L

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_ID))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        categoryRepo = CategoryRepository(db.categoryDao())
        val transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        observeCategories = ObserveCategoriesUseCase(categoryRepo)
        createCategory = CreateCategoryUseCase(categoryRepo)
        updateCategory = UpdateCategoryUseCase(
            categoryRepo,
            GetCategoryUsageUseCase(categoryRepo, transactionRepo),
        )
        deleteCategory = DeleteCategoryUseCase(categoryRepo, transactionRepo)
        observeDetail = ObserveTransactionDetailUseCase(
            transactionRepo,
            AccountRepository(db.accountDao()),
            categoryRepo,
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // O — Observation du référentiel (CA-01)
    // ==============================================================================================

    /** O-01 — Given une base sans catégorie, When on collecte, Then émissions vides et table vide. */
    @Test
    fun `O-01 - Given une base sans categorie - When on collecte l'observation - Then emission vide et table categories vide (CA-01)`() =
        runTest {
            val flat = firstOf(observeCategories())
            val grouped = firstOf(observeCategories.observeGroupedByType())

            assertEquals("O-01 — CA-01 : référentiel vide, zéro entrée attendue ; obtenu $flat", 0, flat.size)
            assertEquals(
                "O-01 — CA-01 : les listes dépenses et revenus devaient être vides ; obtenu $grouped",
                CategoriesByType(),
                grouped,
            )
            assertEquals("O-01 — CA-01 : table `categories` vide attendue", 0, categoryRows().size)
        }

    /**
     * O-02 — Given CAT-ALIM, CAT-LOGT (dépenses) et CAT-SAL (revenu), When on collecte, Then
     * exactement trois entrées d'identifiants distincts, deux dépenses et un revenu, chacune une
     * seule fois et dans la liste de son type (CA-01).
     */
    @Test
    fun `O-02 - Given trois parentes de deux types - When on collecte l'observation - Then trois entrees distinctes separees par type sans doublon (CA-01)`() =
        runTest {
            seedAlim()
            seedLogt()
            seedSal()

            val flat = firstOf(observeCategories())
            assertEquals(
                "O-02 — CA-01 : exactement trois entrées attendues ; obtenu ${flat.map { it.id }}",
                3,
                flat.size,
            )
            assertEquals(
                "O-02 — CA-01 : les trois identifiants semés, chacun une fois ; obtenu ${flat.map { it.id }}",
                setOf(catAlimId, catLogtId, catSalId),
                flat.map { it.id }.toSet(),
            )
            assertEquals(
                "O-02 — CA-01 : deux dépenses et un revenu attendus ; obtenu ${flat.map { it.type }}",
                mapOf(EXPENSE to 2, INCOME to 1),
                flat.groupingBy { it.type }.eachCount(),
            )

            val grouped = firstOf(observeCategories.observeGroupedByType())
            assertEquals(
                "O-02 — CA-01 : la liste dépenses devait contenir exactement ALIM et LOGT, une fois " +
                    "chacune ; obtenu ${grouped.expense.map { it.category.id }}",
                listOf(catAlimId, catLogtId).sorted(),
                grouped.expense.map { it.category.id }.sorted(),
            )
            assertEquals(
                "O-02 — CA-01 : la liste revenus devait contenir exactement SAL ; " +
                    "obtenu ${grouped.income.map { it.category.id }}",
                listOf(catSalId),
                grouped.income.map { it.category.id },
            )
        }

    // ==============================================================================================
    // C — Création (CA-02, CA-03)
    // ==============================================================================================

    /**
     * C-01 — Given une base vide et les flux de gestion et du sélecteur dépenses ouverts, When on
     * crée « ␣␣Courses␣␣ » en dépense avec icône et couleur, Then succès, identifiant strictement
     * positif, exactement une ligne trimée aux champs saisis et sans parent, visible une fois dans la
     * gestion des dépenses et dans le sélecteur dépenses (CA-02).
     */
    @Test
    fun `C-01 - Given une base vide - When on cree un nom entoure d'espaces - Then une seule ligne trimee aux champs saisis visible en gestion et au selecteur (CA-02)`() =
        runTest {
            lateinit var result: CategoryWriteResult
            lateinit var manage: Observed<CategoriesByType>
            lateinit var selector: Observed<List<CategoryEntity>>
            turbineScope {
                val manageFlow = observeCategories.observeGroupedByType().testIn(backgroundScope, TIMEOUT)
                val selectorFlow = categoryRepo.observeByType(EXPENSE.name).testIn(backgroundScope, TIMEOUT)
                val manageInitial = manageFlow.awaitItem()
                val selectorInitial = selectorFlow.awaitItem()

                result = createCategory(NAME_COURSES_UNTRIMMED, EXPENSE, COLOR_COURSES, ICON_COURSES, null)

                manage = manageFlow.settle(manageInitial, "C-01 — gestion")
                selector = selectorFlow.settle(selectorInitial, "C-01 — sélecteur")
            }
            assertEquals("C-01 — synchro : gestion initiale vide", CategoriesByType(), manage.initial)
            assertEquals("C-01 — synchro : sélecteur initial vide", emptyList<CategoryEntity>(), selector.initial)

            val createdId = (result as? CategoryWriteResult.Success)?.categoryId
                ?: throw AssertionError("C-01 — CA-02 : succès attendu ; obtenu $result")
            assertTrue("C-01 — CA-02 : identifiant strictement positif attendu ; obtenu $createdId", createdId > 0L)
            assertEquals(
                "C-01 — CA-02 : exactement une ligne, nom trimé, type, couleur et icône saisis, " +
                    "sans parent ; table = ${categoryRows()}",
                listOf(row(createdId, NAME_COURSES, EXPENSE, COLOR_COURSES, ICON_COURSES, null)),
                categoryRows(),
            )
            assertEquals(
                "C-01 — CA-02 : la gestion des dépenses devait montrer la catégorie créée une " +
                    "seule fois, et rien en revenus ; obtenu ${manage.final}",
                CategoriesByType(expense = listOf(CategoryWithSubs(persisted(createdId), emptyList()))),
                manage.final,
            )
            assertEquals(
                "C-01 — CA-02 : le sélecteur dépenses devait contenir exactement la catégorie " +
                    "créée ; obtenu ${selector.final}",
                listOf(persisted(createdId)),
                selector.final,
            )
            assertNoLateEmission(manage, "C-01 — gestion")
            assertNoLateEmission(selector, "C-01 — sélecteur")
        }

    /**
     * C-02 — Given CAT-TEMOIN, When on tente de créer « » puis « ␣␣␣ » avec la couleur intruse, Then
     * deux refus [CategoryRefusal.BlankName], tables strictement inchangées, couleur intruse absente
     * (CA-03).
     */
    @Test
    fun `C-02 - Given le temoin seul - When on tente un nom vide puis un nom blanc - Then deux refus BlankName et rien n'est ecrit (CA-03)`() =
        runTest {
            seedTemoin()
            val before = snapshot()

            for (blank in listOf(NAME_EMPTY, NAME_BLANK)) {
                val result = createCategory(blank, EXPENSE, COLOR_INTRUS, ICON_COURSES, null)
                assertEquals(
                    "C-02 — CA-03 : « $blank » devait être refusé pour nom blanc ; obtenu $result",
                    CategoryWriteResult.Refused(CategoryRefusal.BlankName),
                    result,
                )
            }

            assertEquals("C-02 — CA-03 : aucune table ne devait changer", before, snapshot())
            assertIntrusAbsent("C-02 — CA-03")
            assertTemoinIntact("C-02")
        }

    /**
     * C-03 — Given CAT-SAL (revenu), When on tente de créer « Loyer » en dépense avec CAT-SAL comme
     * parent et la couleur intruse, Then refus [CategoryRefusal.ParentTypeMismatch] et rien n'est
     * écrit (CA-10, I-5).
     */
    @Test
    fun `C-03 - Given une parente revenu - When on cree une depense sous elle - Then refus ParentTypeMismatch et rien n'est ecrit (CA-10, I-5)`() =
        runTest {
            seedSal()
            val before = snapshot()

            val result = createCategory(NAME_LOYER, EXPENSE, COLOR_INTRUS, ICON_LOGT, catSalId)

            assertEquals(
                "C-03 — CA-10 : refus pour parent d'un autre type attendu ; obtenu $result ; " +
                    "table = ${categoryRows()}",
                CategoryWriteResult.Refused(CategoryRefusal.ParentTypeMismatch),
                result,
            )
            assertEquals("C-03 — I-5 : aucune table ne devait changer", before, snapshot())
            assertIntrusAbsent("C-03 — CA-10")
        }

    // ==============================================================================================
    // U — Modification (CA-04, CA-05, I-2)
    // ==============================================================================================

    /**
     * U-01 — Given CAT-ALIM portée par TX-UTIL et SERIE-UTIL, flux ouverts, When on la renomme
     * « Courses » avec une nouvelle icône et une nouvelle couleur, Then même identifiant, une seule
     * ligne aux nouveaux champs, type dépense, porteurs toujours rattachés ; l'ancien nom disparaît
     * des flux et le nouveau y apparaît une fois (CA-04, I-2).
     */
    @Test
    fun `U-01 - Given Alimentation utilisee par une transaction et une serie - When on la renomme Courses avec icone et couleur neuves - Then meme ligne et rattachements conserves (CA-04, I-2)`() =
        runTest {
            seedAlim()
            seedPorteurs(categoryId = catAlimId)

            lateinit var result: CategoryWriteResult
            lateinit var manage: Observed<CategoriesByType>
            lateinit var selector: Observed<List<CategoryEntity>>
            turbineScope {
                val manageFlow = observeCategories.observeGroupedByType().testIn(backgroundScope, TIMEOUT)
                val selectorFlow = categoryRepo.observeByType(EXPENSE.name).testIn(backgroundScope, TIMEOUT)
                val manageInitial = manageFlow.awaitItem()
                val selectorInitial = selectorFlow.awaitItem()

                result = updateCategory(catAlimId, NAME_COURSES, EXPENSE, COLOR_COURSES, ICON_COURSES, null)

                manage = manageFlow.settle(manageInitial, "U-01 — gestion")
                selector = selectorFlow.settle(selectorInitial, "U-01 — sélecteur")
            }
            assertEquals(
                "U-01 — synchro : sélecteur initial = ALIM seule",
                listOf(catAlimId),
                selector.initial.map { it.id },
            )

            assertEquals(
                "U-01 — CA-04 : succès sur l'identifiant d'ALIM attendu ; obtenu $result",
                CategoryWriteResult.Success(catAlimId),
                result,
            )
            assertEquals(
                "U-01 — CA-04, I-2 : une seule ligne, même identifiant, nouveaux nom, icône et " +
                    "couleur, type dépense ; table = ${categoryRows()}",
                listOf(row(catAlimId, NAME_COURSES, EXPENSE, COLOR_COURSES, ICON_COURSES, null)),
                categoryRows(),
            )
            assertPorteursRattaches("U-01 — I-2", catAlimId, EXPENSE)
            assertEquals(
                "U-01 — CA-04 : la gestion des dépenses devait montrer « Courses » une seule fois " +
                    "sous l'identifiant d'ALIM, « Alimentation » absent ; obtenu ${manage.final}",
                CategoriesByType(expense = listOf(CategoryWithSubs(persisted(catAlimId), emptyList()))),
                manage.final,
            )
            assertEquals(
                "U-01 — CA-04 : le sélecteur dépenses devait montrer « Courses » une seule fois, " +
                    "« Alimentation » absent ; obtenu ${selector.final}",
                listOf(persisted(catAlimId)),
                selector.final,
            )
            assertEquals(
                "U-01 — CA-04 : « Courses » est bien le nom persisté sous l'identifiant d'ALIM",
                NAME_COURSES,
                selector.final.single().name,
            )
            assertNoLateEmission(manage, "U-01 — gestion")
            assertNoLateEmission(selector, "U-01 — sélecteur")
        }

    /**
     * U-02 — Given CAT-ALIM utilisée, When on tente un nom blanc avec la couleur intruse, Then refus
     * [CategoryRefusal.BlankName] ; identifiant, nom, type, icône, couleur et rattachements
     * strictement inchangés (CA-05, I-2).
     */
    @Test
    fun `U-02 - Given Alimentation utilisee - When on tente un nom blanc avec la couleur intruse - Then refus BlankName et rien ne change (CA-05, I-2)`() =
        runTest {
            seedAlim()
            seedPorteurs(categoryId = catAlimId)
            val before = snapshot()

            val result = updateCategory(catAlimId, NAME_BLANK, EXPENSE, COLOR_INTRUS, ICON_COURSES, null)

            assertEquals(
                "U-02 — CA-05 : refus pour nom blanc attendu ; obtenu $result",
                CategoryWriteResult.Refused(CategoryRefusal.BlankName),
                result,
            )
            assertEquals("U-02 — CA-05, I-2 : aucune table ne devait changer", before, snapshot())
            assertIntrusAbsent("U-02 — CA-05")
        }

    // ==============================================================================================
    // D — Suppression (CA-06, CA-07, CA-15, I-1, I-7)
    // ==============================================================================================

    /**
     * D-01 — Given CAT-VIDE, CAT-TEMOIN et CAT-UTIL portée par TX-UTIL et SERIE-UTIL, flux ouverts,
     * When on supprime CAT-VIDE, Then elle disparaît de la table, de la gestion et du sélecteur ;
     * CAT-TEMOIN, CAT-UTIL et les deux porteurs sont intacts (CA-06, I-1).
     */
    @Test
    fun `D-01 - Given une categorie non utilisee - When on la supprime - Then elle disparait et rien d'autre ne change (CA-06, I-1)`() =
        runTest {
            seedVide()
            seedTemoin()
            seedUtilAvecPorteurs()
            val before = snapshot()

            lateinit var result: CategoryDeleteResult
            lateinit var manage: Observed<CategoriesByType>
            lateinit var selector: Observed<List<CategoryEntity>>
            turbineScope {
                val manageFlow = observeCategories.observeGroupedByType().testIn(backgroundScope, TIMEOUT)
                val selectorFlow = categoryRepo.observeByType(EXPENSE.name).testIn(backgroundScope, TIMEOUT)
                val manageInitial = manageFlow.awaitItem()
                val selectorInitial = selectorFlow.awaitItem()

                result = deleteCategory(catVideId)

                manage = manageFlow.settle(manageInitial, "D-01 — gestion")
                selector = selectorFlow.settle(selectorInitial, "D-01 — sélecteur")
            }
            assertEquals(
                "D-01 — synchro : sélecteur initial = VIDE et UTIL",
                setOf(catVideId, catUtilId),
                selector.initial.map { it.id }.toSet(),
            )

            assertEquals("D-01 — CA-06 : suppression attendue ; obtenu $result", CategoryDeleteResult.Deleted, result)
            assertEquals(
                "D-01 — CA-06 : seules les lignes de TEMOIN et d'UTIL devaient rester ; " +
                    "table = ${categoryRows()}",
                before.categories.filterNot { it.startsWith("$catVideId|") },
                categoryRows(),
            )
            assertEquals("D-01 — I-1 : transactions inchangées", before.transactions, transactionRows())
            assertEquals("D-01 — I-1 : séries inchangées", before.series, seriesRows())
            assertTemoinIntact("D-01")
            assertEquals(
                "D-01 — CA-06 : VIDE devait disparaître du sélecteur dépenses, UTIL rester seule ; " +
                    "obtenu ${selector.final.map { it.id }}",
                listOf(persisted(catUtilId)),
                selector.final,
            )
            assertEquals(
                "D-01 — CA-06 : la gestion devait montrer UTIL seule en dépenses et TEMOIN seule " +
                    "en revenus ; obtenu ${manage.final}",
                CategoriesByType(
                    expense = listOf(CategoryWithSubs(persisted(catUtilId), emptyList())),
                    income = listOf(CategoryWithSubs(persisted(catTemoinId), emptyList())),
                ),
                manage.final,
            )
            assertNoLateEmission(manage, "D-01 — gestion")
            assertNoLateEmission(selector, "D-01 — sélecteur")
        }

    /**
     * D-02 — Given CAT-UTIL portée par TX-UTIL et SERIE-UTIL, et CAT-TEMOIN, flux ouverts, When on
     * supprime CAT-UTIL, Then zéro ligne pour elle ; la transaction et la série existent toujours,
     * portent la sentinelle [NO_CATEGORY_ID] qu'aucune catégorie ne détient, et leur lecture métier
     * rend `category == null` (CA-07, I-1).
     */
    @Test
    fun `D-02 - Given une categorie utilisee par une transaction et une serie - When on la supprime - Then ses porteurs survivent sans categorie (CA-07, I-1)`() =
        runTest {
            seedUtilAvecPorteurs()
            seedTemoin()

            lateinit var result: CategoryDeleteResult
            lateinit var manage: Observed<CategoriesByType>
            lateinit var selector: Observed<List<CategoryEntity>>
            turbineScope {
                val manageFlow = observeCategories.observeGroupedByType().testIn(backgroundScope, TIMEOUT)
                val selectorFlow = categoryRepo.observeByType(EXPENSE.name).testIn(backgroundScope, TIMEOUT)
                val manageInitial = manageFlow.awaitItem()
                val selectorInitial = selectorFlow.awaitItem()

                result = deleteCategory(catUtilId)

                manage = manageFlow.settle(manageInitial, "D-02 — gestion")
                selector = selectorFlow.settle(selectorInitial, "D-02 — sélecteur")
            }
            assertEquals(
                "D-02 — synchro : sélecteur initial = UTIL seule",
                listOf(catUtilId),
                selector.initial.map { it.id },
            )

            assertEquals("D-02 — CA-07 : suppression attendue ; obtenu $result", CategoryDeleteResult.Deleted, result)
            assertEquals(
                "D-02 — CA-07 : seule TEMOIN devait rester ; table = ${categoryRows()}",
                listOf(temoinRow()),
                categoryRows(),
            )
            assertPorteursSansCategorie("D-02 — CA-07, I-1", txUtilId, serieUtilId)
            assertEquals("D-02 — CA-07 : sélecteur dépenses vide attendu", emptyList<CategoryEntity>(), selector.final)
            assertEquals(
                "D-02 — CA-07 : la gestion ne devait plus montrer que TEMOIN ; obtenu ${manage.final}",
                CategoriesByType(income = listOf(CategoryWithSubs(persisted(catTemoinId), emptyList()))),
                manage.final,
            )
            assertNoLateEmission(manage, "D-02 — gestion")
            assertNoLateEmission(selector, "D-02 — sélecteur")
        }

    /**
     * D-03 — Given CAT-ALIM et ses deux filles SUB-COURSES et SUB-CANTINE, TX-UTIL portée par la
     * parente, SERIE-UTIL portée par SUB-COURSES, et CAT-TEMOIN portant TX-TEMOIN, When on supprime
     * CAT-ALIM, Then zéro ligne pour la parente et ses deux filles ; TX-UTIL et SERIE-UTIL survivent
     * sans catégorie ; TEMOIN et TX-TEMOIN sont intacts ; le nombre de porteurs est inchangé
     * (CA-15, I-1, I-7).
     */
    @Test
    fun `D-03 - Given une parente avec deux filles et des porteurs sur la parente et une fille - When on supprime la parente - Then les trois disparaissent et les porteurs survivent sans categorie (CA-15, I-1, I-7)`() =
        runTest {
            seedAlim()
            seedCourses()
            seedCantine()
            seedTemoin()
            seedCompte()
            txUtilId = insertTxUtil(categoryId = catAlimId)
            serieUtilId = insertSerieUtil(categoryId = subCoursesId)
            txTemoinId = insertTxTemoin()

            val result = deleteCategory(catAlimId)

            assertEquals("D-03 — CA-15 : suppression attendue ; obtenu $result", CategoryDeleteResult.Deleted, result)
            assertEquals(
                "D-03 — CA-15, I-7 : la parente et ses deux filles devaient disparaître, TEMOIN " +
                    "rester seule ; table = ${categoryRows()}",
                listOf(temoinRow()),
                categoryRows(),
            )
            assertPorteursSansCategorie("D-03 — CA-15, I-1", txUtilId, serieUtilId)
            assertEquals(
                "D-03 — CA-15 : TX-TEMOIN ne devait pas être réaffectée ; transactions = ${transactionRows()}",
                txRow(txTemoinId, INCOME, catTemoinId),
                transactionRows().single { it.startsWith("$txTemoinId|") },
            )
            assertEquals("D-03 — I-1 : deux transactions attendues", 2, transactionRows().size)
            assertTemoinIntact("D-03")
        }

    // ==============================================================================================
    // P — Parent (CA-09, CA-10, I-4, I-5)
    // ==============================================================================================

    /**
     * P-01 — Given CAT-ALIM qui a SUB-COURSES, et CAT-LOGT, When on tente d'affecter CAT-LOGT comme
     * parent d'ALIM avec la couleur intruse, Then refus [CategoryRefusal.HasChildren] ; le parent
     * d'ALIM reste nul, SUB-COURSES reste sa fille, rien n'est écrit (CA-09, I-4).
     */
    @Test
    fun `P-01 - Given Alimentation qui a une sous-categorie - When on lui affecte un parent - Then refus HasChildren et rien n'est ecrit (CA-09, I-4)`() =
        runTest {
            seedAlim()
            seedCourses()
            seedLogt()
            val before = snapshot()

            val result = updateCategory(catAlimId, NAME_ALIM, EXPENSE, COLOR_INTRUS, ICON_ALIM, catLogtId)

            assertEquals(
                "P-01 — I-4 : le parent d'ALIM devait rester nul ; table = ${categoryRows()}",
                null,
                categoryById(catAlimId).parentCategoryId,
            )
            assertEquals(
                "P-01 — CA-09 : SUB-COURSES devait rester rattachée à ALIM ; table = ${categoryRows()}",
                catAlimId,
                categoryById(subCoursesId).parentCategoryId,
            )
            assertEquals(
                "P-01 — CA-09 : refus pour sous-catégories attendu ; obtenu $result ; table = ${categoryRows()}",
                CategoryWriteResult.Refused(CategoryRefusal.HasChildren),
                result,
            )
            assertEquals("P-01 — CA-09 : aucune table ne devait changer", before, snapshot())
            assertIntrusAbsent("P-01 — CA-09")
        }

    /**
     * P-02 — Given CAT-VIDE (dépense) et CAT-SAL (revenu), When on tente CAT-SAL comme parent de
     * CAT-VIDE avec la couleur intruse, Then refus [CategoryRefusal.ParentTypeMismatch] ; aucun parent
     * écrit, tous les champs de CAT-VIDE inchangés (CA-10, I-5).
     */
    @Test
    fun `P-02 - Given une depense sans enfant - When on lui affecte un parent de type revenu - Then refus ParentTypeMismatch et rien n'est ecrit (CA-10, I-5)`() =
        runTest {
            seedVide()
            seedSal()
            val before = snapshot()

            val result = updateCategory(catVideId, NAME_VIDE, EXPENSE, COLOR_INTRUS, ICON_VIDE, catSalId)

            assertEquals(
                "P-02 — I-5 : aucun parent de type revenu ne devait être écrit ; table = ${categoryRows()}",
                null,
                categoryById(catVideId).parentCategoryId,
            )
            assertEquals(
                "P-02 — CA-10 : refus pour parent d'un autre type attendu ; obtenu $result ; " +
                    "table = ${categoryRows()}",
                CategoryWriteResult.Refused(CategoryRefusal.ParentTypeMismatch),
                result,
            )
            assertEquals("P-02 — CA-10 : aucune table ne devait changer", before, snapshot())
            assertIntrusAbsent("P-02 — CA-10")
        }

    // ==============================================================================================
    // T — Type (CA-12, CA-13, I-6)
    // ==============================================================================================

    /**
     * T-01 — Given CAT-UTIL (dépense) portée par TX-UTIL et SERIE-UTIL, When on tente le type revenu
     * avec la couleur intruse, Then refus [CategoryRefusal.CategoryInUse] ; catégorie et porteurs
     * restent dépense et gardent le même rattachement, rien n'est écrit (CA-12, I-6).
     */
    @Test
    fun `T-01 - Given une depense utilisee - When on tente le type revenu - Then refus CategoryInUse et rien n'est ecrit (CA-12, I-6)`() =
        runTest {
            seedUtilAvecPorteurs()
            val before = snapshot()

            val result = updateCategory(catUtilId, NAME_UTIL, INCOME, COLOR_INTRUS, ICON_UTIL, null)

            assertEquals(
                "T-01 — I-6 : UTIL devait rester de type dépense ; table = ${categoryRows()}",
                EXPENSE,
                categoryById(catUtilId).type,
            )
            assertPorteursRattaches("T-01 — I-6", catUtilId, EXPENSE)
            assertEquals(
                "T-01 — CA-12 : refus pour catégorie utilisée attendu ; obtenu $result ; " +
                    "table = ${categoryRows()}",
                CategoryWriteResult.Refused(CategoryRefusal.CategoryInUse),
                result,
            )
            assertEquals("T-01 — CA-12 : aucune table ne devait changer", before, snapshot())
            assertIntrusAbsent("T-01 — CA-12")
        }

    /**
     * T-02 — Given CAT-ALIM qui a SUB-COURSES, When on tente le type revenu avec la couleur intruse,
     * Then refus [CategoryRefusal.HasChildren] ; ALIM et SUB-COURSES restent dépenses et liées, rien
     * n'est écrit (CA-12, I-6).
     */
    @Test
    fun `T-02 - Given Alimentation qui a une sous-categorie - When on tente le type revenu - Then refus HasChildren et rien n'est ecrit (CA-12, I-6)`() =
        runTest {
            seedAlim()
            seedCourses()
            val before = snapshot()

            val result = updateCategory(catAlimId, NAME_ALIM, INCOME, COLOR_INTRUS, ICON_ALIM, null)

            assertEquals(
                "T-02 — I-6 : ALIM et SUB-COURSES devaient rester dépenses ; table = ${categoryRows()}",
                listOf(EXPENSE, EXPENSE),
                listOf(categoryById(catAlimId).type, categoryById(subCoursesId).type),
            )
            assertEquals(
                "T-02 — I-6 : SUB-COURSES devait rester rattachée à ALIM",
                catAlimId,
                categoryById(subCoursesId).parentCategoryId,
            )
            assertEquals(
                "T-02 — CA-12 : refus pour sous-catégories attendu ; obtenu $result ; " +
                    "table = ${categoryRows()}",
                CategoryWriteResult.Refused(CategoryRefusal.HasChildren),
                result,
            )
            assertEquals("T-02 — CA-12 : aucune table ne devait changer", before, snapshot())
            assertIntrusAbsent("T-02 — CA-12")
        }

    /**
     * T-03 — Given CAT-VIDE dépense sans usage ni enfant, flux ouverts, When on la passe en revenu,
     * Then même identifiant, une seule ligne, type revenu ; absente de la gestion et du sélecteur
     * dépenses, présente une fois dans la gestion et le sélecteur revenus (CA-13, I-6).
     */
    @Test
    fun `T-03 - Given une depense sans usage ni enfant - When on la passe en revenu - Then meme ligne de type revenu qui change de liste (CA-13, I-6)`() =
        runTest {
            seedVide()

            lateinit var result: CategoryWriteResult
            lateinit var manage: Observed<CategoriesByType>
            lateinit var expense: Observed<List<CategoryEntity>>
            lateinit var income: Observed<List<CategoryEntity>>
            turbineScope {
                val manageFlow = observeCategories.observeGroupedByType().testIn(backgroundScope, TIMEOUT)
                val expenseFlow = categoryRepo.observeByType(EXPENSE.name).testIn(backgroundScope, TIMEOUT)
                val incomeFlow = categoryRepo.observeByType(INCOME.name).testIn(backgroundScope, TIMEOUT)
                val manageInitial = manageFlow.awaitItem()
                val expenseInitial = expenseFlow.awaitItem()
                val incomeInitial = incomeFlow.awaitItem()

                result = updateCategory(catVideId, NAME_VIDE, INCOME, COLOR_VIDE, ICON_VIDE, null)

                manage = manageFlow.settle(manageInitial, "T-03 — gestion")
                expense = expenseFlow.settle(expenseInitial, "T-03 — sélecteur dépenses")
                income = incomeFlow.settle(incomeInitial, "T-03 — sélecteur revenus")
            }
            assertEquals("T-03 — synchro : sélecteur dépenses = VIDE", listOf(catVideId), expense.initial.map { it.id })
            assertEquals("T-03 — synchro : sélecteur revenus vide", emptyList<CategoryEntity>(), income.initial)

            assertEquals(
                "T-03 — CA-13 : succès sur l'identifiant de VIDE attendu ; obtenu $result",
                CategoryWriteResult.Success(catVideId),
                result,
            )
            assertEquals(
                "T-03 — CA-13 : une seule ligne, même identifiant, type revenu, autres champs " +
                    "inchangés ; table = ${categoryRows()}",
                listOf(row(catVideId, NAME_VIDE, INCOME, COLOR_VIDE, ICON_VIDE, null)),
                categoryRows(),
            )
            assertEquals("T-03 — CA-13 : absente du sélecteur dépenses", emptyList<CategoryEntity>(), expense.final)
            assertEquals(
                "T-03 — CA-13 : présente une seule fois dans le sélecteur revenus ; obtenu ${income.final}",
                listOf(persisted(catVideId)),
                income.final,
            )
            assertEquals(
                "T-03 — CA-13 : absente de la gestion des dépenses, une fois en revenus ; " +
                    "obtenu ${manage.final}",
                CategoriesByType(income = listOf(CategoryWithSubs(persisted(catVideId), emptyList()))),
                manage.final,
            )
            assertNoLateEmission(manage, "T-03 — gestion")
            assertNoLateEmission(expense, "T-03 — sélecteur dépenses")
            assertNoLateEmission(income, "T-03 — sélecteur revenus")
        }

    // ==============================================================================================
    // Jeu de données — écrit par les DAO réels, identifiants rendus par Room
    // ==============================================================================================

    private suspend fun insertCategory(
        name: String,
        type: TransactionType,
        color: Int,
        icon: String,
        parentId: Long? = null,
    ): Long = db.categoryDao().upsert(
        CategoryEntity(name = name, type = type, colorArgb = color, icon = icon, parentCategoryId = parentId),
    )

    private suspend fun seedAlim() { catAlimId = insertCategory(NAME_ALIM, EXPENSE, COLOR_ALIM, ICON_ALIM) }
    private suspend fun seedLogt() { catLogtId = insertCategory(NAME_LOGT, EXPENSE, COLOR_LOGT, ICON_LOGT) }
    private suspend fun seedSal() { catSalId = insertCategory(NAME_SAL, INCOME, COLOR_SAL, ICON_SAL) }
    private suspend fun seedVide() { catVideId = insertCategory(NAME_VIDE, EXPENSE, COLOR_VIDE, ICON_VIDE) }
    private suspend fun seedTemoin() { catTemoinId = insertCategory(NAME_TEMOIN, INCOME, COLOR_TEMOIN, ICON_TEMOIN) }

    private suspend fun seedCourses() {
        subCoursesId = insertCategory(NAME_COURSES, EXPENSE, COLOR_SUB_COURSES, ICON_COURSES, catAlimId)
    }

    private suspend fun seedCantine() {
        subCantineId = insertCategory(NAME_CANTINE, EXPENSE, COLOR_CANTINE, ICON_CANTINE, catAlimId)
    }

    private suspend fun seedCompte() {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 250_000L,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
    }

    private suspend fun seedPorteurs(categoryId: Long) {
        seedCompte()
        txUtilId = insertTxUtil(categoryId)
        serieUtilId = insertSerieUtil(categoryId)
    }

    private suspend fun seedUtilAvecPorteurs() {
        catUtilId = insertCategory(NAME_UTIL, EXPENSE, COLOR_UTIL, ICON_UTIL)
        seedPorteurs(catUtilId)
    }

    private suspend fun insertTxUtil(categoryId: Long): Long = db.transactionDao().upsert(
        TransactionEntity(
            title = TX_UTIL_TITLE,
            amount = TX_UTIL_AMOUNT,
            type = EXPENSE,
            status = TransactionStatus.PAID,
            date = marsSlot,
            accountId = accountId,
            categoryId = categoryId,
        ),
    )

    private suspend fun insertTxTemoin(): Long = db.transactionDao().upsert(
        TransactionEntity(
            title = TX_TEMOIN_TITLE,
            amount = TX_TEMOIN_AMOUNT,
            type = INCOME,
            status = TransactionStatus.PAID,
            date = marsSlot,
            accountId = accountId,
            categoryId = catTemoinId,
        ),
    )

    private suspend fun insertSerieUtil(categoryId: Long): Long = db.recurringSeriesDao().upsertSeries(
        RecurringSeriesEntity(
            title = SERIE_UTIL_TITLE,
            amount = SERIE_UTIL_AMOUNT,
            type = EXPENSE,
            categoryId = categoryId,
            accountId = accountId,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = marsSlot,
        ),
    )

    // ==============================================================================================
    // Constats
    // ==============================================================================================

    private data class Snapshot(val categories: List<String>, val transactions: List<String>, val series: List<String>)

    private fun snapshot() = Snapshot(categoryRows(), transactionRows(), seriesRows())

    private fun categoryRows() = rawRows(
        "SELECT id, name, type, colorArgb, icon, parentCategoryId FROM categories ORDER BY id",
    )

    private fun transactionRows() = rawRows("SELECT id, type, categoryId, deleted FROM transactions ORDER BY id")

    private fun seriesRows() = rawRows("SELECT id, type, categoryId, isCancelled FROM recurring_series ORDER BY id")

    private fun row(id: Long, name: String, type: TransactionType, color: Int, icon: String, parentId: Long?) =
        "$id|$name|${type.name}|$color|$icon|${parentId ?: "null"}"

    private fun txRow(id: Long, type: TransactionType, categoryId: Long) = "$id|${type.name}|$categoryId|0"

    private fun temoinRow() = row(catTemoinId, NAME_TEMOIN, INCOME, COLOR_TEMOIN, ICON_TEMOIN, null)

    private suspend fun categoryById(id: Long): CategoryEntity =
        db.categoryDao().getById(id) ?: throw AssertionError("catégorie $id absente ; table = ${categoryRows()}")

    /** La ligne telle que persistée, lue en base : sert à comparer une émission à l'état réel. */
    private suspend fun persisted(id: Long): CategoryEntity = categoryById(id)

    private fun assertIntrusAbsent(label: String) {
        val intruders = categoryRows().filter { it.split("|")[3] == COLOR_INTRUS.toString() }
        assertEquals(
            "$label : la couleur intruse, soumise dans une écriture refusée, ne devait apparaître " +
                "nulle part ; lignes = $intruders",
            0,
            intruders.size,
        )
    }

    private fun assertTemoinIntact(caseId: String) {
        assertEquals(
            "$caseId : CAT-TEMOIN, jamais ciblée, devait rester inchangée ; table = ${categoryRows()}",
            1,
            categoryRows().count { it == temoinRow() },
        )
    }

    /** TX-UTIL et SERIE-UTIL existent, une fois chacune, rattachées à [categoryId] et de type [type]. */
    private fun assertPorteursRattaches(label: String, categoryId: Long, type: TransactionType) {
        assertEquals(
            "$label : TX-UTIL devait rester rattachée à $categoryId ; transactions = ${transactionRows()}",
            listOf(txRow(txUtilId, type, categoryId)),
            transactionRows(),
        )
        assertEquals(
            "$label : SERIE-UTIL devait rester rattachée à $categoryId ; séries = ${seriesRows()}",
            listOf("$serieUtilId|${type.name}|$categoryId|0"),
            seriesRows(),
        )
    }

    /**
     * La transaction et la série existent toujours, portent [NO_CATEGORY_ID] qu'aucune catégorie ne
     * détient, et leur lecture métier rend `category == null` : c'est ce que l'écran affiche
     * « Sans catégorie ».
     */
    private suspend fun assertPorteursSansCategorie(label: String, txId: Long, seriesId: Long) {
        assertEquals(
            "$label : la transaction devait survivre avec la sentinelle ; transactions = ${transactionRows()}",
            "$txId|${EXPENSE.name}|$NO_CATEGORY_ID|0",
            transactionRows().singleOrNull { it.startsWith("$txId|") },
        )
        assertEquals(
            "$label : la série devait survivre avec la sentinelle ; séries = ${seriesRows()}",
            listOf("$seriesId|${EXPENSE.name}|$NO_CATEGORY_ID|0"),
            seriesRows(),
        )
        assertEquals(
            "$label : aucune catégorie ne devait porter l'identifiant de la sentinelle",
            0,
            rawRows("SELECT id FROM categories WHERE id = $NO_CATEGORY_ID").size,
        )

        val tx = observeDetail.getById(txId)
            ?: throw AssertionError("$label : la lecture métier de la transaction $txId ne rend rien")
        assertNull("$label : la transaction ne devait plus résoudre de catégorie ; obtenu ${tx.category}", tx.category)

        val occurrenceId = RecurrenceEngine.calculateVirtualId(seriesId, marsSlot)
        val occurrence = observeDetail.getById(occurrenceId)
            ?: throw AssertionError("$label : la lecture métier de l'occurrence de la série $seriesId ne rend rien")
        assertEquals("$label : l'occurrence lue devait appartenir à la série", seriesId, occurrence.transaction.seriesId)
        assertNull(
            "$label : l'occurrence de la série ne devait plus résoudre de catégorie ; obtenu ${occurrence.category}",
            occurrence.category,
        )
    }

    private suspend fun <T> firstOf(flow: kotlinx.coroutines.flow.Flow<T>): T {
        var first: T? = null
        flow.test(timeout = TIMEOUT) {
            first = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        @Suppress("UNCHECKED_CAST")
        return first as T
    }

    /** Ce qu'un flux ouvert avant l'action a émis : sa synchro, son émission finale, puis le reste. */
    private data class Observed<T>(val initial: T, val final: T, val late: List<T>)

    /**
     * Attend la première émission différente de [initial] — les répétitions strictes ne portent aucun
     * changement —, puis clôt le flux en gardant toute émission tardive qui en diffère.
     *
     * Le flux est clos **avant** que le moindre oracle ne s'exécute : un échec levé pendant qu'un flux
     * est encore ouvert est enrobé par `turbineScope` dans un rapport d'événements non consommés, qui
     * relègue le message du cas au rang de cause.
     */
    private suspend fun <T> ReceiveTurbine<T>.settle(initial: T, label: String): Observed<T> {
        var final: T
        do {
            final = try {
                awaitItem()
            } catch (e: AssertionError) {
                throw AssertionError("$label : aucune émission nouvelle après l'action ; dernier état observé = $initial", e)
            }
        } while (final == initial)
        val late = cancelAndConsumeRemainingEvents()
            .filterIsInstance<Event.Item<T>>()
            .map { it.value }
            .filter { it != final }
        return Observed(initial, final, late)
    }

    private fun <T> assertNoLateEmission(observed: Observed<T>, label: String) {
        assertEquals("$label : émission tardive différente de l'état final", emptyList<T>(), observed.late)
    }

    private fun rawRows(sql: String): List<String> = buildList {
        db.query(sql, emptyArray<Any?>()).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).joinToString("|") { column ->
                        if (cursor.isNull(column)) "null" else cursor.getString(column)
                    },
                )
            }
        }
    }

    /** Date métier fixe : 10 mars 2026, 09:00, Europe/Paris. Aucune horloge n'est lue. */
    private val marsSlot: Long =
        LocalDate.of(2026, 3, 10).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds

        const val NAME_ALIM = "Alimentation"
        const val NAME_LOGT = "Logement"
        const val NAME_SAL = "Salaire"
        const val NAME_COURSES = "Courses"
        const val NAME_CANTINE = "Cantine"
        const val NAME_VIDE = "Temporaire"
        const val NAME_UTIL = "Transport"
        const val NAME_TEMOIN = "Prime"
        const val NAME_COURSES_UNTRIMMED = "  Courses  "
        const val NAME_LOYER = "Loyer"
        const val NAME_EMPTY = ""
        const val NAME_BLANK = "   "

        const val ICON_ALIM = "restaurant"
        const val ICON_LOGT = "home"
        const val ICON_SAL = "work"
        const val ICON_COURSES = "shopping_cart"
        const val ICON_CANTINE = "sports_esports"
        const val ICON_VIDE = "category"
        const val ICON_UTIL = "directions_bus"
        const val ICON_TEMOIN = "bolt"

        /** Couleurs toutes distinctes : aucune confusion de ligne ne peut tomber juste. */
        val COLOR_ALIM = 0xFFE91E63.toInt()
        val COLOR_LOGT = 0xFF3F51B5.toInt()
        val COLOR_SAL = 0xFF009688.toInt()
        val COLOR_SUB_COURSES = 0xFFFF9800.toInt()
        val COLOR_CANTINE = 0xFF673AB7.toInt()
        val COLOR_VIDE = 0xFF795548.toInt()
        val COLOR_UTIL = 0xFF00BCD4.toInt()
        val COLOR_TEMOIN = 0xFF8BC34A.toInt()
        /** Nouvelle couleur de C-01 et U-01 : jamais portée par une ligne semée. */
        val COLOR_COURSES = 0xFF4CAF50.toInt()
        /** COULEUR-INTRUSE : soumise uniquement dans des écritures qui doivent être refusées. */
        val COLOR_INTRUS = 0xFF607D8B.toInt()

        const val TX_UTIL_TITLE = "Ticket métro"
        const val TX_UTIL_AMOUNT = 4_550L
        const val SERIE_UTIL_TITLE = "Pass transport"
        const val SERIE_UTIL_AMOUNT = 7_500L
        const val TX_TEMOIN_TITLE = "Prime de mars"
        const val TX_TEMOIN_AMOUNT = 30_000L
    }
}
