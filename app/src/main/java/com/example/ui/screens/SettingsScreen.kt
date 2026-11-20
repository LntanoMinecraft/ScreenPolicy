package com.example.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.model.AccentPalette
import com.example.model.AppLanguage
import com.example.model.ThemeMode
import com.example.ui.MainUiState
import com.example.ui.components.ExpressiveActionRow
import com.example.ui.components.ExpressiveSwitchRow
import com.example.ui.components.SectionHeader
import com.example.ui.components.expressiveGroupItemShape
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.previewColorsForPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onFixGameDashboardChange: (Boolean) -> Unit,
    onVerboseLoggingChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onPaletteChange: (AccentPalette) -> Unit,
    onExportUri: (Uri) -> Unit,
    onImportUri: (Uri) -> Unit,
    bottomContentPadding: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) onExportUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportUri(uri)
    }

    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Column(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 720.dp),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header_systemui") {
                SectionHeader(title = stringResource(R.string.settings_group_systemui))
            }
            item(key = "fix_game_dashboard") {
                ExpressiveSwitchRow(
                    title = stringResource(R.string.policy_game_dashboard_title),
                    subtitle = stringResource(R.string.policy_game_dashboard_desc),
                    leadingIcon = Icons.Outlined.SportsEsports,
                    checked = uiState.config.fixGameDashboardRecord,
                    onCheckedChange = onFixGameDashboardChange,
                    shape = expressiveGroupItemShape(0, 1),
                    tag = "switch_game_dashboard_fix",
                )
            }

            item(key = "header_debug") {
                SectionHeader(title = stringResource(R.string.settings_group_debug))
            }
            item(key = "verbose_log") {
                ExpressiveSwitchRow(
                    title = stringResource(R.string.settings_verbose_log_title),
                    subtitle = stringResource(R.string.settings_verbose_log_desc),
                    leadingIcon = Icons.Outlined.BugReport,
                    checked = uiState.config.verboseLogging,
                    onCheckedChange = onVerboseLoggingChange,
                    shape = expressiveGroupItemShape(0, 1),
                    tag = "switch_verbose_logging",
                )
            }

            item(key = "header_appearance") {
                SectionHeader(title = stringResource(R.string.settings_group_appearance))
            }
            item(key = "theme_mode") {
                ThemeModeCard(
                    selected = uiState.appearance.themeMode,
                    onSelect = onThemeModeChange,
                    shape = expressiveGroupItemShape(0, 4),
                )
            }
            item(key = "language") {
                LanguageCard(
                    selected = uiState.appLanguage,
                    onSelect = onLanguageChange,
                    shape = expressiveGroupItemShape(1, 4),
                )
            }
            item(key = "dynamic_color") {
                ExpressiveSwitchRow(
                    title = stringResource(R.string.settings_dynamic_color_title),
                    subtitle = stringResource(R.string.settings_dynamic_color_desc),
                    leadingIcon = Icons.Outlined.ColorLens,
                    checked = uiState.appearance.dynamicColor && dynamicSupported,
                    enabled = dynamicSupported,
                    onCheckedChange = onDynamicColorChange,
                    shape = expressiveGroupItemShape(2, 4),
                    tag = "switch_dynamic_color",
                )
            }
            item(key = "palette_selector") {
                PalettePickerCard(
                    selected = uiState.appearance.palette,
                    isDynamicActive = uiState.appearance.dynamicColor && dynamicSupported,
                    onSelect = onPaletteChange,
                    shape = expressiveGroupItemShape(3, 4),
                )
            }

            item(key = "header_backup") {
                SectionHeader(title = stringResource(R.string.settings_group_backup))
            }
            item(key = "export_config") {
                ExpressiveActionRow(
                    title = stringResource(R.string.settings_export_title),
                    subtitle = stringResource(R.string.settings_export_desc),
                    leadingIcon = Icons.Outlined.Upload,
                    onClick = { exportLauncher.launch("screenpolicy_allowlist.json") },
                    shape = expressiveGroupItemShape(0, 2),
                    tag = "action_export_json",
                )
            }
            item(key = "import_config") {
                ExpressiveActionRow(
                    title = stringResource(R.string.settings_import_title),
                    subtitle = stringResource(R.string.settings_import_desc),
                    leadingIcon = Icons.Outlined.Download,
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    shape = expressiveGroupItemShape(1, 2),
                    tag = "action_import_json",
                )
            }

            item(key = "footer") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_about_version_format,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_about_scope),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFamily,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    shape: Shape,
) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.DARK to stringResource(R.string.theme_dark),
    )

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_theme_mode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        modifier = Modifier.testTag("theme_mode_${mode.key}"),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageCard(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    shape: Shape,
) {
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.language_system),
        AppLanguage.ZH_CN to stringResource(R.string.language_zh),
        AppLanguage.EN to stringResource(R.string.language_en),
    )

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (language, label) ->
                    SegmentedButton(
                        selected = selected == language,
                        onClick = { onSelect(language) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        modifier = Modifier.testTag("language_${language.key}"),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PalettePickerCard(
    selected: AccentPalette,
    isDynamicActive: Boolean,
    onSelect: (AccentPalette) -> Unit,
    shape: Shape,
) {
    val items = listOf(
        AccentPalette.SLATE_TEAL to stringResource(R.string.palette_slate_teal),
        AccentPalette.OCEAN_BLUE to stringResource(R.string.palette_ocean_blue),
        AccentPalette.FOREST_GREEN to stringResource(R.string.palette_forest_green),
        AccentPalette.WARM_AMBER to stringResource(R.string.palette_warm_amber),
    )

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_palette_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items.forEach { (palette, label) ->
                    val isSelected = !isDynamicActive && selected == palette
                    val preview = previewColorsForPalette(palette)
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.03f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "palette_scale",
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .scale(scale)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clickable { onSelect(palette) }
                            .heightIn(min = 76.dp)
                            .padding(vertical = 10.dp, horizontal = 6.dp)
                            .testTag("palette_${palette.key}"),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(preview.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(preview.container),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
