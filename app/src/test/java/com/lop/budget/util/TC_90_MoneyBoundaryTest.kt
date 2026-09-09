package com.lop.budget.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TC-90 — Frontière euros ↔ centimes (LOP-127).
 *
 * ## Niveau
 * Unitaire JVM pur sur [Format], sans Android ni Room. C'est le seul endroit de l'app autorisé
 * à convertir entre l'euro affiché/saisi et le centime persisté (I-4).
 *
 * ## CA couverts
 * - CA-07 : « 12,34 » saisi persiste 1234 ; 1234 relu s'affiche « 12,34 ». Aller-retour identitaire
 *   pour tout montant à exactement 2 décimales.
 * - CA-08 / CA-12 : la surcharge `money(Long)` produit la même chaîne que l'ancienne
 *   `money(Double)` pour le même montant.
 *
 * ## Non couvert
 * Le calcul du solde (US 78), le formatage des objectifs et dettes (P-4, surcharge `Double`).
 *
 * Les attendus sont écrits en dur. `money(12.34)` sert de référence pour CA-08 parce que
 * le CA définit explicitement la surcharge `Long` comme « la même chaîne qu'aujourd'hui » :
 * la valeur de référence est l'ancien comportement, pas un recalcul par la fonction testée.
 */
class MoneyBoundaryTest {

    // --- CA-07 : saisie euros -> centimes ---------------------------------------------------

    @Test
    fun `B-01 - la virgule et le point decimal donnent les memes centimes`() {
        assertEquals(1234L, Format.centsOrNull("12,34"))
        assertEquals(1234L, Format.centsOrNull("12.34"))
    }

    @Test
    fun `B-02 - une saisie sans decimale ou a une decimale est convertie sans perte`() {
        assertEquals(1200L, Format.centsOrNull("12"))
        assertEquals(1250L, Format.centsOrNull("12,5"))
        assertEquals(0L, Format.centsOrNull("0"))
    }

    @Test
    fun `B-03 - une saisie negative garde son signe`() {
        assertEquals(-199L, Format.centsOrNull("-1,99"))
    }

    @Test
    fun `B-04 - une saisie a trois decimales est arrondie half-up`() {
        // P-2 : round_half_up(valeur x 100). Un Double donnerait 1050 (10,505 x 100 = 1050,4999...).
        assertEquals(1051L, Format.centsOrNull("10,505"))
    }

    @Test
    fun `B-05 - une saisie vide ou non numerique ne produit pas de montant`() {
        assertNull(Format.centsOrNull(""))
        assertNull(Format.centsOrNull("   "))
        assertNull(Format.centsOrNull("abc"))
        assertNull(Format.centsOrNull("."))
    }

    // --- CA-07 : centimes -> texte du champ de saisie ---------------------------------------

    @Test
    fun `B-06 - l'aller-retour euros - centimes - euros est identitaire a 2 decimales`() {
        listOf("12.34", "0.0", "820.0", "1000.5", "-1.99").forEach { input ->
            val cents = Format.centsOrNull(input)!!
            assertEquals("aller-retour de $input", input, Format.centsToInput(cents))
        }
    }

    @Test
    fun `B-07 - 1234 centimes se relit 12 euros 34`() {
        assertEquals("12.34", Format.centsToInput(1234L))
        assertEquals("-1.99", Format.centsToInput(-199L))
    }

    // --- CA-08 / CA-12 : formatage --------------------------------------------------------

    @Test
    fun `B-08 - la surcharge Long affiche la meme chaine que la surcharge Double`() {
        assertEquals(Format.money(12.34), Format.money(1234L))
        assertEquals(Format.money(0.0), Format.money(0L))
        assertEquals(Format.money(-1.99), Format.money(-199L))
        assertEquals(Format.money(8.51), Format.money(851L))
    }

    @Test
    fun `B-09 - un solde en centimes ne s'affiche jamais 100 fois trop grand`() {
        // I-7 : le défaut à chasser, un Long centimes passé à la surcharge Double.
        val huitEurosCinquanteEtUn = Format.money(851L)
        assertEquals(Format.money(8.51), huitEurosCinquanteEtUn)
        assertEquals(false, huitEurosCinquanteEtUn == Format.money(851.0))
    }
}
