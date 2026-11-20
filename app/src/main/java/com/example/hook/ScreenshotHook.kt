package com.example.hook

import android.view.WindowManager
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Android 17 (API 37) system_server hook for screenshot-only capture relief (allowScreenshot).
 *
 * Strictly scoped to system screenshot capture paths (WindowManagerService.captureDisplay and
 * ScreenCapture.captureDisplay / captureLayers) so enabling `allowScreenshot` alone allows
 * screenshots for allowlisted apps WITHOUT unlocking WindowState.isSecureLocked() for screen
 * recording.
 *
 * Split-screen / multi-window safety invariant:
 * `mCaptureSecureLayers` is only enabled when every visible FLAG_SECURE window on the captured
 * display belongs to a package explicitly allowlisted with `allowScreenshot == true`. If any
 * non-allowlisted secure window (e.g. banking/payment) is visible on screen, secure capture
 * remains blocked.
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
            val wmsClass = XposedHelpers.findClass(CLASS_WMS, classLoader)
            wmsHooks += XposedBridge.hookAllMethods(
                wmsClass,
                "captureDisplay",
                object : XC_MethodHook() {
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
                },
            ).size
        } catch (t: Throwable) {
            errors.add("WMS:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_wms", t)
        }

        try {
            val scClass = XposedHelpers.findClassIfExists(CLASS_SCREEN_CAPTURE, classLoader)
            if (scClass != null) {
                val hook = object : XC_MethodHook() {
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
                scHooks += XposedBridge.hookAllMethods(scClass, "captureDisplay", hook).size
                scHooks += XposedBridge.hookAllMethods(scClass, "captureLayers", hook).size
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

    /**
     * Inspects all visible FLAG_SECURE windows on the target DisplayContent.
     * Returns the allowlisted package set ONLY if:
     * 1. At least one visible FLAG_SECURE window exists, AND
     * 2. Every single visible FLAG_SECURE window has `allowScreenshot == true`.
     * Returns null if any non-allowlisted secure window is visible (split-screen/freeform protection).
     */
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
            // Fallback to focused window only if traversal signature differs
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
