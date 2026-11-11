package com.codecore.ai.github

import android.content.Context
import android.util.Log
import com.codecore.ai.cloud.GeminiClient
import com.codecore.ai.localai.LlamaBridge
import com.codecore.ai.localai.LocalAIEngine
import com.codecore.ai.model.AIMode
import com.codecore.ai.model.CodeCoreProject
import com.codecore.ai.model.SelfHealingStep
import com.codecore.ai.project.ProjectFileParser
import com.codecore.ai.project.ProjectRepository
import com.codecore.ai.training.DatasetManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class CloudConsultation(
    val timestamp: Long = System.currentTimeMillis(),
    val iteration: Int,
    val query: String,
    val response: String,
    val targetFile: String,
    val model: String = "Google Gemini"
)

class SelfHealingEngine(
    private val context: Context,
    private val gitHubClient: GitHubClient,
    private val projectRepository: ProjectRepository,
    private val localAIEngine: LocalAIEngine,
    private val geminiClient: GeminiClient,
    private val datasetManager: DatasetManager? = null
) {
    private val TAG = "SelfHealingEngine"

    private val _healingHistory = MutableStateFlow<List<SelfHealingStep>>(emptyList())
    val healingHistory: StateFlow<List<SelfHealingStep>> = _healingHistory.asStateFlow()

    private val _currentStatus = MutableStateFlow<String>("Ожидание")
    val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()

    private val _downloadedApkFile = MutableStateFlow<File?>(null)
    val downloadedApkFile: StateFlow<File?> = _downloadedApkFile.asStateFlow()

    private val _lastConsultation = MutableStateFlow<CloudConsultation?>(null)
    val lastConsultation: StateFlow<CloudConsultation?> = _lastConsultation.asStateFlow()

    private var isCancelled = false

    fun cancelCycle() {
        isCancelled = true
        _currentStatus.value = "Цикл самоисправления остановлен пользователем"
    }

    /**
     * Executes autonomous CI and self-healing loop.
     */
    suspend fun runAutonomousCycle(
        token: String,
        owner: String,
        repo: String,
        project: CodeCoreProject,
        mode: AIMode,
        localModelHandle: Long,
        commitMessage: String,
        maxIterations: Int = 5,
        onStatusUpdate: (String) -> Unit
    ): Result<File> {
        isCancelled = false
        _healingHistory.value = emptyList()
        _downloadedApkFile.value = null
        _lastConsultation.value = null

        var currentIteration = 1
        var lastErrorSignature = ""
        var consecutiveIdenticalErrors = 0
        var geminiConsultantUsedForCurrentError = false

        // Step 1: Initial Push
        _currentStatus.value = "Отправка кода в GitHub ($owner/$repo)…"
        onStatusUpdate(_currentStatus.value)

        val pushRes = gitHubClient.pushProjectFiles(
            token = token,
            owner = owner,
            repo = repo,
            files = project.files,
            commitMessage = commitMessage.ifBlank { "feat: autonomous build trigger" }
        )

        if (pushRes.isFailure) {
            val err = "Ошибка отправки в репозиторий: ${pushRes.exceptionOrNull()?.message}"
            _currentStatus.value = err
            return Result.failure(Exception(err))
        }

        var headSha = pushRes.getOrNull()!!

        // Main self-healing loop
        while (currentIteration <= maxIterations) {
            if (isCancelled) {
                return Result.failure(Exception("Самоисправление прервано пользователем"))
            }

            _currentStatus.value = "Итерация $currentIteration/$maxIterations: Ожидание запуска воркфлоу GitHub Actions…"
            onStatusUpdate(_currentStatus.value)

            // Wait for workflow to trigger and finish
            var runCompleted = false
            var runConclusion: String? = null
            var runId: Long = 0L
            var pollAttempts = 0

            while (!runCompleted && pollAttempts < 45) { // up to 7-8 minutes
                if (isCancelled) return Result.failure(Exception("Прервано пользователем"))
                delay(10000)
                pollAttempts++

                val runsRes = gitHubClient.getWorkflowRuns(token, owner, repo, headSha)
                if (runsRes.isSuccess && runsRes.getOrNull()!!.isNotEmpty()) {
                    val run = runsRes.getOrNull()!!.first()
                    runId = run.id
                    _currentStatus.value = "Воркфлоу #${run.runNumber}: статус [${run.status}], результат: [${run.conclusion ?: "в процессе"}]"
                    onStatusUpdate(_currentStatus.value)

                    if (run.status == "completed") {
                        runCompleted = true
                        runConclusion = run.conclusion
                    }
                }
            }

            if (!runCompleted) {
                val err = "Превышен таймаут ожидания завершения сборки GitHub Actions"
                _currentStatus.value = err
                return Result.failure(Exception(err))
            }

            // SUCCESS branch -> Save dataset pair!
            if (runConclusion == "success") {
                _currentStatus.value = "Сборка успешна! Загрузка готового app-debug.apk…"
                onStatusUpdate(_currentStatus.value)

                val apkDest = File(context.filesDir, "downloads/${project.name}-debug.apk")
                val dlRes = gitHubClient.downloadArtifact(token, owner, repo, runId, apkDest)
                if (dlRes.isSuccess) {
                    _downloadedApkFile.value = dlRes.getOrNull()
                    _currentStatus.value = "APK успешно загружен! Готов к установке."
                    onStatusUpdate(_currentStatus.value)

                    val refreshedProject = projectRepository.projects.value.find { it.id == project.id } ?: project

                    // Record verified pair in DatasetManager (Task 3)
                    try {
                        datasetManager?.recordVerifiedPair(
                            prompt = project.description.ifBlank { project.name },
                            files = refreshedProject.files
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to record verified pair to dataset", e)
                    }

                    _healingHistory.value = _healingHistory.value + SelfHealingStep(
                        iteration = currentIteration,
                        timestamp = System.currentTimeMillis(),
                        triggerErrorSummary = "Сборка завершена успешно (PASSED)",
                        filesModified = emptyList(),
                        commitSha = headSha,
                        status = "PASSED"
                    )

                    return Result.success(dlRes.getOrNull()!!)
                } else {
                    return Result.failure(dlRes.exceptionOrNull()!!)
                }
            }

            // FAILURE branch -> Self-Healing
            _currentStatus.value = "Сборка завершилась ошибкой. Загрузка логов и анализ причин…"
            onStatusUpdate(_currentStatus.value)

            val jobsRes = gitHubClient.getWorkflowJobs(token, owner, repo, runId)
            val failedJob = jobsRes.getOrNull()?.find { it.conclusion == "failure" }
                ?: jobsRes.getOrNull()?.firstOrNull()

            if (failedJob == null) {
                return Result.failure(Exception("Не удалось найти упавшую задачу в воркфлоу"))
            }

            val logsRes = gitHubClient.getJobLogs(token, owner, repo, failedJob.id)
            if (logsRes.isFailure) {
                return Result.failure(Exception("Не удалось скачать логи сборки: ${logsRes.exceptionOrNull()?.message}"))
            }

            val rawLogs = logsRes.getOrNull()!!
            val extractedErrors = BuildErrorExtractor.extractErrors(rawLogs)
            val errorSummary = BuildErrorExtractor.formatErrorsForAI(extractedErrors)

            // Loop detection
            if (errorSummary == lastErrorSignature) {
                consecutiveIdenticalErrors++
                if (consecutiveIdenticalErrors >= 2 && geminiConsultantUsedForCurrentError) {
                    val stopReason = "Обнаружено зацикливание: повторная ошибка компиляции не устранена. Автономный цикл остановлен."
                    _currentStatus.value = stopReason
                    _healingHistory.value = _healingHistory.value + SelfHealingStep(
                        iteration = currentIteration,
                        timestamp = System.currentTimeMillis(),
                        triggerErrorSummary = errorSummary,
                        filesModified = emptyList(),
                        commitSha = headSha,
                        status = "FAILED_REPEATED",
                        failureReason = stopReason
                    )
                    return Result.failure(Exception(stopReason))
                }
            } else {
                consecutiveIdenticalErrors = 0
                lastErrorSignature = errorSummary
                geminiConsultantUsedForCurrentError = false
            }

            // Locate problematic file (Task 4: Never default unconditionally to MainActivity.kt)
            val targetRelPath = extractedErrors.firstOrNull { it.filePath != null }?.filePath
            val activeFiles = (projectRepository.projects.value.find { it.id == project.id } ?: project).files

            val currentFileObj = if (targetRelPath != null) {
                activeFiles.find { it.path.endsWith(targetRelPath) || targetRelPath.endsWith(it.name) || it.path == targetRelPath }
            } else {
                // Find matching file by checking if class/file name appears in extracted errors
                activeFiles.firstOrNull { file ->
                    val nameWithoutExt = file.name.substringBeforeLast(".")
                    extractedErrors.any { err ->
                        err.message.contains(nameWithoutExt, ignoreCase = false) ||
                            err.rawSnippet.contains(file.name)
                    }
                } ?: activeFiles.firstOrNull { it.extension == "kt" && !it.name.contains("Theme") }
                  ?: activeFiles.firstOrNull()
            }

            if (currentFileObj == null) {
                return Result.failure(Exception("Проблемный файл (${targetRelPath ?: "не определён"}) не найден в проекте"))
            }

            // Hybrid decision (Task 7):
            // In HYBRID mode: iteration 1 uses local AI.
            // If iteration >= 2 or consecutive identical error occurs, Gemini is called as consultant!
            val shouldUseGemini = mode == AIMode.CLOUD_ONLY ||
                (mode == AIMode.HYBRID && (localModelHandle <= 0L || currentIteration >= 2 || consecutiveIdenticalErrors >= 1))

            if (shouldUseGemini && mode == AIMode.HYBRID) {
                geminiConsultantUsedForCurrentError = true
                _currentStatus.value = "Итерация $currentIteration: Подключение Gemini-консультанта для исправления ${currentFileObj.name}…"
            } else {
                _currentStatus.value = "Итерация $currentIteration: Локальное исправление ошибки в ${currentFileObj.name} через AI…"
            }
            onStatusUpdate(_currentStatus.value)

            // Prompt AI to fix error
            val fixedCode = fixFileWithAI(
                useGemini = shouldUseGemini,
                currentIteration = currentIteration,
                localModelHandle = localModelHandle,
                filePath = currentFileObj.path,
                currentContent = currentFileObj.content,
                errors = errorSummary
            )

            // Apply fix to repository
            projectRepository.updateFileContent(project.id, currentFileObj.id, fixedCode)

            // Commit and push
            val aiTag = if (shouldUseGemini) "Gemini Consultant" else "Local AI"
            val fixCommitMsg = "fix(self-healing): iteration $currentIteration via $aiTag in ${currentFileObj.name}"
            val refreshedProject = projectRepository.projects.value.find { it.id == project.id } ?: project

            val newPushRes = gitHubClient.pushProjectFiles(
                token = token,
                owner = owner,
                repo = repo,
                files = refreshedProject.files,
                commitMessage = fixCommitMsg
            )

            if (newPushRes.isFailure) {
                return Result.failure(Exception("Не удалось закоммитить исправление: ${newPushRes.exceptionOrNull()?.message}"))
            }

            headSha = newPushRes.getOrNull()!!

            _healingHistory.value = _healingHistory.value + SelfHealingStep(
                iteration = currentIteration,
                timestamp = System.currentTimeMillis(),
                triggerErrorSummary = "$aiTag: " + errorSummary.take(180),
                filesModified = listOf(currentFileObj.path),
                commitSha = headSha,
                status = "COMMITTED"
            )

            currentIteration++
        }

        val limitMsg = "Достигнут максимальный лимит итераций самоисправления ($maxIterations)."
        _currentStatus.value = limitMsg
        return Result.failure(Exception(limitMsg))
    }

    private suspend fun fixFileWithAI(
        useGemini: Boolean,
        currentIteration: Int,
        localModelHandle: Long,
        filePath: String,
        currentContent: String,
        errors: String
    ): String {
        val instruction = "Исправь ошибки компиляции:\n$errors"

        return if (useGemini) {
            val systemPrompt = "Ты — старший Android-разработчик и облачный консультант CodeCore AI. Исправь переданный файл согласно логам ошибок компиляции. Верни ПОЛНЫЙ исправленный исходный код файла в блоке File:$filePath\n```kotlin\n...\n```."
            val userPrompt = "Файл: $filePath\nТекущий код:\n```\n$currentContent\n```\nОшибки компиляции:\n$errors\n\nВыдай полный исправленный код файла."
            var response = ""
            val res = geminiClient.generateContent(systemPrompt, userPrompt) { chunk ->
                response += chunk
                true
            }
            if (res.isSuccess) {
                _lastConsultation.value = CloudConsultation(
                    iteration = currentIteration,
                    query = "Файл: $filePath\nОшибки:\n$errors",
                    response = response.take(1000),
                    targetFile = filePath
                )
                val parsed = ProjectFileParser.parseFileBlocks(response)
                if (parsed.isNotEmpty()) parsed.first().content else currentContent
            } else {
                currentContent
            }
        } else {
            // Local AI
            localAIEngine.performSurgicalEdit(
                handle = localModelHandle,
                filePath = filePath,
                currentContent = currentContent,
                instruction = instruction
            ) { true }
        }
    }
}
