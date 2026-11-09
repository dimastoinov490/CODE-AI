package com.codecore.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codecore.ai.model.CodeCoreProject
import com.codecore.ai.model.ProjectFile
import com.codecore.ai.ui.components.GlassCard
import com.codecore.ai.ui.theme.*
import com.codecore.ai.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: MainViewModel,
    project: CodeCoreProject?,
    onNavigateToGitHub: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    if (project == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нет активного проекта. Выберите или создайте проект на главной.", color = TextMuted)
        }
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedFileId by remember(project.id) {
        mutableStateOf(project.files.firstOrNull()?.id ?: "")
    }
    val activeFile = project.files.find { it.id == selectedFileId } ?: project.files.firstOrNull()

    var editableCode by remember(activeFile?.id) {
        mutableStateOf(activeFile?.content ?: "")
    }

    var hasUnsavedChanges by remember { mutableStateOf(false) }

    // Debounced autosave
    LaunchedEffect(editableCode) {
        if (activeFile != null && editableCode != activeFile.content) {
            hasUnsavedChanges = true
            delay(700)
            viewModel.projectRepository.updateFileContent(project.id, activeFile.id, editableCode)
            hasUnsavedChanges = false
        }
    }

    var surgicalPrompt by remember { mutableStateOf("") }
    var isSurgicalLoading by remember { mutableStateOf(false) }
    var proposedCode by remember { mutableStateOf<String?>(null) }

    var isNewFileDialogVisible by remember { mutableStateOf(false) }
    var newFilePathInput by remember { mutableStateOf("") }

    var isRenameDialogVisible by remember { mutableStateOf(false) }
    var renamePathInput by remember { mutableStateOf("") }

    var isDeleteFileDialogVisible by remember { mutableStateOf(false) }

    // SAF ZIP Export
    val exportZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        viewModel.projectRepository.exportProjectZip(project.id, os)
                    }
                    viewModel.showMessage("Проект экспортирован в ZIP")
                } catch (e: Exception) {
                    viewModel.showMessage("Ошибка экспорта ZIP: ${e.message}")
                }
            }
        }
    }

    // SAF ZIP Import
    val importZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        val imported = viewModel.projectRepository.importProjectZip(ins, "ImportedApp")
                        viewModel.showMessage("Проект '${imported.name}' импортирован!")
                    }
                } catch (e: Exception) {
                    viewModel.showMessage("Ошибка импорта ZIP: ${e.message}")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        // Шапка рабочей области
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (hasUnsavedChanges) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("●", color = StatusCyan, fontSize = 10.sp)
                    }
                }
                Text(
                    text = "Файл: ${activeFile?.path ?: "нет файлов"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    maxLines = 1
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { isNewFileDialogVisible = true },
                    modifier = Modifier.size(34.dp).background(SurfaceElevated, CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Новый файл", tint = Lavender, modifier = Modifier.size(16.dp))
                }
                if (activeFile != null) {
                    IconButton(
                        onClick = {
                            renamePathInput = activeFile.path
                            isRenameDialogVisible = true
                        },
                        modifier = Modifier.size(34.dp).background(SurfaceElevated, CircleShape)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Переименовать", tint = Lavender, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { isDeleteFileDialogVisible = true },
                        modifier = Modifier.size(34.dp).background(SurfaceElevated, CircleShape)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить файл", tint = StatusRed, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(
                    onClick = { exportZipLauncher.launch("${project.name}.zip") },
                    modifier = Modifier.size(34.dp).background(SurfaceElevated, CircleShape)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Экспорт ZIP", tint = StatusGreen, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = onNavigateToGitHub,
                    modifier = Modifier.size(34.dp).background(SurfaceElevated, CircleShape)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "GitHub CI", tint = BrightViolet, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Вкладки файлов
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(project.files) { file ->
                val isSelected = file.id == selectedFileId
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) PrimaryViolet else SurfaceElevated,
                    modifier = Modifier
                        .clickable {
                            if (activeFile != null && editableCode != activeFile.content) {
                                viewModel.projectRepository.updateFileContent(project.id, activeFile.id, editableCode)
                            }
                            selectedFileId = file.id
                            editableCode = file.content
                        }
                        .testTag("file_tab_${file.name}")
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) TextPrimary else Lavender,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Редактор исходного кода
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            borderColor = BorderSubtle
        ) {
            OutlinedTextField(
                value = editableCode,
                onValueChange = { editableCode = it },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("code_editor_field"),
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    color = TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = BrightViolet
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Точечные команды AI (Surgical Edit)
        GlassCard(borderColor = BorderGlow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = surgicalPrompt,
                    onValueChange = { surgicalPrompt = it },
                    placeholder = {
                        Text(
                            text = "Команда AI: исправь функцию, добавь жест, замени цвет или проверь импорты…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("surgical_ai_input"),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryViolet,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    maxLines = 2,
                    enabled = !isSurgicalLoading
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (surgicalPrompt.isNotBlank() && activeFile != null) {
                            isSurgicalLoading = true
                            coroutineScope.launch {
                                val res = viewModel.performSurgicalEdit(
                                    filePath = activeFile.path,
                                    currentCode = editableCode,
                                    instruction = surgicalPrompt
                                )
                                isSurgicalLoading = false
                                if (res.isSuccess) {
                                    proposedCode = res.getOrNull()
                                } else {
                                    viewModel.showMessage("Ошибка: ${res.exceptionOrNull()?.message}")
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("surgical_ai_submit"),
                    enabled = !isSurgicalLoading && surgicalPrompt.isNotBlank()
                ) {
                    if (isSurgicalLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Done, contentDescription = "Применить правку", tint = TextPrimary)
                    }
                }
            }
        }
    }

    // Предпросмотр изменений "Было / Стало"
    if (proposedCode != null && activeFile != null) {
        AlertDialog(
            onDismissRequest = { proposedCode = null },
            title = { Text("Предпросмотр точечной правки AI", color = TextPrimary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text("Предложенный код:", style = MaterialTheme.typography.labelSmall, color = BrightViolet)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = proposedCode!!,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = TextPrimary),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        editableCode = proposedCode!!
                        viewModel.projectRepository.updateFileContent(project.id, activeFile.id, proposedCode!!)
                        proposedCode = null
                        surgicalPrompt = ""
                        viewModel.showMessage("Правка успешно применена!")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusGreen)
                ) {
                    Text("Принять")
                }
            },
            dismissButton = {
                TextButton(onClick = { proposedCode = null }) {
                    Text("Отклонить", color = StatusRed)
                }
            },
            containerColor = SurfaceElevated
        )
    }

    // Диалог создания файла
    if (isNewFileDialogVisible) {
        AlertDialog(
            onDismissRequest = { isNewFileDialogVisible = false },
            title = { Text("Создать новый файл", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newFilePathInput,
                    onValueChange = { newFilePathInput = it },
                    placeholder = { Text("например: app/src/main/res/values/strings.xml или ui/Screen.kt") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFilePathInput.isNotBlank()) {
                            viewModel.projectRepository.createNewFile(project.id, newFilePathInput.trim())
                            newFilePathInput = ""
                            isNewFileDialogVisible = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet)
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { isNewFileDialogVisible = false }) {
                    Text("Отмена", color = Lavender)
                }
            },
            containerColor = SurfaceElevated
        )
    }

    // Диалог переименования файла
    if (isRenameDialogVisible && activeFile != null) {
        AlertDialog(
            onDismissRequest = { isRenameDialogVisible = false },
            title = { Text("Переименовать файл", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renamePathInput,
                    onValueChange = { renamePathInput = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renamePathInput.isNotBlank()) {
                            viewModel.projectRepository.renameFile(project.id, activeFile.id, renamePathInput.trim())
                            isRenameDialogVisible = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet)
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { isRenameDialogVisible = false }) {
                    Text("Отмена", color = Lavender)
                }
            },
            containerColor = SurfaceElevated
        )
    }

    // Диалог удаления файла
    if (isDeleteFileDialogVisible && activeFile != null) {
        AlertDialog(
            onDismissRequest = { isDeleteFileDialogVisible = false },
            title = { Text("Удалить файл?", color = TextPrimary) },
            text = { Text("Вы уверены, что хотите удалить файл '${activeFile.name}'?", color = TextMuted) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.projectRepository.deleteFile(project.id, activeFile.id)
                        isDeleteFileDialogVisible = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { isDeleteFileDialogVisible = false }) {
                    Text("Отмена", color = Lavender)
                }
            },
            containerColor = SurfaceElevated
        )
    }
}
