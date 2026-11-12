package com.codecore.ai.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codecore.ai.cloud.GeminiClient
import com.codecore.ai.diagnostics.DiagnosticsManager
import com.codecore.ai.github.GitHubClient
import com.codecore.ai.github.SelfHealingEngine
import com.codecore.ai.localai.GenerationStep
import com.codecore.ai.localai.LocalAIEngine
import com.codecore.ai.model.*
import com.codecore.ai.modelmanager.ModelManager
import com.codecore.ai.project.ProjectRepository
import com.codecore.ai.security.SecureStorage
import com.codecore.ai.training.DatasetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"

    val modelManager = ModelManager(application)
    val projectRepository = ProjectRepository(application)
    val localAIEngine = LocalAIEngine()
    val secureStorage = SecureStorage(application)
    val datasetManager = DatasetManager(application)

    val gitHubClient = GitHubClient(application)
    val geminiClient = GeminiClient(
        apiKeyProvider = { secureStorage.getGeminiApiKey() },
        modelProvider = { secureStorage.getGeminiModel() },
        aiModeProvider = { _selectedMode.value }
    )

    val selfHealingEngine = SelfHealingEngine(
        context = application,
        gitHubClient = gitHubClient,
        projectRepository = projectRepository,
        localAIEngine = localAIEngine,
        geminiClient = geminiClient,
        datasetManager = datasetManager
    )

    val diagnosticsManager = DiagnosticsManager(
        context = application,
        modelManager = modelManager,
        gitHubClient = gitHubClient,
        geminiClient = geminiClient
    )

    private val _selectedMode = MutableStateFlow(AIMode.LOCAL_ONLY)
    val selectedMode: StateFlow<AIMode> = _selectedMode.asStateFlow()

    private val _promptInput = MutableStateFlow("")
    val promptInput: StateFlow<String> = _promptInput.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentGenerationStep = MutableStateFlow<GenerationStep?>(null)
    val currentGenerationStep: StateFlow<GenerationStep?> = _currentGenerationStep.asStateFlow()

    private val _ramTelemetry = MutableStateFlow(modelManager.getMemoryInfo())
    val ramTelemetry: StateFlow<Pair<Float, Float>> = _ramTelemetry.asStateFlow()

    private val _githubToken = MutableStateFlow(secureStorage.getGitHubToken())
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _githubUsername = MutableStateFlow("")
    val githubUsername: StateFlow<String> = _githubUsername.asStateFlow()

    private val _githubScopes = MutableStateFlow<List<String>>(emptyList())
    val githubScopes: StateFlow<List<String>> = _githubScopes.asStateFlow()

    private val _tokenCheckMessage = MutableStateFlow<String?>(null)
    val tokenCheckMessage: StateFlow<String?> = _tokenCheckMessage.asStateFlow()

    private val _isTokenChecking = MutableStateFlow(false)
    val isTokenChecking: StateFlow<Boolean> = _isTokenChecking.asStateFlow()

    private val _geminiApiKey = MutableStateFlow(secureStorage.getGeminiApiKey())
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _geminiModel = MutableStateFlow(secureStorage.getGeminiModel())
    val geminiModel: StateFlow<String> = _geminiModel.asStateFlow()

    private val _geminiCheckMessage = MutableStateFlow<String?>(null)
    val geminiCheckMessage: StateFlow<String?> = _geminiCheckMessage.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _diagnosticChecks = MutableStateFlow<List<DiagnosticItem>>(emptyList())
    val diagnosticChecks: StateFlow<List<DiagnosticItem>> = _diagnosticChecks.asStateFlow()

    private val _isCheckingDiagnostics = MutableStateFlow(false)
    val isCheckingDiagnostics: StateFlow<Boolean> = _isCheckingDiagnostics.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                _ramTelemetry.value = modelManager.getMemoryInfo()
                delay(3000)
            }
        }
        if (_githubToken.value.isNotBlank()) checkGitHubToken()
        runDiagnostics()
    }

    fun setAIMode(mode: AIMode) {
        _selectedMode.value = mode
        if (mode == AIMode.CLOUD_ONLY && _geminiApiKey.value.isBlank()) {
            _geminiCheckMessage.value = "Для облачного режима нужен Gemini API Key."
        }
    }

    fun setPromptInput(text: String) { _promptInput.value = text }
    fun clearUserMessage() { _userMessage.value = null }
    fun showMessage(msg: String) { _userMessage.value = msg }

    fun startProjectGeneration(onSuccess: (CodeCoreProject) -> Unit) {
        val prompt = _promptInput.value.trim()
        if (prompt.isBlank()) {
            showMessage("Введите описание приложения")
            return
        }

        val mode = _selectedMode.value
        val localHandle = modelManager.getActiveModelHandle()

        if (mode == AIMode.LOCAL_ONLY && (localHandle <= 0L || modelManager.engineStatus.value != EngineStatus.READY)) {
            showMessage("Локальная модель не загружена в память. Перейдите во вкладку «Модели AI».")
            return
        }

        if (mode == AIMode.CLOUD_ONLY && _geminiApiKey.value.isBlank()) {
            showMessage("Ключ Gemini API не настроен для режима Cloud.")
            return
        }

        _isGenerating.value = true
        _currentGenerationStep.value = GenerationStep("Инициализация", "Подготовка конвейера генерации…", 0.05f)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (mode == AIMode.CLOUD_ONLY || (mode == AIMode.HYBRID && localHandle <= 0L)) {
                    generateViaGemini(prompt, onSuccess)
                } else {
                    generateViaLocalModel(localHandle, prompt, onSuccess)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                _isGenerating.value = false
                _currentGenerationStep.value = GenerationStep("Ошибка генерации", e.localizedMessage ?: "Сбой", 0f, isFailed = true)
                showMessage("Ошибка: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun generateViaLocalModel(handle: Long, prompt: String, onSuccess: (CodeCoreProject) -> Unit) {
        var isCancelled = false
        val generatedFilesCollector = mutableListOf<ProjectFile>()

        localAIEngine.generateProjectStream(
            handle = handle,
            prompt = prompt,
            temperature = modelManager.temperature.value,
            topP = modelManager.topP.value,
            topK = modelManager.topK.value,
            repeatPenalty = modelManager.repeatPenalty.value
        ).collect { step ->
            _currentGenerationStep.value = step
            if (step.accumulatedFiles != null) {
                generatedFilesCollector.addAll(step.accumulatedFiles)
            }
            if (step.isCancelled) isCancelled = true
        }

        if (isCancelled) {
            _isGenerating.value = false
            return
        }

        if (generatedFilesCollector.isEmpty()) {
            _isGenerating.value = false
            _currentGenerationStep.value = GenerationStep("Ошибка генерации", "Локальная модель не вернула файлы проекта.", 0f, isFailed = true)
            throw IllegalStateException("Локальная модель не вернула файлы проекта. Попробуйте более короткое задание или другой режим.")
        }

        val projectName = com.codecore.ai.project.ProjectNameGenerator.generateProjectName(prompt)
        val created = projectRepository.createProject(
            name = projectName,
            description = prompt,
            generatedFiles = generatedFilesCollector
        )

        _isGenerating.value = false
        _currentGenerationStep.value = null
        _promptInput.value = ""
        onSuccess(created)
    }

    private suspend fun generateViaGemini(prompt: String, onSuccess: (CodeCoreProject) -> Unit) {
        _currentGenerationStep.value = GenerationStep("Генерация через Gemini Cloud", "Отправка запроса в Google AI Studio…", 0.3f)

        val systemPrompt = """Ты — автономный senior Android-разработчик. Создай полнофункциональное приложение на Kotlin + Jetpack Compose.
Пакет: com.codecore.generated.
Выдавай каждый файл строго в формате:
File:app/src/main/java/com/codecore/generated/ИмяФайла.kt
```kotlin
[исходный код]
```
Не пиши вступительного или заключительного текста."""

        var responseText = ""
        val res = geminiClient.generateContent(systemPrompt, prompt) { chunk ->
            responseText += chunk
            true
        }

        if (res.isFailure) {
            throw res.exceptionOrNull() ?: Exception("Сбой вызова Gemini API")
        }

        if (responseText.isBlank()) {
            throw IllegalStateException("Gemini вернул пустой ответ. Проверьте ключ, модель и запрос.")
        }

        _currentGenerationStep.value = GenerationStep("Обработка файлов", "Парсинг сгенерированных блоков кода…", 0.8f)
        val files = com.codecore.ai.project.ProjectFileParser.parseFileBlocks(responseText)
        if (files.isEmpty()) {
            throw IllegalStateException("Gemini не сгенерировал корректные файлы проекта. Проверьте формат ответа модели.")
        }

        val projectName = com.codecore.ai.project.ProjectNameGenerator.generateProjectName(prompt)
        val created = projectRepository.createProject(
            name = projectName,
            description = prompt,
            generatedFiles = files
        )

        _isGenerating.value = false
        _currentGenerationStep.value = null
        _promptInput.value = ""
        onSuccess(created)
    }

    fun stopGeneration() {
        localAIEngine.stopGeneration()
        _isGenerating.value = false
        _currentGenerationStep.value = GenerationStep("Отменено", "Генерация прервана пользователем", 0f, isCancelled = true)
    }

    suspend fun performSurgicalEdit(filePath: String, currentCode: String, instruction: String): Result<String> {
        val mode = _selectedMode.value
        val handle = modelManager.getActiveModelHandle()

        return try {
            if (mode == AIMode.CLOUD_ONLY || (mode == AIMode.HYBRID && handle <= 0L)) {
                val sys = "Ты — эксперт по точечному рефакторингу Android Kotlin. Выдай ПОЛНЫЙ обновлённый файл в блоке File:$filePath\n```kotlin\n[полный код]\n```"
                val usr = "Файл: $filePath\nТекущий код:\n```\n$currentCode\n```\nИнструкция: $instruction"
                var resp = ""
                val res = geminiClient.generateContent(sys, usr) { resp += it; true }
                if (res.isSuccess) {
                    val parsed = com.codecore.ai.project.ProjectFileParser.parseFileBlocks(resp)
                    Result.success(if (parsed.isNotEmpty()) parsed.first().content else currentCode)
                } else {
                    Result.failure(res.exceptionOrNull() ?: Exception("Gemini edit error"))
                }
            } else {
                val updated = localAIEngine.performSurgicalEdit(
                    handle = handle,
                    filePath = filePath,
                    currentContent = currentCode,
                    instruction = instruction
                ) { true }
                Result.success(updated)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun updateGitHubToken(token: String) {
        _githubToken.value = token
        secureStorage.saveGitHubToken(token)
    }

    fun checkGitHubToken() {
        val token = _githubToken.value.trim()
        if (token.isBlank()) {
            _tokenCheckMessage.value = "Введите Personal Access Token"
            return
        }

        _isTokenChecking.value = true
        _tokenCheckMessage.value = "Проверка токена через GET /user…"

        viewModelScope.launch(Dispatchers.IO) {
            val res = gitHubClient.verifyToken(token)
            _isTokenChecking.value = false
            if (res.isSuccess) {
                val (login, scopes) = res.getOrNull()!!
                _githubUsername.value = login
                _githubScopes.value = scopes

                val hasRepo = scopes.contains("repo")
                val hasWorkflow = scopes.contains("workflow")
                if (hasRepo && hasWorkflow) {
                    _tokenCheckMessage.value = "Токен валиден (@$login). Права repo и workflow подтверждены."
                } else {
                    _tokenCheckMessage.value = "Внимание: @$login найден, но не хватает прав: ${if (!hasRepo) "[repo] " else ""}${if (!hasWorkflow) "[workflow]" else ""}"
                }
            } else {
                _tokenCheckMessage.value = res.exceptionOrNull()?.message ?: "Ошибка проверки"
            }
        }
    }

    fun updateGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        secureStorage.saveGeminiApiKey(key)
    }

    fun checkGeminiApiKey() {
        val key = _geminiApiKey.value.trim()
        if (key.isBlank()) {
            _geminiCheckMessage.value = "Введите Gemini API Key"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val res = geminiClient.verifyApiKey()
            if (res.isSuccess) {
                _geminiCheckMessage.value = "Ключ Gemini API валиден и готов к работе."
            } else {
                _geminiCheckMessage.value = res.exceptionOrNull()?.message ?: "Ошибка ключа"
            }
        }
    }

    fun runDiagnostics() {
        _isCheckingDiagnostics.value = true
        viewModelScope.launch {
            val checks = diagnosticsManager.runAllChecks(
                activeProject = projectRepository.activeProject.value,
                githubToken = _githubToken.value,
                geminiApiKey = _geminiApiKey.value
            )
            _diagnosticChecks.value = checks
            _isCheckingDiagnostics.value = false
        }
    }

    fun installApk(apkFile: File) {
        val context = getApplication<Application>()
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
            showMessage("Ошибка установки APK: ${e.localizedMessage}")
        }
    }
}
