package com.lop.budget.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lop.budget.MainActivity
import com.lop.budget.R
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.usecase.DetectionNotifier
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.util.Format
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation Android de [DetectionNotifier] : tout ce que le use case de détection ne doit pas
 * connaître (canal, intention, gestionnaire de notifications) est isolé ici.
 *
 * ÉCARTS CONSERVÉS PAR LA REFONTE :
 * - E-13 : l'absence d'autorisation `POST_NOTIFICATIONS` est capturée et seulement journalisée.
 *   La proposition existe, mais l'utilisateur n'en est jamais averti et l'échec reste silencieux
 *   (CA-24) ;
 * - E-15 : l'identifiant de notification dérive de l'horloge, les avis s'empilent au lieu de se
 *   remplacer.
 */
@Singleton
class AndroidDetectionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : DetectionNotifier {

    override fun notifyProposal(proposal: Proposal) {
        ensureChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("route", Routes.DETECTED)
        }

        val pi = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = "${proposal.label} • ${Format.money(proposal.amountCents, proposal.currency ?: "EUR")}".trim()

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_detected_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
        } catch (e: SecurityException) {
            android.util.Log.e("LopNotifService", "Permission POST_NOTIFICATIONS missing", e)
        }
    }

    private fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "detected_transactions"
    }
}
