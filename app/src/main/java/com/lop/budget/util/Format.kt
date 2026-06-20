package com.lop.budget.util

import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
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

    fun signedMoney(amount: Double, isIncome: Boolean, currencyCode: String = "EUR"): String =
        (if (isIncome) "+" else "\u2212") + money(amount, currencyCode)

    fun monthYear(month: YearMonth, locale: Locale = Locale.FRANCE): String =
        "${month.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }} ${month.year}"

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
    private val full = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)

    fun dayMonth(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(dayMonth)

    fun fullDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(full)
            .replaceFirstChar { it.uppercase() }
}

fun YearMonth.toEpochMilliRange(): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    val start = atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
    return start to end
}
