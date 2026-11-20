package com.example.hook

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Android 17 (API 37) SystemUI hook fixing GMS Game Dashboard screen recording permission failures.
 *
 * Performance & Boot Safety Design:
 * - Mounted exclusively after a 12-second deferred delay in PolicyModuleEntry (zero impact on
 *   SystemUI's 10-second PROC_START_TIMEOUT window).
 * - NEVER hooks android.app.ApplicationPackageManager (eliminates ART deoptimization storms).
 * - Uses exact method signature resolution (no broad XposedBridge.hookAllMethods).
 * - Interception of getCallingPackage() is gated by a nanosecond ThreadLocal identity check so
 *   zero work is performed for any other Activity in SystemUI.
 */
object GameDashboardRecordHook {
    private const val TAG = "GameDashboardFix"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val CLASS_MP_PERMISSION_ACTIVITY =
        "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity"

    private val activeTrampolineActivity = ThreadLocal<Activity?>()

    fun install(classLoader: ClassLoader): Pair<Boolean, String> {
        return try {
            val activityClass = XposedHelpers.findClassIfExists(CLASS_MP_PERMISSION_ACTIVITY, classLoader)
                ?: return false to "Class not found: $CLASS_MP_PERMISSION_ACTIVITY"

            val onCreateMethod = findExactOnCreate(activityClass)
                ?: return false to "onCreate(Bundle) not found in $CLASS_MP_PERMISSION_ACTIVITY"

            XposedBridge.hookMethod(
                onCreateMethod,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!PolicyCache.isGameDashboardFixEnabled()) return
                            val activity = param.thisObject as? Activity ?: return
                            DualTrackLogger.attachContext(activity)
                            val rawCallingPkg = safeOriginalCallingPackage(activity)
                            if (shouldFallbackToGms(activity, rawCallingPkg)) {
                                activeTrampolineActivity.set(activity)
                                syncActivityFieldsToGms(activity, resolvedInfo = null)
                            }
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_SYSTEMUI,
                                TAG,
                                "onCreate_before",
                                t,
                            )
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val active = activeTrampolineActivity.get()
                            if (active != null && active === param.thisObject) {
                                ensureGmsUidConsistency(active)
                            }
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_SYSTEMUI,
                                TAG,
                                "onCreate_after",
                                t,
                            )
                        } finally {
                            activeTrampolineActivity.remove()
                        }
                    }
                },
            )

            // Exact 0-arg getCallingPackage(): prefer declared override on MediaProjectionPermissionActivity,
            // otherwise attach to exact Activity.getCallingPackage() with instant ThreadLocal identity guard.
            val callingPkgMethod = findExactGetCallingPackage(activityClass)
            val callingPkgHooked = if (callingPkgMethod != null) {
                XposedBridge.hookMethod(
                    callingPkgMethod,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val active = activeTrampolineActivity.get() ?: return
                            if (param.thisObject !== active) return
                            try {
                                val original = param.result as? String
                                if (original.isNullOrBlank() || original == "android" || original != GMS_PACKAGE) {
                                    param.result = GMS_PACKAGE
                                    syncActivityFieldsToGms(active, resolvedInfo = null)
                                    DualTrackLogger.logHit(
                                        scope = PolicyConstants.SCOPE_SYSTEMUI,
                                        tag = TAG,
                                        packageName = GMS_PACKAGE,
                                        action = "getCallingPackage intercepted (${original ?: "null"} -> $GMS_PACKAGE)",
                                    )
                                }
                            } catch (t: Throwable) {
                                DualTrackLogger.logError(
                                    PolicyConstants.SCOPE_SYSTEMUI,
                                    TAG,
                                    "getCallingPackage_cb",
                                    t,
                                )
                            }
                        }
                    },
                )
                1
            } else {
                0
            }

            true to "MediaProjectionPermissionActivity (onCreate=1, getCallingPackage=$callingPkgHooked)"
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEMUI, TAG, "install", t)
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun findExactOnCreate(activityClass: Class<*>): Method? {
        return activityClass.declaredMethods.firstOrNull { method ->
            method.name == "onCreate" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Bundle::class.java
        } ?: XposedHelpers.findMethodExactIfExists(activityClass, "onCreate", Bundle::class.java)
    }

    private fun findExactGetCallingPackage(activityClass: Class<*>): Method? {
        val declared = activityClass.declaredMethods.firstOrNull { method ->
            method.name == "getCallingPackage" &&
                method.parameterCount == 0 &&
                method.returnType == String::class.java
        }
        if (declared != null) return declared
        return XposedHelpers.findMethodExactIfExists(Activity::class.java, "getCallingPackage")
    }

    private fun safeOriginalCallingPackage(activity: Activity): String? {
        return try {
            activity.callingPackage
        } catch (_: Throwable) {
            null
        }
    }

    private fun shouldFallbackToGms(activity: Activity, callingPkg: String?): Boolean {
        if (!callingPkg.isNullOrBlank() && callingPkg != "android") {
            val resolvable = try {
                activity.packageManager.getApplicationInfo(callingPkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            if (resolvable) return false
        }
        return looksLikeGameDashboardTrampoline(activity)
    }

    private fun looksLikeGameDashboardTrampoline(activity: Activity): Boolean {
        val intent: Intent? = activity.intent
        val referrerHost = try {
            activity.referrer?.host
        } catch (_: Throwable) {
            null
        }
        if (referrerHost == "android" || referrerHost == GMS_PACKAGE) {
            return true
        }
        val callingClass = activity.callingActivity?.className.orEmpty()
        if (callingClass.contains("GameSessionTrampoline", ignoreCase = true) ||
            callingClass.contains("GameDashboard", ignoreCase = true)
        ) {
            return true
        }
        val extras = intent?.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                if (key.contains("game", ignoreCase = true) ||
                    key.contains("MEDIA_PROJECTION", ignoreCase = true) ||
                    key.contains("USER_REVIEW_GRANTED_CONSENT", ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        return safeOriginalCallingPackage(activity).isNullOrBlank()
    }

    private fun ensureGmsUidConsistency(activity: Activity) {
        try {
            val pkgField = XposedHelpers.findFieldIfExists(activity.javaClass, "mPackageName") ?: return
            val currentPkg = pkgField.get(activity) as? String
            if (currentPkg == GMS_PACKAGE) {
                syncActivityFieldsToGms(activity, resolvedInfo = null)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Synchronizes both mPackageName and mUid on MediaProjectionPermissionActivity with
     * the exact ApplicationInfo.uid of com.google.android.gms.
     */
    private fun syncActivityFieldsToGms(activity: Activity, resolvedInfo: ApplicationInfo?) {
        try {
            val gmsInfo = resolvedInfo ?: activity.packageManager.getApplicationInfo(GMS_PACKAGE, 0)
            val pkgField = XposedHelpers.findFieldIfExists(activity.javaClass, "mPackageName")
            if (pkgField != null) {
                pkgField.set(activity, GMS_PACKAGE)
            }
            val uidField = XposedHelpers.findFieldIfExists(activity.javaClass, "mUid")
            if (uidField != null && (uidField.type == Int::class.javaPrimitiveType || uidField.type == Int::class.javaObjectType)) {
                uidField.setInt(activity, gmsInfo.uid)
            }
        } catch (t: Throwable) {
            DualTrackLogger.logError(
                PolicyConstants.SCOPE_SYSTEMUI,
                TAG,
                "sync_gms_fields",
                t,
            )
        }
    }
}
