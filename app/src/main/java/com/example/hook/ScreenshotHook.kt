package com.example.hook

import android.view.WindowManager
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Android 17 (API 37) system_server hook for screenshot-only capture relief (allowScreenshot).
 *
 * Uses targeted declaredMethods filtering + XposedBridge.hookMethod (never broad hookAllMethods)
 * to avoid ART deoptimization overhead during system_server boot.
 *
 * Split-screen / multi-window safety invariant:
 * `mCaptureSecureLayers` is only enabled when every visible FLAG_SECURE window on the captured
 * display belongs to a package explicitly allowlisted with `allowScreenshot == true`.
 */
object ScreenshotHook {
    private const val TAG = "ScreenshotCapture"
    private const val CLASS_WMS = "com.android.server.wm.WindowManagerService"
    private const val CLASS_SCREEN_CAPTURE = "android.window.ScreenCapture"

    @Volatile
    private var wmsInstance: Any? = null

    fun install(classLoader: ClassLoader): Pair<Boolean, String> {
        var wmsHooks = 0
        var scHooks = 0
        val errors = mutableListOf<String>()

        try {
            val wmsClass = XposedHelpers.findClassIfExists(CLASS_WMS, classLoader)
            if (wmsClass != null) {
                val wmsCallback = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            wmsInstance = param.thisObject
                            val args = param.args ?: return
                            val displayId = args.firstOrNull { it is Int } as? Int
                            val captureArgs = findCaptureArgs(args) ?: return
                            applyAllowlistIfSafe(param.thisObject, displayId, captureArgs, "WMS.captureDisplay")
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_ANDROID,
                                TAG,
                                "wms_captureDisplay_cb",
                                t,
                            )
                        }
                    }
                }
                val targets = selectWmsCaptureDisplayMethods(wmsClass)
                for (method in targets) {
                    XposedBridge.hookMethod(method, wmsCallback)
                    wmsHooks++
                }
            } else {
                errors.add("WMS:ClassNotFound")
            }
        } catch (t: Throwable) {
            errors.add("WMS:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_wms", t)
        }

        try {
            val scClass = XposedHelpers.findClassIfExists(CLASS_SCREEN_CAPTURE, classLoader)
            if (scClass != null) {
                val scCallback = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val wms = wmsInstance ?: return
                            val args = param.args ?: return
                            val captureArgs = findCaptureArgs(args) ?: return
                            applyAllowlistIfSafe(wms, null, captureArgs, "ScreenCapture.${param.method.name}")
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_ANDROID,
                                TAG,
                                "screenCapture_cb",
                                t,
                            )
                        }
                    }
                }
                val targets = selectScreenCaptureMethods(scClass)
                for (method in targets) {
                    XposedBridge.hookMethod(method, scCallback)
                    scHooks++
                }
            }
        } catch (t: Throwable) {
            errors.add("ScreenCapture:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_sc", t)
        }

        val ok = wmsHooks > 0 || scHooks > 0
        val detail = buildString {
            append("WMS.captureDisplay=").append(wmsHooks)
            append(", ScreenCapture=").append(scHooks)
            if (errors.isNotEmpty()) {
                append(" (").append(errors.joinToString("; ")).append(")")
            }
        }
        return ok to detail
    }

    private fun selectWmsCaptureDisplayMethods(wmsClass: Class<*>): List<Method> {
        val candidates = wmsClass.declaredMethods.filter { method ->
            method.name == "captureDisplay" &&
                method.parameterCount >= 2 &&
                method.parameterTypes.any { it == Int::class.javaPrimitiveType } &&
                method.parameterTypes.any { isCaptureArgsType(it) }
        }
        return candidates.sortedByDescending { it.parameterCount }.take(2)
    }

    private fun selectScreenCaptureMethods(scClass: Class<*>): List<Method> {
        val displayTargets = scClass.declaredMethods.filter { method ->
            method.name == "captureDisplay" &&
                method.parameterCount >= 1 &&
                method.parameterTypes.any { isCaptureArgsType(it) }
        }.sortedByDescending { it.parameterCount }.take(1)

        val layerTargets = scClass.declaredMethods.filter { method ->
            method.name == "captureLayers" &&
                method.parameterCount >= 1 &&
                method.parameterTypes.any { isCaptureArgsType(it) }
        }.sortedByDescending { it.parameterCount }.take(1)

        return displayTargets + layerTargets
    }

    private fun isCaptureArgsType(type: Class<*>): Boolean {
        if (type.isPrimitive) return false
        return type.name.contains("CaptureArgs") ||
            XposedHelpers.findFieldIfExists(type, "mCaptureSecureLayers") != null
    }

    private fun findCaptureArgs(args: Array<Any?>): Any? {
        for (arg in args) {
            if (arg == null) continue
            val field = XposedHelpers.findFieldIfExists(arg.javaClass, "mCaptureSecureLayers")
            if (field != null) return arg
        }
        return null
    }

    private fun applyAllowlistIfSafe(
        wms: Any,
        displayId: Int?,
        captureArgs: Any,
        sourceAction: String,
    ) {
        val field = XposedHelpers.findFieldIfExists(captureArgs.javaClass, "mCaptureSecureLayers")
            ?: return
        if (field.getBoolean(captureArgs)) return

        val matchedPackages = collectSafeScreenshotPackagesOnDisplay(wms, displayId) ?: return
        if (matchedPackages.isEmpty()) return

        field.setBoolean(captureArgs, true)
        DualTrackLogger.logHit(
            scope = PolicyConstants.SCOPE_ANDROID,
            tag = TAG,
            packageName = matchedPackages.first(),
            action = "$sourceAction -> mCaptureSecureLayers=true",
        )
    }

    private fun collectSafeScreenshotPackagesOnDisplay(wms: Any, displayId: Int?): Set<String>? {
        val root = XposedHelpers.getObjectField(wms, "mRoot") ?: return null
        val displayContent = when {
            displayId != null -> try {
                XposedHelpers.callMethod(root, "getDisplayContent", displayId)
            } catch (_: Throwable) {
                null
            }
            else -> null
        } ?: try {
            XposedHelpers.callMethod(root, "getTopFocusedDisplayContent")
        } catch (_: Throwable) {
            null
        } ?: return null

        val securePackages = linkedSetOf<String>()
        var hasForbiddenSecureWindow = false

        val consumer = java.util.function.Consumer<Any> { windowState ->
            try {
                val visible = (XposedHelpers.callMethod(windowState, "isVisibleNow") as? Boolean)
                    ?: (XposedHelpers.callMethod(windowState, "isVisible") as? Boolean)
                    ?: false
                if (!visible) return@Consumer

                val attrs = XposedHelpers.getObjectField(windowState, "mAttrs") as? WindowManager.LayoutParams
                val hasFlagSecure = attrs != null &&
                    (attrs.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
                if (!hasFlagSecure) return@Consumer

                val pkg = extractWindowPackage(windowState, attrs)
                if (pkg != null && PolicyCache.isScreenshotAllowed(pkg)) {
                    securePackages.add(pkg)
                } else {
                    hasForbiddenSecureWindow = true
                }
            } catch (_: Throwable) {
                hasForbiddenSecureWindow = true
            }
        }

        try {
            XposedHelpers.callMethod(displayContent, "forAllWindows", consumer, true)
        } catch (_: Throwable) {
            val focused = XposedHelpers.getObjectField(displayContent, "mCurrentFocus") ?: return null
            consumer.accept(focused)
        }

        if (hasForbiddenSecureWindow || securePackages.isEmpty()) {
            return null
        }
        return securePackages
    }

    private fun extractWindowPackage(
        windowState: Any,
        attrs: WindowManager.LayoutParams?,
    ): String? {
        return try {
            val owning = XposedHelpers.callMethod(windowState, "getOwningPackage") as? String
            if (!owning.isNullOrEmpty()) owning else attrs?.packageName
        } catch (_: Throwable) {
            attrs?.packageName
        }
    }
}
