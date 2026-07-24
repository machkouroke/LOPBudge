package com.lop.budget.domain

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.AccountType
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
     * 1. Part bien du solde de référence (initialBalance).
     * 2. Ignore les transactions dont la date de paiement (paidAt) est antérieure ou égale à la date de référence (balanceUpdatedAt).
     * 3. Prend en compte uniquement les transactions payées après la date de référence.
     */
    @Test
    fun `calculateBalances should start from initialBalance and ignore transactions paid before balanceUpdatedAt`() {
        MarkdownReporter.log("Initialisation des données de test")
        val account = AccountEntity(
            id = 1,
            name = "Test Account",
            type = AccountType.CHECKING,
            initialBalance = 1000.0,
            balanceUpdatedAt = 10000L,
            colorArgb = 0,
            icon = ""
        )
        MarkdownReporter.log("Compte configuré : Solde ref = 1000.0, Date ref = 10000")

        val txBefore = TransactionEntity(
            id = 1,
            title = "Before",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = 5000L,
            paidAt = 5000L,
            accountId = 1,
            categoryId = 0
        )
        MarkdownReporter.log("Tx AVANT : ID=1, paidAt=5000 (doit être ignorée)")

        val txAfter = TransactionEntity(
            id = 2,
            title = "After",
            amount = 200.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = 15000L,
            paidAt = 15000L,
            accountId = 1,
            categoryId = 0
        )
        MarkdownReporter.log("Tx APRÈS : ID=2, paidAt=15000 (doit être déduite)")

        val txIncome = TransactionEntity(
            id = 4,
            title = "Income",
            amount = 500.0,
            type = TransactionType.INCOME,
            status = TransactionStatus.PAID,
            date = 12000L,
            paidAt = 12000L,
            accountId = 1,
            categoryId = 0
        )
        MarkdownReporter.log("Tx REVENU : ID=4, paidAt=12000 (doit être ajoutée)")

        val results =
            BalanceEngine.calculateBalances(listOf(account), listOf(txBefore, txAfter, txIncome))
        val finalBalance = results[1L]!!

        MarkdownReporter.log("Solde calculé : $finalBalance")
        assertEquals(1300.0, finalBalance, 0.0)
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
