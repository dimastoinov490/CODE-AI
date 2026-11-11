package com.codecore.ai.cloud

import android.util.Log
import com.codecore.ai.model.AIMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for Google's Gemini Developer API.
 * Model IDs are read from the API rather than assumed to be available for every key.
 */
class GeminiClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { "gemini-2.5-flash" },
    private val aiModeProvider: () -> AIMode = { AIMode.CLOUD_ONLY }
) {
    private val tag = "GeminiClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /** Returns the exact text-generation model IDs visible to this API key. */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Введите ключ Gemini API."))

        try {
            val models = linkedSetOf<String>()
            var pageToken: String? = null
            do {
                val urlBuilder = (baseUrl + "models").toHttpUrl().newBuilder()
                    .addQueryParameter("pageSize", "100")
                pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("x-goog-api-key", key)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw apiException(response.code, response.message, body)
                    }
                    val json = JSONObject(body)
                    val entries = json.optJSONArray("models") ?: JSONArray()
                    for (index in 0 until entries.length()) {
                        val model = entries.optJSONObject(index) ?: continue
                        val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        val supportsGenerateContent = (0 until methods.length()).any {
                            methods.optString(it) == "generateContent"
                        }
                        if (supportsGenerateContent) {
                            val id = model.optString("name").removePrefix("models/")
                            if (id.startsWith("gemini-")) models += id
                        }
                    }
                    pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                }
            } while (pageToken != null)

            Result.success(models.sorted())
        } catch (e: Exception) {
            Log.w(tag, "Unable to list Gemini models", e)
            Result.failure(e)
        }
    }

    /** Verifies the key against the selected, usable text-generation model. */
    suspend fun verifyApiKey(): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Введите ключ Gemini API."))
        val modelId = normalizeModelId(modelProvider())
            ?: return@withContext Result.failure(IllegalArgumentException("Укажите корректный ID модели Gemini."))

        try {
            val request = Request.Builder()
                .url(baseUrl + "models/${modelId.encodePathSegment()}")
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw apiException(response.code, response.message, body)
                val model = JSONObject(body)
                val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                val supportsGenerateContent = (0 until methods.length()).any {
                    methods.optString(it) == "generateContent"
                }
                if (!supportsGenerateContent) {
                    throw IllegalStateException("Модель $modelId доступна, но не поддерживает generateContent. Выберите текстовую Gemini-модель.")
                }
                Result.success("Ключ принят Google; доступна текстовая модель $modelId.")
            }
        } catch (e: Exception) {
            Log.w(tag, "Gemini key/model verification failed", e)
            Result.failure(e)
        }
    }

    /** Generates one response (generateContent is a non-streaming endpoint). */
    suspend fun generateContent(
        systemInstruction: String,
        userPrompt: String,
        modelOverride: String? = null,
        onToken: (String) -> Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        if (aiModeProvider() == AIMode.LOCAL_ONLY) {
            return@withContext Result.failure(IllegalStateException("Gemini отключён в автономном режиме. Переключитесь на гибридный или облачный режим."))
        }

        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(IllegalStateException("Ключ Gemini API не задан."))
        val modelId = normalizeModelId(modelOverride ?: modelProvider())
            ?: return@withContext Result.failure(IllegalArgumentException("Некорректный ID модели Gemini."))
        if (userPrompt.isBlank()) return@withContext Result.failure(IllegalArgumentException("Текст запроса Gemini пуст."))

        try {
            val contents = JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            )
            val payload = JSONObject().put("contents", contents)
            if (systemInstruction.isNotBlank()) {
                payload.put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", systemInstruction))
                ))
            }
            payload.put("generationConfig", JSONObject()
                .put("temperature", 0.4)
                .put("maxOutputTokens", 8192))

            val request = Request.Builder()
                .url(baseUrl + "models/${modelId.encodePathSegment()}:generateContent")
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw apiException(response.code, response.message, body)

                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                val candidate = candidates?.optJSONObject(0)
                if (candidate == null) {
                    val reason = json.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { !it.isNullOrBlank() }
                    throw IllegalStateException(
                        if (reason != null) "Google заблокировал запрос: $reason" else "Google не вернул ответ Gemini."
                    )
                }

                val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val text = buildString {
                    for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        val partText = part.optString("text")
                        if (partText.isNotBlank()) append(partText)
                    }
                }.trim()
                if (text.isEmpty()) {
                    val finishReason = candidate.optString("finishReason", "не указана")
                    throw IllegalStateException("Gemini не вернул текстовый ответ (причина завершения: $finishReason). Проверьте, что выбрана текстовая модель.")
                }
                if (!onToken(text)) throw IllegalStateException("Запрос Gemini отменён.")
                Result.success(text)
            }
        } catch (e: Exception) {
            Log.e(tag, "Gemini request failed", e)
            Result.failure(e)
        }
    }

    private fun normalizeModelId(value: String): String? {
        val id = value.trim().removePrefix("models/")
        return id.takeIf { it.startsWith("gemini-") && it.matches(Regex("[A-Za-z0-9._-]+")) }
    }

    private fun String.encodePathSegment(): String =
        toHttpUrl().newBuilder().addPathSegment(this).build().encodedPath.removePrefix("/")

    private fun apiException(code: Int, status: String, body: String): Exception {
        val googleMessage = try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val explanation = when (code) {
            400 -> "Google отклонил запрос. Проверьте формат ключа, выбранную модель и параметры запроса."
            401, 403 -> "Google не принял ключ или у ключа нет доступа к Gemini API. Проверьте ограничения ключа и включённый Generative Language API."
            404 -> "Модель не найдена или недоступна для этого ключа. Обновите список моделей и выберите ID из ответа Google."
            429 -> "Превышена квота или частота запросов Gemini API."
            else -> "Ошибка Gemini API (HTTP $code $status)."
        }
        return Exception(listOfNotNull(explanation, googleMessage).joinToString(" "))
    }
}
