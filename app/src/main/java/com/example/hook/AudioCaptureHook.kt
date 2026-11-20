package com.example.hook

import android.content.pm.ApplicationInfo
import android.os.Binder
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Android 17 (API 37) system_server hook for internal audio playback capture (allowAudioCapture).
 *
 * Uses targeted declaredMethods filtering + XposedBridge.hookMethod (never broad hookAllMethods):
 * 1. Layer 1 (PackageInfoUtils.generateApplicationInfo): hooks only the primary core implementation
 *    overload(s) returning ApplicationInfo with the highest parameter count (max 2 methods).
 * 2. Layer 2 (AudioService / PlaybackActivityMonitor): hooks only exact int-signature
 *    setAllowedCapturePolicy / getAllowedCapturePolicy methods.
 */
object AudioCaptureHook {
    private const val TAG = "AudioCapture"
    private const val PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE = 1 shl 27 // 0x08000000
    private const val ALLOW_CAPTURE_BY_ALL = 1 // AudioAttributes.ALLOW_CAPTURE_BY_ALL

    private const val CLASS_PACKAGE_INFO_UTILS = "com.android.server.pm.parsing.PackageInfoUtils"
    private const val CLASS_AUDIO_SERVICE = "com.android.server.audio.AudioService"
    private const val CLASS_PLAYBACK_MONITOR = "com.android.server.audio.PlaybackActivityMonitor"

    private val privateFlagsField: Field? by lazy {
        try {
            ApplicationInfo::class.java.getDeclaredField("privateFlags").apply {
                isAccessible = true
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun installPackageInfoLayer(classLoader: ClassLoader): Pair<Int, String?> {
        return try {
            val pkgInfoUtils = XposedHelpers.findClassIfExists(CLASS_PACKAGE_INFO_UTILS, classLoader)
                ?: return 0 to "PackageInfoUtils:ClassNotFound"

            val targets = selectGenerateAppInfoMethods(pkgInfoUtils)
            val callback = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val info = param.result as? ApplicationInfo ?: return
                        if (!PolicyCache.isAudioCaptureAllowed(info.packageName)) return
                        val field = privateFlagsField ?: return
                        val current = field.getInt(info)
                        if ((current and PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE) == 0) {
                            field.setInt(info, current or PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE)
                            DualTrackLogger.logHit(
                                scope = PolicyConstants.SCOPE_ANDROID,
                                tag = TAG,
                                packageName = info.packageName,
                                action = "privateFlags+=ALLOW_AUDIO_PLAYBACK_CAPTURE",
                            )
                        }
                    } catch (t: Throwable) {
                        DualTrackLogger.logError(
                            PolicyConstants.SCOPE_ANDROID,
                            TAG,
                            "generateAppInfo_cb",
                            t,
                        )
                    }
                }
            }

            var count = 0
            for (method in targets) {
                XposedBridge.hookMethod(method, callback)
                count++
            }
            count to null
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_pkg_layer", t)
            0 to "PackageInfoUtils:${t.javaClass.simpleName}"
        }
    }

    fun installAudioServiceLayer(classLoader: ClassLoader): Pair<Int, String?> {
        return try {
            var audioHookCount = 0
            audioHookCount += hookCapturePolicyClass(classLoader, CLASS_AUDIO_SERVICE)
            audioHookCount += hookCapturePolicyClass(classLoader, CLASS_PLAYBACK_MONITOR)
            audioHookCount to null
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_audio_layer", t)
            0 to "AudioService:${t.javaClass.simpleName}"
        }
    }

    fun combineResult(
        pkgResult: Pair<Int, String?>,
        audioResult: Pair<Int, String?>,
    ): Pair<Boolean, String> {
        val pkgHookCount = pkgResult.first
        val audioHookCount = audioResult.first
        val errors = listOfNotNull(pkgResult.second, audioResult.second)
        val ok = pkgHookCount > 0 || audioHookCount > 0
        val detail = buildString {
            append("PackageInfoUtils=").append(pkgHookCount)
            append(", AudioPolicy=").append(audioHookCount)
            if (errors.isNotEmpty()) {
                append(" (").append(errors.joinToString("; ")).append(")")
            }
        }
        return ok to detail
    }

    private fun selectGenerateAppInfoMethods(pkgInfoUtils: Class<*>): List<Method> {
        val candidates = pkgInfoUtils.declaredMethods.filter { method ->
            method.name == "generateApplicationInfo" &&
                method.returnType == ApplicationInfo::class.java &&
                method.parameterCount >= 3
        }
        // Hook only the deepest core implementation(s) where wrapper overloads funnel through
        return candidates.sortedByDescending { it.parameterCount }.take(2)
    }

    private fun hookCapturePolicyClass(classLoader: ClassLoader, className: String): Int {
        val targetClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return 0
        var count = 0

        val setTargets = targetClass.declaredMethods.filter { method ->
            method.name == "setAllowedCapturePolicy" &&
                method.parameterCount in 1..2 &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.take(2)

        if (setTargets.isNotEmpty()) {
            val setCallback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val args = param.args ?: return
                        if (args.isEmpty()) return
                        val (uid, policyIndex) = when {
                            args.size >= 2 && args[0] is Int && args[1] is Int ->
                                (args[0] as Int) to 1
                            args[0] is Int ->
                                Binder.getCallingUid() to 0
                            else -> return
                        }
                        val matchedPkg = PolicyCache.findAllowlistedPackageForUid(uid) {
                            it.allowAudioCapture
                        } ?: return
                        if ((args[policyIndex] as Int) != ALLOW_CAPTURE_BY_ALL) {
                            args[policyIndex] = ALLOW_CAPTURE_BY_ALL
                            DualTrackLogger.logHit(
                                scope = PolicyConstants.SCOPE_ANDROID,
                                tag = TAG,
                                packageName = matchedPkg,
                                action = "setAllowedCapturePolicy=ALLOW_CAPTURE_BY_ALL",
                            )
                        }
                    } catch (t: Throwable) {
                        DualTrackLogger.logError(
                            PolicyConstants.SCOPE_ANDROID,
                            TAG,
                            "setAllowedCapturePolicy_cb",
                            t,
                        )
                    }
                }
            }
            for (method in setTargets) {
                XposedBridge.hookMethod(method, setCallback)
                count++
            }
        }

        val getTargets = targetClass.declaredMethods.filter { method ->
            method.name == "getAllowedCapturePolicy" &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterCount in 0..1 &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.take(2)

        if (getTargets.isNotEmpty()) {
            val getCallback = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val current = param.result as? Int ?: return
                        if (current == ALLOW_CAPTURE_BY_ALL) return
                        val args = param.args
                        val uid = if (!args.isNullOrEmpty() && args[0] is Int) {
                            args[0] as Int
                        } else {
                            Binder.getCallingUid()
                        }
                        val matchedPkg = PolicyCache.findAllowlistedPackageForUid(uid) {
                            it.allowAudioCapture
                        } ?: return
                        param.result = ALLOW_CAPTURE_BY_ALL
                        DualTrackLogger.logHit(
                            scope = PolicyConstants.SCOPE_ANDROID,
                            tag = TAG,
                            packageName = matchedPkg,
                            action = "getAllowedCapturePolicy=ALLOW_CAPTURE_BY_ALL",
                        )
                    } catch (t: Throwable) {
                        DualTrackLogger.logError(
                            PolicyConstants.SCOPE_ANDROID,
                            TAG,
                            "getAllowedCapturePolicy_cb",
                            t,
                        )
                    }
                }
            }
            for (method in getTargets) {
                XposedBridge.hookMethod(method, getCallback)
                count++
            }
        }

        return count
    }
}
