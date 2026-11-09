package com.codecore.ai

import com.codecore.ai.github.BuildErrorExtractor
import com.codecore.ai.project.ProjectFileParser
import org.junit.Assert.*
import org.junit.Test

class ProjectParserTest {

    @Test
    fun testValidRelativePaths() {
        assertTrue(ProjectFileParser.isValidRelativePath("app/src/main/java/MainActivity.kt"))
        assertTrue(ProjectFileParser.isValidRelativePath("build.gradle.kts"))
        assertTrue(ProjectFileParser.isValidRelativePath("res/values/strings.xml"))

        // Invalid: Directory traversal
        assertFalse(ProjectFileParser.isValidRelativePath("../secret.txt"))
        assertFalse(ProjectFileParser.isValidRelativePath("app/../../root.txt"))

        // Invalid: Absolute path
        assertFalse(ProjectFileParser.isValidRelativePath("/etc/hosts"))
        assertFalse(ProjectFileParser.isValidRelativePath("/app/src/MainActivity.kt"))

        // Invalid: Blank
        assertFalse(ProjectFileParser.isValidRelativePath(""))
        assertFalse(ProjectFileParser.isValidRelativePath("   "))
    }

    @Test
    fun testParseFileBlocks() {
        val rawAIOutput = """
            Конечно, вот готовый проект для Android:

            File:app/src/main/java/com/codecore/generated/MainActivity.kt
            ```kotlin
            package com.codecore.generated

            class MainActivity : ComponentActivity() {
                // UI Code
            }
            ```

            Файл:app/src/main/res/values/strings.xml
            ```xml
            <resources>
                <string name="app_name">TestApp</string>
            </resources>
            ```

            Удачи в разработке!
        """.trimIndent()

        val files = ProjectFileParser.parseFileBlocks(rawAIOutput)
        assertEquals(2, files.size)

        val mainActivity = files.find { it.name == "MainActivity.kt" }
        assertNotNull(mainActivity)
        assertEquals("app/src/main/java/com/codecore/generated/MainActivity.kt", mainActivity!!.path)
        assertTrue(mainActivity.content.contains("class MainActivity"))

        val stringsXml = files.find { it.name == "strings.xml" }
        assertNotNull(stringsXml)
        assertEquals("app/src/main/res/values/strings.xml", stringsXml!!.path)
        assertTrue(stringsXml.content.contains("string name=\"app_name\""))
    }

    @Test
    fun testParseFileBlocksWithCutoff() {
        val cutoffOutput = """
            File:app/src/main/java/com/codecore/generated/HomeScreen.kt
            ```kotlin
            @Composable
            fun HomeScreen() {
                Text("Hello World
        """.trimIndent()

        val files = ProjectFileParser.parseFileBlocks(cutoffOutput)
        assertEquals(1, files.size)
        assertEquals("HomeScreen.kt", files[0].name)
        assertTrue(files[0].content.contains("fun HomeScreen()"))
    }

    @Test
    fun testBuildErrorExtractor() {
        val mockGradleLog = """
            > Task :app:compileDebugKotlin
            e: file:///app/src/main/java/com/codecore/generated/MainActivity.kt:(42, 15): Unresolved reference: NonExistentClass
            e: /app/src/main/java/com/codecore/generated/ui/Screen.kt:18:5 Type mismatch: inferred type is String but Int was expected

            > Task :app:compileDebugKotlin FAILED
            FAILURE: Build completed with 2 failures.
            * What went wrong:
            Execution failed for task ':app:compileDebugKotlin'.
        """.trimIndent()

        val errors = BuildErrorExtractor.extractErrors(mockGradleLog)
        assertTrue(errors.isNotEmpty())

        val firstError = errors.find { it.lineNumber == 42 }
        assertNotNull(firstError)
        assertTrue(firstError!!.message.contains("Unresolved reference: NonExistentClass"))
        assertTrue(firstError.filePath!!.contains("MainActivity.kt"))

        val formatted = BuildErrorExtractor.formatErrorsForAI(errors)
        assertTrue(formatted.contains("MainActivity.kt"))
        assertTrue(formatted.contains("Unresolved reference: NonExistentClass"))
    }
}
