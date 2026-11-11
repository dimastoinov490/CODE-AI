package com.codecore.ai.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { "gemini-2.5-flash" }
) {
    private val TAG = "GeminiClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Verifies API key with a minimal models list query using x-goog-api-key header.
     */
    suspend fun verifyApiKey(): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isBlank()) return@withContext Result.failure(IllegalArgumentException("Ключ Gemini API не задан"))

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models"
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", key)
                .build()

            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> Result.success("Ключ Gemini API валиден")
                    400 -> Result.failure(Exception("HTTP 400: Неверный формат ключа Gemini API"))
                    403 -> Result.failure(Exception("HTTP 403 Forbidden: Ключ Gemini API недействителен или заблокирован"))
                    429 -> Result.failure(Exception("HTTP 429: Превышена квота запросов Gemini API"))
                    else -> Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes generation with selected Gemini model and token streaming via x-goog-api-key header.
     */
    suspend fun generateContent(
        systemInstruction: String,
        userPrompt: String,
        modelOverride: String? = null,
        onToken: (String) -> Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("Ключ Gemini API отсутствует"))

        val activeModel = (modelOverride ?: modelProvider()).ifBlank { "gemini-2.5-flash" }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent"

            val contentsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                })
            }

            val payload = JSONObject().apply {
                put("contents", contentsArray)
                if (systemInstruction.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                    })
                }
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 8192)
                })
            }

            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", key)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(bodyStr).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    return@withContext Result.failure(Exception(errMsg))
                }

                val json = JSONObject(bodyStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val promptFeedback = json.optJSONObject("promptFeedback")
                    val blockReason = promptFeedback?.optString("blockReason", "Unknown")
                    return@withContext Result.failure(Exception("Запрос заблокирован фильтром безопасности ($blockReason)"))
                }

                val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                val parts = contentObj?.optJSONArray("parts")
                val text = parts?.getJSONObject(0)?.optString("text", "") ?: ""

                onToken(text)
                Result.success(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed", e)
            Result.failure(e)
        }
    }
}
