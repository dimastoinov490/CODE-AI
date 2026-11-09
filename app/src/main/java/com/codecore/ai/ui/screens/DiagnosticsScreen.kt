package com.codecore.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codecore.ai.model.DiagnosticItem
import com.codecore.ai.ui.components.GlassCard
import com.codecore.ai.ui.theme.*
import com.codecore.ai.ui.viewmodel.MainViewModel

@Composable
fun DiagnosticsScreen(viewModel: MainViewModel) {
    val checks by viewModel.diagnosticChecks.collectAsState()
    val isChecking by viewModel.isCheckingDiagnostics.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ЧЕСТНАЯ ДИАГНОСТИКА СИСТЕМЫ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Реальные проверки оборудования, NDK, моделей, сети и токенов",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Button(
                    onClick = { viewModel.runDiagnostics() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    enabled = !isChecking,
                    modifier = Modifier.testTag("run_diagnostics_button")
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = TextPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("Проверить", fontSize = 12.sp)
                }
            }
        }

        items(checks) { check ->
            GlassCard(
                borderColor = if (check.passed) BorderSubtle else StatusRed
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = check.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = check.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        if (!check.passed && check.recommendation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Рекомендация: ${check.recommendation}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusYellow,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (check.passed) Icons.Default.Check else Icons.Default.Warning,
                        contentDescription = if (check.passed) "Пройдено" else "Внимание",
                        tint = if (check.passed) StatusGreen else StatusRed
                    )
                }
            }
        }
    }
}
