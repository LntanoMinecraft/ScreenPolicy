package com.example.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Android 17 (API 37) SystemUI hook fixing GMS Game Dashboard screen recording permission failures,
 * with an optional strictly gated auto-confirm entire-screen recording path for GMS Game Dashboard.
 *
 * Performance & Boot Safety Design:
 * - Mounted exclusively after a 12-second deferred delay in PolicyModuleEntry (zero impact on
 *   SystemUI's 10-second PROC_START_TIMEOUT window).
 * - NEVER hooks android.app.ApplicationPackageManager (eliminates ART deoptimization storms).
 * - Uses exact method signature resolution (no broad XposedBridge.hookAllMethods).
 * - Interception of getCallingPackage() is gated by a nanosecond ThreadLocal identity check so
 *   zero work is performed for any other Activity in SystemUI.
 *
 * Security Redline for Auto-Confirm:
 * - Auto-confirm ONLY triggers when both fixGameDashboardRecord and autoConfirmGameDashboardRecord
 *   are enabled AND the launch strictly matches GMS Game Dashboard / GameSessionTrampolineActivity.
 * - Any third-party calling package or third-party referrer is unconditionally rejected.
 * - All reflection steps are isolated in try-catch (Throwable) and silently degrade to displaying
 *   the normal system permission dialog if internal AOSP signatures change.
 */
object GameDashboardRecordHook {
    private const val TAG = "GameDashboardFix"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val CLASS_MP_PERMISSION_ACTIVITY =
        "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity"

    /** AOSP ScreenShareMode.ENTIRE_SCREEN constant (SINGLE_APP = 0, ENTIRE_SCREEN = 1). */
    internal const val SCREEN_SHARE_MODE_ENTIRE_SCREEN = 1

    private val activeTrampolineActivity = ThreadLocal<Activity?>()
    private val pendingAutoConfirmActivity = ThreadLocal<Activity?>()

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
                            if (PolicyCache.isGameDashboardAutoConfirmEnabled() &&
                                isStrictGameDashboardLaunch(activity, rawCallingPkg)
                            ) {
                                pendingAutoConfirmActivity.set(activity)
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

                            val autoConfirmTarget = pendingAutoConfirmActivity.get()
                            if (autoConfirmTarget != null &&
                                autoConfirmTarget === param.thisObject &&
                                !autoConfirmTarget.isFinishing &&
                                !autoConfirmTarget.isDestroyed &&
                                PolicyCache.isGameDashboardAutoConfirmEnabled()
                            ) {
                                runAutoConfirmSafely(autoConfirmTarget)
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
                            pendingAutoConfirmActivity.remove()
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

    /**
     * Strict security gate for auto-confirming entire-screen MediaProjection.
     * Rejects EVERY third-party calling package and third-party referrer unconditionally,
     * and requires explicit GMS Game Dashboard / GameSessionTrampolineActivity evidence.
     */
    internal fun matchesStrictGameDashboardTrigger(
        rawCallingPackage: String?,
        callingActivityClassName: String?,
        referrerHost: String?,
        intentExtraKeys: Collection<String>,
        intentStringValues: Collection<String> = emptyList(),
    ): Boolean {
        val caller = rawCallingPackage?.trim().orEmpty()
        if (caller.isNotEmpty() && caller != GMS_PACKAGE && caller != "android") {
            return false
        }

        val refHost = referrerHost?.trim().orEmpty()
        if (refHost.isNotEmpty() && refHost != GMS_PACKAGE && refHost != "android") {
            return false
        }

        if (caller == GMS_PACKAGE || refHost == GMS_PACKAGE) {
            return true
        }

        val callingCls = callingActivityClassName.orEmpty()
        if (callingCls.contains("GameSessionTrampoline", ignoreCase = true) ||
            callingCls.contains("GameDashboard", ignoreCase = true)
        ) {
            return true
        }

        if (intentExtraKeys.any { key ->
                key.contains("game", ignoreCase = true) ||
                    key.contains("GameSessionTrampoline", ignoreCase = true) ||
                    key.contains("GameDashboard", ignoreCase = true)
            }
        ) {
            return true
        }

        if (intentStringValues.any { value ->
                value == GMS_PACKAGE ||
                    value.contains("GameSessionTrampoline", ignoreCase = true) ||
                    value.contains("GameDashboard", ignoreCase = true)
            }
        ) {
            return true
        }

        return false
    }

    private fun isStrictGameDashboardLaunch(activity: Activity, rawCallingPkg: String?): Boolean {
        return try {
            val referrerHost = try {
                activity.referrer?.host
            } catch (_: Throwable) {
                null
            }
            val callingClass = activity.callingActivity?.className
            val intent: Intent? = activity.intent
            val extraKeys = ArrayList<String>()
            val extraStringValues = ArrayList<String>()
            val extras = intent?.extras
            if (extras != null) {
                for (key in extras.keySet()) {
                    extraKeys.add(key)
                    try {
                        @Suppress("DEPRECATION")
                        val rawVal = extras.get(key)
                        if (rawVal is CharSequence) {
                            extraStringValues.add(rawVal.toString())
                        }
                    } catch (_: Throwable) {
                        // Ignore unparcelable system extras
                    }
                }
            }
            matchesStrictGameDashboardTrigger(
                rawCallingPackage = rawCallingPkg,
                callingActivityClassName = callingClass,
                referrerHost = referrerHost,
                intentExtraKeys = extraKeys,
                intentStringValues = extraStringValues,
            )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Isolated auto-confirm entry point. Verifies mPackageName/mUid belong to GMS, then attempts
     * entire-screen grant via reflection. On any mismatch or exception, silently degrades to
     * leaving the normal system permission dialog visible.
     */
    private fun runAutoConfirmSafely(activity: Activity) {
        try {
            if (!ensureAndVerifyGmsIdentityForAutoConfirm(activity)) {
                logAutoConfirmDegraded("gms_identity_mismatch")
                return
            }

            val grantResult = tryAutoConfirmEntireScreen(activity)
            if (grantResult.succeeded) {
                dismissActivityDialogSafely(activity)
                DualTrackLogger.logHit(
                    scope = PolicyConstants.SCOPE_SYSTEMUI,
                    tag = TAG,
                    packageName = GMS_PACKAGE,
                    action = "auto_confirm_entire_screen (${grantResult.detail})",
                )
            } else {
                logAutoConfirmDegraded(grantResult.detail)
            }
        } catch (t: Throwable) {
            logAutoConfirmDegraded("${t.javaClass.simpleName}: ${t.message ?: "unexpected"}")
        }
    }

    internal data class AutoConfirmResult(
        val succeeded: Boolean,
        val detail: String,
    )

    /**
     * Attempts to auto-confirm entire-screen recording on MediaProjectionPermissionActivity.
     * Strategy 1: Directly invoke grantMediaProjectionPermission with ENTIRE_SCREEN (1).
     * Strategy 2: Confirm entire-screen selection on dialog/delegate, then trigger positive action.
     * Never forces finish() without a confirmed grant, ensuring silent fallback on signature drift.
     */
    internal fun tryAutoConfirmEntireScreen(activity: Activity): AutoConfirmResult {
        // Strategy 1: Direct grantMediaProjectionPermission reflection (zero dialog flash)
        val directResult = tryInvokeGrantMediaProjectionPermission(activity)
        if (directResult != null) {
            return directResult
        }

        // Strategy 2: Confirm ENTIRE_SCREEN on dialog/delegate and invoke positive callback
        val dialogResult = tryConfirmDialogEntireScreen(activity)
        if (dialogResult != null) {
            return dialogResult
        }

        return AutoConfirmResult(
            succeeded = false,
            detail = "no_compatible_grant_path",
        )
    }

    private fun tryInvokeGrantMediaProjectionPermission(activity: Activity): AutoConfirmResult? {
        val methods = collectMethodsNamed(activity.javaClass, "grantMediaProjectionPermission")
        if (methods.isEmpty()) return null

        // 1a. Exact AOSP Android 14 QPR2–17 signature: grantMediaProjectionPermission(int screenShareMode)
        val oneIntArg = methods.firstOrNull { method ->
            method.parameterCount == 1 && isIntType(method.parameterTypes[0])
        }
        if (oneIntArg != null) {
            return try {
                oneIntArg.isAccessible = true
                oneIntArg.invoke(activity, SCREEN_SHARE_MODE_ENTIRE_SCREEN)
                AutoConfirmResult(true, "grantMediaProjectionPermission(int=ENTIRE_SCREEN)")
            } catch (t: Throwable) {
                AutoConfirmResult(false, "grant_int_failed:${unwrapCause(t)}")
            }
        }

        // 1b. Extended 2-arg signature: grantMediaProjectionPermission(int screenShareMode, LaunchCookie?)
        val twoArgIntFirst = methods.firstOrNull { method ->
            method.parameterCount == 2 &&
                isIntType(method.parameterTypes[0]) &&
                !method.parameterTypes[1].isPrimitive
        }
        if (twoArgIntFirst != null) {
            return try {
                twoArgIntFirst.isAccessible = true
                twoArgIntFirst.invoke(activity, SCREEN_SHARE_MODE_ENTIRE_SCREEN, null)
                AutoConfirmResult(true, "grantMediaProjectionPermission(int=ENTIRE_SCREEN,null)")
            } catch (t: Throwable) {
                AutoConfirmResult(false, "grant_int_obj_failed:${unwrapCause(t)}")
            }
        }

        // 1c. Legacy 0-arg signature: grantMediaProjectionPermission()
        val zeroArg = methods.firstOrNull { it.parameterCount == 0 }
        if (zeroArg != null) {
            return try {
                zeroArg.isAccessible = true
                zeroArg.invoke(activity)
                AutoConfirmResult(true, "grantMediaProjectionPermission()")
            } catch (t: Throwable) {
                AutoConfirmResult(false, "grant_zero_failed:${unwrapCause(t)}")
            }
        }

        return AutoConfirmResult(
            succeeded = false,
            detail = "unsupported_grant_overload(${methods.joinToString { it.parameterCount.toString() }})",
        )
    }

    private fun tryConfirmDialogEntireScreen(activity: Activity): AutoConfirmResult? {
        return try {
            val dialog = findActivityDialog(activity) ?: return null
            val entireScreenSelected = ensureEntireScreenSelected(activity, dialog)
            if (!entireScreenSelected) {
                return AutoConfirmResult(false, "entire_screen_option_unverified")
            }

            if (activity is DialogInterface.OnClickListener) {
                activity.onClick(dialog, DialogInterface.BUTTON_POSITIVE)
                return AutoConfirmResult(true, "dialog_onClick_positive")
            }

            if (dialog is AlertDialog) {
                val positiveBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                if (positiveBtn != null && positiveBtn.isEnabled) {
                    positiveBtn.performClick()
                    return AutoConfirmResult(true, "alert_dialog_positive_click")
                }
            }

            AutoConfirmResult(false, "positive_trigger_unavailable")
        } catch (t: Throwable) {
            AutoConfirmResult(false, "dialog_confirm_failed:${unwrapCause(t)}")
        }
    }

    private fun ensureEntireScreenSelected(activity: Activity, dialog: Dialog): Boolean {
        val delegate = findDialogDelegate(activity, dialog)
        if (delegate != null) {
            val currentMode = readSelectedOptionMode(delegate)
            if (currentMode == SCREEN_SHARE_MODE_ENTIRE_SCREEN) {
                return true
            }
            if (selectEntireScreenOnDelegate(delegate, dialog)) {
                return readSelectedOptionMode(delegate) == SCREEN_SHARE_MODE_ENTIRE_SCREEN
            }
            return false
        }

        // If no delegate object exists (older simple AlertDialog without single-app picker),
        // verify there is no Spinner selector before allowing positive button click.
        val rootView = dialog.window?.decorView
        val spinner = rootView?.let { findFirstSpinner(it) }
        return spinner == null
    }

    private fun findDialogDelegate(activity: Activity, dialog: Dialog): Any? {
        val candidates = listOf(activity, dialog)
        for (owner in candidates) {
            var cls: Class<*>? = owner.javaClass
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    val typeName = field.type.name
                    if (typeName.contains("MediaProjectionPermissionDialogDelegate", ignoreCase = true) ||
                        field.name.contains("delegate", ignoreCase = true)
                    ) {
                        val value = safeGetField(field, owner)
                        if (value != null && hasMethodOrField(value.javaClass, "ScreenShareOption")) {
                            return value
                        }
                    }
                }
                cls = cls.superclass
            }
        }
        return null
    }

    private fun readSelectedOptionMode(delegate: Any): Int? {
        val selectedOption = invokeZeroArgIfExists(delegate, "getSelectedScreenShareOption")
            ?: findFieldValueContaining(delegate, "selectedScreenShareOption")
            ?: return null
        val modeValue = invokeZeroArgIfExists(selectedOption, "getMode")
            ?: findFieldValueExact(selectedOption, "mode")
        return modeValue as? Int
    }

    private fun selectEntireScreenOnDelegate(delegate: Any, dialog: Dialog): Boolean {
        val options = findFieldValueContaining(delegate, "screenShareOptions") as? List<*> ?: return false
        val entireIndex = options.indexOfFirst { option ->
            if (option == null) return@indexOfFirst false
            val mode = invokeZeroArgIfExists(option, "getMode")
                ?: findFieldValueExact(option, "mode")
            mode == SCREEN_SHARE_MODE_ENTIRE_SCREEN
        }
        if (entireIndex < 0) return false

        val spinner = dialog.window?.decorView?.let { findFirstSpinner(it) }
        if (spinner != null && entireIndex < spinner.count) {
            spinner.setSelection(entireIndex)
        }

        val onItemSelected = collectMethodsNamed(delegate.javaClass, "onItemSelected")
            .firstOrNull { it.parameterCount == 4 }
        if (onItemSelected != null) {
            onItemSelected.isAccessible = true
            onItemSelected.invoke(delegate, spinner, null, entireIndex, 0L)
            return true
        }

        // Direct fallback: update selectedScreenShareOption field if writable
        val optionObj = options[entireIndex]
        val selectedField = findFieldContaining(delegate.javaClass, "selectedScreenShareOption")
        if (selectedField != null && optionObj != null && selectedField.type.isInstance(optionObj)) {
            selectedField.isAccessible = true
            selectedField.set(delegate, optionObj)
            return true
        }
        return false
    }

    private fun ensureAndVerifyGmsIdentityForAutoConfirm(activity: Activity): Boolean {
        return try {
            val gmsInfo = activity.packageManager.getApplicationInfo(GMS_PACKAGE, 0)
            val pkgField = XposedHelpers.findFieldIfExists(activity.javaClass, "mPackageName")
            val currentPkg = pkgField?.get(activity) as? String
            if (!currentPkg.isNullOrBlank() && currentPkg != GMS_PACKAGE && currentPkg != "android") {
                return false
            }
            if (pkgField != null && currentPkg != GMS_PACKAGE) {
                pkgField.set(activity, GMS_PACKAGE)
            }
            val uidField = XposedHelpers.findFieldIfExists(activity.javaClass, "mUid")
            if (uidField != null && isIntType(uidField.type)) {
                val currentUid = uidField.getInt(activity)
                if (currentUid != gmsInfo.uid) {
                    uidField.setInt(activity, gmsInfo.uid)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun dismissActivityDialogSafely(activity: Activity) {
        try {
            val dialog = findActivityDialog(activity) ?: return
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        } catch (_: Throwable) {
        }
    }

    private fun findActivityDialog(activity: Activity): Dialog? {
        val mDialogField = XposedHelpers.findFieldIfExists(activity.javaClass, "mDialog")
        val direct = mDialogField?.let { safeGetField(it, activity) as? Dialog }
        if (direct != null) return direct

        var cls: Class<*>? = activity.javaClass
        while (cls != null && cls != Activity::class.java && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (Dialog::class.java.isAssignableFrom(field.type)) {
                    val value = safeGetField(field, activity) as? Dialog
                    if (value != null) return value
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun logAutoConfirmDegraded(reason: String) {
        DualTrackLogger.logHit(
            scope = PolicyConstants.SCOPE_SYSTEMUI,
            tag = TAG,
            packageName = GMS_PACKAGE,
            action = "auto_confirm_degraded ($reason)",
        )
    }

    private fun collectMethodsNamed(clazz: Class<*>, name: String): List<Method> {
        val found = ArrayList<Method>()
        var cls: Class<*>? = clazz
        while (cls != null && cls != Any::class.java) {
            for (method in cls.declaredMethods) {
                if (method.name == name) {
                    found.add(method)
                }
            }
            cls = cls.superclass
        }
        return found
    }

    private fun isIntType(type: Class<*>): Boolean {
        return type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType
    }

    private fun hasMethodOrField(clazz: Class<*>, token: String): Boolean {
        var cls: Class<*>? = clazz
        while (cls != null && cls != Any::class.java) {
            if (cls.declaredMethods.any { it.name.contains(token, ignoreCase = true) }) return true
            if (cls.declaredFields.any { it.name.contains(token, ignoreCase = true) }) return true
            cls = cls.superclass
        }
        return false
    }

    private fun invokeZeroArgIfExists(target: Any, methodName: String): Any? {
        val method = collectMethodsNamed(target.javaClass, methodName)
            .firstOrNull { it.parameterCount == 0 } ?: return null
        return try {
            method.isAccessible = true
            method.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findFieldContaining(clazz: Class<*>, token: String): Field? {
        var cls: Class<*>? = clazz
        while (cls != null && cls != Any::class.java) {
            val match = cls.declaredFields.firstOrNull { it.name.contains(token, ignoreCase = true) }
            if (match != null) return match
            cls = cls.superclass
        }
        return null
    }

    private fun findFieldValueContaining(target: Any, token: String): Any? {
        val field = findFieldContaining(target.javaClass, token) ?: return null
        return safeGetField(field, target)
    }

    private fun findFieldValueExact(target: Any, name: String): Any? {
        val field = XposedHelpers.findFieldIfExists(target.javaClass, name) ?: return null
        return safeGetField(field, target)
    }

    private fun safeGetField(field: Field, target: Any): Any? {
        return try {
            field.isAccessible = true
            field.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findFirstSpinner(root: View): Spinner? {
        if (root is Spinner) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstSpinner(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun unwrapCause(t: Throwable): String {
        val root = t.cause ?: t
        return "${root.javaClass.simpleName}:${root.message ?: "err"}"
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
            if (uidField != null && isIntType(uidField.type)) {
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
