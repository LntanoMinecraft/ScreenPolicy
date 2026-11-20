package com.example.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.example.data.PolicyConstants
import com.example.model.LogLevel

/**
 * Lightweight IPC endpoint for Track 2 dual-track logging and startup mount status reporting.
 * Strictly enforces caller UID verification: only Process.SYSTEM_UID (1000), com.android.systemui,
 * and self-process calls are accepted.
 */
class TelemetryContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { TelemetryStore.ensureLoaded(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: throw SecurityException("Provider context unavailable")
        val callingUid = Binder.getCallingUid()
        if (!isAuthorizedCaller(ctx, callingUid)) {
            throw SecurityException("Unauthorized caller UID: $callingUid")
        }

        val timestamp = extras?.getLong(PolicyConstants.EXTRA_TIMESTAMP, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val scope = extras?.getString(PolicyConstants.EXTRA_SCOPE)
            ?: if (callingUid == Process.SYSTEM_UID) PolicyConstants.SCOPE_ANDROID else PolicyConstants.SCOPE_SYSTEMUI

        when (method) {
            PolicyConstants.METHOD_REPORT_STATUS -> {
                val hookKey = extras?.getString(PolicyConstants.EXTRA_HOOK_KEY).orEmpty()
                val success = extras?.getBoolean(PolicyConstants.EXTRA_SUCCESS, false) ?: false
                val detail = extras?.getString(PolicyConstants.EXTRA_DETAIL).orEmpty()
                if (hookKey.isNotEmpty()) {
                    TelemetryStore.updateHookStatus(
                        context = ctx,
                        hookKey = hookKey,
                        scope = scope,
                        success = success,
                        detail = detail,
                        timestampMs = timestamp,
                    )
                    TelemetryStore.appendLog(
                        context = ctx,
                        source = scope,
                        level = if (success) LogLevel.INFO else LogLevel.ERROR,
                        tag = hookKey,
                        message = if (detail.isNotBlank()) detail else if (success) "ready" else "missed",
                        timestampMs = timestamp,
                    )
                }
            }

            PolicyConstants.METHOD_REPORT_LOG -> {
                val level = LogLevel.fromCode(extras?.getString(PolicyConstants.EXTRA_LEVEL))
                val tag = extras?.getString(PolicyConstants.EXTRA_TAG).orEmpty().ifBlank { "Runtime" }
                val message = extras?.getString(PolicyConstants.EXTRA_MESSAGE).orEmpty()
                if (message.isNotBlank()) {
                    TelemetryStore.appendLog(
                        context = ctx,
                        source = scope,
                        level = level,
                        tag = tag,
                        message = message,
                        timestampMs = timestamp,
                    )
                }
            }
        }

        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun isAuthorizedCaller(ctx: android.content.Context, callingUid: Int): Boolean {
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.myUid()) {
            return true
        }
        val systemUiUid = runCatching {
            ctx.packageManager.getPackageUid(PolicyConstants.SCOPE_SYSTEMUI, 0)
        }.getOrElse { -1 }
        if (systemUiUid > 0 && callingUid == systemUiUid) {
            return true
        }
        val packages = runCatching {
            ctx.packageManager.getPackagesForUid(callingUid)
        }.getOrNull()
        return packages?.any { it == PolicyConstants.SCOPE_SYSTEMUI } == true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
