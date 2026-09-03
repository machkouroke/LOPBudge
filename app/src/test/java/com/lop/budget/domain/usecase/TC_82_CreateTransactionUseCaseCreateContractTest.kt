package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TC-82 — Contrat d'ÉCRITURE DE CRÉATION de `CreateTransactionUseCase` : ponctuel vs récurrent.
 *
 * ## Niveau et chaîne réellement exercée
 * Unitaire JUnit, mocks **stricts** MockK (aucun `relaxed`), `confirmVerified` dans chaque test.
 * SUT : `CreateTransactionUseCase.invoke(TransactionEdition)` **uniquement**.
 * Collaborateurs doublés : `TransactionRepository`, `SaveTransactionUseCase` (les deux seuls
 * paramètres du constructeur de production).
 * Ce ticket fige **quels appels** sont émis et **avec quels arguments** ; il ne prouve aucune
 * ligne en base (un mock ne voit pas les INSERT).
 *
 * ## Traçabilité — US LOP-2 « Création de transaction : formulaire d'ajout + écriture en base »
 * | Cas   | CA / invariant   | Fonction de production exercée                                    |
 * |-------|------------------|-------------------------------------------------------------------|
 * | C-01  | I-4, I-2, CA-06  | `CreateTransactionUseCase` branche `frequency == NONE`             |
 * | C-02  | I-4, CA-07, CA-05| `CreateTransactionUseCase` branche `frequency != NONE`             |
 * | C-03  | I-5, CA-08       | `toTransactionEntity(status = edition.status ?: PLANNED)`          |
 * | C-04  | I-5, CA-08       | idem, statut PLANNED                                               |
 * | C-05a | CA-05            | `saveSimple(entity, edition.tagIds)` avec tags + note              |
 * | C-05b | CA-05            | `saveSimple(entity, emptyList())` sans optionnel                   |
 *
 * ## Écarts documentés entre le ticket TC-82 et le code réel (ticket à amender)
 * - Le ticket nomme `upsertSeries` : le point d'écriture réel de la branche récurrente est
 *   **`TransactionRepository.saveSeriesWithTags`**. `upsertSeries` existe aussi sur le repository
 *   (délégation `RecurringSeriesOperations`) et est ici asserté **jamais appelé** — preuve qu'une
 *   sauvegarde produit une écriture et une seule (I-2).
 * - Le ticket dit « Retour = id de la série » : le code retourne
 *   `RecurrenceEngine.calculateVirtualId(seriesId, edition.date)`, l'**id virtuel de l'occurrence
 *   d'ancrage** (négatif par construction). C'est cohérent avec I-4/CA-07 — aucune ligne n'est
 *   créée, donc aucun id de transaction réel ne peut être retourné. L'oracle retenu asserte cet
 *   id virtuel ; le libellé du ticket est imprécis et doit être corrigé.
 *
 * ## ANO
 * Aucune ANO ouverte par ce ticket. L'ANO historique I-4/CA-07 (matérialisation d'une occurrence
 * dès la création) est **déjà corrigée** en production : C-02 est donc attendu **vert**. Si une
 * régression la ramenait, l'oracle de C-02 reste inchangé et l'ANO est rouverte — il ne doit
 * jamais être affaibli.
 *
 * ## Hors périmètre explicite
 * - Lignes réellement écrites en base et liste fusionnée (occurrences virtuelles) → **TC-83**
 *   (Room in-memory, sans mock). Un mock ne voit pas les INSERT : ne rien prouver ici par proxy.
 * - Horodatage effectif de `paidAt` (règle de cohérence I-5 appliquée par `saveSimple`) →
 *   TC-77 W-04 / TC-83 R-03. Ici `saveSimple` est mocké : l'oracle I-5 est pris **au boundary du
 *   SUT** (le statut correct lui est-il fourni ?), comme l'impose le ticket.
 * - `status = null` → défaut PLANNED : hors matrice TC-82, couvert par TC-77 W-02.
 * - I-1 (montant), I-3 (aucune écriture avant validation), I-6 (toggle payé masqué en récurrent),
 *   formulaire, `isSaving`, navigation, portées d'édition : niveaux ViewModel / E2E.
 * - `SaveTransactionUseCase.saveSimple` en SUT → TC-77, qui ne doit pas être retouché.
 */
class TC_82_CreateTransactionUseCaseCreateContractTest {

    // --- Mocks stricts : tout appel non prévu fait échouer le test ---
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val saveTransactionUseCase = mockk<SaveTransactionUseCase>(relaxed = false)

    private val sut = CreateTransactionUseCase(transactionRepo, saveTransactionUseCase)

    private fun allMocks() = arrayOf(transactionRepo, saveTransactionUseCase)

    // --- JDD (ticket TC-82) : constantes locales, aucune horloge, aucun fixture partagé ---
    private val editionDate = 1_700_000_000_000L
    private val seriesEndDate = 1_800_000_000_000L
    private val accountId = 1L
    private val categoryId = 10L
    private val tagId = 11L

    /** Id de série renvoyé par le double du repository en branche récurrente. */
    private val stubbedSeriesId = 55L

    /** Id de transaction renvoyé par le double de `saveSimple` en branche ponctuelle. */
    private val stubbedTxId = 77L

    /**
     * Édition discriminante du ticket, **identique d'un cas à l'autre sauf le champ sous test**.
     * `linkedGoalId` / `linkedDebtId` restent nuls : les recalculs objectif/dette appartiennent à
     * `saveSimple` (mocké ici), les valoriser n'ajouterait aucune preuve à ce niveau.
     */
    private fun edition(
        status: TransactionStatus? = TransactionStatus.PAID,
        frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        interval: Int = 1,
        endDate: Long? = null,
        note: String? = "note-opt",
        tagIds: List<Long> = listOf(tagId),
    ) = TransactionEdition(
        title = "TC-create",
        amount = 42.5,
        type = TransactionType.EXPENSE,
        date = editionDate,
        accountId = accountId,
        categoryId = categoryId,
        note = note,
        status = status,
        frequency = frequency,
        interval = interval,
        daysOfWeek = emptySet(),
        endDate = endDate,
        maxOccurrences = null,
        linkedGoalId = null,
        linkedDebtId = null,
        tagIds = tagIds,
    )

    /**
     * Entité ponctuelle attendue, écrite **champ par champ** (et non dérivée du mapper de
     * production, qui testerait le code par lui-même). CA-06 : type, montant, libellé, date,
     * catégorie, compte et statut tels que saisis ; aucun lien série.
     */
    private fun expectedPunctualEntity(
        status: TransactionStatus,
        note: String? = "note-opt",
    ) = TransactionEntity(
        id = 0L,
        title = "TC-create",
        amount = 42.5,
        type = TransactionType.EXPENSE,
        status = status,
        kind = TransactionKind.STANDARD,
        date = editionDate,
        accountId = accountId,
        categoryId = categoryId,
        note = note,
        // I-5 : la cohérence PAID -> horodatage est appliquée par saveSimple, pas par le SUT.
        paidAt = null,
        seriesId = null,
        seriesDate = null,
        isException = false,
        linkedGoalId = null,
        linkedDebtId = null,
        deleted = false,
    )

    // ------------------------------- C-01 : branche ponctuelle -------------------------------

    /**
     * C-01 — Given une édition `frequency = NONE`, When on crée, Then une unique délégation à
     * `saveSimple` avec une entité sans lien série ; aucune écriture de série, aucune
     * matérialisation. (I-4, I-2, CA-06)
     */
    @Test
    fun `C-01 - Given frequency NONE - When invoke - Then saveSimple une fois et aucune serie`() =
        runTest {
            val ed = edition(frequency = RecurrenceFrequency.NONE)
            val entitySlot = slot<TransactionEntity>()
            coEvery {
                saveTransactionUseCase.saveSimple(capture(entitySlot), listOf(tagId))
            } returns stubbedTxId

            val result = sut(ed)

            assertEquals(
                "CA-06 : le retour de la création ponctuelle est l'id rendu par saveSimple",
                stubbedTxId,
                result,
            )
            assertEquals(
                "CA-06 / I-4 : l'entité construite par CreateTransactionUseCase doit reprendre " +
                    "l'édition telle que saisie, sans seriesId/seriesDate et sans isException",
                expectedPunctualEntity(TransactionStatus.PAID),
                entitySlot.captured,
            )
            // I-2 : une sauvegarde = exactement une écriture.
            coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(any(), any()) }
            coVerify(exactly = 0) { transactionRepo.saveSeriesWithTags(any(), any()) }
            coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
            // I-4 : aucune matérialisation d'occurrence à la création.
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            confirmVerified(*allMocks())
        }

    // ------------------------------- C-02 : branche récurrente -------------------------------

    /**
     * C-02 — Given une édition `frequency = MONTHLY`, `interval = 1`, `endDate` non null,
     * When on crée, Then une unique série portant la règle est écrite (ancrage = date du
     * formulaire), **aucune occurrence n'est matérialisée** et aucune transaction n'est écrite.
     * (I-4 / CA-07, CA-05 pour les tags portés par la série)
     */
    @Test
    fun `C-02 - Given frequency MONTHLY - When invoke - Then serie seule et aucune materialisation`() =
        runTest {
            val ed = edition(
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                endDate = seriesEndDate,
            )
            val seriesSlot = slot<RecurringSeriesEntity>()
            coEvery {
                transactionRepo.saveSeriesWithTags(capture(seriesSlot), listOf(tagId))
            } returns stubbedSeriesId

            val result = sut(ed)

            // CA-07 : la série porte la règle saisie, ancrée sur la date du formulaire.
            val expectedSeries = RecurringSeriesEntity(
                id = 0L,
                title = "TC-create",
                amount = 42.5,
                type = TransactionType.EXPENSE,
                categoryId = categoryId,
                accountId = accountId,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = editionDate,
                endDate = seriesEndDate,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = "note-opt",
                linkedGoalId = null,
                linkedDebtId = null,
            )
            assertEquals(
                "CA-07 : la série écrite doit porter la règle saisie, startDate = edition.date " +
                    "et isCancelled = false",
                expectedSeries,
                seriesSlot.captured,
            )
            // CA-05 : les rattachements sélectionnés suivent la série, liste exacte.
            coVerify(exactly = 1) { transactionRepo.saveSeriesWithTags(any(), listOf(tagId)) }
            // I-4 / CA-07 : AUCUNE occurrence matérialisée à la création. Oracle intangible :
            // s'il devient rouge, ouvrir une ANO sur LOP-2 — ne jamais l'assouplir.
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            // I-4 : la création récurrente n'écrit aucune TransactionEntity.
            coVerify(exactly = 0) { saveTransactionUseCase.saveSimple(any(), any()) }
            // I-2 : une seule écriture — pas d'upsert de série en plus de saveSeriesWithTags.
            coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
            // Retour = id virtuel de l'occurrence d'ancrage (cf. écart documenté en en-tête).
            // On le compare à RecurrenceEngine, référence commune avec la liste fusionnée, plutôt
            // qu'à une valeur en dur : la propriété à figer est « c'est l'id que la liste
            // attribuera à ce créneau », pas la formule de hachage.
            assertEquals(
                "I-4 : aucune ligne n'étant créée, le retour doit être l'id virtuel de " +
                    "l'occurrence d'ancrage (series $stubbedSeriesId, date $editionDate)",
                RecurrenceEngine.calculateVirtualId(stubbedSeriesId, editionDate),
                result,
            )
            assertTrue(
                "I-4 : un id d'occurrence virtuelle est négatif par construction, il ne désigne " +
                    "aucune ligne persistée (retour observé : $result)",
                result < 0L,
            )
            confirmVerified(*allMocks())
        }

    // --------------------------------- C-03 / C-04 : statut ---------------------------------

    /**
     * C-03 — Given un ponctuel `status = PAID`, When on crée, Then le statut PAID est fourni à
     * `saveSimple` sans `paidAt` pré-rempli : la règle de cohérence I-5 est déléguée en aval.
     * (I-5 / CA-08 — l'horodatage effectif est prouvé par TC-77 W-04 et TC-83 R-03)
     */
    @Test
    fun `C-03 - Given ponctuel PAID - When invoke - Then statut PAID transmis a saveSimple`() =
        runTest {
            val ed = edition(status = TransactionStatus.PAID)
            val entitySlot = slot<TransactionEntity>()
            coEvery {
                saveTransactionUseCase.saveSimple(capture(entitySlot), listOf(tagId))
            } returns stubbedTxId

            sut(ed)

            val captured = entitySlot.captured
            assertEquals(
                "CA-08 : le statut choisi à la création doit être transmis tel quel à saveSimple",
                TransactionStatus.PAID,
                captured.status,
            )
            assertNull(
                "I-5 : CreateTransactionUseCase ne pré-remplit pas paidAt ; l'horodatage est la " +
                    "responsabilité de SaveTransactionUseCase.saveSimple (mocké ici)",
                captured.paidAt,
            )
            assertEquals(
                "CA-06 : hors statut, l'entité transmise reste l'édition telle que saisie",
                expectedPunctualEntity(TransactionStatus.PAID),
                captured,
            )
            coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(any(), any()) }
            confirmVerified(*allMocks())
        }

    /**
     * C-04 — Given un ponctuel `status = PLANNED`, When on crée, Then le statut PLANNED est
     * transmis et aucune date de paiement n'est portée par l'entité construite. (I-5 / CA-08)
     */
    @Test
    fun `C-04 - Given ponctuel PLANNED - When invoke - Then statut PLANNED transmis sans paidAt`() =
        runTest {
            val ed = edition(status = TransactionStatus.PLANNED)
            val entitySlot = slot<TransactionEntity>()
            coEvery {
                saveTransactionUseCase.saveSimple(capture(entitySlot), listOf(tagId))
            } returns stubbedTxId

            sut(ed)

            val captured = entitySlot.captured
            assertEquals(
                "CA-08 : le statut PLANNED choisi à la création doit être transmis tel quel",
                TransactionStatus.PLANNED,
                captured.status,
            )
            assertNull(
                "I-5 : statut Planifié => paidAt null sur l'entité construite par le SUT",
                captured.paidAt,
            )
            assertEquals(
                "CA-06 : hors statut, l'entité transmise reste l'édition telle que saisie",
                expectedPunctualEntity(TransactionStatus.PLANNED),
                captured,
            )
            coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(any(), any()) }
            confirmVerified(*allMocks())
        }

    // ------------------------------ C-05 : champs optionnels ------------------------------

    /**
     * C-05a — Given un ponctuel avec `tagIds` et `note` renseignés, When on crée, Then la liste
     * de tags exacte et la note sont transmises à `saveSimple`. (CA-05)
     */
    @Test
    fun `C-05a - Given ponctuel avec tags et note - When invoke - Then tags et note transmis`() =
        runTest {
            val ed = edition(note = "note-opt", tagIds = listOf(tagId))
            val entitySlot = slot<TransactionEntity>()
            coEvery {
                saveTransactionUseCase.saveSimple(capture(entitySlot), listOf(tagId))
            } returns stubbedTxId

            val result = sut(ed)

            assertEquals(stubbedTxId, result)
            assertEquals(
                "CA-05 : la note saisie doit être persistée avec la transaction",
                "note-opt",
                entitySlot.captured.note,
            )
            // Liste de tags EXACTE (pas de matcher permissif) : les rattachements sélectionnés
            // sont transmis à la sauvegarde, ni plus ni moins.
            coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(any(), listOf(tagId)) }
            confirmVerified(*allMocks())
        }

    /**
     * C-05b — Given un ponctuel sans tag ni note, When on crée, Then la sauvegarde aboutit avec
     * une liste de tags vide : les champs optionnels ne bloquent jamais la sauvegarde. (CA-05)
     *
     * L'ordre interne `clearTags` / ré-insertion des cross-refs n'est pas observable ici
     * (`saveSimple` est mocké, la chaîne tags est interne au repository) : il est couvert par
     * TC-83 R-05.
     */
    @Test
    fun `C-05b - Given ponctuel sans tag ni note - When invoke - Then liste vide et aucun echec`() =
        runTest {
            val ed = edition(note = null, tagIds = emptyList())
            val entitySlot = slot<TransactionEntity>()
            coEvery {
                saveTransactionUseCase.saveSimple(capture(entitySlot), emptyList())
            } returns stubbedTxId

            val result = sut(ed)

            assertEquals(
                "CA-05 : l'absence de champs optionnels ne bloque pas la sauvegarde",
                stubbedTxId,
                result,
            )
            assertEquals(
                "CA-05 : hors optionnels, l'entité transmise reste l'édition telle que saisie",
                expectedPunctualEntity(TransactionStatus.PAID, note = null),
                entitySlot.captured,
            )
            coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(any(), emptyList()) }
            confirmVerified(*allMocks())
        }
}
