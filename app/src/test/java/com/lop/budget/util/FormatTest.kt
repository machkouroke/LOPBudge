package com.lop.budget.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class FormatTest {

    // --- money() ---

    @Test
    fun `money formats EUR amount with French locale`() {
        val result = Format.money(1234.56, "EUR", Locale.FRANCE)
        // Should contain the amount and the euro symbol
        assertTrue("Expected EUR symbol in '$result'", result.contains("€") || result.contains("EUR"))
        assertTrue("Expected 1 234,56 pattern in '$result'",
            result.contains("1") && result.contains("234"))
    }

    @Test
    fun `money formats zero amount`() {
        val result = Format.money(0.0, "EUR", Locale.FRANCE)
        assertNotNull(result)
        assertTrue("Expected '0' in '$result'", result.contains("0"))
    }

    @Test
    fun `money formats negative amount`() {
        val result = Format.money(-500.0, "EUR", Locale.FRANCE)
        assertNotNull(result)
        assertTrue("Expected '500' in '$result'", result.contains("500"))
    }

    @Test
    fun `money formats USD with US locale`() {
        val result = Format.money(42.99, "USD", Locale.US)
        assertTrue("Expected dollar sign in '$result'", result.contains("$"))
        assertTrue("Expected '42.99' in '$result'", result.contains("42.99"))
    }

    @Test
    fun `money handles large amounts`() {
        val result = Format.money(1_000_000.00, "EUR", Locale.FRANCE)
        assertNotNull(result)
        assertTrue("Expected '1 000 000' pattern in '$result'", result.contains("000"))
    }

    @Test
    fun `money falls back gracefully for invalid currency code`() {
        val result = Format.money(100.0, "INVALID")
        // Should not throw; runCatching falls back to String.format
        assertNotNull(result)
        assertTrue("Expected '100' in '$result'", result.contains("100"))
        assertTrue("Expected 'INVALID' in '$result'", result.contains("INVALID"))
    }

    @Test
    fun `money uses default EUR and FRANCE locale`() {
        val result = Format.money(50.0)
        assertNotNull(result)
        assertTrue("Expected '50' in '$result'", result.contains("50"))
    }

    // --- dayMonth() ---

    @Test
    fun `dayMonth formats date correctly`() {
        // Use a fixed date: June 15, 2025
        val date = LocalDate.of(2025, 6, 15)
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = Format.dayMonth(millis)
        assertNotNull(result)
        assertTrue("Expected '15' in '$result'", result.contains("15"))
        // French month abbreviation for June: "juin"
        assertTrue("Expected month abbreviation in '$result'",
            result.contains("juin") || result.contains("Jun"))
    }

    @Test
    fun `dayMonth formats January 1`() {
        val date = LocalDate.of(2025, 1, 1)
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = Format.dayMonth(millis)
        assertNotNull(result)
        assertTrue("Expected '1' in '$result'", result.contains("1"))
    }

    // --- fullDate() ---

    @Test
    fun `fullDate formats date with capitalized first letter`() {
        val date = LocalDate.of(2025, 6, 15)
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = Format.fullDate(millis)
        assertNotNull(result)
        // First char should be uppercase (day of week in French, capitalized)
        assertTrue("Expected uppercase first char in '$result'",
            result.first().isUpperCase())
        assertTrue("Expected '15' in '$result'", result.contains("15"))
        assertTrue("Expected '2025' in '$result'", result.contains("2025"))
    }

    @Test
    fun `fullDate includes day of week month and year`() {
        // December 25, 2025 is a Thursday (Jeudi in French)
        val date = LocalDate.of(2025, 12, 25)
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = Format.fullDate(millis)
        assertTrue("Expected '25' in '$result'", result.contains("25"))
        assertTrue("Expected '2025' in '$result'", result.contains("2025"))
        // Should contain French month name for December
        assertTrue("Expected 'cembre' (décembre) in '$result'",
            result.lowercase().contains("cembre") || result.lowercase().contains("dec"))
    }
}
