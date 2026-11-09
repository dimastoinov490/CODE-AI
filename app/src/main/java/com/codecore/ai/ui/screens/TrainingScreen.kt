package com.codecore.ai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codecore.ai.training.DatasetManager
import com.codecore.ai.ui.components.GlassCard
import com.codecore.ai.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TrainingScreen(datasetManager: DatasetManager) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var pairCount by remember { mutableIntStateOf(datasetManager.getPairCount()) }
    var fileSizeFormatted by remember { mutableStateOf(datasetManager.getFileSizeFormatted()) }
    var exportStatusText by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/jsonl")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val success = context.contentResolver.openOutputStream(uri)?.use { os ->
                    datasetManager.exportDataset(os)
                } ?: false

                exportStatusText = if (success) {
                    "Датасет успешно экспортирован!"
                } else {
                    "Ошибка экспорта или датасет пуст"
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "ОБУЧЕНИЕ МОДЕЛЕЙ (GOOGLE COLAB)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Дообучение весов через LoRA / QLoRA на удалённых облачных GPU",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // Кнопка обучения в Google Colab
        item {
            GlassCard(borderColor = BorderGlow) {
                Text(
                    text = "УДАЛЁННОЕ ОБУЧЕНИЕ В GOOGLE COLAB",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Тяжёлое обучение нейросетей требует GPU (T4/V100/A100) и выполняется в Google Colab по проверенному скрипту notebooks/train_codecore.ipynb. Полученные веса конвертируются в GGUF Q4_K_M для CodeCore AI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val colabIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/dimastoinov490/CODE-AI/blob/main/notebooks/train_codecore.ipynb")
                        )
                        context.startActivity(colabIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_colab_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Открыть ноутбук для Colab",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Честный обучающий набор данных
        item {
            GlassCard(borderColor = BorderSubtle) {
                Text(
                    text = "СБОР ОБУЧАЮЩЕГО НАБОРА ДАННЫХ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lavender,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "dataset_android_codecore.jsonl",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = if (pairCount > 0) {
                        "Накоплено пар (промпт → код): $pairCount ($fileSizeFormatted)"
                    } else {
                        "Пока нет сохранённых пар (0 пар). Успешные генерации и прошедшие сборку проекты сохраняются сюда."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pairCount > 0) StatusGreen else TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        if (pairCount > 0) {
                            exportLauncher.launch("dataset_android_codecore.jsonl")
                        } else {
                            exportStatusText = "Датасет пока пуст. Создайте и соберите проект, чтобы зафиксировать пару."
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrightViolet),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Экспорт датасета (.jsonl)", fontSize = 12.sp)
                }

                if (exportStatusText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = exportStatusText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Lavender,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Справка
        item {
            GlassCard(borderColor = BorderSubtle) {
                Text(
                    text = "ТЕХНИЧЕСКИЕ ДЕТАЛИ",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrightViolet,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• LoRA (Low-Rank Adaptation): дообучает адаптеры без разрушения базовых способностей модели.\n• Конвертация GGUF: скрипт convert_hf_to_gguf.py объединяет веса и производит квантование в Q4_K_M.\n• Импорт в приложение: используйте кнопку «Импорт GGUF» во вкладке «Модели AI».",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
