package com.example.hook

import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import com.example.data.PolicyConstants
import com.example.data.PolicyJsonCodec
import com.example.model.AppPolicy
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-frequency, zero-disk-IO runtime policy cache for system_server and SystemUI.
 * Hot paths (e.g., WindowState.isSecureLocked at 120Hz) only perform an atomic timestamp check
 * and a lock-free ConcurrentHashMap lookup. Disk checks are throttled to >= 1000ms and only
 * reload when XSharedPreferences.hasFileChanged() reports a modification.
 */
object PolicyCache {
    private const val RELOAD_INTERVAL_MS = 1000L
    private const val UID_CACHE_TTL_MS = 5000L

    private val policyMap = ConcurrentHashMap<String, AppPolicy>()
    private val lastCheckElapsedMs = AtomicLong(0L)
    private val reloading = AtomicBoolean(false)

    @Volatile
    private var xPrefs: XSharedPreferences? = null

    @Volatile
    var fixGameDashboardRecord: Boolean = true
        private set

    @Volatile
    var verboseLogging: Boolean = false
        private set

    private data class CachedUidPackages(
        val packages: Array<String>,
        val expiresAtMs: Long,
    )

    private val uidCache = ConcurrentHashMap<Int, CachedUidPackages>()

    fun init(scope: String) {
        try {
            val prefs = XSharedPreferences(
                PolicyConstants.MODULE_PACKAGE,
                PolicyConstants.POLICY_PREFS_NAME,
            )
            prefs.makeWorldReadable()
            xPrefs = prefs
            reloadNowLocked(prefs)
        } catch (t: Throwable) {
            DualTrackLogger.logError(scope, "PolicyCache", "init_failed", t)
        }
    }

    private fun maybeRefresh() {
        val prefs = xPrefs ?: return
        val now = SystemClock.elapsedRealtime()
        val last = lastCheckElapsedMs.get()
        if (now - last < RELOAD_INTERVAL_MS) return
        if (!lastCheckElapsedMs.compareAndSet(last, now)) return
        if (!reloading.compareAndSet(false, true)) return
        try {
            if (prefs.hasFileChanged()) {
                prefs.reload()
                reloadNowLocked(prefs)
            }
        } catch (_: Throwable) {
            // Never throw on hot path
        } finally {
            reloading.set(false)
        }
    }

    private fun reloadNowLocked(prefs: XSharedPreferences) {
        val raw = prefs.getString(PolicyConstants.PREF_KEY_CONFIG_JSON, null)
        val config = PolicyJsonCodec.decode(raw)
        fixGameDashboardRecord = prefs.getBoolean(
            PolicyConstants.PREF_KEY_FIX_GAME_DASHBOARD,
            config.fixGameDashboardRecord,
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
        maybeRefresh()
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
        maybeRefresh()
        return fixGameDashboardRecord
    }

    fun isVerboseEnabled(): Boolean {
        maybeRefresh()
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
            val atClass = XposedHelpers.findClassIfExists("android.app.ActivityThread", null)
                ?: return emptyArray()
            val app = XposedHelpers.callStaticMethod(atClass, "currentApplication") as? Application
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
        maybeRefresh()
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
