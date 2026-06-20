package com.lop.budget.data.local

import androidx.room.TypeConverter
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType

/** Convertit les enums du domaine en String pour Room. */
class Converters {
    @TypeConverter fun toTransactionType(v: String) = TransactionType.valueOf(v)
    @TypeConverter fun fromTransactionType(v: TransactionType) = v.name

    @TypeConverter fun toTransactionStatus(v: String) = TransactionStatus.valueOf(v)
    @TypeConverter fun fromTransactionStatus(v: TransactionStatus) = v.name

    @TypeConverter fun toRecurrenceFrequency(v: String) = RecurrenceFrequency.valueOf(v)
    @TypeConverter fun fromRecurrenceFrequency(v: RecurrenceFrequency) = v.name

    @TypeConverter fun toAccountType(v: String) = AccountType.valueOf(v)
    @TypeConverter fun fromAccountType(v: AccountType) = v.name
}
