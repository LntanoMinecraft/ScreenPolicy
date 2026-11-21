package com.example.hook

import android.os.Binder
import com.example.data.PolicyConstants
import io.github.libxposed.api.XposedModule

/**
 * Android 17 (API 37) system_server hook suppressing screenshot (ScreenCaptureCallback) and
 * screen recording (ScreenRecordingCallback) observer registration for allowlisted packages.
 *
 * Uses libxposed API 102 interceptors on targeted declaredMethods.
 */
object CaptureDetectHook {
    private const val TAG = "CaptureDetect"
    private const val CLASS_ATMS = "com.android.server.wm.ActivityTaskManagerService"
    private const val CLASS_WMS = "com.android.server.wm.WindowManagerService"
    private const val CLASS_RECORDING_CONTROLLER = "com.android.server.wm.ScreenRecordingCallbackController"

    fun install(module: XposedModule, classLoader: ClassLoader): Pair<Boolean, String> {
        var screenshotCount = 0
        var recordingCount = 0
        val errors = mutableListOf<String>()

        // 1. Suppress ScreenCaptureCallback registration in ActivityTaskManagerService (exact 2-arg)
        try {
            val atmsClass = HookReflect.findClassOrNull(CLASS_ATMS, classLoader)
            if (atmsClass != null) {
                val targets = atmsClass.declaredMethods.filter { method ->
                    method.name == "registerScreenCaptureObserver" &&
                        method.parameterCount == 2
                }.take(1)

                for (method in targets) {
                    module.hook(method).intercept { chain ->
                        try {
                            val matchedPkg = PolicyCache.findAllowlistedPackageForUid(
                                Binder.getCallingUid(),
                            ) { it.blockCaptureDetect }
                            if (matchedPkg != null) {
                                DualTrackLogger.logHit(
                                    scope = PolicyConstants.SCOPE_SYSTEM,
                                    tag = TAG,
                                    packageName = matchedPkg,
                                    action = "blocked registerScreenCaptureObserver",
                                )
                                return@intercept null
                            }
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_SYSTEM,
                                TAG,
                                "registerScreenCaptureObserver_cb",
                                t,
                            )
                        }
                        chain.proceed()
                    }
                    screenshotCount++
                }
            } else {
                errors.add("ATMS:ClassNotFound")
            }
        } catch (t: Throwable) {
            errors.add("ATMS:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_screenshot", t)
        }

        // 2. Suppress ScreenRecordingCallback registration in WindowManagerService / Controller
        try {
            val wmsClass = HookReflect.findClassOrNull(CLASS_WMS, classLoader)
            if (wmsClass != null) {
                val wmsTargets = wmsClass.declaredMethods.filter { method ->
                    method.name == "registerScreenRecordingCallback" &&
                        method.parameterCount == 1 &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.take(1)
                for (method in wmsTargets) {
                    installRecordingBlocker(module, method, "WMS.registerScreenRecordingCallback")
                    recordingCount++
                }
            }

            val controllerClass = HookReflect.findClassOrNull(CLASS_RECORDING_CONTROLLER, classLoader)
            if (controllerClass != null) {
                val ctrlTargets = controllerClass.declaredMethods.filter { method ->
                    method.name == "register" &&
                        method.parameterCount == 1 &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.take(1)
                for (method in ctrlTargets) {
                    installRecordingBlocker(module, method, "ScreenRecordingCallbackController.register")
                    recordingCount++
                }
            }
        } catch (t: Throwable) {
            errors.add("RecordingCb:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_recording", t)
        }

        val ok = screenshotCount > 0 || recordingCount > 0
        val detail = buildString {
            append("ScreenshotCb=").append(screenshotCount)
            append(", RecordingCb=").append(recordingCount)
            if (errors.isNotEmpty()) {
                append(" (").append(errors.joinToString("; ")).append(")")
            }
        }
        return ok to detail
    }

    private fun installRecordingBlocker(
        module: XposedModule,
        method: java.lang.reflect.Method,
        actionLabel: String,
    ) {
        module.hook(method).intercept { chain ->
            try {
                val matchedPkg = PolicyCache.findAllowlistedPackageForUid(
                    Binder.getCallingUid(),
                ) { it.blockCaptureDetect }
                if (matchedPkg != null) {
                    DualTrackLogger.logHit(
                        scope = PolicyConstants.SCOPE_SYSTEM,
                        tag = TAG,
                        packageName = matchedPkg,
                        action = "blocked $actionLabel",
                    )
                    return@intercept false
                }
            } catch (t: Throwable) {
                DualTrackLogger.logError(
                    PolicyConstants.SCOPE_SYSTEM,
                    TAG,
                    "registerRecording_cb",
                    t,
                )
            }
            chain.proceed()
        }
    }
}
