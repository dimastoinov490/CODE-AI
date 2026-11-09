package com.codecore.ai.project

import com.codecore.ai.model.CodeCoreProject
import com.codecore.ai.model.ProjectFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ProjectRepository {

    private val sampleVideoPlayer = CodeCoreProject(
        id = "proj_videoplayer",
        name = "VideoPlayerPro",
        description = "Modern ExoPlayer with gesture controls, PiP and playlists",
        language = "Kotlin / Compose",
        files = listOf(
            ProjectFile(
                id = "f1",
                name = "MainActivity.kt",
                path = "src/main/java/com/example/videoplayer/MainActivity.kt",
                extension = "kt",
                content = """package com.example.videoplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.videoplayer.ui.VideoPlayerSurface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8B5CF6),
                    background = Color(0xFF0B0A12)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VideoPlayerSurface()
                }
            }
        }
    }
}"""
            ),
            ProjectFile(
                id = "f2",
                name = "VideoPlayerSurface.kt",
                path = "src/main/java/com/example/videoplayer/ui/VideoPlayerSurface.kt",
                extension = "kt",
                content = """package com.example.videoplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun VideoPlayerSurface() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // ExoPlayer TextureView & Gesture Controller Overlay
        Text(
            text = "Video Player Surface Ready (Gesture Controls Active)",
            color = Color(0xFFC4B5FD)
        )
    }
}"""
            ),
            ProjectFile(
                id = "f3",
                name = "AndroidManifest.xml",
                path = "src/main/AndroidManifest.xml",
                extension = "xml",
                content = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:label="VideoPlayerPro"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>"""
            ),
            ProjectFile(
                id = "f4",
                name = "build.gradle.kts",
                path = "build.gradle.kts",
                extension = "kts",
                content = """plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.videoplayer"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.videoplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}"""
            )
        )
    )

    private val sampleCryptoTracker = CodeCoreProject(
        id = "proj_crypto",
        name = "CryptoPulse",
        description = "Real-time crypto telemetry with Room database and offline caching",
        language = "Kotlin / Room",
        files = listOf(
            ProjectFile(
                id = "c1",
                name = "MainActivity.kt",
                path = "src/main/java/com/example/cryptopulse/MainActivity.kt",
                extension = "kt",
                content = """package com.example.cryptopulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Live crypto ticker
        }
    }
}"""
            )
        )
    )

    private val _projects = MutableStateFlow<List<CodeCoreProject>>(
        listOf(sampleVideoPlayer, sampleCryptoTracker)
    )
    val projects: StateFlow<List<CodeCoreProject>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow(sampleVideoPlayer)
    val activeProject: StateFlow<CodeCoreProject> = _activeProject.asStateFlow()

    fun selectProject(id: String) {
        val proj = _projects.value.find { it.id == id }
        if (proj != null) {
            _activeProject.value = proj
        }
    }

    fun addProject(project: CodeCoreProject) {
        _projects.value = listOf(project) + _projects.value
        _activeProject.value = project
    }

    fun updateFileContent(projectId: String, fileId: String, newContent: String) {
        _projects.value = _projects.value.map { proj ->
            if (proj.id == projectId) {
                val updatedFiles = proj.files.map { file ->
                    if (file.id == fileId) file.copy(content = newContent, lastModified = System.currentTimeMillis()) else file
                }
                val updatedProj = proj.copy(files = updatedFiles, lastModifiedTimestamp = System.currentTimeMillis())
                if (_activeProject.value.id == projectId) {
                    _activeProject.value = updatedProj
                }
                updatedProj
            } else {
                proj
            }
        }
    }

    fun createNewFile(projectId: String, fileName: String, initialContent: String = "") {
        _projects.value = _projects.value.map { proj ->
            if (proj.id == projectId) {
                val ext = fileName.substringAfterLast(".", "")
                val newFile = ProjectFile(
                    id = UUID.randomUUID().toString(),
                    name = fileName,
                    path = "src/main/$fileName",
                    extension = ext,
                    content = initialContent
                )
                val updatedProj = proj.copy(files = proj.files + newFile)
                if (_activeProject.value.id == projectId) {
                    _activeProject.value = updatedProj
                }
                updatedProj
            } else {
                proj
            }
        }
    }

    fun deleteFile(projectId: String, fileId: String) {
        _projects.value = _projects.value.map { proj ->
            if (proj.id == projectId) {
                val updatedProj = proj.copy(files = proj.files.filter { it.id != fileId })
                if (_activeProject.value.id == projectId) {
                    _activeProject.value = updatedProj
                }
                updatedProj
            } else {
                proj
            }
        }
    }

    fun deleteProject(projectId: String) {
        _projects.value = _projects.value.filter { it.id != projectId }
        if (_activeProject.value.id == projectId && _projects.value.isNotEmpty()) {
            _activeProject.value = _projects.value.first()
        }
    }

    fun duplicateProject(projectId: String) {
        val proj = _projects.value.find { it.id == projectId } ?: return
        val dup = proj.copy(
            id = UUID.randomUUID().toString(),
            name = "${proj.name} (Copy)",
            createdTimestamp = System.currentTimeMillis()
        )
        addProject(dup)
    }
}
