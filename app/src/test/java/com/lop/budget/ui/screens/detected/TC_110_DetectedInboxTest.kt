package com.lop.budget.ui.screens.detected

import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.ProposalStatus
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.buildEdition
import com.lop.budget.domain.usecase.detection.InboxSettings
import com.lop.budget.domain.usecase.detection.MergeResult
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.detection.RefuseProposalUseCase
import com.lop.budget.domain.usecase.transaction.SaveResult
import com.lop.budget.domain.usecase.transaction.SaveTransactionFromProposalUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * TC-110 — Boîte de réception : acceptation, abandon de l'édition, refus (US LOP-54).
 *
 * ## Niveau et chaîne réellement exercée
 *
 * Test **unitaire JVM** : ni Room, ni Robolectric, ni ressource Android. Deux systèmes testés,
 * parce que la fiche couvre deux natures de code :
 *
 * - **ViewModel réel** [DetectedTransactionsViewModel] + `StandardTestDispatcher` sur
 *   `Dispatchers.Main`. Chaîne exercée : action utilisateur → ViewModel → frontières doublées.
 * - **Fonctions pures** [buildEdition] et [shouldWarnOnExit], appelées sans aucun montage.
 *
 * Doublures et justifications :
 *
 * | Dépendance                          | Traitement                          | Pourquoi |
 * |-------------------------------------|-------------------------------------|----------|
 * | `SaveTransactionFromProposalUseCase`| mock strict MockK, arguments capturés | Frontière d'écriture : c'est sur elle que portent tous les « zéro appel » |
 * | `CategoryRepository`                | mock strict                          | Frontière, fournit la catégorie par défaut |
 * | `InboxSettings`                     | mock strict                          | Frontière, pilote R-OK / R-SANSCOMPTE |
 * | `ProposalRepository`                | [FakeProposalRepository] écrite à la main | Un mock ne peut pas *vider* la liste sur `refuse` ; sans état réel, T-08 n'a plus d'oracle |
 * | `RefuseProposalUseCase`             | **vraie instance** sur la doublure   | Délégateur d'une ligne : permet d'asserter l'appel **et** l'état final |
 *
 * Déviation assumée de la lettre de la fiche (« use cases doublés ») pour le seul use case de
 * refus : le doubler rendrait inobservable « la liste devient explicitement vide » exigé par T-08.
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 *
 * | Cas   | CA / invariant        | Fonction de production                          |
 * |-------|-----------------------|-------------------------------------------------|
 * | T-01  | CA-16, I-1            | `DetectedTransactionsViewModel.onAccept`         |
 * | T-01b | CA-16 (assertions obligatoires) | `onAccept`, branche « proposition absente » |
 * | T-02a | CA-16, I-3, I-8, P-4  | `buildEdition` (catégorie suggérée)              |
 * | T-02b | CA-16, I-8, P-4       | `buildEdition` (repli sur la catégorie par défaut) |
 * | T-03  | CA-17, I-9            | `shouldWarnOnExit`                               |
 * | T-04  | CA-18, I-1, I-9       | `onAccept` répété                                |
 * | T-05  | CA-20, I-8            | `onAccept` + `InboxSettings.defaultAccountIdOnce`|
 * | T-06  | CA-21, I-3            | `onAccept` sur un montant nul                    |
 * | T-08  | CA-19, I-6, I-9       | `onRefuse` → `RefuseProposalUseCase`             |
 *
 * ## Rouges attendus — écarts du code courant, à ouvrir en anomalie
 *
 * - **E-5 / P-3** — `onAccept` crée la transaction **avant** d'ouvrir l'édition. T-01, T-04 et T-06
 *   tombent rouges : accepter doit n'écrire strictement rien.
 * - **E-3 / P-5** — faute de réglage « compte par défaut », le ViewModel se replie sur le compte
 *   codé en dur `1L`. T-05 tombe rouge : CA-20 exige un refus avec message et I-8 interdit tout
 *   identifiant choisi par le code.
 * - **P-4** — le statut posé par `buildEdition` est `PLANNED` alors que le paiement a déjà eu lieu.
 *   T-02a et T-02b tombent rouges sur ce seul champ.
 *
 * Les oracles ne sont **pas** assouplis pour absorber ces écarts : c'est leur raison d'être.
 *
 * ## Oracle déporté, assumé
 *
 * « La proposition est marquée ignorée après acceptation » n'est pas observable ici : l'appel à
 * `refuse` a lieu **dans** `SaveTransactionFromProposalUseCase`, qui est doublé à la frontière du
 * ViewModel. Ce que ce fichier prouve, c'est qu'aucun enregistrement n'est déclenché du tout ; que
 * la ligne change de statut en base est porté par la fiche d'intégration.
 *
 * ## Hors périmètre — ce que ce fichier ne vérifie pas
 *
 * - **T-07 (anti double-soumission)** : il n'existe dans ce ViewModel ni méthode d'enregistrement
 *   ni verrou. Les deux vivent dans `TransactionEditViewModel.save` / `tryAcquireSaveLock` et sont
 *   déjà couverts par **TC-80, cas A-07a/b/c**.
 * - **États de chargement / d'erreur / de verrou exposés** : le ViewModel n'expose que `pending` et
 *   `effects`. Les assertions « champ par champ, chargement compris » de la fiche n'ont pas de
 *   cible ; l'API reste à livrer par P-10 et n'est **pas** inventée ici pour le confort du test.
 * - **Affichage du dialogue de confirmation et compteur d'en-tête** (CA-15, part interface de
 *   CA-17) : aucun écran n'est monté. Seule la décision d'avertir est testée, par T-03.
 * - **Effets en base et atomicité de l'enregistrement** : portés par la fiche d'intégration.
 * - **Analyse du texte de notification et décision de détection** : portées par leurs fiches ;
 *   ici les propositions sont fabriquées à la main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectedInboxTest {

    // ------------------------------------------------------------------ Jeu de données (JDD)
    //
    // Identifiants volontairement éloignés de 0 et de 1 : aucune valeur ne doit pouvoir coïncider
    // par hasard avec un repli du code. CPT_1 ≠ COMPTE_CODE_EN_DUR rend E-3 visible (T-05), et
    // CAT_SUGGEREE ≠ CAT_DEF prouve laquelle des deux sources la production a réellement utilisée
    // (T-02a vs T-02b).

    private val zoneParis: ZoneId = ZoneId.of("Europe/Paris")

    /** Horodatage de détection, fixe et nommé : aucun `now()` n'entre dans un scénario (I-11). */
    private val detectedAtT0: Long =
        LocalDateTime.of(2026, 3, 14, 21, 0).atZone(zoneParis).toInstant().toEpochMilli()

    private val pOkId = 4001L
    private val pSansCatId = 4002L
    private val pIllisibleId = 4003L

    private val catSuggeree = 5001L
    private val catDefaut = 9001L
    private val cpt1 = 7001L

    /** Le repli codé en dur du ViewModel (ÉCART E-3), cité pour que T-05 puisse le distinguer. */
    private val compteCodeEnDur = 1L

    private val createdTxId = 6001L
    private val sourcePackage = "com.google.android.apps.walletnfcrel"

    private val pOk = Proposal(
        id = pOkId,
        sourcePackage = sourcePackage,
        amountCents = 1250L,
        currency = "EUR",
        label = "Carrefour",
        fullText = "Carrefour 12,50 €",
        cardName = "Visa ••1234",
        detectedAt = detectedAtT0,
        dedupeKey = "walletnfcrel|1250|EUR|carrefour",
        status = ProposalStatus.PENDING,
        confidence = 1.0f,
        suggestedCategoryId = catSuggeree,
    )

    private val pSansCat = pOk.copy(
        id = pSansCatId,
        suggestedCategoryId = null,
    )

    /**
     * P-ILLISIBLE, tel qu'il est représentable ici.
     *
     * La fiche décrit « un montant absent ou non convertible en centimes ». `Proposal.amountCents`
     * est un `Long` non-nullable : au niveau ViewModel, le seul état observable de ce défaut est le
     * **repli sur zéro** — précisément ce que CA-21 et I-3 interdisent de laisser passer.
     */
    private val pIllisible = pOk.copy(
        id = pIllisibleId,
        amountCents = 0L,
        label = "Paiement",
        fullText = "Paiement effectué",
    )

    // ------------------------------------------------------------------ Montage technique

    private val dispatcher = StandardTestDispatcher()

    private lateinit var saveTransactionFromProposal: SaveTransactionFromProposalUseCase
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var settings: InboxSettings

    /** Identifiants passés à l'enregistrement, dans l'ordre reçu. Vide = aucune écriture demandée. */
    private val enregistrementsProposalIds = mutableListOf<Long>()

    /** Pré-remplissages passés à l'enregistrement, capturés pour être assertés (jamais masqués). */
    private val enregistrementsEditions = mutableListOf<TransactionEdition>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        saveTransactionFromProposal = mockk()
        categoryRepo = mockk()
        settings = mockk()

        // Le stub de l'enregistrement n'existe que pour qu'un appel *illégitime* soit observé au
        // lieu de faire échouer MockK sur un « no answer found » : un mock non configuré n'est pas
        // une preuve RED métier (AGENTS test §9). Les arguments ne sont pas masqués par `any()`,
        // ils sont capturés, puis assertés cas par cas.
        coEvery {
            saveTransactionFromProposal(
                capture(enregistrementsProposalIds),
                capture(enregistrementsEditions),
            )
        } returns SaveResult.Created(createdTxId)

        coEvery { categoryRepo.getDefaultExpenseCategoryId() } returns catDefaut
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Doublure de stockage des propositions, écrite à la main.
     *
     * Elle porte un **vrai** état : `refuse` retire réellement la ligne du flux observé. C'est ce
     * qui permet à T-08 d'asserter « la liste devient explicitement vide » au lieu de se contenter
     * de compter un appel, et à T-01 / T-04 de constater qu'aucune proposition n'a disparu.
     */
    private class FakeProposalRepository(initial: List<Proposal>) : ProposalRepository {

        private val state = MutableStateFlow(initial)

        /** Refus réellement reçus, dans l'ordre. Sert d'oracle d'appel autant que d'oracle d'état. */
        val refusedIds = mutableListOf<Long>()

        override fun observePending(): Flow<List<Proposal>> = state

        override suspend fun upsertOrMerge(
            proposal: Proposal,
            windowMillis: Long,
            nowMillis: Long,
        ): MergeResult = throw UnsupportedOperationException(
            "Hors périmètre de TC-110 : l'écriture d'une proposition est portée par la fiche de détection."
        )

        override suspend fun refuse(proposalId: Long) {
            refusedIds += proposalId
            state.value = state.value.filterNot { it.id == proposalId }
        }
    }

    /**
     * Monte le ViewModel réel et **lance les collectes avant toute action**.
     *
     * `pending` est publié en `WhileSubscribed` : sans abonné, il reste à la liste vide et
     * `onAccept` sortirait silencieusement, rendant tous les oracles verts pour la mauvaise raison.
     * Les effets sont collectés en `UNDISPATCHED` pour qu'aucune émission ne soit manquée.
     */
    private fun TestScope.monter(
        propositions: List<Proposal>,
        compteParDefaut: Long?,
    ): Harnais {
        val repo = FakeProposalRepository(propositions)
        coEvery { settings.defaultAccountIdOnce() } returns compteParDefaut

        val vm = DetectedTransactionsViewModel(
            proposals = repo,
            saveTransactionFromProposal = saveTransactionFromProposal,
            refuseProposal = RefuseProposalUseCase(repo),
            categoryRepo = categoryRepo,
            settings = settings,
        )

        val effets = mutableListOf<InboxEffect>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.effects.collect { effets += it }
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.pending.collect { }
        }
        stabiliser()

        return Harnais(vm, repo, effets)
    }

    /**
     * Fait tourner l'ordonnanceur jusqu'à épuisement, **collectes de fond comprises**.
     *
     * `advanceUntilIdle()` seul ne suffit pas : il n'exécute pas les coroutines de `backgroundScope`
     * — c'est ce qui protège la suite d'une collecte de fond sans fin. Sans le `runCurrent()`, les
     * effets émis par le ViewModel ne sont jamais remis au collecteur et **tous** les oracles
     * d'effets deviennent verts pour la mauvaise raison. Vérifié par sonde le 14 septembre 2026.
     */
    private fun TestScope.stabiliser() {
        advanceUntilIdle()
        runCurrent()
    }

    private class Harnais(
        val vm: DetectedTransactionsViewModel,
        val repo: FakeProposalRepository,
        val effets: List<InboxEffect>,
    )

    /**
     * Contexte joint à chaque message d'échec : dernier état exposé et liste des appels reçus,
     * pour qu'un rouge se diagnostique sans ouvrir le fichier.
     */
    private fun Harnais.diagnostic(): String = buildString {
        append("\n  état exposé (pending) = ")
        append(vm.pending.value.map { "#${it.id}/${it.status}/${it.amountCents}c" })
        append("\n  effets émis = ").append(effets)
        append("\n  enregistrements reçus = ").append(enregistrementsProposalIds)
        append("\n  éditions reçues = ").append(enregistrementsEditions)
        append("\n  refus reçus = ").append(repo.refusedIds)
    }

    /** Aucune écriture n'a été demandée, sous aucune forme. */
    private fun Harnais.assertAucunEnregistrement(exigence: String) {
        assertEquals(
            "$exigence : accepter ne doit déclencher aucun enregistrement." + diagnostic(),
            emptyList<Long>(),
            enregistrementsProposalIds.toList(),
        )
        // `any()` est admis ici parce que l'intention est « aucun appel, quels que soient les
        // arguments » ; il est borné par `confirmVerified` juste en dessous.
        coVerify(exactly = 0) { saveTransactionFromProposal(any(), any()) }
        confirmVerified(saveTransactionFromProposal)
    }

    // =================================================================================== T-01
    // Accepter ouvre l'édition et n'écrit rien (CA-16, I-1).

    @Test
    fun `T-01 - Given P-OK en attente et compte par defaut defini - When onAccept - Then un seul effet OpenEdition et aucun enregistrement (CA-16, I-1)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk), compteParDefaut = cpt1)

            h.vm.onAccept(pOkId)
            stabiliser()

            // 1. Exactement un effet, et c'est une ouverture d'édition portant l'id de P-OK.
            assertEquals(
                "CA-16 : accepter doit produire exactement un effet." + h.diagnostic(),
                1,
                h.effets.size,
            )
            val effet = h.effets.single()
            assertTrue(
                "CA-16 : l'effet d'une acceptation doit être une ouverture d'édition, reçu = $effet" + h.diagnostic(),
                effet is InboxEffect.OpenEdition,
            )
            assertEquals(
                "CA-16 : l'édition doit s'ouvrir sur la proposition acceptée." + h.diagnostic(),
                pOkId,
                (effet as InboxEffect.OpenEdition).proposalId,
            )
            // 2. Témoin direct de l'ÉCART E-5 : aucune transaction ne peut exister à ce stade,
            //    donc l'effet ne peut porter aucun identifiant de transaction créée.
            assertNull(
                "I-1 / P-3 : aucune transaction ne doit exister avant l'enregistrement du formulaire ; " +
                    "un identifiant de transaction dans l'effet prouve une écriture anticipée." + h.diagnostic(),
                effet.createdTransactionId,
            )

            // 3. Aucun appel d'écriture, aucun refus.
            h.assertAucunEnregistrement("CA-16 / I-1")
            assertEquals(
                "I-6 : accepter ne doit jamais faire passer la proposition par le refus." + h.diagnostic(),
                emptyList<Long>(),
                h.repo.refusedIds.toList(),
            )

            // 4. La proposition reste en attente, le compteur de non traitées est inchangé.
            assertEquals(
                "I-9 : la proposition ne quitte la boîte de réception ni par l'acceptation ni par l'abandon." + h.diagnostic(),
                1,
                h.vm.pending.value.size,
            )
            assertEquals(
                "I-9 : la proposition en attente doit être P-OK, inchangée." + h.diagnostic(),
                pOk,
                h.vm.pending.value.single(),
            )
            assertEquals(
                "I-9 : le statut de la proposition doit rester « en attente »." + h.diagnostic(),
                ProposalStatus.PENDING,
                h.vm.pending.value.single().status,
            )
        }

    @Test
    fun `T-01b - Given identifiant inconnu de la boite de reception - When onAccept - Then aucun effet et aucun enregistrement (CA-16)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk), compteParDefaut = cpt1)
            val identifiantAbsent = 4999L

            h.vm.onAccept(identifiantAbsent)
            stabiliser()

            assertEquals(
                "CA-16 : une proposition absente ne doit produire aucun effet." + h.diagnostic(),
                emptyList<InboxEffect>(),
                h.effets.toList(),
            )
            h.assertAucunEnregistrement("CA-16")
            assertEquals(
                "I-9 : une action sur un identifiant absent ne doit toucher aucune autre proposition." + h.diagnostic(),
                listOf(pOk),
                h.vm.pending.value,
            )
        }

    // =================================================================================== T-02
    // Pré-remplissage du formulaire : les huit champs, un à un (CA-16, I-3, I-8, P-4).

    @Test
    fun `T-02a - Given P-OK avec categorie suggeree et reglages R-OK - When buildEdition - Then les huit champs sont ceux de la proposition et des reglages (CA-16, I-8, P-4)`() {
        val edition = buildEdition(pOk, defaultAccountId = cpt1, defaultCategoryId = catDefaut)

        assertEquals("CA-16 : le libellé doit être celui de la proposition", "Carrefour", edition.title)
        assertEquals("CA-16 / I-3 : le montant doit être repris en centimes entiers", 1250L, edition.amount)
        assertEquals("CA-16 : une proposition de paiement est une dépense", TransactionType.EXPENSE, edition.type)
        assertEquals("CA-16 / I-11 : la date doit être l'horodatage de détection", detectedAtT0, edition.date)
        assertEquals(
            "CA-16 / I-8 : le compte doit venir des réglages, aucun identifiant choisi par le code " +
                "(le repli codé en dur vaut $compteCodeEnDur)",
            cpt1,
            edition.accountId,
        )
        assertEquals(
            "CA-16 : la catégorie suggérée doit primer sur la catégorie par défaut",
            catSuggeree,
            edition.categoryId,
        )
        assertNotNull("CA-16 : la note doit citer la source de la proposition", edition.note)
        assertTrue(
            "CA-16 : la note doit citer le paquet source, reçue = « ${edition.note} »",
            edition.note!!.contains(sourcePackage),
        )
        assertEquals(
            "CA-16 / P-4 : le paiement a déjà eu lieu au moment de la notification, la transaction " +
                "doit être créée au statut réglé",
            TransactionStatus.PAID,
            edition.status,
        )
    }

    @Test
    fun `T-02b - Given P-SANSCAT sans categorie suggeree - When buildEdition - Then la categorie par defaut des reglages est retenue (CA-16, I-8, P-4)`() {
        val edition = buildEdition(pSansCat, defaultAccountId = cpt1, defaultCategoryId = catDefaut)

        assertEquals("CA-16 : le libellé doit être celui de la proposition", "Carrefour", edition.title)
        assertEquals("CA-16 / I-3 : le montant doit être repris en centimes entiers", 1250L, edition.amount)
        assertEquals("CA-16 : une proposition de paiement est une dépense", TransactionType.EXPENSE, edition.type)
        assertEquals("CA-16 / I-11 : la date doit être l'horodatage de détection", detectedAtT0, edition.date)
        assertEquals(
            "CA-16 / I-8 : le compte doit venir des réglages, aucun identifiant choisi par le code " +
                "(le repli codé en dur vaut $compteCodeEnDur)",
            cpt1,
            edition.accountId,
        )
        assertEquals(
            "CA-16 / I-8 : sans catégorie suggérée, la catégorie par défaut des réglages doit être " +
                "retenue, et aucune valeur choisie par le code",
            catDefaut,
            edition.categoryId,
        )
        assertNotNull("CA-16 : la note doit citer la source de la proposition", edition.note)
        assertTrue(
            "CA-16 : la note doit citer le paquet source, reçue = « ${edition.note} »",
            edition.note!!.contains(sourcePackage),
        )
        assertEquals(
            "CA-16 / P-4 : le paiement a déjà eu lieu au moment de la notification, la transaction " +
                "doit être créée au statut réglé",
            TransactionStatus.PAID,
            edition.status,
        )
    }

    // =================================================================================== T-03
    // Avertissement de sortie : les quatre combinaisons (CA-17, I-9).
    //
    // Table de décision de `origin == PROPOSAL || isDirty` : chaque clause est rendue fausse
    // indépendamment de l'autre, et le cas où les deux sont fausses est le seul faux attendu.

    @Test
    fun `T-03a - Given edition venant d une proposition et formulaire vierge - When shouldWarnOnExit - Then avertir (CA-17, I-9)`() {
        assertTrue(
            "CA-17 / I-9 : venant d'une proposition, l'avertissement est dû même sans saisie, " +
                "parce que le message annonce que la proposition restera visible",
            shouldWarnOnExit(EditionOrigin.PROPOSAL, isDirty = false),
        )
    }

    @Test
    fun `T-03b - Given edition venant d une proposition avec saisies - When shouldWarnOnExit - Then avertir (CA-17)`() {
        assertTrue(
            "CA-17 : venant d'une proposition et avec des saisies, l'avertissement est dû",
            shouldWarnOnExit(EditionOrigin.PROPOSAL, isDirty = true),
        )
    }

    @Test
    fun `T-03c - Given edition normale avec saisies - When shouldWarnOnExit - Then avertir (CA-17)`() {
        assertTrue(
            "CA-17 : des saisies non enregistrées imposent l'avertissement, même hors proposition",
            shouldWarnOnExit(EditionOrigin.NORMAL, isDirty = true),
        )
    }

    @Test
    fun `T-03d - Given edition normale et formulaire vierge - When shouldWarnOnExit - Then ne pas avertir (CA-17)`() {
        assertTrue(
            "CA-17 : sans proposition et sans saisie, rien n'est perdu, l'avertissement serait du bruit",
            !shouldWarnOnExit(EditionOrigin.NORMAL, isDirty = false),
        )
    }

    // =================================================================================== T-04
    // Accepter puis abandonner, trois fois : rien ne s'accumule, rien ne disparaît (CA-18, I-9).

    @Test
    fun `T-04 - Given P-OK acceptee et abandonnee trois fois - When onAccept repete - Then aucun enregistrement cumule et pre-remplissage identique (CA-18, I-1, I-9)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk), compteParDefaut = cpt1)
            val preRemplissages = mutableListOf<TransactionEdition>()

            repeat(3) { cycle ->
                h.vm.onAccept(pOkId)
                stabiliser()

                // Après chaque cycle : aucune écriture cumulée, la proposition est toujours là.
                h.assertAucunEnregistrement("CA-18 / I-1 (cycle ${cycle + 1})")
                assertEquals(
                    "CA-18 / I-9 (cycle ${cycle + 1}) : la boîte de réception doit contenir exactement " +
                        "une proposition en attente." + h.diagnostic(),
                    1,
                    h.vm.pending.value.size,
                )
                assertEquals(
                    "CA-18 / I-9 (cycle ${cycle + 1}) : la proposition doit être inchangée, champ pour champ." + h.diagnostic(),
                    pOk,
                    h.vm.pending.value.single(),
                )
                assertEquals(
                    "I-6 (cycle ${cycle + 1}) : un abandon d'édition ne doit jamais valoir refus." + h.diagnostic(),
                    emptyList<Long>(),
                    h.repo.refusedIds.toList(),
                )

                // Le pré-remplissage est recalculé depuis l'état réellement exposé : si une saisie
                // abandonnée avait été conservée quelque part, c'est cette valeur qui dériverait.
                preRemplissages += buildEdition(
                    h.vm.pending.value.single(),
                    defaultAccountId = cpt1,
                    defaultCategoryId = catDefaut,
                )
            }

            assertEquals(
                "CA-18 : les trois cycles doivent avoir produit trois ouvertures d'édition." + h.diagnostic(),
                3,
                h.effets.size,
            )
            assertEquals(
                "CA-18 : chaque réouverture doit porter l'identifiant de la même proposition." + h.diagnostic(),
                listOf(pOkId, pOkId, pOkId),
                h.effets.map { (it as InboxEffect.OpenEdition).proposalId },
            )
            assertEquals(
                "CA-18 : le formulaire doit se rouvrir avec les valeurs d'origine de la proposition, " +
                    "pas celles saisies puis abandonnées." + h.diagnostic(),
                listOf(preRemplissages[0], preRemplissages[0], preRemplissages[0]),
                preRemplissages.toList(),
            )
        }

    // =================================================================================== T-05
    // Aucun compte de destination : refus explicite, aucun compte choisi par le code (CA-20, I-8).

    @Test
    fun `T-05 - Given aucun compte par defaut dans les reglages - When onAccept - Then erreur explicite et aucun enregistrement (CA-20, I-8)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk), compteParDefaut = null)

            h.vm.onAccept(pOkId)
            stabiliser()

            val erreurs = h.effets.filterIsInstance<InboxEffect.Error>()
            assertEquals(
                "CA-20 : sans compte de destination, l'acceptation doit être refusée avec exactement " +
                    "un message explicite." + h.diagnostic(),
                1,
                erreurs.size,
            )
            assertTrue(
                "CA-20 : le message de refus doit porter un identifiant de ressource non nul, " +
                    "reçu = ${erreurs.firstOrNull()?.messageRes}" + h.diagnostic(),
                erreurs.single().messageRes != 0,
            )

            h.assertAucunEnregistrement("CA-20 / I-8")

            // I-8 : aucun compte ne peut avoir été choisi par le code — ni le repli codé en dur,
            // ni aucun autre. La capture ci-dessus rendrait un tel identifiant visible.
            assertEquals(
                "I-8 : aucun identifiant de compte ne doit être choisi par le ViewModel " +
                    "(repli codé en dur attendu absent : $compteCodeEnDur)." + h.diagnostic(),
                emptyList<Long>(),
                enregistrementsEditions.map { it.accountId },
            )

            assertEquals(
                "CA-20 / I-9 : la proposition doit rester en attente et visible." + h.diagnostic(),
                listOf(pOk),
                h.vm.pending.value,
            )
        }

    // =================================================================================== T-06
    // Montant non convertible en centimes : jamais de transaction à zéro (CA-21, I-3).

    @Test
    fun `T-06 - Given proposition dont le montant n est pas convertible en centimes - When onAccept - Then refus explicite et aucun enregistrement a zero (CA-21, I-3)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pIllisible), compteParDefaut = cpt1)

            h.vm.onAccept(pIllisibleId)
            stabiliser()

            val erreurs = h.effets.filterIsInstance<InboxEffect.Error>()
            assertEquals(
                "CA-21 : un montant non convertible doit produire exactement un refus avec message." + h.diagnostic(),
                1,
                erreurs.size,
            )
            assertTrue(
                "CA-21 : le message de refus doit porter un identifiant de ressource non nul, " +
                    "reçu = ${erreurs.firstOrNull()?.messageRes}" + h.diagnostic(),
                erreurs.single().messageRes != 0,
            )

            h.assertAucunEnregistrement("CA-21 / I-3")

            // Interdit explicitement nommé par la fiche : un enregistrement portant zéro centime.
            assertEquals(
                "CA-21 / I-3 : aucune transaction de montant zéro ne doit être créée par ce chemin ; " +
                    "le repli silencieux sur zéro est interdit." + h.diagnostic(),
                emptyList<Long>(),
                enregistrementsEditions.map { it.amount },
            )

            assertEquals(
                "CA-21 / I-9 : la proposition refusée à l'enregistrement reste en attente et visible." + h.diagnostic(),
                listOf(pIllisible),
                h.vm.pending.value,
            )
        }

    // =================================================================================== T-08
    // Refus explicite : la proposition quitte la boîte de réception (CA-19, I-6, I-9).

    @Test
    fun `T-08 - Given P-OK en attente - When onRefuse - Then un seul refus sur P-OK, aucun enregistrement et liste vide (CA-19, I-6, I-9)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk), compteParDefaut = cpt1)

            h.vm.onRefuse(pOkId)
            stabiliser()

            assertEquals(
                "CA-19 : le refus doit être transmis exactement une fois, pour la proposition refusée." + h.diagnostic(),
                listOf(pOkId),
                h.repo.refusedIds.toList(),
            )
            h.assertAucunEnregistrement("CA-19 / I-1")
            assertEquals(
                "CA-19 / I-9 : la proposition refusée doit quitter la boîte de réception, qui devient vide." + h.diagnostic(),
                emptyList<Proposal>(),
                h.vm.pending.value,
            )
            assertEquals(
                "CA-19 : un refus ne doit produire aucun effet de navigation." + h.diagnostic(),
                emptyList<InboxEffect>(),
                h.effets.toList(),
            )
        }
}
