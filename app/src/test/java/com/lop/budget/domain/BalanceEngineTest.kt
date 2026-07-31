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

/**
 * Tests du moteur de calcul des soldes.
 * Couvre l'US : Calculer les ajustements de solde via transactions compensatoires (LOP-87)
 * et l'historique LOP-78 / LOP-85.
 */
class BalanceEngineTest {

    @get:Rule
    val reporter = MarkdownReporter()

    @Before
    fun setup() {
        // Optionnel : MarkdownReporter.reset() si on veut isoler les classes
    }

    /**
     * TC1 : Vérifie que le calcul du solde actuel :
     * 1. Part bien du solde initial (initialBalance).
     * 2. Somme les revenus et soustrait les dépenses.
     */
    @Test
    fun `TC1 - calculateBalances should sum paid income and subtract paid expenses`() {
        MarkdownReporter.log("TC1 : Calcul cumulatif simple")
        val account = AccountEntity(
            id = 1,
            name = "A1",
            type = AccountType.CHECKING,
            initialBalance = 1000.0,
            colorArgb = 0,
            icon = ""
        )

        val tx1 = TransactionEntity(
            id = 1,
            title = "Revenu",
            amount = 500.0,
            type = TransactionType.INCOME,
            status = TransactionStatus.PAID,
            date = 1000L,
            accountId = 1,
            categoryId = 0
        )
        val tx2 = TransactionEntity(
            id = 2,
            title = "Dépense",
            amount = 200.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = 2000L,
            accountId = 1,
            categoryId = 0
        )

        val results = BalanceEngine.calculateBalances(
            listOf(account),
            listOf(tx1, tx2)
        )
        MarkdownReporter.log("Solde calculé pour le compte A1 : ${results[1L]}, Attendu: 1300")
        assertEquals(1300.0, results[1L]!!, 0.0)
    }

    /**
     * TC2 : Vérifie qu'une transaction technique d'ajustement est traitée comme une transaction
     * standard pour le calcul du solde
     */
    @Test
    fun `TC2 - calculateBalances should include adjustment transactions`() {
        MarkdownReporter.log("TC2 : Prise en compte des ajustements")
        val account = AccountEntity(
            id = 1,
            name = "A1",
            type = AccountType.CHECKING,
            initialBalance = 1000.0,
            colorArgb = 0,
            icon = ""
        )

        val adj = TransactionEntity(
            id = 1,
            title = "Ajustement",
            amount = 300.0,
            type = TransactionType.INCOME,
            status = TransactionStatus.PAID,
            kind = TransactionKind.BALANCE_ADJUSTMENT,
            date = 1000L,
            accountId = 1,
            categoryId = 0
        )

        val results = BalanceEngine.calculateBalances(listOf(account),
            listOf(adj))
        MarkdownReporter.log("Solde calculé pour le compte A1 : ${results[1L]}, Attendu: 1300")

        assertEquals(1300.0, results[1L]!!, 0.0)
    }

    /**
     * TC3 : Vérifie que les transactions planifiées (non payées) sont ignorées.
     */
    @Test
    fun `TC3 - calculateBalances should ignore planned transactions`() {
        MarkdownReporter.log("TC3 : Exclusion des transactions PLANNED")
        val account = AccountEntity(
            id = 1,
            name = "A1",
            type = AccountType.CHECKING,
            initialBalance = 1000.0,
            colorArgb = 0,
            icon = ""
        )

        val txPlanned = TransactionEntity(
            id = 1,
            title = "Futur",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = 1000L,
            accountId = 1,
            categoryId = 0
        )


        val results = BalanceEngine.calculateBalances(listOf(account),
            listOf(txPlanned))
        MarkdownReporter.log("Solde calculé pour le compte A1 : ${results[1L]}, Attendu: 1000")
        assertEquals(1000.0, results[1L]!!, 0.0)
    }

    /**
     * TC4 : Vérifie que les transactions supprimées sont ignorées.
     */
    @Test
    fun `TC4 - calculateBalances should ignore deleted transactions`() {
        MarkdownReporter.log("TC4 : Exclusion des transactions supprimées")
        val account = AccountEntity(
            id = 1,
            name = "A1",
            type = AccountType.CHECKING,
            initialBalance = 1000.0,
            colorArgb = 0,
            icon = ""
        )

        val txDeleted = TransactionEntity(
            id = 1,
            title = "Supprimée",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = 1000L,
            deleted = true,
            accountId = 1,
            categoryId = 0
        )

        val results = BalanceEngine.calculateBalances(listOf(account),
            listOf(txDeleted))
        MarkdownReporter.log("Solde calculé pour le compte A1 : ${results[1L]}, Attendu: 1000")
        assertEquals(1000.0, results[1L]!!, 0.0)
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

        MarkdownReporter.log("Total obtenu : $total (A2 doit être ignoré), Attendu: 1000.0")
        assertEquals(1000.0, total, 0.0)
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}
