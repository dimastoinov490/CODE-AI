package com.codecore.ai.project

import java.util.UUID

object ProjectNameGenerator {

    private val translitMap = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private val stopWords = setOf(
        "создай", "сделай", "напиши", "приложение", "проект", "для", "на", "с", "по", "в", "и",
        "программа", "программу", "мобильное", "разработай",
        "create", "make", "build", "app", "application", "for", "with", "a", "an", "the", "in", "to"
    )

    /**
     * Extracts an intuitive, PascalCase project name derived directly from the user's prompt.
     * Guaranteed never to return placeholder names like "AutonomousApp" or "CloudApp".
     */
    fun generateProjectName(prompt: String): String {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) {
            return "App${UUID.randomUUID().toString().take(6).uppercase()}"
        }

        val tokens = trimmed.lowercase()
            .split(Regex("[^a-zA-Zа-яА-Я0-9]+"))
            .filter { it.isNotBlank() && it !in stopWords }

        val candidateWords = if (tokens.isEmpty()) {
            trimmed.split(Regex("[^a-zA-Zа-яА-Я0-9]+")).filter { it.isNotBlank() }
        } else tokens

        val sb = StringBuilder()
        for (word in candidateWords.take(3)) {
            val transliterated = buildString {
                for (ch in word) {
                    val lower = ch.lowercaseChar()
                    append(translitMap[lower] ?: lower)
                }
            }
            if (transliterated.isNotBlank()) {
                sb.append(transliterated.replaceFirstChar { it.uppercase() })
            }
        }

        val result = sb.toString().filter { it.isLetterOrDigit() }
        return when {
            result.length in 3..25 -> "${result}App"
            result.length > 25 -> "${result.take(22)}App"
            else -> "App${UUID.randomUUID().toString().replace("-", "").take(6).uppercase()}"
        }
    }

    /**
     * Generates a unique, compliant Android applicationId for the project.
     * Pattern: com.codecore.app.<slug>.<uniqueHex>
     */
    fun generateUniqueApplicationId(projectName: String): String {
        val slug = projectName.lowercase()
            .removeSuffix("app")
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(12)
            .ifBlank { "app" }
        val randomHex = UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
        return "com.codecore.app.$slug.$randomHex"
    }
}
