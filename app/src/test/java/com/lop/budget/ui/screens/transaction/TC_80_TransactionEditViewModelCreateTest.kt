package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.DebtRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TagRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CreateTransactionUseCase
import com.lop.budget.domain.usecase.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.ObserveTransactionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * TC-80 — `TransactionEditViewModel` en **mode ajout uniquement** (`id` absent du
 * `SavedStateHandle`).
 *
 * ## Niveau et chaîne exercée
 * Test **unitaire ViewModel**, mocks stricts MockK, `StandardTestDispatcher` +
 * `Dispatchers.setMain`. Chaîne réellement exercée :
 * `SavedStateHandle` → `TransactionEditViewModel` (init / setters / `save`) →
 * `CreateTransactionUseCase` (mocké). Rien en-dessous n'est exercé : ce ticket prouve
 * *quelle dépendance est appelée et avec quoi*, pas ce qui est écrit en base.
 *
 * ## Traçabilité — cas → CA/invariant → production
 * | Cas   | CA / invariant | Fonction de production                                   |
 * |-------|----------------|----------------------------------------------------------|
 * | A-01  | CA-01          | `TransactionEditViewModel.init`, `TransactionForm` (défauts) |
 * | A-02  | CA-01          | `TransactionEditViewModel.init` (clé `type` du handle)     |
 * | A-03  | CA-03          | `TransactionEditViewModel.setType`                         |
 * | A-03b | CA-03          | `TransactionEditViewModel.setType`                         |
 * | A-04  | CA-04, I-1     | `TransactionEditViewModel.save`, `TransactionForm.isValid` |
 * | A-05  | CA-04          | `TransactionEditViewModel.save`, `TransactionForm.isValid` |
 * | A-06  | CA-05          | `TransactionEditViewModel.performSave`, `toEdition`        |
 * | A-07  | CA-10, I-2     | `TransactionEditViewModel.save` / `performSave`            |
 * | A-08  | CA-09, I-3     | `TransactionEditViewModel.init` + setters                  |
 * | A-09  | CA-04          | `TransactionEditViewModel.clearFieldError` via les setters  |
 * | A-07d | CA-10          | `save` — branche `catch (CancellationException)`            |
 *
 * ## Anomalies — état courant
 * Les oracles sont ceux de la spécification et n'ont jamais été assouplis.
 * Voir le commentaire d'ANO déposé sur le ticket Notion TC-80.
 *
 * Traitées (les cas correspondants sont verts) :
 * - **ANO-A** (A-03) — `setType` ne réinitialisait pas `categoryId` devenu incohérent.
 *   Corrigé : `setType` vide la catégorie sur changement effectif de type.
 * - **ANO-B** (A-07a) — `_isSaving` n'était levé que dans `performSave`, après le `launch`,
 *   si bien que deux `save()` successifs franchissaient la garde. Corrigé : verrou pris
 *   atomiquement et synchroniquement par `tryAcquireSaveLock()`.
 * - **ANO-D** (A-01, A-02) — `SettingsRepository` était injecté et jamais utilisé.
 *   Corrigé : le ViewModel expose `currency`, alimenté par `settings.currency`.
 *
 * - **ANO-C** (A-04, A-05, A-09) — aucun état d'erreur par champ n'existait. Corrigé par
 *   l'enabler « Erreurs de formulaire de transaction » : `validate()` rattache chaque
 *   manquement à son champ dans `fieldErrors`, et les setters purgent l'erreur corrigée.
 * - **ANO-M4** (A-07c) — l'exception du use case remontait non capturée hors de
 *   `viewModelScope.launch`. Corrigé : `catch` avec re-`throw` de `CancellationException`
 *   et alimentation de `saveError`.
 *
 * Aucune anomalie ouverte : la suite doit être entièrement verte.
 *
 * ## Hors périmètre (explicitement non couvert ici)
 * - Portées SINGLE/FUTURE/ALL et préremplissage en édition → TC-75.
 * - Cardinalités et lignes Room réellement écrites (I-4) → TC-82 (appels use case) et
 *   TC-83 (lignes Room). Un mock ne voit pas les INSERT.
 * - I-6 (masquage du toggle « payé » en récurrent) : règle d'UI, → tickets Compose/Maestro.
 * - Rendu visuel des erreurs (position, Snackbar) : règle d'UI, → tickets Compose/Maestro.
 *   Ce fichier prouve l'état du ViewModel, pas ce que l'écran en fait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionEditViewModelCreateTest {

    private val testDispatcher = StandardTestDispatcher()

    // --- Mocks stricts (aucun relaxed) ---
    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val categoryRepo = mockk<CategoryRepository>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val tagRepo = mockk<TagRepository>(relaxed = false)
    private val goalRepo = mockk<GoalRepository>(relaxed = false)
    private val debtRepo = mockk<DebtRepository>(relaxed = false)
    private val createTransactionUseCase = mockk<CreateTransactionUseCase>(relaxed = false)
    private val editTransactionWithScopeUseCase =
        mockk<EditTransactionWithScopeUseCase>(relaxed = false)
    private val observeTransactionUseCase = mockk<ObserveTransactionUseCase>(relaxed = false)
    private val settings = mockk<SettingsRepository>(relaxed = false)
    private val context = mockk<Context>(relaxed = false)

    private val allMocks = arrayOf(
        accountRepo, categoryRepo, transactionRepo, tagRepo, goalRepo, debtRepo,
        createTransactionUseCase, editTransactionWithScopeUseCase,
        observeTransactionUseCase, settings, context
    )

    /**
     * Jeu de données déclaré **localement** : deux comptes rendent « le premier compte »
     * discriminant, et les cardinalités exactes de ce fichier n'en dépendent pas d'un
     * fixture partagé qu'un autre ticket pourrait faire évoluer.
     */
    private val primaryAccount = account(id = 100L)
    private val secondaryAccount = account(id = 200L)
    private val expenseCategory = category(id = 10L, type = TransactionType.EXPENSE)
    private val incomeCategory = category(id = 20L, type = TransactionType.INCOME)

    /** Distincte du défaut « EUR » de `SettingsRepository.currency` : l'oracle A-01 discrimine. */
    private val appCurrency = "USD"

    /** Fuseau forcé : l'oracle « jour civil courant » ne doit pas dépendre du fuseau de la CI. */
    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    private val defaultTitle = "Transaction"
    private val newTransactionId = 4242L

    private val doneIds = mutableListOf<Long>()
    private val onDone: (Long) -> Unit = { doneIds += it }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { context.getString(R.string.tx_default_title) } returns defaultTitle
        every { categoryRepo.observeByType(TransactionType.EXPENSE.name) } returns
            flowOf(listOf(expenseCategory))
        every { categoryRepo.observeByType(TransactionType.INCOME.name) } returns
            flowOf(listOf(incomeCategory))
        every { accountRepo.observeAll() } returns flowOf(listOf(primaryAccount, secondaryAccount))
        every { tagRepo.observeAll() } returns flowOf(emptyList())
        every { goalRepo.observeAll() } returns flowOf(emptyList())
        every { debtRepo.observeAll() } returns flowOf(emptyList())
        every { settings.currency } returns flowOf(appCurrency)

        // Lectures d'initialisation : exclues du bilan de `confirmVerified`.
        // `settings.currency` en fait partie : l'oracle de devise d'A-01/A-02 porte sur la
        // valeur exposée par `vm.currency`, pas sur le fait qu'un getter ait été touché.
        excludeRecords {
            categoryRepo.observeByType(any())
            accountRepo.observeAll()
            tagRepo.observeAll()
            goalRepo.observeAll()
            debtRepo.observeAll()
            context.getString(any())
            settings.currency
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ Fixtures

    private fun account(id: Long, balanceUpdatedAt: Long = 0L) = AccountEntity(
        id = id, name = "Account $id", type = AccountType.CHECKING,
        initialBalance = 1000.0, balanceUpdatedAt = balanceUpdatedAt,
        colorArgb = 0, icon = "wallet"
    )

    private fun category(id: Long, type: TransactionType) = CategoryEntity(
        id = id, name = "Category $id", type = type,
        colorArgb = 0, icon = "category", parentCategoryId = null
    )

    /** SUT en **création** : le `SavedStateHandle` ne porte jamais la clé `id`. */
    private fun createSut(type: TransactionType): TransactionEditViewModel =
        TransactionEditViewModel(
            accountRepo, categoryRepo, transactionRepo, tagRepo, goalRepo, debtRepo,
            createTransactionUseCase, editTransactionWithScopeUseCase,
            observeTransactionUseCase, settings,
            SavedStateHandle(mapOf("type" to type.name)), context
        )

    /** Aucun use case d'écriture n'a été sollicité. */
    private fun verifyNoWrite() {
        coVerify(exactly = 0) { createTransactionUseCase(any()) }
        coVerify(exactly = 0) {
            editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
        }
    }

    private fun civilDayOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /**
     * `TransactionEditViewModel.currency` est partagé en `SharingStarted.WhileSubscribed` :
     * sans abonné, `value` reste la valeur initiale de repli et non celle des settings.
     * On souscrit dans le `backgroundScope` du test pour lire la valeur réellement exposée.
     */
    private fun TestScope.currencyExposedBy(sut: TransactionEditViewModel): String {
        // `UnconfinedTestDispatcher` : l'abonnement doit démarrer immédiatement, sinon il
        // reste en file d'attente et le partage `WhileSubscribed` ne s'amorce jamais.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.currency.collect { }
        }
        advanceUntilIdle()
        return sut.currency.value
    }

    // ------------------------------------------------------- A-01 / A-02 : défauts

    @Test
    fun `A-01 - Given handle type EXPENSE et comptes non vides - When init - Then defauts d'ouverture coherents (CA-01)`() =
        runTest(testDispatcher) {
            // La fenêtre entre cette capture et la construction du VM est de l'ordre de la
            // microseconde ; `TransactionForm.date` vaut `System.currentTimeMillis()` et le VM
            // n'expose aucune horloge injectable (production intouchable sur ce ticket).
            val expectedDay = LocalDate.now(zone)

            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            val form = sut.form.value
            assertEquals(
                "CA-01 : le type d'ouverture doit venir de SavedStateHandle[\"type\"]",
                TransactionType.EXPENSE, form.type
            )
            assertEquals(
                "CA-01 : form.date doit tomber sur le jour civil courant (fuseau $zone)",
                expectedDay, civilDayOf(form.date)
            )
            assertEquals(
                "CA-01 : le premier compte de accountRepo.observeAll() doit être présélectionné",
                primaryAccount.id, form.accountId
            )
            assertEquals(
                "CA-01 : statut par défaut en ajout, tel que porté par TransactionForm.status",
                TransactionStatus.PLANNED, form.status
            )
            assertFalse(
                "CA-01 : le formulaire d'ajout ne doit porter aucun rattachement de série",
                form.seriesId != null
            )
            assertEquals(
                "CA-04 : un formulaire fraîchement ouvert est vide, pas fautif — aucune erreur affichée",
                emptyMap<TransactionFormField, Int>(), sut.fieldErrors.value
            )
            verifyNoWrite()

            // CA-01 — devise de l'application (ANO-D corrigée).
            assertEquals(
                "CA-01 : la devise exposée au formulaire doit être celle des settings",
                appCurrency, currencyExposedBy(sut)
            )
            confirmVerified(*allMocks)
        }

    @Test
    fun `A-02 - Given handle type INCOME - When init - Then type INCOME et memes defauts qu'en A-01 (CA-01)`() =
        runTest(testDispatcher) {
            val expectedDay = LocalDate.now(zone)

            val sut = createSut(type = TransactionType.INCOME)
            advanceUntilIdle()

            val form = sut.form.value
            assertEquals(
                "CA-01 : SavedStateHandle[\"type\"] = INCOME doit ouvrir un formulaire de revenu",
                TransactionType.INCOME, form.type
            )
            assertEquals(
                "CA-01 : form.date doit tomber sur le jour civil courant (fuseau $zone)",
                expectedDay, civilDayOf(form.date)
            )
            assertEquals(
                "CA-01 : le premier compte doit être présélectionné, quel que soit le type",
                primaryAccount.id, form.accountId
            )
            assertEquals(
                "CA-01 : statut par défaut en ajout, identique quel que soit le type",
                TransactionStatus.PLANNED, form.status
            )
            verifyNoWrite()

            // CA-01 — même oracle qu'en A-01 : « le reste des défauts comme A-01 ».
            assertEquals(
                "CA-01 : la devise exposée ne dépend pas du type d'ouverture",
                appCurrency, currencyExposedBy(sut)
            )
            confirmVerified(*allMocks)
        }

    // ------------------------------------------------ A-03 : changement de type (CA-03)

    @Test
    fun `A-03 - Given formulaire EXPENSE renseigne - When setType INCOME - Then titre et montant conserves et categorie videe (CA-03)`() =
        runTest(testDispatcher) {
            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)

            sut.setType(TransactionType.INCOME)

            val form = sut.form.value
            assertEquals(
                "CA-03 : le type sélectionné doit être appliqué",
                TransactionType.INCOME, form.type
            )
            assertEquals(
                "CA-03 : le titre reste cohérent après changement de type, il ne doit pas être réinitialisé",
                "Courses", form.title
            )
            assertEquals(
                "CA-03 : le montant reste cohérent après changement de type, il ne doit pas être réinitialisé",
                "42.50", form.amountInput
            )
            assertNull(
                "CA-03/ANO-A : une catégorie de dépense devient incohérente en INCOME, " +
                    "TransactionEditViewModel.setType doit la vider",
                form.categoryId
            )
            verifyNoWrite()
            confirmVerified(*allMocks)
        }

    @Test
    fun `A-03b - Given formulaire EXPENSE avec categorie de depense - When setType EXPENSE - Then la categorie compatible est conservee (CA-03)`() =
        runTest(testDispatcher) {
            // Garde de non-régression pour le correctif d'ANO-A : le futur `setType` doit vider
            // la catégorie *devenue incohérente*, et elle seule — pas à chaque appel.
            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setCategory(expenseCategory.id)
            sut.setType(TransactionType.EXPENSE)

            val form = sut.form.value
            assertEquals(
                "CA-03 : le type doit rester EXPENSE",
                TransactionType.EXPENSE, form.type
            )
            assertEquals(
                "CA-03 : une catégorie déjà compatible avec le type ne doit pas être vidée",
                expenseCategory.id, form.categoryId
            )
            verifyNoWrite()
            confirmVerified(*allMocks)
        }

    // ------------------------------------------- A-04 : validation du montant (CA-04, I-1)

    /** CA-04 : la sauvegarde est bloquée **et** l'erreur est rattachée au champ montant. */
    private fun assertAmountBlocksSave(
        rawAmount: String,
        label: String,
        expectedError: Int,
    ) = runTest(testDispatcher) {
        val sut = createSut(type = TransactionType.EXPENSE)
        advanceUntilIdle()

        sut.setTitle("Courses")
        sut.setCategory(expenseCategory.id)
        sut.setAmountRaw(rawAmount)

        sut.save(onDone)
        advanceUntilIdle()

        assertEquals(
            "CA-04/I-1 : montant $label — le montant retenu par le formulaire",
            rawAmount, sut.form.value.amountInput
        )
        verifyNoWrite()
        assertEquals(
            "CA-04/I-1 : montant $label — aucune sauvegarde ne doit aboutir, onDone ne doit pas être invoqué",
            emptyList<Long>(), doneIds
        )
        assertEquals(
            "CA-04 : montant $label — l'erreur doit porter sur le champ montant, et sur lui seul",
            setOf(TransactionFormField.AMOUNT), sut.fieldErrors.value.keys
        )
        assertEquals(
            "CA-04 : montant $label — message attendu sur le champ montant",
            expectedError, sut.fieldErrors.value[TransactionFormField.AMOUNT]
        )
        confirmVerified(*allMocks)
    }

    /**
     * Garde anti-oracle vacant : si les deux `@StringRes` de montant valaient le même entier
     * (cas classique d'un R non résolu en test unitaire), les oracles d'A-04a et d'A-04b
     * passeraient sans rien discriminer.
     */
    @Test
    fun `A-04 - Les deux messages d'erreur de montant sont des ressources distinctes`() {
        assertNotEquals(
            "CA-04 : « montant absent » et « montant non positif » doivent être deux messages distincts",
            R.string.tx_error_amount_required, R.string.tx_error_amount_positive
        )
    }

    @Test
    fun `A-04a - Given montant absent - When save - Then aucune ecriture et erreur sur le montant (CA-04, I-1)`() =
        assertAmountBlocksSave(
            rawAmount = "", label = "absent",
            expectedError = R.string.tx_error_amount_required
        )

    @Test
    fun `A-04b - Given montant nul - When save - Then aucune ecriture et erreur sur le montant (CA-04, I-1)`() =
        assertAmountBlocksSave(
            rawAmount = "0", label = "nul",
            expectedError = R.string.tx_error_amount_positive
        )

    @Test
    fun `A-04c - Given montant negatif - When save - Then aucune ecriture et erreur sur le montant (CA-04, I-1)`() =
        assertAmountBlocksSave(
            rawAmount = "-5", label = "négatif",
            expectedError = R.string.tx_error_amount_positive
        )

    // --------------------------------- A-05 : champs obligatoires manquants (CA-04)

    @Test
    fun `A-05a - Given montant valide et categoryId null - When save - Then aucune ecriture (CA-04)`() =
        runTest(testDispatcher) {
            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            // categoryId volontairement laissé à null

            sut.save(onDone)
            advanceUntilIdle()

            assertNull(
                "CA-04 : précondition du cas — aucune catégorie sélectionnée",
                sut.form.value.categoryId
            )
            verifyNoWrite()
            assertEquals(
                "CA-04 : catégorie manquante — onDone ne doit pas être invoqué",
                emptyList<Long>(), doneIds
            )
            assertEquals(
                "CA-04 : l'erreur doit porter sur la catégorie, et sur elle seule — le montant est valide",
                setOf(TransactionFormField.CATEGORY), sut.fieldErrors.value.keys
            )
            assertEquals(
                "CA-04 : message attendu près du champ catégorie",
                R.string.tx_error_category_required,
                sut.fieldErrors.value[TransactionFormField.CATEGORY]
            )
            confirmVerified(*allMocks)
        }

    @Test
    fun `A-05b - Given montant valide et accountId null - When save - Then aucune ecriture (CA-04)`() =
        runTest(testDispatcher) {
            // Aucun compte en base : rien à présélectionner, `form.accountId` reste null.
            every { accountRepo.observeAll() } returns flowOf(emptyList())

            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)

            sut.save(onDone)
            advanceUntilIdle()

            assertNull(
                "CA-04 : précondition du cas — aucun compte sélectionnable",
                sut.form.value.accountId
            )
            verifyNoWrite()
            assertEquals(
                "CA-04 : compte manquant — onDone ne doit pas être invoqué",
                emptyList<Long>(), doneIds
            )
            assertEquals(
                "CA-04 : l'erreur doit porter sur le compte, et sur lui seul — montant et catégorie sont valides",
                setOf(TransactionFormField.ACCOUNT), sut.fieldErrors.value.keys
            )
            assertEquals(
                "CA-04 : message attendu près du champ compte",
                R.string.tx_error_account_required,
                sut.fieldErrors.value[TransactionFormField.ACCOUNT]
            )
            confirmVerified(*allMocks)
        }

    // ------------------------------------------- A-06 : champs optionnels absents (CA-05)

    @Test
    fun `A-06 - Given formulaire valide sans note ni tags - When save - Then une seule creation et onDone (CA-05)`() =
        runTest(testDispatcher) {
            coEvery { accountRepo.getById(primaryAccount.id) } returns primaryAccount
            coEvery { createTransactionUseCase(any()) } returns newTransactionId

            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)
            // Ni setNote(...) ni toggleTag(...) : les optionnels ne doivent pas bloquer.
            val expectedDate = sut.form.value.date

            sut.save(onDone)
            advanceUntilIdle()

            val edition = slot<TransactionEdition>()
            coVerify(exactly = 1) {
                createTransactionUseCase(capture(edition))
            }
            val captured = edition.captured
            assertEquals("CA-05 : titre transmis tel que saisi", "Courses", captured.title)
            assertEquals("CA-05 : montant transmis tel que saisi", 42.50, captured.amount, 0.0)
            assertEquals("CA-05 : type transmis tel que saisi", TransactionType.EXPENSE, captured.type)
            assertEquals("CA-05 : date transmise telle que portée par le formulaire", expectedDate, captured.date)
            assertEquals("CA-05 : compte transmis tel que saisi", primaryAccount.id, captured.accountId)
            assertEquals("CA-05 : catégorie transmise telle que saisie", expenseCategory.id, captured.categoryId)
            assertEquals("CA-05 : statut transmis tel que saisi", TransactionStatus.PLANNED, captured.status)
            assertEquals(
                "CA-05 : création ponctuelle — aucune règle de récurrence",
                RecurrenceFrequency.NONE, captured.frequency
            )
            assertNull("CA-05 : note absente doit être transmise à null, sans bloquer", captured.note)
            assertEquals(
                "CA-05 : aucun tag sélectionné doit être transmis comme liste vide, sans bloquer",
                emptyList<Long>(), captured.tagIds
            )
            assertEquals(
                "CA-05 : onDone doit être invoqué exactement une fois avec l'id retourné par le use case",
                listOf(newTransactionId), doneIds
            )
            coVerify(exactly = 0) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) { accountRepo.getById(primaryAccount.id) }
            confirmVerified(*allMocks)
        }

    // ---------------------------------- A-07 : anti double-soumission (CA-10, I-2)

    @Test
    fun `A-07a - Given formulaire valide - When deux save consecutifs - Then une seule creation (CA-10, I-2)`() =
        runTest(testDispatcher) {
            coEvery { accountRepo.getById(primaryAccount.id) } returns primaryAccount
            coEvery { createTransactionUseCase(any()) } returns newTransactionId

            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)

            // Deux appuis rapides : aucune reprise du dispatcher entre les deux.
            sut.save(onDone)
            sut.save(onDone)
            advanceUntilIdle()

            assertFalse(
                "CA-10 : isSaving doit être retombé à false une fois la sauvegarde terminée",
                sut.isSaving.value
            )
            coVerify(exactly = 1) {
                createTransactionUseCase(any())
            }
            assertEquals(
                "I-2 : des appuis rapides répétés ne doivent produire qu'une seule écriture",
                listOf(newTransactionId), doneIds
            )
            coVerify { accountRepo.getById(primaryAccount.id) }
            confirmVerified(*allMocks)
        }

    @Test
    fun `A-07b - Given sauvegarde en vol - When second save pendant isSaving - Then une seule creation (CA-10, I-2)`() =
        runTest(testDispatcher) {
            val inFlight = CompletableDeferred<Unit>()
            coEvery { accountRepo.getById(primaryAccount.id) } returns primaryAccount
            coEvery { createTransactionUseCase(any()) } coAnswers {
                inFlight.await()
                newTransactionId
            }

            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)

            sut.save(onDone)
            advanceUntilIdle()

            assertTrue(
                "CA-10 : isSaving doit être true pendant l'appel au use case (indicateur de chargement)",
                sut.isSaving.value
            )

            // Second appui pendant que la première sauvegarde est en vol.
            sut.save(onDone)
            advanceUntilIdle()

            inFlight.complete(Unit)
            advanceUntilIdle()

            assertFalse(
                "CA-10 : isSaving doit retomber à false après l'appel",
                sut.isSaving.value
            )
            coVerify(exactly = 1) {
                createTransactionUseCase(any())
            }
            assertEquals(
                "I-2 : un second appui pendant isSaving ne doit produire aucune écriture supplémentaire",
                listOf(newTransactionId), doneIds
            )
            coVerify(exactly = 1) { accountRepo.getById(primaryAccount.id) }
            confirmVerified(*allMocks)
        }

    @Test
    fun `A-07c - Given use case en echec - When save - Then isSaving retombe et aucune seconde ecriture (CA-10, I-2)`() =
        runTest(testDispatcher) {
            coEvery { accountRepo.getById(primaryAccount.id) } returns primaryAccount
            coEvery { createTransactionUseCase(any()) } throws IllegalStateException("échec de sauvegarde")

            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)

            sut.save(onDone)
            advanceUntilIdle()

            assertFalse(
                "CA-10 : après un échec, isSaving doit retomber à false (sinon l'écran reste bloqué)",
                sut.isSaving.value
            )
            coVerify(exactly = 1) {
                createTransactionUseCase(any())
            }
            assertEquals(
                "I-2 : un échec ne doit pas produire de seconde écriture, ni invoquer onDone",
                emptyList<Long>(), doneIds
            )
            // CA-10 : l'échec affiche une erreur claire. Que ce test se termine prouve aussi
            // que l'exception ne s'échappe plus de viewModelScope.launch.
            assertEquals(
                "CA-10 : un échec de sauvegarde doit être signalé à l'utilisateur",
                R.string.tx_error_save_failed, sut.saveError.value
            )
            coVerify { accountRepo.getById(primaryAccount.id) }
            confirmVerified(*allMocks)
        }

    @Test
    fun `A-07d - Given sauvegarde annulee - When CancellationException - Then aucune erreur affichee (CA-10)`() =
        runTest(testDispatcher) {
            coEvery { accountRepo.getById(primaryAccount.id) } returns primaryAccount
            coEvery { createTransactionUseCase(any()) } throws CancellationException("scope annulé")

            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)

            sut.save(onDone)
            advanceUntilIdle()

            assertNull(
                "CA-10 : une annulation du scope n'est pas un échec de sauvegarde — " +
                    "aucune erreur ne doit être présentée à l'utilisateur",
                sut.saveError.value
            )
            assertFalse(
                "CA-10 : le verrou de sauvegarde doit être relâché même sur annulation",
                sut.isSaving.value
            )
            assertEquals(
                "CA-10 : une sauvegarde annulée n'aboutit pas, onDone ne doit pas être invoqué",
                emptyList<Long>(), doneIds
            )
            coVerify(exactly = 1) { createTransactionUseCase(any()) }
            coVerify { accountRepo.getById(primaryAccount.id) }
            confirmVerified(*allMocks)
        }

    // ------------------------------------- A-08 : aucune écriture sans save (CA-09, I-3)

    @Test
    fun `A-08 - Given saisie valide - When aucun save - Then aucune ecriture en base (CA-09, I-3)`() =
        runTest(testDispatcher) {
            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            sut.setAmountRaw("42.50")
            sut.setCategory(expenseCategory.id)
            sut.setNote("Une note")
            sut.setAccount(secondaryAccount.id)

            // Aucun appel à save() : quitter le formulaire ne doit rien écrire.

            assertTrue(
                "I-3 : précondition du cas — le formulaire est bien devenu valide et n'a pas été sauvegardé",
                sut.form.value.isValid
            )
            verifyNoWrite()
            confirmVerified(*allMocks)
        }

    // -------------------------------- A-09 : purge des erreurs à la correction (CA-04)

    @Test
    fun `A-09 - Given erreurs affichees - When le champ fautif est corrige - Then seule son erreur disparait (CA-04)`() =
        runTest(testDispatcher) {
            val sut = createSut(type = TransactionType.EXPENSE)
            advanceUntilIdle()

            sut.setTitle("Courses")
            // Ni montant ni catégorie : deux champs fautifs, le compte est présélectionné.
            sut.save(onDone)
            advanceUntilIdle()

            assertEquals(
                "CA-04 : les deux champs manquants doivent être signalés",
                setOf(TransactionFormField.AMOUNT, TransactionFormField.CATEGORY),
                sut.fieldErrors.value.keys
            )

            sut.setAmountRaw("42.50")
            assertEquals(
                "CA-04 : corriger le montant ne doit lever que l'erreur du montant",
                setOf(TransactionFormField.CATEGORY), sut.fieldErrors.value.keys
            )

            sut.setCategory(expenseCategory.id)
            assertEquals(
                "CA-04 : le dernier champ corrigé lève la dernière erreur",
                emptyMap<TransactionFormField, Int>(), sut.fieldErrors.value
            )

            verifyNoWrite()
            assertEquals(
                "CA-04 : corriger les champs n'écrit rien tant que save() n'est pas rappelé",
                emptyList<Long>(), doneIds
            )
            confirmVerified(*allMocks)
        }
}
