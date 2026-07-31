package com.lop.budget.domain

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.reports.MarkdownReporter
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BalanceEngineTest {

    @get:Rule
    val reporter = MarkdownReporter()

    @Before
    fun setup() {
        // Optionnel : MarkdownReporter.reset() si on veut isoler les classes
    }

    /**
     * Vérifie que le calcul du solde actuel :
     * 1. Part bien du solde initial (initialBalance).
     * 2. Prend en compte TOUTES les transactions payées (standards et ajustements).
     */
    @Test
    fun `calculateBalances should start from initialBalance and sum all paid transactions`() {
        MarkdownReporter.log("Initialisation des données de test (Nouveau moteur cumulatif)")
        val account = AccountEntity(
            id = 1,
            name = "Test Account",
            type = AccountType.CHECKING,
            initialBalance = 1000.0,
            colorArgb = 0,
            icon = ""
        )
        MarkdownReporter.log("Compte configuré : Solde initial = 1000.0")

        val tx1 = TransactionEntity(
            id = 1,
            title = "Standard 1",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = 5000L,
            accountId = 1,
            categoryId = 0
        )
        
        val tx2 = TransactionEntity(
            id = 2,
            title = "Ajustement",
            amount = 300.0,
            type = TransactionType.INCOME,
            status = TransactionStatus.PAID,
            kind = TransactionKind.BALANCE_ADJUSTMENT,
            date = 10000L,
            accountId = 1,
            categoryId = 0
        )

        val results =
            BalanceEngine.calculateBalances(listOf(account), listOf(tx1, tx2))
        val finalBalance = results[1L]!!

        MarkdownReporter.log("Solde calculé : $finalBalance (1000 - 100 + 300 = 1200)")
        assertEquals(1200.0, finalBalance, 0.0)
    }

    /**
     * Vérifie que le calcul du solde total consolidé :
     * 1. Inclut uniquement les comptes dont l'option 'includeInTotal' est activée.
     * 2. Exclut les comptes archivés ou explicitement masqués du total.
     */
    @Test
    fun `calculateTotalBalance should only include accounts with includeInTotal set to true`() {
        MarkdownReporter.log("Vérification du calcul du solde total consolidé")
        val account1 = AccountEntity(
            id = 1,
            name = "A1",
            type = AccountType.CHECKING,
            initialBalance = 100.0,
            includeInTotal = true,
            colorArgb = 0,
            icon = ""
        )
        val account2 = AccountEntity(
            id = 2,
            name = "A2",
            type = AccountType.CHECKING,
            initialBalance = 200.0,
            includeInTotal = false,
            colorArgb = 0,
            icon = ""
        )

        val balances = mapOf(1L to 1000.0, 2L to 500.0)
        val total = BalanceEngine.calculateTotalBalance(listOf(account1, account2), balances)

        MarkdownReporter.log("Total obtenu : $total (A2 doit être ignoré)")
        assertEquals(1000.0, total, 0.0)
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}
