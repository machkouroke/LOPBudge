package com.lop.budget.ui.screens.detected

import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.ProposalStatus
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.buildEdition
import com.lop.budget.domain.usecase.detection.MergeResult
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.detection.RefuseProposalUseCase
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
 * | Dépendance              | Traitement                                | Pourquoi |
 * |-------------------------|-------------------------------------------|----------|
 * | `ProposalRepository`    | [FakeProposalRepository] écrite à la main | Un mock ne peut pas *vider* la liste sur `refuse` ; sans état réel, T-08 n'a plus d'oracle |
 * | `RefuseProposalUseCase` | **vraie instance** sur la doublure         | Délégateur d'une ligne : permet d'asserter l'appel **et** l'état final |
 *
 * Déviation assumée de la lettre de la fiche (« use cases doublés ») pour le seul use case de
 * refus : le doubler rendrait inobservable « la liste devient explicitement vide » exigé par T-08.
 *
 * ## Où est passé l'oracle « zéro appel d'enregistrement »
 *
 * La fiche demande de vérifier que l'acceptation n'appelle pas le use case d'enregistrement. Depuis
 * le correctif d'ANO-H, ce use case **n'est plus une dépendance** de ce ViewModel : l'acceptation
 * n'a plus aucun moyen d'écrire. L'invariant I-1 est donc tenu **par construction**, ce qui est plus
 * fort qu'une vérification a posteriori — un `coVerify(exactly = 0)` peut être contourné en ajoutant
 * une dépendance, pas l'absence de dépendance.
 *
 * Ce qui reste asserté ici, et qui resterait faux si quelqu'un réintroduisait une écriture : la
 * proposition n'est **ni confirmée ni refusée** par une acceptation. Une proposition n'est confirmée
 * que lorsqu'une transaction a réellement été créée (I-6), donc `confirmedIds` vide prouve qu'aucune
 * transaction n'est née de ce chemin.
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 *
 * | Cas   | CA / invariant        | Fonction de production                           |
 * |-------|-----------------------|--------------------------------------------------|
 * | T-01  | CA-16, I-1            | `DetectedTransactionsViewModel.onAccept`          |
 * | T-01b | CA-16 (assertions obligatoires) | `onAccept`, branche « proposition absente » |
 * | T-02a | CA-16, I-3, I-8, P-4  | `buildEdition` (catégorie suggérée)               |
 * | T-02b | CA-16, I-8, P-4       | `buildEdition` (repli sur la catégorie par défaut) |
 * | T-03  | CA-17, I-9            | `shouldWarnOnExit`                                |
 * | T-04  | CA-18, I-1, I-9       | `onAccept` répété                                 |
 * | T-06  | CA-21, I-3            | `onAccept` sur un montant nul                     |
 * | T-08  | CA-19, I-6, I-9       | `onRefuse` → `RefuseProposalUseCase`              |
 *
 * **T-05 a été retiré** — voir « Révision de CA-20 » plus bas. Ce n'est pas un oubli.
 *
 * ## Anomalies — ouvertes par ce ticket, corrigées le 15 septembre 2026
 *
 * - **ANO-H** — accepter créait la transaction avant d'ouvrir l'édition, si bien qu'un abandon
 *   laissait une transaction orpheline et faisait disparaître la proposition (T-01, T-04, T-06).
 *   Corrigé : accepter n'écrit rien, le formulaire s'ouvre depuis la proposition, et c'est son
 *   enregistrement qui crée la transaction et **confirme** la proposition (I-6, P-3).
 * - **ANO-I** — le compte `1L` était choisi par le code (T-05). Corrigé : plus aucun identifiant
 *   écrit en dur ; le formulaire choisit son compte comme pour n'importe quel ajout (I-8).
 * - **ANO-J** — le pré-remplissage posait le statut « prévu » (T-02). Corrigé : « réglé » (P-4).
 *
 * ## Révision de CA-20 — décision produit du 15 septembre 2026
 *
 * CA-20 et P-5 exigeaient de **refuser l'acceptation** tant qu'aucun « compte par défaut » n'était
 * défini dans les réglages. Ce comportement n'a jamais été voulu, et le réglage n'existe pas : rien
 * n'écrivait jamais `lastAccountId`, si bien que la garde bloquait **toutes** les acceptations.
 *
 * Décision retenue : le compte n'est pas une condition d'acceptation. Le formulaire le pré-remplit
 * comme pour un ajout normal et l'utilisateur le change librement. Le rattachement d'une carte à un
 * compte fera l'objet d'une EVOL ; c'est elle qui portera un vrai pré-remplissage par compte.
 *
 * Conséquence sur ce fichier : **T-05 est retiré**. Son scénario — « aucun compte par défaut » —
 * n'est plus exprimable ici, le ViewModel ne lisant plus aucun réglage. Ce qui restait de I-8 à ce
 * niveau est tenu par construction (aucune source de compte injectée) et asserté par T-02, qui
 * vérifie que le compte du pré-remplissage vient bien du paramètre et d'aucune valeur littérale.
 *
 * ## Hors périmètre — ce que ce fichier ne vérifie pas
 *
 * - **T-07 (anti double-soumission)** : il n'existe dans ce ViewModel ni méthode d'enregistrement
 *   ni verrou. Les deux vivent dans `TransactionEditViewModel.save` / `tryAcquireSaveLock` et sont
 *   déjà couverts par **TC-80, cas A-07a/b/c**.
 * - **Le formulaire ouvert depuis une proposition** : `TransactionEditViewModel.loadProposal` et le
 *   routage de `performSave` vers l'enregistrement depuis proposition sont livrés par le correctif
 *   d'ANO-H mais **ne sont pas couverts ici** — ils demandent leur propre fiche ViewModel.
 * - **États de chargement / d'erreur / de verrou exposés** : le ViewModel n'expose que `pending` et
 *   `effects`. Les assertions « champ par champ, chargement compris » de la fiche n'ont pas de
 *   cible ; l'API reste à livrer par P-10 et n'est **pas** inventée ici pour le confort du test.
 * - **Affichage du dialogue de confirmation et compteur d'en-tête** (CA-15, part interface de
 *   CA-17) : aucun écran n'est monté. Seule la décision d'avertir est testée, par T-03.
 * - **Effets en base et atomicité de l'enregistrement** : portés par la fiche d'intégration.
 *   L'écriture n'est toujours pas atomique, écart documenté sur `SaveTransactionFromProposalUseCase`.
 * - **Analyse du texte de notification et décision de détection** : portées par leurs fiches ;
 *   ici les propositions sont fabriquées à la main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectedInboxTest {

    // ------------------------------------------------------------------ Jeu de données (JDD)
    //
    // Identifiants volontairement éloignés de 0 et de 1 : aucune valeur ne doit pouvoir coïncider
    // par hasard avec un repli du code. cpt1 ≠ compteCodeEnDur rend ANO-I visible (T-05), et
    // catSuggeree ≠ catDefaut prouve laquelle des deux sources la production a réellement utilisée
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

    /** L'ancien repli codé en dur (ANO-I), cité pour que T-05 puisse le distinguer d'une lecture. */
    private val compteCodeEnDur = 1L

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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
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

        /** Confirmations reçues : une proposition n'est confirmée que si une transaction existe. */
        val confirmedIds = mutableListOf<Pair<Long, Long>>()

        override fun observePending(): Flow<List<Proposal>> = state

        override suspend fun upsertOrMerge(
            proposal: Proposal,
            windowMillis: Long,
            nowMillis: Long,
        ): MergeResult = throw UnsupportedOperationException(
            "Hors périmètre de TC-110 : l'écriture d'une proposition est portée par la fiche de détection."
        )

        override suspend fun getById(proposalId: Long): Proposal? =
            state.value.firstOrNull { it.id == proposalId }

        override suspend fun refuse(proposalId: Long) {
            refusedIds += proposalId
            state.value = state.value.filterNot { it.id == proposalId }
        }

        override suspend fun confirm(proposalId: Long, transactionId: Long) {
            confirmedIds += proposalId to transactionId
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
    private fun TestScope.monter(propositions: List<Proposal>): Harnais {
        val repo = FakeProposalRepository(propositions)

        val vm = DetectedTransactionsViewModel(
            proposals = repo,
            refuseProposal = RefuseProposalUseCase(repo),
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
        append("\n  refus reçus = ").append(repo.refusedIds)
        append("\n  confirmations reçues = ").append(repo.confirmedIds)
    }

    /**
     * L'acceptation n'a soldé la proposition d'aucune façon.
     *
     * Une proposition confirmée signifie qu'une transaction a été créée (I-6) : `confirmedIds` vide
     * prouve donc qu'aucune écriture n'est née de ce chemin.
     */
    private fun Harnais.assertAucuneEcriture(exigence: String) {
        assertEquals(
            "$exigence : accepter ne doit créer aucune transaction, donc ne confirmer aucune proposition." +
                diagnostic(),
            emptyList<Pair<Long, Long>>(),
            repo.confirmedIds.toList(),
        )
        assertEquals(
            "$exigence / I-6 : accepter ne doit jamais faire passer la proposition par le refus." +
                diagnostic(),
            emptyList<Long>(),
            repo.refusedIds.toList(),
        )
    }

    // =================================================================================== T-01
    // Accepter ouvre l'édition et n'écrit rien (CA-16, I-1).

    @Test
    fun `T-01 - Given P-OK en attente et compte par defaut defini - When onAccept - Then un seul effet OpenEdition et aucune ecriture (CA-16, I-1)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk))

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

            // 2. Aucune écriture : ni transaction créée, ni proposition soldée (I-1, P-3).
            h.assertAucuneEcriture("CA-16 / I-1")

            // 3. La proposition reste en attente, le compteur de non traitées est inchangé.
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
    fun `T-01b - Given identifiant inconnu de la boite de reception - When onAccept - Then aucun effet et aucune ecriture (CA-16)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk))
            val identifiantAbsent = 4999L

            h.vm.onAccept(identifiantAbsent)
            stabiliser()

            assertEquals(
                "CA-16 : une proposition absente ne doit produire aucun effet." + h.diagnostic(),
                emptyList<InboxEffect>(),
                h.effets.toList(),
            )
            h.assertAucuneEcriture("CA-16")
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
                "(l'ancien repli codé en dur valait $compteCodeEnDur)",
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
                "(l'ancien repli codé en dur valait $compteCodeEnDur)",
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
    fun `T-04 - Given P-OK acceptee et abandonnee trois fois - When onAccept repete - Then aucune ecriture cumulee et pre-remplissage identique (CA-18, I-1, I-9)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk))
            val preRemplissages = mutableListOf<TransactionEdition>()

            repeat(3) { cycle ->
                h.vm.onAccept(pOkId)
                stabiliser()

                // Après chaque cycle : aucune écriture cumulée, la proposition est toujours là.
                h.assertAucuneEcriture("CA-18 / I-1 (cycle ${cycle + 1})")
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

    // =================================================================================== T-06
    // Montant non convertible en centimes : jamais de transaction à zéro (CA-21, I-3).

    @Test
    fun `T-06 - Given proposition dont le montant n est pas convertible en centimes - When onAccept - Then refus explicite et aucune ecriture (CA-21, I-3)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pIllisible))

            h.vm.onAccept(pIllisibleId)
            stabiliser()

            assertEquals(
                "CA-21 : un montant non convertible doit produire exactement un effet, un refus motivé." +
                    h.diagnostic(),
                1,
                h.effets.size,
            )
            val effet = h.effets.single()
            assertTrue(
                "CA-21 / I-3 : un montant non convertible ne doit jamais ouvrir l'édition — ce serait " +
                    "un formulaire pré-rempli à zéro centime, reçu = $effet" + h.diagnostic(),
                effet is InboxEffect.Error,
            )
            assertTrue(
                "CA-21 : le message de refus doit porter un identifiant de ressource non nul, " +
                    "reçu = ${(effet as InboxEffect.Error).messageRes}" + h.diagnostic(),
                effet.messageRes != 0,
            )

            h.assertAucuneEcriture("CA-21 / I-3")
            assertEquals(
                "CA-21 / I-9 : la proposition refusée à l'enregistrement reste en attente et visible." + h.diagnostic(),
                listOf(pIllisible),
                h.vm.pending.value,
            )
        }

    // =================================================================================== T-08
    // Refus explicite : la proposition quitte la boîte de réception (CA-19, I-6, I-9).

    @Test
    fun `T-08 - Given P-OK en attente - When onRefuse - Then un seul refus sur P-OK, aucune confirmation et liste vide (CA-19, I-6, I-9)`() =
        runTest(dispatcher) {
            val h = monter(propositions = listOf(pOk))

            h.vm.onRefuse(pOkId)
            stabiliser()

            assertEquals(
                "CA-19 : le refus doit être transmis exactement une fois, pour la proposition refusée." + h.diagnostic(),
                listOf(pOkId),
                h.repo.refusedIds.toList(),
            )
            assertEquals(
                "I-6 : un refus ne doit jamais confirmer la proposition — les deux statuts ne sont pas " +
                    "interchangeables." + h.diagnostic(),
                emptyList<Pair<Long, Long>>(),
                h.repo.confirmedIds.toList(),
            )
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
