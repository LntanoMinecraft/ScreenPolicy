package com.example.hook

import android.view.WindowManager
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Android 17 (API 37) system_server hook for per-window screen recording relief (allowScreenRecord).
 *
 * Strictly per-window allowlist-gated via WindowState.isSecureLocked() only.
 * Display-wide hooks (e.g. DisplayContent.hasSecureWindowOnScreen) are intentionally omitted
 * so split-screen or freeform windows belonging to non-allowlisted banking/payment apps are
 * never collaterally exposed.
 */
object ScreenRecordHook {
    private const val TAG = "ScreenRecord"
    private const val CLASS_WINDOW_STATE = "com.android.server.wm.WindowState"

    fun install(classLoader: ClassLoader): Pair<Boolean, String> {
        return try {
            val windowStateClass = XposedHelpers.findClass(CLASS_WINDOW_STATE, classLoader)
            val hooks = XposedBridge.hookAllMethods(
                windowStateClass,
                "isSecureLocked",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.result != true) return
                            val pkg = extractWindowPackage(param.thisObject) ?: return
                            if (PolicyCache.isScreenRecordAllowed(pkg)) {
                                param.result = false
                                DualTrackLogger.logHit(
                                    scope = PolicyConstants.SCOPE_ANDROID,
                                    tag = TAG,
                                    packageName = pkg,
                                    action = "isSecureLocked=false",
                                )
                            }
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_ANDROID,
                                TAG,
                                "isSecureLocked_cb",
                                t,
                            )
                        }
                    }
                },
            )

            if (hooks.isNotEmpty()) {
                true to "WindowState.isSecureLocked (${hooks.size})"
            } else {
                false to "WindowState.isSecureLocked method not found"
            }
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install", t)
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun extractWindowPackage(windowState: Any?): String? {
        if (windowState == null) return null
        return try {
            val owning = XposedHelpers.callMethod(windowState, "getOwningPackage") as? String
            if (!owning.isNullOrEmpty()) return owning
            val attrs = XposedHelpers.getObjectField(windowState, "mAttrs") as? WindowManager.LayoutParams
            attrs?.packageName
        } catch (_: Throwable) {
            null
        }
    }
}
