package com.codecore.ai.model

enum class AIMode(val title: String, val subtitle: String, val badge: String) {
    LOCAL_ONLY("Mode A: Local AI", "100% Autonomous, zero cloud requests, fully offline", "OFFLINE"),
    HYBRID("Mode B: Local AI + Gemini", "Local AI is primary, Gemini assists with reviews", "HYBRID"),
    CLOUD_ONLY("Mode C: Cloud AI Only", "Fast generation directly through cloud intelligence", "CLOUD")
}

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    LOADED_IN_RAM,
    ERROR
}

data class GGUFModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val requiredRamGb: Float,
    val quantization: String,
    val downloadUrl: String,
    val status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val isRecommended: Boolean = false
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
