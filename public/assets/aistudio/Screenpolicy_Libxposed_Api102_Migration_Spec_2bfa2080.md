# 录屏策略（ScreenPolicy）迁移至最新 libxposed API 102 工程改造指南

> **升级目标**：彻底消除 LSPosed 管理器中的 `legacy` 标记及「此模块使用了已废弃且即将移除的功能」黄色警告，全面升级至现代 **libxposed API 102** 标准（与最新 LSPosed 框架对齐），用官方 `Remote Preferences` 与 `XposedService` 替代已废弃的 `XSharedPreferences / MODE_WORLD_READABLE`。
> **使用方式**：直接将下方「迁移 Prompt 正文」发送给 AI Studio 中的 **Gemini 4 Neon** 即可完成无缝迁移。

---

## 迁移 Prompt 正文

你是一位熟悉最新 **libxposed Modern Xposed API 102** 的资深 Android Framework 与 LSPosed 工程师。目前 **录屏策略（ScreenPolicy）** 仍在使用旧版 Xposed API 82（`de.robv.android.xposed.*` 与 `XSharedPreferences / MODE_WORLD_READABLE`），在最新 LSPosed 管理器中显示为 `legacy` 并触发「已废弃且即将移除的功能」警告。

请**完整保留现有全部功能（4 项白名单开关、分屏隐私隔离、A17 游戏侧边栏高画质录屏修复与免疫 R8 混淆的自动全屏确认、SAF 配置备份恢复、M3 Expressive UI、双语、新版图标、关于二级页）、防卡开机机制（`SystemUI` 延迟异步挂载、零 `ApplicationPackageManager` Hook、启动期零 IPC）以及已跑通的 GitHub Actions 工作流（保留 `gradle-version: wrapper`、`gradle` 命令与无 `-x lintVitalAnalyzeRelease`）**，将模块底层彻底迁移至最新 **libxposed API 102**：

### 一、依赖替换与彻底清除 Legacy API

1. **更新 `app/build.gradle.kts` 与 `gradle/libs.versions.toml`**：
   * 彻底移除旧版 `de.robv.android.xposed:api:82` 依赖，并清除源码中所有 `de.robv.android.xposed.*` 导入（**API 102 模块禁止调用任何 Legacy Xposed API**）；
   * 添加 Maven Central 官方最新依赖（参考 [libxposed/api](https://github.com/libxposed/api) 与 [libxposed/service](https://github.com/libxposed/service)）：
     ```kotlin
     compileOnly("io.github.libxposed:api:102.0.0")
     implementation("io.github.libxposed:service:102.0.1")
     ```
2. **清理旧版元数据与入口文件**：
   * 删除 `app/src/main/assets/xposed_init`；
   * 删除 `AndroidManifest.xml` 中全部旧版 `<meta-data>`（`xposedmodule`、`xposeddescription`、`xposedminversion`、`xposedscope`、`xposedsharedprefs`）；
   * 在 `AndroidManifest.xml` 的 `<application>` 标签上使用标准 `android:label`（模块名称）与 `android:description`（模块简介，如 `@string/module_description`）。

### 二、配置 Modern Xposed API 102 元数据（`src/main/resources/META-INF/xposed/`）

在 `app/src/main/resources/META-INF/xposed/` 目录下新增以下 3 个标准文件（由 Gradle 自动打入 APK）：

1. **`META-INF/xposed/module.prop`**：
   ```properties
   minApiVersion=102
   targetApiVersion=102
   staticScope=true
   exceptionMode=protective
   ```
   *(设置 `staticScope=true` 从框架层锁死作用域，防止误勾选任何游戏进程；`exceptionMode=protective` 进一步保护系统进程稳定。)*
2. **`META-INF/xposed/java_init.list`**：
   * 仅写一行入口类全限定名（如 `com.example.hook.PolicyModuleEntry`）。
3. **`META-INF/xposed/scope.list`**：
   ```text
   system
   com.android.systemui
   ```
   *(关键规范：Modern Xposed API 102 中 `system_server` 必须使用虚拟包名 **`system`**，而非旧版 `android`；系统界面仍为 `com.android.systemui`。)*

### 三、重写模块入口与拦截器链（`XposedModule` API 102）

1. **入口类继承 `io.github.libxposed.api.XposedModule`**：
   * 提供**公开无参构造函数**（`public <init>()`），构造函数内不做任何初始化，所有逻辑从 `onModuleLoaded(...)` 起执行；
   * 使用现代生命周期回调：
     * 在 `onSystemServerStarting(...)` 中，保持主线程零阻塞，派发至 `MIN_PRIORITY` 后台线程挂载 `system_server` 的 4 项白名单 Hook（`ScreenshotHook`、`ScreenRecordHook`、`AudioCaptureHook`、`CaptureDetectHook`）；
     * 在 `onPackageReady(...)` 中校验 `param.packageName == "com.android.systemui"`，继续保持 **12 秒延迟异步挂载** `GameDashboardRecordHook`，彻底规避 `SystemUI` 10 秒启动超时。
2. **改用 OkHttp 风格 `Hooker<T>` 拦截器链**：
   * 所有 Hook 统一迁移至现代 API：`hook(method).intercept { chain -> ... chain.proceed() ... }`；
   * 因现代 API 不再提供 `XposedHelpers`，统一使用极轻量的标准 Kotlin 反射工具封装（精确 `declaredMethods` / `declaredFields` 查找），严禁引入 DexKit 或宽泛全重载 Hook；对系统框架可能被 ART 内联的关键方法可按需调用 `deoptimize(method)`。

### 四、升级配置同步与状态检测（`libxposed/service` + `Remote Preferences`）

1. **彻底废弃 `XSharedPreferences` / `MODE_WORLD_READABLE`**：
   * **App 端**：在 `Application` 中通过 `XposedServiceHelper.registerListener(...)` 监听 `XposedService` 连接（**注意 Direct Boot 兼容**：因 `XposedProvider` 为 Direct Boot Aware，在设备首次解锁前回调可能提前触发，访问凭据加密存储前必须校验 `UserManager.isUserUnlocked()`）。
   * 通过 `service.getRemotePreferences("screen_policy")` 读写白名单与全局策略（同时本地可保留一份用于未激活时预览与 SAF 导出/导入的镜像）；
   * **Hook 端**：通过 `XposedModule.getRemotePreferences("screen_policy")` 读取配置，并注册 `OnSharedPreferenceChangeListener` 实时刷新内存 `ConcurrentHashMap` 缓存，实现真正的零磁盘轮询、毫秒级热更新！
2. **首页三态激活检测升级**：
   * 结合 `XposedService` 实时状态（`service.frameworkName`、`service.apiVersion`、`service.scope`）与异步遥测，首页卡片可即时展示当前框架与 `API 102` 连接状态、作用域校验结果（检查 `system` 与 `com.android.systemui`）以及全部 5 项 Hook 的挂载明细与耗时。

### 五、更新官方 R8 / ProGuard 规则（`proguard-rules.pro`）

严格遵循 [libxposed/api](https://github.com/libxposed/api) 官方推荐规则，替换旧版 Xposed 保留规则：

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```
*(同时保留 `TelemetryContentProvider` 及配置模型类的必要保留规则。)*

### 六、输出要求

请直接完成上述全部 API 102 迁移修改，验证 Debug 编译、Robolectric 单元测试与 Release R8 混淆打包全部通过，并输出简明迁移变更清单（重点确认已完全清除 `de.robv.android.xposed` 与 `MODE_WORLD_READABLE` 引用）。
