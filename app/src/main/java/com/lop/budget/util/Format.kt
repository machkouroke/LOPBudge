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
                currency = Currency.getInstance(currencyCode)
            }
        }.clone() as NumberFormat

    /** Objectifs et dettes : montants en euros `Double` (P-4). */
    fun money(amount: Double, currencyCode: String = "EUR", locale: Locale = Locale.FRANCE): String {
        return runCatching {
            currencyFormat(currencyCode, locale).format(amount)
        }.getOrElse { String.format(locale, "%.2f %s", amount, currencyCode) }
    }

    /** Soldes et transactions de compte : centimes -> euros affichés. `money(1234L)` == `money(12.34)`. */
    fun money(amountCents: Long, currencyCode: String = "EUR", locale: Locale = Locale.FRANCE): String =
        money(amountCents / 100.0, currencyCode, locale)

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
