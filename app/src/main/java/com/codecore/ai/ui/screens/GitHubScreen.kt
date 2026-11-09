package com.codecore.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codecore.ai.model.CodeCoreProject
import com.codecore.ai.ui.components.GlassCard
import com.codecore.ai.ui.components.NeonBadge
import com.codecore.ai.ui.theme.*
import com.codecore.ai.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun GitHubScreen(
    viewModel: MainViewModel,
    project: CodeCoreProject?
) {
    val coroutineScope = rememberCoroutineScope()
    val githubToken by viewModel.githubToken.collectAsState()
    val githubUsername by viewModel.githubUsername.collectAsState()
    val githubScopes by viewModel.githubScopes.collectAsState()
    val tokenMessage by viewModel.tokenCheckMessage.collectAsState()
    val isChecking by viewModel.isTokenChecking.collectAsState()

    val currentStatus by viewModel.selfHealingEngine.currentStatus.collectAsState()
    val healingHistory by viewModel.selfHealingEngine.healingHistory.collectAsState()
    val downloadedApk by viewModel.selfHealingEngine.downloadedApkFile.collectAsState()

    var isTokenVisible by remember { mutableStateOf(false) }
    var commitMessageInput by remember { mutableStateOf("feat: autonomous build trigger from CodeCore AI") }
    var isRunningCycle by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Шапка
        item {
            Text(
                text = "ИНТЕГРАЦИЯ GITHUB И CI/CD",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Удалённая сборка и автономный цикл самоисправления компиляции",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // Предупреждение о безопасности токена и минутах
        item {
            Surface(
                color = SurfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = StatusCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Безопасность и квота GitHub", style = MaterialTheme.typography.labelSmall, color = StatusCyan, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Токен шифруется аппаратно через Android KeyStore и никогда не попадает в git/логи.\n• Приватные репозитории расходуют бесплатные минуты GitHub Actions (2000 мин/мес).\n• Запись разрешена исключительно в репозитории с префиксом cc-, созданные приложением.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Ввод и проверка токена
        item {
            GlassCard(borderColor = if (githubScopes.contains("repo") && githubScopes.contains("workflow")) StatusGreen else BorderGlow) {
                Text(
                    text = "PERSONAL ACCESS TOKEN (CLASSIC)",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { viewModel.updateGitHubToken(it) },
                    label = { Text("GitHub PAT (ghp_...)") },
                    modifier = Modifier.fillMaxWidth().testTag("github_token_input"),
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                            Icon(
                                if (isTokenVisible) Icons.Default.Done else Icons.Default.Lock,
                                contentDescription = "Показать токен",
                                tint = Lavender
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryViolet,
                        unfocusedBorderColor = BorderSubtle
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.checkGitHubToken() },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isChecking,
                        modifier = Modifier.testTag("verify_token_button")
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = TextPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Проверить токен", fontSize = 12.sp)
                    }

                    if (githubUsername.isNotBlank()) {
                        NeonBadge(
                            text = "@$githubUsername",
                            dotColor = StatusGreen
                        )
                    }
                }

                if (tokenMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = tokenMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (githubScopes.contains("repo")) StatusGreen else StatusYellow,
                        fontSize = 11.sp
                    )
                }

                if (githubScopes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Права токена: ${githubScopes.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Управление сборкой текущего проекта
        item {
            GlassCard(borderColor = BorderSubtle) {
                Text(
                    text = "СБОРКА АКТИВНОГО ПРОЕКТА",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Проект: ${project?.name ?: "Не выбран"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = commitMessageInput,
                    onValueChange = { commitMessageInput = it },
                    label = { Text("Сообщение коммита (используется при пуше)") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isRunningCycle) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = BrightViolet,
                            trackColor = SurfaceElevated
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = Lavender,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.selfHealingEngine.cancelCycle()
                                isRunningCycle = false
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Прервать цикл")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (project == null) {
                                viewModel.showMessage("Выберите проект в редакторе")
                                return@Button
                            }
                            if (githubToken.isBlank()) {
                                viewModel.showMessage("Сначала укажите GitHub Personal Access Token")
                                return@Button
                            }

                            isRunningCycle = true
                            coroutineScope.launch {
                                // Create cc- repo if needed
                                val slug = project.name.lowercase().replace("[^a-z0-9]".toRegex(), "-")
                                val repoRes = viewModel.gitHubClient.createRepository(
                                    token = githubToken,
                                    projectSlug = slug,
                                    projectId = project.id,
                                    isPrivate = true
                                )

                                if (repoRes.isFailure) {
                                    isRunningCycle = false
                                    viewModel.showMessage("Ошибка репозитория: ${repoRes.exceptionOrNull()?.message}")
                                    return@launch
                                }

                                val repoInfo = repoRes.getOrNull()!!
                                val cycleRes = viewModel.selfHealingEngine.runAutonomousCycle(
                                    token = githubToken,
                                    owner = repoInfo.owner,
                                    repo = repoInfo.repo,
                                    project = project,
                                    mode = viewModel.selectedMode.value,
                                    localModelHandle = viewModel.modelManager.getActiveModelHandle(),
                                    commitMessage = commitMessageInput
                                ) { status ->
                                    // Status update
                                }
                                isRunningCycle = false
                                if (cycleRes.isSuccess) {
                                    viewModel.showMessage("Сборка успешна! APK загружен.")
                                } else {
                                    viewModel.showMessage("Цикл завершился: ${cycleRes.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("start_ci_cycle_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Запустить автономный цикл сборки и исправления", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Готовый скачанный APK
        if (downloadedApk != null) {
            item {
                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusGreen)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = StatusGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ГОТОВЫЙ APK СБОРКИ", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Файл: ${downloadedApk!!.name} (${downloadedApk!!.length() / 1024} КБ)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lavender
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.installApk(downloadedApk!!) },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("install_apk_button")
                        ) {
                            Text("Установить app-debug.apk", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // История итераций самоисправления
        if (healingHistory.isNotEmpty()) {
            item {
                Text(
                    text = "ИСТОРИЯ САМОИСПРАВЛЕНИЯ (${healingHistory.size} ИТЕРАЦИЙ)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lavender,
                    fontWeight = FontWeight.Bold
                )
            }

            items(healingHistory) { step ->
                GlassCard(
                    borderColor = when (step.status) {
                        "PASSED" -> StatusGreen
                        "FAILED_REPEATED" -> StatusRed
                        else -> BorderSubtle
                    }
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Итерация #${step.iteration}", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                            NeonBadge(
                                text = step.status,
                                dotColor = when (step.status) {
                                    "PASSED" -> StatusGreen
                                    "FAILED_REPEATED" -> StatusRed
                                    else -> StatusCyan
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = step.triggerErrorSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 4
                        )
                        if (step.commitSha != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Коммит: ${step.commitSha.take(8)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrightViolet,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
