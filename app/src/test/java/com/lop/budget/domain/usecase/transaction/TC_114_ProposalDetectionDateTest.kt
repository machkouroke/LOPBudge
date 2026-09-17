package com.lop.budget.domain.usecase.transaction

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.ProposalStatus
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.buildEdition
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.detection.MergeResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * TC-114 — La date d'une transaction issue d'une proposition est l'horodatage de détection
 * (US LOP-54).
 *
 * ## Ce que ce fichier prouve, en une phrase
 *
 * Qu'une transaction née d'une proposition porte l'heure du **paiement détecté**, et que ni
 * l'ouverture du formulaire, ni l'enregistrement, ni une relecture après redémarrage ne peuvent y
 * substituer l'heure courante.
 *
 * ## Niveau et chaîne réellement exercée
 *
 * Intégration sur **base Room réelle** : c'est la date **persistée** qu'il faut prouver, et une
 * doublure ne voit aucun `INSERT`. **Aucun mock, aucun fake, aucun spy** — MockK n'est pas importé.
 *
 * ```
 * buildEdition (fonction pure)                        → pré-remplissage du formulaire
 * SaveTransactionFromProposalUseCase (réel)
 *   → CreateTransactionUseCase → SaveTransactionUseCase → TransactionRepository → TransactionDao (réels)
 *   → NotificationDetectionRepository → DetectedTransactionProposalDao (réels)
 *   → LopDatabase v21 : en mémoire, sauf D-4 qui monte une base sur fichier
 * ```
 *
 * ## Pourquoi il n'y a pas d'horloge pilotable ici — lire avant de juger D-3
 *
 * La fiche demandait une horloge injectée qu'on avance entre les étapes. **Ni `buildEdition` ni
 * `SaveTransactionFromProposalUseCase` n'en reçoivent** : ils n'ont aucune horloge à lire. Avancer
 * le temps est donc, sur cette chaîne, un non-événement **par construction** — ce qui est plus fort
 * qu'une vérification faite après coup, puisqu'on ne peut pas contourner une dépendance absente.
 *
 * D-3 le prouve sur deux plans, sans jamais simuler une horloge que personne ne consulte :
 *
 * 1. **Structurellement** : par réflexion, aucune signature de la chaîne ne mentionne `Clock`.
 * 2. **Par le comportement** : deux propositions détectées à **30 jours d'écart** sont enregistrées
 *    dans le même instant d'exécution. Les deux transactions persistées portent des dates séparées
 *    de 30 jours. Si quoi que ce soit lisait l'heure courante, les deux dates seraient confondues.
 *
 * ## Fixture discriminante
 *
 * `T_DETECT`, `T_OPEN` et `T_SAVE` sont **tous distincts**, et la date pré-remplie modifiée à la
 * main de D-6 est distincte des trois. Chaque oracle nomme donc une seule source possible : si la
 * production prenait l'une des autres, l'écart serait visible au lieu de passer inaperçu.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 *
 * | Cas | CA / invariant        | Fonction de production                            |
 * |-----|-----------------------|----------------------------------------------------|
 * | D-1 | CA-16, I-11, P-4      | `buildEdition`                                      |
 * | D-2 | CA-27, I-6, I-11, P-4 | `SaveTransactionFromProposalUseCase`                |
 * | D-3 | CA-27, I-11           | les deux, écart de détection de 30 jours            |
 * | D-4 | CA-22, I-11           | relecture après fermeture de la base                |
 * | D-5 | CA-27, I-11           | enregistrement d'une détection en fin de journée    |
 * | D-6 | P-11                  | enregistrement d'une date saisie à la main          |
 *
 * ## Anomalie ouverte par cette fiche, puis corrigée le 15 septembre 2026
 *
 * **ANO-M — la transaction détectée était marquée payée à l'heure d'enregistrement** (D-2).
 * `SaveTransactionUseCase.saveSimple` horodatait `paidAt` avec `System.currentTimeMillis()` dès
 * qu'un statut réglé arrivait sans date de paiement. Un paiement détecté le 10 mars et enregistré le
 * 12 était donc marqué payé le 12 : pour un seul événement, la date de la transaction et sa date de
 * paiement divergeaient.
 *
 * RED constaté : `date=1773172800000` (correct) mais `paidAt=1789492105470`, l'heure réelle
 * d'exécution. Corrigé — une transaction déclarée réglée est payée **à sa date**. La règle vaut pour
 * tous les appelants de `saveSimple`, pas seulement la détection, et P-4 a été révisée en ce sens.
 * L'oracle n'a pas été assoupli : c'est la production qui a changé.
 *
 * L'assertion de `paidAt` reste **volontairement la dernière** de D-2 : les oracles qui la précèdent
 * sont ainsi tous évalués, et un futur rouge tomberait sur la cause réelle plutôt que sur un effet.
 *
 * ## Hors périmètre — ce que ce fichier ne vérifie pas
 *
 * - **Le rendu de la date à l'écran** et son format d'affichage : ce niveau ne prouve aucun affichage.
 * - **Reconnaissance du texte, regroupement anti-doublon, réglages et autorisations** : portés par
 *   TC-107, TC-108 et TC-110.
 * - **L'atomicité de l'enregistrement et les cardinalités des propositions** : portées par TC-109.
 *   ANO-K et ANO-L ne sont pas citées ici, une cause racine n'étant prouvée qu'une fois.
 * - **Les fuseaux multiples et le changement d'heure** au-delà de D-4 : non couverts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ProposalDetectionDateTest {

    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    // ------------------------------------------------------------------ Jeu de données (JDD)

    private val zoneParis: ZoneId = ZoneId.of("Europe/Paris")

    private fun instant(annee: Int, mois: Int, jour: Int, heure: Int, minute: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(annee, mois, jour, heure, minute, 0), zoneParis)
            .toInstant()
            .toEpochMilli()

    /** `T_DETECT` — instant de détection : 10 mars 2026 à 21 h 00, heure de Paris. */
    private val tDetect: Long = instant(2026, 3, 10, 21, 0)

    /** `T_OPEN` — ouverture du formulaire, deux jours plus tard. Jamais une date attendue. */
    private val tOpen: Long = instant(2026, 3, 12, 9, 15)

    /** `T_SAVE` — enregistrement, quatre minutes après l'ouverture. Jamais une date attendue. */
    private val tSave: Long = instant(2026, 3, 12, 9, 19)

    /** Date saisie à la main dans le formulaire en D-6. Distincte des trois instants ci-dessus. */
    private val tSaisieManuelle: Long = instant(2026, 3, 1, 12, 0)

    /** `proposalB` — détection à la dernière minute du 28 février, loin devant l'enregistrement. */
    private val tDetectFinDeJournee: Long = instant(2026, 2, 28, 23, 58)

    private val trenteJoursMs = 30L * 24 * 60 * 60 * 1000

    private val paquetGoogleWallet = "com.google.android.apps.walletnfcrel"
    private val fenetreMs = 120_000L

    /** `proposalA` — proposition en attente, montant en centimes entiers (P-1 / I-3). */
    private val propositionA = Proposal(
        sourcePackage = paquetGoogleWallet,
        amountCents = 1_250L,
        currency = "EUR",
        label = "Carrefour",
        fullText = "Google Wallet • Carrefour 12,50 €",
        cardName = "Visa ••1234",
        detectedAt = tDetect,
        dedupeKey = "K-A-carrefour",
        status = ProposalStatus.PENDING,
        confidence = 0.94f,
    )

    /** `proposalB` — identique, sauf l'horodatage de fin de journée et le libellé. */
    private val propositionB = propositionA.copy(
        label = "Station",
        detectedAt = tDetectFinDeJournee,
        lastDetectedAt = tDetectFinDeJournee,
        dedupeKey = "K-B-station",
    )

    /** `proposalC` — détectée 30 jours avant `proposalA`. Sert la comparaison de D-3. */
    private val propositionC = propositionA.copy(
        label = "Boulangerie",
        detectedAt = tDetect - trenteJoursMs,
        lastDetectedAt = tDetect - trenteJoursMs,
        dedupeKey = "K-C-boulangerie",
    )

    // ------------------------------------------------------------------ Montage

    private lateinit var db: LopDatabase
    private lateinit var depot: NotificationDetectionRepository
    private lateinit var enregistrerDepuisProposition: SaveTransactionFromProposalUseCase

    /** Identifiants réellement alloués par Room, conservés sous noms symboliques. */
    private var compteRef: Long = 0
    private var categorieRef: Long = 0

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

        compteRef = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 100_000L,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        categorieRef = db.categoryDao().upsert(
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

    // ==========================================================================================
    // D-1 — Pré-remplissage du formulaire (CA-16, I-11, P-4)
    // ==========================================================================================

    @Test
    fun `given une proposition detectee when le formulaire est pre rempli then la date est celle de la detection et rien n est ecrit`() =
        runTest {
            depot.upsertOrMerge(propositionA, fenetreMs, tDetect)
            val transactionsAvant = transactions()

            val prefill = buildEdition(
                proposal = propositionA,
                defaultAccountId = compteRef,
                defaultCategoryId = categorieRef,
            )

            assertEquals(
                "CA-16 / I-11 : la date pré-remplie est l'instant de détection, ni T_OPEN ni T_SAVE",
                tDetect,
                prefill.date,
            )
            // Les trois instants du JDD sont distincts : nommer les deux exclus fait de l'oracle
            // ci-dessus une assertion discriminante, et non une coïncidence.
            assertNotEquals("CA-16 / I-11 : la date pré-remplie n'est pas T_OPEN", tOpen, prefill.date)
            assertNotEquals("CA-16 / I-11 : la date pré-remplie n'est pas T_SAVE", tSave, prefill.date)

            // Les sept autres champs nommés par CA-16.
            assertEquals("CA-16 : libellé", "Carrefour", prefill.title)
            assertEquals("CA-16 / I-3 : montant en centimes", 1_250L, prefill.amount)
            assertEquals("CA-16 : type dépense", TransactionType.EXPENSE, prefill.type)
            assertEquals("CA-16 / I-8 : compte reçu en paramètre, jamais codé en dur", compteRef, prefill.accountId)
            assertEquals("CA-16 / I-8 : catégorie reçue en paramètre", categorieRef, prefill.categoryId)
            assertEquals("CA-16 / P-4 : statut réglé", TransactionStatus.PAID, prefill.status)
            assertEquals(
                "CA-16 : note citant la source",
                "Détecté via $paquetGoogleWallet",
                prefill.note,
            )

            // I-1 / P-3 : ouvrir le formulaire n'écrit rien. Instantané avant / après.
            assertEquals(
                "I-1 / P-3 : un pré-remplissage n'ajoute aucune ligne | avant = $transactionsAvant | après = ${transactions()}",
                transactionsAvant,
                transactions(),
            )
            assertEquals("I-1 : zéro transaction après un pré-remplissage", 0, transactions().size)
        }

    // ==========================================================================================
    // D-2 — Enregistrement sans toucher à la date (CA-27, I-6, I-11, P-4) — ROUGE ATTENDU, ANO-M
    // ==========================================================================================

    @Test
    fun `given un formulaire pre rempli non modifie when la transaction est enregistree then elle porte la date et l heure de paiement de la detection`() =
        runTest {
            val idProposition = inserer(propositionA)
            val prefill = buildEdition(propositionA, compteRef, categorieRef)

            val resultat = enregistrerDepuisProposition(idProposition, prefill)

            assertTrue(
                "CA-27 : l'enregistrement d'une édition valide doit aboutir — reçu $resultat",
                resultat is SaveResult.Created,
            )
            val idTransaction = (resultat as SaveResult.Created).transactionId

            assertEquals("CA-27 : exactement une transaction créée | ${transactions()}", 1, transactions().size)

            val tx = requireNotNull(db.transactionDao().getById(idTransaction)).transaction
            assertEquals(
                "CA-27 / I-11 : la date persistée est l'instant de détection, ni T_OPEN ni T_SAVE " +
                    "| transaction relue = $tx",
                tDetect,
                tx.date,
            )
            assertNotEquals("CA-27 / I-11 : la date persistée n'est pas T_OPEN", tOpen, tx.date)
            assertNotEquals("CA-27 / I-11 : la date persistée n'est pas T_SAVE", tSave, tx.date)

            // I-6 : la proposition est soldée par une confirmation portant l'identifiant réel.
            val relue = requireNotNull(db.detectedTransactionProposalDao().getById(idProposition))
            assertEquals("CA-27 / I-6 : la proposition passe à confirmée", "confirmed", relue.status)
            assertEquals(
                "CA-27 / I-6 : la proposition porte l'identifiant de la transaction créée",
                idTransaction,
                relue.createdTransactionId,
            )

            assertEquals("CA-16 / P-4 : la transaction est réglée", TransactionStatus.PAID, tx.status)

            // ANO-M — rouge légitime attendu, et volontairement placé en dernier pour que tous les
            // oracles verts ci-dessus soient évalués. P-4 et I-11 révisés le 15 septembre 2026 :
            // une transaction réglée issue d'une proposition est payée à l'instant de la détection,
            // jamais à l'instant où l'utilisateur vide sa boîte de réception.
            assertEquals(
                "CA-27 / I-11 / P-4 (ANO-M) : la date de paiement effective doit être l'instant de " +
                    "détection, pas l'heure d'enregistrement | transaction relue = $tx",
                tDetect,
                tx.paidAt,
            )
        }

    // ==========================================================================================
    // D-3 — Écart de 30 jours entre détection et enregistrement (CA-27, I-11)
    // ==========================================================================================

    @Test
    fun `given deux propositions detectees a trente jours d ecart when elles sont enregistrees ensemble then chaque date suit sa propre detection`() =
        runTest {
            // Structurellement : la chaîne testée ne reçoit aucune horloge. Une dépendance absente
            // ne se contourne pas, là où une horloge injectée mais ignorée resterait à surveiller.
            val facadeProposal = Class.forName("com.lop.budget.domain.model.ProposalKt")
            val signatureDuPrefill = facadeProposal.declaredMethods
                .single { it.name == "buildEdition" }
                .parameterTypes
                .map { it.name }
            val signatureDeLEnregistrement = buildList {
                addAll(
                    SaveTransactionFromProposalUseCase::class.java.declaredConstructors
                        .single().parameterTypes.map { it.name },
                )
                addAll(
                    SaveTransactionFromProposalUseCase::class.java.declaredMethods
                        .single { it.name == "invoke" }.parameterTypes.map { it.name },
                )
            }
            (signatureDuPrefill + signatureDeLEnregistrement).forEach { type ->
                assertTrue(
                    "I-11 / P-11 : $type est une horloge dans la chaîne proposition → transaction ; " +
                        "ni le pré-remplissage ni l'enregistrement ne doivent pouvoir lire l'heure",
                    !type.startsWith("java.time.Clock") && !type.endsWith("Clock"),
                )
            }

            // Par le comportement : deux enregistrements dans le même instant d'exécution, deux
            // dates séparées de 30 jours. Si quoi que ce soit lisait l'heure courante, les deux
            // dates persistées seraient confondues au lieu d'être distantes d'un mois.
            val idA = inserer(propositionA)
            val idC = inserer(propositionC)

            val prefillA = buildEdition(propositionA, compteRef, categorieRef)
            val prefillC = buildEdition(propositionC, compteRef, categorieRef)
            assertEquals("CA-27 / I-11 : le pré-remplissage de A suit sa détection", tDetect, prefillA.date)
            assertEquals(
                "CA-27 / I-11 : le pré-remplissage de C suit sa détection, 30 jours plus tôt",
                tDetect - trenteJoursMs,
                prefillC.date,
            )

            val txA = requireNotNull(
                db.transactionDao().getById(
                    (enregistrerDepuisProposition(idA, prefillA) as SaveResult.Created).transactionId,
                ),
            ).transaction
            val txC = requireNotNull(
                db.transactionDao().getById(
                    (enregistrerDepuisProposition(idC, prefillC) as SaveResult.Created).transactionId,
                ),
            ).transaction

            assertEquals("CA-27 / I-11 : A garde sa date de détection", tDetect, txA.date)
            assertEquals(
                "CA-27 / I-11 : C garde la sienne, inchangée par l'écart d'horloge",
                tDetect - trenteJoursMs,
                txC.date,
            )
            assertEquals(
                "CA-27 / I-11 : deux enregistrements simultanés produisent deux dates distantes de " +
                    "30 jours | A = ${txA.date} | C = ${txC.date}",
                trenteJoursMs,
                txA.date - txC.date,
            )
        }

    // ==========================================================================================
    // D-4 — Relecture après fermeture de la base (CA-22, I-11)
    // ==========================================================================================

    @Test
    fun `given une transaction enregistree depuis une proposition when la base est rouverte then la date relue est celle de la detection`() =
        runTest {
            val fichier = File(dossierTemporaire.root, "tc114-persistance.db")

            val premiereOuverture = ouvrirSurFichier(fichier)
            val idTransaction = try {
                preparerReferentiel(premiereOuverture)
                val depotFichier = NotificationDetectionRepository(
                    premiereOuverture.detectedTransactionProposalDao(),
                )
                val enregistrer = construireEnregistrement(premiereOuverture, depotFichier)
                val idProposition =
                    (depotFichier.upsertOrMerge(propositionA, fenetreMs, tDetect) as MergeResult.Inserted)
                        .proposalId
                val prefill = buildEdition(propositionA, compteRef, categorieRef)
                (enregistrer(idProposition, prefill) as SaveResult.Created).transactionId
            } finally {
                premiereOuverture.close()
            }

            val secondeOuverture = ouvrirSurFichier(fichier)
            try {
                val relue = requireNotNull(secondeOuverture.transactionDao().getById(idTransaction)).transaction

                // L'oracle porte sur l'**instant**, en millisecondes depuis l'époque, jamais sur une
                // chaîne formatée ni sur un jour calendaire seul : un décalage de fuseau à la
                // relecture passerait sinon inaperçu.
                assertEquals(
                    "CA-22 / I-11 : la date relue après réouverture est l'instant de détection " +
                        "| transaction relue = $relue",
                    tDetect,
                    relue.date,
                )
                assertNotEquals("CA-22 / I-11 : la date relue n'est pas T_SAVE", tSave, relue.date)
            } finally {
                secondeOuverture.close()
            }
        }

    // ==========================================================================================
    // D-5 — Détection à la dernière minute d'un jour (CA-27, I-11)
    // ==========================================================================================

    @Test
    fun `given une detection a la derniere minute du 28 fevrier when elle est enregistree deux semaines plus tard then le jour calendaire reste celui de la detection`() =
        runTest {
            val idProposition = inserer(propositionB)
            val prefill = buildEdition(propositionB, compteRef, categorieRef)

            val idTransaction =
                (enregistrerDepuisProposition(idProposition, prefill) as SaveResult.Created).transactionId
            val tx = requireNotNull(db.transactionDao().getById(idTransaction)).transaction

            assertEquals(
                "CA-27 / I-11 : la date persistée est le 28 février à 23 h 58, jour calendaire de la " +
                    "détection et non celui de l'enregistrement | transaction relue = $tx",
                tDetectFinDeJournee,
                tx.date,
            )
            // Le piège du cas : le jour calendaire bascule à une minute près. On le nomme.
            assertEquals(
                "CA-27 / I-11 : le jour calendaire relu à Paris est bien le 28 février",
                LocalDateTime.of(2026, 2, 28, 23, 58),
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(tx.date), zoneParis)
                    .toLocalDateTime(),
            )
        }

    // ==========================================================================================
    // D-6 — Date modifiée à la main avant enregistrement (P-11)
    // ==========================================================================================

    @Test
    fun `given une date pre remplie modifiee a la main when la transaction est enregistree then la date saisie est persistee`() =
        runTest {
            val idProposition = inserer(propositionA)
            val prefillModifie = buildEdition(propositionA, compteRef, categorieRef)
                .copy(date = tSaisieManuelle)

            val idTransaction =
                (enregistrerDepuisProposition(idProposition, prefillModifie) as SaveResult.Created)
                    .transactionId
            val tx = requireNotNull(db.transactionDao().getById(idTransaction)).transaction

            // P-11 : la date pré-remplie est une valeur par défaut **modifiable**, pas une valeur
            // verrouillée. Ce cas interdit une implémentation qui réécrirait la date depuis la
            // proposition en ignorant la saisie de l'utilisateur.
            assertEquals(
                "P-11 : la date saisie à la main est persistée telle quelle | transaction relue = $tx",
                tSaisieManuelle,
                tx.date,
            )
            assertNotEquals(
                "P-11 : l'enregistrement ne doit pas réécrire la date depuis la proposition",
                tDetect,
                tx.date,
            )
        }

    // ------------------------------------------------------------------ Outillage local

    private suspend fun inserer(proposition: Proposal): Long =
        (depot.upsertOrMerge(proposition, fenetreMs, proposition.detectedAt) as MergeResult.Inserted)
            .proposalId

    /** Instantané de la table des transactions, lignes supprimées comprises. */
    private fun transactions(base: LopDatabase = db): List<LigneTransaction> =
        base.query(
            "SELECT id, title, amount, date, paidAt FROM transactions ORDER BY id",
            arrayOf<Any>(),
        ).use { curseur ->
            buildList {
                while (curseur.moveToNext()) {
                    add(
                        LigneTransaction(
                            id = curseur.getLong(0),
                            title = curseur.getString(1),
                            amount = curseur.getLong(2),
                            date = curseur.getLong(3),
                            paidAt = if (curseur.isNull(4)) null else curseur.getLong(4),
                        ),
                    )
                }
            }
        }

    private fun ouvrirSurFichier(fichier: File): LopDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
            fichier.absolutePath,
        ).allowMainThreadQueries().build()

    /** Recrée compte et catégorie dans une base tierce, en réutilisant les identifiants symboliques. */
    private suspend fun preparerReferentiel(base: LopDatabase) {
        compteRef = base.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 100_000L,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        categorieRef = base.categoryDao().upsert(
            CategoryEntity(
                name = "Courses",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "cart",
            ),
        )
    }

    private fun construireEnregistrement(
        base: LopDatabase,
        propositions: NotificationDetectionRepository,
    ): SaveTransactionFromProposalUseCase {
        val transactionRepo = TransactionRepository(base.transactionDao(), base.recurringSeriesDao())
        val syncProgress = SyncProgressUseCase(
            transactionRepo,
            GoalRepository(base.goalDao()),
            LoanRepository(base.loanDao()),
        )
        return SaveTransactionFromProposalUseCase(
            CreateTransactionUseCase(
                transactionRepo,
                SaveTransactionUseCase(transactionRepo, syncProgress),
            ),
            propositions,
            AccountRepository(base.accountDao()),
            object : AtomicWriter {
                override suspend fun <T> atomically(block: suspend () -> T): T =
                    base.withTransaction(block)
            },
        )
    }

    private data class LigneTransaction(
        val id: Long,
        val title: String,
        val amount: Long,
        val date: Long,
        val paidAt: Long?,
    )
}
