package com.example.model

data class AppPolicy(
    val allowScreenshot: Boolean = false,
    val allowScreenRecord: Boolean = false,
    val allowAudioCapture: Boolean = false,
    val blockCaptureDetect: Boolean = false,
) {
    val hasAnyEnabled: Boolean
        get() = allowScreenshot || allowScreenRecord || allowAudioCapture || blockCaptureDetect
}

data class GlobalPolicyConfig(
    val fixGameDashboardRecord: Boolean = true,
    val verboseLogging: Boolean = false,
    val appPolicies: Map<String, AppPolicy> = emptyMap(),
)

enum class HookMountState {
    READY,
    FAILED,
    WAITING,
}

enum class ModuleActivationState {
    INACTIVE,
    MISSING_FRAMEWORK,
    MISSING_SYSTEM_UI,
    ACTIVE,
}

data class HookStatusEntry(
    val key: String,
    val scope: String,
    val state: HookMountState = HookMountState.WAITING,
    val detail: String = "",
    val updatedAtMs: Long = 0L,
    val bootCount: Int = -1,
)

data class PolicyStatusSummary(
    val activationState: ModuleActivationState = ModuleActivationState.INACTIVE,
    val frameworkAlive: Boolean = false,
    val systemUiAlive: Boolean = false,
    val frameworkReadyCount: Int = 0,
    val frameworkTotalCount: Int = 4,
    val systemUiReadyCount: Int = 0,
    val systemUiTotalCount: Int = 1,
    val hookEntries: List<HookStatusEntry> = emptyList(),
)

enum class LogLevel(val code: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERR"),
    HIT("HIT");

    companion object {
        fun fromCode(raw: String?): LogLevel =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.code.equals(raw, ignoreCase = true) }
                ?: INFO
    }
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val source: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class AccentPalette(val key: String) {
    SLATE_TEAL("slate_teal"),
    OCEAN_BLUE("ocean_blue"),
    FOREST_GREEN("forest_green"),
    WARM_AMBER("warm_amber");

    companion object {
        fun fromKey(key: String?): AccentPalette =
            entries.firstOrNull { it.key == key } ?: SLATE_TEAL
    }
}

enum class AppLanguage(val key: String, val languageTag: String?) {
    SYSTEM("system", null),
    ZH_CN("zh-CN", "zh-CN"),
    EN("en", "en");

    companion object {
        fun fromLanguageTag(tag: String?): AppLanguage {
            val normalized = tag?.trim()?.lowercase().orEmpty()
            return when {
                normalized.isEmpty() -> SYSTEM
                normalized.startsWith("zh") -> ZH_CN
                normalized.startsWith("en") -> EN
                else -> SYSTEM
            }
        }
    }
}

data class UiAppearancePrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val palette: AccentPalette = AccentPalette.SLATE_TEAL,
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val isManuallyAdded: Boolean = false,
    val policy: AppPolicy = AppPolicy(),
)
