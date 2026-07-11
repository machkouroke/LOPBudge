package com.lop.budget.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lop.budget.MainActivity
import com.lop.budget.R
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.ui.navigation.Routes
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

            val parsed = PaymentNotificationParser.parse(sbn, applicationContext) ?: return@launch

            val proposal = DetectedTransactionProposalEntity(
                amount = parsed.amount,
                currency = parsed.currency,
                label = parsed.label,
                detectedAt = System.currentTimeMillis(),
                sourcePackage = pkg,
                dedupeKey = "${pkg}|${parsed.amount}|${parsed.currency ?: ""}|${parsed.normalizedText}",
            )

            // Anti-doublon : fenêtre courte (2 minutes)
            val inserted = repo.upsertIfNotDuplicate(proposal, dedupeWindowMs = 2 * 60 * 1000L)
            if (inserted > 0) {
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
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
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

        NotificationManagerCompat.from(applicationContext).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
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
