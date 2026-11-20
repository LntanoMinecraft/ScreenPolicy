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
    fun `policy json codec round-trips 4 independent switches and omits disabled entries`() {
        val original = GlobalPolicyConfig(
            fixGameDashboardRecord = true,
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
}
