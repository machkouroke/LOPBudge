package com.lop.budget.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.CurrencyCatalog
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SearchCurrenciesUseCase
import com.lop.budget.notifications.QwenDownloadManager
import com.lop.budget.ui.screens.home.HomeUiState
import com.lop.budget.ui.screens.settings.SettingsUiState
import com.lop.budget.ui.screens.settings.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.Locale
import java.util.TimeZone

/**
 * TC-105 — Préférence de devise : défaut, écriture, persistance et propagation.
 * **Niveau intégration, stockage de préférences réel** (DataStore sur le répertoire temporaire que
 * Robolectric alloue à chaque méthode de test), plus une partie ViewModel.
 *
 * Chaîne exercée : `SettingsRepository` **réel** → DataStore **réel** sur fichier ;
 * `SettingsViewModel` **réel** → `SearchCurrenciesUseCase` **réel** → `CurrencyCatalog` **réel**.
 * La chaîne de persistance n'est doublée nulle part : un faux dépôt ne voit pas ce qui est
 * réellement écrit. Seule doublure, stricte : `QwenDownloadManager`, sans rapport avec la devise.
 *
 * Source de vérité : CA-01, CA-06, CA-07, CA-08, CA-11, CA-13 et les invariants I-1, I-3, I-5 de
 * l'US LOP-58.
 * **Les comportements actuels de `SettingsRepository` et `SettingsViewModel` ne constituent pas
 * l'oracle.** Les attendus viennent de la fiche, jamais du code.
 *
 * ### cas → CA / invariant → production
 * ```
 * T-01   S-VIDE, lecture seule           CA-01, I-3, I-5   SettingsRepository.currency, SettingsUiState.currency
 * T-02   saisie sans sélection           CA-07, I-5        SettingsViewModel.onCurrencyQueryChange/.onCurrencySheetDismissed
 * T-03   sélection, observation ouverte   CA-06, CA-13, I-3 SettingsViewModel.setCurrency → SettingsRepository.setCurrency
 * T-04a  choix puis redémarrage          CA-08             SettingsRepository.currency relu par une instance neuve
 * T-04b  S-USD puis redémarrage          CA-08             idem
 * T-05   S-CORROMPU ×4                   CA-11, I-1        CurrencyCatalog.byCodeOrDefault à la lecture
 * T-06   codes hors catalogue            I-1               SettingsRepository.setCurrency — garde d'écriture
 * T-07   instantané Room avant/après     I-2, CA-09        aucune écriture en base au changement de devise
 * T-08   recensement des lectures        CA-13, I-3        analyse, restituée ci-dessous
 * ```
 *
 * ### Montage : pourquoi le stockage est assemblé ici
 *
 * `SettingsRepository` obtient son stockage par le délégué `private val Context.dataStore`, qui met
 * en cache **une seule** instance par processus et la construit sur `FileStorage`. Deux obstacles en
 * découlent, tous deux mesurés par sonde jetable les 16 et 17 septembre 2026 :
 *
 * 1. **Le redémarrage.** Reconstruire `SettingsRepository(context)` rend le stockage déjà chargé en
 *    mémoire : relire ne prouverait rien. [monterDepot] injecte une instance neuve dans le délégué,
 *    après avoir arrêté la précédente, si bien que le dépôt qui en sort relit réellement le disque.
 * 2. **L'écriture répétée.** `FileStorage` publie chaque écriture par `File.renameTo`, qui **sous
 *    Windows ne peut pas écraser un fichier existant** — mesuré : `renameTo` rend `false` et la
 *    cible garde son ancien contenu. La 1re écriture passait, les suivantes levaient
 *    `IOException: Unable to rename`. Le stockage est donc assemblé sur **okio**
 *    (`OkioStorage` + `FileSystem.SYSTEM`, qui publie par `Files.move(ATOMIC_MOVE)` et sait
 *    remplacer — mesuré aussi). Sur Android les deux publient à l'identique ; cette substitution
 *    ne compense qu'une limite du poste de développement, exactement comme forcer un fuseau.
 *
 * Ce qui reste **réel** : le fichier, le dépôt, le ViewModel, et `PreferencesSerializer` — le
 * sérialiseur de production lui-même. Seule la primitive de publication du fichier change, et elle
 * appartient à DataStore, pas au code testé. Aucune ligne de production n'a été ajoutée.
 *
 * **Piège écarté au passage.** Quand une écriture échouait, `SettingsRepository.currency` annonçait
 * malgré tout la nouvelle valeur : l'état en mémoire avançait sans que le fichier bouge. Un oracle
 * posé sur le flux seul aurait donc viré au vert sur une écriture perdue. Les cas qui prouvent une
 * persistance relisent pour cette raison le **disque**, par [redemarrer].
 *
 * Le semis d'une préférence corrompue passe **directement** par le DataStore, jamais par
 * `setCurrency` : la faire valider par l'API qui la refuse serait tautologique.
 *
 * Robolectric alloue par ailleurs un `filesDir` neuf à chaque méthode : aucun état ne fuit d'un cas
 * à l'autre.
 *
 * ### Points de synchronisation, distingués des oracles
 * Les flux de préférences font de l'E/S réelle, que l'horloge virtuelle de `runTest` n'attend pas.
 * [attendreDevise] fait donc tourner l'ordonnanceur de test **et** laisse l'E/S avancer, sous un
 * délai borné déclaré localement ([DELAI_OBSERVATION_MS]). Une absence d'émission y devient un échec
 * **métier** nommant le CA et le dernier état observé, jamais un délai brut. L'attente initiale de
 * T-03 porte sur l'existence d'une émission, pas sur son contenu.
 *
 * ### T-08 — recensement des lectures de la devise (analyse, pas un test)
 * Effectué le 16 septembre 2026 sur la branche `main`. **Tous** les consommateurs lisent
 * `SettingsRepository.currency`, aucune copie locale n'est persistée :
 * `HomeViewModel:100`, `AccountsViewModel:35`, `AccountsManageViewModel:32`,
 * `AccountDetailViewModel:46`, `AnalyticsViewModel:80`, `MonthlyTransactionsViewModel:185`,
 * `SearchViewModel:60`, `GoalsViewModel:30`, `SettingsViewModel:42`,
 * `TransactionEditViewModel:346`, `TransactionActionViewModel:300`, `AiViewModel:100`, et
 * `BalanceWidget.provideGlance` pour le widget d'écran d'accueil.
 *
 * Une seule violation de I-3, et l'US l'avait anticipée : **ANO-A**
 * (<https://app.notion.com/p/3dd50f34a8c5812b97b3ca0c243f9010>), `HomeUiState.currency = "USD"`
 * alors que toutes les autres valeurs initiales et le dépôt répondent `EUR`. L'écran d'accueil
 * affichait donc des dollars le temps que le flux émette.
 *
 * **Corrigée le 17 septembre 2026**, sur décision explicite de l'auteur de l'US : la valeur
 * initiale vaut désormais `CurrencyCatalog.default.code`. L'US plaçait ce défaut hors de son
 * périmètre (« à ouvrir en anomalie et non à corriger ici ») ; l'arbitrage a été de le corriger
 * quand même. Le cas T-08 ci-dessous verrouille la correction.
 * Cas voisin écarté : `DetectedTransactionsScreen:148` replie sur `"EUR"`, mais il s'agit de la
 * devise **lue dans une notification bancaire**, hors sujet malgré le nom.
 *
 * ### Hors périmètre, explicitement
 * - Repris de l'US : conversion et taux de change, devise par compte, format régional, devise des
 *   notifications bancaires.
 * - Ce niveau ne prouve **pas** le contenu et l'ordre du catalogue (TC-104) ni le formatage des
 *   montants (TC-106). CA-02, CA-03 et CA-15 restent des vérifications sur appareil.
 * - Plus aucun manque : la seconde sélection identique de T-03, un temps déclarée non couvrable, l'est
 *   depuis le 17 septembre 2026 par le montage sur okio décrit plus haut. Elle n'appelait ni test
 *   instrumenté ni appareil — l'obstacle n'était pas Android, seulement la primitive de renommage du
 *   poste de développement.
 */
@RunWith(RobolectricTestRunner::class)
class CurrencyPreferenceTest {

    /**
     * Fenêtre d'observation des flux, **déclarée localement**.
     *
     * Elle conditionne le verdict de T-03 : la partager avec une autre fiche ferait qu'un futur
     * réglage ailleurs déplacerait le rouge hors d'ici.
     */
    private val DELAI_OBSERVATION_MS = 5_000L

    private val cleDevise = stringPreferencesKey("currency")

    private val dispatcher = StandardTestDispatcher()
    private lateinit var fuseauInitial: TimeZone
    private lateinit var localeInitiale: Locale

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var depot: SettingsRepository
    private lateinit var stockage: DataStore<Preferences>
    private val scopesOuverts = mutableListOf<CoroutineScope>()
    private var db: LopDatabase? = null

    @Before
    fun setUp() {
        fuseauInitial = TimeZone.getDefault()
        localeInitiale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
        Dispatchers.setMain(dispatcher)

        depot = monterDepot()
    }

    @After
    fun tearDown() {
        db?.close()
        Dispatchers.resetMain()
        scopesOuverts.forEach { it.cancel() }
        scopesOuverts.clear()
        injecterStockage(null)
        TimeZone.setDefault(fuseauInitial)
        Locale.setDefault(localeInitiale)
    }

    // ---------------------------------------------------------------------------------------
    // T-01 — Sans préférence, l'euro ; et lire n'écrit pas.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given aucune preference when lue then EUR expose et rien ecrit`() = runTest(dispatcher) {
        val vm = viewModel()
        abonner(vm)

        assertEquals(
            "T-01 / CA-01 : sans préférence enregistrée, le dépôt doit répondre EUR",
            "EUR",
            depot.currency.first(),
        )
        val etat = attendreDevise(vm, "EUR", "T-01 / CA-01, I-3")
        assertEquals(
            "T-01 / CA-01 : l'écran de réglages doit afficher le symbole de l'euro",
            "€",
            etat.currency.symbol,
        )

        laisserRetomberLesEcrituresEventuelles()
        assertEquals(
            "T-01 / I-5 : lire la préférence ne doit rien écrire, la clé devise doit rester absente " +
                "— clés présentes ${clesPresentes()}",
            null,
            deviseStockee(),
        )
        assertEquals(
            "T-01 / I-5 : aucune clé ne doit apparaître dans le stockage du seul fait d'une lecture " +
                "— clés présentes ${clesPresentes()}",
            emptyList<String>(),
            clesPresentes(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-02 — Chercher puis refermer n'écrit rien.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given une saisie de recherche sans selection when feuille refermee then aucune ecriture`() =
        runTest(dispatcher) {
            val vm = viewModel()
            abonner(vm)
            attendreDevise(vm, "EUR", "T-02 / CA-01 — état initial")

            listOf("U", "US", "USD").forEach { frappe -> vm.onCurrencyQueryChange(frappe) }
            advanceUntilIdle()
            assertEquals(
                "T-02 / CA-04 : la recherche doit bien filtrer, sinon le cas ne prouve rien sur " +
                    "l'absence d'écriture",
                listOf("USD"),
                vm.currencyResults.value.map { it.code },
            )

            vm.onCurrencySheetDismissed()
            advanceUntilIdle()
            laisserRetomberLesEcrituresEventuelles()

            assertEquals(
                "T-02 / CA-07, I-5 : taper dans la recherche puis refermer la feuille sans choisir " +
                    "ne persiste rien, la clé devise doit rester absente — clés ${clesPresentes()}",
                null,
                deviseStockee(),
            )
            assertEquals(
                "T-02 / I-5 : aucune clé parasite ne doit apparaître — clés ${clesPresentes()}",
                emptyList<String>(),
                clesPresentes(),
            )
            assertEquals(
                "T-02 / CA-07 : la devise exposée reste celle d'avant l'ouverture de la feuille",
                "EUR",
                depot.currency.first(),
            )
        }

    // ---------------------------------------------------------------------------------------
    // T-03 — Sélectionner propage sans redémarrage, sur une observation déjà ouverte.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given une observation ouverte when devise selectionnee then propagation sans redemarrage`() =
        runTest(dispatcher) {
            // Observation ouverte AVANT la sélection : c'est ce que CA-13 exige de prouver.
            val emissions = mutableListOf<String>()
            backgroundScope.launch { depot.currency.collect { emissions += it } }
            runCurrent()

            val vm = viewModel()
            abonner(vm)
            // Point de synchronisation, pas un oracle : on attend qu'une première valeur existe.
            attendreCondition("T-03 — aucune première émission du flux de devise") {
                emissions.isNotEmpty()
            }

            val usd = CurrencyCatalog.byCodeOrNull("USD")!!
            vm.setCurrency(usd)
            advanceUntilIdle()

            val etat = attendreDevise(vm, "USD", "T-03 / CA-06, CA-13")
            assertEquals(
                "T-03 / CA-06 : la ligne des réglages doit afficher la nouvelle devise en entier",
                "USD",
                etat.currency.code,
            )

            attendreCondition(
                "T-03 / CA-13 : aucune émission après sélection de USD sur l'observation déjà " +
                    "ouverte — émissions observées $emissions",
            ) { emissions.lastOrNull() == "USD" }

            assertEquals(
                "T-03 / CA-06, I-1 : le stockage doit contenir exactement le code choisi",
                "USD",
                deviseStockee(),
            )
            assertEquals(
                "T-03 / I-3 : une seule clé devise, pas de clé parasite — clés ${clesPresentes()}",
                listOf("currency"),
                clesPresentes(),
            )
            assertEquals(
                "T-03 / I-3 : la source de vérité est unique, aucune valeur étrangère ne doit " +
                    "transiter — émissions observées $emissions",
                emptyList<String>(),
                emissions.filterNot { it == "EUR" || it == "USD" },
            )
        }

    /**
     * Seconde sélection **identique** : l'écriture est idempotente.
     *
     * C'est le cas que la contrainte Windows rendait inaccessible tant que le stockage publiait par
     * `File.renameTo`. Il exige deux écritures successives sur le même fichier.
     *
     * L'oracle final porte sur le **disque**, relu par un stockage neuf, et non sur le flux en
     * mémoire : une écriture qui échoue peut mettre à jour l'état en mémoire sans jamais atteindre
     * le fichier, et le flux annoncerait alors une valeur que l'application ne retrouverait pas au
     * redémarrage.
     */
    @Test
    fun `given une devise deja selectionnee when re-selectionnee then ecriture idempotente`() =
        runTest(dispatcher) {
            val emissions = mutableListOf<String>()
            backgroundScope.launch { depot.currency.collect { emissions += it } }
            runCurrent()

            val usd = CurrencyCatalog.byCodeOrNull("USD")!!
            val vm = viewModel()
            abonner(vm)

            vm.setCurrency(usd)
            advanceUntilIdle()
            attendreCondition("T-03 — la première sélection de USD n'a jamais été persistée") {
                emissions.lastOrNull() == "USD"
            }
            val clesApresPremiere = clesPresentes()

            vm.setCurrency(usd)
            advanceUntilIdle()
            laisserRetomberLesEcrituresEventuelles()

            assertEquals(
                "T-03 / CA-06, I-1 : re-sélectionner la même devise ne change pas la valeur stockée",
                "USD",
                deviseStockee(),
            )
            assertEquals(
                "T-03 / I-3 : une seconde sélection identique ne doit créer aucune clé " +
                    "supplémentaire — après la 1re $clesApresPremiere, après la 2de " +
                    "${clesPresentes()}",
                clesApresPremiere,
                clesPresentes(),
            )
            assertEquals(
                "T-03 / I-3 : aucune valeur étrangère n'a transité entre les deux sélections — " +
                    "émissions observées $emissions",
                emptyList<String>(),
                emissions.filterNot { it == "EUR" || it == "USD" },
            )
            assertEquals(
                "T-03 / CA-06 : après les deux sélections, c'est bien USD qui est **sur le disque** " +
                    "— une écriture qui n'atteint pas le fichier ne compte pas",
                "USD",
                redemarrer().currency.first(),
            )
        }

    // ---------------------------------------------------------------------------------------
    // T-04 — Le choix survit au redémarrage, et le défaut ne l'écrase pas.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given une devise choisie when depot recree then le choix survit`() = runTest(dispatcher) {
        depot.setCurrency("JPY")
        assertEquals(
            "T-04a / CA-06 : la sélection doit d'abord avoir été persistée",
            "JPY",
            deviseStockee(),
        )

        val apresRedemarrage = redemarrer().currency.first()

        assertEquals(
            "T-04a / CA-08 : après redémarrage, la devise choisie est toujours celle affichée — " +
                "la valeur par défaut EUR ne doit pas écraser le choix",
            "JPY",
            apresRedemarrage,
        )
        assertEquals(
            "T-04a / CA-08 : une seule clé devise après redémarrage — clés ${clesPresentes()}",
            listOf("currency"),
            clesPresentes(),
        )
    }

    @Test
    fun `given une preference USD when depot recree then le defaut n ecrase pas le choix`() =
        runTest(dispatcher) {
            semer("USD")

            val apresRedemarrage = redemarrer().currency.first()

            assertEquals(
                "T-04b / CA-08 : une préférence déjà présente au démarrage doit être servie telle " +
                    "quelle, pas remplacée par le défaut",
                "USD",
                apresRedemarrage,
            )
        }

    // ---------------------------------------------------------------------------------------
    // T-05 — Une préférence corrompue dégrade vers l'euro sans planter. Une variante par cas.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given une preference vide when lue then repli sur EUR`() = verifierRepli("")

    @Test
    fun `given une preference avec espace when lue then repli sur EUR`() = verifierRepli("eur ")

    @Test
    fun `given une preference inconnue when lue then repli sur EUR`() = verifierRepli("ABC")

    @Test
    fun `given une preference en minuscules when lue then repli sur EUR`() = verifierRepli("usd")

    private fun verifierRepli(valeurCorrompue: String) = runTest(dispatcher) {
        semer(valeurCorrompue)

        val lue = runCatching { depot.currency.first() }.getOrElse { erreur ->
            throw AssertionError(
                "T-05 / CA-11 : une préférence corrompue « $valeurCorrompue » ne doit pas faire " +
                    "échouer la lecture — obtenu ${erreur::class.simpleName}: ${erreur.message}",
            )
        }
        assertEquals(
            "T-05 / CA-11, I-1 : « $valeurCorrompue » n'est pas un code du catalogue, la lecture " +
                "doit dégrader vers l'euro plutôt que le propager au formatage des montants",
            "EUR",
            lue,
        )

        val vm = viewModel()
        abonner(vm)
        val etat = attendreDevise(vm, "EUR", "T-05 / CA-11 — variante « $valeurCorrompue »")
        assertEquals(
            "T-05 / CA-11 : l'écran doit rester capable d'afficher des montants, donc porter un " +
                "symbole — variante « $valeurCorrompue »",
            "€",
            etat.currency.symbol,
        )
        assertEquals(
            "T-05 / CA-11 : la valeur corrompue ne doit pas avoir été réécrite par la lecture, " +
                "elle est ignorée et non corrigée — clés ${clesPresentes()}",
            valeurCorrompue,
            deviseStockee(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-06 — Seul un code du catalogue atteint le stockage.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given des codes hors catalogue when setCurrency then aucune ecriture`() =
        runTest(dispatcher) {
            semer("USD")

            listOf("ABC", "", "eur").forEach { tentative ->
                runCatching { depot.setCurrency(tentative) }.onFailure { erreur ->
                    throw AssertionError(
                        "T-06 / I-1 : setCurrency(« $tentative ») a tenté d'atteindre le stockage " +
                            "au lieu de refuser la valeur — ${erreur::class.simpleName}: " +
                            "${erreur.message}",
                    )
                }
                assertEquals(
                    "T-06 / I-1 : « $tentative » n'est pas exactement un code du catalogue et ne " +
                        "doit jamais être persisté — clés ${clesPresentes()}",
                    "USD",
                    deviseStockee(),
                )
            }

            assertEquals(
                "T-06 / I-1 : après les trois tentatives, la préférence antérieure est intacte",
                "USD",
                depot.currency.first(),
            )
            assertEquals(
                "T-06 / I-1 : aucune clé parasite n'a été créée — clés ${clesPresentes()}",
                listOf("currency"),
                clesPresentes(),
            )
        }

    // ---------------------------------------------------------------------------------------
    // T-07 — Changer de devise ne touche à aucun montant stocké.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given des transactions en base when devise changee then aucune ligne modifiee`() =
        runTest(dispatcher) {
            val base = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Application>(),
                LopDatabase::class.java,
            ).allowMainThreadQueries().build().also { db = it }

            val compte = base.accountDao().upsert(
                AccountEntity(
                    name = "Compte courant",
                    type = AccountType.CHECKING,
                    initialBalance = 100_000L,
                    colorArgb = 0xFF2196F3.toInt(),
                    icon = "wallet",
                ),
            )
            val categorie = base.categoryDao().upsert(
                CategoryEntity(
                    name = "Courses",
                    type = TransactionType.EXPENSE,
                    colorArgb = 0xFF4CAF50.toInt(),
                    icon = "cart",
                ),
            )
            val dateCourses = 1_757_000_000_000L // 4 septembre 2026, fixe
            listOf(1_234L to "Courses du marché", 9_900L to "Abonnement mensuel").forEach {
                    (centimes, libelle) ->
                base.transactionDao().upsert(
                    TransactionEntity(
                        title = libelle,
                        amount = centimes,
                        type = TransactionType.EXPENSE,
                        status = TransactionStatus.PAID,
                        date = dateCourses,
                        accountId = compte,
                        categoryId = categorie,
                    ),
                )
            }

            val avant = base.transactionDao().observeAllEntities().first()
            val soldeAvant = base.accountDao().observeAll().first().map { it.id to it.initialBalance }
            assertEquals(
                "T-07 : le jeu de données doit contenir les deux transactions attendues, sinon le " +
                    "cas ne prouve rien",
                2,
                avant.size,
            )

            depot.setCurrency("USD")
            assertEquals(
                "T-07 / CA-06 : le changement de devise doit avoir eu lieu",
                "USD",
                deviseStockee(),
            )

            val apres = base.transactionDao().observeAllEntities().first()
            val soldeApres = base.accountDao().observeAll().first().map { it.id to it.initialBalance }

            assertEquals(
                "T-07 / CA-09, I-2 : changer de devise ne crée ni ne supprime aucune ligne",
                avant.size,
                apres.size,
            )
            assertEquals(
                "T-07 / CA-09, I-2 : aucun montant stocké ne bouge, aucune conversion n'est " +
                    "appliquée — avant ${avant.map { it.id to it.amount }}, " +
                    "après ${apres.map { it.id to it.amount }}",
                avant.map { it.id to it.amount },
                apres.map { it.id to it.amount },
            )
            assertEquals(
                "T-07 / CA-09, I-2 : les lignes sont identiques champ pour champ",
                avant,
                apres,
            )
            assertEquals(
                "T-07 / CA-09, I-2 : les soldes de comptes sont inchangés",
                soldeAvant,
                soldeApres,
            )
        }

    // ---------------------------------------------------------------------------------------
    // T-08 — Le seul volet assertable du recensement : aucune devise codée en dur dans un état.
    //
    // La fiche qualifie T-08 d'analyse, et l'essentiel l'est : qu'un écran lise bien le dépôt ne se
    // prouve pas par un test unitaire. Mais son oracle dit aussi « aucune devise codée en dur »,
    // et *cela* s'asserte. Ce cas verrouille la correction d'ANO-A.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given les etats d ecran when valeur initiale de devise then celle du catalogue`() {
        assertEquals(
            "T-08 / CA-13, I-3 (ANO-A) : l'état d'accueil ne doit pas porter sa propre devise en " +
                "dur — sinon l'écran légende ses montants dans une devise que l'utilisateur n'a " +
                "jamais choisie, le temps que le flux de préférences émette",
            CurrencyCatalog.default.code,
            HomeUiState().currency,
        )
        assertEquals(
            "T-08 / I-3 : l'état des réglages doit partir de la même devise que le dépôt",
            CurrencyCatalog.default.code,
            SettingsUiState().currency.code,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Montage. Rien ici ne produit d'attendu : ces outils préparent et observent.
    // ---------------------------------------------------------------------------------------

    /** Vrai ViewModel. Seule doublure stricte : le gestionnaire de téléchargement du modèle d'IA. */
    private fun viewModel(): SettingsViewModel {
        val telechargement = mockk<QwenDownloadManager>()
        every { telechargement.isModelInstalled() } returns false
        return SettingsViewModel(depot, telechargement, SearchCurrenciesUseCase())
    }

    /**
     * `uiState` et `currencyResults` sont des `stateIn(WhileSubscribed)` : sans abonné ils restent
     * sur leur valeur initiale et n'émettent jamais. Les deux sont donc collectés.
     */
    private fun TestScope.abonner(vm: SettingsViewModel) {
        backgroundScope.launch { vm.uiState.collect { } }
        backgroundScope.launch { vm.currencyResults.collect { } }
        runCurrent()
    }

    /**
     * « Redémarrage » : l'ancien stockage est arrêté et jeté, un neuf est monté sur le **même**
     * fichier, et le dépôt qui en sort relit donc le disque et non un cache mémoire.
     */
    private fun redemarrer(): SettingsRepository = monterDepot()

    /** Semis direct dans le stockage, sans passer par `setCurrency` qui validerait la valeur. */
    private suspend fun semer(valeur: String) {
        stockage.edit { it[cleDevise] = valeur }
    }

    private suspend fun deviseStockee(): String? = stockage.data.first()[cleDevise]

    private suspend fun clesPresentes(): List<String> =
        stockage.data.first().asMap().keys.map { it.name }.sorted()

    /**
     * Attend que l'état du ViewModel porte [attendu], sous un délai réel borné.
     *
     * L'horloge de `runTest` est virtuelle et n'attend pas l'E/S du DataStore : il faut donc faire
     * tourner l'ordonnanceur **et** laisser le temps réel avancer. L'absence d'émission devient un
     * échec métier nommant le CA et le dernier état observé, jamais un délai brut.
     */
    private suspend fun TestScope.attendreDevise(
        vm: SettingsViewModel,
        attendu: String,
        libelle: String,
    ): SettingsUiState {
        val limite = System.nanoTime() + DELAI_OBSERVATION_MS * 1_000_000
        var dernier = vm.uiState.value
        while (System.nanoTime() < limite) {
            advanceUntilIdle()
            dernier = vm.uiState.value
            if (dernier.currency.code == attendu) return dernier
            withContext(Dispatchers.Default) { delay(10) }
        }
        throw AssertionError(
            "$libelle : l'état du ViewModel n'a jamais porté « $attendu » en " +
                "$DELAI_OBSERVATION_MS ms — dernier état observé « ${dernier.currency.code} », " +
                "stockage « ${deviseStockee()} »",
        )
    }

    /** Même principe, pour une condition qui ne porte pas sur l'état du ViewModel. */
    private suspend fun TestScope.attendreCondition(libelle: String, condition: () -> Boolean) {
        val limite = System.nanoTime() + DELAI_OBSERVATION_MS * 1_000_000
        while (System.nanoTime() < limite) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        throw AssertionError("$libelle (délai $DELAI_OBSERVATION_MS ms)")
    }

    /**
     * Laisse une écriture fautive le temps d'atteindre le disque avant d'asserter son absence.
     *
     * Cette attente **durcit** l'oracle au lieu de le relâcher : sans elle, un cas « aucune
     * écriture » pourrait passer au vert simplement parce que l'E/S n'a pas encore eu lieu.
     */
    private suspend fun TestScope.laisserRetomberLesEcrituresEventuelles() {
        advanceUntilIdle()
        withContext(Dispatchers.Default) { delay(200) }
        advanceUntilIdle()
    }

    // ---------------------------------------------------------------------------------------
    // Accès au DataStore réel du dépôt, par réflexion. Test seul, aucune API de production ajoutée.
    // ---------------------------------------------------------------------------------------

    private fun champInstance(): Pair<Any, java.lang.reflect.Field> {
        val facade = Class.forName("com.lop.budget.data.repository.SettingsRepositoryKt")
        val delegueField = facade.getDeclaredField("dataStore\$delegate")
        delegueField.isAccessible = true
        val delegue = delegueField.get(null)!!
        val instance = delegue.javaClass.getDeclaredField("INSTANCE")
        instance.isAccessible = true
        return delegue to instance
    }

    private fun injecterStockage(store: DataStore<Preferences>?) {
        val (delegue, instance) = champInstance()
        instance.set(delegue, store)
    }

    /**
     * Monte un stockage neuf sur le fichier de préférences, l'injecte dans le délégué, et rend un
     * dépôt qui l'utilise. Arrête au passage le stockage précédent : une seule instance vivante à
     * la fois sur un même fichier, ce que DataStore exige.
     *
     * Appelé au montage de chaque cas **et** à chaque « redémarrage » : c'est la même opération.
     */
    private fun monterDepot(): SettingsRepository {
        scopesOuverts.forEach { it.cancel() }
        scopesOuverts.clear()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopesOuverts += scope
        val fichier = context.preferencesDataStoreFile("lop_settings")
        fichier.parentFile?.mkdirs()

        stockage = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                fichier.absolutePath.toPath()
            },
            scope = scope,
        )
        injecterStockage(stockage)
        return SettingsRepository(context)
    }
}
