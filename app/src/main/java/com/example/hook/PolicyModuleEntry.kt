package com.example.hook

import com.example.data.PolicyConstants
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module entry point (registered in assets/xposed_init).
 *
 * Scope security contract:
 * Only executes in `android` (system_server) and `com.android.systemui`.
 * Zero injection into third-party applications or games.
 */
class PolicyModuleEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when (lpparam.packageName) {
                PolicyConstants.SCOPE_ANDROID -> {
                    if (lpparam.processName == PolicyConstants.SCOPE_ANDROID) {
                        installSystemFrameworkHooks(lpparam.classLoader)
                    }
                }

                PolicyConstants.SCOPE_SYSTEMUI -> {
                    installSystemUiHooks(lpparam.classLoader)
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

    private fun installSystemFrameworkHooks(classLoader: ClassLoader) {
        PolicyCache.init(PolicyConstants.SCOPE_ANDROID)

        runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_SCREENSHOT) {
            ScreenshotHook.install(classLoader)
        }
        runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_SCREEN_RECORD) {
            ScreenRecordHook.install(classLoader)
        }
        runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_AUDIO_CAPTURE) {
            AudioCaptureHook.install(classLoader)
        }
        runIsolated(PolicyConstants.SCOPE_ANDROID, PolicyConstants.HOOK_CAPTURE_DETECT) {
            CaptureDetectHook.install(classLoader)
        }
    }

    private fun installSystemUiHooks(classLoader: ClassLoader) {
        PolicyCache.init(PolicyConstants.SCOPE_SYSTEMUI)

        runIsolated(PolicyConstants.SCOPE_SYSTEMUI, PolicyConstants.HOOK_GAME_DASHBOARD) {
            GameDashboardRecordHook.install(classLoader)
        }
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
        DualTrackLogger.reportHookStatus(
            scope = scope,
            hookKey = hookKey,
            success = ok,
            detail = detail,
        )
    }
}
