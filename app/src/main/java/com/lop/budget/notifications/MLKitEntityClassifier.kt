package com.lop.budget.notifications

import android.content.Context
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.MoneyEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Classifieur utilisant Google ML Kit pour une détection structurée et transparente.
 * Extrait montants, devises et dates de manière robuste.
 */
@Singleton
class MLKitEntityClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) : NotificationClassifier {

    private val entityExtractor = EntityExtraction.getClient(
        EntityExtractorOptions.Builder(EntityExtractorOptions.FRENCH).build()
    )

    override suspend fun classify(text: String): ClassificationResult {
        return try {
            // Téléchargement automatique et transparent du micro-modèle
            suspendCancellableCoroutine<Unit> { cont ->
                entityExtractor.downloadModelIfNeeded()
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }

            val params = EntityExtractionParams.Builder(text).build()
            val annotations = suspendCancellableCoroutine<List<EntityAnnotation>> { cont ->
                entityExtractor.annotate(params)
                    .addOnSuccessListener { results -> cont.resume(results) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }

            var hasMoney = false
            var confidence = 0.5f

            for (annotation in annotations) {
                for (entity in annotation.entities) {
                    if (entity is MoneyEntity) {
                        hasMoney = true
                        confidence = 0.9f
                    }
                }
            }

            if (hasMoney) {
                ClassificationResult(ClassificationResult.Status.TRANSACTION, confidence)
            } else {
                ClassificationResult(ClassificationResult.Status.UNCERTAIN, 0.3f)
            }
        } catch (e: Exception) {
            android.util.Log.e("MLKitClassifier", "Entity extraction failed", e)
            ClassificationResult(ClassificationResult.Status.UNCERTAIN, 0.2f)
        }
    }
}
