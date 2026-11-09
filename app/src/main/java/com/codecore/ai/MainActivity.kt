package com.codecore.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codecore.ai.ui.screens.*
import com.codecore.ai.ui.theme.*
import com.codecore.ai.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CodeCoreAITheme {
                CodeCoreRootApp(viewModel = viewModel)
            }
        }
    }
}

enum class CodeCoreNavTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Главная", Icons.Default.Home),
    WORKSPACE("Редактор", Icons.Default.Edit),
    MODELS("Модели AI", Icons.Default.Settings),
    TRAINING("Обучение", Icons.Default.Star),
    GITHUB("GitHub CI", Icons.Default.Refresh),
    DIAGNOSTICS("Диагностика", Icons.Default.Warning)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeCoreRootApp(viewModel: MainViewModel) {
    var currentTab by remember { mutableStateOf(CodeCoreNavTab.DASHBOARD) }
    val activeProject by viewModel.projectRepository.activeProject.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val userMsg by viewModel.userMessage.collectAsState()

    LaunchedEffect(userMsg) {
        userMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    // Hardware back button returns to Dashboard
    BackHandler(enabled = currentTab != CodeCoreNavTab.DASHBOARD) {
        currentTab = CodeCoreNavTab.DASHBOARD
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BackgroundDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(listOf(BorderSubtle, BorderGlow, BorderSubtle)),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                CodeCoreNavTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) BrightViolet else TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) Lavender else TextMuted,
                                maxLines = 1,
                                fontSize = 10.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = SurfaceElevated
                        ),
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(BackgroundDark, SurfaceDark, BackgroundDark)
                    )
                )
        ) {
            when (currentTab) {
                CodeCoreNavTab.DASHBOARD -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToModels = { currentTab = CodeCoreNavTab.MODELS },
                    onOpenProject = { proj ->
                        viewModel.projectRepository.selectProject(proj.id)
                        currentTab = CodeCoreNavTab.WORKSPACE
                    }
                )
                CodeCoreNavTab.WORKSPACE -> WorkspaceScreen(
                    viewModel = viewModel,
                    project = activeProject,
                    onNavigateToGitHub = { currentTab = CodeCoreNavTab.GITHUB },
                    onNavigateToDiagnostics = { currentTab = CodeCoreNavTab.DIAGNOSTICS }
                )
                CodeCoreNavTab.MODELS -> ModelManagerScreen(
                    modelManager = viewModel.modelManager
                )
                CodeCoreNavTab.TRAINING -> TrainingScreen(
                    datasetManager = viewModel.datasetManager
                )
                CodeCoreNavTab.GITHUB -> GitHubScreen(
                    viewModel = viewModel,
                    project = activeProject
                )
                CodeCoreNavTab.DIAGNOSTICS -> DiagnosticsScreen(
                    viewModel = viewModel
                )
            }
        }
    }
}
