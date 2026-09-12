package com.lop.budget.di

import com.lop.budget.notifications.AndroidDetectionNotifier
import com.lop.budget.notifications.ClassificationResult
import com.lop.budget.notifications.HeuristicNotificationClassifier
import com.lop.budget.notifications.MLKitEntityClassifier
import com.lop.budget.notifications.NotificationClassifier
import com.lop.budget.notifications.PaymentNotificationParser
import com.lop.budget.notifications.PaymentParser
import com.lop.budget.notifications.SmartCategorizer
import com.lop.budget.notifications.QwenLocalCategorizer
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.usecase.DetectionNotifier
import com.lop.budget.domain.usecase.DetectionSettings
import com.lop.budget.domain.usecase.InboxSettings
import com.lop.budget.domain.usecase.ProposalRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {

    @Provides
    @Singleton
    fun provideNotificationClassifier(
        settings: SettingsRepository,
        mlKit: MLKitEntityClassifier
    ): NotificationClassifier {
        return object : NotificationClassifier {
            private val heuristic = HeuristicNotificationClassifier()

            override suspend fun classify(text: String): ClassificationResult {
                val hResult = heuristic.classify(text)
                
                // Si c'est uncertain ou même transaction, on renforce via ML Kit
                if (hResult.status != ClassificationResult.Status.IGNORE && 
                    settings.notificationDetectionEnabled.first()) {
                    return mlKit.classify(text)
                }
                
                return hResult
            }
        }
    }

    @Provides
    @Singleton
    fun provideSmartCategorizer(
        impl: QwenLocalCategorizer
    ): SmartCategorizer = impl

    // --- Ports de la chaîne de détection (US LOP-54, P-10) ---
    // Les use cases ne connaissent que ces interfaces : aucun type Android dans leurs signatures.

    @Provides
    @Singleton
    fun providePaymentParser(impl: PaymentNotificationParser): PaymentParser = impl

    @Provides
    @Singleton
    fun provideDetectionSettings(impl: SettingsRepository): DetectionSettings = impl

    @Provides
    @Singleton
    fun provideInboxSettings(impl: SettingsRepository): InboxSettings = impl

    @Provides
    @Singleton
    fun provideProposalRepository(impl: NotificationDetectionRepository): ProposalRepository = impl

    @Provides
    @Singleton
    fun provideDetectionNotifier(impl: AndroidDetectionNotifier): DetectionNotifier = impl
}
