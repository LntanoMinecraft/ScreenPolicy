package com.example.hook

import android.view.WindowManager
import com.example.data.PolicyConstants
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Android 17 (API 37) system_server hook for screenshot-only capture relief (allowScreenshot).
 *
 * Uses libxposed API 102 interceptor chains on targeted declaredMethods (zero broad wildcard hooks).
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

    fun install(module: XposedModule, classLoader: ClassLoader): Pair<Boolean, String> {
        var wmsHooks = 0
        var scHooks = 0
        val errors = mutableListOf<String>()

        try {
            val wmsClass = HookReflect.findClassOrNull(CLASS_WMS, classLoader)
            if (wmsClass != null) {
                val targets = selectWmsCaptureDisplayMethods(wmsClass)
                for (method in targets) {
                    module.hook(method).intercept { chain ->
                        try {
                            val receiver = chain.thisObject
                            if (receiver != null) {
                                wmsInstance = receiver
                                val args = chain.args
                                val displayId = args.firstOrNull { it is Int } as? Int
                                val captureArgs = findCaptureArgs(args)
                                if (captureArgs != null) {
                                    applyAllowlistIfSafe(receiver, displayId, captureArgs, "WMS.captureDisplay")
                                }
                            }
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_SYSTEM,
                                TAG,
                                "wms_captureDisplay_cb",
                                t,
                            )
                        }
                        chain.proceed()
                    }
                    wmsHooks++
                }
            } else {
                errors.add("WMS:ClassNotFound")
            }
        } catch (t: Throwable) {
            errors.add("WMS:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_wms", t)
        }

        try {
            val scClass = HookReflect.findClassOrNull(CLASS_SCREEN_CAPTURE, classLoader)
            if (scClass != null) {
                val targets = selectScreenCaptureMethods(scClass)
                for (method in targets) {
                    val methodName = method.name
                    module.hook(method).intercept { chain ->
                        try {
                            val wms = wmsInstance
                            if (wms != null) {
                                val captureArgs = findCaptureArgs(chain.args)
                                if (captureArgs != null) {
                                    applyAllowlistIfSafe(wms, null, captureArgs, "ScreenCapture.$methodName")
                                }
                            }
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_SYSTEM,
                                TAG,
                                "screenCapture_cb",
                                t,
                            )
                        }
                        chain.proceed()
                    }
                    scHooks++
                }
            }
        } catch (t: Throwable) {
            errors.add("ScreenCapture:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_sc", t)
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
            HookReflect.findFieldOrNull(type, "mCaptureSecureLayers") != null
    }

    private fun findCaptureArgs(args: List<Any?>): Any? {
        for (arg in args) {
            if (arg == null) continue
            val field = HookReflect.findFieldOrNull(arg.javaClass, "mCaptureSecureLayers")
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
        val field = HookReflect.findFieldOrNull(captureArgs.javaClass, "mCaptureSecureLayers")
            ?: return
        if (field.getBoolean(captureArgs)) return

        val matchedPackages = collectSafeScreenshotPackagesOnDisplay(wms, displayId) ?: return
        if (matchedPackages.isEmpty()) return

        field.setBoolean(captureArgs, true)
        DualTrackLogger.logHit(
            scope = PolicyConstants.SCOPE_SYSTEM,
            tag = TAG,
            packageName = matchedPackages.first(),
            action = "$sourceAction -> mCaptureSecureLayers=true",
        )
    }

    private fun collectSafeScreenshotPackagesOnDisplay(wms: Any, displayId: Int?): Set<String>? {
        val root = HookReflect.getObjectFieldOrNull(wms, "mRoot") ?: return null
        val displayContent = when {
            displayId != null -> HookReflect.callMethodExactOrNull(
                root,
                "getDisplayContent",
                arrayOf(Int::class.javaPrimitiveType!!),
                displayId,
            )
            else -> null
        } ?: HookReflect.callZeroArgOrNull(root, "getTopFocusedDisplayContent")
            ?: return null

        val securePackages = linkedSetOf<String>()
        var hasForbiddenSecureWindow = false

        val consumer = Consumer<Any> { windowState ->
            try {
                val visible = (HookReflect.callZeroArgOrNull(windowState, "isVisibleNow") as? Boolean)
                    ?: (HookReflect.callZeroArgOrNull(windowState, "isVisible") as? Boolean)
                    ?: false
                if (!visible) return@Consumer

                val attrs = HookReflect.getObjectFieldOrNull(windowState, "mAttrs") as? WindowManager.LayoutParams
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

        val traversed = HookReflect.callMethodExactOrNull(
            displayContent,
            "forAllWindows",
            arrayOf(Consumer::class.java, Boolean::class.javaPrimitiveType!!),
            consumer,
            true,
        ) != null || HookReflect.findMethodExactOrNull(
            displayContent.javaClass,
            "forAllWindows",
            Consumer::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) != null

        if (!traversed) {
            val focused = HookReflect.getObjectFieldOrNull(displayContent, "mCurrentFocus") ?: return null
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
            val owning = HookReflect.callZeroArgOrNull(windowState, "getOwningPackage") as? String
            if (!owning.isNullOrEmpty()) owning else attrs?.packageName
        } catch (_: Throwable) {
            attrs?.packageName
        }
    }
}
