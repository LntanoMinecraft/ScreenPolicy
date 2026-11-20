package com.example.hook

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Android 17 (API 37) SystemUI hook fixing GMS Game Dashboard screen recording permission failures.
 *
 * Root cause on Android 17:
 * When GMS Game Dashboard (com.google.android.gms) launches MediaProjectionPermissionActivity
 * through android/.service.games.GameSessionTrampolineActivity (realCallingUid=1000),
 * MediaProjectionPermissionActivity.getCallingPackage() resolves to null/empty or fails
 * PackageManager.getApplicationInfo with NameNotFoundException, immediately cancelling the prompt.
 *
 * Fix:
 * Falls back to "com.google.android.gms" AND strictly synchronizes both mPackageName and mUid
 * on the Activity instance with the resolved GMS ApplicationInfo.uid so subsequent
 * IMediaProjectionManager token creation passes UID-to-package verification cleanly.
 */
object GameDashboardRecordHook {
    private const val TAG = "GameDashboardFix"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val CLASS_MP_PERMISSION_ACTIVITY =
        "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity"
    private const val CLASS_APP_PACKAGE_MANAGER = "android.app.ApplicationPackageManager"

    private val activePermissionActivity = ThreadLocal<Activity?>()

    fun install(classLoader: ClassLoader): Pair<Boolean, String> {
        return try {
            val activityClass = XposedHelpers.findClass(CLASS_MP_PERMISSION_ACTIVITY, classLoader)

            val callingPkgHooks = XposedBridge.hookAllMethods(
                activityClass,
                "getCallingPackage",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!PolicyCache.isGameDashboardFixEnabled()) return
                            val activity = param.thisObject as? Activity ?: return
                            val original = param.result as? String
                            if (shouldFallbackToGms(activity, original)) {
                                param.result = GMS_PACKAGE
                                syncActivityFieldsToGms(activity, resolvedInfo = null)
                                DualTrackLogger.logHit(
                                    scope = PolicyConstants.SCOPE_SYSTEMUI,
                                    tag = TAG,
                                    packageName = GMS_PACKAGE,
                                    action = "getCallingPackage fallback (${original ?: "null"} -> $GMS_PACKAGE)",
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

            val onCreateHooks = XposedBridge.hookAllMethods(
                activityClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!PolicyCache.isGameDashboardFixEnabled()) return
                            val activity = param.thisObject as? Activity ?: return
                            DualTrackLogger.attachContext(activity)
                            activePermissionActivity.set(activity)
                            patchActivityIfTrampoline(activity)
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
                            if (PolicyCache.isGameDashboardFixEnabled()) {
                                val activity = param.thisObject as? Activity
                                if (activity != null) {
                                    ensureGmsUidConsistency(activity)
                                }
                            }
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_SYSTEMUI,
                                TAG,
                                "onCreate_after",
                                t,
                            )
                        } finally {
                            activePermissionActivity.remove()
                        }
                    }
                },
            )

            val appPmHooks = installAppInfoGuard(classLoader)

            val total = callingPkgHooks.size + onCreateHooks.size
            if (total > 0) {
                true to "MediaProjectionPermissionActivity (getCallingPackage=${callingPkgHooks.size}, onCreate=${onCreateHooks.size}, pmGuard=$appPmHooks)"
            } else {
                false to "No target methods matched in $CLASS_MP_PERMISSION_ACTIVITY"
            }
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEMUI, TAG, "install", t)
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun installAppInfoGuard(classLoader: ClassLoader): Int {
        return try {
            val appPmClass = XposedHelpers.findClass(CLASS_APP_PACKAGE_MANAGER, classLoader)
            XposedBridge.hookAllMethods(
                appPmClass,
                "getApplicationInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = activePermissionActivity.get() ?: return
                        try {
                            if (!PolicyCache.isGameDashboardFixEnabled()) return
                            val thrown = param.throwable
                            if (thrown !is PackageManager.NameNotFoundException) return
                            val args = param.args ?: return
                            if (args.isEmpty()) return
                            val requestedPkg = args[0] as? String
                            if (!requestedPkg.isNullOrBlank() && requestedPkg != "android") return

                            val pm = param.thisObject as? PackageManager ?: return
                            val gmsInfo = pm.getApplicationInfo(GMS_PACKAGE, 0)
                            param.throwable = null
                            param.result = gmsInfo
                            syncActivityFieldsToGms(activity, resolvedInfo = gmsInfo)
                            DualTrackLogger.logHit(
                                scope = PolicyConstants.SCOPE_SYSTEMUI,
                                tag = TAG,
                                packageName = GMS_PACKAGE,
                                action = "getApplicationInfo recovered -> $GMS_PACKAGE (uid=${gmsInfo.uid})",
                            )
                        } catch (t: Throwable) {
                            DualTrackLogger.logError(
                                PolicyConstants.SCOPE_SYSTEMUI,
                                TAG,
                                "getApplicationInfo_guard",
                                t,
                            )
                        }
                    }
                },
            ).size
        } catch (_: Throwable) {
            0
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
        return activity.callingPackage.isNullOrBlank()
    }

    private fun patchActivityIfTrampoline(activity: Activity) {
        try {
            val pkgField = XposedHelpers.findFieldIfExists(activity.javaClass, "mPackageName")
            val currentPkg = pkgField?.get(activity) as? String
            if (shouldFallbackToGms(activity, currentPkg)) {
                syncActivityFieldsToGms(activity, resolvedInfo = null)
            }
        } catch (_: Throwable) {
        }
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
