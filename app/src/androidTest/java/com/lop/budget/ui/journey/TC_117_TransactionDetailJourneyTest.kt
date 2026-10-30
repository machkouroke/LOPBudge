package com.lop.budget.ui.journey

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.R
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.di.TestClock
import com.lop.budget.domain.model.AccountType
import com.lop.budget.ui.common.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-117 — Détail de transaction : titre, informations, affordance commune et parcours (LOP-53).
 *
 * Portage de `Maestro/tests/TC-117_Detail_Transaction/TC-117-1_Detail_Transaction.yaml`, dont les six
 * étapes deviennent quatre cas indépendants.
 *
 * ## Niveau et chaîne réelle
 * Graphe réel, `Journey` : liste de janvier 2027 → `TransactionDetailScreen` → sélecteurs partagés →
 * `TransactionActionViewModel.quickEdit*`. Jeu du seeder ancré sur l'horloge figée au 15 janvier
 * 2027 ; « Courses Hebdomadaires » (8 420 centimes, payée, Compte courant, Courses) tient le rôle
 * TX-PAY de la fiche. Le second compte « Compte B TC117 » est semé par le DAO : le flow le créait par
 * les réglages, faute de pouvoir semer, et cette création n'est pas l'objet de la fiche.
 *
 * ## Cas → CA → assertion principale
 * ```
 * U-01  T-01..T-03  CA-01, CA-02, CA-11  titre au-dessus du montant ; six informations ; affordance
 *                                        sur catégorie, date, compte, pas sur le type
 * U-02  T-04        CA-06                chaque ligne ouvre SON sélecteur, jamais l'édition complète
 * U-03  T-05        CA-07                le compte affiché passe à B sans geste de rafraîchissement
 * U-04  T-06        CA-13                Modifier et Supprimer joignables ; Modifier ouvre l'édition
 * ```
 * Statut (CA-02) : décision produit du 16 septembre 2026, le libellé du bouton d'action EST
 * l'affichage du statut ; il est vérifié exact, ce qui exclut la présence des deux libellés.
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 4/4 verts, deux passages, 6 s. Mutations de production, une à la fois :
 * ```
 * affordance ajoutée à la ligne Type                   → U-01 (T-03, donnée de contrôle)
 * la ligne Catégorie ouvre les comptes                 → U-02
 * quickEditAccount sans effet                          → U-03
 * libellés du statut inversés                          → U-01 (T-02)
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TransactionDetailJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var clock: TestClock

    private val journey by lazy { Journey(composeRule, "tc117") }
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        hiltRule.inject()
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(Journey.PARIS))
        Locale.setDefault(Locale.FRANCE)
        runBlocking {
            db.clearAllTables()
            SeederDataset.seed(db, LocalDate.of(2027, 1, 15))
            db.accountDao().upsert(
                AccountEntity(
                    name = ACCOUNT_B, type = AccountType.CHECKING, initialBalance = 25_000,
                    colorArgb = 0xFF455A64.toInt(), icon = "wallet",
                ),
            )
        }
        clock.fixedAt = Journey.at(2027, 1, 15)
        journey.case = "montage"
        journey.launch()
        journey.openMonthFromHome("Janvier 2027")
        journey.openDetail(TX_PAY)
    }

    @After
    fun tearDown() {
        journey.close()
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun u01_given_TX_PAY_when_detail_then_titre_au_dessus_du_montant_six_informations_et_affordance_commune() = with(journey) {
        case = "U-01 (T-01..T-03)"
        // T-01 / CA-01 : le titre, puis le montant juste en dessous, dans le même bloc.
        val title = composeRule.onNode(hasTestTag(TestTags.TRANSACTION_DETAIL_TITLE), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val amount = composeRule.onNode(hasTestTag(TestTags.TRANSACTION_DETAIL_AMOUNT), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        expect("T-01 / CA-01 — le montant commence sous le titre (bords en px : titre $title, montant $amount)", true, amount.top >= title.bottom)

        // T-02 / CA-02 : les six informations.
        expect("T-02 — montant", listOf("84,20 €"), texts(TestTags.TRANSACTION_DETAIL_AMOUNT))
        expect("T-02 — type", listOf("Dépense"), value(TestTags.TRANSACTION_DETAIL_FIELD_TYPE))
        expect("T-02 — date : une valeur affichée", 1, value(TestTags.TRANSACTION_DETAIL_FIELD_DATE).count { it.isNotBlank() })
        expect("T-02 — catégorie", listOf("Courses"), value(TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY))
        expect("T-02 — compte", listOf("Compte courant"), value(TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT))
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
        expect("T-02 — statut payé, porté par le seul libellé du bouton", listOf("Marquer comme non payé"), texts(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_TITLE))

        // T-03 / CA-11 : même affordance sur les trois lignes modifiables ; le type, non modifiable, n'en a pas.
        listOf(TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY, TestTags.TRANSACTION_DETAIL_FIELD_DATE, TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT).forEach { field ->
            expect("T-03 / CA-11 — affordance de $field", 1, count(field + TestTags.EDIT_AFFORDANCE_SUFFIX))
        }
        expect("T-03 / CA-11 — donnée de contrôle : le type n'a pas d'affordance", 0, count(TestTags.TRANSACTION_DETAIL_FIELD_TYPE + TestTags.EDIT_AFFORDANCE_SUFFIX))
    }

    @Test
    fun u02_given_TX_PAY_when_chaque_ligne_modifiable_touchee_then_son_seul_selecteur_jamais_l_edition() = with(journey) {
        case = "U-02 (T-04)"
        openField(TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY)
        assertOnly("T-04 / CA-06 — catégorie", TestTags.PICKER_CATEGORY_SHEET)
        pressBack()
        await("sélecteur de catégorie refermé") { count(TestTags.PICKER_CATEGORY_SHEET) == 0 }
        expect("retour sur le détail", TestTags.SCREEN_DETAIL, activeScreen())

        openField(TestTags.TRANSACTION_DETAIL_FIELD_DATE)
        assertOnly("T-04 / CA-06 — date", TestTags.PICKER_DATE)
        val cancel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cancel)
        click(composeRule.onNode(hasText(cancel) and hasClickAction() and inside(TestTags.PICKER_DATE)))
        await("sélecteur de date refermé") { count(TestTags.PICKER_DATE) == 0 }
        expect("retour sur le détail", TestTags.SCREEN_DETAIL, activeScreen())

        openField(TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT)
        assertOnly("T-04 / CA-06 — compte", TestTags.PICKER_ACCOUNT_SHEET)
    }

    @Test
    fun u03_given_TX_PAY_sur_Compte_courant_when_Compte_B_choisi_then_compte_a_jour_sans_rafraichissement() = with(journey) {
        case = "U-03 (T-05)"
        openField(TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT)
        await("sélecteur de comptes") { count(TestTags.PICKER_ACCOUNT_SHEET) == 1 }
        val accountB = hasText(ACCOUNT_B) and hasClickAction() and inside(TestTags.PICKER_ACCOUNT_SHEET)
        await("« $ACCOUNT_B » proposé") { count(accountB, merged = true) == 1 }

        click(composeRule.onNode(accountB))

        expect("T-05 — on reste sur le détail", TestTags.SCREEN_DETAIL, activeScreen())
        await("T-05 / CA-07 — compte « $ACCOUNT_B » affiché") { value(TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT) == listOf(ACCOUNT_B) }
        expect("T-05 / CA-07 — l'ancien compte n'est plus affiché", listOf(ACCOUNT_B), value(TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT))
    }

    @Test
    fun u04_given_TX_PAY_when_Modifier_then_Modifier_et_Supprimer_joignables_et_edition_complete() = with(journey) {
        case = "U-04 (T-06)"
        expect("T-06 / CA-13 — Modifier", 1, count(TestTags.TRANSACTION_DETAIL_EDIT))
        expect("T-06 / CA-13 — Supprimer", 1, count(TestTags.TRANSACTION_DETAIL_DELETE))

        click(node(TestTags.TRANSACTION_DETAIL_EDIT))

        awaitScreen(TestTags.SCREEN_EDIT)
        await("T-06 — formulaire de « $TX_PAY »") { fieldText(TestTags.TX_EDIT_FIELD_TITLE) == TX_PAY }
    }

    private fun Journey.value(field: String) = texts(field + TestTags.VALUE_SUFFIX, merged = false)

    /** La ligne est cliquable sous son étiquette : son nœud cliquable est un enfant. */
    private fun Journey.openField(field: String) =
        click(composeRule.onNode(hasClickAction() and inside(field) and inside(TestTags.SCREEN_DETAIL)))

    /** [expected] ouvert, les deux autres sélecteurs et l'édition complète absents. */
    private fun Journey.assertOnly(label: String, expected: String) {
        await("$label : $expected ouvert") { count(expected) == 1 }
        listOf(TestTags.PICKER_CATEGORY_SHEET, TestTags.PICKER_DATE, TestTags.PICKER_ACCOUNT_SHEET, TestTags.SCREEN_EDIT)
            .filter { it != expected }
            .forEach { other -> expect("$label — $other absent", 0, count(other)) }
    }

    private companion object {
        const val TX_PAY = "Courses Hebdomadaires"
        const val ACCOUNT_B = "Compte B TC117"
    }
}
