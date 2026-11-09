package com.codecore.ai.project

import com.codecore.ai.model.ProjectFile
import java.util.UUID

object ProjectFileParser {

    /**
     * Validates relative file paths to protect filesystem integrity.
     * Prevents directory traversal ("..") and absolute paths.
     */
    fun isValidRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.replace("\\", "/").trim()
        if (normalized.startsWith("/") || normalized.startsWith("./")) return false
        val segments = normalized.split("/")
        if (segments.any { it == ".." || it.isBlank() }) return false
        return true
    }

    /**
     * Sanitizes and normalizes relative path.
     */
    fun sanitizePath(path: String): String {
        return path.replace("\\", "/")
            .trim()
            .removePrefix("/")
            .removePrefix("./")
    }

    /**
     * Parses model output containing blocks formatted as:
     * File:path/to/File.kt
     * ```kotlin
     * [content]
     * ```
     */
    fun parseFileBlocks(rawText: String): List<ProjectFile> {
        val result = mutableListOf<ProjectFile>()
        if (rawText.isBlank()) return result

        val lines = rawText.lines()
        var currentPath: String? = null
        val currentContent = StringBuilder()
        var inCodeBlock = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("File:", ignoreCase = true) || trimmed.startsWith("Файл:", ignoreCase = true)) {
                // Save previous file if exists
                if (currentPath != null && isValidRelativePath(currentPath)) {
                    val contentStr = cleanExtractedContent(currentContent.toString())
                    if (contentStr.isNotBlank()) {
                        val fileName = currentPath.substringAfterLast("/")
                        val ext = fileName.substringAfterLast(".", "")
                        result.add(
                            ProjectFile(
                                id = UUID.randomUUID().toString(),
                                name = fileName,
                                path = currentPath,
                                extension = ext,
                                content = contentStr
                            )
                        )
                    }
                }

                val rawPath = trimmed.substringAfter(":").trim()
                val candidatePath = sanitizePath(rawPath)
                currentPath = if (isValidRelativePath(candidatePath)) candidatePath else null
                currentContent.clear()
                inCodeBlock = false
                continue
            }

            if (currentPath != null) {
                if (trimmed.startsWith("```")) {
                    inCodeBlock = !inCodeBlock
                    continue
                }
                currentContent.append(line).append("\n")
            }
        }

        // Handle trailing file
        if (currentPath != null && isValidRelativePath(currentPath)) {
            val contentStr = cleanExtractedContent(currentContent.toString())
            if (contentStr.isNotBlank()) {
                val fileName = currentPath.substringAfterLast("/")
                val ext = fileName.substringAfterLast(".", "")
                result.add(
                    ProjectFile(
                        id = UUID.randomUUID().toString(),
                        name = fileName,
                        path = currentPath,
                        extension = ext,
                        content = contentStr
                    )
                )
            }
        }

        return result
    }

    private fun cleanExtractedContent(raw: String): String {
        var content = raw.trim()
        if (content.endsWith("```")) {
            content = content.removeSuffix("```").trimEnd()
        }
        return content
    }
}
