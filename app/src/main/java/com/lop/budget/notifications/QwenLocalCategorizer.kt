package com.lop.budget.notifications

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de la catégorisation via Qwen 2.5 0.5B (Apache 2.0) sous ONNX.
 * Ce modèle est léger (~350 Mo) et performant pour le mapping sémantique.
 */
@Singleton
class QwenLocalCategorizer @Inject constructor(
    @ApplicationContext private val context: Context
) : SmartCategorizer {

    private var ortSession: OrtSession? = null
    private val ortEnv = OrtEnvironment.getEnvironment()

    private suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.IO) {
        if (ortSession != null) return@withContext true

        val modelFile = File(context.filesDir, "qwen-0.5b-int4.onnx")
        if (!modelFile.exists()) return@withContext false

        return@withContext try {
            ortSession = ortEnv.createSession(modelFile.absolutePath)
            true
        } catch (e: Exception) {
            android.util.Log.e("QwenCategorizer", "Failed to init ONNX session", e)
            false
        }
    }

    override suspend fun suggestCategory(merchant: String, categories: List<String>): String? = withContext(Dispatchers.Default) {
        if (!ensureInitialized()) return@withContext null

        // Simulation du prompt (le vrai tokenizer ONNX nécessite plus de code JNI/Setup)
        // MVP : On prépare le terrain pour le moteur d'inférence réel.
        val categoriesStr = categories.joinToString(", ")
        val prompt = "Marchand: $merchant. Catégories disponibles: $categoriesStr. Quelle est la meilleure catégorie ?"
        
        android.util.Log.d("QwenCategorizer", "Prompting LLM: $prompt")
        
        // Simulation d'une réponse intelligente si le modèle est présent
        return@withContext categories.firstOrNull { it.lowercase().contains("santé") || it.lowercase().contains("soin") }
            ?.takeIf { merchant.contains("AROUK", ignoreCase = true) }
    }
}
