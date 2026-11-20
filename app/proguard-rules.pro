# ScreenPolicy R8 / ProGuard rules
# Keep only LSPosed entry point, IPC ContentProvider, and policy serialization models.

# 1. LSPosed module entry point declared in assets/xposed_init
-keep class com.example.hook.PolicyModuleEntry {
    public <init>();
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# 2. UID-verified TelemetryContentProvider declared in AndroidManifest.xml
-keep class com.example.provider.TelemetryContentProvider {
    *;
}

# 3. Allowlist serialization models
-keep class com.example.model.AppPolicy {
    *;
}
-keep class com.example.model.GlobalPolicyConfig {
    *;
}

# 4. Provided compileOnly Xposed API classes exist at runtime in LSPosed
-dontwarn de.robv.android.xposed.**

# Preserve concise stack trace metadata for Android 17 field diagnostics
-keepattributes SourceFile,LineNumberTable
