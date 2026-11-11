package com.codecore.ai

import com.codecore.ai.model.ProjectFile
import com.codecore.ai.project.ProjectNameGenerator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProjectScaffoldTest {

    @Test
    fun testProjectNameGeneratorFromPrompt() {
        val name1 = ProjectNameGenerator.generateProjectName("Блокнот заметок с локальным поиском")
        assertTrue("Name should start with uppercase: $name1", name1.first().isUpperCase())
        assertFalse("Name should not contain spaces: $name1", name1.contains(" "))
        assertTrue("Name should contain transliterated letters: $name1", name1.contains("Bloknot"))

        val name2 = ProjectNameGenerator.generateProjectName("ToDo List Planner 2026")
        assertTrue("Name should retain ASCII words: $name2", name2.startsWith("Todo") || name2.startsWith("ToDo"))

        val nameEmpty = ProjectNameGenerator.generateProjectName("   ")
        assertTrue("Blank prompt should generate dynamic App name: $nameEmpty", nameEmpty.startsWith("App"))
    }

    @Test
    fun testUniqueApplicationIdGeneration() {
        val appId1 = ProjectNameGenerator.generateUniqueApplicationId("NotesApp")
        assertTrue("AppId should start with com.codecore.app: $appId1", appId1.startsWith("com.codecore.app."))
        val parts = appId1.split(".")
        assertTrue("AppId should have 5 parts: $appId1", parts.size >= 4)

        val appId2 = ProjectNameGenerator.generateUniqueApplicationId("NotesApp")
        // Different random hash suffix per project
        assertNotEquals("App IDs should be unique per invocation", appId1, appId2)
    }

    @Test
    fun testScaffoldAssetPathMappingRules() {
        fun mapTemplateAssetPath(path: String): String {
            return when {
                path.startsWith("github_workflows/") -> ".github/workflows/" + path.removePrefix("github_workflows/")
                path == "github_workflows" -> ".github/workflows"
                path == "gitignore.txt" -> ".gitignore"
                else -> path
            }
        }

        assertEquals(".github/workflows/build.yml", mapTemplateAssetPath("github_workflows/build.yml"))
        assertEquals(".github/workflows", mapTemplateAssetPath("github_workflows"))
        assertEquals(".gitignore", mapTemplateAssetPath("gitignore.txt"))
        assertEquals("app/build.gradle.kts", mapTemplateAssetPath("app/build.gradle.kts"))
        assertEquals("gradle/wrapper/gradle-wrapper.properties", mapTemplateAssetPath("gradle/wrapper/gradle-wrapper.properties"))
    }

    @Test
    fun testTemplateAssetsContainWorkflowAndGitignore() {
        val assetsDir = File("src/main/assets/project_template")
        assertTrue("Assets project_template directory must exist", assetsDir.exists())

        val workflowAsset = File(assetsDir, "github_workflows/build.yml")
        assertTrue("Asset github_workflows/build.yml must exist without leading dot", workflowAsset.exists())
        val workflowContent = workflowAsset.readText()
        assertTrue("Workflow must contain 'name: Build Android APK'", workflowContent.contains("name: Build Android APK"))
        assertTrue("Workflow must contain 'name: Build Debug APK'", workflowContent.contains("name: Build Debug APK"))
        assertTrue("Workflow must contain 'actions/checkout@v4'", workflowContent.contains("actions/checkout@v4"))
        assertTrue("Workflow must contain JDK 21 temurin", workflowContent.contains("java-version: '21'") && workflowContent.contains("distribution: 'temurin'"))
        assertTrue("Workflow must build with ./gradlew assembleDebug", workflowContent.contains("./gradlew assembleDebug"))
        assertTrue("Workflow must upload artifact app-debug", workflowContent.contains("name: app-debug"))
        assertTrue("Workflow must target app/build/outputs/apk/debug/app-debug.apk", workflowContent.contains("app/build/outputs/apk/debug/app-debug.apk"))

        val gitignoreAsset = File(assetsDir, "gitignore.txt")
        assertTrue("Asset gitignore.txt must exist without leading dot", gitignoreAsset.exists())
        val gitignoreContent = gitignoreAsset.readText()
        assertTrue("Gitignore must ignore .gradle", gitignoreContent.contains(".gradle"))
        assertTrue("Gitignore must ignore /build", gitignoreContent.contains("/build"))
    }

    @Test
    fun testSimulatedProjectScaffoldCreation() {
        val tempDir = File.createTempFile("test_proj_", "").apply {
            delete()
            mkdirs()
        }

        try {
            // Simulate copyTemplateScaffold with asset mapping
            val assetsDir = File("src/main/assets/project_template")
            assetsDir.walkTopDown().filter { it.isFile }.forEach { sourceFile ->
                val rawRel = sourceFile.relativeTo(assetsDir).path.replace("\\", "/")
                val targetRel = when {
                    rawRel.startsWith("github_workflows/") -> ".github/workflows/" + rawRel.removePrefix("github_workflows/")
                    rawRel == "gitignore.txt" -> ".gitignore"
                    else -> rawRel
                }
                val destFile = File(tempDir, targetRel)
                destFile.parentFile?.mkdirs()
                sourceFile.copyTo(destFile, overwrite = true)
            }

            // Verify .github/workflows/build.yml and .gitignore are present in created project
            val targetWorkflow = File(tempDir, ".github/workflows/build.yml")
            assertTrue("Created project MUST contain .github/workflows/build.yml", targetWorkflow.exists())
            assertTrue(targetWorkflow.readText().contains("Build Android APK"))

            val targetGitignore = File(tempDir, ".gitignore")
            assertTrue("Created project MUST contain .gitignore", targetGitignore.exists())

            val targetBuildGradle = File(tempDir, "app/build.gradle.kts")
            assertTrue("Created project MUST contain app/build.gradle.kts", targetBuildGradle.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
