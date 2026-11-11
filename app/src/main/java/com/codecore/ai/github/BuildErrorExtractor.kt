package com.codecore.ai.github

import com.codecore.ai.model.ExtractedBuildError

object BuildErrorExtractor {

    /**
     * Extracts meaningful Kotlin compiler and Gradle build errors from full CI logs.
     * Prevents sending tens of thousands of log lines to AI models.
     */
    fun extractErrors(rawLog: String): List<ExtractedBuildError> {
        val result = mutableListOf<ExtractedBuildError>()
        if (rawLog.isBlank()) return result

        val lines = rawLog.lines()
        val kotlinErrorRegex = Regex("""e:\s+(?:file:///)?([^\s:]+):\((\d+),\s*(\d+)\):\s*(.+)""")
        val altKotlinRegex = Regex("""e:\s+(?:file:///)?([^\s:]+):(\d+):(\d+)\s*(.+)""")
        val javaErrorRegex = Regex("""([^\s:]+\.java):(\d+):\s*error:\s*(.+)""")
        val aaptXmlErrorRegex = Regex("""(?:AAPT:\s+error:\s+)?(?:file:///)?([^\s:]+\.xml):(\d+)(?::\d+)?:\s*(?:error:\s*)?(.+)""")
        val gradleScriptErrorRegex = Regex("""Build file '([^']+)' line:\s*(\d+)""")

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            val kMatch = kotlinErrorRegex.find(trimmed) ?: altKotlinRegex.find(trimmed)
            if (kMatch != null) {
                val fullPath = kMatch.groupValues[1]
                val lineNum = kMatch.groupValues[2].toIntOrNull()
                val msg = kMatch.groupValues[4].trim()
                val relPath = normalizeFilePath(fullPath)
                result.add(
                    ExtractedBuildError(
                        filePath = relPath,
                        lineNumber = lineNum,
                        errorType = "KotlinCompilationError",
                        message = msg,
                        rawSnippet = trimmed
                    )
                )
                continue
            }

            val jMatch = javaErrorRegex.find(trimmed)
            if (jMatch != null) {
                val fullPath = jMatch.groupValues[1]
                val lineNum = jMatch.groupValues[2].toIntOrNull()
                val msg = jMatch.groupValues[3].trim()
                val relPath = normalizeFilePath(fullPath)
                result.add(
                    ExtractedBuildError(
                        filePath = relPath,
                        lineNumber = lineNum,
                        errorType = "JavaCompilationError",
                        message = msg,
                        rawSnippet = trimmed
                    )
                )
                continue
            }

            val xMatch = aaptXmlErrorRegex.find(trimmed)
            if (xMatch != null) {
                val fullPath = xMatch.groupValues[1]
                val lineNum = xMatch.groupValues[2].toIntOrNull()
                val msg = xMatch.groupValues[3].trim()
                val relPath = normalizeFilePath(fullPath)
                result.add(
                    ExtractedBuildError(
                        filePath = relPath,
                        lineNumber = lineNum,
                        errorType = "Aapt2ResourceError",
                        message = msg,
                        rawSnippet = trimmed
                    )
                )
                continue
            }

            val gMatch = gradleScriptErrorRegex.find(trimmed)
            if (gMatch != null) {
                val fullPath = gMatch.groupValues[1]
                val lineNum = gMatch.groupValues[2].toIntOrNull()
                val relPath = normalizeFilePath(fullPath)
                result.add(
                    ExtractedBuildError(
                        filePath = relPath,
                        lineNumber = lineNum,
                        errorType = "GradleScriptError",
                        message = "Build script failure: $trimmed",
                        rawSnippet = trimmed
                    )
                )
                continue
            }

            if (trimmed.startsWith("e: ") || trimmed.startsWith("error: ")) {
                val msg = trimmed.removePrefix("e: ").removePrefix("error: ")
                result.add(
                    ExtractedBuildError(
                        filePath = null,
                        lineNumber = null,
                        errorType = "CompilerMessage",
                        message = msg,
                        rawSnippet = trimmed
                    )
                )
                continue
            }

            if (trimmed.contains("What went wrong:", ignoreCase = true) || trimmed.contains("Execution failed for task", ignoreCase = true)) {
                val contextLines = lines.subList(index, minOf(index + 5, lines.size)).joinToString("\n")
                result.add(
                    ExtractedBuildError(
                        filePath = null,
                        lineNumber = null,
                        errorType = "GradleTaskFailure",
                        message = trimmed,
                        rawSnippet = contextLines
                    )
                )
            }
        }

        return result.distinctBy { it.rawSnippet }
    }

    /**
     * Produces a clean, concise prompt summary for AI models.
     */
    fun formatErrorsForAI(errors: List<ExtractedBuildError>): String {
        if (errors.isEmpty()) return "Build failed with unparsed task errors. Please check Gradle configuration and imports."

        val sb = StringBuilder("Ошибки компиляции Android / Gradle:\n")
        for ((idx, err) in errors.take(6).withIndex()) {
            sb.append("${idx + 1}. ")
            if (err.filePath != null) {
                sb.append("Файл: ${err.filePath}")
                if (err.lineNumber != null) sb.append(" (строка ${err.lineNumber})")
                sb.append("\n   ")
            }
            sb.append("Ошибка: ${err.message}\n")
        }
        return sb.toString()
    }

    private fun normalizeFilePath(path: String): String {
        val clean = path.replace("\\", "/").trim().removePrefix("file://")
        return when {
            clean.contains("app/src/") -> clean.substring(clean.indexOf("app/src/"))
            clean.contains("src/main/") -> "app/" + clean.substring(clean.indexOf("src/main/"))
            clean.contains("app/") -> clean.substring(clean.indexOf("app/"))
            else -> clean.substringAfterLast("/")
        }
    }
}
