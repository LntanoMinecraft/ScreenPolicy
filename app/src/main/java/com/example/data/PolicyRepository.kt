package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import com.example.model.AccentPalette
import com.example.model.AppPolicy
import com.example.model.GlobalPolicyConfig
import com.example.model.InstalledAppInfo
import com.example.model.ThemeMode
import com.example.model.UiAppearancePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists allowlist policies via world-readable SharedPreferences (for LSPosed XSharedPreferences)
 * and UI appearance settings via private SharedPreferences. Uses zero-permission launcher queries
 * and SAF streams for JSON export/import.
 */
class PolicyRepository(private val context: Context) {

    private val appContext: Context = context.applicationContext

    private val uiPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PolicyConstants.UI_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks whether LSPosed's world-readable preference bridge is active for this module.
     * In LSPosed with xposedsharedprefs=true, MODE_WORLD_READABLE succeeds ONLY when active.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("WorldReadableFiles")
    fun isWorldReadablePrefsActive(): Boolean {
        return try {
            appContext.getSharedPreferences(
                PolicyConstants.POLICY_PREFS_NAME,
                Context.MODE_WORLD_READABLE,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WorldReadableFiles")
    private fun obtainPolicyPrefs(): SharedPreferences {
        return try {
            appContext.getSharedPreferences(
                PolicyConstants.POLICY_PREFS_NAME,
                Context.MODE_WORLD_READABLE,
            )
        } catch (_: SecurityException) {
            appContext.getSharedPreferences(
                PolicyConstants.POLICY_PREFS_NAME,
                Context.MODE_PRIVATE,
            )
        }
    }

    fun loadGlobalPolicyConfig(): GlobalPolicyConfig {
        val prefs = obtainPolicyPrefs()
        val raw = prefs.getString(PolicyConstants.PREF_KEY_CONFIG_JSON, null)
        return runCatching { PolicyJsonCodec.decode(raw) }.getOrElse { GlobalPolicyConfig() }
    }

    fun saveGlobalPolicyConfig(config: GlobalPolicyConfig) {
        val prefs = obtainPolicyPrefs()
        val serialized = PolicyJsonCodec.encode(config)
        prefs.edit()
            .putString(PolicyConstants.PREF_KEY_CONFIG_JSON, serialized)
            .putBoolean(PolicyConstants.PREF_KEY_FIX_GAME_DASHBOARD, config.fixGameDashboardRecord)
            .putBoolean(
                PolicyConstants.PREF_KEY_AUTO_CONFIRM_GAME_DASHBOARD,
                config.autoConfirmGameDashboardRecord,
            )
            .putBoolean(PolicyConstants.PREF_KEY_VERBOSE_LOGGING, config.verboseLogging)
            .apply()
    }

    fun loadAppearancePrefs(): UiAppearancePrefs {
        return UiAppearancePrefs(
            themeMode = ThemeMode.fromKey(uiPrefs.getString(PolicyConstants.PREF_UI_THEME_MODE, null)),
            dynamicColor = uiPrefs.getBoolean(PolicyConstants.PREF_UI_DYNAMIC_COLOR, true),
            palette = AccentPalette.fromKey(uiPrefs.getString(PolicyConstants.PREF_UI_PALETTE, null)),
        )
    }

    fun saveAppearancePrefs(prefs: UiAppearancePrefs) {
        uiPrefs.edit()
            .putString(PolicyConstants.PREF_UI_THEME_MODE, prefs.themeMode.key)
            .putBoolean(PolicyConstants.PREF_UI_DYNAMIC_COLOR, prefs.dynamicColor)
            .putString(PolicyConstants.PREF_UI_PALETTE, prefs.palette.key)
            .apply()
    }

    /**
     * Loads launchable apps via targeted ACTION_MAIN/CATEGORY_LAUNCHER queries (zero QUERY_ALL_PACKAGES)
     * and merges any manually added packages currently present in the allowlist.
     */
    suspend fun loadInstalledApps(currentPolicies: Map<String, AppPolicy>): List<InstalledAppInfo> =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            }

            val seenPackages = linkedMapOf<String, InstalledAppInfo>()
            for (info in resolved) {
                val activityInfo = info.activityInfo ?: continue
                val pkg = activityInfo.packageName ?: continue
                if (pkg == appContext.packageName || seenPackages.containsKey(pkg)) continue
                val appInfo = activityInfo.applicationInfo
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val label = runCatching {
                    info.loadLabel(pm).toString().trim()
                }.getOrNull().takeUnless { it.isNullOrBlank() } ?: pkg

                seenPackages[pkg] = InstalledAppInfo(
                    packageName = pkg,
                    label = label,
                    isSystemApp = isSystem,
                    isManuallyAdded = false,
                    policy = currentPolicies[pkg] ?: AppPolicy(),
                )
            }

            // Include manually configured packages that do not expose a launcher activity
            for ((pkg, policy) in currentPolicies) {
                if (!seenPackages.containsKey(pkg)) {
                    val appInfo = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(pkg, 0)
                        }
                    }.getOrNull()
                    val label = appInfo?.let {
                        runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
                    } ?: pkg
                    val isSystem = appInfo?.let {
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    } ?: false
                    seenPackages[pkg] = InstalledAppInfo(
                        packageName = pkg,
                        label = label,
                        isSystemApp = isSystem,
                        isManuallyAdded = true,
                        policy = policy,
                    )
                }
            }

            seenPackages.values.sortedWith(
                compareByDescending<InstalledAppInfo> { it.policy.hasAnyEnabled }
                    .thenBy { it.label.lowercase() }
            )
        }

    fun loadAppIcon(packageName: String): Drawable? {
        return runCatching {
            appContext.packageManager.getApplicationIcon(packageName)
        }.getOrNull()
    }

    suspend fun exportConfigToUri(uri: Uri, config: GlobalPolicyConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = PolicyJsonCodec.encode(config, prettyIndent = 2)
                appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: error("Output stream unavailable")
            }
        }

    suspend fun importConfigFromUri(uri: Uri): Result<GlobalPolicyConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                val maxBytes = 2 * 1024 * 1024
                val text = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readNBytes(maxBytes + 1)
                    if (bytes.size > maxBytes) {
                        throw IllegalArgumentException("Backup file exceeds maximum supported size")
                    }
                    bytes.toString(Charsets.UTF_8)
                } ?: error("Input stream unavailable")
                PolicyJsonCodec.decodeBackupStrict(text)
            }
        }
}
