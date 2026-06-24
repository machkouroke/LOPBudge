package com.lop.budget.data.local

import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    // --- TransactionType ---

    @Test
    fun `toTransactionType converts INCOME string`() {
        assertEquals(TransactionType.INCOME, converters.toTransactionType("INCOME"))
    }

    @Test
    fun `toTransactionType converts EXPENSE string`() {
        assertEquals(TransactionType.EXPENSE, converters.toTransactionType("EXPENSE"))
    }

    @Test
    fun `fromTransactionType converts INCOME to string`() {
        assertEquals("INCOME", converters.fromTransactionType(TransactionType.INCOME))
    }

    @Test
    fun `fromTransactionType converts EXPENSE to string`() {
        assertEquals("EXPENSE", converters.fromTransactionType(TransactionType.EXPENSE))
    }

    @Test
    fun `TransactionType roundtrip INCOME`() {
        val original = TransactionType.INCOME
        assertEquals(original, converters.toTransactionType(converters.fromTransactionType(original)))
    }

    @Test
    fun `TransactionType roundtrip EXPENSE`() {
        val original = TransactionType.EXPENSE
        assertEquals(original, converters.toTransactionType(converters.fromTransactionType(original)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toTransactionType throws for invalid string`() {
        converters.toTransactionType("INVALID")
    }

    // --- TransactionStatus ---

    @Test
    fun `toTransactionStatus converts PLANNED string`() {
        assertEquals(TransactionStatus.PLANNED, converters.toTransactionStatus("PLANNED"))
    }

    @Test
    fun `toTransactionStatus converts PAID string`() {
        assertEquals(TransactionStatus.PAID, converters.toTransactionStatus("PAID"))
    }

    @Test
    fun `fromTransactionStatus converts PLANNED to string`() {
        assertEquals("PLANNED", converters.fromTransactionStatus(TransactionStatus.PLANNED))
    }

    @Test
    fun `fromTransactionStatus converts PAID to string`() {
        assertEquals("PAID", converters.fromTransactionStatus(TransactionStatus.PAID))
    }

    @Test
    fun `TransactionStatus roundtrip all values`() {
        TransactionStatus.entries.forEach { status ->
            assertEquals(status, converters.toTransactionStatus(converters.fromTransactionStatus(status)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toTransactionStatus throws for invalid string`() {
        converters.toTransactionStatus("UNKNOWN")
    }

    // --- RecurrenceFrequency ---

    @Test
    fun `RecurrenceFrequency roundtrip all values`() {
        RecurrenceFrequency.entries.forEach { freq ->
            assertEquals(freq, converters.toRecurrenceFrequency(converters.fromRecurrenceFrequency(freq)))
        }
    }

    @Test
    fun `toRecurrenceFrequency converts NONE`() {
        assertEquals(RecurrenceFrequency.NONE, converters.toRecurrenceFrequency("NONE"))
    }

    @Test
    fun `toRecurrenceFrequency converts DAILY`() {
        assertEquals(RecurrenceFrequency.DAILY, converters.toRecurrenceFrequency("DAILY"))
    }

    @Test
    fun `toRecurrenceFrequency converts WEEKLY`() {
        assertEquals(RecurrenceFrequency.WEEKLY, converters.toRecurrenceFrequency("WEEKLY"))
    }

    @Test
    fun `toRecurrenceFrequency converts MONTHLY`() {
        assertEquals(RecurrenceFrequency.MONTHLY, converters.toRecurrenceFrequency("MONTHLY"))
    }

    @Test
    fun `toRecurrenceFrequency converts YEARLY`() {
        assertEquals(RecurrenceFrequency.YEARLY, converters.toRecurrenceFrequency("YEARLY"))
    }

    @Test
    fun `fromRecurrenceFrequency preserves name`() {
        RecurrenceFrequency.entries.forEach { freq ->
            assertEquals(freq.name, converters.fromRecurrenceFrequency(freq))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toRecurrenceFrequency throws for invalid string`() {
        converters.toRecurrenceFrequency("BIWEEKLY")
    }

    // --- AccountType ---

    @Test
    fun `AccountType roundtrip all values`() {
        AccountType.entries.forEach { type ->
            assertEquals(type, converters.toAccountType(converters.fromAccountType(type)))
        }
    }

    @Test
    fun `toAccountType converts CHECKING`() {
        assertEquals(AccountType.CHECKING, converters.toAccountType("CHECKING"))
    }

    @Test
    fun `toAccountType converts CASH`() {
        assertEquals(AccountType.CASH, converters.toAccountType("CASH"))
    }

    @Test
    fun `toAccountType converts SAVINGS`() {
        assertEquals(AccountType.SAVINGS, converters.toAccountType("SAVINGS"))
    }

    @Test
    fun `toAccountType converts CARD`() {
        assertEquals(AccountType.CARD, converters.toAccountType("CARD"))
    }

    @Test
    fun `toAccountType converts OTHER`() {
        assertEquals(AccountType.OTHER, converters.toAccountType("OTHER"))
    }

    @Test
    fun `fromAccountType preserves name`() {
        AccountType.entries.forEach { type ->
            assertEquals(type.name, converters.fromAccountType(type))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toAccountType throws for invalid string`() {
        converters.toAccountType("CRYPTO")
    }
}
