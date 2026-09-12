package com.lop.budget.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lop.budget.domain.usecase.HandlePaymentNotificationUseCase
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Écoute les notifications système (après autorisation utilisateur).
 *
 * **Adaptateur, pas règle métier** (I-10, CA-26) : ce service traduit un `StatusBarNotification` en
 * [NotificationSnapshot] et délègue. Il ne lit aucun réglage, ne filtre aucune source, ne calcule
 * aucune clé de regroupement et n'écrit rien en base — tout cela vit dans
 * [HandlePaymentNotificationUseCase], donc testable sans appareil.
 */
class LopNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun handlePaymentNotification(): HandlePaymentNotificationUseCase
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val handle = EntryPointAccessors
            .fromApplication(applicationContext, ServiceEntryPoint::class.java)
            .handlePaymentNotification()

        val snapshot = sbn.toSnapshot()
        scope.launch { handle(snapshot) }
    }

    /**
     * `android.bigText` est replié dans le texte : l'instantané n'a que deux champs textuels, et
     * l'analyse recevait déjà les deux collés dans le même ordre.
     */
    private fun StatusBarNotification.toSnapshot(): NotificationSnapshot {
        val extras = notification.extras
        val body = listOfNotNull(
            extras.getCharSequence("android.text")?.toString(),
            extras.getCharSequence("android.bigText")?.toString(),
        ).filter { it.isNotBlank() }.joinToString(" • ")

        return NotificationSnapshot(
            sourcePackage = packageName,
            title = extras.getCharSequence("android.title")?.toString(),
            text = body.ifBlank { null },
            postedAtMillis = postTime,
        )
    }
}
