package com.lop.budget.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * TC-106 — Formatage des montants selon la devise choisie.
 * **Niveau unitaire JVM pur.** Ni Android, ni Room, ni réseau, ni doublure.
 *
 * Chaîne exercée : `Format.money(Long, String, Locale)` → `Format.money(Double, …)` →
 * `Format.currencyFormat` (cache `ConcurrentHashMap`) → `NumberFormat`/`Currency` de la plateforme.
 * Et `Format.currencySymbol(String, Locale)`. Aucun mock : ce sont des calculs purs, la politique
 * MockK de `app/src/test/AGENTS.md` n'a rien à arbitrer ici.
 *
 * Source de vérité : CA-09 et CA-14, les invariants I-2 et I-4 de l'US LOP-58, et la convention
 * P-5 (« la devise est un choix d'affichage, jamais une conversion »).
 * **Le comportement actuel de `Format` ne constitue pas l'oracle.** Les attendus ci-dessous
 * viennent de la fiche, jamais du code.
 *
 * ### Normalisation appliquée avant comparaison
 * Le formatage français insère des espaces insécables entre les groupes de milliers et devant le
 * symbole : U+00A0, U+202F (fine insécable) et U+2009. Les trois sont repliés sur l'espace
 * ordinaire par [spaces] avant toute comparaison. Sans cela les attendus échoueraient pour une
 * raison typographique et non métier. **Aucune autre transformation** n'est appliquée : le nombre
 * de décimales, le séparateur décimal et le symbole sont comparés tels quels.
 *
 * ### cas → CA / invariant → production
 * ```
 * T-01a..d  M-1234 en EUR/USD/GBP/CHF   CA-14, I-2   Format.money — chaîne complète en dur
 * T-01e     parties chiffrées égales    I-2, P-5     Format.money — preuve d'absence de conversion
 * T-02      M-1234 et M-0 en JPY        CA-14        Format.money — zéro décimale
 * T-03a     M-1     (0,01 €)            I-2          Format.money — le centime unitaire survit
 * T-03b     M-NEG   (-12,34 €)          I-2          Format.money — signe conservé
 * T-03c     M-GROS  (1 234 567,89 €)    I-2          Format.money — milliers, aucun arrondi
 * T-03d     M-MAX                       I-2          Format.money — ANO-B, rouge attendu
 * T-04a     currencySymbol ×4           CA-14        Format.currencySymbol — symboles en dur
 * T-04b     money("ABC") et money("")   I-1          Format.money — ni exception, ni sortie vide
 * T-05      EUR / USD / EUR             I-2          Format.currencyFormat — étanchéité du cache
 * ```
 *
 * ### Écarts fiche / code relevés avant écriture, et reportés dans la fiche Notion
 * - La fiche annonçait `¥` comme symbole du yen. **Le catalogue porte `JPY`**, parce que P-1 impose
 *   de résoudre les symboles en locale française, où la plateforme rend `JPY`. Asserter `¥` ferait
 *   échouer T-02 pour une raison qui n'est pas celle du CA : l'oracle de CA-14 est le **nombre de
 *   décimales**. La fiche a été corrigée le 16 septembre 2026.
 * - La fiche redoutait des décimales codées en dur à deux. **Écarté** : `currencyFormat` force
 *   `min/maximumFractionDigits` sur `defaultFractionDigits` de la devise choisie. T-02 est donc
 *   attendu vert, et sa sensibilité est prouvée par mutation, pas par le rouge.
 *
 * ### ANO traitée
 * - **ANO-B** — <https://app.notion.com/p/3dd50f34a8c58120b4b7c8d819392894>
 *   « Un montant très élevé s'affiche arrondi : money(Long) transite par un flottant ».
 *   `Format.money(Long)` divisait par `100.0` puis déléguait à la surcharge `Double`. Un flottant
 *   ne représente pas exactement `Long.MAX_VALUE / 100`, si bien que le montant affiché s'écartait
 *   du montant stocké sans le moindre avertissement — 1,93 € sur cette valeur. Contredisait I-2
 *   (« seul le symbole et le nombre de décimales affichés changent »).
 *
 *   **RED le 16 septembre 2026** (T-03d, message conservé dans la fiche Notion), **corrigé et GREEN
 *   le 17 septembre 2026** : `money(Long)` construit désormais un `BigDecimal.valueOf(cents, 2)`,
 *   valeur exacte que `NumberFormat` formate sans repasser par un flottant. L'oracle de T-03d n'a
 *   pas bougé d'un caractère entre le rouge et le vert — c'est la production qui a changé.
 *
 * ### Hors périmètre, explicitement
 * - Repris de l'US : conversion et taux de change, devise par compte, devise lue dans une
 *   notification bancaire.
 * - **La position du symbole et les séparateurs restent français quelle que soit la devise**, parce
 *   que `Format` fige `Locale.FRANCE`. L'US le déclare hors périmètre (ticket de localisation) :
 *   `12,34 $US` n'est donc pas un défaut ici, et T-01 ne l'asserte que pour verrouiller la partie
 *   chiffrée.
 * - Ce niveau ne prouve **pas** que chaque écran passe la devise courante au formateur : CA-13 est
 *   porté par TC-105.
 */
class CurrencyFormattingTest {

    private lateinit var defaultLocale: Locale
    private lateinit var defaultZone: TimeZone

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
    // Jeu de données de la fiche. Les montants sont des centimes, exactement ce que la base stocke.
    // ---------------------------------------------------------------------------------------

    private val mille234 = 1234L          // M-1234
    private val zero = 0L                 // M-0
    private val unCentime = 1L            // M-1
    private val negatif = -1234L          // M-NEG
    private val gros = 123_456_789L       // M-GROS
    private val max = Long.MAX_VALUE      // M-MAX

    // ---------------------------------------------------------------------------------------
    // T-01 — Un même montant, quatre devises : le symbole change, le nombre jamais.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given M-1234 when formate en EUR then deux decimales et symbole euro`() =
        assertMoney(mille234, "EUR", "12,34 €", "T-01a")

    @Test
    fun `given M-1234 when formate en USD then deux decimales et symbole dollar`() =
        assertMoney(mille234, "USD", "12,34 \$US", "T-01b")

    @Test
    fun `given M-1234 when formate en GBP then deux decimales et symbole livre`() =
        assertMoney(mille234, "GBP", "12,34 £GB", "T-01c")

    @Test
    fun `given M-1234 when formate en CHF then deux decimales et symbole franc suisse`() =
        assertMoney(mille234, "CHF", "12,34 CHF", "T-01d")

    /**
     * Le cœur de P-5 : changer de devise ne convertit rien.
     *
     * On retire de chaque sortie tout ce qui n'est ni chiffre ni séparateur, puis on compare les
     * quatre parties chiffrées **entre elles**. Un taux de change appliqué quelque part ferait
     * diverger au moins l'une d'elles, même si chaque attendu individuel de T-01a..d avait été
     * recopié de travers.
     */
    @Test
    fun `given M-1234 when formate en quatre devises then aucune conversion du montant`() {
        val chiffres = listOf("EUR", "USD", "GBP", "CHF").associateWith { code ->
            money(mille234, code).filter { it.isDigit() || it == ',' || it == '.' }
        }

        chiffres.forEach { (code, part) ->
            assertEquals(
                "T-01e / CA-14, I-2, P-5 : la partie chiffrée de $mille234 centimes en $code " +
                    "devrait rester 12,34 — obtenu « $part » dans « ${money(mille234, code)} »",
                "12,34",
                part,
            )
        }
        assertEquals(
            "T-01e / I-2, P-5 : les quatre devises doivent rendre la même partie chiffrée, " +
                "sinon une conversion s'est glissée dans le formatage — obtenu $chiffres",
            1,
            chiffres.values.toSet().size,
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-02 — Le yen n'a pas de subdivision : zéro décimale, et le montant stocké ne bouge pas.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given M-1234 et M-0 when formates en JPY then aucune decimale affichee`() {
        val yen1234 = money(mille234, "JPY")
        val yen0 = money(zero, "JPY")

        listOf(mille234 to yen1234, zero to yen0).forEach { (cents, rendu) ->
            assertFalse(
                "T-02 / CA-14 : le yen n'a pas de subdivision, aucun séparateur décimal ne doit " +
                    "apparaître pour $cents centimes — obtenu « $rendu »",
                rendu.contains(',') || rendu.contains('.'),
            )
        }
        assertEquals(
            "T-02 / CA-14 : $mille234 centimes en JPY doivent s'afficher « 12 » sans décimale — " +
                "obtenu « $yen1234 »",
            "12",
            yen1234.filter { it.isDigit() },
        )
        assertEquals(
            "T-02 / CA-14 : $zero centime en JPY doit s'afficher « 0 » sans décimale — " +
                "obtenu « $yen0 »",
            "0",
            yen0.filter { it.isDigit() },
        )
        // I-6 par ricochet : une devise sans glyphe dédiée reste affichable.
        assertTrue(
            "T-02 / CA-10 : le rendu du yen ne doit pas se réduire au nombre — obtenu « $yen1234 »",
            yen1234.any { !it.isDigit() && !it.isWhitespace() },
        )

        // Comparaison exigée par la fiche : l'euro, lui, garde ses deux décimales.
        assertMoney(zero, "EUR", "0,00 €", "T-02")
        assertNotEquals(
            "T-02 / CA-14 : le même montant nul ne peut pas se rendre à l'identique en EUR et en " +
                "JPY, puisque le nombre de décimales diffère",
            money(zero, "EUR"),
            yen0,
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-03 — Limites de la conversion centimes → affichage, à devise constante.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given M-1 when formate en EUR then le centime unitaire survit`() =
        assertMoney(unCentime, "EUR", "0,01 €", "T-03a")

    @Test
    fun `given M-NEG when formate en EUR then le signe est conserve`() =
        assertMoney(negatif, "EUR", "-12,34 €", "T-03b")

    @Test
    fun `given M-GROS when formate en EUR then milliers separes et aucun arrondi`() =
        assertMoney(gros, "EUR", "1 234 567,89 €", "T-03c")

    /**
     * **ANO-B — rouge attendu.**
     *
     * L'attendu est la valeur exacte du montant stocké, conformément à I-2. `money(Long)` passe par
     * un `Double` : le résultat observé s'arrête sur un multiple de 16 centimes et affiche
     * `…760,00` au lieu de `…758,07`. L'écart est silencieux, c'est ce qui le rend gênant.
     *
     * Ne pas assouplir cet oracle : il ne coûte rien de plus qu'une ANO ouverte, et l'assouplir
     * signerait qu'un montant affiché a le droit de s'écarter du montant stocké.
     */
    @Test
    fun `given M-MAX when formate en EUR then le montant affiche egale le montant stocke`() {
        val rendu = money(max, "EUR")

        assertTrue(
            "T-03d / I-2 : le formatage d'un montant extrême ne doit produire ni sortie vide ni " +
                "notation scientifique — obtenu « $rendu »",
            rendu.isNotEmpty() && !rendu.contains('E'),
        )
        assertEquals(
            "T-03d / I-2 (ANO-B) : $max centimes valent 92 233 720 368 547 758,07 €. " +
                "money(Long) divise par 100.0 et perd la précision du Long — obtenu « $rendu »",
            "92 233 720 368 547 758,07 €",
            rendu,
        )
    }

    // ---------------------------------------------------------------------------------------
    // T-04 — Symbole seul, et robustesse aux codes que I-1 rend normalement inatteignables.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `given quatre devises when currencySymbol then symbole non vide en locale francaise`() {
        val attendus = mapOf(
            "EUR" to "€",
            "USD" to "\$US",
            "JPY" to "JPY",
            "GBP" to "£GB",
        )

        attendus.forEach { (code, attendu) ->
            val obtenu = spaces(Format.currencySymbol(code, Locale.FRANCE))
            assertTrue(
                "T-04a / CA-10 : le symbole de $code ne peut pas être vide",
                obtenu.isNotEmpty(),
            )
            assertEquals(
                "T-04a / CA-14 : symbole attendu pour $code en locale française — obtenu « $obtenu »",
                attendu,
                obtenu,
            )
        }
    }

    /**
     * Codes hors catalogue.
     *
     * L'US ne définit aucun contrat pour eux, et I-1 les rend en principe inatteignables : seule une
     * devise du catalogue est persistée. L'exigence retenue est donc celle de la fiche en l'absence
     * de contrat — **ni plantage, ni sortie vide** — et l'ambiguïté est signalée plutôt que comblée
     * par une spécification inventée.
     */
    @Test
    fun `given codes hors catalogue when money then ni exception ni sortie vide`() {
        listOf("ABC", "").forEach { code ->
            val rendu = runCatching { money(mille234, code) }.getOrElse { erreur ->
                throw AssertionError(
                    "T-04b / I-1 : formater $mille234 centimes avec le code « $code » ne doit pas " +
                        "lever d'exception — obtenu ${erreur::class.simpleName}: ${erreur.message}",
                )
            }
            assertTrue(
                "T-04b / I-1 : formater $mille234 centimes avec le code « $code » doit rendre une " +
                    "sortie non vide — obtenu « $rendu »",
                rendu.isNotBlank(),
            )
            assertEquals(
                "T-04b / I-2 : même avec un code inconnu, la partie chiffrée reste celle du montant " +
                    "stocké — obtenu « $rendu »",
                "12,34",
                rendu.filter { it.isDigit() || it == ',' || it == '.' },
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // T-05 — Le cache de formats ne fait pas fuiter une devise sur une autre.
    // ---------------------------------------------------------------------------------------

    /**
     * Le cache est indexé par `"code|locale"`. Deux façons de le prendre en défaut : formater une
     * autre devise entre deux rendus, et changer la locale par défaut de la JVM sous ses pieds.
     * On fait les deux, et on exige que les deux rendus `EUR` restent identiques entre eux **et**
     * identiques à celui de T-01a.
     */
    @Test
    fun `given devise alternee et locale JVM modifiee when money then le rendu EUR ne bouge pas`() {
        val avant = money(mille234, "EUR")

        val localeJvm = Locale.getDefault()
        val entreDeux: String
        try {
            Locale.setDefault(Locale.US)
            entreDeux = money(mille234, "USD")
        } finally {
            Locale.setDefault(localeJvm)
        }
        val apres = money(mille234, "EUR")

        assertEquals(
            "T-05 / I-2 : le rendu EUR doit être insensible au formatage d'une autre devise et à " +
                "la locale de la machine — avant « $avant », après « $apres » " +
                "(rendu USD intercalé : « $entreDeux »)",
            avant,
            apres,
        )
        assertEquals(
            "T-05 / I-2 : le rendu EUR doit rester celui de T-01a — obtenu « $apres »",
            "12,34 €",
            apres,
        )
        assertNotEquals(
            "T-05 / I-2 : le rendu USD intercalé doit différer du rendu EUR, sinon le cache a " +
                "servi le même format pour deux devises",
            apres,
            entreDeux,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Outils du test. Aucun ne produit d'attendu : ils normalisent l'observé.
    // ---------------------------------------------------------------------------------------

    private fun assertMoney(cents: Long, code: String, attendu: String, cas: String) {
        val obtenu = money(cents, code)
        assertEquals(
            "$cas / CA-14, I-2 : $cents centimes en $code — obtenu « $obtenu »",
            attendu,
            obtenu,
        )
    }

    /** Sortie réelle du système testé, espaces insécables repliés. Jamais un attendu. */
    private fun money(cents: Long, code: String): String =
        spaces(Format.money(cents, code, Locale.FRANCE))

    /** Repli des trois espaces insécables du formatage français sur l'espace ordinaire. */
    private fun spaces(text: String): String =
        text
            .replace(' ', ' ') // NO-BREAK SPACE
            .replace(' ', ' ') // NARROW NO-BREAK SPACE
            .replace(' ', ' ') // THIN SPACE
}
