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
    fun `verify app name and modern libxposed API 102 metadata resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertTrue(appName == "录屏策略" || appName == "ScreenPolicy")

        val classLoader = javaClass.classLoader!!
        val modulePropLines = classLoader.getResourceAsStream("META-INF/xposed/module.prop")
            ?.bufferedReader()?.readLines()?.filter { it.isNotEmpty() }.orEmpty()
        assertEquals(
            listOf("minApiVersion=102", "targetApiVersion=102", "staticScope=true"),
            modulePropLines,
        )

        val javaInit = classLoader.getResourceAsStream("META-INF/xposed/java_init.list")
            ?.bufferedReader()?.readLines()?.filter { it.isNotEmpty() }.orEmpty()
        assertEquals(listOf("com.example.hook.PolicyModuleEntry"), javaInit)

        val scopes = classLoader.getResourceAsStream("META-INF/xposed/scope.list")
            ?.bufferedReader()?.readLines()?.filter { it.isNotEmpty() }.orEmpty()
        assertEquals(listOf("system", "com.android.systemui"), scopes)
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
                source = PolicyConstants.SCOPE_SYSTEM,
                level = LogLevel.INFO,
                tag = "Test",
                message = "entry-$idx",
            )
        }
        assertEquals(PolicyConstants.MAX_LOG_ENTRIES, TelemetryStore.logs.value.size)
        assertEquals("entry-214", TelemetryStore.logs.value.first().message)

        val currentBootEntry = HookStatusEntry(
            key = PolicyConstants.HOOK_SCREENSHOT,
            scope = PolicyConstants.SCOPE_SYSTEM,
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
    fun `r8-immune auto-confirm finds obfuscated dialog by type, switches spinner to entire screen, and clicks confirm`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
            .create()
            .resume()
            .get()

        var selectedIdx = -1
        var positiveClicked = false

        val spinner = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                listOf("单个应用", "整个屏幕"),
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    selectedIdx = position
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(spinner)
            .setPositiveButton("开始") { _, _ -> positiveClicked = true }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        // Obfuscated field name ("a") holding the showing dialog to simulate R8-minified SystemUI
        class ObfuscatedPermissionActivity : android.app.Activity() {
            @Suppress("unused")
            private val a: android.app.Dialog = dialog
        }

        val obfuscatedActivity = ObfuscatedPermissionActivity()
        val lookup = com.example.hook.GameDashboardRecordHook.findDialogByType(obfuscatedActivity)
        assertEquals(dialog, lookup.showingDialog)

        val step2 = com.example.hook.GameDashboardRecordHook.selectEntireScreenOptionInDialog(dialog)
        assertTrue(step2.succeeded)
        assertEquals(1, spinner.selectedItemPosition)
        assertEquals(1, selectedIdx)

        val step3 = com.example.hook.GameDashboardRecordHook.clickConfirmButtonInDialog(dialog)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(step3.succeeded)
        assertTrue(positiveClicked)

        dialog.dismiss()
    }

    @Test
    fun `r8-immune auto-confirm handles custom button layout, English labels, fallback index, and silent degradation`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
            .create()
            .resume()
            .get()

        var customStartClicked = false
        val container = android.widget.LinearLayout(activity)
        val spinner = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                listOf("Single app", "Entire screen"),
            )
        }
        val cancelBtn = android.widget.Button(activity).apply {
            text = "Cancel"
        }
        val startBtn = android.widget.Button(activity).apply {
            text = "Start"
            setOnClickListener { customStartClicked = true }
        }
        container.addView(spinner)
        container.addView(cancelBtn)
        container.addView(startBtn)

        val customDialog = android.app.Dialog(activity).apply {
            setContentView(container)
        }
        customDialog.show()

        val step2 = com.example.hook.GameDashboardRecordHook.selectEntireScreenOptionInDialog(customDialog)
        assertTrue(step2.succeeded)
        assertEquals(1, spinner.selectedItemPosition)

        val step3 = com.example.hook.GameDashboardRecordHook.clickConfirmButtonInDialog(customDialog)
        assertTrue(step3.succeeded)
        assertTrue(customStartClicked)
        customDialog.dismiss()

        // Silent degradation when activity has no Dialog field
        val emptyActivity = android.app.Activity()
        val missingLookup = com.example.hook.GameDashboardRecordHook.findDialogByType(emptyActivity)
        assertFalse(missingLookup.anyDialogFound)
        assertEquals(null, missingLookup.showingDialog)
        assertFalse(emptyActivity.isFinishing)

        // Verify updated high-quality recording bilingual copy exists
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.getString(R.string.policy_game_dashboard_title).isNotBlank())
        assertTrue(context.getString(R.string.policy_game_dashboard_desc).isNotBlank())
        assertTrue(com.example.hook.GameDashboardRecordHook.matchesConfirmButtonText("立即开始"))
        assertTrue(com.example.hook.GameDashboardRecordHook.matchesConfirmButtonText("Start"))
        assertFalse(com.example.hook.GameDashboardRecordHook.matchesConfirmButtonText("Cancel"))
        assertFalse(com.example.hook.GameDashboardRecordHook.matchesConfirmButtonText("取消"))
    }

    @Test
    fun `backup codec generates dated filename and strictly validates imported JSON schema`() {
        val fileName = PolicyJsonCodec.defaultBackupFileName()
        assertTrue(Regex("^ScreenPolicy-backup-\\d{8}\\.json$").matches(fileName))

        val original = GlobalPolicyConfig(
            fixGameDashboardRecord = true,
            autoConfirmGameDashboardRecord = true,
            verboseLogging = false,
            appPolicies = mapOf(
                "com.demo.bank" to AppPolicy(
                    allowScreenshot = true,
                    allowScreenRecord = true,
                    allowAudioCapture = false,
                    blockCaptureDetect = true,
                ),
            ),
        )
        val exported = PolicyJsonCodec.encode(original, prettyIndent = 2)
        val restored = PolicyJsonCodec.decodeBackupStrict(exported)
        assertEquals(original, restored)

        val invalidSamples = listOf(
            "",
            "not json at all",
            "{}",
            """{"hello":"world"}""",
            """{"version":99,"apps":{}}""",
            """{"version":2,"apps":[]}""",
            """{"version":2,"fixGameDashboardRecord":"yes","apps":{}}""",
            """{"version":2,"apps":{"invalid_pkg_no_dot":{"allowScreenshot":true}}}""",
            """{"version":2,"apps":{"com.demo.app":"not_an_object"}}""",
            """{"version":2,"apps":{"com.demo.app":{"allowScreenshot":"true"}}}""",
        )
        for (sample in invalidSamples) {
            val failed = runCatching { PolicyJsonCodec.decodeBackupStrict(sample) }.isFailure
            assertTrue("Expected schema rejection for: $sample", failed)
        }
    }

    @Test
    fun `policy cache applies remote preferences immediately and service state evaluates scopes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_remote_prefs_mirror", Context.MODE_PRIVATE)
        val config = GlobalPolicyConfig(
            fixGameDashboardRecord = true,
            autoConfirmGameDashboardRecord = true,
            verboseLogging = true,
            appPolicies = mapOf(
                "com.example.protected" to AppPolicy(
                    allowScreenshot = true,
                    allowScreenRecord = false,
                    allowAudioCapture = true,
                    blockCaptureDetect = true,
                ),
            ),
        )
        prefs.edit()
            .putString(PolicyConstants.PREF_KEY_CONFIG_JSON, PolicyJsonCodec.encode(config))
            .putBoolean(PolicyConstants.PREF_KEY_FIX_GAME_DASHBOARD, true)
            .putBoolean(PolicyConstants.PREF_KEY_AUTO_CONFIRM_GAME_DASHBOARD, true)
            .putBoolean(PolicyConstants.PREF_KEY_VERBOSE_LOGGING, true)
            .commit()

        com.example.hook.PolicyCache.applyFromPreferences(prefs)
        assertTrue(com.example.hook.PolicyCache.isScreenshotAllowed("com.example.protected"))
        assertFalse(com.example.hook.PolicyCache.isScreenRecordAllowed("com.example.protected"))
        assertTrue(com.example.hook.PolicyCache.isAudioCaptureAllowed("com.example.protected"))
        assertTrue(com.example.hook.PolicyCache.isBlockCaptureDetectEnabled("com.example.protected"))
        assertTrue(com.example.hook.PolicyCache.isGameDashboardFixEnabled())
        assertTrue(com.example.hook.PolicyCache.isGameDashboardAutoConfirmEnabled())
        assertTrue(com.example.hook.PolicyCache.isVerboseEnabled())

        val fullState = com.example.data.XposedServiceState(
            connected = true,
            frameworkName = "LSPosed",
            apiVersion = 102,
            scopes = setOf("system", "com.android.systemui"),
        )
        assertTrue(fullState.hasSystemScope)
        assertTrue(fullState.hasSystemUiScope)

        val partialState = com.example.data.XposedServiceState(
            connected = true,
            frameworkName = "LSPosed",
            apiVersion = 102,
            scopes = setOf("system"),
        )
        assertTrue(partialState.hasSystemScope)
        assertFalse(partialState.hasSystemUiScope)
    }

    @Suppress("unused")
    private class FakeAudioSystem {
        companion object {
            @JvmStatic
            var lastUid: Int = -1

            @JvmStatic
            var lastFlags: Int = -1

            @JvmStatic
            fun setAllowedCapturePolicy(uid: Int, flags: Int): Int {
                lastUid = uid
                lastFlags = flags
                return 0
            }

            @JvmStatic
            fun setAllowedCapturePolicy(uid: Int, flags: String): Int = -1
        }
    }

    @Suppress("unused")
    private class FakePlaybackActivityMonitor {
        var lastUid: Int = -1
        var lastPolicy: Int = -1

        fun setAllowedCapturePolicy(uid: Int, capturePolicy: Int): Int {
            lastUid = uid
            lastPolicy = capturePolicy
            return 0
        }
    }

    @Test
    fun `audio capture hook clears audioserver flags, flushes cache, and works independently of screenshot or record flags`() {
        // 1. Exact (int, int) method matching on AudioSystem
        val matched = com.example.hook.AudioCaptureHook.selectAudioSystemSetPolicyMethod(
            FakeAudioSystem::class.java,
        )
        assertTrue(matched != null)
        assertEquals(2, matched!!.parameterCount)
        assertEquals(Int::class.javaPrimitiveType, matched.parameterTypes[0])
        assertEquals(Int::class.javaPrimitiveType, matched.parameterTypes[1])

        // 2. Clears AUDIO_FLAG_NO_MEDIA_PROJECTION (0x400) | AUDIO_FLAG_NO_SYSTEM_CAPTURE (0x800)
        assertEquals(
            0,
            com.example.hook.AudioCaptureHook.computeEffectiveAudioSystemFlags(
                currentFlags = 0x400 or 0x800,
                isAllowlisted = true,
            ),
        )
        assertEquals(
            0x400,
            com.example.hook.AudioCaptureHook.computeEffectiveAudioSystemFlags(
                currentFlags = 0x400,
                isAllowlisted = false,
            ),
        )

        // 3. Service policy forced to ALLOW_CAPTURE_BY_ALL (1) when allowlisted
        assertEquals(
            com.example.hook.AudioCaptureHook.ALLOW_CAPTURE_BY_ALL,
            com.example.hook.AudioCaptureHook.computeEffectiveServiceCapturePolicy(
                currentPolicy = 3,
                isAllowlisted = true,
            ),
        )
        assertEquals(
            3,
            com.example.hook.AudioCaptureHook.computeEffectiveServiceCapturePolicy(
                currentPolicy = 3,
                isAllowlisted = false,
            ),
        )

        // 4. Active cache flush invokes AudioSystem(uid, 0) and PlaybackActivityMonitor(uid, 1)
        FakeAudioSystem.lastUid = -1
        FakeAudioSystem.lastFlags = -1
        val fakeMonitor = FakePlaybackActivityMonitor()
        val (audioSysOk, monitorOk) = com.example.hook.AudioCaptureHook.flushPolicyForTarget(
            uid = 10288,
            audioSystemClass = FakeAudioSystem::class.java,
            playbackMonitorInstance = fakeMonitor,
        )
        assertTrue(audioSysOk)
        assertTrue(monitorOk)
        assertEquals(10288, FakeAudioSystem.lastUid)
        assertEquals(0, FakeAudioSystem.lastFlags)
        assertEquals(10288, fakeMonitor.lastUid)
        assertEquals(1, fakeMonitor.lastPolicy)

        // 5. Audio-only allowlist works independently of screenshot/screenRecord and notifies listeners
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_audio_only_prefs", Context.MODE_PRIVATE)
        val audioOnlyConfig = GlobalPolicyConfig(
            appPolicies = mapOf(
                "com.tencent.tmgp.dfm" to AppPolicy(
                    allowScreenshot = false,
                    allowScreenRecord = false,
                    allowAudioCapture = true,
                    blockCaptureDetect = false,
                ),
            ),
        )
        prefs.edit()
            .putString(PolicyConstants.PREF_KEY_CONFIG_JSON, PolicyJsonCodec.encode(audioOnlyConfig))
            .commit()

        var listenerTriggered = 0
        val listener: () -> Unit = { listenerTriggered++ }
        com.example.hook.PolicyCache.addPolicyChangeListener(listener)
        try {
            com.example.hook.PolicyCache.applyFromPreferences(prefs)
            assertEquals(1, listenerTriggered)
            assertTrue(com.example.hook.PolicyCache.isAudioCaptureAllowed("com.tencent.tmgp.dfm"))
            assertFalse(com.example.hook.PolicyCache.isScreenshotAllowed("com.tencent.tmgp.dfm"))
            assertFalse(com.example.hook.PolicyCache.isScreenRecordAllowed("com.tencent.tmgp.dfm"))
            assertEquals(
                setOf("com.tencent.tmgp.dfm"),
                com.example.hook.PolicyCache.getAudioCaptureAllowedPackages(),
            )
            com.example.hook.PolicyCache.registerResolvedUid(10288, "com.tencent.tmgp.dfm")
            assertEquals(
                "com.tencent.tmgp.dfm",
                com.example.hook.PolicyCache.findAllowlistedPackageForUid(10288) { it.allowAudioCapture },
            )
        } finally {
            com.example.hook.PolicyCache.removePolicyChangeListener(listener)
        }
    }
}
