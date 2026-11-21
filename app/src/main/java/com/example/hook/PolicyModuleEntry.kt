package com.example.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.data.PolicyConstants
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Modern Xposed API 102 module entry point (declared in META-INF/xposed/java_init.list).
 *
 * Bootloop-proof startup contract:
 * 1. Public no-arg constructor performs zero initialization; lifecycle begins at onModuleLoaded().
 * 2. Static scope hard-locked via META-INF/xposed/scope.list (`system` + `com.android.systemui`,
 *    `staticScope=true`, `exceptionMode=protective`) plus explicit package guard in code.
 * 3. Zero UI/Compose/AndroidX references so ART loads only a minimal pure-Hook class graph.
 * 4. `onSystemServerStarting`: dispatches immediately onto a MIN_PRIORITY daemon background thread
 *    so system_server main thread overhead is < 1ms; telemetry IPC deferred by 28 seconds.
 * 5. `onPackageReady` (`com.android.systemui`): strictly deferred by 12,000ms (completely clearing
 *    Android's 10-second PROC_START_TIMEOUT window) and executed off the main thread; telemetry IPC
 *    deferred to ~28s total uptime.
 */
class PolicyModuleEntry : XposedModule() {

    companion object {
        private const val SYSTEM_UI_MOUNT_DELAY_MS = 12_000L
        private const val SYSTEM_SERVER_TELEMETRY_DELAY_MS = 28_000L
        private const val SYSTEM_UI_TELEMETRY_DELAY_AFTER_MOUNT_MS = 16_000L
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        DualTrackLogger.attachModule(this)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        try {
            scheduleSystemFrameworkMount(param.classLoader)
        } catch (t: Throwable) {
            DualTrackLogger.logError(
                PolicyConstants.SCOPE_SYSTEM,
                "Entry",
                "onSystemServerStarting",
                t,
            )
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        try {
            if (param.packageName != PolicyConstants.SCOPE_SYSTEMUI) {
                return
            }
            scheduleDeferredSystemUiMount(param.classLoader)
        } catch (t: Throwable) {
            DualTrackLogger.logError(
                param.packageName,
                "Entry",
                "onPackageReady",
                t,
            )
        }
    }

    /**
     * Spawns a single MIN_PRIORITY daemon thread immediately (< 1ms on system_server thread)
     * so system_server startup is never blocked by reflection, prefs init, or ContentProvider IPC.
     */
    private fun scheduleSystemFrameworkMount(classLoader: ClassLoader) {
        val module = this
        val worker = Thread(
            {
                val mountStartMs = SystemClock.elapsedRealtime()
                try {
                    PolicyCache.init(module, PolicyConstants.SCOPE_SYSTEM)

                    val pkgLayerResult = try {
                        AudioCaptureHook.installPackageInfoLayer(module, classLoader)
                    } catch (t: Throwable) {
                        DualTrackLogger.logError(
                            PolicyConstants.SCOPE_SYSTEM,
                            PolicyConstants.HOOK_AUDIO_CAPTURE,
                            "pkg_layer_crash",
                            t,
                        )
                        0 to "PkgLayer:${t.javaClass.simpleName}"
                    }

                    runIsolated(PolicyConstants.SCOPE_SYSTEM, PolicyConstants.HOOK_SCREENSHOT) {
                        ScreenshotHook.install(module, classLoader)
                    }
                    runIsolated(PolicyConstants.SCOPE_SYSTEM, PolicyConstants.HOOK_SCREEN_RECORD) {
                        ScreenRecordHook.install(module, classLoader)
                    }
                    runIsolated(PolicyConstants.SCOPE_SYSTEM, PolicyConstants.HOOK_AUDIO_CAPTURE) {
                        val audioLayerResult = AudioCaptureHook.installAudioServiceLayer(module, classLoader)
                        AudioCaptureHook.combineResult(pkgLayerResult, audioLayerResult)
                    }
                    runIsolated(PolicyConstants.SCOPE_SYSTEM, PolicyConstants.HOOK_CAPTURE_DETECT) {
                        CaptureDetectHook.install(module, classLoader)
                    }

                    // Proactively clear stale audioserver capture-policy cache for allowlisted UIDs
                    // (immediate best-effort + post-boot deferred pass once PM/AudioService are up).
                    AudioCaptureHook.scheduleActiveCacheRefresh(
                        classLoader = classLoader,
                        delayMs = 0L,
                        reason = "boot_init",
                    )
                    AudioCaptureHook.scheduleActiveCacheRefresh(
                        classLoader = classLoader,
                        delayMs = 15_000L,
                        reason = "boot_settled",
                    )

                    val elapsedMs = (SystemClock.elapsedRealtime() - mountStartMs).coerceAtLeast(0L)
                    DualTrackLogger.recordStartupSummary(
                        scope = PolicyConstants.SCOPE_SYSTEM,
                        message = "system_server hooks ready in ${elapsedMs}ms (API 102)",
                    )
                    DualTrackLogger.scheduleDeferredBootFlush(SYSTEM_SERVER_TELEMETRY_DELAY_MS)
                } catch (t: Throwable) {
                    DualTrackLogger.logError(
                        PolicyConstants.SCOPE_SYSTEM,
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
        val module = this
        val worker = Thread(
            {
                val mountStartMs = SystemClock.elapsedRealtime()
                try {
                    PolicyCache.init(module, PolicyConstants.SCOPE_SYSTEMUI)
                    runIsolated(PolicyConstants.SCOPE_SYSTEMUI, PolicyConstants.HOOK_GAME_DASHBOARD) {
                        GameDashboardRecordHook.install(module, classLoader)
                    }
                    val elapsedMs = (SystemClock.elapsedRealtime() - mountStartMs).coerceAtLeast(0L)
                    DualTrackLogger.recordStartupSummary(
                        scope = PolicyConstants.SCOPE_SYSTEMUI,
                        message = "SystemUI hook ready in ${elapsedMs}ms (deferred, API 102)",
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
