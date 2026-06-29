package com.lop.budget.util

import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object Format {
    fun money(amount: Double, currencyCode: String = "EUR", locale: Locale = Locale.FRANCE): String {
        return runCatching {
            val nf = NumberFormat.getCurrencyInstance(locale)
            nf.currency = Currency.getInstance(currencyCode)
            nf.format(amount)
        }.getOrElse { String.format(locale, "%.2f %s", amount, currencyCode) }
    }

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
}
