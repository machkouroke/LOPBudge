package com.lop.budget.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.seed.DatabaseSeeder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LopDatabase {
        lateinit var dbRef: LopDatabase
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dbRef = Room.databaseBuilder(context, LopDatabase::class.java, LopDatabase.NAME)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    scope.launch { DatabaseSeeder.seed(dbRef) }
                }
            })
            .addMigrations(
                LopDatabase.MIGRATION_1_2,
                LopDatabase.MIGRATION_2_3,
                LopDatabase.MIGRATION_3_4,
            )
            .fallbackToDestructiveMigration()
            .build()
        return dbRef
    }

    @Provides fun provideTransactionDao(db: LopDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideAccountDao(db: LopDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: LopDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTagDao(db: LopDatabase): TagDao = db.tagDao()
    @Provides fun provideGoalDao(db: LopDatabase): GoalDao = db.goalDao()
    @Provides fun provideDebtDao(db: LopDatabase): DebtDao = db.debtDao()
    @Provides fun provideRecurringSeriesDao(db: LopDatabase): com.lop.budget.data.local.dao.RecurringSeriesDao = db.recurringSeriesDao()
    @Provides fun provideDetectedProposalDao(db: LopDatabase): DetectedTransactionProposalDao = db.detectedTransactionProposalDao()
}
