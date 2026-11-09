package com.codecore.ai.model

enum class AIMode(val title: String, val subtitle: String, val badge: String) {
    LOCAL_ONLY("Режим A: Только Local AI", "100% автономно, без интернета, запросы в облако отключены", "АВТОНОМНЫЙ"),
    HYBRID("Режим B: Local AI + Gemini", "Локальная модель — разработчик, Gemini — облачный консультант", "ГИБРИД"),
    CLOUD_ONLY("Режим C: Только Cloud AI", "Быстрая генерация напрямую через облачный интеллект", "ОБЛАКО")
}

enum class EngineStatus {
    NOT_INSTALLED,      // Модель не установлена (нет GGUF файлов)
    DOWNLOADING,        // Идёт скачивание
    INSTALLED,          // Файл на диске, но не загружен в RAM
    LOADING_TO_RAM,     // Загрузка весов в RAM
    READY,              // Успешно загружен в RAM и готов к генерации
    ERROR,              // Ошибка загрузки или выполнения
    LOW_MEMORY          // Недостаточно RAM для загрузки
}

data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val progressPercent: Float = 0f,
    val speedBytesPerSec: Long = 0,
    val etaSeconds: Long = 0,
    val isPaused: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null
)

data class CatalogModel(
    val id: String,
    val name: String,
    val developer: String,
    val purpose: String,
    val supportedLanguages: String,
    val fileName: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val requiredRamGb: Float,
    val quantization: String,
    val downloadUrl: String,
    val isRecommended: Boolean = false,
    val isCustom: Boolean = false
)

data class ProjectFile(
    val id: String,
    val name: String,
    val path: String,
    val extension: String,
    val content: String,
    val isDirectory: Boolean = false,
    val lastModified: Long = System.currentTimeMillis()
)

data class CodeCoreProject(
    val id: String,
    val name: String,
    val description: String,
    val language: String,
    val files: List<ProjectFile>,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

data class DiagnosticItem(
    val id: String,
    val category: String,
    val title: String,
    val detail: String,
    val passed: Boolean,
    val recommendation: String = ""
)

data class GitHubConfig(
    val token: String = "",
    val username: String = "",
    val defaultBranch: String = "main",
    val isPrivateRepos: Boolean = true,
    val lastVerifiedTimestamp: Long = 0,
    val scopes: List<String> = emptyList()
)

data class CreatedRepoInfo(
    val owner: String,
    val repo: String,
    val projectId: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class WorkflowRunInfo(
    val id: Long,
    val name: String,
    val status: String,       // queued, in_progress, completed
    val conclusion: String?,  // success, failure, cancelled, etc.
    val htmlUrl: String,
    val headSha: String,
    val runNumber: Int,
    val createdAt: String,
    val jobs: List<WorkflowJobInfo> = emptyList()
)

data class WorkflowJobInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val stepNames: List<String> = emptyList()
)

data class ExtractedBuildError(
    val filePath: String?,
    val lineNumber: Int?,
    val errorType: String,
    val message: String,
    val rawSnippet: String
)

data class SelfHealingStep(
    val iteration: Int,
    val timestamp: Long,
    val triggerErrorSummary: String,
    val filesModified: List<String>,
    val commitSha: String?,
    val status: String, // FIXING, COMMITTED, WAITING_BUILD, PASSED, FAILED_REPEATED
    val failureReason: String? = null
)
