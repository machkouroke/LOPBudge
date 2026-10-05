package com.lop.budget.di

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.LoanDao
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Singleton

/**
 * Remplace [AppModule] pour toute la suite instrumentée.
 *
 * Deux raisons de substituer le module entier plutôt que la seule fabrique de base :
 * 1. `AppModule.provideDatabase` lance `DatabaseSeeder.seed(db)` **en arrière-plan** dès que
 *    `BuildConfig.DEBUG` est vrai. Un jeu de données de test inséré en `@Before` pourrait être
 *    doublé par ce seeding asynchrone : aucune cardinalité exacte ne serait fiable.
 * 2. La base réelle survit d'un cas à l'autre. Une base **en mémoire**, recréée par processus de
 *    test et vidée entre les cas, est le seul montage qui garantit « aucun état partagé ».
 *
 * Les autres liaisons d'`AppModule` sont reconduites à l'identique : les remplacer par des
 * doublures reviendrait à tester autre chose que l'application.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object TestAppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LopDatabase =
        Room.inMemoryDatabaseBuilder(context, LopDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Provides
    @Singleton
    fun provideTestClock(): TestClock = TestClock()

    /** Même horloge que [AppModule] tant qu'un cas ne la fige pas : voir [TestClock]. */
    @Provides
    fun provideClock(clock: TestClock): Clock = clock

    @Provides
    @Singleton
    fun provideAtomicWriter(db: LopDatabase): AtomicWriter = object : AtomicWriter {
        override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction(block)
    }

    @Provides fun provideTransactionDao(db: LopDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideAccountDao(db: LopDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: LopDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTagDao(db: LopDatabase): TagDao = db.tagDao()
    @Provides fun provideGoalDao(db: LopDatabase): GoalDao = db.goalDao()
    @Provides fun provideLoanDao(db: LopDatabase): LoanDao = db.loanDao()
    @Provides fun provideRecurringSeriesDao(db: LopDatabase): RecurringSeriesDao =
        db.recurringSeriesDao()
    @Provides fun provideDetectedProposalDao(db: LopDatabase): DetectedTransactionProposalDao =
        db.detectedTransactionProposalDao()
}

/**
 * Horloge de la suite instrumentée : l'heure réelle, comme `Clock.systemUTC()`, tant qu'un cas ne
 * la fige pas avec [fixedAt].
 *
 * Hilt crée un composant neuf pour chaque cas : une horloge figée ne déborde ni sur le cas suivant
 * ni sur les autres classes, qui continuent de lire l'heure réelle. Le fuseau reste celui que
 * l'appelant demande par `withZone`, comme en production.
 */
class TestClock : Clock() {
    @Volatile
    var fixedAt: Instant? = null

    override fun instant(): Instant = fixedAt ?: Instant.now()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Zoned(zone)

    /** Suit [fixedAt] même après `withZone` : figer l'horloge après coup reste visible. */
    private inner class Zoned(private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = this@TestClock.instant()

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = Zoned(zone)
    }
}
