package com.example.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import com.example.data.PolicyConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Android 17 (API 37) SystemUI hook fixing GMS Game Dashboard screen recording permission failures,
 * with an optional R8-obfuscation-immune auto-confirm entire-screen recording path strictly gated
 * to GMS Game Dashboard launches.
 *
 * Performance & Boot Safety Design:
 * - Mounted exclusively after a 12-second deferred delay in PolicyModuleEntry (zero impact on
 *   SystemUI's 10-second PROC_START_TIMEOUT window).
 * - NEVER hooks android.app.ApplicationPackageManager (eliminates ART deoptimization storms).
 * - Uses exact method signature resolution (no broad XposedBridge.hookAllMethods).
 * - Interception of getCallingPackage() is gated by a nanosecond ThreadLocal identity check so
 *   zero work is performed for any other Activity in SystemUI.
 *
 * R8-Immune Auto-Confirm Pipeline (Public SDK APIs Only):
 * - Zero dependency on private method names or private field names in SystemUI.
 * - Step 1: Scan Activity & superclass fields by type for a showing android.app.Dialog.
 * - Step 2: Traverse dialog.window.decorView for Spinner, match "整个屏幕" / "全部屏幕" / "Entire screen"
 *   (fallback to adapter.count - 1), call setSelection(index) and invoke onItemSelectedListener.
 * - Step 3: On the next frame, click AlertDialog.BUTTON_POSITIVE or fallback to android.R.id.button1
 *   / clickable button matching "开始" / "立即开始" / "Start".
 * - Strictly gated to GMS Game Dashboard / GameSessionTrampolineActivity; third-party apps are
 *   unconditionally rejected. All failures silently degrade to the native system dialog.
 */
object GameDashboardRecordHook {
    private const val TAG = "GameDashboardFix"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val CLASS_MP_PERMISSION_ACTIVITY =
        "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity"

    private val RETRY_DELAYS_MS = longArrayOf(60L, 180L)

    private val ENTIRE_SCREEN_KEYWORDS = listOf(
        "整个屏幕",
        "全部屏幕",
        "entire screen",
    )

    private val CONFIRM_BUTTON_EXACT_LABELS = setOf(
        "开始",
        "立即开始",
        "开始录制",
        "start",
        "start now",
        "start recording",
    )

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
                                scheduleAutoConfirmFlow(autoConfirmTarget)
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

    internal data class AutoConfirmStepResult(
        val succeeded: Boolean,
        val retryable: Boolean,
        val detail: String,
    )

    internal data class DialogLookupResult(
        val showingDialog: Dialog?,
        val anyDialogFound: Boolean,
    )

    /**
     * Schedules the R8-immune auto-confirm flow after MediaProjectionPermissionActivity.onCreate().
     * Posts immediately onto activity.window.decorView and retries up to 2 times (60ms / 180ms)
     * in case the dialog finishes show() asynchronously.
     */
    internal fun scheduleAutoConfirmFlow(activity: Activity) {
        try {
            val decorView = activity.window?.decorView
            if (decorView != null) {
                decorView.post {
                    executeAutoConfirmAttempt(activity, decorView, retryIndex = 0)
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    executeAutoConfirmAttempt(activity, activity.window?.decorView, retryIndex = 0)
                }
            }
        } catch (t: Throwable) {
            logAutoConfirmDegraded("schedule_failed:${unwrapCause(t)}")
        }
    }

    private fun executeAutoConfirmAttempt(
        activity: Activity,
        decorView: View?,
        retryIndex: Int,
    ) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return

            // Step 1: Locate showing Dialog strictly by field type (immune to R8 field renaming)
            val lookup = findDialogByType(activity)
            val dialog = lookup.showingDialog
            if (dialog == null) {
                val reason = if (lookup.anyDialogFound) {
                    "step1_dialog_not_showing"
                } else {
                    "step1_dialog_not_found"
                }
                if (retryIndex < RETRY_DELAYS_MS.size) {
                    scheduleRetry(activity, decorView, retryIndex)
                } else {
                    logAutoConfirmDegraded("$reason (retries=${RETRY_DELAYS_MS.size})")
                }
                return
            }

            // Step 2: Traverse dialog.window.decorView for Spinner and select "Entire screen"
            val step2 = selectEntireScreenOptionInDialog(dialog)
            if (!step2.succeeded) {
                if (step2.retryable && retryIndex < RETRY_DELAYS_MS.size) {
                    scheduleRetry(activity, decorView, retryIndex)
                } else {
                    logAutoConfirmDegraded(step2.detail)
                }
                return
            }

            // Step 3: On the next frame, click the positive confirmation button
            val postTarget = dialog.window?.decorView ?: decorView
            val clickTask = Runnable {
                try {
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@Runnable
                    val step3 = clickConfirmButtonInDialog(dialog)
                    if (step3.succeeded) {
                        DualTrackLogger.logHit(
                            scope = PolicyConstants.SCOPE_SYSTEMUI,
                            tag = TAG,
                            packageName = GMS_PACKAGE,
                            action = "auto_confirm_entire_screen (step1=Dialog, ${step2.detail}, ${step3.detail})",
                        )
                    } else if (step3.retryable && retryIndex < RETRY_DELAYS_MS.size) {
                        scheduleRetry(activity, decorView, retryIndex)
                    } else {
                        logAutoConfirmDegraded("${step2.detail} -> ${step3.detail}")
                    }
                } catch (t: Throwable) {
                    logAutoConfirmDegraded("step3_exception:${unwrapCause(t)}")
                }
            }

            if (postTarget != null) {
                postTarget.post(clickTask)
            } else {
                Handler(Looper.getMainLooper()).post(clickTask)
            }
        } catch (t: Throwable) {
            logAutoConfirmDegraded("attempt_exception:${unwrapCause(t)}")
        }
    }

    private fun scheduleRetry(activity: Activity, decorView: View?, retryIndex: Int) {
        val delayMs = RETRY_DELAYS_MS[retryIndex]
        val nextRetry = retryIndex + 1
        val targetView = decorView ?: activity.window?.decorView
        val runnable = Runnable {
            executeAutoConfirmAttempt(activity, targetView, nextRetry)
        }
        if (targetView != null) {
            targetView.postDelayed(runnable, delayMs)
        } else {
            Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs)
        }
    }

    /**
     * Step 1 (R8-immune): Walks Activity and all superclasses' declaredFields matching Dialog
     * strictly by type (or runtime Dialog instance on DialogInterface/Object fields), without
     * referencing any private field name.
     */
    internal fun findDialogByType(activity: Activity): DialogLookupResult {
        var anyDialogFound = false
        var cls: Class<*>? = activity.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (field.type.isPrimitive) continue
                val typeMatchesDialog = Dialog::class.java.isAssignableFrom(field.type) ||
                    field.type == DialogInterface::class.java ||
                    field.type == Any::class.java
                if (!typeMatchesDialog) continue

                val candidate = safeGetField(field, activity) as? Dialog ?: continue
                anyDialogFound = true
                if (candidate.isShowing) {
                    return DialogLookupResult(showingDialog = candidate, anyDialogFound = true)
                }
            }
            cls = cls.superclass
        }
        return DialogLookupResult(showingDialog = null, anyDialogFound = anyDialogFound)
    }

    /**
     * Step 2 (R8-immune): Finds the Spinner inside dialog.window.decorView, matches the
     * "整个屏幕" / "全部屏幕" / "Entire screen" option (falling back to adapter.count - 1),
     * updates selection and explicitly dispatches onItemSelectedListener.onItemSelected(...).
     */
    internal fun selectEntireScreenOptionInDialog(dialog: Dialog): AutoConfirmStepResult {
        return try {
            val rootView = dialog.window?.decorView
                ?: return AutoConfirmStepResult(
                    succeeded = false,
                    retryable = true,
                    detail = "step2_dialog_decor_null",
                )

            val spinner = findFirstSpinner(rootView, preferVisible = true)
                ?: findFirstSpinner(rootView, preferVisible = false)

            if (spinner == null) {
                // Entire-screen-only dialogs (no single-app selector) do not contain a Spinner.
                return AutoConfirmStepResult(
                    succeeded = true,
                    retryable = false,
                    detail = "step2=no_spinner_direct_entire_screen",
                )
            }

            val adapter = spinner.adapter
            if (adapter == null || adapter.count <= 0) {
                return AutoConfirmStepResult(
                    succeeded = false,
                    retryable = true,
                    detail = "step2_spinner_adapter_empty",
                )
            }

            val matchIndex = findEntireScreenAdapterIndex(spinner)
            val targetIndex = if (matchIndex >= 0) matchIndex else adapter.count - 1
            val itemId = try {
                adapter.getItemId(targetIndex)
            } catch (_: Throwable) {
                targetIndex.toLong()
            }

            spinner.setSelection(targetIndex, false)
            val selectedView = spinner.selectedView ?: try {
                adapter.getView(targetIndex, null, spinner)
            } catch (_: Throwable) {
                null
            }
            spinner.onItemSelectedListener?.onItemSelected(spinner, selectedView, targetIndex, itemId)

            val matchTag = if (matchIndex >= 0) "matched" else "fallback_last"
            AutoConfirmStepResult(
                succeeded = true,
                retryable = false,
                detail = "step2=spinner_idx_$targetIndex($matchTag/${adapter.count})",
            )
        } catch (t: Throwable) {
            AutoConfirmStepResult(
                succeeded = false,
                retryable = false,
                detail = "step2_spinner_failed:${unwrapCause(t)}",
            )
        }
    }

    internal fun findEntireScreenAdapterIndex(spinner: Spinner): Int {
        val adapter = spinner.adapter ?: return -1
        for (index in 0 until adapter.count) {
            val labelText = extractAdapterItemText(spinner, index)
            if (matchesEntireScreenText(labelText)) {
                return index
            }
        }
        return -1
    }

    internal fun matchesEntireScreenText(text: String?): Boolean {
        val normalized = text?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return ENTIRE_SCREEN_KEYWORDS.any { keyword ->
            normalized.contains(keyword, ignoreCase = true)
        }
    }

    private fun extractAdapterItemText(spinner: Spinner, index: Int): String {
        val adapter = spinner.adapter ?: return ""
        val parts = StringBuilder()

        val rawItem = try {
            adapter.getItem(index)
        } catch (_: Throwable) {
            null
        }
        if (rawItem != null) {
            parts.append(rawItem.toString()).append(' ')
        }

        val itemView = try {
            adapter.getView(index, null, spinner)
        } catch (_: Throwable) {
            null
        }
        if (itemView != null) {
            collectTextFromViewTree(itemView, parts)
        }

        val dropDownView = try {
            adapter.getDropDownView(index, null, spinner)
        } catch (_: Throwable) {
            null
        }
        if (dropDownView != null) {
            collectTextFromViewTree(dropDownView, parts)
        }

        return parts.toString()
    }

    private fun collectTextFromViewTree(view: View, out: StringBuilder) {
        if (view is TextView) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                out.append(text).append(' ')
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTextFromViewTree(view.getChildAt(i), out)
            }
        }
    }

    /**
     * Step 3 (R8-immune): Invokes AlertDialog.getButton(BUTTON_POSITIVE).performClick(),
     * falling back to android.R.id.button1 or any clickable button in custom dialog layouts
     * whose text matches "开始" / "立即开始" / "Start".
     */
    internal fun clickConfirmButtonInDialog(dialog: Dialog): AutoConfirmStepResult {
        return try {
            // 3a. Standard AlertDialog positive button
            val positiveButton = (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_POSITIVE)
            if (positiveButton != null && positiveButton.isEnabled && positiveButton.visibility == View.VISIBLE) {
                positiveButton.performClick()
                return AutoConfirmStepResult(
                    succeeded = true,
                    retryable = false,
                    detail = "step3=alert_positive_click",
                )
            }

            val rootView = dialog.window?.decorView
                ?: return AutoConfirmStepResult(
                    succeeded = false,
                    retryable = true,
                    detail = "step3_dialog_decor_null",
                )

            // 3b. Custom layout button with standard android.R.id.button1
            val button1 = rootView.findViewById<View?>(android.R.id.button1)
            if (button1 != null && button1.isClickable && button1.isEnabled && button1.visibility == View.VISIBLE) {
                button1.performClick()
                return AutoConfirmStepResult(
                    succeeded = true,
                    retryable = false,
                    detail = "step3=android_button1_click",
                )
            }

            // 3c. Custom layout clickable button matching "开始" / "立即开始" / "Start"
            val textMatchedButton = findConfirmButtonByText(rootView)
            if (textMatchedButton != null) {
                textMatchedButton.performClick()
                return AutoConfirmStepResult(
                    succeeded = true,
                    retryable = false,
                    detail = "step3=custom_text_button_click",
                )
            }

            AutoConfirmStepResult(
                succeeded = false,
                retryable = true,
                detail = "step3_positive_button_not_found",
            )
        } catch (t: Throwable) {
            AutoConfirmStepResult(
                succeeded = false,
                retryable = false,
                detail = "step3_click_failed:${unwrapCause(t)}",
            )
        }
    }

    internal fun matchesConfirmButtonText(rawText: CharSequence?): Boolean {
        val text = rawText?.toString()?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        if (text.contains("取消") || text.contains("cancel")) return false
        if (text in CONFIRM_BUTTON_EXACT_LABELS) return true
        return text.startsWith("开始") || text.startsWith("立即开始") || text.startsWith("start")
    }

    private fun findConfirmButtonByText(root: View): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root.isClickable && root.isEnabled) {
            if (root is TextView && matchesConfirmButtonText(root.text)) {
                return root
            }
            if (root is ViewGroup) {
                val sb = StringBuilder()
                collectTextFromViewTree(root, sb)
                if (matchesConfirmButtonText(sb.toString())) {
                    return root
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val match = findConfirmButtonByText(root.getChildAt(i))
                if (match != null) return match
            }
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

    private fun safeGetField(field: Field, target: Any): Any? {
        return try {
            field.isAccessible = true
            field.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findFirstSpinner(root: View, preferVisible: Boolean): Spinner? {
        if (preferVisible && root.visibility != View.VISIBLE) return null
        if (root is Spinner) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstSpinner(root.getChildAt(i), preferVisible)
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
     * the exact ApplicationInfo.uid of com.google.android.gms when fields are present.
     */
    private fun syncActivityFieldsToGms(activity: Activity, resolvedInfo: ApplicationInfo?) {
        try {
            val gmsInfo = resolvedInfo ?: activity.packageManager.getApplicationInfo(GMS_PACKAGE, 0)
            val pkgField = XposedHelpers.findFieldIfExists(activity.javaClass, "mPackageName")
            if (pkgField != null) {
                pkgField.set(activity, GMS_PACKAGE)
            }
            val uidField = XposedHelpers.findFieldIfExists(activity.javaClass, "mUid")
            if (uidField != null &&
                (uidField.type == Int::class.javaPrimitiveType || uidField.type == Int::class.javaObjectType)
            ) {
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
