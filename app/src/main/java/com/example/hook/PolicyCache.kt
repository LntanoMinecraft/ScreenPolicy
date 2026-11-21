package com.example.hook

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import com.example.data.PolicyConstants
import com.example.data.PolicyJsonCodec
import com.example.model.AppPolicy
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * High-frequency, zero-disk-polling runtime policy cache for system_server and SystemUI.
 *
 * Powered by libxposed API 102 Remote Preferences ("screen_policy") with a real-time
 * OnSharedPreferenceChangeListener:
 * - Hot paths (e.g., WindowState.isSecureLocked at 120Hz) perform only a lock-free
 *   ConcurrentHashMap lookup with zero disk IO and zero polling overhead.
 * - Any preference update written by the app UI via XposedService pushes an instant
 *   in-memory cache refresh in milliseconds.
 */
object PolicyCache {
    private const val UID_CACHE_TTL_MS = 5000L

    private val policyMap = ConcurrentHashMap<String, AppPolicy>()

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        try {
            applyFromPreferences(prefs)
        } catch (_: Throwable) {
            // Never disrupt framework callback thread
        }
    }

    @Volatile
    var fixGameDashboardRecord: Boolean = true
        private set

    @Volatile
    var autoConfirmGameDashboardRecord: Boolean = false
        private set

    @Volatile
    var verboseLogging: Boolean = false
        private set

    private data class CachedUidPackages(
        val packages: Array<String>,
        val expiresAtMs: Long,
    )

    private val uidCache = ConcurrentHashMap<Int, CachedUidPackages>()

    fun init(module: XposedModule, scope: String) {
        try {
            val prefs = module.getRemotePreferences(PolicyConstants.REMOTE_PREFS_GROUP)
            remotePrefs = prefs
            applyFromPreferences(prefs)
            prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
        } catch (t: Throwable) {
            DualTrackLogger.logError(scope, "PolicyCache", "init_remote_prefs_failed", t)
        }
    }

    internal fun applyFromPreferences(prefs: SharedPreferences) {
        val raw = prefs.getString(PolicyConstants.PREF_KEY_CONFIG_JSON, null)
        val config = runCatching { PolicyJsonCodec.decode(raw) }.getOrDefault(
            com.example.model.GlobalPolicyConfig()
        )
        fixGameDashboardRecord = prefs.getBoolean(
            PolicyConstants.PREF_KEY_FIX_GAME_DASHBOARD,
            config.fixGameDashboardRecord,
        )
        autoConfirmGameDashboardRecord = prefs.getBoolean(
            PolicyConstants.PREF_KEY_AUTO_CONFIRM_GAME_DASHBOARD,
            config.autoConfirmGameDashboardRecord,
        )
        verboseLogging = prefs.getBoolean(
            PolicyConstants.PREF_KEY_VERBOSE_LOGGING,
            config.verboseLogging,
        )

        val incoming = config.appPolicies
        val iterator = policyMap.keys.iterator()
        while (iterator.hasNext()) {
            val existingKey = iterator.next()
            if (!incoming.containsKey(existingKey)) {
                iterator.remove()
            }
        }
        incoming.forEach { (pkg, policy) ->
            if (policy.hasAnyEnabled) {
                policyMap[pkg] = policy
            } else {
                policyMap.remove(pkg)
            }
        }
    }

    fun getPolicy(packageName: String?): AppPolicy? {
        if (packageName.isNullOrEmpty()) return null
        return policyMap[packageName]
    }

    fun isScreenshotAllowed(packageName: String?): Boolean {
        return getPolicy(packageName)?.allowScreenshot == true
    }

    fun isScreenRecordAllowed(packageName: String?): Boolean {
        return getPolicy(packageName)?.allowScreenRecord == true
    }

    fun isAudioCaptureAllowed(packageName: String?): Boolean {
        return getPolicy(packageName)?.allowAudioCapture == true
    }

    fun isBlockCaptureDetectEnabled(packageName: String?): Boolean {
        return getPolicy(packageName)?.blockCaptureDetect == true
    }

    fun isGameDashboardFixEnabled(): Boolean {
        return fixGameDashboardRecord
    }

    fun isGameDashboardAutoConfirmEnabled(): Boolean {
        return fixGameDashboardRecord && autoConfirmGameDashboardRecord
    }

    fun isVerboseEnabled(): Boolean {
        return verboseLogging
    }

    fun resolvePackagesForUid(uid: Int): Array<String> {
        if (uid <= 0) return emptyArray()
        val now = SystemClock.elapsedRealtime()
        val cached = uidCache[uid]
        if (cached != null && now < cached.expiresAtMs) {
            return cached.packages
        }
        val resolved = try {
            val atClass = HookReflect.findClassOrNull("android.app.ActivityThread", null)
                ?: return emptyArray()
            val app = HookReflect.callStaticZeroArgOrNull(atClass, "currentApplication") as? Application
            val pm: PackageManager? = app?.packageManager
            pm?.getPackagesForUid(uid) ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }
        if (uidCache.size > 256) {
            uidCache.clear()
        }
        uidCache[uid] = CachedUidPackages(resolved, now + UID_CACHE_TTL_MS)
        return resolved
    }

    fun findAllowlistedPackageForUid(uid: Int, predicate: (AppPolicy) -> Boolean): String? {
        if (policyMap.isEmpty()) return null
        val packages = resolvePackagesForUid(uid)
        for (pkg in packages) {
            val policy = policyMap[pkg]
            if (policy != null && predicate(policy)) {
                return pkg
            }
        }
        return null
    }
}
