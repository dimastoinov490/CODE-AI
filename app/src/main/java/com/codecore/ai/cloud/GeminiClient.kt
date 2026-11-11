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

/** REST client for Google's Gemini Developer API. */
class GeminiClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { "gemini-2.5-flash" },
    private val aiModeProvider: () -> AIMode = { AIMode.CLOUD_ONLY }
) {
    private val tag = "GeminiClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /** Queries Google for models available to this key that support text generation. */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Введите ключ Gemini API."))
        try {
            val models = linkedSetOf<String>()
            var pageToken: String? = null
            do {
                val urlBuilder = endpoint("models").newBuilder().addQueryParameter("pageSize", "100")
                pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
                val request = Request.Builder().url(urlBuilder.build())
                    .header("x-goog-api-key", key).header("Accept", "application/json").get().build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw apiException(response.code, response.message, body)
                    val json = JSONObject(body)
                    val entries = json.optJSONArray("models") ?: JSONArray()
                    for (index in 0 until entries.length()) {
                        val model = entries.optJSONObject(index) ?: continue
                        val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        if ((0 until methods.length()).any { methods.optString(it) == "generateContent" }) {
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

    /** Verifies the key and selected model using Google's model resource endpoint. */
    suspend fun verifyApiKey(): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Введите ключ Gemini API."))
        val modelId = normalizeModelId(modelProvider())
            ?: return@withContext Result.failure(IllegalArgumentException("Укажите корректный ID модели Gemini."))
        try {
            val request = Request.Builder().url(modelEndpoint(modelId))
                .header("x-goog-api-key", key).header("Accept", "application/json").get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw apiException(response.code, response.message, body)
                val methods = JSONObject(body).optJSONArray("supportedGenerationMethods") ?: JSONArray()
                if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) {
                    throw IllegalStateException("Модель $modelId доступна, но не поддерживает generateContent. Выберите текстовую Gemini-модель.")
                }
                Result.success("Ключ Gemini API валиден; Google подтвердил доступ к модели $modelId.")
            }
        } catch (e: Exception) {
            Log.w(tag, "Gemini key/model verification failed", e)
            Result.failure(e)
        }
    }

    /** Calls generateContent; this REST endpoint returns a complete response, not token streaming. */
    suspend fun generateContent(
        systemInstruction: String,
        userPrompt: String,
        modelOverride: String? = null,
        onToken: (String) -> Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        if (aiModeProvider() == AIMode.LOCAL_ONLY) {
            return@withContext Result.failure(IllegalStateException("Gemini отключён в автономном режиме. Переключитесь на гибридный или обла��ный режим."))
        }
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext Result.failure(IllegalStateException("Ключ Gemini API не задан."))
        val modelId = normalizeModelId(modelOverride ?: modelProvider())
            ?: return@withContext Result.failure(IllegalArgumentException("Некорректный ID модели Gemini."))
        if (userPrompt.isBlank()) return@withContext Result.failure(IllegalArgumentException("Текст запроса Gemini пуст."))

        try {
            val content = JSONObject().put("role", "user").put(
                "parts", JSONArray().put(JSONObject().put("text", userPrompt))
            )
            val payload = JSONObject().put("contents", JSONArray().put(content))
            if (systemInstruction.isNotBlank()) {
                payload.put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", systemInstruction))
                ))
            }
            payload.put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 8192))

            val request = Request.Builder().url(modelEndpoint(modelId) + ":generateContent")
                .header("x-goog-api-key", key).header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType)).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw apiException(response.code, response.message, body)
                val json = JSONObject(body)
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                if (candidate == null) {
                    val reason = json.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { !it.isNullOrBlank() }
                    throw IllegalStateException(reason?.let { "Google заблокировал запрос: $it" } ?: "Google не вернул ответ Gemini.")
                }
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val text = buildString {
                    for (index in 0 until parts.length()) {
                        val partText = parts.optJSONObject(index)?.optString("text").orEmpty()
                        if (partText.isNotBlank()) append(partText)
                    }
                }.trim()
                if (text.isEmpty()) {
                    throw IllegalStateException("Gemini не вернул текст (finishReason=${candidate.optString("finishReason", "unknown")}). Выберите текстовую Gemini-модель.")
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

    private fun endpoint(path: String) = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl()
        .newBuilder().addPathSegments(path).build()

    private fun modelEndpoint(modelId: String) = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl()
        .newBuilder().addPathSegment("models").addPathSegment(modelId).build()

    private fun apiException(code: Int, status: String, body: String): Exception {
        val googleMessage = try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        val explanation = when (code) {
            400 -> "Google отклонил запрос. Проверьте модель и параметры запроса."
            401, 403 -> "Google не принял ключ или у него нет доступа к Gemini API. Проверьте ключ и ограничения доступа."
            404 -> "Модель не найдена или недоступна для этого ключа. Обновите список моделей по ответу Google."
            429 -> "Превышена квота или частота запросов Gemini API."
            else -> "Ошибка Gemini API (HTTP $code $status)."
        }
        return Exception(listOfNotNull(explanation, googleMessage).joinToString(" "))
    }
}
