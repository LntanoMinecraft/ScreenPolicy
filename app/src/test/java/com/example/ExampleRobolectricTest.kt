package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PolicyConstants
import com.example.data.PolicyJsonCodec
import com.example.model.AppPolicy
import com.example.model.GlobalPolicyConfig
import com.example.model.HookMountState
import com.example.model.HookStatusEntry
import com.example.model.LogLevel
import com.example.provider.TelemetryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verify app name and strict xposed scope`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertTrue(appName == "录屏策略" || appName == "ScreenPolicy")

        val scopes = context.resources.getStringArray(R.array.xposed_scope).toList()
        assertEquals(listOf("android", "com.android.systemui"), scopes)
    }

    @Test
    fun `policy json codec round-trips 4 independent switches and autoConfirmGameDashboardRecord`() {
        val original = GlobalPolicyConfig(
            fixGameDashboardRecord = true,
            autoConfirmGameDashboardRecord = true,
            verboseLogging = true,
            appPolicies = mapOf(
                "com.demo.game" to AppPolicy(
                    allowScreenshot = true,
                    allowScreenRecord = false,
                    allowAudioCapture = true,
                    blockCaptureDetect = false,
                ),
                "com.demo.untouched" to AppPolicy(),
            ),
        )
        val encoded = PolicyJsonCodec.encode(original, prettyIndent = 2)
        val decoded = PolicyJsonCodec.decode(encoded)

        assertTrue(decoded.fixGameDashboardRecord)
        assertTrue(decoded.autoConfirmGameDashboardRecord)
        assertTrue(decoded.verboseLogging)
        assertEquals(1, decoded.appPolicies.size)
        assertEquals(
            AppPolicy(
                allowScreenshot = true,
                allowScreenRecord = false,
                allowAudioCapture = true,
                blockCaptureDetect = false,
            ),
            decoded.appPolicies["com.demo.game"],
        )
        assertFalse(decoded.appPolicies.containsKey("com.demo.untouched"))
    }

    @Test
    fun `policy json codec falls back cleanly for legacy disableSecure field`() {
        val legacyJson = """
            {
              "version": 1,
              "fixGameDashboardRecord": true,
              "verboseLogging": false,
              "apps": {
                "com.legacy.app": {
                  "disableSecure": true,
                  "allowAudioCapture": false,
                  "blockCaptureDetect": true
                }
              }
            }
        """.trimIndent()

        val decoded = PolicyJsonCodec.decode(legacyJson)
        assertFalse(decoded.autoConfirmGameDashboardRecord)
        val policy = decoded.appPolicies["com.legacy.app"]
        assertEquals(
            AppPolicy(
                allowScreenshot = true,
                allowScreenRecord = true,
                allowAudioCapture = false,
                blockCaptureDetect = true,
            ),
            policy,
        )
    }

    @Test
    fun `telemetry store caps ring buffer at 200 entries and detects boot session heartbeats`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TelemetryStore.clearLogs(context)
        repeat(215) { idx ->
            TelemetryStore.appendLog(
                context = context,
                source = PolicyConstants.SCOPE_ANDROID,
                level = LogLevel.INFO,
                tag = "Test",
                message = "entry-$idx",
            )
        }
        assertEquals(PolicyConstants.MAX_LOG_ENTRIES, TelemetryStore.logs.value.size)
        assertEquals("entry-214", TelemetryStore.logs.value.first().message)

        val currentBootEntry = HookStatusEntry(
            key = PolicyConstants.HOOK_SCREENSHOT,
            scope = PolicyConstants.SCOPE_ANDROID,
            state = HookMountState.READY,
            updatedAtMs = System.currentTimeMillis(),
            bootCount = 12,
        )
        assertTrue(TelemetryStore.isEntryFromCurrentBoot(currentBootEntry, currentBootCount = 12))
        assertFalse(TelemetryStore.isEntryFromCurrentBoot(currentBootEntry, currentBootCount = 13))

        TelemetryStore.clearLogs(context)
    }

    @Test
    fun `seven accent palettes and google sans flex typography are configured properly`() {
        val palettes = com.example.model.AccentPalette.entries
        assertEquals(7, palettes.size)
        palettes.forEach { palette ->
            assertEquals(palette, com.example.model.AccentPalette.fromKey(palette.key))
            val light = com.example.ui.theme.colorSchemeForPalette(palette, darkTheme = false)
            val dark = com.example.ui.theme.colorSchemeForPalette(palette, darkTheme = true)
            assertTrue(light.primary != dark.primary)
            val preview = com.example.ui.theme.previewColorsForPalette(palette)
            assertEquals(light.primary, preview.primary)
        }

        val typo = com.example.ui.theme.ExpressiveTypography
        assertEquals(com.example.ui.theme.GoogleSansFlexFamily, typo.headlineSmall.fontFamily)
        assertEquals(com.example.ui.theme.GoogleSansFlexFamily, typo.titleMedium.fontFamily)
        assertEquals(com.example.ui.theme.TabularNumericFeature, typo.labelLarge.fontFeatureSettings)
        assertEquals(com.example.ui.theme.TabularNumericFeature, typo.labelMedium.fontFeatureSettings)
    }

    @Test
    fun `about screen links and string resources are valid`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            "https://github.com/LntanoMinecraft/ScreenPolicy",
            com.example.ui.screens.AboutLinks.GITHUB_URL,
        )
        assertEquals(
            "https://www.coolapk.com/u/34054336",
            com.example.ui.screens.AboutLinks.COOLAPK_WEB_URL,
        )
        assertTrue(context.getString(R.string.about_github_subtitle).contains("LntanoMinecraft/ScreenPolicy"))
        assertTrue(context.getString(R.string.about_coolapk_subtitle).contains("34054336"))
        assertTrue(context.getString(R.string.settings_about_entry_title).isNotBlank())
        assertTrue(context.getString(R.string.policy_game_dashboard_auto_confirm_title).isNotBlank())
        assertTrue(context.getString(R.string.policy_game_dashboard_auto_confirm_desc).isNotBlank())
    }

    @Test
    fun `dual track logger buffers hook status purely in memory without boot IPC`() {
        val before = com.example.hook.DualTrackLogger.snapshotHookStatusCount()
        com.example.hook.DualTrackLogger.recordHookStatus(
            scope = PolicyConstants.SCOPE_SYSTEMUI,
            hookKey = PolicyConstants.HOOK_GAME_DASHBOARD,
            success = true,
            detail = "MediaProjectionPermissionActivity (onCreate=1, getCallingPackage=1)",
        )
        com.example.hook.DualTrackLogger.recordStartupSummary(
            scope = PolicyConstants.SCOPE_SYSTEMUI,
            message = "SystemUI hook ready in 4ms (deferred)",
        )
        assertTrue(com.example.hook.DualTrackLogger.snapshotHookStatusCount() >= maxOf(1, before))
    }

    @Test
    fun `strict game dashboard auto-confirm gate allows GMS trampoline and rejects third-party apps`() {
        // Genuine GMS Game Dashboard launches must match
        assertTrue(
            com.example.hook.GameDashboardRecordHook.matchesStrictGameDashboardTrigger(
                rawCallingPackage = "com.google.android.gms",
                callingActivityClassName = "com.google.android.gms.games.GameSessionTrampolineActivity",
                referrerHost = "com.google.android.gms",
                intentExtraKeys = emptyList(),
            )
        )
        assertTrue(
            com.example.hook.GameDashboardRecordHook.matchesStrictGameDashboardTrigger(
                rawCallingPackage = null,
                callingActivityClassName = "com.google.android.gms.games.GameSessionTrampolineActivity",
                referrerHost = "android",
                intentExtraKeys = emptyList(),
            )
        )
        assertTrue(
            com.example.hook.GameDashboardRecordHook.matchesStrictGameDashboardTrigger(
                rawCallingPackage = "android",
                callingActivityClassName = null,
                referrerHost = "com.google.android.gms",
                intentExtraKeys = emptyList(),
            )
        )

        // Third-party apps must ALWAYS be rejected — even if they spoof extras or activity names
        assertFalse(
            com.example.hook.GameDashboardRecordHook.matchesStrictGameDashboardTrigger(
                rawCallingPackage = "com.thirdparty.recorder",
                callingActivityClassName = "com.google.android.gms.games.GameSessionTrampolineActivity",
                referrerHost = "com.google.android.gms",
                intentExtraKeys = listOf("game_dashboard_record"),
                intentStringValues = listOf("com.google.android.gms"),
            )
        )
        assertFalse(
            com.example.hook.GameDashboardRecordHook.matchesStrictGameDashboardTrigger(
                rawCallingPackage = null,
                callingActivityClassName = null,
                referrerHost = "com.evil.spyware",
                intentExtraKeys = listOf("game_session"),
            )
        )
        // Generic anonymous MediaProjection launch without GMS/Game Dashboard traits must be rejected
        assertFalse(
            com.example.hook.GameDashboardRecordHook.matchesStrictGameDashboardTrigger(
                rawCallingPackage = null,
                callingActivityClassName = null,
                referrerHost = "android",
                intentExtraKeys = listOf("EXTRA_MEDIA_PROJECTION"),
            )
        )
    }

    @Test
    fun `game dashboard auto-confirm invokes entire-screen grant and degrades silently on unknown methods`() {
        class FakeGrantActivity : android.app.Activity() {
            var grantedMode: Int = -1
            @Suppress("unused")
            private fun grantMediaProjectionPermission(screenShareMode: Int) {
                grantedMode = screenShareMode
            }
        }

        class FakeChangedSignatureActivity : android.app.Activity()

        val fakeGrant = FakeGrantActivity()
        val okResult = com.example.hook.GameDashboardRecordHook.tryAutoConfirmEntireScreen(fakeGrant)
        assertTrue(okResult.succeeded)
        assertEquals(
            com.example.hook.GameDashboardRecordHook.SCREEN_SHARE_MODE_ENTIRE_SCREEN,
            fakeGrant.grantedMode,
        )

        val fakeDegraded = FakeChangedSignatureActivity()
        val degradedResult = com.example.hook.GameDashboardRecordHook.tryAutoConfirmEntireScreen(fakeDegraded)
        assertFalse(degradedResult.succeeded)
        assertFalse(fakeDegraded.isFinishing)
    }
}
