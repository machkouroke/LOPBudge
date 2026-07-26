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
        MarkdownReporter.log("Expense AVANT : ID=1, paidAt=5000 (doit être ignorée)")

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
        MarkdownReporter.log("Expense APRÈS : ID=2, paidAt=15000 (doit être déduite)")

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
        MarkdownReporter.log("Income REVENU : ID=4, paidAt=12000 (doit être ajoutée)")

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

    /**
     * Cas important : Modification d'une transaction déjà payée AVANT la correction du solde.
     * On simule la modification réelle en comparant l'état avant et après.
     */
    @Test
    fun `modifying an old paid transaction should NOT impact the current balance`() {
        MarkdownReporter.log("Cas : Modification d'une ancienne transaction")
        val account = AccountEntity(
            id = 1,
            name = "Compte",
            type = AccountType.CHECKING,
            initialBalance = 950.0, // Solde de référence défini le 10 Juillet
            balanceUpdatedAt = 1000L, // 10 Juillet = 1000
            colorArgb = 0,
            icon = ""
        )
        
        // 1. État initial : Transaction de 20€ payée le 8 Juillet (800 < 1000)
        val txOriginal = TransactionEntity(
            id = 1,
            title = "Tx Originale",
            amount = 20.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = 500L,
            paidAt = 800L,
            accountId = 1,
            categoryId = 0
        )
        
        val balanceInit = BalanceEngine.calculateBalances(listOf(account), listOf(txOriginal))[1L]!!
        MarkdownReporter.log("Balance initiale (Tx 20€, paidAt 800) : $balanceInit (doit être 950.0)")
        assertEquals(950.0, balanceInit, 0.0)

        // 2. Simulation de la modification : l'utilisateur passe le montant à 25€ le 12 Juillet
        // mais le paidAt reste à 800 car elle était déjà payée.
        val txModified = txOriginal.copy(amount = 25.0)
        MarkdownReporter.log("Action : Modification du montant 20€ -> 25€ (paidAt conservé à 800)")

        val balanceFinal = BalanceEngine.calculateBalances(listOf(account), listOf(txModified))[1L]!!
        MarkdownReporter.log("Balance finale : $balanceFinal (doit RESTER à 950.0)")

        // Le solde ne doit pas bouger car paidAt (800) est toujours inférieur à balanceUpdatedAt (1000)
        assertEquals(950.0, balanceFinal, 0.0)
    }

    /**
     * Cas : Transaction ancienne (date transaction avant ref) mais PAYÉE après la correction.
     * 1. Transaction datée du 9 Juillet (non payée).
     * 2. 10 Juillet : Correction du solde à 1000€.
     * 3. 12 Juillet : On la marque comme payée.
     * Résultat : Elle doit être déduite car paidAt (12 Juillet) > ref (10 Juillet).
     */
    @Test
    fun `old transaction paid AFTER correction should impact the current balance`() {
        MarkdownReporter.log("Cas : Transaction ancienne payée après correction")
        val account = AccountEntity(
            id = 1,
            name = "Compte",
            type = AccountType.CHECKING,
            initialBalance = 1000.0,
            balanceUpdatedAt = 1000L, // 10 Juillet
            colorArgb = 0,
            icon = ""
        )

        val tx = TransactionEntity(
            id = 1,
            title = "Dépense retardataire",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = 900L, // 9 Juillet
            paidAt = 1200L, // 12 Juillet (1200 > 1000)
            accountId = 1,
            categoryId = 0
        )
        MarkdownReporter.log("Tx datée du 900 mais PAYÉE le 1200 : doit être déduite")

        val results = BalanceEngine.calculateBalances(listOf(account), listOf(tx))
        val finalBalance = results[1L]!!

        MarkdownReporter.log("Solde obtenu : $finalBalance (Attendu : 950.0)")
        assertEquals(950.0, finalBalance, 0.0)
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}
