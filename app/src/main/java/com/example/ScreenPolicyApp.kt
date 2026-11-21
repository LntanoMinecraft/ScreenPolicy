package com.example

import android.app.Application
import com.example.data.XposedServiceBridge

/**
 * Application entry point registering the libxposed API 102 XposedService listener.
 * Direct Boot safe: XposedServiceBridge checks UserManager.isUserUnlocked before accessing
 * credential-encrypted local storage.
 */
class ScreenPolicyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceBridge.init(this)
    }
}
