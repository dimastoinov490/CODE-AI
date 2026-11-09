package com.codecore.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codecore.ai.model.*
import com.codecore.ai.modelmanager.ModelManager
import com.codecore.ai.ui.components.GlassCard
import com.codecore.ai.ui.components.NeonBadge
import com.codecore.ai.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ModelManagerScreen(modelManager: ModelManager) {
    val coroutineScope = rememberCoroutineScope()
    val installedFiles by modelManager.installedFiles.collectAsState()
    val engineStatus by modelManager.engineStatus.collectAsState()
    val activeModel by modelManager.activeModel.collectAsState()
    val downloadProgressMap by modelManager.downloadProgressMap.collectAsState()
    val customModels by modelManager.customModels.collectAsState()

    val contextWindow by modelManager.contextWindow.collectAsState()
    val cpuThreads by modelManager.cpuThreads.collectAsState()
    val temperature by modelManager.temperature.collectAsState()
    val topP by modelManager.topP.collectAsState()
    val topK by modelManager.topK.collectAsState()
    val maxTokens by modelManager.maxTokens.collectAsState()
    val needsReload by modelManager.needsReload.collectAsState()
    val ramWarning by modelManager.ramWarning.collectAsState()
    val lastEngineError by modelManager.lastEngineError.collectAsState()

    val (availRam, totalRam) = remember { modelManager.getMemoryInfo() }

    // SAF Import Launcher for custom GGUF files
    val importGgufLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                modelManager.importCustomGguf(uri)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Шапка раздела
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "МОДЕЛИ AI (GGUF)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Локальные модели нейросетей для запуска на процессоре смартфона",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                OutlinedButton(
                    onClick = { importGgufLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrightViolet),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Импорт GGUF", fontSize = 11.sp)
                }
            }
        }

        // Предупреждение о перезагрузке
        if (needsReload && activeModel != null) {
            item {
                Surface(
                    color = SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusCyan),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Параметры изменены. Перезагрузите модель в RAM.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusCyan
                        )
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    modelManager.loadModelIntoRam(activeModel!!)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Перезагрузить", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Предупреждение о RAM / mmap
        if (ramWarning != null) {
            item {
                Surface(
                    color = SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusYellow),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = ramWarning!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusYellow,
                        modifier = Modifier.padding(10.dp),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Сообщение об ошибке движка
        if (lastEngineError != null) {
            item {
                Surface(
                    color = SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Ошибка: $lastEngineError",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusRed,
                        modifier = Modifier.padding(10.dp),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Карточка системной памяти
        item {
            GlassCard(borderColor = BorderSubtle) {
                Text(
                    text = "ПАРАМЕТРЫ ПАМЯТИ УСТРОЙСТВА",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Всего RAM: %.1f ГБ".format(totalRam), style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("Свободно RAM: %.1f ГБ".format(availRam), style = MaterialTheme.typography.bodySmall, color = StatusGreen)
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { ((totalRam - availRam) / totalRam).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = PrimaryViolet,
                    trackColor = SurfaceElevated
                )
            }
        }

        // Слайдеры параметров инференса (n_ctx, threads, temp, top_p, max_tokens)
        item {
            GlassCard(borderColor = BorderSubtle) {
                Text(
                    text = "НАСТРОЙКИ ИНФЕРЕНСА (LLAMA.CPP)",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Контекстное окно
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Контекстное окно (n_ctx)", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("$contextWindow токенов", style = MaterialTheme.typography.bodySmall, color = Lavender)
                }
                Slider(
                    value = contextWindow.toFloat(),
                    onValueChange = { modelManager.setContextWindow(it.toInt()) },
                    valueRange = 1024f..8192f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = BrightViolet, activeTrackColor = PrimaryViolet)
                )

                // Потоки CPU
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Потоки CPU (threads)", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("$cpuThreads потока", style = MaterialTheme.typography.bodySmall, color = Lavender)
                }
                Slider(
                    value = cpuThreads.toFloat(),
                    onValueChange = { modelManager.setCpuThreads(it.toInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = BrightViolet, activeTrackColor = PrimaryViolet)
                )

                // Температура
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Температура (temperature)", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("%.2f".format(temperature), style = MaterialTheme.typography.bodySmall, color = Lavender)
                }
                Slider(
                    value = temperature,
                    onValueChange = { modelManager.setTemperature(it) },
                    valueRange = 0.1f..1.2f,
                    colors = SliderDefaults.colors(thumbColor = BrightViolet, activeTrackColor = PrimaryViolet)
                )

                // Top-P
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Top-P выборка", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("%.2f".format(topP), style = MaterialTheme.typography.bodySmall, color = Lavender)
                }
                Slider(
                    value = topP,
                    onValueChange = { modelManager.setTopP(it) },
                    valueRange = 0.5f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = BrightViolet, activeTrackColor = PrimaryViolet)
                )

                // Max Tokens
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Максимум токенов", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("$maxTokens", style = MaterialTheme.typography.bodySmall, color = Lavender)
                }
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = { modelManager.setMaxTokens(it.toInt()) },
                    valueRange = 512f..4096f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = BrightViolet, activeTrackColor = PrimaryViolet)
                )
            }
        }

        // Каталог моделей
        item {
            Text(
                text = "КАТАЛОГ И ИМПОРТИРОВАННЫЕ МОДЕЛИ",
                style = MaterialTheme.typography.labelSmall,
                color = Lavender,
                fontWeight = FontWeight.Bold
            )
        }

        val allModels = modelManager.getAllCatalogModels()
        items(allModels) { model ->
            val isInstalled = installedFiles.containsKey(model.id)
            val isActiveInRam = activeModel?.id == model.id && engineStatus == EngineStatus.READY
            val isCurrentLoading = activeModel?.id == model.id && engineStatus == EngineStatus.LOADING_TO_RAM
            val progress = downloadProgressMap[model.id]

            GlassCard(
                borderColor = if (isActiveInRam) StatusGreen else BorderSubtle
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = model.developer,
                                style = MaterialTheme.typography.bodySmall,
                                color = Lavender,
                                fontSize = 11.sp
                            )
                        }

                        if (model.isRecommended) {
                            NeonBadge(text = "РЕКОМЕНДУЕМ", dotColor = StatusGreen)
                        } else if (model.isCustom) {
                            NeonBadge(text = "ПОЛЬЗОВАТЕЛЬСКАЯ", dotColor = StatusCyan)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = model.purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Размер: ${model.formattedSize}", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontSize = 10.sp)
                        Text("RAM: ${model.requiredRamGb} ГБ", style = MaterialTheme.typography.labelSmall, color = Lavender, fontSize = 10.sp)
                        Text("Квантование: ${model.quantization}", style = MaterialTheme.typography.labelSmall, color = BrightViolet, fontSize = 10.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Progress bar if downloading
                    if (progress != null) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { progress.progressPercent },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = BrightViolet,
                                trackColor = SurfaceElevated
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${(progress.progressPercent * 100).toInt()}% • ${progress.speedBytesPerSec / 1024} КБ/с",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Lavender,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "Осталось: ${progress.etaSeconds} сек",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { modelManager.cancelDownload(model.id) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
                                ) {
                                    Text("Отмена", fontSize = 11.sp)
                                }
                            }
                        }
                    } else if (!isInstalled && !model.isCustom) {
                        Button(
                            onClick = { modelManager.startRealDownload(model, coroutineScope) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Скачать GGUF (${model.formattedSize})", fontSize = 12.sp)
                        }
                    } else {
                        // Installed actions
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isActiveInRam) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            modelManager.loadModelIntoRam(model)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    enabled = !isCurrentLoading
                                ) {
                                    if (isCurrentLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = TextPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Загрузка в RAM…", fontSize = 12.sp)
                                    } else {
                                        Text("Загрузить в RAM", fontSize = 12.sp)
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { modelManager.unloadActiveModel() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Выгрузить из RAM", color = Lavender, fontSize = 12.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = { modelManager.deleteModelFile(model.id) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Удалить", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
