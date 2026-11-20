package com.example.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.PolicyConstants
import com.example.model.AppPolicy
import com.example.model.HookMountState
import com.example.model.HookStatusEntry
import com.example.model.InstalledAppInfo
import com.example.model.ModuleActivationState
import com.example.model.PolicyStatusSummary
import com.example.ui.AppListFilter
import com.example.ui.MainUiState
import com.example.ui.components.AppIconAvatar
import com.example.ui.components.ExpressiveSwitchRow
import com.example.ui.components.expressiveGroupItemShape
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    uiState: MainUiState,
    loadIcon: (String) -> Drawable?,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (AppListFilter) -> Unit,
    onToggleShowSystemApps: () -> Unit,
    onUpdateAppPolicy: (String, AppPolicy) -> Unit,
    onAddManualPackage: (String) -> InstalledAppInfo?,
    bottomContentPadding: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    var selectedPackageName by remember { mutableStateOf<String?>(null) }
    var showAddPackageDialog by remember { mutableStateOf(false) }

    val configuredCount by remember(uiState.apps) {
        derivedStateOf { uiState.apps.count { it.policy.hasAnyEnabled } }
    }

    val filteredApps by remember(
        uiState.apps,
        uiState.searchQuery,
        uiState.appFilter,
        uiState.showSystemApps,
    ) {
        derivedStateOf {
            val q = uiState.searchQuery.trim().lowercase()
            uiState.apps.filter { app ->
                val matchesSystem = uiState.showSystemApps || !app.isSystemApp || app.policy.hasAnyEnabled
                val matchesTab = when (uiState.appFilter) {
                    AppListFilter.ALL -> true
                    AppListFilter.CONFIGURED -> app.policy.hasAnyEnabled
                }
                val matchesQuery = q.isEmpty() ||
                    app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q)
                matchesSystem && matchesTab && matchesQuery
            }
        }
    }

    val selectedApp = remember(selectedPackageName, uiState.apps) {
        selectedPackageName?.let { pkg -> uiState.apps.firstOrNull { it.packageName == pkg } }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 720.dp),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            actions = {
                IconButton(
                    onClick = { showAddPackageDialog = true },
                    modifier = Modifier.testTag("add_package_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.action_add_package),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "status_card") {
                ModuleStatusHeroCard(
                    status = uiState.status,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            item(key = "search_bar") {
                ExpressiveSearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            item(key = "filter_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = uiState.appFilter == AppListFilter.ALL,
                        onClick = { onFilterChange(AppListFilter.ALL) },
                        label = { Text(stringResource(R.string.filter_all)) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        modifier = Modifier.testTag("filter_chip_all"),
                    )

                    FilterChip(
                        selected = uiState.appFilter == AppListFilter.CONFIGURED,
                        onClick = { onFilterChange(AppListFilter.CONFIGURED) },
                        label = {
                            val labelText = if (configuredCount > 0) {
                                "${stringResource(R.string.filter_configured)} ($configuredCount)"
                            } else {
                                stringResource(R.string.filter_configured)
                            }
                            Text(labelText)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        modifier = Modifier.testTag("filter_chip_configured"),
                    )

                    FilterChip(
                        selected = uiState.showSystemApps,
                        onClick = onToggleShowSystemApps,
                        label = { Text(stringResource(R.string.filter_system)) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        modifier = Modifier.testTag("filter_chip_system"),
                    )
                }
            }

            if (uiState.isLoadingApps) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                }
            } else if (filteredApps.isEmpty()) {
                item(key = "empty") {
                    EmptyAppsPlaceholder()
                }
            } else {
                itemsIndexed(
                    items = filteredApps,
                    key = { _, app -> app.packageName },
                ) { index, app ->
                    AppPolicyListRow(
                        app = app,
                        shape = expressiveGroupItemShape(index, filteredApps.size),
                        loadIcon = loadIcon,
                        onClick = { selectedPackageName = app.packageName },
                    )
                }
            }
        }
    }

    if (selectedApp != null) {
        AppPolicyBottomSheet(
            app = selectedApp,
            loadIcon = loadIcon,
            onDismiss = { selectedPackageName = null },
            onPolicyChange = { updated ->
                onUpdateAppPolicy(selectedApp.packageName, updated)
            },
        )
    }

    if (showAddPackageDialog) {
        AddManualPackageDialog(
            onDismiss = { showAddPackageDialog = false },
            onConfirm = { rawPkg ->
                val added = onAddManualPackage(rawPkg)
                showAddPackageDialog = false
                if (added != null) {
                    selectedPackageName = added.packageName
                }
            },
        )
    }
}

@Composable
private fun ModuleStatusHeroCard(
    status: PolicyStatusSummary,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "status_chevron",
    )

    val containerColor: Color
    val contentColor: Color
    val badgeBgColor: Color
    val badgeIconColor: Color
    val statusIcon: androidx.compose.ui.graphics.vector.ImageVector
    val titleText: String
    val summaryText: String?
    val descriptionText: String

    when (status.activationState) {
        ModuleActivationState.INACTIVE -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            badgeBgColor = MaterialTheme.colorScheme.error
            badgeIconColor = MaterialTheme.colorScheme.onError
            statusIcon = Icons.Outlined.ErrorOutline
            titleText = stringResource(R.string.status_inactive_title)
            summaryText = null
            descriptionText = stringResource(R.string.status_inactive_desc)
        }

        ModuleActivationState.MISSING_FRAMEWORK -> {
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            badgeBgColor = MaterialTheme.colorScheme.tertiary
            badgeIconColor = MaterialTheme.colorScheme.onTertiary
            statusIcon = Icons.Outlined.WarningAmber
            titleText = stringResource(R.string.status_scope_missing_title)
            summaryText = stringResource(
                R.string.status_summary_format,
                status.frameworkReadyCount,
                status.frameworkTotalCount,
                status.systemUiReadyCount,
                status.systemUiTotalCount,
            )
            descriptionText = stringResource(R.string.status_missing_framework_desc)
        }

        ModuleActivationState.MISSING_SYSTEM_UI -> {
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            badgeBgColor = MaterialTheme.colorScheme.tertiary
            badgeIconColor = MaterialTheme.colorScheme.onTertiary
            statusIcon = Icons.Outlined.WarningAmber
            titleText = stringResource(R.string.status_scope_missing_title)
            summaryText = stringResource(
                R.string.status_summary_format,
                status.frameworkReadyCount,
                status.frameworkTotalCount,
                status.systemUiReadyCount,
                status.systemUiTotalCount,
            )
            descriptionText = stringResource(R.string.status_missing_systemui_desc)
        }

        ModuleActivationState.ACTIVE -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            badgeBgColor = MaterialTheme.colorScheme.primary
            badgeIconColor = MaterialTheme.colorScheme.onPrimary
            statusIcon = Icons.Outlined.CheckCircle
            titleText = stringResource(R.string.status_running_title)
            summaryText = stringResource(
                R.string.status_running_summary_format,
                status.frameworkReadyCount,
                status.frameworkTotalCount,
                status.systemUiReadyCount,
                status.systemUiTotalCount,
            )
            descriptionText = ""
        }
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable { expanded = !expanded }
            .testTag("module_status_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(badgeBgColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = badgeIconColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleLarge,
                            color = contentColor,
                        )
                        if (summaryText != null) {
                            Text(
                                text = summaryText,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                ),
                                color = contentColor.copy(alpha = 0.84f),
                            )
                        }
                    }
                }

                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(chevronRotation),
                )
            }

            if (descriptionText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.88f),
                )
            }

            AnimatedVisibility(
                visible = expanded && status.hookEntries.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    status.hookEntries.forEach { entry ->
                        HookStatusMiniRow(entry = entry, onContainerColor = contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun HookStatusMiniRow(
    entry: HookStatusEntry,
    onContainerColor: Color,
) {
    val label = when (entry.key) {
        PolicyConstants.HOOK_SCREENSHOT -> stringResource(R.string.policy_screenshot_title)
        PolicyConstants.HOOK_SCREEN_RECORD -> stringResource(R.string.policy_screen_record_title)
        PolicyConstants.HOOK_AUDIO_CAPTURE -> stringResource(R.string.policy_audio_capture_title)
        PolicyConstants.HOOK_CAPTURE_DETECT -> stringResource(R.string.policy_capture_detect_title)
        PolicyConstants.HOOK_GAME_DASHBOARD -> stringResource(R.string.policy_game_dashboard_title)
        else -> entry.key
    }
    val stateText = when (entry.state) {
        HookMountState.READY -> stringResource(R.string.status_detail_ready)
        HookMountState.FAILED -> stringResource(R.string.status_detail_failed)
        HookMountState.WAITING -> stringResource(R.string.status_detail_waiting)
    }
    val stateIcon = when (entry.state) {
        HookMountState.READY -> Icons.Outlined.CheckCircle
        HookMountState.FAILED -> Icons.Outlined.ErrorOutline
        HookMountState.WAITING -> Icons.Outlined.RadioButtonUnchecked
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = stateIcon,
                contentDescription = null,
                tint = if (entry.state == HookMountState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    onContainerColor
                },
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = onContainerColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stateText,
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.state == HookMountState.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                onContainerColor.copy(alpha = 0.78f)
            },
        )
    }
}

@Composable
private fun ExpressiveSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_apps_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apps_search_input"),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("apps_search_clear"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPolicyListRow(
    app: InstalledAppInfo,
    shape: Shape,
    loadIcon: (String) -> Drawable?,
    onClick: () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag("app_row_${app.packageName}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconAvatar(
                packageName = app.packageName,
                label = app.label,
                loadIcon = loadIcon,
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.isSystemApp) {
                        MiniBadge(
                            text = stringResource(R.string.badge_system),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (app.policy.hasAnyEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (app.policy.allowScreenshot) {
                            MiniBadge(
                                text = stringResource(R.string.badge_screenshot),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (app.policy.allowScreenRecord) {
                            MiniBadge(
                                text = stringResource(R.string.badge_screen_record),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (app.policy.allowAudioCapture) {
                            MiniBadge(
                                text = stringResource(R.string.badge_audio),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (app.policy.blockCaptureDetect) {
                            MiniBadge(
                                text = stringResource(R.string.badge_detect),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPolicyBottomSheet(
    app: InstalledAppInfo,
    loadIcon: (String) -> Drawable?,
    onDismiss: () -> Unit,
    onPolicyChange: (AppPolicy) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    AppIconAvatar(
                        packageName = app.packageName,
                        label = app.label,
                        loadIcon = loadIcon,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (app.policy.hasAnyEnabled) {
                    TextButton(
                        onClick = { onPolicyChange(AppPolicy()) },
                        modifier = Modifier.testTag("sheet_reset_all"),
                    ) {
                        Text(stringResource(R.string.action_reset_app_policy))
                    }
                }
            }

            ExpressiveSwitchRow(
                title = stringResource(R.string.policy_screenshot_title),
                subtitle = stringResource(R.string.policy_screenshot_desc),
                leadingIcon = Icons.Outlined.Screenshot,
                checked = app.policy.allowScreenshot,
                onCheckedChange = { checked ->
                    onPolicyChange(app.policy.copy(allowScreenshot = checked))
                },
                shape = expressiveGroupItemShape(0, 4),
                tag = "switch_allow_screenshot",
            )

            ExpressiveSwitchRow(
                title = stringResource(R.string.policy_screen_record_title),
                subtitle = stringResource(R.string.policy_screen_record_desc),
                leadingIcon = Icons.Outlined.Videocam,
                checked = app.policy.allowScreenRecord,
                onCheckedChange = { checked ->
                    onPolicyChange(app.policy.copy(allowScreenRecord = checked))
                },
                shape = expressiveGroupItemShape(1, 4),
                tag = "switch_allow_screen_record",
            )

            ExpressiveSwitchRow(
                title = stringResource(R.string.policy_audio_capture_title),
                subtitle = stringResource(R.string.policy_audio_capture_desc),
                leadingIcon = Icons.Outlined.GraphicEq,
                checked = app.policy.allowAudioCapture,
                onCheckedChange = { checked ->
                    onPolicyChange(app.policy.copy(allowAudioCapture = checked))
                },
                shape = expressiveGroupItemShape(2, 4),
                tag = "switch_audio_capture",
            )

            ExpressiveSwitchRow(
                title = stringResource(R.string.policy_capture_detect_title),
                subtitle = stringResource(R.string.policy_capture_detect_desc),
                leadingIcon = Icons.Outlined.VisibilityOff,
                checked = app.policy.blockCaptureDetect,
                onCheckedChange = { checked ->
                    onPolicyChange(app.policy.copy(blockCaptureDetect = checked))
                },
                shape = expressiveGroupItemShape(3, 4),
                tag = "switch_capture_detect",
            )
        }
    }
}

@Composable
private fun AddManualPackageDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_package_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.dialog_add_package_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_package_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input) },
                enabled = input.trim().contains('.'),
                modifier = Modifier.testTag("manual_package_confirm"),
            ) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun EmptyAppsPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.empty_apps_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.empty_apps_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
