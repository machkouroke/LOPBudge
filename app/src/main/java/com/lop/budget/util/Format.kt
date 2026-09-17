package com.lop.budget.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object Format {
    /**
     * `NumberFormat` par couple (devise, locale).
     *
     * `getCurrencyInstance` fait une résolution ICU et alloue un `DecimalFormat` à chaque appel.
     * [money] est appelée une fois par ligne de transaction, par en-tête de jour et par carte de
     * compte, donc à chaque recomposition pendant le défilement : le coût est reconduit à chaque
     * frame. Les clés sont bornées par le nombre de devises réellement affichées.
     *
     * `NumberFormat` n'est pas thread-safe et les composables lisent ce cache depuis le thread UI
     * pendant que les ViewModels peuvent formater sur `Dispatchers.Default` : le format est donc
     * cloné à chaque usage. Le clone reste bien moins cher que la résolution ICU complète.
     */
    private val currencyFormats = java.util.concurrent.ConcurrentHashMap<String, NumberFormat>()

    private fun currencyFormat(currencyCode: String, locale: Locale): NumberFormat =
        currencyFormats.getOrPut("$currencyCode|$locale") {
            NumberFormat.getCurrencyInstance(locale).apply {
                val selected = Currency.getInstance(currencyCode)
                currency = selected
                // `currency = …` remplace le symbole mais laisse le nombre de décimales de la
                // devise **de la locale** : en français, tout s'affichait avec deux décimales,
                // y compris le yen, qui n'a pas de subdivision. Le format suit la devise
                // choisie, pas la langue de l'appareil (CA-14).
                minimumFractionDigits = selected.defaultFractionDigits
                maximumFractionDigits = selected.defaultFractionDigits
            }
        }.clone() as NumberFormat

    /**
     * Montants en euros `Double`.
     *
     * Ne subsiste que pour les taux et les valeurs d'affichage qui ne sont pas des montants
     * stockés. Depuis LOP-80, objectifs et prêts comptent en centimes comme les transactions
     * (I-3) : pour un montant lu en base, utiliser la surcharge `Long`.
     */
    fun money(amount: Double, currencyCode: String = "EUR", locale: Locale = Locale.FRANCE): String {
        return runCatching {
            currencyFormat(currencyCode, locale).format(amount)
        }.getOrElse { String.format(locale, "%.2f %s", amount, currencyCode) }
    }

    /**
     * Soldes et transactions de compte : centimes -> euros affichés. `money(1234L)` == `money(12.34)`.
     *
     * Passe par [BigDecimal] et **non** par `amountCents / 100.0` : un `Double` ne représente pas
     * exactement tous les `Long`, si bien que les très gros montants s'affichaient arrondis sans
     * aucun avertissement — `Long.MAX_VALUE` rendait `…760,00 €` au lieu de `…758,07 €`, soit
     * 1,93 € d'écart entre le montant stocké et le montant affiché. `BigDecimal.valueOf(cents, 2)`
     * construit la valeur exacte, et `NumberFormat` la formate sans repasser par un flottant.
     */
    fun money(amountCents: Long, currencyCode: String = "EUR", locale: Locale = Locale.FRANCE): String {
        val exact = BigDecimal.valueOf(amountCents, 2)
        return runCatching {
            currencyFormat(currencyCode, locale).format(exact)
        }.getOrElse { String.format(locale, "%.2f %s", exact, currencyCode) }
    }

    /**
     * Frontière UI : saisie en euros ("12,34" ou "12.34") -> centimes, arrondi half-up.
     * Passe par [BigDecimal] pour que 10,505 donne bien 1051 et non 1050.
     */
    fun centsOrNull(input: String): Long? =
        input.replace(',', '.').trim().takeIf { it.isNotEmpty() }?.let { normalized ->
            runCatching {
                BigDecimal(normalized).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            }.getOrNull()
        }

    /** Frontière UI : centimes -> texte d'un champ de saisie en euros. */
    fun centsToInput(cents: Long): String = (cents / 100.0).toString()

    /**
     * Frontière UI : euros d'un champ numérique -> centimes, arrondi au centime le plus proche.
     *
     * Passe par [centsOrNull] et donc par [BigDecimal] : `Double.toString()` rend la représentation
     * décimale exacte la plus courte, si bien que 123,45 € donne 12345 et jamais 12344.
     */
    fun centsOf(euros: Double): Long = centsOrNull(euros.toString()) ?: 0L

    /** Frontière UI : centimes -> euros d'un champ numérique, sans perte (cf. [money]). */
    fun eurosOf(cents: Long): Double = BigDecimal.valueOf(cents, 2).toDouble()

    /** Symbole seul ("EUR" -> "€"), pour les champs de saisie qui affichent déjà le nombre. */
    fun currencySymbol(currencyCode: String = "EUR", locale: Locale = Locale.FRANCE): String =
        runCatching { Currency.getInstance(currencyCode).getSymbol(locale) }
            .getOrElse { currencyCode }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
    private val full = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)

    fun dayMonth(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(dayMonth)

    fun fullDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(full)
            .replaceFirstChar { it.uppercase() }

    /** Exécute : "juin 2026" → "Juin 2026" */
    fun monthYear(ym: YearMonth): String =
        ym.format(monthYear).replaceFirstChar { it.uppercase() }

    fun shortDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dayMonth)
}
