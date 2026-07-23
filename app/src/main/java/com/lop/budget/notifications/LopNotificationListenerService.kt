package com.lop.budget.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lop.budget.MainActivity
import com.lop.budget.R
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.ui.navigation.Routes
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Écoute les notifications système (après autorisation utilisateur) et crée des propositions.
 */
class LopNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun settingsRepository(): SettingsRepository
        fun notificationDetectionRepository(): NotificationDetectionRepository
        fun paymentNotificationParser(): PaymentNotificationParser
        fun smartCategorizer(): SmartCategorizer
        fun budgetRepository(): BudgetRepository
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ep = EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
        val settings = ep.settingsRepository()
        val repo = ep.notificationDetectionRepository()
        val parser = ep.paymentNotificationParser()
        val categorizer = ep.smartCategorizer()
        val budget = ep.budgetRepository()

        scope.launch {
            if (!settings.isNotificationDetectionEnabledOnce()) return@launch

            val pkg = sbn.packageName
            if (!settings.isAllowedNotificationSource(pkg)) return@launch

            val parsed = parser.parse(sbn, applicationContext) ?: return@launch

            val status = when (parsed.classification.status) {
                ClassificationResult.Status.TRANSACTION -> DetectedTransactionProposalEntity.STATUS_PENDING
                ClassificationResult.Status.UNCERTAIN -> DetectedTransactionProposalEntity.STATUS_UNCERTAIN
                else -> return@launch // Déjà filtré par le parser normalement
            }

            // Catégorisation intelligente (IA)
            var suggestedCatId: Long? = null
            if (settings.useLocalLlm.first()) {
                val availableCats = budget.observeCategories().first().map { it.name }
                val suggestedName = categorizer.suggestCategory(parsed.label, availableCats)
                if (suggestedName != null) {
                    suggestedCatId = budget.observeCategories().first().find { it.name == suggestedName }?.id
                }
            }

            val proposal = DetectedTransactionProposalEntity(
                amount = parsed.amount,
                currency = parsed.currency,
                label = parsed.label,
                fullText = parsed.fullText,
                cardName = parsed.cardName,
                detectedAt = System.currentTimeMillis(),
                sourcePackage = pkg,
                dedupeKey = "${pkg}|${parsed.amount}|${parsed.currency ?: ""}|${parsed.normalizedText}",
                status = status,
                confidenceScore = parsed.classification.confidence,
                suggestedCategoryId = suggestedCatId
            )

            // Anti-doublon : fenêtre courte (2 minutes)
            val inserted = repo.upsertIfNotDuplicate(proposal, dedupeWindowMs = 2 * 60 * 1000L)
            if (inserted > 0 && status == DetectedTransactionProposalEntity.STATUS_PENDING) {
                postDetectedNotification(proposal)
            }
        }
    }

    private fun postDetectedNotification(p: DetectedTransactionProposalEntity) {
        ensureChannel()

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("route", Routes.DETECTED)
        }

        val pi = PendingIntent.getActivity(
            applicationContext,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = "${p.label} • ${p.amount} ${p.currency ?: ""}".trim()

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.notif_detected_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
        } catch (e: SecurityException) {
            android.util.Log.e("LopNotifService", "Permission POST_NOTIFICATIONS missing", e)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = applicationContext.getString(R.string.notif_channel_desc)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "detected_transactions"
    }
}
