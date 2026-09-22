package com.lop.budget.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.MainActivity
import com.lop.budget.ui.common.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-126 — Ouverture par adresse externe : adresse inconnue et non-régression de l'extra `route`
 * (enabler LOP-172).
 *
 * ## Objectif
 * Prouver les deux volets de l'enabler qu'aucun test ne couvre : qu'une adresse externe
 * **inconnue** n'ouvre aucun écran arbitraire et ne fait pas planter l'application (CA-04), et que
 * le mécanisme d'ouverture **préexistant** continue de fonctionner à l'identique (CA-05).
 *
 * Source de vérité : l'enabler LOP-172, https://app.notion.com/p/3e250f34a8c5817c98c2cca1d308d6c9
 * **Les comportements actuels de `LopNavHost` et de `MainActivity` ne constituent pas l'oracle** :
 * ce sont eux qui sont jugés.
 *
 * ## Pourquoi cette classe existe alors que TC-125 ouvre déjà ses écrans par adresse externe
 * TC-125 en est un **consommateur**, pas un validateur : il n'ouvre que des adresses valides, et
 * son échec ne dirait pas si le défaut vient de l'adressage ou de l'écran. Les deux volets couverts
 * ici sont précisément ceux qui portent un risque de régression silencieuse.
 *
 * ## Niveau et chaîne exercée
 * Intégration instrumentée. Une adresse externe est résolue par le **graphe de navigation réel** à
 * partir d'un intent réel : ni un test JVM ni un ViewModel ne voient ce mécanisme.
 * ```
 * Intent VIEW (schéma lopbudge) ─┐
 *                                ├─→ MainActivity → LopNavHost → graphe de navigation → écran
 * Intent avec extra `route` ─────┘
 * ```
 *
 * ## Traçabilité — cas → CA → fonction de production
 * ```
 * E-01   CA-04, I-1   résolution d'un deep link non déclaré → repli sur la destination de départ
 * E-02   CA-04, I-1   idem, sur un motif déclaré mais non valorisé
 * E-03   CA-05        deep link de la notification → destination DETECTED
 * E-04   témoin       démarrage nu, sans adresse ni extra
 * ```
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - Le montage instrumenté du dépôt est réutilisé tel quel (`HiltTestRunner`, `TestAppModule` et sa
 *   base en mémoire). Il n'appartient pas à cette fiche.
 * - Une activité neuve par cas, fermée en `@After`.
 * - **Aucune donnée n'est insérée** : les cas portent sur la résolution d'adresse, pas sur un
 *   contenu. Les écrans visés se rendent sur une base vide.
 * - Attentes **bornées**, avec l'arbre d'accessibilité joint à toute expiration : sans lui,
 *   « écran jamais rendu » et « écran rendu mais mal identifié » produisent le même message.
 * - Aucun oracle ne porte sur une date : aucun fuseau ni locale n'est forcé.
 *
 * ## Risques relevés à la lecture (observations statiques, aucune couleur annoncée)
 * - `LopNavHost` lit `navController.graph` dans un `LaunchedEffect` déclenché par `startRoute`
 *   (lignes 99-107), et `startDestination` vaut `startRoute ?: Routes.HOME` (ligne 126). Un motif
 *   inconnu passé par **cet** mécanisme empêche le graphe d'être posé. À confirmer à l'exécution :
 *   une adresse externe, qui ne traverse pas `startRoute`, échappe-t-elle à ce chemin ? E-01 et
 *   E-02 sondent exactement cette frontière.
 * - Les deux mécanismes d'entrée coexistent. Leur convergence est hors périmètre de l'enabler,
 *   mais E-03 doit rester vert tant que l'extra `route` existe.
 *
 * ## Anomalie ouverte par cette classe, puis corrigée
 * - **ANO-5 (CA-05)** — https://app.notion.com/p/3e250f34a8c581969eead9518c175a4a
 *   Ouvrir l'application par l'extra `route` la faisait **planter au démarrage**, quelle que soit
 *   la route : `LopNavHost` lisait `navController.graph` dans un `LaunchedEffect`, donc avant que
 *   `NavHost` ne l'ait posé. C'était le seul mécanisme de la notification de détection, et aucun
 *   test ne le traversait — le flow Maestro TC-109 fait un `launchApp` nu.
 *
 *   Antériorité vérifiée : en retirant temporairement les `deepLinks` de LOP-172, E-03 échouait à
 *   l'identique. Le défaut préexistait à l'enabler.
 *
 *   **Corrigée le 21 septembre 2026 par migration**, et non par contournement : l'extra `route`,
 *   le paramètre `startRoute` et son `LaunchedEffect` ont été **supprimés**. La notification passe
 *   désormais par une adresse externe, seul mécanisme d'entrée. Le périmètre de LOP-172 et son
 *   CA-05 ont été amendés en conséquence.
 *
 * ## Résultats — 21 septembre 2026, SM-S938B (Galaxy S25 Ultra, Android 16 / API 36)
 * ```
 * E-01 ✔   E-02 ✔   E-03 ✔   E-04 ✔        4/4 verts
 * ```
 * Suite instrumentée complète : 9 tests, 0 échec, lancée **d'un seul bloc** — ce qui était
 * impossible tant que le plantage d'ANO-5 tuait le processus applicatif et faisait échouer en
 * cascade tous les tests suivants sur un message sans rapport.
 *
 * ### Preuves de sensibilité
 * ```
 * La destination de départ n'est plus l'accueil          → E-01, E-02, E-04 rouges  ✔
 * L'adresse de la notification n'est plus déclarée       → E-03 rouge               ✔
 * ```
 * Une troisième mutation était prévue par la fiche — « une adresse inconnue retombe sur la
 * première route déclarée », sous forme d'un deep link supplémentaire vers le détail. Elle **n'a
 * pas été détectée** par E-01 et la raison n'a pas été établie : l'adresse n'a pas été routée vers
 * le détail malgré un motif et des arguments compatibles. La sensibilité de
 * [assertNoArbitraryScreen] reste donc **non prouvée** ; celle de [assertLandsOnHome] l'est. À
 * reprendre, sans assouplir l'oracle entre-temps.
 *
 * ## Hors périmètre
 * - CA-01, CA-02 et CA-03 : couverts par **TC-125**, non redémontrés ici.
 * - Retrait ou convergence du mécanisme `startRoute`.
 * - Liens web et vérification de domaine : aucun domaine n'est associé à l'application.
 * - Reconstruction de la pile arrière après ouverture externe : aucun critère ne la spécifie.
 * - Le parcours de notification de bout en bout, porté par le flow Maestro TC-109.
 *
 * ## Exécution
 * `./gradlew :app:connectedDebugAndroidTest --tests "*ExternalEntryPointsTest*"`
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ExternalEntryPointsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /** Règle vide : chaque cas construit lui-même l'intent qu'il veut soumettre. */
    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    // ==============================================================================================
    // E-01 — Une adresse inconnue n'ouvre aucun écran arbitraire
    // ==============================================================================================

    /**
     * E-01 — Given une adresse portant le bon schéma mais un chemin qu'aucune route ne déclare,
     * When l'application est ouverte dessus, Then elle démarre sur son accueil, n'affiche ni détail
     * ni formulaire, et ne lève aucune exception (CA-04, I-1).
     */
    @Test
    fun e01_une_adresse_inconnue_retombe_sur_l_accueil_sans_ouvrir_d_ecran_arbitraire() {
        launchDeepLink(UNKNOWN_PATH)

        assertLandsOnHome("E-01", UNKNOWN_PATH)
        assertNoArbitraryScreen("E-01", UNKNOWN_PATH)
    }

    // ==============================================================================================
    // E-02 — Un motif déclaré mais non valorisé n'est pas une adresse valide
    // ==============================================================================================

    /**
     * E-02 — Given une adresse reprenant le **motif déclaré** au lieu d'une valeur — le trou laissé
     * non rempli —, When l'application est ouverte dessus, Then elle se comporte comme en E-01
     * (CA-04, I-1).
     *
     * Cas distinct de E-01, et non un doublon : une adresse **syntaxiquement proche** d'une route
     * déclarée emprunte une autre branche de résolution qu'une adresse franchement étrangère. Un
     * appariement trop permissif passerait E-01 et échouerait ici.
     */
    @Test
    fun e02_un_motif_declare_mais_non_valorise_n_est_pas_une_adresse_valide() {
        launchDeepLink(Routes.DETAIL)

        assertLandsOnHome("E-02", Routes.DETAIL)
        assertNoArbitraryScreen("E-02", Routes.DETAIL)
    }

    // ==============================================================================================
    // E-03 — Le mécanisme préexistant reste intact
    // ==============================================================================================

    /**
     * E-03 — Given l'adresse externe que construit réellement la notification de détection,
     * When l'application est ouverte dessus, Then l'écran des propositions est affiché sans passer
     * par l'accueil (CA-05).
     *
     * L'oracle porte sur l'**adresse exacte** produite par `AndroidDetectionNotifier`, pas sur une
     * adresse recomposée pour le test : les deux se construisent par `Routes.deepLinkPattern`, donc
     * un changement de schéma casse ce cas au lieu de passer inaperçu.
     */
    @Test
    fun e03_l_adresse_de_la_notification_ouvre_l_ecran_des_propositions_detectees() {
        launchDeepLink(Routes.DETECTED)

        awaitTag(
            TestTags.SCREEN_DETECTED,
            "E-03 — CA-05 : l'adresse de la notification devait ouvrir l'écran des propositions",
        )
        assertEquals(
            "E-03 — CA-05 : l'accueil ne devait pas être présenté à la place ; " +
                "l'adresse externe court-circuite la destination de départ",
            0,
            nodeCount(TestTags.SCREEN_HOME),
        )
    }

    // ==============================================================================================
    // E-04 — Témoin de démarrage
    // ==============================================================================================

    /**
     * E-04 — Given aucun intent particulier, When l'application est lancée, Then l'accueil est
     * affiché.
     *
     * Témoin **obligatoire** : sans lui, E-01 et E-02 ne distinguent pas « l'adresse a bien été
     * ignorée » de « l'application n'a pas démarré du tout ». Ce cas ne porte aucun CA, et c'est
     * assumé — il donne leur sens aux deux autres.
     */
    @Test
    fun e04_temoin_un_demarrage_nu_affiche_l_accueil() {
        launchWithoutEntryPoint()

        assertLandsOnHome("E-04", "aucune adresse")
    }

    // ==============================================================================================
    // Oracles partagés
    // ==============================================================================================

    /**
     * I-1 — une adresse non résolue retombe sur la destination de départ, qui est l'accueil.
     *
     * L'oracle vise un **nœud nommé** de l'accueil, jamais un comptage global à zéro : une
     * application qui ne se serait pas lancée satisferait ce dernier.
     */
    private fun assertLandsOnHome(label: String, submitted: String) {
        awaitTag(
            TestTags.SCREEN_HOME,
            "$label — CA-04 / I-1 : l'application devait démarrer sur son accueil après « $submitted »",
        )
    }

    /** I-1 — aucun écran arbitraire n'est présenté à la place. */
    private fun assertNoArbitraryScreen(label: String, submitted: String) {
        assertEquals(
            "$label — CA-04 / I-1 : aucune adresse inconnue ne doit ouvrir le détail d'une " +
                "transaction ; adresse soumise « $submitted »",
            0,
            nodeCount(TestTags.SCREEN_DETAIL),
        )
        assertEquals(
            "$label — CA-04 / I-1 : aucune adresse inconnue ne doit ouvrir le formulaire de " +
                "transaction ; adresse soumise « $submitted »",
            0,
            nodeCount(TestTags.SCREEN_EDIT),
        )
    }

    // ==============================================================================================
    // Points d'entrée
    // ==============================================================================================

    /** Ouvre l'application par un intent `VIEW`, exactement comme une notification ou le widget. */
    private fun launchDeepLink(route: String) = launch(
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse(Routes.deepLinkPattern(route)),
            targetContext(),
            MainActivity::class.java,
        ),
    )

    private fun launchWithoutEntryPoint() =
        launch(Intent(targetContext(), MainActivity::class.java))

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent)
        composeRule.waitForIdle()
    }

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    // ==============================================================================================
    // Lecture de l'écran
    // ==============================================================================================

    private fun nodeCount(tag: String): Int =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /**
     * Comptage **tolérant à l'absence d'arbre**, réservé aux prédicats d'attente.
     *
     * `ActivityScenario.launch` rend la main dès que l'activité est reprise, mais la hiérarchie
     * Compose n'est pas encore attachée à la fenêtre — `MainActivity` installe un écran de
     * démarrage avant de poser son contenu. Interroger l'arbre à cet instant lève une exception au
     * lieu de rendre zéro, ce qui ferait échouer l'attente au premier tour au lieu de la faire
     * patienter. Point de montage, jamais un oracle.
     */
    private fun nodeCountOrNone(tag: String): Int =
        runCatching { nodeCount(tag) }.getOrDefault(0)

    /**
     * Attente **bornée**, convertie en échec nommé.
     *
     * L'arbre d'accessibilité de toutes les fenêtres est joint au message : sans lui, « écran
     * jamais rendu » et « écran rendu mais identifié autrement » sont indiscernables, et chaque
     * hypothèse coûte un cycle complet de construction et d'installation.
     */
    private fun awaitTag(tag: String, failureMessage: String) {
        try {
            composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { nodeCountOrNone(tag) > 0 }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "$failureMessage (attente de $TIMEOUT_MS ms dépassée : ${timeout.message})\n" +
                    "Arbre sémantique au moment de l'échec :\n${semanticsDump()}",
            )
        }
    }

    private fun semanticsDump(): String = runCatching {
        composeRule.onAllNodes(isRoot()).printToString(maxDepth = MAX_DUMP_DEPTH)
    }.getOrElse { "arbre indisponible : ${it.message}" }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val MAX_DUMP_DEPTH = 20

        /** Chemin qu'aucune route du graphe ne déclare. */
        const val UNKNOWN_PATH = "ecran-inexistant/42"
    }
}
