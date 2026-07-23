package com.lop.budget.notifications

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationClassifierTest {

    private val classifier = HeuristicNotificationClassifier()

    @Test
    fun `classify valid transaction - Google Wallet`() = runBlocking {
        val text = "Google Wallet • Paiement de 12,50 € chez Starbucks"
        val result = classifier.classify(text)
        assertEquals(ClassificationResult.Status.TRANSACTION, result.status)
    }

    @Test
    fun `classify valid transaction - Bank Card`() = runBlocking {
        val text = "Achat de 45,00 € avec votre carte VISA à Carrefour"
        val result = classifier.classify(text)
        assertEquals(ClassificationResult.Status.TRANSACTION, result.status)
    }

    @Test
    fun `classify promo - should ignore`() = runBlocking {
        val text = "Profitez de -20% de remise sur votre prochaine commande avec le code PROMO20"
        val result = classifier.classify(text)
        assertEquals(ClassificationResult.Status.IGNORE, result.status)
    }

    @Test
    fun `classify cashback - should ignore`() = runBlocking {
        val text = "Félicitations ! Vous avez reçu 0,50 € de cashback"
        val result = classifier.classify(text)
        assertEquals(ClassificationResult.Status.IGNORE, result.status)
    }

    @Test
    fun `classify balance info - should be uncertain or ignore`() = runBlocking {
        val text = "Votre solde est de 1250,00 €"
        val result = classifier.classify(text)
        // Le mot "solde" est négatif, pas de mot positif. Score faible.
        assertEquals(ClassificationResult.Status.IGNORE, result.status)
    }

    @Test
    fun `classify ambiguous text - should be uncertain`() = runBlocking {
        // Contient un mot positif "paiement" mais aussi un mot négatif "disponible" (souvent lié au solde)
        val text = "Paiement possible. Solde disponible : 50 €"
        val result = classifier.classify(text)
        // Score attendu :
        // -0.7 (solde) -0.7 (disponible) -> limité à -0.7 ou -1.4 selon implém
        // +0.5 (paiement) +0.3 (montant) -> +0.8
        // Total possiblement négatif ou proche de 0.
        // On vérifie juste que ce n'est pas une TRANSACTION.
        assert(result.status != ClassificationResult.Status.TRANSACTION)
    }
}
