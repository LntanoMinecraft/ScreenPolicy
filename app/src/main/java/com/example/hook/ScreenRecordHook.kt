package com.example.hook

import android.view.WindowManager
import com.example.data.PolicyConstants
import io.github.libxposed.api.XposedModule

/**
 * Android 17 (API 37) system_server hook for per-window screen recording relief (allowScreenRecord).
 *
 * Strictly per-window allowlist-gated via the single 0-arg WindowState.isSecureLocked() method
 * using libxposed API 102 interceptors and ART deoptimization.
 */
object ScreenRecordHook {
    private const val TAG = "ScreenRecord"
    private const val CLASS_WINDOW_STATE = "com.android.server.wm.WindowState"

    fun install(module: XposedModule, classLoader: ClassLoader): Pair<Boolean, String> {
        return try {
            val windowStateClass = HookReflect.findClassOrNull(CLASS_WINDOW_STATE, classLoader)
                ?: return false to "Class not found: $CLASS_WINDOW_STATE"

            val targetMethod = windowStateClass.declaredMethods.firstOrNull { method ->
                method.name == "isSecureLocked" &&
                    method.parameterCount == 0 &&
                    method.returnType == Boolean::class.javaPrimitiveType
            } ?: HookReflect.findMethodExactOrNull(windowStateClass, "isSecureLocked")
                ?: return false to "WindowState.isSecureLocked() not found"

            runCatching { module.deoptimize(targetMethod) }

            module.hook(targetMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (result == true) {
                        val pkg = extractWindowPackage(chain.thisObject)
                        if (pkg != null && PolicyCache.isScreenRecordAllowed(pkg)) {
                            DualTrackLogger.logHit(
                                scope = PolicyConstants.SCOPE_SYSTEM,
                                tag = TAG,
                                packageName = pkg,
                                action = "isSecureLocked=false",
                            )
                            return@intercept false
                        }
                    }
                } catch (t: Throwable) {
                    DualTrackLogger.logError(
                        PolicyConstants.SCOPE_SYSTEM,
                        TAG,
                        "isSecureLocked_cb",
                        t,
                    )
                }
                result
            }

            true to "WindowState.isSecureLocked() (1)"
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install", t)
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun extractWindowPackage(windowState: Any?): String? {
        if (windowState == null) return null
        return try {
            val owning = HookReflect.callZeroArgOrNull(windowState, "getOwningPackage") as? String
            if (!owning.isNullOrEmpty()) return owning
            val attrs = HookReflect.getObjectFieldOrNull(windowState, "mAttrs") as? WindowManager.LayoutParams
            attrs?.packageName
        } catch (_: Throwable) {
            null
        }
    }
}
