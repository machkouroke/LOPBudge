package com.lop.budget.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client minimaliste pour l'API Gemini (generateContent).
 *
 * IMPORTANT (contrainte produit) : l'assistant est volontairement limité au
 * CONSEIL et au QUESTIONNEMENT. Il ne déclenche AUCUNE opération (pas de
 * création/suppression de transaction). On envoie un contexte budgétaire en
 * lecture seule et on récupère une réponse textuelle.
 */
@Singleton
class GeminiClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable private data class Part(val text: String)
    @Serializable private data class Content(val parts: List<Part>, val role: String? = null)
    @Serializable private data class GenerationConfig(
        val temperature: Double = 0.6,
        @SerialName("maxOutputTokens") val maxOutputTokens: Int = 800,
    )
    @Serializable private data class GenerateRequest(
        val contents: List<Content>,
        @SerialName("systemInstruction") val systemInstruction: Content? = null,
        @SerialName("generationConfig") val generationConfig: GenerationConfig = GenerationConfig(),
    )
    @Serializable private data class Candidate(val content: Content? = null)
    @Serializable private data class GenerateResponse(val candidates: List<Candidate> = emptyList())

    /**
     * @param apiKey clé fournie par l'utilisateur dans les réglages.
     * @param systemPrompt cadre l'assistant (rôle, langue, périmètre conseil-only).
     * @param history messages précédents (rôles "user"/"model").
     * @param userMessage nouveau message de l'utilisateur.
     */
    suspend fun ask(
        apiKey: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        model: String = "gemini-1.5-flash",
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Clé API Gemini manquante. Ajoute-la dans Réglages."))
        }
        runCatching {
            val contents = buildList {
                history.forEach { (role, text) -> add(Content(listOf(Part(text)), role)) }
                add(Content(listOf(Part(userMessage)), "user"))
            }
            val payload = GenerateRequest(
                contents = contents,
                systemInstruction = Content(listOf(Part(systemPrompt))),
            )
            val body = json.encodeToString(GenerateRequest.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Erreur API (${resp.code}). Vérifie ta clé Gemini.")
                val parsed = json.decodeFromString(GenerateResponse.serializer(), raw)
                parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: error("Réponse vide de l'assistant.")
            }
        }
    }
}
