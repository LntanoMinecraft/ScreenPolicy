# ScreenPolicy R8 / ProGuard rules (Modern Xposed API 102)
# Official libxposed/api rules + IPC ContentProvider and policy serialization models.

-dontwarn io.github.libxposed.annotation.**
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# UID-verified TelemetryContentProvider declared in AndroidManifest.xml
-keep class com.example.provider.TelemetryContentProvider {
    *;
}

# Allowlist serialization models
-keep class com.example.model.AppPolicy {
    *;
}
-keep class com.example.model.GlobalPolicyConfig {
    *;
}

# Preserve concise stack trace metadata for Android 17 field diagnostics
-keepattributes SourceFile,LineNumberTable
