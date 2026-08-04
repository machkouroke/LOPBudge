package com.lop.budget.di

import android.content.Context
import androidx.room.Room
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideInMemoryDatabase(@ApplicationContext context: Context): LopDatabase {
        return Room.inMemoryDatabaseBuilder(context, LopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Provides fun provideTransactionDao(db: LopDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideAccountDao(db: LopDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: LopDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTagDao(db: LopDatabase): TagDao = db.tagDao()
    @Provides fun provideGoalDao(db: LopDatabase): GoalDao = db.goalDao()
    @Provides fun provideDebtDao(db: LopDatabase): DebtDao = db.debtDao()
    @Provides fun provideRecurringSeriesDao(db: LopDatabase): RecurringSeriesDao = db.recurringSeriesDao()
    @Provides fun provideDetectedProposalDao(db: LopDatabase): DetectedTransactionProposalDao = db.detectedTransactionProposalDao()
}
