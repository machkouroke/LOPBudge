package com.lop.budget.domain.usecase.tag

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.TagRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-128 — CRUD tags sur base réelle : observation, création, renommage, suppression
 * (US LOP-21, réf. 21).
 *
 * ## Niveau
 * Intégration sur base Room réelle, en mémoire (Robolectric SDK 33, application Android neutre).
 * **Aucun mock, aucun fake, aucun spy sur la chaîne.** Ce ticket porte sur des lignes écrites, des
 * cardinalités et des jointures : une doublure ne voit ni un `INSERT`, ni le `CASCADE` qui efface
 * les liens d'un tag supprimé.
 *
 * ## Chaîne réellement exercée
 * ```
 * Observation   ObserveTagsUseCase()      → TagRepository.observeAll → TagDao
 * Création      CreateTagUseCase(name,c)  → TagRepository.observeAll + upsert
 * Renommage     UpdateTagUseCase(t,n,c)   → TagRepository.observeAll + upsert
 * Suppression   DeleteTagUseCase(id)      → TagRepository.delete, puis CASCADE du schéma
 * ```
 * Les quatre use cases sont instanciés pour de vrai sur `TagRepository(db.tagDao())`. Le jeu de
 * données est semé par les API publiques (`TransactionDao.saveWithTags`,
 * `RecurringSeriesDao.saveSeriesWithTags`), et l'état est constaté par SQL brut limité au test.
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 * ```
 * O-01   CA-01               ObserveTagsUseCase.invoke (référentiel vide)
 * O-02   CA-01               ObserveTagsUseCase.invoke (trois tags)
 * C-01   CA-02               CreateTagUseCase.invoke (nom valide, trim, couleur)
 * C-02   CA-03               CreateTagUseCase.invoke (branche `trimmed.isEmpty()`)
 * C-03   CA-03               CreateTagUseCase.invoke (branche nom normalisé déjà porté)
 * U-01   CA-04, I-3          UpdateTagUseCase.invoke (renommage effectif)
 * U-02   CA-05               UpdateTagUseCase.invoke (branche nom blanc)
 * U-03   CA-05, I-2          UpdateTagUseCase.invoke (branche collision normalisée)
 * D-01   CA-06, CA-08 pers.  DeleteTagUseCase.invoke (tag sans lien)
 * D-02   CA-07, CA-08 pers., DeleteTagUseCase.invoke (tag utilisé par une transaction ET une
 *        I-1, I-2            série) + CASCADE de `transaction_tags` / `series_tags`
 * ```
 * Aucun cas sans CA, aucun CA de ce périmètre sans cas. D-01 et D-02 couvrent CA-08 **côté
 * persistance seulement** : c'est le même `DeleteTagUseCase` que l'écran de gestion et que la
 * modal. Le retrait de la sélection en cours (`form.tagIds`) est **TC-129**.
 *
 * ## Fixtures discriminantes
 * - **Couleurs toutes distinctes**, jamais réutilisées d'un tag à l'autre. `COLOR_INTRUS` n'est
 *   **tentée que sur des no-op** (C-03, U-02, U-03) : si elle apparaît une seule fois en base,
 *   c'est qu'une écriture qui devait être refusée est passée. Chacun de ces trois cas l'assert.
 * - **TAG-PRO est le témoin** : jamais la cible d'une écriture. Son triplet (id, nom, couleur) est
 *   réasserté dans tous les cas où il est semé, par [assertTagProIntact].
 * - Les tags sont identifiés par les **identifiants rendus par Room**, jamais par leur nom :
 *   `tags` n'a aucun index unique sur `name`, et depuis LOP-21 plus aucune lecture par nom
 *   n'existe côté DAO. Aucun identifiant n'est inventé.
 * - U-01 renomme « Santé » et relit les jointures **par identifiant** : un renommage qui
 *   recréerait une ligne au lieu de mettre à jour l'existante casserait les liens, et le cas
 *   rougirait sur TX-PONCT comme sur SERIE-M.
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - Base neuve par cas, fermée en `@After` ; aucun état réutilisé d'un cas à l'autre. Chaque cas
 *   ne sème que ce dont son oracle a besoin : O-01 et C-01 exigent une base **sans aucun tag**.
 * - `Europe/Paris` et `Locale.FRANCE` forcés puis restaurés. La date métier `marsSlot` est fixe et
 *   nommée ; aucune horloge courante n'est lue. Aucun oracle ne porte sur une date : elle n'est là
 *   que pour donner aux parents un état métier valide.
 * - Observation par Turbine, attente bornée. Aucun `Thread.sleep`, aucun délai arbitraire.
 * - [rawRows] lit `tags`, `transaction_tags` et `series_tags` par SQL brut, trié. Une requête de
 *   lecture limitée au test est autorisée par `app/src/test/AGENTS.md` §6 ; **aucune API de
 *   production n'a été ajoutée** pour ce fichier.
 * - `observeAll` trie par `name`. CA-01 n'exige aucun ordre : les oracles portent sur des
 *   ensembles et des cardinalités, jamais sur une position.
 *
 * ## Écarts spec / code relevés à la lecture (aucun n'ouvre d'oracle ici)
 * - `tags` n'a **aucun index unique** sur `name`. L'unicité du nom normalisé (I-2 de LOP-3) vit
 *   entièrement dans `CreateTagUseCase` et `UpdateTagUseCase`, via `observeAll().first()` et
 *   `normalizedTagName()` (`trim().lowercase()`, `Locale.ROOT`). Un `INSERT` SQL brut de deux
 *   « Santé » ne serait donc pas un oracle de ce ticket, et ce fichier n'en fait pas un.
 * - `DeleteTagUseCase` ne touche jamais aux jointures : ce sont les `ON DELETE CASCADE` de
 *   `TransactionTagCrossRef` et `SeriesTagCrossRef` qui les effacent. L'oracle est l'**état final
 *   des tables**, pas le nombre d'instructions exécutées — recopier un `DELETE` applicatif n'est
 *   pas exigé, et masquerait la perte de la contrainte si elle survenait.
 * - `TagDao.countUsages` a été retiré (P-1) : un tag utilisé se supprime. Rebrancher un garde
 *   d'usage ferait rougir D-02, ce qui est précisément la mutation de sensibilité de ce cas.
 *
 * ## Anomalies
 * Aucune ANO ouverte par ce fichier à ce jour. Aucun oracle n'a été assoupli : un rouge serait
 * remonté tel quel, avec son message réel et une ANO par cause racine.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Retrait de la sélection en cours du formulaire (`form.tagIds`, CA-08) → **TC-129**. Ce niveau
 *   ne voit pas cet état.
 * - Annuler la confirmation de suppression (CA-06, CA-07, CA-08) : état Compose local
 *   (`tagToDelete`), aucune API ViewModel. Annuler, c'est **ne pas appeler** `DeleteTagUseCase` ;
 *   aucun cas de ce fichier ne peut le constater.
 * - `TagsManageViewModel` : délégation pure (P-4), rien à prouver ici.
 * - Création rapide depuis la modal, message de nom vide, sélection automatique → **TC-124**
 *   (LOP-3).
 * - Sélection et pré-sélection au formulaire → **TC-123**. Liens persistés à la sauvegarde d'une
 *   transaction → **TC-122**.
 * - Sélecteur de couleur, navigation vers l'écran de gestion, écrans Compose, E2E,
 *   accessibilité.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*TagCrudPersistenceTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TagCrudPersistenceTest {

    // --- Composants réels -----------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var tagRepo: TagRepository
    private lateinit var observeTags: ObserveTagsUseCase
    private lateinit var createTag: CreateTagUseCase
    private lateinit var updateTag: UpdateTagUseCase
    private lateinit var deleteTag: DeleteTagUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    // --- Identifiants du jeu de données, tous rendus par Room -----------------------------------

    private var tagSanteId = 0L
    private var tagProId = 0L
    private var tagVacancesId = 0L
    private var accountId = 0L
    private var categoryId = 0L
    private var txPonctId = 0L
    private var serieMId = 0L

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_ID))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        tagRepo = TagRepository(db.tagDao())
        observeTags = ObserveTagsUseCase(tagRepo)
        createTag = CreateTagUseCase(tagRepo)
        updateTag = UpdateTagUseCase(tagRepo)
        deleteTag = DeleteTagUseCase(tagRepo)
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // O-01 — Un référentiel vide n'expose aucune entrée
    // ==============================================================================================

    /**
     * O-01 — Given une base sans aucun tag, When on collecte `ObserveTagsUseCase`, Then la liste
     * émise est vide et la table `tags` ne contient aucune ligne (CA-01).
     */
    @Test
    fun `O-01 - Given une base sans aucun tag - When on collecte ObserveTagsUseCase - Then la liste emise est vide et la table tags est vide (CA-01)`() =
        runTest {
            val observed = observedTags()

            assertEquals(
                "O-01 — CA-01 : un référentiel vide doit émettre zéro entrée ; " +
                    "obtenu ${observed.map { it.id }}",
                emptyList<TagEntity>(),
                observed,
            )
            assertEquals(
                "O-01 — CA-01 : la table `tags` devait être vide ; tags = ${rawRows(SQL_TAGS)}",
                emptyList<String>(),
                rawRows(SQL_TAGS),
            )
        }

    // ==============================================================================================
    // O-02 — Trois tags en base donnent exactement trois entrées
    // ==============================================================================================

    /**
     * O-02 — Given trois tags en base, When on collecte `ObserveTagsUseCase`, Then la liste
     * contient exactement trois entrées, d'identifiants distincts et égaux à ceux rendus par Room,
     * sans aucun doublon (CA-01).
     */
    @Test
    fun `O-02 - Given trois tags en base - When on collecte ObserveTagsUseCase - Then exactement trois entrees d'identifiants distincts sans doublon (CA-01)`() =
        runTest {
            seedThreeTags()

            val observed = observedTags()

            assertEquals(
                "O-02 — CA-01 : la liste devait contenir exactement une entrée par tag existant ; " +
                    "obtenu ${observed.map { it.id }}",
                3,
                observed.size,
            )
            assertEquals(
                "O-02 — CA-01 : aucun doublon d'identifiant n'est toléré ; " +
                    "obtenu ${observed.map { it.id }}",
                3,
                observed.map { it.id }.toSet().size,
            )
            assertEquals(
                "O-02 — CA-01 : les entrées devaient porter exactement les identifiants rendus " +
                    "par Room ; obtenu ${observed.map { it.id }}",
                setOf(tagSanteId, tagProId, tagVacancesId),
                observed.map { it.id }.toSet(),
            )
            assertEquals(
                "O-02 — CA-01 : chaque entrée devait porter le nom et la couleur persistés ; " +
                    "obtenu ${observed.map { it.name to it.colorArgb }}",
                setOf(
                    TAG_SANTE_NAME to COLOR_SANTE,
                    TAG_PRO_NAME to COLOR_PRO,
                    TAG_VACANCES_NAME to COLOR_VACANCES,
                ),
                observed.map { it.name to it.colorArgb }.toSet(),
            )
        }

    // ==============================================================================================
    // C-01 — Créer un nom valide écrit exactement un tag, trimé
    // ==============================================================================================

    /**
     * C-01 — Given une base sans aucun tag, When on crée « ␣␣Courses␣␣ », Then exactement une ligne
     * est écrite, son nom est trimé et sa casse conservée, sa couleur est celle saisie, et
     * l'identifiant rendu est celui de cette ligne (CA-02).
     */
    @Test
    fun `C-01 - Given une base sans aucun tag - When on cree un nom entoure d'espaces - Then exactement une ligne trimee avec la couleur saisie (CA-02)`() =
        runTest {
            val createdId = createTag(NAME_COURSES_UNTRIMMED, COLOR_VACANCES)

            assertNotNull(
                "C-01 — CA-02 : la création d'un nom valide devait rendre un identifiant non nul",
                createdId,
            )
            assertTrue(
                "C-01 — CA-02 : l'identifiant rendu devait être celui d'une ligne réellement " +
                    "écrite, donc strictement positif ; obtenu $createdId",
                createdId!! > 0L,
            )
            assertEquals(
                "C-01 — CA-02 : exactement une ligne devait être écrite dans `tags` ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                1,
                rawRows(SQL_TAGS).size,
            )

            val persisted = observedTags().single()
            assertEquals(
                "C-01 — CA-02 : l'identifiant rendu devait être celui de la ligne persistée ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                createdId,
                persisted.id,
            )
            assertEquals(
                "C-01 — CA-02 : le nom devait être persisté après `trim`, casse conservée, et non " +
                    "la chaîne brute « $NAME_COURSES_UNTRIMMED » ; obtenu « ${persisted.name} »",
                NAME_COURSES_TRIMMED,
                persisted.name,
            )
            assertEquals(
                "C-01 — CA-02 : la couleur devait être persistée telle que saisie ; " +
                    "obtenu ${persisted.colorArgb}",
                COLOR_VACANCES,
                persisted.colorArgb,
            )
        }

    // ==============================================================================================
    // C-02 — Un nom vide ou blanc n'écrit rien
    // ==============================================================================================

    /**
     * C-02 — Given le référentiel Santé et Pro, When on tente de créer « » puis « ␣␣␣ », Then les
     * deux appels rendent `null`, aucune ligne n'est écrite et le référentiel observé est
     * strictement inchangé (CA-03).
     *
     * Un seul cas pour deux jeux : ils empruntent la **même branche** `trimmed.isEmpty()`. Le
     * référentiel est semé plutôt que laissé vide, pour que « inchangé » ait un contenu à
     * comparer : sur une base vide, l'oracle ne distinguerait pas un refus correct d'un
     * référentiel qui n'a jamais rien su écrire.
     */
    @Test
    fun `C-02 - Given le referentiel Sante et Pro - When on tente de creer un nom vide puis un nom blanc - Then rien n'est ecrit et le referentiel est inchange (CA-03)`() =
        runTest {
            seedSanteAndPro()
            val tagsBefore = rawRows(SQL_TAGS)

            val fromEmpty = createTag(NAME_EMPTY, COLOR_INTRUS)
            val fromBlank = createTag(NAME_BLANK, COLOR_INTRUS)

            assertNull(
                "C-02 — CA-03 : un nom vide ne doit rien créer et rendre `null` ; obtenu $fromEmpty",
                fromEmpty,
            )
            assertNull(
                "C-02 — CA-03 : un nom composé uniquement d'espaces ne doit rien créer et rendre " +
                    "`null` ; obtenu $fromBlank",
                fromBlank,
            )
            assertEquals(
                "C-02 — CA-03 : le nombre de tags devait rester inchangé ; " +
                    "avant = $tagsBefore, après = ${rawRows(SQL_TAGS)}",
                2,
                rawRows(SQL_TAGS).size,
            )
            assertEquals(
                "C-02 — CA-03 : aucune ligne de `tags` ne devait bouger ; " +
                    "avant = $tagsBefore, après = ${rawRows(SQL_TAGS)}",
                tagsBefore,
                rawRows(SQL_TAGS),
            )
            assertEquals(
                "C-02 — CA-03 : le référentiel observé devait rester exactement Santé et Pro ; " +
                    "obtenu ${observedTags().map { it.id }}",
                setOf(tagSanteId, tagProId),
                observedTags().map { it.id }.toSet(),
            )

            assertIntrusColorNeverPersisted("C-02")
            assertTagProIntact("C-02")
        }

    // ==============================================================================================
    // C-03 — Un nom déjà porté à la normalisation près ne crée rien et n'écrase rien
    // ==============================================================================================

    /**
     * C-03 — Given TAG-SANTE nommé « Santé », When on tente de créer « santé » avec une autre
     * couleur, Then l'identifiant rendu est celui de TAG-SANTE, la table compte toujours une seule
     * ligne « Santé », son nom et sa couleur sont inchangés, et TAG-PRO est intact (CA-03).
     */
    @Test
    fun `C-03 - Given TAG-SANTE nomme Sante - When on tente de creer sante avec une autre couleur - Then l'existant est rendu inchange (CA-03)`() =
        runTest {
            seedSanteAndPro()

            val returnedId = createTag(NAME_SANTE_LOWERCASE, COLOR_INTRUS)

            assertEquals(
                "C-03 — CA-03 : la création devait rendre l'identifiant du tag existant de même " +
                    "nom normalisé, pas en créer un nouveau ; obtenu $returnedId",
                tagSanteId,
                returnedId,
            )
            assertEquals(
                "C-03 — CA-03 : aucun second tag ne devait être créé ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                2,
                rawRows(SQL_TAGS).size,
            )

            val sante = tagById(tagSanteId)
            assertEquals(
                "C-03 — CA-03 : le nom du tag existant ne doit pas être écrasé par la casse " +
                    "retentée « $NAME_SANTE_LOWERCASE » ; obtenu « ${sante.name} »",
                TAG_SANTE_NAME,
                sante.name,
            )
            assertEquals(
                "C-03 — CA-03 : la couleur du tag existant ne doit pas être écrasée ; " +
                    "obtenu ${sante.colorArgb}",
                COLOR_SANTE,
                sante.colorArgb,
            )

            assertIntrusColorNeverPersisted("C-03")
            assertTagProIntact("C-03")
        }

    // ==============================================================================================
    // U-01 — Renommer conserve l'identifiant et tous les liens
    // ==============================================================================================

    /**
     * U-01 — Given TAG-SANTE lié à TX-PONCT et à SERIE-M, When on le renomme en « Médical » avec
     * une nouvelle couleur, Then il reste exactement un tag portant le même identifiant, avec le
     * nouveau nom et la nouvelle couleur, plus aucune ligne « Santé », et la transaction comme la
     * série restent liées à **cet identifiant** ainsi qu'à Pro (CA-04, I-3).
     */
    @Test
    fun `U-01 - Given TAG-SANTE lie a une transaction et a une serie - When on le renomme en Medical - Then meme identifiant nouveau nom et tous les liens conserves (CA-04, I-3)`() =
        runTest {
            seedSanteProAndLinks()

            updateTag(tagById(tagSanteId), NAME_MEDICAL, COLOR_MEDICAL)

            assertEquals(
                "U-01 — I-3 : renommer ne doit jamais créer un second tag ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                2,
                rawRows(SQL_TAGS).size,
            )

            val renamed = tagById(tagSanteId)
            assertEquals(
                "U-01 — I-3 : le tag renommé devait conserver son identifiant",
                tagSanteId,
                renamed.id,
            )
            assertEquals(
                "U-01 — CA-04 : le nom devait devenir « $NAME_MEDICAL » ; " +
                    "obtenu « ${renamed.name} »",
                NAME_MEDICAL,
                renamed.name,
            )
            assertEquals(
                "U-01 — CA-04 : la couleur devait devenir celle saisie ; " +
                    "obtenu ${renamed.colorArgb}",
                COLOR_MEDICAL,
                renamed.colorArgb,
            )
            assertEquals(
                "U-01 — CA-04 : plus aucune ligne ne devait porter l'ancien nom ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT id FROM tags WHERE name = '$TAG_SANTE_NAME'"),
            )

            assertEquals(
                "U-01 — I-3 : la transaction devait rester liée au même identifiant, plus Pro ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagSanteId, tagProId),
                transactionTagIdsOf(txPonctId),
            )
            assertEquals(
                "U-01 — I-3 : la série devait rester liée au même identifiant, plus Pro ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagSanteId, tagProId),
                seriesTagIdsOf(serieMId),
            )
            assertEquals(
                "U-01 — I-3 : relue par l'API publique, la transaction porte le nouveau nom sans " +
                    "avoir changé de lien ; obtenu ${readTransactionTags()}",
                setOf(NAME_MEDICAL, TAG_PRO_NAME),
                readTransactionTags().map { it.name }.toSet(),
            )
            assertEquals(
                "U-01 — I-3 : relue par l'API publique, la série porte le nouveau nom sans avoir " +
                    "changé de lien ; obtenu ${readSeriesTags()}",
                setOf(NAME_MEDICAL, TAG_PRO_NAME),
                readSeriesTags().map { it.name }.toSet(),
            )

            assertTagProIntact("U-01")
        }

    // ==============================================================================================
    // U-02 — Renommer vers un nom blanc ne touche à rien
    // ==============================================================================================

    /**
     * U-02 — Given TAG-SANTE lié à TX-PONCT et à SERIE-M, When on tente de le renommer en « ␣␣␣ »
     * avec une autre couleur, Then son nom, sa couleur et ses liens sont inchangés et aucune ligne
     * nouvelle n'est écrite (CA-05).
     */
    @Test
    fun `U-02 - Given TAG-SANTE lie a une transaction et a une serie - When on tente de le renommer vers un nom blanc - Then il reste inchange (CA-05)`() =
        runTest {
            seedSanteProAndLinks()
            val tagsBefore = rawRows(SQL_TAGS)

            updateTag(tagById(tagSanteId), NAME_BLANK, COLOR_INTRUS)

            assertEquals(
                "U-02 — CA-05 : un renommage refusé ne doit écrire aucune ligne nouvelle ; " +
                    "avant = $tagsBefore, après = ${rawRows(SQL_TAGS)}",
                tagsBefore,
                rawRows(SQL_TAGS),
            )

            val sante = tagById(tagSanteId)
            assertEquals(
                "U-02 — CA-05 : le nom devait rester « $TAG_SANTE_NAME » ; obtenu « ${sante.name} »",
                TAG_SANTE_NAME,
                sante.name,
            )
            assertEquals(
                "U-02 — CA-05 : la couleur devait rester celle d'origine ; " +
                    "obtenu ${sante.colorArgb}",
                COLOR_SANTE,
                sante.colorArgb,
            )
            assertEquals(
                "U-02 — CA-05 : les liens transaction devaient rester inchangés ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagSanteId, tagProId),
                transactionTagIdsOf(txPonctId),
            )
            assertEquals(
                "U-02 — CA-05 : les liens série devaient rester inchangés ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagSanteId, tagProId),
                seriesTagIdsOf(serieMId),
            )

            assertIntrusColorNeverPersisted("U-02")
            assertTagProIntact("U-02")
        }

    // ==============================================================================================
    // U-03 — Renommer vers un nom déjà porté laisse les deux tags inchangés
    // ==============================================================================================

    /**
     * U-03 — Given TAG-SANTE et TAG-PRO, When on tente de renommer TAG-SANTE en « pro » avec une
     * autre couleur, Then les deux tags gardent leurs noms, leurs couleurs et leurs liens
     * (CA-05, I-2).
     */
    @Test
    fun `U-03 - Given TAG-SANTE et TAG-PRO - When on tente de renommer Sante en pro - Then les deux tags restent inchanges (CA-05, I-2)`() =
        runTest {
            seedSanteProAndLinks()
            val tagsBefore = rawRows(SQL_TAGS)

            updateTag(tagById(tagSanteId), NAME_PRO_LOWERCASE, COLOR_INTRUS)

            assertEquals(
                "U-03 — I-2 : un renommage en collision ne doit écrire aucune ligne ; " +
                    "avant = $tagsBefore, après = ${rawRows(SQL_TAGS)}",
                tagsBefore,
                rawRows(SQL_TAGS),
            )

            val sante = tagById(tagSanteId)
            assertEquals(
                "U-03 — CA-05 : le tag renommé devait garder son nom ; obtenu « ${sante.name} »",
                TAG_SANTE_NAME,
                sante.name,
            )
            assertEquals(
                "U-03 — CA-05 : le tag renommé devait garder sa couleur ; " +
                    "obtenu ${sante.colorArgb}",
                COLOR_SANTE,
                sante.colorArgb,
            )
            assertEquals(
                "U-03 — CA-05 : les liens de Santé devaient rester inchangés ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagSanteId, tagProId),
                transactionTagIdsOf(txPonctId),
            )
            assertEquals(
                "U-03 — CA-05 : les liens série devaient rester inchangés ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagSanteId, tagProId),
                seriesTagIdsOf(serieMId),
            )

            assertIntrusColorNeverPersisted("U-03")
            assertTagProIntact("U-03")
        }

    // ==============================================================================================
    // D-01 — Supprimer un tag sans lien ne touche qu'à lui
    // ==============================================================================================

    /**
     * D-01 — Given TAG-VACANCES sans aucun lien, aux côtés de Santé et Pro liés à TX-PONCT et
     * SERIE-M, When on le supprime, Then plus aucune ligne ni aucun lien ne le désigne, les deux
     * autres tags restent avec leurs liens, et le référentiel observé compte deux entrées
     * (CA-06, I-1, I-2).
     */
    @Test
    fun `D-01 - Given TAG-VACANCES sans aucun lien - When on le supprime - Then il disparait et les deux autres tags restent lies (CA-06, I-1, I-2)`() =
        runTest {
            seedSanteProAndLinks()
            tagVacancesId = db.tagDao().upsert(
                TagEntity(name = TAG_VACANCES_NAME, colorArgb = COLOR_VACANCES),
            )

            deleteTag(tagVacancesId)

            assertEquals(
                "D-01 — CA-06 : plus aucune ligne de `tags` ne devait porter cet identifiant ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT id FROM tags WHERE id = $tagVacancesId"),
            )
            assertEquals(
                "D-01 — I-2 : plus aucun lien transaction ne devait désigner ce tag ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT transactionId FROM transaction_tags WHERE tagId = $tagVacancesId"),
            )
            assertEquals(
                "D-01 — I-2 : plus aucun lien série ne devait désigner ce tag ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT seriesId FROM series_tags WHERE tagId = $tagVacancesId"),
            )
            assertEquals(
                "D-01 — CA-06 : le référentiel observé devait compter exactement deux entrées ; " +
                    "obtenu ${observedTags().map { it.id }}",
                setOf(tagSanteId, tagProId),
                observedTags().map { it.id }.toSet(),
            )
            assertEquals(
                "D-01 — I-1 : supprimer un tag sans lien ne doit toucher aucune autre jointure ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagSanteId, tagProId),
                transactionTagIdsOf(txPonctId),
            )
            assertEquals(
                "D-01 — I-1 : supprimer un tag sans lien ne doit toucher aucun lien de série ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagSanteId, tagProId),
                seriesTagIdsOf(serieMId),
            )

            assertTagProIntact("D-01")
        }

    // ==============================================================================================
    // D-02 — Supprimer un tag utilisé emporte ses liens, jamais ses porteurs
    // ==============================================================================================

    /**
     * D-02 — Given TAG-SANTE utilisé par TX-PONCT **et** par SERIE-M, When on le supprime, Then
     * plus aucune ligne ni aucun lien ne le désigne, la transaction et la série existent toujours
     * et sont lisibles, chacune portant **exactement** son autre tag, et TAG-PRO est intact
     * (CA-07, I-1, I-2).
     */
    @Test
    fun `D-02 - Given TAG-SANTE utilise par une transaction et une serie - When on le supprime - Then ses liens disparaissent et ses porteurs survivent avec Pro seul (CA-07, I-1, I-2)`() =
        runTest {
            seedSanteProAndLinks()

            deleteTag(tagSanteId)

            assertEquals(
                "D-02 — CA-07 : plus aucune ligne de `tags` ne devait porter cet identifiant ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT id FROM tags WHERE id = $tagSanteId"),
            )
            assertEquals(
                "D-02 — CA-07 : il ne devait rester qu'un seul tag au référentiel ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                setOf(tagProId),
                observedTags().map { it.id }.toSet(),
            )
            assertEquals(
                "D-02 — I-2 : aucun lien transaction ne devait subsister vers ce tag ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT transactionId FROM transaction_tags WHERE tagId = $tagSanteId"),
            )
            assertEquals(
                "D-02 — I-2 : aucun lien série ne devait subsister vers ce tag ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT seriesId FROM series_tags WHERE tagId = $tagSanteId"),
            )

            assertEquals(
                "D-02 — I-1 : la transaction ne doit jamais être supprimée avec le tag ; " +
                    "transactions = ${rawRows(SQL_TRANSACTIONS)}",
                1,
                rawRows("SELECT id FROM transactions WHERE id = $txPonctId").size,
            )
            assertEquals(
                "D-02 — I-1 : la série ne doit jamais être supprimée avec le tag ; " +
                    "recurring_series = ${rawRows(SQL_SERIES)}",
                1,
                rawRows("SELECT id FROM recurring_series WHERE id = $serieMId").size,
            )

            assertNotNull(
                "D-02 — I-1 : la transaction devait rester lisible par l'API publique après la " +
                    "suppression du tag",
                db.transactionDao().getById(txPonctId),
            )
            assertEquals(
                "D-02 — CA-07 : la transaction devait porter exactement son autre tag ; " +
                    "obtenu ${readTransactionTags().map { it.id }}",
                setOf(tagProId),
                readTransactionTags().map { it.id }.toSet(),
            )
            assertEquals(
                "D-02 — CA-07 : la série devait porter exactement son autre tag ; " +
                    "obtenu ${readSeriesTags().map { it.id }}",
                setOf(tagProId),
                readSeriesTags().map { it.id }.toSet(),
            )
            assertEquals(
                "D-02 — CA-07 : la jointure transaction ne devait conserver que le lien vers Pro ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagProId),
                transactionTagIdsOf(txPonctId),
            )
            assertEquals(
                "D-02 — CA-07 : la jointure série ne devait conserver que le lien vers Pro ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagProId),
                seriesTagIdsOf(serieMId),
            )

            assertTagProIntact("D-02")
        }

    // ==============================================================================================
    // Jeu de données
    // ==============================================================================================

    /** Santé et Pro seuls : suffisant pour les branches de création et de collision. */
    private suspend fun seedSanteAndPro() {
        tagSanteId = db.tagDao().upsert(TagEntity(name = TAG_SANTE_NAME, colorArgb = COLOR_SANTE))
        tagProId = db.tagDao().upsert(TagEntity(name = TAG_PRO_NAME, colorArgb = COLOR_PRO))
    }

    private suspend fun seedThreeTags() {
        seedSanteAndPro()
        tagVacancesId =
            db.tagDao().upsert(TagEntity(name = TAG_VACANCES_NAME, colorArgb = COLOR_VACANCES))
    }

    /**
     * Santé et Pro, plus une transaction ponctuelle et une série récurrente liées **aux deux**.
     *
     * Les parents sont créés avant les lignes qui les référencent, et les liens sont posés par les
     * API publiques que l'application emprunte. Porter les deux tags est ce qui rend D-02
     * démontrable : après suppression de Santé, « exactement Pro » distingue un `CASCADE` correct
     * d'un effacement trop large.
     */
    private suspend fun seedSanteProAndLinks() {
        seedSanteAndPro()

        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 250_000L,
                balanceUpdatedAt = 0L,
                colorArgb = 0,
                icon = "wallet",
            ),
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = "Dépenses",
                type = TransactionType.EXPENSE,
                colorArgb = 0,
                icon = "category",
                parentCategoryId = null,
            ),
        )

        txPonctId = db.transactionDao().saveWithTags(
            TransactionEntity(
                title = TX_PONCT_TITLE,
                amount = TX_PONCT_AMOUNT,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                date = marsSlot,
                accountId = accountId,
                categoryId = categoryId,
            ),
            listOf(tagSanteId, tagProId),
        )
        serieMId = db.recurringSeriesDao().saveSeriesWithTags(
            RecurringSeriesEntity(
                title = SERIE_M_TITLE,
                amount = SERIE_M_AMOUNT,
                type = TransactionType.EXPENSE,
                categoryId = categoryId,
                accountId = accountId,
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = marsSlot,
            ),
            listOf(tagSanteId, tagProId),
        )
    }

    // ==============================================================================================
    // Constats d'état
    // ==============================================================================================

    /**
     * Collecte la première émission de `ObserveTagsUseCase`, sous abonnement réel et attente bornée.
     *
     * Chaque appel ouvre un nouvel abonnement : la première émission d'un `Flow` Room est l'état
     * courant de la table, ce qui donne l'état d'après-écriture sans réabonnement manuel.
     */
    private suspend fun observedTags(): List<TagEntity> {
        lateinit var emission: List<TagEntity>
        observeTags().test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return emission
    }

    /** Le tag tel que l'écran le lirait — jamais reconstruit à la main depuis les constantes. */
    private suspend fun tagById(id: Long): TagEntity = observedTags().single { it.id == id }

    private suspend fun readTransactionTags(): List<TagEntity> =
        db.transactionDao().getById(txPonctId)?.tags.orEmpty()

    private suspend fun readSeriesTags(): List<TagEntity> =
        db.recurringSeriesDao().getTagsForSeries(serieMId)

    private fun transactionTagIdsOf(txId: Long): Set<Long> =
        rawRows("SELECT tagId FROM transaction_tags WHERE transactionId = $txId ORDER BY tagId")
            .map { it.toLong() }.toSet()

    private fun seriesTagIdsOf(seriesId: Long): Set<Long> =
        rawRows("SELECT tagId FROM series_tags WHERE seriesId = $seriesId ORDER BY tagId")
            .map { it.toLong() }.toSet()

    /** Le témoin : TAG-PRO n'est la cible d'aucune écriture, dans aucun cas. */
    private suspend fun assertTagProIntact(label: String) {
        val pro = tagById(tagProId)
        assertEquals(
            "$label — témoin : TAG-PRO devait garder son nom ; obtenu « ${pro.name} »",
            TAG_PRO_NAME,
            pro.name,
        )
        assertEquals(
            "$label — témoin : TAG-PRO devait garder sa couleur ; obtenu ${pro.colorArgb}",
            COLOR_PRO,
            pro.colorArgb,
        )
    }

    /**
     * `COLOR_INTRUS` n'est tentée que sur des écritures qui doivent être refusées. Si elle
     * apparaît sur une ligne quelconque, c'est qu'un refus n'a pas eu lieu.
     */
    private fun assertIntrusColorNeverPersisted(label: String) {
        assertEquals(
            "$label — la couleur tentée sur un no-op ne doit apparaître sur aucune ligne ; " +
                "tags = ${rawRows(SQL_TAGS)}",
            emptyList<String>(),
            rawRows("SELECT id FROM tags WHERE colorArgb = $COLOR_INTRUS"),
        )
    }

    private fun rawRows(sql: String): List<String> = buildList {
        db.query(sql, emptyArray<Any?>()).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).joinToString("|") { column ->
                        if (cursor.isNull(column)) "null" else cursor.getString(column)
                    },
                )
            }
        }
    }

    // ==============================================================================================
    // Dates
    // ==============================================================================================

    /** Date métier fixe et lisible. Aucun oracle ne porte dessus ; aucune horloge n'est lue. */
    private val marsSlot: Long =
        LocalDate.of(2026, 3, 10).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds

        const val TAG_SANTE_NAME = "Santé"
        const val TAG_PRO_NAME = "Pro"
        const val TAG_VACANCES_NAME = "Vacances"

        /** Noms soumis aux use cases, tous distincts des noms persistés au socle. */
        const val NAME_COURSES_UNTRIMMED = "  Courses  "
        const val NAME_COURSES_TRIMMED = "Courses"
        const val NAME_EMPTY = ""
        const val NAME_BLANK = "   "
        const val NAME_SANTE_LOWERCASE = "santé"
        const val NAME_PRO_LOWERCASE = "pro"
        const val NAME_MEDICAL = "Médical"

        /**
         * Couleurs toutes distinctes : aucune confusion de ligne ne peut tomber juste.
         * `COLOR_INTRUS` n'est tentée que sur des écritures qui doivent être refusées.
         */
        val COLOR_SANTE = 0xFFE91E63.toInt()
        val COLOR_PRO = 0xFF3F51B5.toInt()
        val COLOR_VACANCES = 0xFF009688.toInt()
        val COLOR_MEDICAL = 0xFF4CAF50.toInt()
        val COLOR_INTRUS = 0xFFFF9800.toInt()

        /** Libellés et montants distincts : aucune confusion de porteur ne peut tomber juste. */
        const val TX_PONCT_TITLE = "Consultation ponctuelle"
        const val TX_PONCT_AMOUNT = 4_550L
        const val SERIE_M_TITLE = "Mutuelle mensuelle"
        const val SERIE_M_AMOUNT = 12_900L

        const val SQL_TAGS = "SELECT id, name, colorArgb FROM tags ORDER BY id"
        const val SQL_TRANSACTION_TAGS =
            "SELECT transactionId, tagId FROM transaction_tags ORDER BY transactionId, tagId"
        const val SQL_SERIES_TAGS =
            "SELECT seriesId, tagId FROM series_tags ORDER BY seriesId, tagId"
        const val SQL_TRANSACTIONS = "SELECT id, title, deleted FROM transactions ORDER BY id"
        const val SQL_SERIES = "SELECT id, title FROM recurring_series ORDER BY id"
    }
}
