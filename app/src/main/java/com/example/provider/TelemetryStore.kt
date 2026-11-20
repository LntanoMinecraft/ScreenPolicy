package com.example.provider

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.data.PolicyConstants
import com.example.model.HookMountState
import com.example.model.HookStatusEntry
import com.example.model.LogEntry
import com.example.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe local ring buffer (max 200 entries) and current-boot hook status store
 * backed by private app files.
 */
object TelemetryStore {
    private const val LOG_FILE_NAME = "telemetry_logs.json"
    private const val STATUS_FILE_NAME = "hook_status.json"

    private val lock = Any()
    private val nextId = AtomicLong(1L)

    @Volatile
    private var initialized = false

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _hookStatuses = MutableStateFlow<Map<String, HookStatusEntry>>(defaultHookStatusMap())
    val hookStatuses: StateFlow<Map<String, HookStatusEntry>> = _hookStatuses.asStateFlow()

    fun currentBootCount(context: Context): Int {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }

    fun isEntryFromCurrentBoot(entry: HookStatusEntry, currentBootCount: Int): Boolean {
        if (entry.state == HookMountState.WAITING || entry.updatedAtMs <= 0L) return false
        if (currentBootCount >= 0 && entry.bootCount >= 0) {
            return entry.bootCount == currentBootCount
        }
        val estimatedBootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return entry.updatedAtMs >= estimatedBootTimeMs - 10_000L
    }

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val appContext = context.applicationContext
            loadLogsLocked(appContext)
            loadStatusesLocked(appContext)
            initialized = true
        }
    }

    fun reload(context: Context) {
        synchronized(lock) {
            val appContext = context.applicationContext
            loadLogsLocked(appContext)
            loadStatusesLocked(appContext)
            initialized = true
        }
    }

    fun appendLog(
        context: Context,
        source: String,
        level: LogLevel,
        tag: String,
        message: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        ensureLoaded(context)
        synchronized(lock) {
            val entry = LogEntry(
                id = nextId.getAndIncrement(),
                timestampMs = timestampMs,
                source = source,
                level = level,
                tag = tag,
                message = message.take(512),
            )
            val updated = ArrayList<LogEntry>(_logs.value.size + 1)
            updated.add(entry)
            updated.addAll(_logs.value)
            if (updated.size > PolicyConstants.MAX_LOG_ENTRIES) {
                updated.subList(PolicyConstants.MAX_LOG_ENTRIES, updated.size).clear()
            }
            _logs.value = updated
            persistLogsLocked(context.applicationContext, updated)
        }
    }

    fun updateHookStatus(
        context: Context,
        hookKey: String,
        scope: String,
        success: Boolean,
        detail: String,
        timestampMs: Long = System.currentTimeMillis(),
        bootCount: Int = currentBootCount(context),
    ) {
        ensureLoaded(context)
        synchronized(lock) {
            val current = _hookStatuses.value.toMutableMap()
            current[hookKey] = HookStatusEntry(
                key = hookKey,
                scope = scope,
                state = if (success) HookMountState.READY else HookMountState.FAILED,
                detail = detail.take(256),
                updatedAtMs = timestampMs,
                bootCount = bootCount,
            )
            _hookStatuses.value = current
            persistStatusesLocked(context.applicationContext, current)
        }
    }

    fun clearLogs(context: Context) {
        ensureLoaded(context)
        synchronized(lock) {
            _logs.value = emptyList()
            val file = File(context.applicationContext.filesDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun clearStatuses(context: Context) {
        synchronized(lock) {
            _hookStatuses.value = defaultHookStatusMap()
            val file = File(context.applicationContext.filesDir, STATUS_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun defaultHookStatusMap(): Map<String, HookStatusEntry> = linkedMapOf(
        PolicyConstants.HOOK_SCREENSHOT to HookStatusEntry(
            key = PolicyConstants.HOOK_SCREENSHOT,
            scope = PolicyConstants.SCOPE_ANDROID,
        ),
        PolicyConstants.HOOK_SCREEN_RECORD to HookStatusEntry(
            key = PolicyConstants.HOOK_SCREEN_RECORD,
            scope = PolicyConstants.SCOPE_ANDROID,
        ),
        PolicyConstants.HOOK_AUDIO_CAPTURE to HookStatusEntry(
            key = PolicyConstants.HOOK_AUDIO_CAPTURE,
            scope = PolicyConstants.SCOPE_ANDROID,
        ),
        PolicyConstants.HOOK_CAPTURE_DETECT to HookStatusEntry(
            key = PolicyConstants.HOOK_CAPTURE_DETECT,
            scope = PolicyConstants.SCOPE_ANDROID,
        ),
        PolicyConstants.HOOK_GAME_DASHBOARD to HookStatusEntry(
            key = PolicyConstants.HOOK_GAME_DASHBOARD,
            scope = PolicyConstants.SCOPE_SYSTEMUI,
        ),
    )

    private fun loadLogsLocked(context: Context) {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (!file.exists()) {
            _logs.value = emptyList()
            return
        }
        runCatching {
            val arr = JSONArray(file.readText())
            val list = ArrayList<LogEntry>(arr.length())
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("id", (i + 1).toLong())
                if (id > maxId) maxId = id
                list.add(
                    LogEntry(
                        id = id,
                        timestampMs = obj.optLong("ts", 0L),
                        source = obj.optString("src", "app"),
                        level = LogLevel.fromCode(obj.optString("lvl", "INFO")),
                        tag = obj.optString("tag", "Core"),
                        message = obj.optString("msg", ""),
                    )
                )
            }
            nextId.set(maxId + 1L)
            _logs.value = list.take(PolicyConstants.MAX_LOG_ENTRIES)
        }.onFailure {
            _logs.value = emptyList()
        }
    }

    private fun persistLogsLocked(context: Context, entries: List<LogEntry>) {
        runCatching {
            val arr = JSONArray()
            for (entry in entries) {
                arr.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("ts", entry.timestampMs)
                        put("src", entry.source)
                        put("lvl", entry.level.name)
                        put("tag", entry.tag)
                        put("msg", entry.message)
                    }
                )
            }
            val target = File(context.filesDir, LOG_FILE_NAME)
            val tmp = File(context.filesDir, "$LOG_FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    private fun loadStatusesLocked(context: Context) {
        val defaults = defaultHookStatusMap().toMutableMap()
        val file = File(context.filesDir, STATUS_FILE_NAME)
        if (!file.exists()) {
            _hookStatuses.value = defaults
            return
        }
        runCatching {
            val root = JSONObject(file.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!defaults.containsKey(key)) continue
                val obj = root.optJSONObject(key) ?: continue
                val stateName = obj.optString("state", HookMountState.WAITING.name)
                val state = runCatching { HookMountState.valueOf(stateName) }
                    .getOrDefault(HookMountState.WAITING)
                defaults[key] = HookStatusEntry(
                    key = key,
                    scope = obj.optString("scope", PolicyConstants.SCOPE_ANDROID),
                    state = state,
                    detail = obj.optString("detail", ""),
                    updatedAtMs = obj.optLong("ts", 0L),
                    bootCount = obj.optInt("boot", -1),
                )
            }
            _hookStatuses.value = defaults
        }.onFailure {
            _hookStatuses.value = defaults
        }
    }

    private fun persistStatusesLocked(context: Context, map: Map<String, HookStatusEntry>) {
        runCatching {
            val root = JSONObject()
            map.forEach { (key, entry) ->
                root.put(
                    key,
                    JSONObject().apply {
                        put("scope", entry.scope)
                        put("state", entry.state.name)
                        put("detail", entry.detail)
                        put("ts", entry.updatedAtMs)
                        put("boot", entry.bootCount)
                    }
                )
            }
            val target = File(context.filesDir, STATUS_FILE_NAME)
            val tmp = File(context.filesDir, "$STATUS_FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }
    }
}
