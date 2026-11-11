package com.codecore.ai.project

import com.codecore.ai.model.ProjectFile
import java.util.UUID

object ProjectFileParser {

    /** Validates relative file paths to protect filesystem integrity. */
    fun isValidRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.replace("\\", "/").trim()
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.startsWith("./")) return false
        val segments = normalized.split("/")
        if (segments.any { it.isBlank() || it == ".." }) return false
        return true
    }

    /** Sanitizes and normalizes relative path. */
    fun sanitizePath(path: String): String {
        return path
            .replace("\\", "/")
            .trim()
            .removePrefix("/")
            .removePrefix("./")
            .removePrefix("file://")
            .replace(Regex("^.+?:\\s*"), "")
            .trim()
    }

    /** Parses model output containing blocks formatted as File:path/to/File.kt ... */
    fun parseFileBlocks(rawText: String): List<ProjectFile> {
        val result = mutableListOf<ProjectFile>()
        if (rawText.isBlank()) return result

        val lines = rawText.lines()
        var currentPath: String? = null
        val currentContent = StringBuilder()

        fun finalizeCurrent() {
            if (currentPath == null) return
            val contentStr = cleanExtractedContent(currentContent.toString())
            if (contentStr.isNotBlank()) {
                val fileName = currentPath!!.substringAfterLast("/")
                val ext = fileName.substringAfterLast(".", "")
                if (fileName.isNotBlank()) {
                    result.add(
                        ProjectFile(
                            id = UUID.randomUUID().toString(),
                            name = fileName,
                            path = currentPath!!,
                            extension = ext,
                            content = contentStr
                        )
                    )
                }
            }
            currentPath = null
            currentContent.setLength(0)
        }

        for (line in lines) {
            val trimmed = line.trim()
            val fileHeader = Regex("^(?:File|Файл)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
                .find(trimmed)

            if (fileHeader != null) {
                finalizeCurrent()
                val rawPath = sanitizePath(fileHeader.groupValues[1])
                val candidatePath = rawPath.substringBefore(" - ").substringBefore(" ").trim()
                currentPath = if (candidatePath.isNotBlank() && isValidRelativePath(candidatePath)) {
                    candidatePath
                } else {
                    null
                }
                continue
            }

            if (currentPath != null) {
                if (trimmed.startsWith("```")) {
                    continue
                }
                currentContent.append(line).append("\n")
            }
        }

        finalizeCurrent()
        return result
    }

    private fun cleanExtractedContent(raw: String): String {
        var content = raw.trim()
        content = content.removePrefix("```")
        content = content.removeSuffix("```")
        content = content.trim()
        if (content.startsWith("kotlin")) {
            content = content.removePrefix("kotlin").trim()
        }
        if (content.startsWith("xml")) {
            content = content.removePrefix("xml").trim()
        }
        return content
    }
}
