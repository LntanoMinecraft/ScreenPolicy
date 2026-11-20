package com.example.data

import com.example.model.AppPolicy
import com.example.model.GlobalPolicyConfig
import org.json.JSONObject

/**
 * Pure org.json codec shared between the app UI (SAF import/export, SharedPreferences)
 * and the system_server / SystemUI runtime cache.
 * Backward-compatible with legacy `disableSecure` configs on decode.
 */
object PolicyJsonCodec {
    private const val FIELD_VERSION = "version"
    private const val FIELD_FIX_GAME_DASHBOARD = "fixGameDashboardRecord"
    private const val FIELD_VERBOSE_LOGGING = "verboseLogging"
    private const val FIELD_APPS = "apps"
    private const val FIELD_ALLOW_SCREENSHOT = "allowScreenshot"
    private const val FIELD_ALLOW_SCREEN_RECORD = "allowScreenRecord"
    private const val FIELD_LEGACY_DISABLE_SECURE = "disableSecure"
    private const val FIELD_ALLOW_AUDIO = "allowAudioCapture"
    private const val FIELD_BLOCK_DETECT = "blockCaptureDetect"

    private const val SCHEMA_VERSION = 2

    fun encode(config: GlobalPolicyConfig, prettyIndent: Int = 0): String {
        val root = JSONObject().apply {
            put(FIELD_VERSION, SCHEMA_VERSION)
            put(FIELD_FIX_GAME_DASHBOARD, config.fixGameDashboardRecord)
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
            verboseLogging = verboseLogging,
            appPolicies = result,
        )
    }
}
