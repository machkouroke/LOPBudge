package com.lop.budget.notifications

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
private data class LlmResponse(
    val isTransaction: Boolean,
    val confidence: Float,
    val merchant: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
)

/**
 * Classifieur utilisant un LLM local (Gemma 2b via MediaPipe).
 * 
 * IMPORTANT : Cette classe ne doit être instanciée que si le modèle est présent.
 */
@Singleton
class LocalLLMClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationClassifier {

    private var llmInference: LlmInference? = null
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext true

        val modelFile = File(context.filesDir, "gemma-2b-it-gpu-int4.bin")
        if (!modelFile.exists()) return@withContext false

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(256)
                // .setTemperature(0.1f) // Certains SDK MP GenAI ne l'exposent pas directement ici
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            android.util.Log.e("LocalLLM", "Failed to initialize LLM", e)
            false
        }
    }

    override suspend fun classify(text: String): ClassificationResult {
        return classifyAsync(text)
    }

    suspend fun classifyAsync(text: String): ClassificationResult = withContext(Dispatchers.Default) {
        if (!ensureInitialized()) return@withContext ClassificationResult(ClassificationResult.Status.UNCERTAIN, 0.5f, "LLM not initialized")

        val prompt = """
            Analyze this notification and determine if it's a real payment transaction.
            Respond ONLY with a JSON object: {"isTransaction": bool, "confidence": float, "merchant": string, "amount": float, "currency": string}
            
            Notification: "$text"
        """.trimIndent()

        return@withContext try {
            val result = llmInference?.generateResponse(prompt) ?: return@withContext ClassificationResult(ClassificationResult.Status.UNCERTAIN, 0.5f)
            val parsed = json.decodeFromString<LlmResponse>(result)
            
            when {
                (parsed.isTransaction && parsed.confidence > 0.8f) -> 
                    ClassificationResult(ClassificationResult.Status.TRANSACTION, parsed.confidence)
                (!parsed.isTransaction && parsed.confidence > 0.8f) ->
                    ClassificationResult(ClassificationResult.Status.IGNORE, parsed.confidence)
                else -> 
                    ClassificationResult(ClassificationResult.Status.UNCERTAIN, parsed.confidence)
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalLLM", "Inference failed", e)
            ClassificationResult(ClassificationResult.Status.UNCERTAIN, 0.5f)
        }
    }
}
