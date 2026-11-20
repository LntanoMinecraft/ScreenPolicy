package com.example.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import com.example.data.PolicyConstants
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-boot-IPC dual-track telemetry logger for system_server and SystemUI.
 *
 * - Track 1: Immediately writes concise markers to XposedBridge.log (memory-safe, zero Binder IPC).
 * - Track 2: Stores startup mount statuses and timing summaries purely in memory (< 1ms) during
 *   boot, then flushes them asynchronously to TelemetryContentProvider ONLY after a 28-second
 *   boot-stabilization window on a single MIN_PRIORITY daemon thread.
 * - Direct-boot / locked-user safe: checks UserManager.isUserUnlocked and retries at most once
 *   (after 20s) before failing silently — never blocks system threads and never loops infinitely.
 */
object DualTrackLogger {
    private const val ERROR_THROTTLE_MS = 60_000L
    private const val HIT_THROTTLE_MS = 5_000L
    private const val MAX_PENDING_REPORTS = 48
    private const val BOOT_IPC_DELAY_MS = 28_000L
    private const val SINGLE_RETRY_DELAY_MS = 20_000L

    private val providerUri: Uri by lazy { Uri.parse(PolicyConstants.PROVIDER_URI_STRING) }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ScreenPolicy-Telemetry").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private data class HookStatusRecord(
        val scope: String,
        val hookKey: String,
        val success: Boolean,
        val detail: String,
        val timestampMs: Long,
    )

    private data class PendingCall(
        val method: String,
        val extras: Bundle,
    )

    private val hookStatusMemory = ConcurrentHashMap<String, HookStatusRecord>()
    private val errorCooldowns = ConcurrentHashMap<String, Long>()
    private val hitCooldowns = ConcurrentHashMap<String, Long>()
    private val pendingQueue = ConcurrentLinkedQueue<PendingCall>()

    private val initialFlushScheduled = AtomicBoolean(false)
    private val bootIpcReady = AtomicBoolean(false)
    private val singleRetryUsed = AtomicBoolean(false)

    @Volatile
    private var appContextRef: Context? = null

    fun attachContext(context: Context?) {
        if (context == null) return
        appContextRef = context.applicationContext ?: context
        if (bootIpcReady.get() && !pendingQueue.isEmpty()) {
            scheduleImmediateFlush()
        }
    }

    /**
     * Records hook mount result purely in memory (< 1ms, zero Binder IPC).
     */
    fun recordHookStatus(scope: String, hookKey: String, success: Boolean, detail: String) {
        val nowMs = System.currentTimeMillis()
        hookStatusMemory["$scope:$hookKey"] = HookStatusRecord(
            scope = scope,
            hookKey = hookKey,
            success = success,
            detail = detail,
            timestampMs = nowMs,
        )
        safeXposedLog("[$scope][$hookKey] mount=${if (success) "OK" else "FAIL"}: $detail")

        val extras = Bundle().apply {
            putString(PolicyConstants.EXTRA_SCOPE, scope)
            putString(PolicyConstants.EXTRA_HOOK_KEY, hookKey)
            putBoolean(PolicyConstants.EXTRA_SUCCESS, success)
            putString(PolicyConstants.EXTRA_DETAIL, detail)
            putLong(PolicyConstants.EXTRA_TIMESTAMP, nowMs)
        }
        enqueueOrFlush(PolicyConstants.METHOD_REPORT_STATUS, extras)
    }

    /**
     * Records startup mount timing summary (e.g. "SystemUI hook ready in 4ms (deferred)").
     * Enqueued after individual hook statuses so it lands at the top of the app's Logs list.
     */
    fun recordStartupSummary(scope: String, message: String) {
        safeXposedLog("[$scope][Startup] $message")
        val extras = Bundle().apply {
            putString(PolicyConstants.EXTRA_SCOPE, scope)
            putString(PolicyConstants.EXTRA_LEVEL, "INFO")
            putString(PolicyConstants.EXTRA_TAG, "Startup")
            putString(PolicyConstants.EXTRA_MESSAGE, message)
            putLong(PolicyConstants.EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        enqueueOrFlush(PolicyConstants.METHOD_REPORT_LOG, extras)
    }

    /**
     * Schedules the first ContentProvider flush after boot stabilization (>= 25-30s).
     * Guarantees zero ContentProvider IPC during Android's 10-second process start window.
     */
    fun scheduleDeferredBootFlush(delayMs: Long = BOOT_IPC_DELAY_MS) {
        val effectiveDelay = delayMs.coerceAtLeast(15_000L)
        if (!initialFlushScheduled.compareAndSet(false, true)) return
        try {
            scheduler.schedule(
                {
                    bootIpcReady.set(true)
                    flushAllSafely(allowRetry = true)
                },
                effectiveDelay,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: Throwable) {
            // Never disrupt system process if scheduler rejects
        }
    }

    fun logError(scope: String, tag: String, errorKey: String, throwable: Throwable) {
        val now = SystemClock.elapsedRealtime()
        val dedupeKey = "$scope:$tag:$errorKey"
        val prev = errorCooldowns[dedupeKey]
        if (prev != null && now - prev < ERROR_THROTTLE_MS) return
        errorCooldowns[dedupeKey] = now

        val msg = "${throwable.javaClass.simpleName}: ${throwable.message ?: errorKey}"
        safeXposedLog("[$scope][$tag] ERROR ($errorKey): $msg")

        val extras = Bundle().apply {
            putString(PolicyConstants.EXTRA_SCOPE, scope)
            putString(PolicyConstants.EXTRA_LEVEL, "ERROR")
            putString(PolicyConstants.EXTRA_TAG, tag)
            putString(PolicyConstants.EXTRA_MESSAGE, "$errorKey -> $msg")
            putLong(PolicyConstants.EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        enqueueOrFlush(PolicyConstants.METHOD_REPORT_LOG, extras)
    }

    fun logHit(scope: String, tag: String, packageName: String, action: String) {
        if (!PolicyCache.isVerboseEnabled()) return
        val now = SystemClock.elapsedRealtime()
        val key = "$scope:$tag:$packageName:$action"
        val prev = hitCooldowns[key]
        if (prev != null && now - prev < HIT_THROTTLE_MS) return
        hitCooldowns[key] = now

        val msg = "$packageName -> $action"
        safeXposedLog("[$scope][$tag] HIT: $msg")

        val extras = Bundle().apply {
            putString(PolicyConstants.EXTRA_SCOPE, scope)
            putString(PolicyConstants.EXTRA_LEVEL, "HIT")
            putString(PolicyConstants.EXTRA_TAG, tag)
            putString(PolicyConstants.EXTRA_MESSAGE, msg)
            putLong(PolicyConstants.EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        enqueueOrFlush(PolicyConstants.METHOD_REPORT_LOG, extras)
    }

    fun snapshotHookStatusCount(): Int = hookStatusMemory.size

    private fun enqueueOrFlush(method: String, extras: Bundle) {
        if (pendingQueue.size >= MAX_PENDING_REPORTS) {
            pendingQueue.poll()
        }
        pendingQueue.offer(PendingCall(method, extras))

        if (bootIpcReady.get()) {
            scheduleImmediateFlush()
        }
    }

    private fun scheduleImmediateFlush() {
        try {
            scheduler.execute {
                flushAllSafely(allowRetry = false)
            }
        } catch (_: Throwable) {
        }
    }

    private fun flushAllSafely(allowRetry: Boolean) {
        try {
            val ctx = resolveContext()
            if (ctx == null || !isUserUnlocked(ctx)) {
                if (allowRetry) scheduleSingleRetry()
                return
            }

            val drained = ArrayList<PendingCall>()
            while (true) {
                val item = pendingQueue.poll() ?: break
                drained.add(item)
            }
            if (drained.isEmpty()) return

            var anyFailed = false
            for (item in drained) {
                val ok = dispatchToProvider(ctx, item.method, item.extras)
                if (!ok) {
                    anyFailed = true
                    if (allowRetry && !singleRetryUsed.get() && pendingQueue.size < MAX_PENDING_REPORTS) {
                        pendingQueue.offer(item)
                    }
                }
            }

            if (anyFailed && allowRetry) {
                scheduleSingleRetry()
            }
        } catch (_: Throwable) {
            // Completely isolate telemetry failures
        }
    }

    private fun scheduleSingleRetry() {
        if (!singleRetryUsed.compareAndSet(false, true)) return
        try {
            scheduler.schedule(
                { flushAllSafely(allowRetry = false) },
                SINGLE_RETRY_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: Throwable) {
        }
    }

    private fun isUserUnlocked(ctx: Context): Boolean {
        return try {
            val um = ctx.getSystemService(UserManager::class.java)
            um?.isUserUnlocked ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun dispatchToProvider(ctx: Context, method: String, extras: Bundle): Boolean {
        return try {
            val result = ctx.contentResolver.call(providerUri, method, null, extras)
            result != null
        } catch (_: Throwable) {
            // App process may not be ready yet during direct boot; fail silently
            false
        }
    }

    private fun resolveContext(): Context? {
        appContextRef?.let { return it }
        return try {
            val atClass = XposedHelpers.findClassIfExists("android.app.ActivityThread", null)
                ?: return null
            val app = XposedHelpers.callStaticMethod(atClass, "currentApplication") as? Context
            if (app != null) {
                appContextRef = app
                app
            } else {
                val thread = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
                val sysCtx = thread?.let {
                    XposedHelpers.callMethod(it, "getSystemContext") as? Context
                }
                if (sysCtx != null) {
                    appContextRef = sysCtx
                }
                sysCtx
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeXposedLog(line: String) {
        try {
            XposedBridge.log("[ScreenPolicy] $line")
        } catch (_: Throwable) {
        }
    }
}
