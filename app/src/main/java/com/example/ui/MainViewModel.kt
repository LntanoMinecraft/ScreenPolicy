package com.example.ui

import android.app.Application
import android.app.LocaleManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.LocaleList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.PolicyConstants
import com.example.data.PolicyRepository
import com.example.model.AccentPalette
import com.example.model.AppLanguage
import com.example.model.AppPolicy
import com.example.model.GlobalPolicyConfig
import com.example.model.HookMountState
import com.example.model.InstalledAppInfo
import com.example.model.LogEntry
import com.example.model.PolicyStatusSummary
import com.example.model.ThemeMode
import com.example.model.UiAppearancePrefs
import com.example.provider.TelemetryStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppListFilter {
    ALL,
    CONFIGURED,
}

data class MainUiState(
    val status: PolicyStatusSummary = PolicyStatusSummary(),
    val config: GlobalPolicyConfig = GlobalPolicyConfig(),
    val appearance: UiAppearancePrefs = UiAppearancePrefs(),
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val apps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = true,
    val searchQuery: String = "",
    val appFilter: AppListFilter = AppListFilter.ALL,
    val showSystemApps: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val logsOnlyErrors: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = PolicyRepository(appContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    init {
        TelemetryStore.ensureLoaded(appContext)
        val initialConfig = repository.loadGlobalPolicyConfig()
        val initialAppearance = repository.loadAppearancePrefs()

        _uiState.update {
            it.copy(
                config = initialConfig,
                appearance = initialAppearance,
                appLanguage = readCurrentAppLanguage(),
                status = buildStatusSummary(TelemetryStore.hookStatuses.value),
                logs = TelemetryStore.logs.value,
            )
        }

        observeTelemetry()
        refreshApps()
    }

    private fun observeTelemetry() {
        viewModelScope.launch {
            TelemetryStore.logs.collect { logs ->
                _uiState.update { it.copy(logs = logs) }
            }
        }
        viewModelScope.launch {
            TelemetryStore.hookStatuses.collect { statusMap ->
                _uiState.update { it.copy(status = buildStatusSummary(statusMap)) }
            }
        }
    }

    private fun buildStatusSummary(
        statusMap: Map<String, com.example.model.HookStatusEntry>,
    ): PolicyStatusSummary {
        val currentBoot = TelemetryStore.currentBootCount(appContext)
        val orderedKeys = listOf(
            PolicyConstants.HOOK_SCREENSHOT,
            PolicyConstants.HOOK_SCREEN_RECORD,
            PolicyConstants.HOOK_AUDIO_CAPTURE,
            PolicyConstants.HOOK_CAPTURE_DETECT,
            PolicyConstants.HOOK_GAME_DASHBOARD,
        )
        val entries = orderedKeys.mapNotNull { key ->
            val raw = statusMap[key] ?: return@mapNotNull null
            if (TelemetryStore.isEntryFromCurrentBoot(raw, currentBoot)) {
                raw
            } else {
                raw.copy(state = HookMountState.WAITING, detail = "")
            }
        }

        val frameworkEntries = entries.filter { it.scope == PolicyConstants.SCOPE_ANDROID }
        val systemUiEntries = entries.filter { it.scope == PolicyConstants.SCOPE_SYSTEMUI }

        val frameworkAlive = frameworkEntries.any { it.state != HookMountState.WAITING }
        val systemUiAlive = systemUiEntries.any { it.state != HookMountState.WAITING }

        val frameworkReady = frameworkEntries.count { it.state == HookMountState.READY }
        val systemUiReady = systemUiEntries.count { it.state == HookMountState.READY }

        val activationState = when {
            frameworkAlive && systemUiAlive -> com.example.model.ModuleActivationState.ACTIVE
            frameworkAlive && !systemUiAlive -> com.example.model.ModuleActivationState.MISSING_SYSTEM_UI
            !frameworkAlive && systemUiAlive -> com.example.model.ModuleActivationState.MISSING_FRAMEWORK
            else -> com.example.model.ModuleActivationState.INACTIVE
        }

        return PolicyStatusSummary(
            activationState = activationState,
            frameworkAlive = frameworkAlive,
            systemUiAlive = systemUiAlive,
            frameworkReadyCount = frameworkReady,
            frameworkTotalCount = 4,
            systemUiReadyCount = systemUiReady,
            systemUiTotalCount = 1,
            hookEntries = entries,
        )
    }

    fun refreshApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true) }
            val loaded = repository.loadInstalledApps(_uiState.value.config.appPolicies)
            _uiState.update {
                it.copy(
                    apps = loaded,
                    isLoadingApps = false,
                )
            }
        }
    }

    fun refreshStatusAndLogs() {
        TelemetryStore.reload(appContext)
        _uiState.update {
            it.copy(
                status = buildStatusSummary(TelemetryStore.hookStatuses.value),
                logs = TelemetryStore.logs.value,
                appLanguage = readCurrentAppLanguage(),
            )
        }
    }

    fun setAppLanguage(language: AppLanguage) {
        val localeManager = appContext.getSystemService(LocaleManager::class.java)
        val localeList = if (language.languageTag != null) {
            LocaleList.forLanguageTags(language.languageTag)
        } else {
            LocaleList.getEmptyLocaleList()
        }
        runCatching {
            localeManager?.applicationLocales = localeList
        }
        _uiState.update { it.copy(appLanguage = language) }
    }

    private fun readCurrentAppLanguage(): AppLanguage {
        return runCatching {
            val localeManager = appContext.getSystemService(LocaleManager::class.java)
            val locales = localeManager?.applicationLocales
            if (locales == null || locales.isEmpty) {
                AppLanguage.SYSTEM
            } else {
                AppLanguage.fromLanguageTag(locales.get(0)?.toLanguageTag())
            }
        }.getOrDefault(AppLanguage.SYSTEM)
    }

    fun loadAppIcon(packageName: String): Drawable? = repository.loadAppIcon(packageName)

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setAppFilter(filter: AppListFilter) {
        _uiState.update { it.copy(appFilter = filter) }
    }

    fun toggleShowSystemApps() {
        _uiState.update { it.copy(showSystemApps = !it.showSystemApps) }
    }

    fun updateAppPolicy(packageName: String, updatedPolicy: AppPolicy) {
        val currentConfig = _uiState.value.config
        val nextPolicies = currentConfig.appPolicies.toMutableMap()
        if (updatedPolicy.hasAnyEnabled) {
            nextPolicies[packageName] = updatedPolicy
        } else {
            nextPolicies.remove(packageName)
        }
        val nextConfig = currentConfig.copy(appPolicies = nextPolicies)
        repository.saveGlobalPolicyConfig(nextConfig)

        val updatedApps = _uiState.value.apps.map { app ->
            if (app.packageName == packageName) app.copy(policy = updatedPolicy) else app
        }.sortedWith(
            compareByDescending<InstalledAppInfo> { it.policy.hasAnyEnabled }
                .thenBy { it.label.lowercase() }
        )

        _uiState.update {
            it.copy(
                config = nextConfig,
                apps = updatedApps,
            )
        }
        _snackbarEvents.tryEmit(appContext.getString(R.string.snackbar_restart_to_apply))
    }

    fun addManualPackage(rawPackage: String): InstalledAppInfo? {
        val pkg = rawPackage.trim()
        if (pkg.isEmpty() || !pkg.contains('.')) return null
        val existing = _uiState.value.apps.firstOrNull { it.packageName == pkg }
        if (existing != null) return existing

        val newEntry = InstalledAppInfo(
            packageName = pkg,
            label = pkg,
            isSystemApp = false,
            isManuallyAdded = true,
            policy = _uiState.value.config.appPolicies[pkg] ?: AppPolicy(),
        )
        _uiState.update { state ->
            state.copy(apps = listOf(newEntry) + state.apps)
        }
        return newEntry
    }

    fun setFixGameDashboardRecord(enabled: Boolean) {
        val next = _uiState.value.config.copy(fixGameDashboardRecord = enabled)
        repository.saveGlobalPolicyConfig(next)
        _uiState.update { it.copy(config = next) }
    }

    fun setAutoConfirmGameDashboardRecord(enabled: Boolean) {
        val next = _uiState.value.config.copy(autoConfirmGameDashboardRecord = enabled)
        repository.saveGlobalPolicyConfig(next)
        _uiState.update { it.copy(config = next) }
    }

    fun setVerboseLogging(enabled: Boolean) {
        val next = _uiState.value.config.copy(verboseLogging = enabled)
        repository.saveGlobalPolicyConfig(next)
        _uiState.update { it.copy(config = next) }
    }

    fun setThemeMode(mode: ThemeMode) {
        val next = _uiState.value.appearance.copy(themeMode = mode)
        repository.saveAppearancePrefs(next)
        _uiState.update { it.copy(appearance = next) }
    }

    fun setDynamicColor(enabled: Boolean) {
        val next = _uiState.value.appearance.copy(dynamicColor = enabled)
        repository.saveAppearancePrefs(next)
        _uiState.update { it.copy(appearance = next) }
    }

    fun setPalette(palette: AccentPalette) {
        val next = _uiState.value.appearance.copy(
            dynamicColor = false,
            palette = palette,
        )
        repository.saveAppearancePrefs(next)
        _uiState.update { it.copy(appearance = next) }
    }

    fun setLogsOnlyErrors(onlyErrors: Boolean) {
        _uiState.update { it.copy(logsOnlyErrors = onlyErrors) }
    }

    fun clearLogs() {
        TelemetryStore.clearLogs(appContext)
        _snackbarEvents.tryEmit(appContext.getString(R.string.logs_cleared))
    }

    fun formatLogsForClipboard(entries: List<LogEntry>): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return entries.joinToString("\n") { entry ->
            val time = formatter.format(Date(entry.timestampMs))
            "[$time] [${entry.level.code}] [${entry.source}/${entry.tag}] ${entry.message}"
        }
    }

    fun notifyCopied() {
        _snackbarEvents.tryEmit(appContext.getString(R.string.logs_copied))
    }

    fun exportConfig(uri: Uri) {
        viewModelScope.launch {
            val result = repository.exportConfigToUri(uri, _uiState.value.config)
            val msg = if (result.isSuccess) {
                appContext.getString(R.string.snackbar_export_success)
            } else {
                appContext.getString(R.string.snackbar_export_failed)
            }
            _snackbarEvents.tryEmit(msg)
        }
    }

    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            val result = repository.importConfigFromUri(uri)
            result.onSuccess { imported ->
                repository.saveGlobalPolicyConfig(imported)
                val reloadedApps = repository.loadInstalledApps(imported.appPolicies)
                _uiState.update {
                    it.copy(
                        config = imported,
                        apps = reloadedApps,
                    )
                }
                _snackbarEvents.tryEmit(
                    appContext.getString(R.string.snackbar_import_success, imported.appPolicies.size)
                )
            }.onFailure {
                _snackbarEvents.tryEmit(appContext.getString(R.string.snackbar_import_failed))
            }
        }
    }
}
