package com.codecore.ai.project

import android.content.Context
import android.util.Log
import com.codecore.ai.model.CodeCoreProject
import com.codecore.ai.model.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectRepository(private val context: Context) {
    private val TAG = "ProjectRepository"

    val projectsDir: File = File(context.filesDir, "projects").apply {
        if (!exists()) mkdirs()
    }

    private val _projects = MutableStateFlow<List<CodeCoreProject>>(emptyList())
    val projects: StateFlow<List<CodeCoreProject>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow<CodeCoreProject?>(null)
    val activeProject: StateFlow<CodeCoreProject?> = _activeProject.asStateFlow()

    init {
        loadProjectsFromDisk()
    }

    /**
     * Scans filesDir/projects/ and loads all persisted projects.
     * No hardcoded VideoPlayerPro or CryptoPulse.
     */
    fun loadProjectsFromDisk() {
        val loaded = mutableListOf<CodeCoreProject>()
        val dirs = projectsDir.listFiles { f -> f.isDirectory } ?: emptyArray()

        for (dir in dirs) {
            try {
                val metaFile = File(dir, "project_meta.json")
                if (metaFile.exists()) {
                    val metaJson = JSONObject(metaFile.readText())
                    val id = metaJson.optString("id", dir.name)
                    val name = metaJson.optString("name", dir.name)
                    val description = metaJson.optString("description", "")
                    val language = metaJson.optString("language", "Kotlin / Compose")
                    val created = metaJson.optLong("createdTimestamp", System.currentTimeMillis())
                    val modified = metaJson.optLong("lastModifiedTimestamp", System.currentTimeMillis())

                    val files = mutableListOf<ProjectFile>()
                    collectProjectFiles(dir, dir, files)

                    loaded.add(
                        CodeCoreProject(
                            id = id,
                            name = name,
                            description = description,
                            language = language,
                            files = files,
                            createdTimestamp = created,
                            lastModifiedTimestamp = modified
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load project from ${dir.name}", e)
            }
        }

        loaded.sortByDescending { it.lastModifiedTimestamp }
        _projects.value = loaded

        if (_activeProject.value == null && loaded.isNotEmpty()) {
            _activeProject.value = loaded.first()
        }
    }

    private fun collectProjectFiles(rootDir: File, currentDir: File, list: MutableList<ProjectFile>) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            if (file.name == "project_meta.json" || file.name == ".git") continue
            if (file.isDirectory) {
                collectProjectFiles(rootDir, file, list)
            } else {
                val relPath = file.relativeTo(rootDir).path.replace("\\", "/")
                val ext = file.extension
                val content = try {
                    file.readText()
                } catch (e: Exception) {
                    ""
                }
                list.add(
                    ProjectFile(
                        id = UUID.nameUUIDFromBytes(relPath.toByteArray()).toString(),
                        name = file.name,
                        path = relPath,
                        extension = ext,
                        content = content,
                        lastModified = file.lastModified()
                    )
                )
            }
        }
    }

    fun selectProject(id: String) {
        val proj = _projects.value.find { it.id == id }
        if (proj != null) {
            _activeProject.value = proj
        }
    }

    /**
     * Initializes project on disk with base scaffold from assets plus custom files.
     */
    fun createProject(
        name: String,
        description: String,
        generatedFiles: List<ProjectFile>
    ): CodeCoreProject {
        val projectId = UUID.randomUUID().toString()
        val projectDir = File(projectsDir, projectId).apply { mkdirs() }

        // 1. Copy base template scaffold from assets
        copyTemplateScaffold(projectDir)

        // 2. Write or overwrite generated application files
        for (file in generatedFiles) {
            val targetFile = File(projectDir, file.path)
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(file.content)
        }

        // 3. Write metadata
        val meta = JSONObject().apply {
            put("id", projectId)
            put("name", name)
            put("description", description)
            put("language", "Kotlin / Compose")
            put("createdTimestamp", System.currentTimeMillis())
            put("lastModifiedTimestamp", System.currentTimeMillis())
        }
        File(projectDir, "project_meta.json").writeText(meta.toString(2))

        val loadedFiles = mutableListOf<ProjectFile>()
        collectProjectFiles(projectDir, projectDir, loadedFiles)

        val newProject = CodeCoreProject(
            id = projectId,
            name = name,
            description = description,
            language = "Kotlin / Compose",
            files = loadedFiles
        )

        _projects.value = listOf(newProject) + _projects.value.filter { it.id != projectId }
        _activeProject.value = newProject
        return newProject
    }

    private fun copyTemplateScaffold(targetDir: File) {
        val assetManager = context.assets
        try {
            copyAssetFolder(assetManager, "project_template", targetDir)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying project template assets", e)
        }
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, assetPath: String, targetDir: File) {
        val files = assetManager.list(assetPath) ?: return
        if (files.isEmpty()) {
            // It's a file
            val targetFile = File(targetDir, assetPath.removePrefix("project_template/"))
            targetFile.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.name == "gradlew") {
                targetFile.setExecutable(true, false)
            }
        } else {
            for (subFile in files) {
                val subAssetPath = if (assetPath.isEmpty()) subFile else "$assetPath/$subFile"
                val subFiles = assetManager.list(subAssetPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    copyAssetFolder(assetManager, subAssetPath, targetDir)
                } else {
                    val rel = subAssetPath.removePrefix("project_template/")
                    val targetFile = File(targetDir, rel)
                    targetFile.parentFile?.mkdirs()
                    try {
                        assetManager.open(subAssetPath).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (targetFile.name == "gradlew") {
                            targetFile.setExecutable(true, false)
                        }
                    } catch (e: Exception) {
                        // Might be empty directory
                        targetFile.mkdirs()
                    }
                }
            }
        }
    }

    fun updateFileContent(projectId: String, fileId: String, newContent: String) {
        val project = _projects.value.find { it.id == projectId } ?: return
        val file = project.files.find { it.id == fileId } ?: return

        val projDir = File(projectsDir, projectId)
        val diskFile = File(projDir, file.path)
        diskFile.parentFile?.mkdirs()
        diskFile.writeText(newContent)

        val updatedFiles = project.files.map {
            if (it.id == fileId) it.copy(content = newContent, lastModified = System.currentTimeMillis()) else it
        }
        val updatedProject = project.copy(files = updatedFiles, lastModifiedTimestamp = System.currentTimeMillis())

        updateProjectMeta(projDir, updatedProject)

        _projects.value = _projects.value.map { if (it.id == projectId) updatedProject else it }
        if (_activeProject.value?.id == projectId) {
            _activeProject.value = updatedProject
        }
    }

    fun createNewFile(projectId: String, relativePath: String, initialContent: String = "") {
        val project = _projects.value.find { it.id == projectId } ?: return
        val sanitized = ProjectFileParser.sanitizePath(relativePath)
        if (!ProjectFileParser.isValidRelativePath(sanitized)) {
            Log.e(TAG, "Invalid path: $relativePath")
            return
        }

        val projDir = File(projectsDir, projectId)
        val diskFile = File(projDir, sanitized)
        diskFile.parentFile?.mkdirs()
        diskFile.writeText(initialContent)

        val newFile = ProjectFile(
            id = UUID.nameUUIDFromBytes(sanitized.toByteArray()).toString(),
            name = sanitized.substringAfterLast("/"),
            path = sanitized,
            extension = sanitized.substringAfterLast(".", ""),
            content = initialContent
        )

        val updatedProject = project.copy(
            files = project.files + newFile,
            lastModifiedTimestamp = System.currentTimeMillis()
        )
        updateProjectMeta(projDir, updatedProject)

        _projects.value = _projects.value.map { if (it.id == projectId) updatedProject else it }
        if (_activeProject.value?.id == projectId) {
            _activeProject.value = updatedProject
        }
    }

    fun renameFile(projectId: String, fileId: String, newPath: String) {
        val project = _projects.value.find { it.id == projectId } ?: return
        val file = project.files.find { it.id == fileId } ?: return
        val sanitized = ProjectFileParser.sanitizePath(newPath)
        if (!ProjectFileParser.isValidRelativePath(sanitized)) return

        val projDir = File(projectsDir, projectId)
        val oldFile = File(projDir, file.path)
        val newFile = File(projDir, sanitized)
        newFile.parentFile?.mkdirs()

        if (oldFile.exists()) {
            oldFile.renameTo(newFile)
        }

        val updatedFiles = project.files.map {
            if (it.id == fileId) {
                it.copy(
                    name = sanitized.substringAfterLast("/"),
                    path = sanitized,
                    extension = sanitized.substringAfterLast(".", "")
                )
            } else it
        }
        val updatedProject = project.copy(files = updatedFiles, lastModifiedTimestamp = System.currentTimeMillis())
        updateProjectMeta(projDir, updatedProject)

        _projects.value = _projects.value.map { if (it.id == projectId) updatedProject else it }
        if (_activeProject.value?.id == projectId) {
            _activeProject.value = updatedProject
        }
    }

    fun deleteFile(projectId: String, fileId: String) {
        val project = _projects.value.find { it.id == projectId } ?: return
        val file = project.files.find { it.id == fileId } ?: return

        val projDir = File(projectsDir, projectId)
        val diskFile = File(projDir, file.path)
        if (diskFile.exists()) {
            diskFile.delete()
        }

        val updatedFiles = project.files.filter { it.id != fileId }
        val updatedProject = project.copy(files = updatedFiles, lastModifiedTimestamp = System.currentTimeMillis())
        updateProjectMeta(projDir, updatedProject)

        _projects.value = _projects.value.map { if (it.id == projectId) updatedProject else it }
        if (_activeProject.value?.id == projectId) {
            _activeProject.value = updatedProject
        }
    }

    fun deleteProject(projectId: String) {
        val projDir = File(projectsDir, projectId)
        if (projDir.exists()) {
            projDir.deleteRecursively()
        }

        val remaining = _projects.value.filter { it.id != projectId }
        _projects.value = remaining
        if (_activeProject.value?.id == projectId) {
            _activeProject.value = remaining.firstOrNull()
        }
    }

    fun duplicateProject(projectId: String) {
        val project = _projects.value.find { it.id == projectId } ?: return
        val newId = UUID.randomUUID().toString()
        val newName = "${project.name} (Copy)"

        val sourceDir = File(projectsDir, projectId)
        val targetDir = File(projectsDir, newId)
        sourceDir.copyRecursively(targetDir, overwrite = true)

        val meta = JSONObject().apply {
            put("id", newId)
            put("name", newName)
            put("description", project.description)
            put("language", project.language)
            put("createdTimestamp", System.currentTimeMillis())
            put("lastModifiedTimestamp", System.currentTimeMillis())
        }
        File(targetDir, "project_meta.json").writeText(meta.toString(2))

        val files = mutableListOf<ProjectFile>()
        collectProjectFiles(targetDir, targetDir, files)

        val dup = project.copy(
            id = newId,
            name = newName,
            files = files,
            createdTimestamp = System.currentTimeMillis(),
            lastModifiedTimestamp = System.currentTimeMillis()
        )

        _projects.value = listOf(dup) + _projects.value
        _activeProject.value = dup
    }

    fun exportProjectZip(projectId: String, outputStream: OutputStream) {
        val projDir = File(projectsDir, projectId)
        if (!projDir.exists()) return

        ZipOutputStream(outputStream).use { zos ->
            val files = projDir.walkTopDown().filter { it.isFile && it.name != "project_meta.json" }
            for (file in files) {
                val relPath = file.relativeTo(projDir).path.replace("\\", "/")
                val entry = ZipEntry(relPath)
                zos.putNextEntry(entry)
                file.inputStream().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    fun importProjectZip(inputStream: InputStream, suggestedName: String): CodeCoreProject {
        val newId = UUID.randomUUID().toString()
        val projDir = File(projectsDir, newId).apply { mkdirs() }

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && ProjectFileParser.isValidRelativePath(entry.name)) {
                    val targetFile = File(projDir, entry.name)
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val name = if (suggestedName.isNotBlank()) suggestedName else "ImportedApp"
        val meta = JSONObject().apply {
            put("id", newId)
            put("name", name)
            put("description", "Импортирован из ZIP-архива")
            put("language", "Kotlin / Compose")
            put("createdTimestamp", System.currentTimeMillis())
            put("lastModifiedTimestamp", System.currentTimeMillis())
        }
        File(projDir, "project_meta.json").writeText(meta.toString(2))

        val files = mutableListOf<ProjectFile>()
        collectProjectFiles(projDir, projDir, files)

        val project = CodeCoreProject(
            id = newId,
            name = name,
            description = "Импортирован из ZIP-архива",
            language = "Kotlin / Compose",
            files = files
        )

        _projects.value = listOf(project) + _projects.value
        _activeProject.value = project
        return project
    }

    private fun updateProjectMeta(dir: File, project: CodeCoreProject) {
        val meta = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("description", project.description)
            put("language", project.language)
            put("createdTimestamp", project.createdTimestamp)
            put("lastModifiedTimestamp", project.lastModifiedTimestamp)
        }
        File(dir, "project_meta.json").writeText(meta.toString(2))
    }
}
