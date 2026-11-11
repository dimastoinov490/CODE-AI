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
    val isFailed: Boolean = false,
    val accumulatedFiles: List<ProjectFile>? = null
)

class LocalAIEngine {
    private val TAG = "LocalAIEngine"
    private val isCancelled = AtomicBoolean(false)

    fun stopGeneration() {
        Log.i(TAG, "Requesting immediate generation stop")
        isCancelled.set(true)
    }

    fun generateProjectStream(
        handle: Long,
        prompt: String,
        temperature: Float = 0.6f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f
    ): Flow<GenerationStep> = flow {
        if (handle <= 0L || !LlamaBridge.isNativeReady()) {
            emit(GenerationStep(
                title = "Ошибка: AI модель не готова",
                detail = "Локальная GGUF-модель не загружена в память. Перейдите во вкладку «Модели AI» и загрузите модель.",
                progress = 0f,
                isFailed = true
            ))
            return@flow
        }

        isCancelled.set(false)

        emit(GenerationStep("Этап 1/3: Архитектурный план", "Анализ задания и построение структуры файлов…", 0.1f))

        val planSystemPrompt = """<|im_start|>system
Ты — автономный senior Android-архитектор. Сформируй список из 2–4 необходимых файлов для реализации приложения.
Пакет приложения: com.codecore.generated.
Ты можешь планировать файлы исходного кода (.kt), ресурсы (.xml) и конфигурации.
Разрешены стандартные зависимости: androidx.compose.material3, lifecycle, navigation-compose, coroutines.
Перечисли файлы по одному на строку в формате:
File:app/src/main/java/com/codecore/generated/ИмяФайла.kt - краткое назначение
File:app/src/main/res/values/strings.xml - строковые ресурсы (опционально)
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

        emit(GenerationStep("Этап 2/3: Генерация файлов", "План сформирован (${filesToGenerate.size} файлов). Начинается синтез кода…", 0.5f))

        val generatedProjectFiles = mutableListOf<ProjectFile>()
        val totalFiles = filesToGenerate.size

        for ((index, filePath) in filesToGenerate.withIndex()) {
            if (isCancelled.get()) {
                emit(GenerationStep("Генерация отменена", "Операция остановлена пользователем", 0f, isCancelled = true))
                return@flow
            }

            val fileName = filePath.substringAfterLast("/")
            val isXml = filePath.endsWith(".xml")
            val codeFence = if (isXml) "xml" else "kotlin"
            val fileProgress = 0.3f + (index.toFloat() / totalFiles.coerceAtLeast(1)) * 0.6f
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
```$codeFence
[исходный код]
```
Разрешены только стандартные зависимости Compose/AndroidX. Код должен собираться без ошибок.<|im_end|>
<|im_start|>user
Общее задание: $prompt
Архитектурный план:
$planText

Сгенерируй содержимое для файла: $filePath<|im_end|>
<|im_start|>assistant
File:$filePath
```$codeFence
"""

            val fileRawOutput = StringBuilder("File:$filePath\n```$codeFence\n")
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
                val cleanedCode = fileRawOutput.toString()
                    .substringAfter("```kotlin\n", "")
                    .substringAfter("```xml\n", "")
                    .substringAfter("```\n", "")
                    .substringBeforeLast("```")
                    .trim()
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

        if (isCancelled.get()) {
            emit(GenerationStep("Генерация отменена", "Операция остановлена пользователем", 0f, isCancelled = true))
            return@flow
        }

        if (generatedProjectFiles.isEmpty()) {
            emit(GenerationStep(
                title = "Ошибка генерации",
                detail = "Локальная модель не вернула корректный код ни для одного файла.",
                progress = 0f,
                isFailed = true
            ))
            return@flow
        }

        emit(GenerationStep(
            title = "Этап 3/3: Финализация проекта",
            detail = "Сгенерировано ${generatedProjectFiles.size} файлов. Объединение с каркасом Gradle…",
            progress = 1.0f,
            accumulatedFiles = generatedProjectFiles
        ))
    }

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
Верни ПОЛНЫЙ обновлённы�� исходный код файла без комментариев-заглушек и без сокращений.
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
                .trim()
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
                if (ProjectFileParser.isValidRelativePath(sanitized) && (sanitized.endsWith(".kt") || sanitized.endsWith(".xml") || sanitized.endsWith(".kts"))) {
                    paths.add(sanitized)
                }
            } else if (trimmed.contains(".kt") || trimmed.contains(".xml")) {
                val match = Regex("""[\w/\.\-]+\.(kt|xml|kts)""").find(trimmed)?.value
                if (match != null) {
                    val sanitized = ProjectFileParser.sanitizePath(match)
                    if (ProjectFileParser.isValidRelativePath(sanitized)) paths.add(sanitized)
                }
            }
        }
        return paths.distinct()
    }
}
