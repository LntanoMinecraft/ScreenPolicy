package com.example.hook

import android.view.WindowManager
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Android 17 (API 37) system_server hook for per-window screen recording relief (allowScreenRecord).
 *
 * Strictly per-window allowlist-gated via the single 0-arg WindowState.isSecureLocked() method.
 * Uses exact Method lookup + XposedBridge.hookMethod (never broad hookAllMethods).
 */
object ScreenRecordHook {
    private const val TAG = "ScreenRecord"
    private const val CLASS_WINDOW_STATE = "com.android.server.wm.WindowState"

    fun install(classLoader: ClassLoader): Pair<Boolean, String> {
        return try {
            val windowStateClass = XposedHelpers.findClassIfExists(CLASS_WINDOW_STATE, classLoader)
                ?: return false to "Class not found: $CLASS_WINDOW_STATE"

            val targetMethod = windowStateClass.declaredMethods.firstOrNull { method ->
                method.name == "isSecureLocked" &&
                    method.parameterCount == 0 &&
                    method.returnType == Boolean::class.javaPrimitiveType
            } ?: XposedHelpers.findMethodExactIfExists(windowStateClass, "isSecureLocked")
                ?: return false to "WindowState.isSecureLocked() not found"

            XposedBridge.hookMethod(
                targetMethod,
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

            true to "WindowState.isSecureLocked() (1)"
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
