package com.example.data

object PolicyConstants {
    const val MODULE_PACKAGE = "com.lntano.screenpolicy"
    const val POLICY_PREFS_NAME = "policy_config"
    const val UI_PREFS_NAME = "ui_appearance"

    const val PREF_KEY_CONFIG_JSON = "config_json"
    const val PREF_KEY_FIX_GAME_DASHBOARD = "fix_game_dashboard_record"
    const val PREF_KEY_VERBOSE_LOGGING = "verbose_logging"

    const val PREF_UI_THEME_MODE = "theme_mode"
    const val PREF_UI_DYNAMIC_COLOR = "dynamic_color"
    const val PREF_UI_PALETTE = "accent_palette"

    const val SCOPE_ANDROID = "android"
    const val SCOPE_SYSTEMUI = "com.android.systemui"

    const val HOOK_SCREENSHOT = "screenshot_capture"
    const val HOOK_SCREEN_RECORD = "screen_record"
    const val HOOK_AUDIO_CAPTURE = "audio_capture"
    const val HOOK_CAPTURE_DETECT = "capture_detect"
    const val HOOK_GAME_DASHBOARD = "game_dashboard"

    const val PROVIDER_AUTHORITY = "com.lntano.screenpolicy.telemetry"
    const val PROVIDER_URI_STRING = "content://$PROVIDER_AUTHORITY"

    const val METHOD_REPORT_STATUS = "report_status"
    const val METHOD_REPORT_LOG = "report_log"

    const val EXTRA_HOOK_KEY = "hook_key"
    const val EXTRA_SCOPE = "scope"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_LEVEL = "level"
    const val EXTRA_TAG = "tag"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_TIMESTAMP = "timestamp"

    const val MAX_LOG_ENTRIES = 200
}
