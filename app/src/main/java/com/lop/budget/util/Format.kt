package com.lop.budget.util

import android.util.Log
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object Format {
    fun money(amount: Double, currencyCode: String = "EUR", locale: Locale = Locale.FRANCE): String {
        return try {
            val nf = NumberFormat.getCurrencyInstance(locale)
            nf.currency = Currency.getInstance(currencyCode)
            nf.format(amount)
        } catch (e: IllegalArgumentException) {
            Log.w("Format", "Invalid currency code: $currencyCode", e)
            String.format(locale, "%.2f %s", amount, currencyCode)
        }
    }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
    private val full = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)

    fun dayMonth(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(dayMonth)

    fun fullDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(full)
            .replaceFirstChar { it.uppercase() }
}
