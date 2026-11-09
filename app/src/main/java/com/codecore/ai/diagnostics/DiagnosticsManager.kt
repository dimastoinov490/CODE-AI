package com.codecore.ai.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.codecore.ai.cloud.GeminiClient
import com.codecore.ai.github.GitHubClient
import com.codecore.ai.localai.LlamaBridge
import com.codecore.ai.model.CodeCoreProject
import com.codecore.ai.model.DiagnosticItem
import com.codecore.ai.model.EngineStatus
import com.codecore.ai.modelmanager.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DiagnosticsManager(
    private val context: Context,
    private val modelManager: ModelManager,
    private val gitHubClient: GitHubClient,
    private val geminiClient: GeminiClient
) {

    suspend fun runAllChecks(
        activeProject: CodeCoreProject?,
        githubToken: String,
        geminiApiKey: String
    ): List<DiagnosticItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DiagnosticItem>()

        // 1. Native NDK Library check
        val isNativeLoaded = LlamaBridge.isNativeReady()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val errorMsg = LlamaBridge.getLoadError()?.localizedMessage
        list.add(
            DiagnosticItem(
                id = "ndk_lib",
                category = "Нативный NDK",
                title = "Библиотека libllama_android.so",
                detail = if (isNativeLoaded) "Загружена успешно (Архитектура устройства: $abi)" else "Ошибка загрузки: ${errorMsg ?: "Библиотека не найдена в APK"}",
                passed = isNativeLoaded,
                recommendation = if (!isNativeLoaded) "Соберите APK с externalNativeBuild и ABI arm64-v8a" else ""
            )
        )

        // 2. RAM and Storage check
        val (availRam, totalRam) = modelManager.getMemoryInfo()
        val availStorageBytes = modelManager.getAvailableStorageBytes()
        val availStorageGb = availStorageBytes / (1024f * 1024f * 1024f)
        val ramPassed = totalRam >= 3.5f
        list.add(
            DiagnosticItem(
                id = "ram_storage",
                category = "Память и ресурсы",
                title = "Оперативная память и накопитель",
                detail = "RAM: %.1f ГБ свободно из %.1f ГБ | Накопитель: %.1f ГБ свободно".format(availRam, totalRam, availStorageGb),
                passed = ramPassed,
                recommendation = if (!ramPassed) "Для моделей 3B рекомендуется устройство с 6 ГБ ОЗУ" else ""
            )
        )

        // 3. GGUF Models on disk
        val installed = modelManager.installedFiles.value
        val modelsValid = installed.isNotEmpty()
        list.add(
            DiagnosticItem(
                id = "gguf_models",
                category = "GGUF Модели",
                title = "Наличие GGUF моделей на диске",
                detail = if (modelsValid) "Обнаружено ${installed.size} валидных GGUF файлов" else "Нет скачанных GGUF моделей",
                passed = modelsValid,
                recommendation = if (!modelsValid) "Перейдите во вкладку «Модели AI» и скачайте Qwen2.5-Coder" else ""
            )
        )

        // 4. Model Active in RAM
        val isReady = modelManager.engineStatus.value == EngineStatus.READY
        val activeName = modelManager.activeModel.value?.name ?: "нет"
        list.add(
            DiagnosticItem(
                id = "model_active",
                category = "AI Движок",
                title = "Активная модель в памяти",
                detail = if (isReady) "Модель $activeName готова к генерации кода" else "Модель не загружена в RAM",
                passed = isReady,
                recommendation = if (!isReady) "Нажмите «Загрузить в RAM» в карточке модели" else ""
            )
        )

        // 5. Network Connectivity
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(net)
        val hasNet = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        list.add(
            DiagnosticItem(
                id = "network",
                category = "Сеть",
                title = "Интернет-соединение",
                detail = if (hasNet) "Подключение к сети активно" else "Нет подключения к интернету",
                passed = hasNet,
                recommendation = if (!hasNet) "Подключите Wi-Fi или мобильный интернет для скачивания моделей и CI" else ""
            )
        )

        // 6. GitHub Token check (real ping)
        if (githubToken.isNotBlank()) {
            val ghRes = gitHubClient.verifyToken(githubToken)
            val ghPassed = ghRes.isSuccess
            val ghDetail = if (ghPassed) {
                val (login, scopes) = ghRes.getOrNull()!!
                "Авторизован как @$login. Права: ${scopes.joinToString(", ")}"
            } else {
                ghRes.exceptionOrNull()?.message ?: "Ошибка проверки токена"
            }
            list.add(
                DiagnosticItem(
                    id = "github_token",
                    category = "GitHub CI",
                    title = "Personal Access Token",
                    detail = ghDetail,
                    passed = ghPassed,
                    recommendation = if (!ghPassed) "Укажите классический PAT с правами 'repo' и 'workflow'" else ""
                )
            )
        } else {
            list.add(
                DiagnosticItem(
                    id = "github_token",
                    category = "GitHub CI",
                    title = "Personal Access Token",
                    detail = "Токен не введён в настройках",
                    passed = false,
                    recommendation = "Введите GitHub PAT для удалённой сборки APK"
                )
            )
        }

        // 7. Gemini API Key check (real ping)
        if (geminiApiKey.isNotBlank()) {
            val geminiRes = geminiClient.verifyApiKey()
            val geminiPassed = geminiRes.isSuccess
            list.add(
                DiagnosticItem(
                    id = "gemini_key",
                    category = "Gemini Cloud",
                    title = "Ключ Gemini API",
                    detail = if (geminiPassed) "Ключ верифицирован Google AI Studio" else (geminiRes.exceptionOrNull()?.message ?: "Недействительный ключ"),
                    passed = geminiPassed,
                    recommendation = if (!geminiPassed) "Проверьте ключ на https://aistudio.google.com" else ""
                )
            )
        }

        // 8. Active Project structure check
        if (activeProject != null) {
            val hasGradlew = activeProject.files.any { it.name == "gradlew" }
            val hasWorkflow = activeProject.files.any { it.path.contains(".github/workflows") }
            val hasManifest = activeProject.files.any { it.name == "AndroidManifest.xml" }
            val projPassed = hasGradlew && hasWorkflow && hasManifest
            list.add(
                DiagnosticItem(
                    id = "project_structure",
                    category = "Проект",
                    title = "Структура активного проекта (${activeProject.name})",
                    detail = "gradlew: ${if (hasGradlew) "Да" else "Нет"} | CI workflow: ${if (hasWorkflow) "Да" else "Нет"} | Manifest: ${if (hasManifest) "Да" else "Нет"}",
                    passed = projPassed,
                    recommendation = if (!projPassed) "Сгенерируйте проект с базовым каркасом" else ""
                )
            )
        }

        list
    }
}
