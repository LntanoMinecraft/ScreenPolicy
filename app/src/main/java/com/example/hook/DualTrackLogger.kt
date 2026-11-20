package com.example.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.example.data.PolicyConstants
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Dual-track logger:
 * - Track 1: Always writes key events to XposedBridge.log for LSPosed Manager inspection.
 * - Track 2: Asynchronously reports startup hook statuses, deduplicated/rate-limited runtime
 *   exceptions, and (only when verboseLogging is enabled) allowlist hits to TelemetryContentProvider.
 *
 * Every path is strictly guarded with try-catch(Throwable) so telemetry can never disrupt system processes.
 */
object DualTrackLogger {
    private const val ERROR_THROTTLE_MS = 60_000L
    private const val HIT_THROTTLE_MS = 5_000L
    private const val MAX_PENDING_REPORTS = 32

    private val providerUri: Uri by lazy { Uri.parse(PolicyConstants.PROVIDER_URI_STRING) }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScreenPolicy-Telemetry").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val errorCooldowns = ConcurrentHashMap<String, Long>()
    private val hitCooldowns = ConcurrentHashMap<String, Long>()
    private val pendingQueue = ConcurrentLinkedQueue<PendingCall>()

    @Volatile
    private var appContextRef: Context? = null

    private data class PendingCall(
        val method: String,
        val extras: Bundle,
    )

    fun attachContext(context: Context?) {
        if (context == null) return
        appContextRef = context.applicationContext ?: context
        flushPendingAsync()
    }

    fun reportHookStatus(scope: String, hookKey: String, success: Boolean, detail: String) {
        safeXposedLog("[$scope][$hookKey] mount=${if (success) "OK" else "FAIL"}: $detail")
        val extras = Bundle().apply {
            putString(PolicyConstants.EXTRA_SCOPE, scope)
            putString(PolicyConstants.EXTRA_HOOK_KEY, hookKey)
            putBoolean(PolicyConstants.EXTRA_SUCCESS, success)
            putString(PolicyConstants.EXTRA_DETAIL, detail)
            putLong(PolicyConstants.EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        enqueueProviderCall(PolicyConstants.METHOD_REPORT_STATUS, extras)
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
        enqueueProviderCall(PolicyConstants.METHOD_REPORT_LOG, extras)
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
        enqueueProviderCall(PolicyConstants.METHOD_REPORT_LOG, extras)
    }

    private fun enqueueProviderCall(method: String, extras: Bundle) {
        try {
            executor.execute {
                try {
                    val ctx = resolveContext()
                    if (ctx == null) {
                        if (pendingQueue.size < MAX_PENDING_REPORTS) {
                            pendingQueue.offer(PendingCall(method, extras))
                        }
                        return@execute
                    }
                    flushPendingLocked(ctx)
                    dispatchToProvider(ctx, method, extras)
                } catch (_: Throwable) {
                    // Isolate telemetry failures completely
                }
            }
        } catch (_: Throwable) {
            // Executor rejected or unavailable
        }
    }

    private fun flushPendingAsync() {
        if (pendingQueue.isEmpty()) return
        try {
            executor.execute {
                val ctx = resolveContext() ?: return@execute
                flushPendingLocked(ctx)
            }
        } catch (_: Throwable) {
        }
    }

    private fun flushPendingLocked(ctx: Context) {
        while (true) {
            val item = pendingQueue.poll() ?: break
            dispatchToProvider(ctx, item.method, item.extras)
        }
    }

    private fun dispatchToProvider(ctx: Context, method: String, extras: Bundle) {
        try {
            ctx.contentResolver.call(providerUri, method, null, extras)
        } catch (_: Throwable) {
            // Module app may not be unlocked yet during direct boot; fail silently
        }
    }

    private fun resolveContext(): Context? {
        appContextRef?.let { return it }
        return try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
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
