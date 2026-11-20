package com.example.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.data.PolicyConstants
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module entry point (registered in assets/xposed_init).
 *
 * Bootloop-proof startup contract:
 * 1. Scope hard-guard: executes ONLY in `android` (system_server) and `com.android.systemui`.
 * 2. Zero UI/Compose/AndroidX references so ART loads only a tiny pure-Hook class graph.
 * 3. `handleLoadPackage` main-thread budget < 10ms (typically < 1ms): zero disk IO and zero
 *    ContentProvider IPC on the calling thread.
 * 4. `com.android.systemui`: strictly deferred by 12,000ms (completely clearing Android's 10-second
 *    PROC_START_TIMEOUT window) and executed off the main thread; telemetry IPC deferred to ~28s.
 * 5. `android` (system_server): dispatches immediately onto a MIN_PRIORITY daemon background thread
 *    so system_server main thread overhead is near zero; telemetry IPC deferred by 28 seconds.
 */
class PolicyModuleEntry : IXposedHookLoadPackage {

    companion object {
        private const val SYSTEM_UI_MOUNT_DELAY_MS = 12_000L
        private const val SYSTEM_SERVER_TELEMETRY_DELAY_MS = 28_000L
        private const val SYSTEM_UI_TELEMETRY_DELAY_AFTER_MOUNT_MS = 16_000L
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when (lpparam.packageName) {
                PolicyConstants.SCOPE_ANDROID -> {
                    if (lpparam.processName == PolicyConstants.SCOPE_ANDROID) {
                        scheduleSystemFrameworkMount(lpparam.classLoader)
                    }
                }

                PolicyConstants.SCOPE_SYSTEMUI -> {
                    scheduleDeferredSystemUiMount(lpparam.classLoader)
                }

                else -> {
                    // Hard guard: never execute in any non-system process
                    return
                }
            }
        } catch (t: Throwable) {
            DualTrackLogger.logError(
                lpparam.packageName ?: "unknown",
                "Entry",
                "handleLoadPackage",
                t,
            )
        }
    }

    /**
     * Spawns a single MIN_PRIORITY daemon thread immediately (< 1ms on handleLoadPackage thread)
     * so system_server startup is never blocked by disk IO, reflection, or ContentProvider IPC.
     */
    private fun scheduleSystemFrameworkMount(classLoader: ClassLoader) {
        val worker = Thread(
            {
                val mountStartMs = SystemClock.elapsedRealtime()
                try {
                    PolicyCache.init(PolicyConstants.SCOPE_ANDROID)

                    // Mount PackageInfoUtils early on the background thread, then the audio service layer
                    val pkgLayerResult = try {
                        AudioCaptureHook.installPackageInfoLayer(classLoader)
                    } catch (t: Throwable) {
                        DualTrackLogger.logError(
                            PolicyConstants.SCOPE_ANDROID,
                            PolicyConstants.HOOK_AUDIO_CAPTURE,
                            "pkg_layer_crash",
                            t,
                        )
                        0 to "PkgLayer:${t.javaClass.simpleName}"
                    }

                    // Mount remaining non-boot-critical framework hooks in full isolation
                    runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_SCREENSHOT) {
                        ScreenshotHook.install(classLoader)
                    }
                    runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_SCREEN_RECORD) {
                        ScreenRecordHook.install(classLoader)
                    }
                    runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_AUDIO_CAPTURE) {
                        val audioLayerResult = AudioCaptureHook.installAudioServiceLayer(classLoader)
                        AudioCaptureHook.combineResult(pkgLayerResult, audioLayerResult)
                    }
                    runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_CAPTURE_DETECT) {
                        CaptureDetectHook.install(classLoader)
                    }

                    val elapsedMs = (SystemClock.elapsedRealtime() - mountStartMs).coerceAtLeast(0L)
                    DualTrackLogger.recordStartupSummary(
                        scope = PolicyConstants.SCOPE_ANDROID,
                        message = "system_server hooks ready in ${elapsedMs}ms",
                    )
                    DualTrackLogger.scheduleDeferredBootFlush(SYSTEM_SERVER_TELEMETRY_DELAY_MS)
                } catch (t: Throwable) {
                    DualTrackLogger.logError(
                        PolicyConstants.SCOPE_ANDROID,
                        "Entry",
                        "sys_mount_worker",
                        t,
                    )
                }
            },
            "ScreenPolicy-SysInit",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        worker.start()
    }

    /**
     * Strictly defers SystemUI hook mounting by 12 seconds so SystemUI completes cold start
     * well inside Android's 10-second PROC_START_TIMEOUT limit with zero extra overhead.
     */
    private fun scheduleDeferredSystemUiMount(classLoader: ClassLoader) {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null) {
            Handler(mainLooper).postDelayed(
                { launchSystemUiWorker(classLoader) },
                SYSTEM_UI_MOUNT_DELAY_MS,
            )
        } else {
            val fallback = Thread(
                {
                    try {
                        Thread.sleep(SYSTEM_UI_MOUNT_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    launchSystemUiWorker(classLoader)
                },
                "ScreenPolicy-SysUiDelay",
            ).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            fallback.start()
        }
    }

    private fun launchSystemUiWorker(classLoader: ClassLoader) {
        val worker = Thread(
            {
                val mountStartMs = SystemClock.elapsedRealtime()
                try {
                    PolicyCache.init(PolicyConstants.SCOPE_SYSTEMUI)
                    runIsolated(PolicyConstants.SCOPE_SYSTEMUI, PolicyConstants.HOOK_GAME_DASHBOARD) {
                        GameDashboardRecordHook.install(classLoader)
                    }
                    val elapsedMs = (SystemClock.elapsedRealtime() - mountStartMs).coerceAtLeast(0L)
                    DualTrackLogger.recordStartupSummary(
                        scope = PolicyConstants.SCOPE_SYSTEMUI,
                        message = "SystemUI hook ready in ${elapsedMs}ms (deferred)",
                    )
                    DualTrackLogger.scheduleDeferredBootFlush(SYSTEM_UI_TELEMETRY_DELAY_AFTER_MOUNT_MS)
                } catch (t: Throwable) {
                    DualTrackLogger.logError(
                        PolicyConstants.SCOPE_SYSTEMUI,
                        "Entry",
                        "sysui_mount_worker",
                        t,
                    )
                }
            },
            "ScreenPolicy-SysUiInit",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        worker.start()
    }

    private fun runIsolated(
        scope: String,
        hookKey: String,
        installer: () -> Pair<Boolean, String>,
    ) {
        val (ok, detail) = try {
            installer()
        } catch (t: Throwable) {
            DualTrackLogger.logError(scope, hookKey, "mount_crash", t)
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
        DualTrackLogger.recordHookStatus(
            scope = scope,
            hookKey = hookKey,
            success = ok,
            detail = detail,
        )
    }
}
