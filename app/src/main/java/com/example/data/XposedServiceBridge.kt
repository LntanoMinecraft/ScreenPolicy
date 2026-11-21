package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class XposedServiceState(
    val connected: Boolean = false,
    val frameworkName: String = "",
    val apiVersion: Int = 0,
    val scopes: Set<String> = emptySet(),
) {
    val hasSystemScope: Boolean
        get() = scopes.contains(PolicyConstants.SCOPE_SYSTEM) || scopes.contains("android")

    val hasSystemUiScope: Boolean
        get() = scopes.contains(PolicyConstants.SCOPE_SYSTEMUI)
}

/**
 * Manages the libxposed API 102 XposedService connection and Remote Preferences bridge.
 *
 * Direct Boot safety: XposedProvider is Direct Boot Aware, so onServiceBind may fire before
 * the user unlocks the device. Credential-encrypted local storage is only accessed after
 * verifying UserManager.isUserUnlocked.
 */
object XposedServiceBridge {
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeService: XposedService? = null

    private val _serviceState = MutableStateFlow(XposedServiceState())
    val serviceState: StateFlow<XposedServiceState> = _serviceState.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) return

        runCatching {
            XposedServiceHelper.registerListener(
                object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        activeService = service
                        publishServiceState(service)
                        syncPreferencesOnBindSafely(service)
                    }

                    override fun onServiceDied(service: XposedService) {
                        if (activeService === service) {
                            activeService = null
                        }
                        _serviceState.value = XposedServiceState()
                    }
                }
            )
        }
    }

    fun refreshScopeIfBound() {
        val service = activeService ?: return
        publishServiceState(service)
    }

    fun getRemotePreferences(): SharedPreferences? {
        val service = activeService ?: return null
        return runCatching {
            service.getRemotePreferences(PolicyConstants.REMOTE_PREFS_GROUP)
        }.getOrNull()
    }

    private fun publishServiceState(service: XposedService) {
        val name = runCatching { service.frameworkName }.getOrDefault("")
        val api = runCatching { service.apiVersion }.getOrDefault(0)
        val scopes = runCatching { service.scope.toSet() }.getOrDefault(emptySet())
        _serviceState.value = XposedServiceState(
            connected = true,
            frameworkName = name,
            apiVersion = api,
            scopes = scopes,
        )
    }

    private fun syncPreferencesOnBindSafely(service: XposedService) {
        val ctx = appContext ?: return
        if (!isUserUnlocked(ctx)) {
            // Direct Boot guard: defer credential-encrypted local storage access until unlocked
            return
        }
        runCatching {
            val remote = service.getRemotePreferences(PolicyConstants.REMOTE_PREFS_GROUP)
            val local = ctx.getSharedPreferences(PolicyConstants.POLICY_PREFS_NAME, Context.MODE_PRIVATE)
            val remoteJson = remote.getString(PolicyConstants.PREF_KEY_CONFIG_JSON, null)
            val localJson = local.getString(PolicyConstants.PREF_KEY_CONFIG_JSON, null)

            if (remoteJson.isNullOrBlank() && !localJson.isNullOrBlank()) {
                val config = PolicyJsonCodec.decode(localJson)
                remote.edit()
                    .putString(PolicyConstants.PREF_KEY_CONFIG_JSON, PolicyJsonCodec.encode(config))
                    .putBoolean(PolicyConstants.PREF_KEY_FIX_GAME_DASHBOARD, config.fixGameDashboardRecord)
                    .putBoolean(
                        PolicyConstants.PREF_KEY_AUTO_CONFIRM_GAME_DASHBOARD,
                        config.autoConfirmGameDashboardRecord,
                    )
                    .putBoolean(PolicyConstants.PREF_KEY_VERBOSE_LOGGING, config.verboseLogging)
                    .apply()
            } else if (!remoteJson.isNullOrBlank() && remoteJson != localJson) {
                val config = PolicyJsonCodec.decode(remoteJson)
                local.edit()
                    .putString(PolicyConstants.PREF_KEY_CONFIG_JSON, PolicyJsonCodec.encode(config))
                    .putBoolean(PolicyConstants.PREF_KEY_FIX_GAME_DASHBOARD, config.fixGameDashboardRecord)
                    .putBoolean(
                        PolicyConstants.PREF_KEY_AUTO_CONFIRM_GAME_DASHBOARD,
                        config.autoConfirmGameDashboardRecord,
                    )
                    .putBoolean(PolicyConstants.PREF_KEY_VERBOSE_LOGGING, config.verboseLogging)
                    .apply()
            }
        }
    }

    fun isUserUnlocked(context: Context): Boolean {
        return runCatching {
            val um = context.getSystemService(UserManager::class.java)
            um?.isUserUnlocked ?: true
        }.getOrDefault(true)
    }
}
