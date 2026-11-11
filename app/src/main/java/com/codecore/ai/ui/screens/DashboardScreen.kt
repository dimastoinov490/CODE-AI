package com.codecore.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codecore.ai.model.*
import com.codecore.ai.ui.components.CodeCoreLogo
import com.codecore.ai.ui.components.GlassCard
import com.codecore.ai.ui.components.NeonBadge
import com.codecore.ai.ui.theme.*
import com.codecore.ai.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToModels: () -> Unit,
    onOpenProject: (CodeCoreProject) -> Unit
) {
    val selectedMode by viewModel.selectedMode.collectAsState()
    val promptInput by viewModel.promptInput.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val currentStep by viewModel.currentGenerationStep.collectAsState()
    val (availRam, totalRam) = viewModel.ramTelemetry.collectAsState().value

    val engineStatus by viewModel.modelManager.engineStatus.collectAsState()
    val activeModel by viewModel.modelManager.activeModel.collectAsState()
    val installedFiles by viewModel.modelManager.installedFiles.collectAsState()
    val projects by viewModel.projectRepository.projects.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        // Логотип и бейдж режима
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CodeCoreLogo()
                NeonBadge(
                    text = selectedMode.badge,
                    dotColor = when (selectedMode) {
                        AIMode.LOCAL_ONLY -> StatusGreen
                        AIMode.HYBRID -> StatusCyan
                        AIMode.CLOUD_ONLY -> BrightViolet
                    }
                )
            }
        }

        // Переключатель режимов
        item {
            GlassCard(borderColor = BorderGlow) {
                Text(
                    text = "РЕЖИМ РАБОТЫ AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AIMode.values().forEach { mode ->
                        val isSelected = selectedMode == mode
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) SurfaceElevated else Color.Transparent,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, PrimaryViolet) else null,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.setAIMode(mode) }
                                .testTag("mode_${mode.name.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = mode.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) TextPrimary else TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = selectedMode.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        // Статус движка и RAM
        item {
            GlassCard(borderColor = BorderSubtle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ЛОКАЛЬНЫЙ ДВИЖОК GGUF",
                            style = MaterialTheme.typography.labelSmall,
                            color = Lavender,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (engineStatus) {
                                EngineStatus.NOT_INSTALLED -> "Модель не скачана"
                                EngineStatus.DOWNLOADING -> "Скачивание модели…"
                                EngineStatus.INSTALLED -> "Модель на диске (выгружена из RAM)"
                                EngineStatus.LOADING_TO_RAM -> "Загрузка весов в RAM…"
                                EngineStatus.READY -> "Готова: ${activeModel?.name ?: ""}"
                                EngineStatus.ERROR -> "Ошибка инициализации"
                                EngineStatus.LOW_MEMORY -> "Недостаточно RAM"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = when (engineStatus) {
                                EngineStatus.READY -> StatusGreen
                                EngineStatus.LOADING_TO_RAM, EngineStatus.DOWNLOADING -> StatusCyan
                                EngineStatus.ERROR, EngineStatus.LOW_MEMORY -> StatusRed
                                else -> TextMuted
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "RAM: %.1f ГБ свободно / %.1f ГБ всего".format(availRam, totalRam),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    if (engineStatus != EngineStatus.READY) {
                        Button(
                            onClick = onNavigateToModels,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("setup_model_button")
                        ) {
                            Text("Модели", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Генератор проектов
        item {
            GlassCard(borderColor = BorderGlow) {
                Text(
                    text = "АВТОНОМНЫЙ ГЕНЕРАТОР ПРОЕКТОВ",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { viewModel.setPromptInput(it) },
                    placeholder = {
                        Text(
                            text = "Опишите приложение (например: Блокнот заметок с локальным поиском, тегами, Compose Material 3 и тёмной темой)…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .testTag("project_prompt_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryViolet,
                        unfocusedBorderColor = BorderSubtle,
                        cursorColor = BrightViolet
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isGenerating) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { currentStep?.progress ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = BrightViolet,
                            trackColor = SurfaceElevated
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentStep?.title ?: "Генерация…",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = currentStep?.detail ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Lavender,
                                    fontSize = 11.sp
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.stopGeneration() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("stop_generation_button")
                            ) {
                                Text("Остановить")
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.startProjectGeneration { newProject ->
                                onOpenProject(newProject)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("generate_project_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Сгенерировать проект",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Недавние сохранённые проекты на диске
        item {
            Text(
                text = "ПРОЕКТЫ НА ДИСКЕ (${projects.size})",
                style = MaterialTheme.typography.labelSmall,
                color = Lavender,
                fontWeight = FontWeight.Bold
            )
        }

        if (projects.isEmpty()) {
            item {
                GlassCard(borderColor = BorderSubtle) {
                    Text(
                        text = "Нет сохранённых проектов. Введите задание выше для автономной генерации.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        } else {
            items(projects) { project ->
                GlassCard(
                    borderColor = BorderSubtle,
                    modifier = Modifier.clickable { onOpenProject(project) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = project.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                maxLines = 2,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Файлов: ${project.files.size} | ${project.language}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrightViolet,
                                fontSize = 10.sp
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    viewModel.projectRepository.duplicateProject(project.id)
                                    viewModel.showMessage("Проект дублирован")
                                },
                                modifier = Modifier.testTag("duplicate_project_${project.id}")
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = "Дублировать", tint = Lavender, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onOpenProject(project) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Открыть", tint = Lavender)
                            }
                        }
                    }
                }
            }
        }
    }
}
