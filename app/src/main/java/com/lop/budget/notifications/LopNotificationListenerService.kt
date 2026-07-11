package com.lop.budget.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ep = EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
        val settings = ep.settingsRepository()
        val repo = ep.notificationDetectionRepository()

        scope.launch {
            if (!settings.isNotificationDetectionEnabledOnce()) return@launch

            val pkg = sbn.packageName
            if (!settings.isAllowedNotificationSource(pkg)) return@launch

            val parsed = PaymentNotificationParser.parse(sbn) ?: return@launch

            val proposal = DetectedTransactionProposalEntity(
                amount = parsed.amount,
                currency = parsed.currency,
                label = parsed.label,
                detectedAt = System.currentTimeMillis(),
                sourcePackage = pkg,
                dedupeKey = "${pkg}|${parsed.amount}|${parsed.currency ?: ""}|${parsed.normalizedText}",
            )

            // Anti-doublon : fenêtre courte (2 minutes)
            repo.upsertIfNotDuplicate(proposal, dedupeWindowMs = 2 * 60 * 1000L)
        }
    }
}
