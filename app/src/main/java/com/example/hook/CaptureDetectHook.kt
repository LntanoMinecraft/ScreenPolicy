package com.example.hook

import android.os.Binder
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Android 17 (API 37) system_server hook suppressing screenshot (ScreenCaptureCallback) and
 * screen recording (ScreenRecordingCallback) observer registration for allowlisted packages.
 *
 * Uses targeted declaredMethods filtering + XposedBridge.hookMethod (never broad hookAllMethods).
 */
object CaptureDetectHook {
    private const val TAG = "CaptureDetect"
    private const val CLASS_ATMS = "com.android.server.wm.ActivityTaskManagerService"
    private const val CLASS_WMS = "com.android.server.wm.WindowManagerService"
    private const val CLASS_RECORDING_CONTROLLER = "com.android.server.wm.ScreenRecordingCallbackController"

    fun install(classLoader: ClassLoader): Pair<Boolean, String> {
        var screenshotCount = 0
        var recordingCount = 0
        val errors = mutableListOf<String>()

        // 1. Suppress ScreenCaptureCallback registration in ActivityTaskManagerService (exact 2-arg)
        try {
            val atmsClass = XposedHelpers.findClassIfExists(CLASS_ATMS, classLoader)
            if (atmsClass != null) {
                val targets = atmsClass.declaredMethods.filter { method ->
                    method.name == "registerScreenCaptureObserver" &&
                        method.parameterCount == 2
                }.take(1)

                val callback = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val matchedPkg = PolicyCache.findAllowlistedPackageForUid(
                                Binder.getCallingUid(),
                            ) { it.blockCaptureDetect } ?: return
                            param.result = null
                            DualTrackLogger.logHit(
                                scope = PolicyConstants.SCOPE_ANDROID,
                                tag = TAG,
                                packageName = matchedPkg,
                                action = "blocked registerScreenCaptureObserver",
                            )
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_ANDROID,
                                TAG,
                                "registerScreenCaptureObserver_cb",
                                t,
                            )
                        }
                    }
                }

                for (method in targets) {
                    XposedBridge.hookMethod(method, callback)
                    screenshotCount++
                }
            } else {
                errors.add("ATMS:ClassNotFound")
            }
        } catch (t: Throwable) {
            errors.add("ATMS:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_screenshot", t)
        }

        // 2. Suppress ScreenRecordingCallback registration in WindowManagerService / Controller
        try {
            val wmsClass = XposedHelpers.findClassIfExists(CLASS_WMS, classLoader)
            if (wmsClass != null) {
                val wmsTargets = wmsClass.declaredMethods.filter { method ->
                    method.name == "registerScreenRecordingCallback" &&
                        method.parameterCount == 1 &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.take(1)
                val callback = createRecordingBlockerHook("WMS.registerScreenRecordingCallback")
                for (method in wmsTargets) {
                    XposedBridge.hookMethod(method, callback)
                    recordingCount++
                }
            }

            val controllerClass = XposedHelpers.findClassIfExists(CLASS_RECORDING_CONTROLLER, classLoader)
            if (controllerClass != null) {
                val ctrlTargets = controllerClass.declaredMethods.filter { method ->
                    method.name == "register" &&
                        method.parameterCount == 1 &&
                        method.returnType == Boolean::class.javaPrimitiveType
                }.take(1)
                val callback = createRecordingBlockerHook("ScreenRecordingCallbackController.register")
                for (method in ctrlTargets) {
                    XposedBridge.hookMethod(method, callback)
                    recordingCount++
                }
            }
        } catch (t: Throwable) {
            errors.add("RecordingCb:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_recording", t)
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

    private fun createRecordingBlockerHook(actionLabel: String): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val matchedPkg = PolicyCache.findAllowlistedPackageForUid(
                        Binder.getCallingUid(),
                    ) { it.blockCaptureDetect } ?: return
                    param.result = false
                    DualTrackLogger.logHit(
                        scope = PolicyConstants.SCOPE_ANDROID,
                        tag = TAG,
                        packageName = matchedPkg,
                        action = "blocked $actionLabel",
                    )
                } catch (t: Throwable) {
                    DualTrackLogger.logError(
                        PolicyConstants.SCOPE_ANDROID,
                        TAG,
                        "registerRecording_cb",
                        t,
                    )
                }
            }
        }
    }
}
