package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ScreenPolicyTheme

private enum class MainTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    APPS("apps", R.string.nav_apps, Icons.Filled.Apps, Icons.Outlined.Apps),
    LOGS("logs", R.string.nav_logs, Icons.Filled.Terminal, Icons.Outlined.Terminal),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ScreenPolicyTheme(appearance = uiState.appearance) {
                ScreenPolicyRoot(
                    viewModel = viewModel,
                    uiState = uiState,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatusAndLogs()
    }
}

@Composable
private fun ScreenPolicyRoot(
    viewModel: MainViewModel,
    uiState: MainUiState,
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.APPS) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    BackHandler(enabled = currentTab != MainTab.APPS) {
        currentTab = MainTab.APPS
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp
        val bottomContentPadding = if (useNavRail) 32.dp else 112.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets.statusBars,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = if (useNavRail) 12.dp else 88.dp),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useNavRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.navigationBarsPadding(),
                        ) {
                            MainTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes),
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    modifier = Modifier.testTag("nav_rail_${tab.route}"),
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = {
                                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                val slideSpring = spring<IntOffset>(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                                val fadeSpring = spring<Float>(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                )
                                (slideInHorizontally(animationSpec = slideSpring) { it / 14 * direction } +
                                    fadeIn(animationSpec = fadeSpring) +
                                    scaleIn(
                                        initialScale = 0.985f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )) togetherWith
                                    (slideOutHorizontally(animationSpec = slideSpring) { -it / 18 * direction } +
                                        fadeOut(animationSpec = fadeSpring))
                            },
                            label = "tab_expressive_transition",
                        ) { tab ->
                            when (tab) {
                                MainTab.APPS -> AppsScreen(
                                    uiState = uiState,
                                    loadIcon = viewModel::loadAppIcon,
                                    onSearchQueryChange = viewModel::setSearchQuery,
                                    onFilterChange = viewModel::setAppFilter,
                                    onToggleShowSystemApps = viewModel::toggleShowSystemApps,
                                    onUpdateAppPolicy = viewModel::updateAppPolicy,
                                    onAddManualPackage = viewModel::addManualPackage,
                                    bottomContentPadding = bottomContentPadding,
                                )

                                MainTab.LOGS -> LogsScreen(
                                    uiState = uiState,
                                    onOnlyErrorsChange = viewModel::setLogsOnlyErrors,
                                    onRefresh = viewModel::refreshStatusAndLogs,
                                    onClear = viewModel::clearLogs,
                                    formatLogs = viewModel::formatLogsForClipboard,
                                    onCopied = viewModel::notifyCopied,
                                    bottomContentPadding = bottomContentPadding,
                                )

                                MainTab.SETTINGS -> SettingsScreen(
                                    uiState = uiState,
                                    onFixGameDashboardChange = viewModel::setFixGameDashboardRecord,
                                    onVerboseLoggingChange = viewModel::setVerboseLogging,
                                    onThemeModeChange = viewModel::setThemeMode,
                                    onLanguageChange = viewModel::setAppLanguage,
                                    onDynamicColorChange = viewModel::setDynamicColor,
                                    onPaletteChange = viewModel::setPalette,
                                    onExportUri = viewModel::exportConfig,
                                    onImportUri = viewModel::importConfig,
                                    bottomContentPadding = bottomContentPadding,
                                )
                            }
                        }
                    }
                }

                if (!useNavRail) {
                    FloatingExpressiveNavBar(
                        currentTab = currentTab,
                        onSelectTab = { currentTab = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Material Design 3 Expressive Floating Pill Navigation Bar.
 * Floats cleanly above the system gesture bar with translucent tonal surface,
 * soft elevation, and spring-driven active pill indicator & icon micro-scale.
 */
@Composable
private fun FloatingExpressiveNavBar(
    currentTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        modifier = modifier.testTag("floating_nav_bar"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { tab ->
                val selected = currentTab == tab
                val indicatorColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "nav_pill_bg_${tab.route}",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "nav_pill_fg_${tab.route}",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "nav_icon_scale_${tab.route}",
                )

                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(indicatorColor)
                        .clickable(
                            role = Role.Tab,
                            onClick = { onSelectTab(tab) },
                        )
                        .padding(
                            horizontal = if (selected) 18.dp else 14.dp,
                            vertical = 10.dp,
                        )
                        .testTag("nav_tab_${tab.route}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = stringResource(tab.labelRes),
                        tint = contentColor,
                        modifier = Modifier
                            .size(22.dp)
                            .scale(iconScale),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
