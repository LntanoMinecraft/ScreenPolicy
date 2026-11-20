package com.example.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.example.ui.theme.TabularNumericFeature
import com.example.ui.theme.previewColorsForPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onFixGameDashboardChange: (Boolean) -> Unit,
    onAutoConfirmGameDashboardChange: (Boolean) -> Unit,
    onVerboseLoggingChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onPaletteChange: (AccentPalette) -> Unit,
    onExportUri: (Uri) -> Unit,
    onImportUri: (Uri) -> Unit,
    onOpenAbout: () -> Unit,
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
                    shape = expressiveGroupItemShape(0, 2),
                    tag = "switch_game_dashboard_fix",
                )
            }
            item(key = "auto_confirm_game_dashboard") {
                ExpressiveSwitchRow(
                    title = stringResource(R.string.policy_game_dashboard_auto_confirm_title),
                    subtitle = stringResource(R.string.policy_game_dashboard_auto_confirm_desc),
                    leadingIcon = Icons.Outlined.Bolt,
                    checked = uiState.config.autoConfirmGameDashboardRecord,
                    enabled = uiState.config.fixGameDashboardRecord,
                    onCheckedChange = onAutoConfirmGameDashboardChange,
                    shape = expressiveGroupItemShape(1, 2),
                    tag = "switch_game_dashboard_auto_confirm",
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

            item(key = "header_about") {
                SectionHeader(title = stringResource(R.string.settings_group_about))
            }
            item(key = "about_entry") {
                AboutEntryCard(
                    versionName = BuildConfig.VERSION_NAME,
                    onClick = onOpenAbout,
                    shape = expressiveGroupItemShape(0, 1),
                )
            }
        }
    }
}

@Composable
private fun AboutEntryCard(
    versionName: String,
    onClick: () -> Unit,
    shape: Shape,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag("action_open_about"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.settings_about_entry_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "v$versionName",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = TabularNumericFeature,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
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
        AccentPalette.INDIGO_IRIS to stringResource(R.string.palette_indigo_iris),
        AccentPalette.FOREST_GREEN to stringResource(R.string.palette_forest_green),
        AccentPalette.WARM_AMBER to stringResource(R.string.palette_warm_amber),
        AccentPalette.ROSE_CORAL to stringResource(R.string.palette_rose_coral),
        AccentPalette.SLATE_GRAPHITE to stringResource(R.string.palette_slate_graphite),
    )
    val activeLabel = if (isDynamicActive) {
        stringResource(R.string.settings_palette_dynamic_hint)
    } else {
        items.firstOrNull { it.first == selected }?.second.orEmpty()
    }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_palette_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = activeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fitsSingleRow = maxWidth >= 336.dp
                val rowModifier = if (fitsSingleRow) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                }

                Row(
                    modifier = rowModifier,
                    horizontalArrangement = if (fitsSingleRow) {
                        Arrangement.SpaceBetween
                    } else {
                        Arrangement.spacedBy(6.dp)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { (palette, label) ->
                        CircularPaletteSwatch(
                            palette = palette,
                            label = label,
                            isSelected = !isDynamicActive && selected == palette,
                            onClick = { onSelect(palette) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CircularPaletteSwatch(
    palette: AccentPalette,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val preview = previewColorsForPalette(palette)
    val outerScale by animateFloatAsState(
        targetValue = if (isSelected) 1.06f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = 380f,
        ),
        label = "swatch_scale_${palette.key}",
    )
    val ringColor by animateColorAsState(
        targetValue = if (isSelected) preview.primary else Color.Transparent,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 380f,
        ),
        label = "swatch_ring_${palette.key}",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = label
                this.selected = isSelected
            }
            .clickable(
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag("palette_${palette.key}"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .scale(outerScale)
                .clip(CircleShape)
                .border(
                    width = 2.5.dp,
                    color = ringColor,
                    shape = CircleShape,
                )
                .padding(4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(preview.primary),
            ) {
                AnimatedVisibility(
                    visible = !isSelected,
                    enter = fadeIn(animationSpec = spring(stiffness = 400f)),
                    exit = fadeOut(animationSpec = spring(stiffness = 500f)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(preview.container.copy(alpha = 0.85f)),
                    )
                }

                AnimatedVisibility(
                    visible = isSelected,
                    enter = scaleIn(
                        initialScale = 0.45f,
                        animationSpec = spring(
                            dampingRatio = 0.52f,
                            stiffness = 420f,
                        ),
                    ) + fadeIn(animationSpec = spring(stiffness = 420f)),
                    exit = scaleOut(
                        targetScale = 0.5f,
                        animationSpec = spring(stiffness = 480f),
                    ) + fadeOut(animationSpec = spring(stiffness = 480f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
