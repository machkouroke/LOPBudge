package com.lop.budget.domain.usecase.detection

import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.ProposalStatus
import com.lop.budget.notifications.NotificationSnapshot
import com.lop.budget.notifications.ParseResult
import com.lop.budget.notifications.ParsedPayment
import com.lop.budget.notifications.PaymentParser
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * TC-107 — Décision de détection : réglages, sources, fenêtre de regroupement (US LOP-54).
 *
 * ## Niveau et chaîne réellement exercée
 *
 * Test **unitaire JVM pur** : ni Room, ni Robolectric, ni `Context`, ni service en cours
 * d'exécution. L'absence même de runner Android est l'une des preuves demandées (CA-25), et T-06
 * l'assert explicitement plutôt que de la laisser à la bonne volonté d'un relecteur.
 *
 * ```
 * HandlePaymentNotificationUseCase (réel)
 *   → DetectionSettings   (doublure stricte)
 *   → PaymentParser       (doublure stricte)
 *   → ProposalRepository  (doublure stricte)
 *   → DetectionNotifier   (doublure stricte)
 *   → java.time.Clock     (horloge pilotable écrite ici, pas un mock)
 * ```
 *
 * Les cinq dépendances sont aux frontières du système testé, jamais dedans. Aucun `relaxed`, aucun
 * `spyk`, aucun `any()` dans un stub ou une vérification positive.
 *
 * ## Ce que ce niveau ne peut pas prouver — lire avant de juger T-05
 *
 * La fiche demandait de prouver la bascule de la fenêtre de regroupement : à 119 secondes on
 * regroupe, à 121 on crée deux propositions. **Ce niveau ne peut pas le prouver.** La décision
 * « doublon ou pas » appartient au dépôt, et le dépôt est ici une doublure : la programmer pour
 * répondre « regroupé » à 119 s puis relire cette réponse serait un oracle tautologique, c'est-à-dire
 * un test qui vérifie ce qu'il vient lui-même de dicter.
 *
 * Ce que le use case décide réellement, et que T-05 assert donc à la place :
 *
 * 1. il transmet au dépôt **exactement 120 000 ms** de fenêtre ;
 * 2. il transmet **exactement l'instant de l'horloge injectée**, ce qui est la formulation vérifiable
 *    de « la bascule est asservie à l'horloge, jamais à l'heure courante » ;
 * 3. il traduit fidèlement la réponse du dépôt en [DetectionOutcome], et n'avertit pas l'utilisateur
 *    sur un regroupement.
 *
 * La bascule elle-même, sur vraie base, est portée par **TC-109 T-02 et T-03**. Fiche Notion révisée
 * en ce sens le 15 septembre 2026.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 *
 * | Cas   | CA / invariant       | Fonction de production                                  |
 * |-------|----------------------|---------------------------------------------------------|
 * | T-01  | CA-04, I-4, I-5      | `invoke`, garde `isNotificationDetectionEnabledOnce`      |
 * | T-02  | CA-05, CA-26, I-5    | `invoke`, garde `isAllowedNotificationSource`             |
 * | T-03  | CA-06, I-1, I-3      | `invoke`, branche nominale → `upsertOrMerge` + notifieur  |
 * | T-04  | CA-10, P-7           | `invoke`, branche incertaine → notifieur muet             |
 * | T-05a | CA-12, I-7           | `invoke`, transmission fenêtre/horloge + mapping `Merged` |
 * | T-05b | CA-13, I-7           | idem, mapping `Inserted` hors fenêtre                     |
 * | T-06  | CA-23, CA-25, I-10   | signature de `HandlePaymentNotificationUseCase`           |
 *
 * ## Anomalies
 *
 * Aucune ouverte par cette fiche. Le compteur d'occurrences n'est toujours pas incrémenté par le
 * dépôt réel (`NotificationDetectionRepository.upsertOrMerge`), mais ce défaut est **invisible à ce
 * niveau** où le dépôt est doublé : il est porté par **TC-109 T-02**, et le citer ici reviendrait à
 * compter deux fois la même cause racine.
 *
 * ## Hors périmètre — ce que ce fichier ne vérifie pas
 *
 * - **Ce qui est réellement écrit en base** : une doublure ne voit aucun `INSERT`. Porté par TC-109.
 * - **Le contenu de l'analyse du texte** : le parseur est doublé, il *fabrique* son verdict. Aucun
 *   oracle d'ici ne porte sur ce qu'il produit. Porté par TC-108.
 * - **CA-23, la part exécution** : T-06 prouve qu'aucune signature de la chaîne n'expose d'accès
 *   distant. Il ne prouve pas qu'aucune implémentation n'en fait — cela relève d'une vérification
 *   sur appareil, et la fiche le classe hors périmètre.
 * - **L'appel effectif du rappel Android** et les autorisations système (CA-01 à CA-03, CA-24) :
 *   vérification manuelle sur appareil.
 * - **Le compteur d'en-tête** (CA-15) : aucun écran n'est monté ici.
 */
class HandlePaymentNotificationTest {

    // ------------------------------------------------------------------ Jeu de données (JDD)

    private val zoneParis: ZoneId = ZoneId.of("Europe/Paris")

    /**
     * Horodatage de référence de l'horloge figée (`T0` de la fiche).
     *
     * Date fixe et nommée, indépendante d'aujourd'hui : aucun attendu de ce fichier n'est calculé
     * depuis l'heure courante.
     */
    private val t0: Long = ZonedDateTime
        .of(LocalDateTime.of(2026, 3, 10, 21, 0, 0), zoneParis)
        .toInstant()
        .toEpochMilli()

    /**
     * Fenêtre de regroupement : 2 minutes (P-2), **déclarée localement**.
     *
     * TC-109 porte aujourd'hui la même valeur et la redéclare de son côté. C'est délibéré : cette
     * durée conditionne une cardinalité dans chacun des deux fichiers, et un fixture partagé la
     * rendrait modifiable par un ticket tiers sans que ni l'un ni l'autre ne s'en aperçoive.
     */
    private val fenetreRegroupementMs = 120_000L

    private val paquetGoogleWallet = "com.google.android.apps.walletnfcrel"
    private val paquetMessagerie = "com.whatsapp"

    /**
     * Clés de regroupement **fabriquées par le parseur doublé**, et volontairement sans ressemblance
     * avec le texte de la notification.
     *
     * C'est la fixture discriminante de ce fichier : si le use case recalculait la clé lui-même à
     * partir du montant ou du texte, la valeur écrite ne pourrait pas être celle-ci.
     */
    private val cleK1 = "K-1-cle-opaque-fabriquee-par-le-parseur"
    private val cleK2 = "K-2-cle-opaque-fabriquee-par-le-parseur"

    /** Identifiants alloués par le dépôt. Éloignés de 0 et de 1 : aucun repli du code ne coïncide. */
    private val idProposeOk = 4_201L
    private val idProposeIncertain = 4_202L
    private val idProposeHorsFenetre = 4_203L

    /**
     * Instantané reconnu. `postedAtMillis` est **différent** de `t0`, et c'est voulu : CA-06 et la
     * fiche exigent que la date de détection vienne de l'horloge injectée. Si le use case recopiait
     * l'horodatage de la notification, T-03 deviendrait rouge.
     */
    private val instantaneOk = NotificationSnapshot(
        sourcePackage = paquetGoogleWallet,
        title = "Google Wallet",
        text = "Carrefour 12,50 €",
        postedAtMillis = t0 - 7_000L,
    )

    private val instantaneAutreSource = NotificationSnapshot(
        sourcePackage = paquetMessagerie,
        title = "Maman",
        text = "Paiement de 12,50 € bien reçu",
        postedAtMillis = t0 - 7_000L,
    )

    private val instantaneIncertain = NotificationSnapshot(
        sourcePackage = paquetGoogleWallet,
        title = "Google Wallet",
        text = "Transaction 12,50",
        postedAtMillis = t0 - 7_000L,
    )

    private val paiementOk = ParsedPayment(
        amountCents = 1_250L,
        currency = "EUR",
        label = "Carrefour",
        cardName = "Visa ••1234",
        normalizedText = "carrefour",
    )

    private val paiementIncertain = ParsedPayment(
        amountCents = 1_250L,
        currency = null,
        label = "Transaction",
        cardName = null,
        normalizedText = "transaction",
    )

    private val confianceCertaine = 0.94f
    private val confianceIncertaine = 0.55f

    /** Texte brut attendu sur la proposition (P-9) : titre et texte joints par « • ». */
    private val texteBrutAttendu = "Google Wallet • Carrefour 12,50 €"

    // ------------------------------------------------------------------ Montage

    private lateinit var settings: DetectionSettings
    private lateinit var parser: PaymentParser
    private lateinit var proposals: ProposalRepository
    private lateinit var notifier: DetectionNotifier
    private lateinit var horloge: HorlogePilotable
    private lateinit var useCase: HandlePaymentNotificationUseCase

    /** Propositions réellement soumises au dépôt, dans l'ordre. Relevé, pas `any()`. */
    private val propositionsEcrites = mutableListOf<Proposal>()

    /** Propositions réellement annoncées à l'utilisateur, dans l'ordre. */
    private val propositionsNotifiees = mutableListOf<Proposal>()

    @Before
    fun setUp() {
        settings = mockk()
        parser = mockk()
        proposals = mockk()
        notifier = mockk()
        horloge = HorlogePilotable(Instant.ofEpochMilli(t0), zoneParis)

        propositionsEcrites.clear()
        propositionsNotifiees.clear()

        // Seul stub global : l'avertissement, capturé pour être compté et relu. Compter les éléments
        // capturés remplace un `verify(exactly = n) { notifier.notifyProposal(any()) }`, interdit ici.
        every { notifier.notifyProposal(capture(propositionsNotifiees)) } just Runs

        useCase = HandlePaymentNotificationUseCase(
            settings = settings,
            parser = parser,
            proposals = proposals,
            notifier = notifier,
            clock = horloge,
        )
    }

    // ------------------------------------------------------------------ T-01 — CA-04, I-4, I-5

    @Test
    fun `given la detection desactivee when une notification de paiement arrive then rien n est analyse ni ecrit`() =
        runTest {
            coEvery { settings.isNotificationDetectionEnabledOnce() } returns false

            val resultat = useCase(instantaneOk)

            assertEquals(
                "CA-04 / I-5 : détection désactivée, le use case doit rendre Skipped(DETECTION_DISABLED)",
                DetectionOutcome.Skipped(SkipReason.DETECTION_DISABLED),
                resultat,
            )

            // I-4 : un texte de notification ne doit pas même être analysé quand la détection est
            // éteinte. Le parseur muet est l'oracle, pas un détail d'implémentation.
            verify { parser wasNot Called }
            verify { proposals wasNot Called }
            verify { notifier wasNot Called }

            // La source n'a pas non plus à être consultée : la garde d'activation vient en premier.
            coVerify(exactly = 1) { settings.isNotificationDetectionEnabledOnce() }
            confirmVerified(settings, parser, proposals, notifier)
        }

    // ------------------------------------------------------------------ T-02 — CA-05, CA-26, I-5

    @Test
    fun `given une source non autorisee when sa notification contient un montant then elle est ignoree sans analyse`() =
        runTest {
            coEvery { settings.isNotificationDetectionEnabledOnce() } returns true
            every { settings.isAllowedNotificationSource(paquetMessagerie) } returns false

            val resultat = useCase(instantaneAutreSource)

            assertEquals(
                "CA-05 / CA-26 : le filtrage de source appartient au use case, pas au service Android",
                DetectionOutcome.Skipped(SkipReason.SOURCE_NOT_ALLOWED),
                resultat,
            )

            verify { parser wasNot Called }
            verify { proposals wasNot Called }
            verify { notifier wasNot Called }

            coVerifyOrder {
                settings.isNotificationDetectionEnabledOnce()
                settings.isAllowedNotificationSource(paquetMessagerie)
            }
            confirmVerified(settings, parser, proposals, notifier)
        }

    // ------------------------------------------------------------------ T-03 — CA-06, I-1, I-3

    @Test
    fun `given une notification Wallet reconnue when la detection est active then une proposition complete est ecrite et annoncee`() =
        runTest {
            armerChaineNominale(MergeResult.Inserted(idProposeOk), t0)

            val resultat = useCase(instantaneOk)

            assertEquals(
                "CA-06 : une notification reconnue produit une proposition certaine",
                DetectionOutcome.Created(idProposeOk, uncertain = false),
                resultat,
            )

            assertEquals(
                "CA-06 : exactement une écriture soumise au dépôt",
                1,
                propositionsEcrites.size,
            )

            val ecrite = propositionsEcrites.single()
            assertEquals("CA-06 : paquet source", paquetGoogleWallet, ecrite.sourcePackage)
            assertEquals("CA-06 / I-3 : montant en centimes entiers", 1_250L, ecrite.amountCents)
            assertEquals("CA-06 : devise détectée", "EUR", ecrite.currency)
            assertEquals("CA-06 : libellé", "Carrefour", ecrite.label)
            assertEquals("CA-06 : nom de carte", "Visa ••1234", ecrite.cardName)
            assertEquals("P-9 : texte brut conservé", texteBrutAttendu, ecrite.fullText)
            assertEquals(
                "CA-06 / P-2 : la clé vient de PaymentParser.dedupeKey, jamais d'un recalcul local",
                cleK1,
                ecrite.dedupeKey,
            )
            assertEquals("CA-06 : statut en attente", ProposalStatus.PENDING, ecrite.status)
            assertEquals("CA-06 : score de confiance", confianceCertaine, ecrite.confidence, 0f)
            assertEquals(
                "CA-06 : horodatage = T0 de l'horloge injectée, pas le postedAt de la notification",
                t0,
                ecrite.detectedAt,
            )

            // P-7 : une proposition certaine interrompt l'utilisateur, une seule fois.
            assertEquals("P-7 : exactement un avertissement", 1, propositionsNotifiees.size)
            assertEquals(
                "P-7 : l'avertissement porte l'identifiant alloué par le dépôt, pas 0",
                idProposeOk,
                propositionsNotifiees.single().id,
            )

            // Ordre causal : lire les réglages avant d'analyser, écrire avant d'annoncer.
            coVerifyOrder {
                settings.isNotificationDetectionEnabledOnce()
                settings.isAllowedNotificationSource(paquetGoogleWallet)
                parser.parse(instantaneOk)
                parser.dedupeKey(paquetGoogleWallet, paiementOk)
                proposals.upsertOrMerge(ecrite, fenetreRegroupementMs, t0)
                notifier.notifyProposal(propositionsNotifiees.single())
            }
            confirmVerified(settings, parser, proposals, notifier)
        }

    // ------------------------------------------------------------------ T-04 — CA-10, P-7

    @Test
    fun `given une notification jugee incertaine when elle est traitee then la proposition est ecrite sans avertir`() =
        runTest {
            coEvery { settings.isNotificationDetectionEnabledOnce() } returns true
            every { settings.isAllowedNotificationSource(paquetGoogleWallet) } returns true
            coEvery { parser.parse(instantaneIncertain) } returns
                ParseResult.Uncertain(paiementIncertain, confianceIncertaine)
            every { parser.dedupeKey(paquetGoogleWallet, paiementIncertain) } returns cleK2
            coEvery {
                proposals.upsertOrMerge(capture(propositionsEcrites), fenetreRegroupementMs, t0)
            } returns MergeResult.Inserted(idProposeIncertain)

            val resultat = useCase(instantaneIncertain)

            assertEquals(
                "CA-10 : une analyse incertaine produit bien une proposition, marquée incertaine",
                DetectionOutcome.Created(idProposeIncertain, uncertain = true),
                resultat,
            )

            assertEquals("CA-10 : exactement une écriture", 1, propositionsEcrites.size)
            val ecrite = propositionsEcrites.single()
            assertEquals("CA-10 : statut incertain", ProposalStatus.UNCERTAIN, ecrite.status)
            assertEquals("CA-10 : clé du parseur", cleK2, ecrite.dedupeKey)
            assertEquals("CA-10 : score conservé", confianceIncertaine, ecrite.confidence, 0f)

            // P-7 : les incertaines alimentent le compteur sans transformer le bruit en interruption.
            assertEquals(
                "P-7 : zéro avertissement pour une proposition incertaine",
                0,
                propositionsNotifiees.size,
            )
            verify { notifier wasNot Called }
        }

    // ------------------------------------------------------------------ T-05a — CA-12, I-7

    @Test
    fun `given une seconde notification identique dans la fenetre when le depot la regroupe then le use case rend Merged sans avertir`() =
        runTest {
            val instantSecondeNotification = t0 + 119_000L

            armerChaineNominale(MergeResult.Inserted(idProposeOk), t0)
            // Second appel : mêmes réglages, même analyse, seul l'instant transmis change. Le stub
            // n'est armé QUE pour cet instant précis — si le use case lisait l'heure courante au lieu
            // de l'horloge injectée, aucune réponse ne serait trouvée et le cas échouerait.
            coEvery {
                proposals.upsertOrMerge(
                    capture(propositionsEcrites),
                    fenetreRegroupementMs,
                    instantSecondeNotification,
                )
            } returns MergeResult.Merged(idProposeOk, occurrences = 2)

            val premier = useCase(instantaneOk)
            horloge.avancerDe(Duration.ofMillis(119_000L))
            val second = useCase(instantaneOk)

            assertEquals(
                "CA-06 : la première notification crée la proposition",
                DetectionOutcome.Created(idProposeOk, uncertain = false),
                premier,
            )
            assertEquals(
                "CA-12 / I-7 : la réponse de regroupement du dépôt est rendue telle quelle, compteur compris",
                DetectionOutcome.Merged(idProposeOk, occurrences = 2),
                second,
            )

            // Le use case soumet bien les deux notifications ; c'est le dépôt qui tranche. La
            // cardinalité des LIGNES écrites est l'affaire de TC-109 T-02, pas de ce niveau.
            assertEquals("deux soumissions au dépôt, une par notification", 2, propositionsEcrites.size)

            // P-2 : la fenêtre transmise est exactement celle de la convention, et les deux instants
            // viennent de l'horloge injectée. C'est la formulation vérifiable ici de « la bascule est
            // asservie à l'horloge, jamais à l'heure courante ».
            coVerify(exactly = 1) {
                proposals.upsertOrMerge(propositionsEcrites[0], fenetreRegroupementMs, t0)
            }
            coVerify(exactly = 1) {
                proposals.upsertOrMerge(
                    propositionsEcrites[1],
                    fenetreRegroupementMs,
                    instantSecondeNotification,
                )
            }

            // P-7 : un regroupement n'interrompt pas une seconde fois l'utilisateur.
            assertEquals(
                "CA-12 / P-7 : un seul avertissement pour deux notifications regroupées",
                1,
                propositionsNotifiees.size,
            )
        }

    // ------------------------------------------------------------------ T-05b — CA-13, I-7

    @Test
    fun `given une seconde notification identique hors de la fenetre when elle est traitee then le use case rend Created`() =
        runTest {
            val instantSecondeNotification = t0 + 121_000L

            armerChaineNominale(MergeResult.Inserted(idProposeOk), t0)
            coEvery {
                proposals.upsertOrMerge(
                    capture(propositionsEcrites),
                    fenetreRegroupementMs,
                    instantSecondeNotification,
                )
            } returns MergeResult.Inserted(idProposeHorsFenetre)

            useCase(instantaneOk)
            horloge.avancerDe(Duration.ofMillis(121_000L))
            val second = useCase(instantaneOk)

            assertEquals(
                "CA-13 / I-7 : hors fenêtre, la réponse de création du dépôt est rendue telle quelle",
                DetectionOutcome.Created(idProposeHorsFenetre, uncertain = false),
                second,
            )
            assertEquals("deux soumissions au dépôt", 2, propositionsEcrites.size)
            assertEquals(
                "CA-13 : deux propositions distinctes, donc deux avertissements",
                2,
                propositionsNotifiees.size,
            )

            coVerify(exactly = 1) {
                proposals.upsertOrMerge(
                    propositionsEcrites[1],
                    fenetreRegroupementMs,
                    instantSecondeNotification,
                )
            }
        }

    // ------------------------------------------------------------------ T-06 — CA-23, CA-25, I-10

    @Test
    fun `given le use case de detection when on inspecte sa signature then aucun type Android ni acces distant n y apparait`() {
        val constructeur = HandlePaymentNotificationUseCase::class.java.declaredConstructors.single()
        val methodeInvoke = HandlePaymentNotificationUseCase::class.java.declaredMethods
            .single { it.name == "invoke" }

        val typesDeSignature = buildList {
            addAll(constructeur.parameterTypes)
            addAll(methodeInvoke.parameterTypes)
            add(methodeInvoke.returnType)
        }

        // CA-25 / I-10 : la signature ne mentionne aucun type Android. C'est ce qui permet d'appeler
        // ce use case depuis ce fichier, sans émulateur, sans Context et sans service.
        typesDeSignature.forEach { type ->
            assertTrue(
                "CA-25 / I-10 : ${type.name} est un type Android dans la signature du use case de détection",
                !type.name.startsWith("android.") && !type.name.startsWith("androidx."),
            )
        }

        // CA-23 : aucune des dépendances injectées n'expose d'accès distant dans son contrat. Cela
        // prouve la forme, pas l'exécution : qu'aucune implémentation n'émette d'appel réseau relève
        // d'une vérification sur appareil, explicitement hors périmètre de cette fiche.
        val paquetsDistants = listOf("java.net.", "javax.net.", "okhttp3.", "retrofit2.", "java.net.http.")
        constructeur.parameterTypes.forEach { dependance ->
            dependance.declaredMethods.forEach { methode ->
                (methode.parameterTypes.toList() + methode.returnType).forEach { type ->
                    assertTrue(
                        "CA-23 / I-4 : ${dependance.simpleName}.${methode.name} expose ${type.name}, " +
                            "un type de communication distante, dans le contrat de la détection",
                        paquetsDistants.none { prefixe -> type.name.startsWith(prefixe) },
                    )
                }
            }
        }

        // CA-25, la preuve par le montage : ce fichier ne monte aucun runner Android. Si quelqu'un
        // ajoutait Robolectric pour faire passer un cas, l'assertion ci-dessous tomberait.
        assertNull(
            "CA-25 : ce fichier doit rester exécutable sans runner Android ; un @RunWith y est apparu",
            HandlePaymentNotificationTest::class.java.getAnnotation(RunWith::class.java),
        )
    }

    // ------------------------------------------------------------------ Outillage local

    /**
     * Arme la chaîne nominale : détection active, source autorisée, analyse certaine, et une réponse
     * du dépôt pour l'instant [instantAttendu].
     *
     * Le stub du dépôt fixe la fenêtre et l'instant à leurs valeurs **exactes** : ce ne sont pas des
     * `any()` déguisés. Si le use case transmettait une autre fenêtre ou lisait l'heure système,
     * aucune réponse ne serait trouvée et le cas échouerait — c'est là que vit l'oracle de T-05.
     */
    private fun armerChaineNominale(reponse: MergeResult, instantAttendu: Long) {
        coEvery { settings.isNotificationDetectionEnabledOnce() } returns true
        every { settings.isAllowedNotificationSource(paquetGoogleWallet) } returns true
        coEvery { parser.parse(instantaneOk) } returns
            ParseResult.Payment(paiementOk, confianceCertaine)
        every { parser.dedupeKey(paquetGoogleWallet, paiementOk) } returns cleK1
        coEvery {
            proposals.upsertOrMerge(capture(propositionsEcrites), fenetreRegroupementMs, instantAttendu)
        } returns reponse
    }

    /**
     * Horloge pilotable : dépendance de production réelle, pas une doublure.
     *
     * [java.time.Clock.fixed] ne se déplace pas, et la fiche demande d'avancer explicitement le temps
     * entre deux notifications. Cette implémentation minimale le permet sans jamais consulter
     * l'horloge système.
     */
    private class HorlogePilotable(
        private var instantCourant: Instant,
        private val zone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = HorlogePilotable(instantCourant, zone)
        override fun instant(): Instant = instantCourant
        fun avancerDe(duree: Duration) {
            instantCourant = instantCourant.plus(duree)
        }
    }
}
