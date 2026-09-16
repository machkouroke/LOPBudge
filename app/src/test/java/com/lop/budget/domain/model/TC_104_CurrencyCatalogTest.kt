package com.lop.budget.domain.model

import com.lop.budget.domain.usecase.SearchCurrenciesUseCase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * TC-104 — Catalogue de devises : contenu, tri, recherche et visuel.
 * **Niveau unitaire JVM pur.** Ni Android, ni Room, ni réseau, ni doublure.
 *
 * Chaîne exercée : `CurrencyCatalog.all` / `.byCodeOrNull` / `.byCodeOrDefault` (réels) →
 * `CURRENCY_CATALOG` (ressource générée, P-1) ; `AppCurrency.flag` / `.label` (réels) ;
 * `SearchCurrenciesUseCase.invoke` (réel) → `TextSearch.normalize` (réel).
 * Aucun mock : aucun de ces éléments n'a de dépendance injectable, la politique MockK de
 * `app/src/test/AGENTS.md` n'a rien à arbitrer ici.
 *
 * Source de vérité : CA-04, CA-05, CA-10, CA-12, CA-15, les invariants I-1, I-4, I-6, I-7 de l'US
 * LOP-58, et les conventions P-1 à P-4.
 * **Le contenu actuel du catalogue ne constitue pas l'oracle.** Les huit devises de tête, les codes
 * bannis et les drapeaux attendus viennent de la fiche et des conventions, jamais du fichier généré.
 *
 * ### cas → CA / invariant → production
 * ```
 * T-01   parcours intégral de `all`     I-1, I-6, CA-12, P-1   CurrencyCatalog.all, AppCurrency.flag/.label
 * T-02   ordre d'affichage              P-3                    CurrencyCatalog.all
 * T-03a  six écritures de « euro »      CA-04, P-4             SearchCurrenciesUseCase.invoke
 * T-03b  accents repliés                CA-04, P-4             SearchCurrenciesUseCase.invoke
 * T-03c  correspondance par contenu     CA-04, P-3, P-4        SearchCurrenciesUseCase.invoke
 * T-04   terme vide ou blanc            P-4                    SearchCurrenciesUseCase.invoke
 * T-05   terme sans correspondance      CA-05                  SearchCurrenciesUseCase.invoke
 * T-06   drapeaux et substitut          CA-10, I-6, P-2        AppCurrency.flag
 * T-07   validation d'un code           I-1, CA-11             CurrencyCatalog.byCodeOrNull/byCodeOrDefault
 * ```
 *
 * ### Écarts fiche / code relevés avant écriture, et reportés dans la fiche Notion
 * - La fiche annonçait `CurrencyOption`, `CurrencyCatalog.search`, `.byCode` et `.flagEmoji` comme
 *   « signature cible à livrer ». **L'US 58 est livrée** sous d'autres noms : `AppCurrency`,
 *   `SearchCurrenciesUseCase`, `byCodeOrNull` / `byCodeOrDefault`, et la propriété `AppCurrency.flag`.
 *   Décision du 16 septembre 2026 : aligner la fiche sur le code plutôt que renommer la production.
 *   Aucun oracle n'a changé dans l'opération.
 * - T-07 laissait ouvert le contrat de `"eur"` et `"eur "`. **Tranché** : I-1 interdit qu'une casse
 *   différente soit retenue, et `byCodeOrNull` documente une comparaison sensible à la casse et aux
 *   espaces. L'attendu est donc `null`, et `byCodeOrDefault` replie sur `EUR`.
 * - La fiche donnait `¥` comme symbole de D-JPY. Le catalogue porte `JPY`, ce qu'impose P-1
 *   (symboles résolus en locale française). Ce cas n'asserte donc que ce que CA-10 exige vraiment :
 *   un symbole non vide. Voir TC-106 pour le nombre de décimales du yen.
 *
 * ### Vérification de lecture, non couvrable par un test
 * P-4 exige que la recherche de devises réutilise **la même** fonction de normalisation que la
 * recherche de transactions. Vérifié par lecture le 16 septembre 2026 : `SearchCurrenciesUseCase` et
 * `SearchTransactionsUseCase` passent tous deux par `util/TextSearch.normalize`. Aucun test ne peut
 * détecter une duplication future — seule une relecture le peut.
 *
 * ### Hors périmètre, explicitement
 * - Repris de l'US : conversion et taux de change, devise par compte, format régional d'affichage,
 *   devise lue dans une notification bancaire.
 * - Ce niveau ne prouve **pas** la persistance de la préférence (TC-105) ni le formatage des
 *   montants (TC-106).
 * - CA-02 et CA-03 — ouverture de la feuille au geste, ligne sélectionnée visible sans défilement —
 *   restent des vérifications sur appareil, l'US les déclare non automatisées.
 * - **CA-15 n'est couvert que par construction** : ce test est du JVM pur, il ne peut pas atteindre
 *   le réseau, donc il ne peut pas non plus prouver qu'un appel réseau serait absent en production.
 *   La vérification en mode avion reste manuelle.
 */
class CurrencyCatalogTest {

    private lateinit var defaultLocale: Locale
    private lateinit var defaultZone: TimeZone

    private val search = SearchCurrenciesUseCase()

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        defaultZone = TimeZone.getDefault()
        Locale.setDefault(Locale.FRANCE)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        TimeZone.setDefault(defaultZone)
    }

    // ---------------------------------------------------------------------------------------
    // Contrat de la fiche, déclaré localement et non partagé.
    //
    // Ces trois listes conditionnent une cardinalité ou un ordre exact. Les mutualiser avec une
    // autre fiche ferait qu'un futur changement de P-3 déplacerait le rouge hors d'ici.
    // ---------------------------------------------------------------------------------------

    /** P-3 : devises courantes en tête, dans cet ordre exact. */
    private val teteImposee = listOf("EUR", "USD", "GBP", "CHF", "CAD", "JPY", "XOF", "MAD")

    /** P-1 : codes techniques et métaux, exclus du catalogue affichable. */
    private val codesBannis = listOf("XXX", "XTS", "XAU", "XAG", "XDR")

    /** Ancres de la fiche qui doivent exister pour que les autres cas aient un sens. */
    private val ancresAttendues = listOf("EUR", "USD", "GBP", "JPY", "XOF")

    // ---------------------------------------------------------------------------------------
    // T-01 — Toute entrée du catalogue est affichable. Parcours intégral, pas d'échantillon.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given le catalogue when parcouru entierement then chaque entree est affichable`() {
        val all = CurrencyCatalog.all

        assertTrue(
            "T-01 / P-1 : le catalogue embarqué ne peut pas être vide, la feuille n'aurait rien " +
                "à afficher",
            all.isNotEmpty(),
        )

        val codes = all.map { it.code }
        val doublons = codes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(
            "T-01 / I-1 : aucun code ne doit apparaître deux fois, deux lignes identiques rendraient " +
                "la sélection ambiguë — doublons trouvés $doublons sur ${apercu(all)}",
            emptySet<String>(),
            doublons,
        )

        val malFormes = codes.filterNot { it.matches(Regex("^[A-Z]{3}$")) }
        assertEquals(
            "T-01 / I-1 : un code ISO 4217 fait exactement trois lettres majuscules — fautifs " +
                "$malFormes",
            emptyList<String>(),
            malFormes,
        )

        val ancresManquantes = ancresAttendues.filterNot { it in codes }
        assertEquals(
            "T-01 / P-1 : ces devises de référence doivent figurer au catalogue — manquantes " +
                "$ancresManquantes",
            emptyList<String>(),
            ancresManquantes,
        )

        val bannisPresents = codesBannis.filter { it in codes }
        assertEquals(
            "T-01 / P-1 : codes de fonds, métaux et codes d'essai sont exclus du catalogue " +
                "affichable — présents à tort $bannisPresents",
            emptyList<String>(),
            bannisPresents,
        )

        // I-6 et CA-12 : les quatre champs observés de **chaque** entrée, pas d'un échantillon.
        val sansNom = all.filter { it.name.isBlank() }.map { it.code }
        val sansSymbole = all.filter { it.symbol.isBlank() }.map { it.code }
        val sansDrapeau = all.filter { it.flag.isBlank() }.map { it.code }
        val libelleIncomplet = all
            .filterNot { it.label.contains(it.code) && it.label.contains(it.name) }
            .map { it.code }

        assertEquals(
            "T-01 / I-6 : une devise sans nom ne serait pas identifiable — fautives $sansNom",
            emptyList<String>(),
            sansNom,
        )
        assertEquals(
            "T-01 / CA-10 : une devise sans symbole ne peut pas légender un montant — fautives " +
                "$sansSymbole",
            emptyList<String>(),
            sansSymbole,
        )
        assertEquals(
            "T-01 / I-6 : aucune ligne ne s'affiche sans visuel, le substitut de P-2 tient lieu de " +
                "drapeau à défaut de pays — fautives $sansDrapeau",
            emptyList<String>(),
            sansDrapeau,
        )
        assertEquals(
            "T-01 / CA-12, I-7 : le libellé doit porter le code **et** le nom, pour rester " +
                "identifiable sans le drapeau et au lecteur d'écran — fautives $libelleIncomplet",
            emptyList<String>(),
            libelleIncomplet,
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-02 — L'ordre d'affichage est figé par P-3 et ne dépend pas de la machine.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given le catalogue when lu deux fois then ordre de P-3 stable et queue alphabetique`() {
        val premiere = CurrencyCatalog.all.map { it.code }
        val seconde = CurrencyCatalog.all.map { it.code }

        assertEquals(
            "T-02 / P-3 : les huit devises courantes ouvrent la liste dans cet ordre exact — " +
                "obtenu ${premiere.take(teteImposee.size)}",
            teteImposee,
            premiere.take(teteImposee.size),
        )

        val queue = premiere.drop(teteImposee.size)
        val desordre = queue.zipWithNext().filterNot { (a, b) -> a < b }
        assertEquals(
            "T-02 / P-3 : passé les devises courantes, les codes sont strictement croissants — " +
                "ruptures $desordre",
            emptyList<Pair<String, String>>(),
            desordre,
        )

        val teteRepetee = queue.filter { it in teteImposee }
        assertEquals(
            "T-02 / P-3 : une devise de tête ne doit pas réapparaître dans la queue — répétées " +
                "$teteRepetee",
            emptyList<String>(),
            teteRepetee,
        )

        assertEquals(
            "T-02 / P-3 : deux lectures successives doivent donner la même liste position par " +
                "position, sinon l'ordre dépend de l'exécution",
            premiere,
            seconde,
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-03 — La recherche ignore la casse, les accents et les espaces de bord, et filtre par
    // contenu. Les attendus sont des égalités croisées : le code ne peut pas les offrir.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given six ecritures de euro when recherchees then meme resultat contenant EUR`() {
        val variantes = listOf("eur", "EUR", "Eur", "euro", "€", "  eur ")
        val resultats = variantes.associateWith { search(it) }

        resultats.forEach { (terme, resultat) ->
            assertTrue(
                "T-03a / CA-04 : « $terme » doit ramener EUR — obtenu ${apercu(resultat)}",
                resultat.any { it.code == "EUR" },
            )
            assertOrdreDeAll(resultat, "T-03a", terme)
        }

        val distincts = resultats.values.map { liste -> liste.map { it.code } }.toSet()
        assertEquals(
            "T-03a / CA-04, P-4 : casse, accents, symbole et espaces de bord doivent être " +
                "indifférents, les six écritures de « euro » doivent donc rendre la même liste — " +
                "obtenu ${resultats.mapValues { entree -> entree.value.map { it.code } }}",
            1,
            distincts.size,
        )
    }

    @Test
    fun `given un nom accentue when recherche avec et sans accent then meme resultat`() {
        val sansAccent = search("suedoise")
        val avecAccent = search("suédoise")

        assertTrue(
            "T-03b / CA-04, P-4 : « suedoise » sans accent doit ramener la couronne suédoise (SEK) " +
                "— obtenu ${apercu(sansAccent)}",
            sansAccent.any { it.code == "SEK" },
        )
        assertEquals(
            "T-03b / P-4 : le repli des accents rend les deux écritures équivalentes — " +
                "sans accent ${apercu(sansAccent)}, avec accent ${apercu(avecAccent)}",
            sansAccent.map { it.code },
            avecAccent.map { it.code },
        )
    }

    @Test
    fun `given un fragment interne when recherche then correspondance par contenu et non par prefixe`() {
        val resultat = search("oll")

        assertTrue(
            "T-03c / CA-04, P-4 : « oll » est au milieu de « dollar », la correspondance se fait " +
                "par contenu et doit donc ramener USD — obtenu ${apercu(resultat)}",
            resultat.any { it.code == "USD" },
        )
        assertTrue(
            "T-03c / CA-04 : « oll » n'apparaît ni dans le code, ni dans le nom, ni dans le symbole " +
                "de EUR : la recherche filtre réellement — obtenu ${apercu(resultat)}",
            resultat.none { it.code == "EUR" },
        )
        assertOrdreDeAll(resultat, "T-03c", "oll")
    }

    // ---------------------------------------------------------------------------------------
    // T-04 / T-05 — Les deux bords du filtre : tout, et rien.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given un terme vide ou blanc when recherche then catalogue complet`() {
        val attendu = CurrencyCatalog.all.map { it.code }

        listOf("", "   ").forEach { terme ->
            assertEquals(
                "T-04 / P-4 : une saisie vide ramène le catalogue complet, dans l'ordre de P-3 " +
                    "— terme « $terme »",
                attendu,
                search(terme).map { it.code },
            )
        }
    }

    @Test
    fun `given un terme sans correspondance when recherche then aucune ligne`() {
        val resultat = search("zzzzz")

        assertEquals(
            "T-05 / CA-05 : une recherche sans correspondance n'affiche aucune ligne — obtenu " +
                "${apercu(resultat)}",
            0,
            resultat.size,
        )
        assertTrue(
            "T-05 / CA-05 : en particulier la devise courante ne doit pas rester en repli dans la " +
                "liste — obtenu ${apercu(resultat)}",
            resultat.none { it.code == "EUR" },
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-06 — Le drapeau, dérivé du pays de référence, avec substitut quand il n'y en a pas.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given des devises avec et sans pays when flag then drapeau attendu ou substitut neutre`() {
        // Attendus écrits en dur. Deux drapeaux emoji sont indiscernables à l'œil dans un source :
        // le point de code de chacun est donc nommé en commentaire, pour qu'une relecture puisse
        // vérifier l'attendu sans exécuter le test.
        val attendus = listOf(
            Triple("EUR", "🇪🇺", "U+1F1EA U+1F1FA — P-2 tranche explicitement ce cas"),
            Triple("USD", "🇺🇸", "U+1F1FA U+1F1F8"),
            Triple("JPY", "🇯🇵", "U+1F1EF U+1F1F5"),
            Triple("XOF", "🏳️", "U+1F3F3 U+FE0F — D-SANSPAYS, substitut neutre de P-2"),
        )

        attendus.forEach { (code, attendu, pointsDeCode) ->
            val devise = CurrencyCatalog.byCodeOrNull(code)
                ?: throw AssertionError(
                    "T-06 : $code doit exister au catalogue pour que ce cas ait un sens",
                )
            assertEquals(
                "T-06 / CA-10, I-6 : drapeau attendu pour $code ($pointsDeCode), pays de référence " +
                    "« ${devise.country} » — obtenu « ${devise.flag} »",
                attendu,
                devise.flag,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // T-07 — Le point de passage unique de la validation d'un code (I-1).
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given des codes exacts ou approchants when byCode then seul le code exact est reconnu`() {
        val euro = CurrencyCatalog.byCodeOrNull("EUR")
        assertEquals(
            "T-07 / I-1 : « EUR » est un code du catalogue et doit être reconnu — obtenu $euro",
            "EUR",
            euro?.code,
        )

        // I-1 : « une chaîne libre, un code inconnu ou une casse différente ne sont jamais
        // persistés ». Les quatre écritures ci-dessous sont donc des inconnus, pas des EUR.
        listOf("eur", "eur ", "", "ABC").forEach { entree ->
            assertNull(
                "T-07 / I-1 : « $entree » n'est pas exactement un code du catalogue, la casse et " +
                    "les espaces comptent — obtenu ${CurrencyCatalog.byCodeOrNull(entree)}",
                CurrencyCatalog.byCodeOrNull(entree),
            )
            assertEquals(
                "T-07 / CA-11 : une préférence corrompue « $entree » doit dégrader l'affichage vers " +
                    "l'euro plutôt que faire échouer le formatage",
                "EUR",
                CurrencyCatalog.byCodeOrDefault(entree).code,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Outils du test. Aucun ne produit d'attendu : ils décrivent l'observé.
    // ---------------------------------------------------------------------------------------

    /**
     * P-3 : « pendant une recherche, l'ordre est conservé ». Un résultat de recherche doit donc être
     * une sous-suite de [CurrencyCatalog.all], et pas seulement le bon ensemble de devises.
     */
    private fun assertOrdreDeAll(resultat: List<AppCurrency>, cas: String, terme: String) {
        val retenus = resultat.map { it.code }.toSet()
        assertEquals(
            "$cas / P-3 : le filtre conserve l'ordre du catalogue, il ne le retrie pas — " +
                "terme « $terme », obtenu ${apercu(resultat)}",
            CurrencyCatalog.all.map { it.code }.filter { it in retenus },
            resultat.map { it.code },
        )
    }

    /** Liste des codes obtenus, tronquée à 20 comme l'exige la fiche. */
    private fun apercu(liste: List<AppCurrency>): String {
        val codes = liste.map { it.code }
        return if (codes.size <= 20) "$codes (${codes.size})"
        else "${codes.take(20)} … (+${codes.size - 20})"
    }
}
