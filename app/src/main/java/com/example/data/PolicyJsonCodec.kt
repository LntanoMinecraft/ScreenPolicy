package com.example.data

import com.example.model.AppPolicy
import com.example.model.GlobalPolicyConfig
import org.json.JSONObject

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure org.json codec shared between the app UI (SAF import/export, SharedPreferences)
 * and the system_server / SystemUI runtime cache.
 * Backward-compatible with legacy `disableSecure` configs on decode.
 */
object PolicyJsonCodec {
    private const val FIELD_VERSION = "version"
    private const val FIELD_FIX_GAME_DASHBOARD = "fixGameDashboardRecord"
    private const val FIELD_AUTO_CONFIRM_GAME_DASHBOARD = "autoConfirmGameDashboardRecord"
    private const val FIELD_VERBOSE_LOGGING = "verboseLogging"
    private const val FIELD_APPS = "apps"
    private const val FIELD_ALLOW_SCREENSHOT = "allowScreenshot"
    private const val FIELD_ALLOW_SCREEN_RECORD = "allowScreenRecord"
    private const val FIELD_LEGACY_DISABLE_SECURE = "disableSecure"
    private const val FIELD_ALLOW_AUDIO = "allowAudioCapture"
    private const val FIELD_BLOCK_DETECT = "blockCaptureDetect"

    private const val SCHEMA_VERSION = 2

    fun defaultBackupFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMillis))
        return "ScreenPolicy-backup-$dateStamp.json"
    }

    fun encode(config: GlobalPolicyConfig, prettyIndent: Int = 0): String {
        val root = JSONObject().apply {
            put(FIELD_VERSION, SCHEMA_VERSION)
            put(FIELD_FIX_GAME_DASHBOARD, config.fixGameDashboardRecord)
            put(FIELD_AUTO_CONFIRM_GAME_DASHBOARD, config.autoConfirmGameDashboardRecord)
            put(FIELD_VERBOSE_LOGGING, config.verboseLogging)

            val appsObj = JSONObject()
            config.appPolicies
                .entries
                .sortedBy { it.key }
                .forEach { (pkg, policy) ->
                    if (policy.hasAnyEnabled) {
                        appsObj.put(
                            pkg,
                            JSONObject().apply {
                                put(FIELD_ALLOW_SCREENSHOT, policy.allowScreenshot)
                                put(FIELD_ALLOW_SCREEN_RECORD, policy.allowScreenRecord)
                                put(FIELD_ALLOW_AUDIO, policy.allowAudioCapture)
                                put(FIELD_BLOCK_DETECT, policy.blockCaptureDetect)
                            }
                        )
                    }
                }
            put(FIELD_APPS, appsObj)
        }
        return if (prettyIndent > 0) root.toString(prettyIndent) else root.toString()
    }

    fun decode(rawJson: String?): GlobalPolicyConfig {
        if (rawJson.isNullOrBlank()) return GlobalPolicyConfig()
        val root = JSONObject(rawJson)
        val fixGameDashboard = root.optBoolean(FIELD_FIX_GAME_DASHBOARD, true)
        val autoConfirmGameDashboard = root.optBoolean(FIELD_AUTO_CONFIRM_GAME_DASHBOARD, false)
        val verboseLogging = root.optBoolean(FIELD_VERBOSE_LOGGING, false)
        val appsObj = root.optJSONObject(FIELD_APPS)

        val result = linkedMapOf<String, AppPolicy>()
        if (appsObj != null) {
            val keys = appsObj.keys()
            while (keys.hasNext()) {
                val pkg = keys.next().trim()
                if (pkg.isEmpty()) continue
                val item = appsObj.optJSONObject(pkg) ?: continue
                val legacySecure = item.optBoolean(FIELD_LEGACY_DISABLE_SECURE, false)
                val policy = AppPolicy(
                    allowScreenshot = item.optBoolean(FIELD_ALLOW_SCREENSHOT, legacySecure),
                    allowScreenRecord = item.optBoolean(FIELD_ALLOW_SCREEN_RECORD, legacySecure),
                    allowAudioCapture = item.optBoolean(FIELD_ALLOW_AUDIO, false),
                    blockCaptureDetect = item.optBoolean(FIELD_BLOCK_DETECT, false),
                )
                if (policy.hasAnyEnabled) {
                    result[pkg] = policy
                }
            }
        }
        return GlobalPolicyConfig(
            fixGameDashboardRecord = fixGameDashboard,
            autoConfirmGameDashboardRecord = autoConfirmGameDashboard,
            verboseLogging = verboseLogging,
            appPolicies = result,
        )
    }

    /**
     * Strictly validates an imported backup JSON file against the ScreenPolicy schema.
     * Throws IllegalArgumentException on corrupted JSON, unsupported version, missing required
     * sections, or mismatched field types so existing user configuration is never overwritten.
     */
    fun decodeBackupStrict(rawJson: String?): GlobalPolicyConfig {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw IllegalArgumentException("Backup file is not a valid JSON object")
        }

        val root = try {
            JSONObject(trimmed)
        } catch (t: Throwable) {
            throw IllegalArgumentException("Malformed JSON syntax", t)
        }

        val rawVersion = root.opt(FIELD_VERSION)
        val version = when (rawVersion) {
            is Int -> rawVersion
            is Long -> rawVersion.toInt()
            else -> throw IllegalArgumentException("Missing or invalid schema version")
        }
        if (version !in 1..SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported schema version: $version")
        }

        val rawApps = root.opt(FIELD_APPS)
        val appsObj = rawApps as? JSONObject
            ?: throw IllegalArgumentException("Missing or invalid 'apps' object")

        val fixGameDashboard = readStrictBoolean(root, FIELD_FIX_GAME_DASHBOARD, defaultValue = true)
        val autoConfirmGameDashboard =
            readStrictBoolean(root, FIELD_AUTO_CONFIRM_GAME_DASHBOARD, defaultValue = false)
        val verboseLogging = readStrictBoolean(root, FIELD_VERBOSE_LOGGING, defaultValue = false)

        val result = linkedMapOf<String, AppPolicy>()
        val keys = appsObj.keys()
        while (keys.hasNext()) {
            val rawPkg = keys.next()
            val pkg = rawPkg.trim()
            if (pkg.isEmpty() || !pkg.contains('.') || pkg.any { it.isWhitespace() }) {
                throw IllegalArgumentException("Invalid package key: $rawPkg")
            }
            val item = appsObj.opt(rawPkg) as? JSONObject
                ?: throw IllegalArgumentException("App entry for $pkg must be a JSON object")

            val legacySecure = readStrictBoolean(item, FIELD_LEGACY_DISABLE_SECURE, defaultValue = false)
            val policy = AppPolicy(
                allowScreenshot = readStrictBoolean(item, FIELD_ALLOW_SCREENSHOT, defaultValue = legacySecure),
                allowScreenRecord = readStrictBoolean(item, FIELD_ALLOW_SCREEN_RECORD, defaultValue = legacySecure),
                allowAudioCapture = readStrictBoolean(item, FIELD_ALLOW_AUDIO, defaultValue = false),
                blockCaptureDetect = readStrictBoolean(item, FIELD_BLOCK_DETECT, defaultValue = false),
            )
            if (policy.hasAnyEnabled) {
                result[pkg] = policy
            }
        }

        return GlobalPolicyConfig(
            fixGameDashboardRecord = fixGameDashboard,
            autoConfirmGameDashboardRecord = autoConfirmGameDashboard,
            verboseLogging = verboseLogging,
            appPolicies = result,
        )
    }

    private fun readStrictBoolean(obj: JSONObject, key: String, defaultValue: Boolean): Boolean {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        val value = obj.opt(key)
        return value as? Boolean
            ?: throw IllegalArgumentException("Field '$key' must be a boolean")
    }
}
