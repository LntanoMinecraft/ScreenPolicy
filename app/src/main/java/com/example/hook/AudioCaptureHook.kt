package com.example.hook

import android.content.pm.ApplicationInfo
import android.os.Binder
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/**
 * Android 17 (API 37) system_server hook for internal audio playback capture (allowAudioCapture).
 *
 * Dual-layer allowlist enforcement:
 * 1. ApplicationInfo flag layer: sets ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE
 *    when PackageInfoUtils generates ApplicationInfo for an allowlisted package.
 * 2. AudioService runtime layer: overrides setAllowedCapturePolicy / getAllowedCapturePolicy to
 *    AudioAttributes.ALLOW_CAPTURE_BY_ALL (1) for allowlisted package UIDs.
 *
 * Strictly avoids touching Bluetooth A2DP, SCO, or voice-call audio routing.
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

    fun install(classLoader: ClassLoader): Pair<Boolean, String> {
        var pkgHookCount = 0
        var audioHookCount = 0
        val errors = mutableListOf<String>()

        // Layer 1: ApplicationInfo.privateFlags
        try {
            val pkgInfoUtils = XposedHelpers.findClass(CLASS_PACKAGE_INFO_UTILS, classLoader)
            val hooks = XposedBridge.hookAllMethods(
                pkgInfoUtils,
                "generateApplicationInfo",
                object : XC_MethodHook() {
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
                },
            )
            pkgHookCount = hooks.size
        } catch (t: Throwable) {
            errors.add("PackageInfoUtils:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_pkg_layer", t)
        }

        // Layer 2: AudioService & PlaybackActivityMonitor capture policy
        try {
            audioHookCount += hookCapturePolicyClass(classLoader, CLASS_AUDIO_SERVICE)
            audioHookCount += hookCapturePolicyClass(classLoader, CLASS_PLAYBACK_MONITOR)
        } catch (t: Throwable) {
            errors.add("AudioService:${t.javaClass.simpleName}")
            DualTrackLogger.logError(PolicyConstants.SCOPE_ANDROID, TAG, "install_audio_layer", t)
        }

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

    private fun hookCapturePolicyClass(classLoader: ClassLoader, className: String): Int {
        val targetClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return 0
        var count = 0

        count += XposedBridge.hookAllMethods(
            targetClass,
            "setAllowedCapturePolicy",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val args = param.args ?: return
                        if (args.isEmpty()) return
                        // Android 17 signatures: (int policy) or (int uid, int policy)
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
            },
        ).size

        count += XposedBridge.hookAllMethods(
            targetClass,
            "getAllowedCapturePolicy",
            object : XC_MethodHook() {
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
            },
        ).size

        return count
    }
}
