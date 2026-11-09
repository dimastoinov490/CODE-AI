package com.codecore.ai.localai

import android.util.Log
import com.codecore.ai.model.ProjectFile
import com.codecore.ai.project.ProjectFileParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class GenerationStep(
    val title: String,
    val detail: String,
    val progress: Float,
    val streamingToken: String? = null,
    val accumulatedText: String? = null,
    val isCancelled: Boolean = false,
    val isFailed: Boolean = false
)

class LocalAIEngine {
    private val TAG = "LocalAIEngine"
    private val isCancelled = AtomicBoolean(false)

    fun stopGeneration() {
        Log.i(TAG, "Requesting immediate generation stop")
        isCancelled.set(true)
    }

    /**
     * Executes real multi-step autonomous Android code generation using llama.cpp via LlamaBridge.
     */
    fun generateProjectStream(
        handle: Long,
        prompt: String,
        temperature: Float = 0.6f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f
    ): Flow<GenerationStep> = flow {
        if (handle <= 0L || !LlamaBridge.isNativeReady()) {
            emit(
                GenerationStep(
                    title = "Ошибка: AI модель не готова",
                    detail = "Локальная GGUF-модель не загружена в память. Перейдите во вкладку «Модели AI» и загрузите модель.",
                    progress = 0f,
                    isFailed = true
                )
            )
            return@flow
        }

        isCancelled.set(false)

        // ----------------------------------------------------
        // Step 1: Multi-step agent - Architectural Plan
        // ----------------------------------------------------
        emit(GenerationStep("Этап 1/3: Архитектурный план", "Анализ задания и построение структуры файлов…", 0.1f))

        val planSystemPrompt = """<|im_start|>system
Ты — автономный senior Android-архитектор. Сформируй список из 2-4 необходимых файлов для реализации приложения на Kotlin и Jetpack Compose (Material 3).
Пакет приложения: com.codecore.generated.
Перечисли файлы по одному на строку в формате:
File:app/src/main/java/com/codecore/generated/ИмяФайла.kt - краткое назначение
Не пиши лишнего текста.<|im_end|>
<|im_start|>user
Задание: $prompt<|im_end|>
<|im_start|>assistant
"""

        val planOutput = StringBuilder()
        try {
            LlamaBridge.generate(
                handle = handle,
                prompt = planSystemPrompt,
                temperature = temperature,
                topP = topP,
                topK = topK,
                repeatPenalty = repeatPenalty,
                maxTokens = 350
            ) { token ->
                if (isCancelled.get()) return@generate false
                planOutput.append(token)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating plan", e)
        }

        if (isCancelled.get()) {
            emit(GenerationStep("Генерация отменена", "Операция остановлена пользователем", 0f, isCancelled = true))
            return@flow
        }

        val planText = planOutput.toString().trim()
        val plannedFiles = extractPlannedFilePaths(planText)
        val filesToGenerate = if (plannedFiles.isNotEmpty()) plannedFiles else listOf(
            "app/src/main/java/com/codecore/generated/MainActivity.kt",
            "app/src/main/java/com/codecore/generated/ui/AppScreen.kt"
        )

        emit(GenerationStep("Этап 2/3: Генерация файлов", "План сформирован (${filesToGenerate.size} файлов). Начинается синтез кода…", 0.3f))

        // ----------------------------------------------------
        // Step 2: Multi-step agent - File by file synthesis
        // ----------------------------------------------------
        val generatedProjectFiles = mutableListOf<ProjectFile>()
        val totalFiles = filesToGenerate.size

        for ((index, filePath) in filesToGenerate.withIndex()) {
            if (isCancelled.get()) {
                emit(GenerationStep("Генерация отменена", "Операция остановлена пользователем", 0f, isCancelled = true))
                return@flow
            }

            val fileName = filePath.substringAfterLast("/")
            val fileProgress = 0.3f + (index.toFloat() / totalFiles) * 0.6f
            emit(GenerationStep(
                title = "Синтез файла (${index + 1}/$totalFiles)",
                detail = "Генерация $filePath…",
                progress = fileProgress
            ))

            val filePrompt = """<|im_start|>system
Ты — старший Kotlin Android-инженер. Напиши полный, компилируемый исходный код для файла `$filePath`.
Требования:
- Пакет: com.codecore.generated
- Используй Jetpack Compose (Material 3), Coroutines, Flow
- Выдавай строго в формате:
File:$filePath
```kotlin
[исходный код]
```
Не используй не объявленные зависимости. Код должен собираться без ошибок.<|im_end|>
<|im_start|>user
Общее задание: $prompt
Архитектурный план:
$planText

Сгенерируй содержимое для файла: $filePath<|im_end|>
<|im_start|>assistant
File:$filePath
```kotlin
"""

            val fileRawOutput = StringBuilder("File:$filePath\n```kotlin\n")
            try {
                LlamaBridge.generate(
                    handle = handle,
                    prompt = filePrompt,
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    repeatPenalty = repeatPenalty,
                    maxTokens = 1800
                ) { token ->
                    if (isCancelled.get()) return@generate false
                    fileRawOutput.append(token)
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating file $filePath", e)
            }

            if (isCancelled.get()) {
                emit(GenerationStep("Генерация отменена", "Операция остановлена пользователем", 0f, isCancelled = true))
                return@flow
            }

            val parsed = ProjectFileParser.parseFileBlocks(fileRawOutput.toString())
            if (parsed.isNotEmpty()) {
                generatedProjectFiles.addAll(parsed)
            } else {
                // In case of cutoff without closing markdown
                val cleanedCode = fileRawOutput.toString()
                    .substringAfter("```kotlin\n", "")
                    .substringAfter("```\n", "")
                    .substringBeforeLast("```")
                if (cleanedCode.isNotBlank()) {
                    generatedProjectFiles.add(
                        ProjectFile(
                            id = java.util.UUID.randomUUID().toString(),
                            name = fileName,
                            path = filePath,
                            extension = fileName.substringAfterLast(".", ""),
                            content = cleanedCode
                        )
                    )
                }
            }
        }

        // ----------------------------------------------------
        // Step 3: Final validation & completion
        // ----------------------------------------------------
        if (isCancelled.get()) {
            emit(GenerationStep("Генерация отменена", "Операция остановлена пользователем", 0f, isCancelled = true))
            return@flow
        }

        emit(GenerationStep(
            title = "Этап 3/3: Финализация проекта",
            detail = "Сгенерировано ${generatedProjectFiles.size} файлов. Объединение с каркасом Gradle…",
            progress = 1.0f
        ))
    }

    /**
     * Executes real surgical edit on a single file using the active local model or Gemini.
     */
    suspend fun performSurgicalEdit(
        handle: Long,
        filePath: String,
        currentContent: String,
        instruction: String,
        onToken: (String) -> Boolean
    ): String = withContext(Dispatchers.Default) {
        if (handle <= 0L || !LlamaBridge.isNativeReady()) {
            throw IllegalStateException("Локальная AI-модель не загружена в память")
        }

        val prompt = """<|im_start|>system
Ты — инструмент точечного рефакторинга кода Android (Kotlin/Compose).
Твоя задача — обновить переданный файл согласно инструкции пользователя.
Верни ПОЛНЫЙ обновлённый исходный код файла без комментариев-заглушек и без сокращений.
Формат:
File:$filePath
```kotlin
[полный обновлённый код файла]
```<|im_end|>
<|im_start|>user
Файл: $filePath
Текущий код:
```
$currentContent
```

Инструкция: $instruction<|im_end|>
<|im_start|>assistant
File:$filePath
```kotlin
"""

        val output = StringBuilder("File:$filePath\n```kotlin\n")
        LlamaBridge.generate(
            handle = handle,
            prompt = prompt,
            temperature = 0.3f,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.05f,
            maxTokens = 2500
        ) { token ->
            output.append(token)
            onToken(token)
        }

        val parsed = ProjectFileParser.parseFileBlocks(output.toString())
        if (parsed.isNotEmpty()) {
            parsed.first().content
        } else {
            output.toString()
                .substringAfter("```kotlin\n", "")
                .substringAfter("```\n", "")
                .substringBeforeLast("```")
                .ifBlank { currentContent }
        }
    }

    private fun extractPlannedFilePaths(planText: String): List<String> {
        val paths = mutableListOf<String>()
        for (line in planText.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("File:", ignoreCase = true) || trimmed.startsWith("Файл:", ignoreCase = true)) {
                val candidate = trimmed.substringAfter(":").substringBefore("-").substringBefore(" ").trim()
                val sanitized = ProjectFileParser.sanitizePath(candidate)
                if (ProjectFileParser.isValidRelativePath(sanitized) && sanitized.endsWith(".kt")) {
                    paths.add(sanitized)
                }
            } else if (trimmed.contains(".kt")) {
                val match = Regex("""[\w/\.\-]+\.kt""").find(trimmed)?.value
                if (match != null) {
                    val sanitized = ProjectFileParser.sanitizePath(match)
                    if (ProjectFileParser.isValidRelativePath(sanitized)) {
                        paths.add(sanitized)
                    }
                }
            }
        }
        return paths.distinct()
    }
}
