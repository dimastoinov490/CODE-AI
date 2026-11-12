package com.codecore.ai.project

import com.codecore.ai.model.ProjectFile
import java.util.UUID

object ProjectFileParser {
    /** Validates relative file paths to protect filesystem integrity. */
    fun isValidRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized.startsWith('/') || normalized.startsWith("./")) return false
        return normalized.split('/').none { it.isBlank() || it == ".." }
    }

    /** Normalizes common model-provided prefixes but never makes an unsafe path valid. */
    fun sanitizePath(path: String): String {
        var normalized = path.trim().replace('\\', '/')
        if (normalized.startsWith("file://")) normalized = normalized.removePrefix("file://")
        return normalized
    }

    /**
     * Parses `File:path` blocks containing a complete fenced code block.
     * Incomplete/truncated fences are discarded so partial AI output is never written as a source file.
     */
    fun parseFileBlocks(rawText: String): List<ProjectFile> {
        if (rawText.isBlank()) return emptyList()
        val result = mutableListOf<ProjectFile>()
        var currentPath: String? = null
        val content = StringBuilder()
        var insideFence = false
        var completedFence = false

        fun saveCompletedFile() {
            val path = currentPath
            val source = content.toString().trim()
            if (path != null && completedFence && source.isNotBlank() && isValidRelativePath(path)) {
                val name = path.substringAfterLast('/')
                if (name.isNotBlank()) {
                    result += ProjectFile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        path = path,
                        extension = name.substringAfterLast('.', ""),
                        content = source
                    )
                }
            }
        }

        for (line in rawText.lines()) {
            val trimmed = line.trim()
            val header = Regex("^(?:File|Файл)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
            if (header != null) {
                saveCompletedFile()
                val rawPath = sanitizePath(header.groupValues[1])
                // Optional human-readable suffix is allowed only after whitespace or " - ".
                val candidate = rawPath.substringBefore(" - ").substringBefore(Regex("\\s+" )).trim()
                currentPath = candidate.takeIf { isValidRelativePath(it) }
                content.setLength(0)
                insideFence = false
                completedFence = false
                continue
            }

            if (currentPath == null) continue
            if (trimmed.startsWith("```")) {
                if (!insideFence && !completedFence) {
                    insideFence = true
                } else if (insideFence) {
                    insideFence = false
                    completedFence = true
                }
                continue
            }
            if (insideFence) content.append(line).append('\n')
        }

        saveCompletedFile()
        return result
    }
}
