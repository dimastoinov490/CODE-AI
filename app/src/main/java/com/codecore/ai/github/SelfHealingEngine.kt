package com.codecore.ai.github

import android.content.Context
import android.util.Log
import com.codecore.ai.cloud.GeminiClient
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

/** Public UI state for a successful Gemini-assisted correction. */
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
    private val _currentStatus = MutableStateFlow("Ожидание")
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

        _currentStatus.value = "Отправка кода в GitHub ($owner/$repo)…"
        onStatusUpdate(_currentStatus.value)
        val pushRes = gitHubClient.pushProjectFiles(
            token = token,
            owner = owner,
            repo = repo,
            files = project.files,
            commitMessage = commitMessage.ifBlank { "feat: autonomous build trigger" }
        )
        if (pushRes.isFailure) return fail("Ошибка отправки в репозиторий: ${pushRes.exceptionOrNull()?.message}")
        var headSha = pushRes.getOrNull()!!

        while (currentIteration <= maxIterations) {
            if (isCancelled) return fail("Самоисправление прервано пользователем")
            _currentStatus.value = "Итерация $currentIteration/$maxIterations: ожидание GitHub Actions…"
            onStatusUpdate(_currentStatus.value)

            var runCompleted = false
            var runConclusion: String? = null
            var runId = 0L
            var pollAttempts = 0
            while (!runCompleted && pollAttempts < 45) {
                if (isCancelled) return fail("Прервано пользователем")
                delay(10_000)
                pollAttempts++
                val runsRes = gitHubClient.getWorkflowRuns(token, owner, repo, headSha)
                if (runsRes.isFailure) continue
                val run = runsRes.getOrNull()?.firstOrNull() ?: continue
                runId = run.id
                _currentStatus.value = "Воркфлоу #${run.runNumber}: ${run.status}, результат: ${run.conclusion ?: "в процессе"}"
                onStatusUpdate(_currentStatus.value)
                if (run.status == "completed") {
                    runCompleted = true
                    runConclusion = run.conclusion
                }
            }
            if (!runCompleted) return fail("Превышен таймаут ожидания завершения сборки GitHub Actions")

            if (runConclusion == "success") {
                _currentStatus.value = "Сборка успешна! Загрузка APK…"
                onStatusUpdate(_currentStatus.value)
                val apkDest = File(context.filesDir, "downloads/${project.name}-debug.apk")
                val download = gitHubClient.downloadArtifact(token, owner, repo, runId, apkDest)
                if (download.isFailure) return fail("Не удалось загрузить APK: ${download.exceptionOrNull()?.message}")
                val apk = download.getOrNull()!!
                _downloadedApkFile.value = apk
                _currentStatus.value = "APK успешно загружен! Готов к установке."
                onStatusUpdate(_currentStatus.value)
                val refreshed = projectRepository.projects.value.find { it.id == project.id } ?: project
                try {
                    datasetManager?.recordVerifiedPair(
                        prompt = project.description.ifBlank { project.name },
                        files = refreshed.files
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to record verified pair", e)
                }
                _healingHistory.value += SelfHealingStep(
                    iteration = currentIteration,
                    timestamp = System.currentTimeMillis(),
                    triggerErrorSummary = "Сборка завершена успешно (PASSED)",
                    filesModified = emptyList(),
                    commitSha = headSha,
                    status = "PASSED"
                )
                return Result.success(apk)
            }

            _currentStatus.value = "Сборка завершилась ошибкой. Анализ логов…"
            onStatusUpdate(_currentStatus.value)
            val jobs = gitHubClient.getWorkflowJobs(token, owner, repo, runId)
            if (jobs.isFailure) return fail("Не удалось получить задачи сборки: ${jobs.exceptionOrNull()?.message}")
            val failedJob = jobs.getOrNull()?.find { it.conclusion == "failure" }
                ?: return fail("Не удалось определить упавшую задачу GitHub Actions")
            val logs = gitHubClient.getJobLogs(token, owner, repo, failedJob.id)
            if (logs.isFailure) return fail("Не удалось скачать логи: ${logs.exceptionOrNull()?.message}")
            val errors = BuildErrorExtractor.extractErrors(logs.getOrNull().orEmpty())
            if (errors.isEmpty()) return fail("В логах не удалось распознать исправимую ошибку. Автоизменения не выполнялись.")
            val errorSummary = BuildErrorExtractor.formatErrorsForAI(errors)
            if (errorSummary.isBlank()) return fail("Из логов не удалось сформировать описание ошибки.")

            if (errorSummary == lastErrorSignature) {
                consecutiveIdenticalErrors++
                if (consecutiveIdenticalErrors >= 2 && geminiConsultantUsedForCurrentError) {
                    val reason = "Обнаружено зацикливание: повторная ошибка компиляции не устранена. Цикл остановлен."
                    recordFailure(currentIteration, errorSummary, headSha, reason)
                    return fail(reason)
                }
            } else {
                consecutiveIdenticalErrors = 0
                lastErrorSignature = errorSummary
                geminiConsultantUsedForCurrentError = false
            }

            val activeFiles = (projectRepository.projects.value.find { it.id == project.id } ?: project).files
            val targetFile = findUnambiguousTargetFile(errors, activeFiles)
                ?: return fail("Не удалось однозначно определить проблемный файл по логам. Автоматическая правка отменена.")

            val useGemini = mode == AIMode.CLOUD_ONLY ||
                (mode == AIMode.HYBRID && (localModelHandle <= 0L || currentIteration >= 2 || consecutiveIdenticalErrors >= 1))
            if (useGemini && mode == AIMode.HYBRID) geminiConsultantUsedForCurrentError = true
            _currentStatus.value = if (useGemini) {
                "Итерация $currentIteration: Gemini исправляет ${targetFile.path}…"
            } else {
                "Итерация $currentIteration: локальная модель исправляет ${targetFile.path}…"
            }
            onStatusUpdate(_currentStatus.value)

            val fixedCode = try {
                fixFileWithAI(
                    useGemini = useGemini,
                    currentIteration = currentIteration,
                    localModelHandle = localModelHandle,
                    filePath = targetFile.path,
                    currentContent = targetFile.content,
                    errors = errorSummary
                )
            } catch (e: Exception) {
                return fail("AI не смог исправить файл: ${e.localizedMessage ?: "неизвестная ошибка"}")
            }

            if (fixedCode.isBlank() || fixedCode == targetFile.content) {
                val reason = "AI не предложил реального изменения для ${targetFile.path}; коммит не создан."
                recordFailure(currentIteration, errorSummary, headSha, reason, targetFile.path)
                return fail(reason)
            }

            projectRepository.updateFileContent(project.id, targetFile.id, fixedCode)
            val updatedProject = projectRepository.projects.value.find { it.id == project.id } ?: project
            val aiTag = if (useGemini) "Gemini Consultant" else "Local AI"
            val newPush = gitHubClient.pushProjectFiles(
                token = token,
                owner = owner,
                repo = repo,
                files = updatedProject.files,
                commitMessage = "fix(self-healing): iteration $currentIteration via $aiTag in ${targetFile.name}"
            )
            if (newPush.isFailure) return fail("Не удалось отправить исправление: ${newPush.exceptionOrNull()?.message}")
            headSha = newPush.getOrNull()!!
            _healingHistory.value += SelfHealingStep(
                iteration = currentIteration,
                timestamp = System.currentTimeMillis(),
                triggerErrorSummary = "$aiTag: ${errorSummary.take(180)}",
                filesModified = listOf(targetFile.path),
                commitSha = headSha,
                status = "COMMITTED"
            )
            currentIteration++
        }

        return fail("Достигнут максимальный лимит итераций самоисправления ($maxIterations).")
    }

    private fun findUnambiguousTargetFile(
        errors: List<com.codecore.ai.model.ExtractedBuildError>,
        files: List<com.codecore.ai.model.ProjectFile>
    ): com.codecore.ai.model.ProjectFile? {
        val errorPaths = errors.mapNotNull { it.filePath?.let(::normalizePath) }.distinct()
        if (errorPaths.isNotEmpty()) {
            val matches = files.filter { file ->
                val filePath = normalizePath(file.path)
                errorPaths.any { errorPath ->
                    filePath == errorPath || filePath.endsWith("/$errorPath") || errorPath.endsWith("/$filePath")
                }
            }.distinctBy { it.id }
            return matches.singleOrNull()
        }

        val candidates = files.filter { file ->
            errors.any { error ->
                val stem = file.name.substringBeforeLast(".")
                (stem.length >= 4 && error.message.contains(stem, ignoreCase = true)) ||
                    error.rawSnippet.contains(file.name, ignoreCase = true)
            }
        }.distinctBy { it.id }
        return candidates.singleOrNull()
    }

    private fun normalizePath(path: String): String = path
        .replace("\\", "/")
        .removePrefix("file://")
        .trimStart('/')

    private suspend fun fixFileWithAI(
        useGemini: Boolean,
        currentIteration: Int,
        localModelHandle: Long,
        filePath: String,
        currentContent: String,
        errors: String
    ): String {
        val instruction = "Исправь ошибки сборки. Верни полный исправленный файл без пояснений. Ошибки:\n$errors"
        if (!useGemini) {
            if (localModelHandle <= 0L) throw IllegalStateException("Локальная модель не загружена")
            return localAIEngine.performSurgicalEdit(
                handle = localModelHandle,
                filePath = filePath,
                currentContent = currentContent,
                instruction = instruction
            ) { true }
        }

        val systemPrompt = "Ты — старший Android-разработчик. Исправь переданный файл по ошибкам сборки. Верни полный код ровно одного файла в формате File:$filePath и Markdown code block. Не возвращай пояснения."
        val userPrompt = "Файл: $filePath\nТекущий код:\n```\n$currentContent\n```\nОшибки сборки:\n$errors"
        val response = StringBuilder()
        val result = geminiClient.generateContent(systemPrompt, userPrompt) { chunk ->
            response.append(chunk)
            true
        }
        if (result.isFailure) throw result.exceptionOrNull() ?: IllegalStateException("Gemini API error")
        val text = response.toString()
        val parsed = ProjectFileParser.parseFileBlocks(text)
        val matching = parsed.filter { normalizePath(it.path) == normalizePath(filePath) }
        val fixedCode = matching.singleOrNull()?.content
            ?: throw IllegalStateException("Gemini не вернул однозначный блок файла $filePath")
        _lastConsultation.value = CloudConsultation(
            iteration = currentIteration,
            query = "Файл: $filePath\nОшибки:\n$errors",
            response = text.take(1000),
            targetFile = filePath
        )
        return fixedCode
    }

    private fun recordFailure(iteration: Int, errors: String, sha: String, reason: String, path: String? = null) {
        _healingHistory.value += SelfHealingStep(
            iteration = iteration,
            timestamp = System.currentTimeMillis(),
            triggerErrorSummary = errors.take(180),
            filesModified = path?.let(::listOf) ?: emptyList(),
            commitSha = sha,
            status = "FAILED_REPEATED",
            failureReason = reason
        )
    }

    private fun fail(message: String): Result<File> {
        _currentStatus.value = message
        return Result.failure(IllegalStateException(message))
    }
}
