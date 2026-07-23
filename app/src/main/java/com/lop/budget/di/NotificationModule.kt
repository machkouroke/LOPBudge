package com.lop.budget.di

import com.lop.budget.notifications.ClassificationResult
import com.lop.budget.notifications.HeuristicNotificationClassifier
import com.lop.budget.notifications.MLKitEntityClassifier
import com.lop.budget.notifications.NotificationClassifier
import com.lop.budget.notifications.SmartCategorizer
import com.lop.budget.notifications.QwenLocalCategorizer
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
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
    fun providePaymentNotificationParser(
        classifier: NotificationClassifier
    ): com.lop.budget.notifications.PaymentNotificationParser {
        return com.lop.budget.notifications.PaymentNotificationParser(classifier)
    }

    @Provides
    @Singleton
    fun provideSmartCategorizer(
        impl: QwenLocalCategorizer
    ): SmartCategorizer = impl
}
