package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.ProposalStatus
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.detection.MergeResult
import com.lop.budget.domain.usecase.detection.RefuseProposalUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.SaveResult
import com.lop.budget.domain.usecase.transaction.SaveTransactionFromProposalUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * TC-109 — Propositions détectées en base : écriture, regroupement, enregistrement atomique
 * (US LOP-54).
 *
 * ## Niveau et chaîne réellement exercée
 *
 * Intégration sur **base Room réelle**. La fiche l'impose et c'est la seule façon de trancher :
 * une doublure ne voit aucun `INSERT`, et c'est précisément ce qui est écrit — combien de lignes,
 * avec quelles valeurs — qu'il faut prouver ici. **Aucun mock, aucun fake, aucun spy** : MockK n'est
 * pas importé dans ce fichier.
 *
 * ```
 * NotificationDetectionRepository (réel)
 *   → DetectedTransactionProposalDao (réel)
 * SaveTransactionFromProposalUseCase (réel)
 *   → CreateTransactionUseCase → SaveTransactionUseCase → TransactionRepository → TransactionDao (réels)
 *   → NotificationDetectionRepository (réel)
 * RefuseProposalUseCase (réel)
 *   → LopDatabase v21 (SQLite natif Robolectric) : en mémoire, sauf T-07
 * ```
 *
 * **T-07 monte une base sur fichier**, dans un dossier temporaire : une base en mémoire ne se
 * rouvre pas, et le cas porte exactement sur la relecture après fermeture.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 *
 * | Cas  | CA / invariant       | Fonction de production                                   | Attendu |
 * |------|----------------------|-----------------------------------------------------------|---------|
 * | T-01 | CA-06, I-1, I-3      | `NotificationDetectionRepository.upsertOrMerge` (insertion) | vert   |
 * | T-02 | CA-12, I-7           | idem, branche doublon dans la fenêtre                      | **ROUGE — ANO-K** |
 * | T-03 | CA-13, I-7           | idem, branche hors fenêtre                                 | vert   |
 * | T-04 | CA-16, I-6           | `SaveTransactionFromProposalUseCase` + `confirm`            | vert   |
 * | T-05 | CA-16, I-6           | idem, édition invalide                                     | **ROUGE — ANO-L** |
 * | T-06 | CA-19, I-6, I-9      | `RefuseProposalUseCase` → `ignore`                          | vert   |
 * | T-07 | CA-22, I-9           | relecture après fermeture de la base                       | vert   |
 *
 * ## Anomalies ouvertes par cette fiche
 *
 * - **ANO-K — le regroupement anti-doublon perd l'occurrence écartée.** `upsertOrMerge` reconnaît
 *   bien le doublon, mais retourne sans rien écrire : `occurrences` reste à 1 et `lastDetectedAt`
 *   à 0. Une notification écartée ne laisse donc aucune trace sur la proposition conservée, ce que
 *   I-7 interdit explicitement. **T-02 est rouge et le restera** tant que l'anomalie n'est pas
 *   traitée. L'oracle n'est pas assoupli.
 * - **ANO-L — l'enregistrement depuis une proposition n'est ni validé ni atomique.**
 *   `SaveTransactionFromProposalUseCase` crée la transaction puis confirme, sans transaction de base
 *   commune ; et la table `transactions` n'a **aucune clé étrangère** sur `accountId`, si bien qu'un
 *   compte inexistant s'insère sans erreur. **T-05 est rouge** sur ses deux assertions à la fois.
 *
 * Le compteur d'occurrences d'ANO-K est aussi cité par TC-107 dans ses risques, mais **TC-107 ne
 * l'assert pas** : à ce niveau le dépôt est doublé et le défaut y est invisible. Une cause racine,
 * une anomalie, un seul cas qui la prouve.
 *
 * ## Hors périmètre — ce que ce fichier ne vérifie pas
 *
 * - **La décision d'écrire ou non** selon réglages et source : portée par TC-107.
 * - **L'analyse du texte de notification** : portée par TC-108. Les propositions sont fabriquées ici.
 * - **L'état exposé à l'écran et le parcours d'abandon** : portés par TC-110.
 * - **La date de paiement effective** (`paidAt`) de la transaction créée : portée par TC-114, qui
 *   possède le sujet temporel. Ne pas l'asserter ici évite de faire porter ANO-M par deux fiches.
 * - **Le redémarrage réel du téléphone** : seule la persistance après recréation de la base est
 *   couverte, par T-07.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ProposalPersistenceTest {

    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    // ------------------------------------------------------------------ Jeu de données (JDD)

    private val zoneParis: ZoneId = ZoneId.of("Europe/Paris")

    /** `T0` : horodatage de détection de référence. Date fixe, indépendante d'aujourd'hui. */
    private val t0: Long = ZonedDateTime
        .of(LocalDateTime.of(2026, 3, 10, 21, 0, 0), zoneParis)
        .toInstant()
        .toEpochMilli()

    /**
     * `FENETRE` : fenêtre de regroupement de 2 minutes (P-2), **déclarée localement**.
     *
     * TC-107 porte aujourd'hui la même valeur et la redéclare de son côté. C'est délibéré : cette
     * durée conditionne le « exactement une » de T-02 et le « exactement deux » de T-03. La reprendre
     * d'un fixture partagé la rendrait modifiable par un ticket tiers sans que ce fichier le sache.
     */
    private val fenetreMs = 120_000L

    private val paquetGoogleWallet = "com.google.android.apps.walletnfcrel"
    private val cleK1 = "K-1-cle-de-regroupement"

    /** `P-OK` : proposition en attente, montant en centimes entiers (P-1 / I-3). */
    private val pOk = Proposal(
        sourcePackage = paquetGoogleWallet,
        amountCents = 1_250L,
        currency = "EUR",
        label = "Carrefour",
        fullText = "Google Wallet • Carrefour 12,50 €",
        cardName = "Visa ••1234",
        detectedAt = t0,
        dedupeKey = cleK1,
        status = ProposalStatus.PENDING,
        confidence = 0.94f,
    )

    /** `P-BIS` : même clé, **dans** la fenêtre (T0 + 119 s). */
    private val pBis = pOk.copy(detectedAt = t0 + 119_000L, lastDetectedAt = t0 + 119_000L)

    /** `P-TARD` : même clé, **hors** de la fenêtre (T0 + 121 s). */
    private val pTard = pOk.copy(detectedAt = t0 + 121_000L, lastDetectedAt = t0 + 121_000L)

    /** Compte qui n'existe dans aucune table : c'est la seule propriété invalide de T-05. */
    private val compteInexistant = 99_999L

    // ------------------------------------------------------------------ Montage

    private lateinit var db: LopDatabase
    private lateinit var depot: NotificationDetectionRepository
    private lateinit var enregistrerDepuisProposition: SaveTransactionFromProposalUseCase
    private lateinit var refuser: RefuseProposalUseCase

    /** Identifiants réellement alloués par Room, conservés sous noms symboliques. */
    private var cpt1: Long = 0
    private var cat1: Long = 0

    private lateinit var fuseauInitial: TimeZone
    private lateinit var localeInitiale: Locale

    @Before
    fun setUp() = runTest {
        fuseauInitial = TimeZone.getDefault()
        localeInitiale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneParis))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        depot = NotificationDetectionRepository(db.detectedTransactionProposalDao())
        enregistrerDepuisProposition = construireEnregistrement(db, depot)
        refuser = RefuseProposalUseCase(depot)

        cpt1 = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 100_000L,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        cat1 = db.categoryDao().upsert(
            CategoryEntity(
                name = "Courses",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "cart",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(fuseauInitial)
        Locale.setDefault(localeInitiale)
    }

    /** `ED-OK` : édition soumise au formulaire. Construite après le montage, les IDs venant de Room. */
    private fun editionOk(compteId: Long = cpt1, date: Long = t0) = TransactionEdition(
        title = "Carrefour",
        amount = 1_250L,
        type = TransactionType.EXPENSE,
        date = date,
        accountId = compteId,
        categoryId = cat1,
        note = "Détecté via $paquetGoogleWallet",
        status = TransactionStatus.PAID,
        frequency = RecurrenceFrequency.NONE,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        linkedGoalId = null,
        linkedDebtId = null,
        tagIds = emptyList(),
    )

    // ==========================================================================================
    // T-01 — Écriture sur base vide (CA-06, I-1, I-3)
    // ==========================================================================================

    @Test
    fun `given une base vide when une proposition est ecrite then une seule ligne complete existe et aucune transaction`() =
        runTest {
            assertEquals("instantané d'avant : table des propositions vide", 0, propositions().size)
            assertEquals("instantané d'avant : table des transactions vide", 0, transactions().size)

            val resultat = depot.upsertOrMerge(pOk, fenetreMs, t0)

            assertTrue(
                "CA-06 : une base vide ne peut produire qu'une insertion, pas un regroupement — reçu $resultat",
                resultat is MergeResult.Inserted,
            )
            val idPOk = (resultat as MergeResult.Inserted).proposalId

            assertEquals(
                "CA-06 : exactement une proposition | ${propositions()}",
                1,
                propositions().size,
            )

            // Les neuf champs nommés par CA-06, relus depuis la base, pas depuis l'objet soumis.
            val relue = db.detectedTransactionProposalDao().getById(idPOk)
            assertNotNull("CA-06 : la proposition doit être relisible par son identifiant", relue)
            requireNotNull(relue)
            assertEquals("CA-06 : paquet source", paquetGoogleWallet, relue.sourcePackage)
            assertEquals("CA-06 / I-3 : montant en centimes entiers", 1_250L, relue.amountCents)
            assertEquals("CA-06 : devise", "EUR", relue.currency)
            assertEquals("CA-06 : libellé", "Carrefour", relue.label)
            assertEquals("CA-06 : horodatage de détection", t0, relue.detectedAt)
            assertEquals("CA-06 / P-2 : clé de regroupement", cleK1, relue.dedupeKey)
            assertEquals("CA-06 : statut en attente", "pending", relue.status)
            assertEquals("CA-06 / I-7 : compteur d'occurrences initial", 1, relue.occurrences)
            assertEquals("CA-06 : score de confiance", 0.94f, relue.confidenceScore, 0f)

            // I-1 : l'arrivée d'une notification n'écrit jamais dans la table des transactions.
            assertEquals(
                "I-1 : zéro transaction après une simple détection | ${transactions()}",
                0,
                transactions().size,
            )
        }

    // ==========================================================================================
    // T-02 — Regroupement dans la fenêtre (CA-12, I-7) — ROUGE ATTENDU, ANO-K
    // ==========================================================================================

    @Test
    fun `given une proposition ecrite when la meme revient dans la fenetre then elle est regroupee et l occurrence est comptabilisee`() =
        runTest {
            val idPOk = (depot.upsertOrMerge(pOk, fenetreMs, t0) as MergeResult.Inserted).proposalId

            val resultat = depot.upsertOrMerge(pBis, fenetreMs, t0 + 119_000L)

            assertEquals(
                "CA-12 : une seule proposition après deux notifications de même clé dans la fenêtre | ${propositions()}",
                1,
                propositions().size,
            )
            assertTrue(
                "CA-12 : le second appel doit rendre un résultat de fusion, jamais une insertion — reçu $resultat",
                resultat is MergeResult.Merged,
            )
            assertEquals(
                "CA-12 : la fusion porte l'identifiant de la ligne conservée",
                idPOk,
                (resultat as MergeResult.Merged).proposalId,
            )

            val relue = requireNotNull(db.detectedTransactionProposalDao().getById(idPOk))

            // ANO-K — rouge légitime attendu ici. I-7 interdit qu'un événement écarté comme doublon
            // disparaisse sans trace ; le dépôt reconnaît le doublon mais n'incrémente rien.
            assertEquals(
                "CA-12 / I-7 (ANO-K) : la notification écartée doit rester comptabilisée sur la " +
                    "proposition conservée | proposition relue = $relue",
                2,
                relue.occurrences,
            )
            assertEquals(
                "CA-12 / I-7 (ANO-K) : le second horodatage doit laisser une trace | proposition relue = $relue",
                t0 + 119_000L,
                relue.lastDetectedAt,
            )
            assertEquals(
                "CA-12 / I-7 (ANO-K) : le compteur rendu par la fusion doit être celui de la base",
                2,
                resultat.occurrences,
            )
        }

    // ==========================================================================================
    // T-03 — Hors de la fenêtre (CA-13, I-7)
    // ==========================================================================================

    @Test
    fun `given une proposition ecrite when la meme revient hors de la fenetre then deux propositions distinctes existent`() =
        runTest {
            val idPOk = (depot.upsertOrMerge(pOk, fenetreMs, t0) as MergeResult.Inserted).proposalId

            val resultat = depot.upsertOrMerge(pTard, fenetreMs, t0 + 121_000L)

            assertTrue(
                "CA-13 : hors fenêtre, la seconde notification est une nouvelle proposition — reçu $resultat",
                resultat is MergeResult.Inserted,
            )
            val idPTard = (resultat as MergeResult.Inserted).proposalId

            val lignes = propositions()
            assertEquals("CA-13 : exactement deux propositions | $lignes", 2, lignes.size)
            assertNotEquals("CA-13 : les deux propositions ont des identifiants distincts", idPOk, idPTard)
            assertEquals(
                "CA-13 / I-7 : chaque proposition compte une seule occurrence | $lignes",
                listOf(1, 1),
                lignes.map { it.occurrences },
            )
            assertEquals(
                "CA-13 : les deux propositions gardent leur horodatage de détection | $lignes",
                listOf(t0, t0 + 121_000L),
                lignes.map { it.detectedAt },
            )
            assertEquals("I-1 : toujours zéro transaction | ${transactions()}", 0, transactions().size)
        }

    // ==========================================================================================
    // T-04 — Enregistrement abouti (CA-16, I-6)
    // ==========================================================================================

    @Test
    fun `given une proposition en attente when une transaction est enregistree depuis elle then elle est confirmee avec l identifiant cree`() =
        runTest {
            val idPOk = (depot.upsertOrMerge(pOk, fenetreMs, t0) as MergeResult.Inserted).proposalId

            val resultat = enregistrerDepuisProposition(idPOk, editionOk())

            assertTrue(
                "CA-16 : l'enregistrement d'une édition valide doit aboutir — reçu $resultat",
                resultat is SaveResult.Created,
            )
            val idTransaction = (resultat as SaveResult.Created).transactionId

            val lignesTx = transactions()
            assertEquals("CA-16 : exactement une transaction créée | $lignesTx", 1, lignesTx.size)

            // Les huit champs nommés par CA-16, relus depuis la base.
            val tx = requireNotNull(db.transactionDao().getById(idTransaction)).transaction
            assertEquals("CA-16 : libellé", "Carrefour", tx.title)
            assertEquals("CA-16 / I-3 : montant en centimes", 1_250L, tx.amount)
            assertEquals("CA-16 : type dépense", TransactionType.EXPENSE, tx.type)
            assertEquals("CA-16 / P-4 : statut réglé", TransactionStatus.PAID, tx.status)
            assertEquals("CA-16 / I-11 : date de détection", t0, tx.date)
            assertEquals("CA-16 / I-8 : compte issu de l'édition, jamais codé en dur", cpt1, tx.accountId)
            assertEquals("CA-16 / I-8 : catégorie issue de l'édition", cat1, tx.categoryId)
            assertEquals("CA-16 : note citant la source", "Détecté via $paquetGoogleWallet", tx.note)

            // I-6 : confirmée, avec le lien vers la transaction. Jamais ignorée.
            val relue = requireNotNull(db.detectedTransactionProposalDao().getById(idPOk))
            assertEquals("CA-16 / I-6 : la proposition acceptée passe à confirmée", "confirmed", relue.status)
            assertNotEquals(
                "CA-16 / I-6 : une proposition acceptée ne doit jamais passer à ignorée",
                "ignored",
                relue.status,
            )
            assertEquals(
                "CA-16 / I-6 : la proposition porte l'identifiant réel de la transaction insérée",
                idTransaction,
                relue.createdTransactionId,
            )

            assertEquals(
                "CA-16 : une proposition confirmée quitte la boîte de réception",
                emptyList<Long>(),
                enAttente().map { it.id },
            )
        }

    // ==========================================================================================
    // T-05 — Édition invalide (CA-16, I-6) — ROUGE ATTENDU, ANO-L
    // ==========================================================================================

    @Test
    fun `given une edition sur un compte inexistant when l enregistrement est tente then rien n est ecrit des deux cotes`() =
        runTest {
            val idPOk = (depot.upsertOrMerge(pOk, fenetreMs, t0) as MergeResult.Inserted).proposalId
            val avantPropositions = propositions()
            val avantTransactions = transactions()

            val resultat = enregistrerDepuisProposition(idPOk, editionOk(compteId = compteInexistant))

            val apresPropositions = propositions()
            val apresTransactions = transactions()
            val etat = "résultat = $resultat | propositions = $apresPropositions | transactions = $apresTransactions"

            // ANO-L — rouge légitime attendu. Les deux écritures doivent être atomiques : une
            // transaction sans confirmation est un échec au même titre qu'une confirmation sans
            // transaction. Aujourd'hui la transaction s'insère sur un compte qui n'existe pas.
            assertEquals(
                "CA-16 / I-6 (ANO-L) : une édition invalide ne crée aucune transaction | $etat",
                0,
                apresTransactions.size,
            )
            assertEquals(
                "CA-16 / I-6 (ANO-L) : la proposition reste en attente tant qu'aucune transaction n'existe | $etat",
                "pending",
                requireNotNull(db.detectedTransactionProposalDao().getById(idPOk)).status,
            )
            assertEquals(
                "CA-16 (ANO-L) : instantané des propositions inchangé | $etat",
                avantPropositions,
                apresPropositions,
            )
            assertEquals(
                "CA-16 (ANO-L) : instantané des transactions inchangé | $etat",
                avantTransactions,
                apresTransactions,
            )
        }

    // ==========================================================================================
    // T-06 — Refus explicite (CA-19, I-6, I-9)
    // ==========================================================================================

    @Test
    fun `given une proposition refusee when la meme notification revient dans la fenetre then elle ne reapparait pas en attente`() =
        runTest {
            val idPOk = (depot.upsertOrMerge(pOk, fenetreMs, t0) as MergeResult.Inserted).proposalId

            refuser(idPOk)

            assertEquals("CA-19 / I-1 : un refus ne crée aucune transaction | ${transactions()}", 0, transactions().size)
            val relue = requireNotNull(db.detectedTransactionProposalDao().getById(idPOk))
            assertEquals("CA-19 / I-6 : la proposition refusée passe à ignorée", "ignored", relue.status)
            assertNotEquals(
                "CA-19 / I-6 : une proposition refusée ne doit jamais passer à confirmée",
                "confirmed",
                relue.status,
            )
            assertEquals(
                "CA-19 / P-6 : la proposition est conservée, seule la boîte de réception l'exclut | ${propositions()}",
                1,
                propositions().size,
            )
            assertEquals(
                "CA-19 : la boîte de réception est explicitement vide après un refus",
                emptyList<Long>(),
                enAttente().map { it.id },
            )

            // I-9 : ni une réémission de la notification ni rien d'autre ne la fait revenir.
            depot.upsertOrMerge(pBis, fenetreMs, t0 + 119_000L)

            assertEquals(
                "CA-19 / I-9 : une réémission dans la fenêtre ne crée pas de seconde ligne | ${propositions()}",
                1,
                propositions().size,
            )
            assertEquals(
                "CA-19 / I-9 : une proposition refusée ne réapparaît pas en attente",
                emptyList<Long>(),
                enAttente().map { it.id },
            )
            assertEquals("CA-19 / I-1 : toujours zéro transaction | ${transactions()}", 0, transactions().size)
        }

    // ==========================================================================================
    // T-07 — Persistance après fermeture (CA-22, I-9)
    // ==========================================================================================

    @Test
    fun `given deux propositions ecrites when la base est fermee puis rouverte then elles sont relues a l identique`() =
        runTest {
            // Une base **sur fichier** : une base en mémoire disparaît à la fermeture, et ce cas
            // porte précisément sur ce qui survit à la fermeture.
            val fichier = File(dossierTemporaire.root, "tc109-persistance.db")

            val premiereOuverture = ouvrirSurFichier(fichier)
            val avant = try {
                val depotFichier = NotificationDetectionRepository(
                    premiereOuverture.detectedTransactionProposalDao(),
                )
                depotFichier.upsertOrMerge(pOk, fenetreMs, t0)
                depotFichier.upsertOrMerge(pTard, fenetreMs, t0 + 121_000L)
                propositions(premiereOuverture)
            } finally {
                premiereOuverture.close()
            }

            assertEquals("préalable : deux propositions écrites avant fermeture | $avant", 2, avant.size)

            val secondeOuverture = ouvrirSurFichier(fichier)
            try {
                val apres = propositions(secondeOuverture)

                assertEquals(
                    "CA-22 / I-9 : les propositions non traitées sont retrouvées à l'identique | avant = $avant | après = $apres",
                    avant,
                    apres,
                )
                assertEquals(
                    "CA-22 : une proposition en attente le reste après relance",
                    listOf("pending", "pending"),
                    apres.map { it.status },
                )
                assertEquals(
                    "CA-22 / I-3 : les montants restent des centimes entiers après relecture",
                    listOf(1_250L, 1_250L),
                    apres.map { it.amountCents },
                )
            } finally {
                secondeOuverture.close()
            }
        }

    // ------------------------------------------------------------------ Outillage local

    /**
     * Instantané de la table des propositions.
     *
     * `observePending` masque les lignes traitées, et aucun accès dédié ne rend la table entière :
     * la lecture se fait donc en SQL brut, limité au code de test. Aucune API de production n'est
     * ajoutée pour le confort du test (AGENTS.md §6).
     */
    private fun propositions(base: LopDatabase = db): List<LigneProposition> =
        base.query(
            "SELECT id, status, occurrences, detectedAt, lastDetectedAt, createdTransactionId, amountCents " +
                "FROM detected_transaction_proposals ORDER BY id",
            arrayOf<Any>(),
        ).use { curseur ->
            buildList {
                while (curseur.moveToNext()) {
                    add(
                        LigneProposition(
                            id = curseur.getLong(0),
                            status = curseur.getString(1),
                            occurrences = curseur.getInt(2),
                            detectedAt = curseur.getLong(3),
                            lastDetectedAt = curseur.getLong(4),
                            createdTransactionId = if (curseur.isNull(5)) null else curseur.getLong(5),
                            amountCents = curseur.getLong(6),
                        ),
                    )
                }
            }
        }

    /** Instantané de la table des transactions, lignes supprimées comprises. */
    private fun transactions(base: LopDatabase = db): List<LigneTransaction> =
        base.query(
            "SELECT id, title, amount, accountId, date, deleted FROM transactions ORDER BY id",
            arrayOf<Any>(),
        ).use { curseur ->
            buildList {
                while (curseur.moveToNext()) {
                    add(
                        LigneTransaction(
                            id = curseur.getLong(0),
                            title = curseur.getString(1),
                            amount = curseur.getLong(2),
                            accountId = curseur.getLong(3),
                            date = curseur.getLong(4),
                            deleted = curseur.getInt(5) != 0,
                        ),
                    )
                }
            }
        }

    /**
     * Boîte de réception observée, avec délai d'échec borné.
     *
     * Chaque appel réabonne le flux : le contrat porte sur ce que l'observation rend **après** la
     * mutation, pas sur une valeur capturée au début du cas.
     *
     * Le délai vient de `runTest`, pas d'un `withTimeout` local. Piège rencontré et écarté : à
     * l'intérieur de `runTest`, `withTimeout` compte du temps **virtuel** et expire donc aussitôt,
     * alors que Room émet depuis un vrai fil d'exécution. Le test tombait alors sur un délai dépassé
     * — un échec de montage, qui n'est pas une preuve métier.
     */
    private suspend fun enAttente(): List<Proposal> = depot.observePending().first()

    private fun ouvrirSurFichier(fichier: File): LopDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
            fichier.absolutePath,
        ).allowMainThreadQueries().build()

    private fun construireEnregistrement(
        base: LopDatabase,
        propositions: NotificationDetectionRepository,
    ): SaveTransactionFromProposalUseCase {
        val transactionRepo = TransactionRepository(base.transactionDao(), base.recurringSeriesDao())
        val syncProgress = SyncProgressUseCase(
            transactionRepo,
            GoalRepository(base.goalDao()),
            DebtRepository(base.debtDao()),
        )
        return SaveTransactionFromProposalUseCase(
            CreateTransactionUseCase(
                transactionRepo,
                SaveTransactionUseCase(transactionRepo, syncProgress),
            ),
            propositions,
        )
    }

    private data class LigneProposition(
        val id: Long,
        val status: String,
        val occurrences: Int,
        val detectedAt: Long,
        val lastDetectedAt: Long,
        val createdTransactionId: Long?,
        val amountCents: Long,
    )

    private data class LigneTransaction(
        val id: Long,
        val title: String,
        val amount: Long,
        val accountId: Long,
        val date: Long,
        val deleted: Boolean,
    )
}
