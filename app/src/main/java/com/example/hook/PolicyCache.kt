package com.example.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import com.example.data.PolicyConstants
import com.example.data.PolicyJsonCodec
import com.example.model.AppPolicy
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-frequency, zero-disk-polling runtime policy cache for system_server and SystemUI.
 *
 * Powered by libxposed API 102 Remote Preferences ("screen_policy") with a real-time
 * OnSharedPreferenceChangeListener:
 * - Hot paths (e.g., WindowState.isSecureLocked at 120Hz) perform only a lock-free
 *   ConcurrentHashMap lookup with zero disk IO and zero polling overhead.
 * - Any preference update written by the app UI via XposedService pushes an instant
 *   in-memory cache refresh in milliseconds and notifies registered policy listeners
 *   (e.g., AudioCaptureHook active audioserver cache refresh).
 */
object PolicyCache {
    private const val UID_CACHE_TTL_MS = 5000L
    private const val EMPTY_UID_CACHE_TTL_MS = 500L

    private val policyMap = ConcurrentHashMap<String, AppPolicy>()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

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

    fun addPolicyChangeListener(listener: () -> Unit) {
        changeListeners.addIfAbsent(listener)
    }

    internal fun removePolicyChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
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

        for (listener in changeListeners) {
            try {
                listener()
            } catch (_: Throwable) {
                // Keep policy change dispatch isolated
            }
        }
    }

    fun getPolicy(packageName: String?): AppPolicy? {
        if (packageName.isNullOrEmpty()) return null
        return policyMap[packageName]
    }

    fun getAudioCaptureAllowedPackages(): Set<String> {
        val result = LinkedHashSet<String>()
        policyMap.forEach { (pkg, policy) ->
            if (policy.allowAudioCapture) {
                result.add(pkg)
            }
        }
        return result
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

    /**
     * Seeds the UID -> package cache when a proactive package->UID lookup succeeds,
     * ensuring instant hit resolution on the AudioSystem/AudioService hot path.
     */
    internal fun registerResolvedUid(uid: Int, packageName: String) {
        if (uid <= 0 || packageName.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val existing = uidCache[uid]?.packages
        val merged = if (existing == null || existing.isEmpty()) {
            arrayOf(packageName)
        } else if (existing.contains(packageName)) {
            existing
        } else {
            existing + packageName
        }
        if (uidCache.size > 256) {
            uidCache.clear()
        }
        uidCache[uid] = CachedUidPackages(merged, now + UID_CACHE_TTL_MS)
    }

    fun resolvePackagesForUid(uid: Int): Array<String> {
        if (uid <= 0) return emptyArray()
        val now = SystemClock.elapsedRealtime()
        val cached = uidCache[uid]
        if (cached != null && now < cached.expiresAtMs) {
            return cached.packages
        }
        val resolved = queryPackagesForUid(uid)
        if (uidCache.size > 256) {
            uidCache.clear()
        }
        val ttl = if (resolved.isNotEmpty()) UID_CACHE_TTL_MS else EMPTY_UID_CACHE_TTL_MS
        uidCache[uid] = CachedUidPackages(resolved, now + ttl)
        return resolved
    }

    private fun queryPackagesForUid(uid: Int): Array<String> {
        try {
            val pm = resolveSystemPackageManager()
            val pkgs = pm?.getPackagesForUid(uid)
            if (pkgs != null && pkgs.isNotEmpty()) {
                return pkgs
            }
        } catch (_: Throwable) {
            // Fall through to PackageManagerInternal
        }

        try {
            val localServices = HookReflect.findClassOrNull("com.android.server.LocalServices", null)
            val pmInternalClass = HookReflect.findClassOrNull(
                "android.content.pm.PackageManagerInternal",
                null,
            )
            if (localServices != null && pmInternalClass != null) {
                val pmInternal = HookReflect.callStaticMethodExactOrNull(
                    localServices,
                    "getService",
                    arrayOf(Class::class.java),
                    pmInternalClass,
                )
                if (pmInternal != null) {
                    val pkgName = HookReflect.callMethodExactOrNull(
                        pmInternal,
                        "getNameForUid",
                        arrayOf(Int::class.javaPrimitiveType!!),
                        uid,
                    ) as? String
                    if (!pkgName.isNullOrEmpty()) {
                        return arrayOf(pkgName.substringBefore(':'))
                    }
                }
            }
        } catch (_: Throwable) {
            // Safe fallback
        }

        return emptyArray()
    }

    internal fun resolveSystemPackageManager(): PackageManager? {
        return try {
            val atClass = HookReflect.findClassOrNull("android.app.ActivityThread", null)
                ?: return null
            val app = HookReflect.callStaticZeroArgOrNull(atClass, "currentApplication") as? Application
            val appPm = app?.packageManager
            if (appPm != null) return appPm

            val thread = HookReflect.callStaticZeroArgOrNull(atClass, "currentActivityThread")
                ?: return null
            val sysCtx = HookReflect.callZeroArgOrNull(thread, "getSystemContext") as? Context
            sysCtx?.packageManager
        } catch (_: Throwable) {
            null
        }
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
