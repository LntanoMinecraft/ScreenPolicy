# 录屏策略（ScreenPolicy）恶性卡开机 Bug 根治与 DEX/启动极速优化开发文档

> **背景故障**：勾选 `com.android.systemui`（系统界面）并重启后，系统卡在开机进入桌面的加载进度条处无限转圈。
> **根因定性**：Android 对 `SystemUI`、`system_server` 等核心系统进程启动有严格的 **10 秒超时限制（`PROC_START_TIMEOUT`）**。当前工程存在多处高耗时与启动期阻塞隐患（多 DEX 体积膨胀、宽泛 `hookAllMethods` 触发 ART 大范围反优化、Hook 高频底层类 `ApplicationPackageManager`、以及在 `handleLoadPackage` 启动期同步跨进程调用 `ContentProvider`），叠加导致 `SystemUI` 启动突破 10 秒红线并陷入重启死循环。
> **使用方式**：将下方「优化改造 Prompt 正文」直接发送给 AI Studio 中的 **Gemini 4 Neon**，在不影响 AI Studio 实时预览的前提下，彻底重构 DEX 体积、混淆策略与 Hook 启动链路，将进程注入耗时压至 **`< 20ms`**。

---

## 优化改造 Prompt 正文

你是一位资深 Android ART 虚拟机、LSPosed 底层性能调优与 R8/DEX 优化专家。当前 **录屏策略（ScreenPolicy）** 在 Android 17 真机勾选 `com.android.systemui` 重启后，出现了**卡在开机进桌面进度条不动**的恶性 Bug（核心原因为 Android 系统进程启动有 **10 秒超时限制**，DEX 加载/反优化与 `handleLoadPackage` 初始化过慢触发了 `SystemUI` 启动超时循环）。

请**完整保留现有 4 项白名单功能、游戏侧边栏录屏修复、M3 Expressive UI、双语、新版图标、关于二级页、已修复的 GitHub Actions 工作流（务必保留 `gradle-version: wrapper` 与 `gradle` 命令）以及 AI Studio 预览所需的根目录元文件**，按以下 **5 项硬性工程规范** 对 DEX 体积、混淆配置与 Hook 启动架构进行彻底重构，将 `handleLoadPackage` 主线程阻塞耗时严格控制在 **`< 10ms`**，彻底根治卡开机问题：

### 一、精准瘦身 DEX 与 R8 混淆配置（保留 AI Studio 预览兼容性）

1. **精准清理重型业务依赖，保留 AI Studio 容器元文件**：
   * **必须保留**：根目录的 `metadata.json`、`public/assets/aistudio`、`.env.example` 以及 `debugConfig` 调试签名配置（这些文件不打进 APK、不占 DEX 空间，保留它们以确保 AI Studio 实时预览与云端模拟器始终正常）；
   * **必须清理**：从 `app/build.gradle.kts`、`build.gradle.kts` 与 `gradle/libs.versions.toml` 中移除所有**会被打包进 APK/DEX 但本模块根本未使用的重型库与插件**——如 `firebase-bom`、`google-services`、`room`、`ksp` 等，大幅削减 `classes.dex` 数量与体积，加快 LSPosed 注入时的 DEX 加载速度。
2. **优化 R8 混淆与多 DEX 布局（`app/build.gradle.kts` + `proguard-rules.pro`）**：
   * 在 `release` 构建类型中启用 **`isMinifyEnabled = true`** 与 **`isShrinkResources = true`**；
   * 编写精准克制的 `proguard-rules.pro`：仅 `-keep` Xposed 入口类（`xposed_init` 指定类）、`ContentProvider` 及白名单序列化数据类，其余代码交由 R8 充分裁剪压缩；
   * **Hook 入口类与 UI 严格解耦（零重型类图污染）**：`xposed_init` 入口类及 `hook/` 包下所有代码**严禁引用或静态依赖**任何 Jetpack Compose、Material3、AndroidX UI 或资源类，仅使用纯 Kotlin/Java 标准库与 `de.robv.android.xposed.*` API，确保 ART 在系统进程加载入口类时只触达极小的纯 Hook 核心类；
   * **严禁引入 DexKit 或任何运行时全盘 DEX 扫描库**，全部采用精确类名反射（`XposedHelpers.findClassIfExists`），避免开机解析几十 MB 系统 DEX 触发 10 秒超时。

### 二、斩断 `SystemUI` 启动期致命瓶颈（彻底重写 `GameDashboardRecordHook`）

1. **严禁 Hook `android.app.ApplicationPackageManager.getApplicationInfo`！**
   * `SystemUI` 开机启动阶段会高频调用 `ApplicationPackageManager.getApplicationInfo` 查询大量系统包，Hook 该类会触发 ART 大范围反优化（Deoptimization）并急剧拖慢进程冷启动。请**彻底删除**对 `ApplicationPackageManager` 的所有 Hook。
2. **仅精准 Hook `MediaProjectionPermissionActivity`（零启动期开销）**：
   * 仅针对 `com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity` 使用精确方法定位（`findAndHookMethod` / 精确参数类型匹配，**禁用无差别 `hookAllMethods`**）：
     * 在 `onCreate(Bundle)` 的 `beforeHookedMethod` 中，检查 `getCallingPackage()` 是否为空或异常，若属于游戏仪表盘跳板启动场景，直接在进入原生 `getApplicationInfo` 之前拦截修正 `getCallingPackage()` 返回 `"com.google.android.gms"` 并同步修正 `mPackageName` 与 `mUid`；
   * **强制延迟挂载（完全避开 Android 10 秒启动窗口）**：
     * 在 `com.android.systemui` 的 `handleLoadPackage` 中，**禁止在主线程同步执行任何 Hook 挂载**！改用 `Handler(Looper.getMainLooper()).postDelayed({ ... }, 12_000L)` 或独立低优先级守护线程延迟 12 秒挂载，完全避开系统开机启动校验窗口。

### 三、`handleLoadPackage` 零阻塞铁律与「启动期零 IPC」（斩断开机死锁）

1. **`handleLoadPackage` 耗时硬红线 `< 10ms`**：
   * 入口函数内只做包名判断（`android` / `com.android.systemui`）与极轻量任务派发；
   * **严禁**在 `handleLoadPackage` 主线程中执行磁盘 IO（包括同步加载 `XSharedPreferences`）、**严禁**执行任何 `ContentResolver.call()` / `ContentProvider` 跨进程调用！
2. **斩断开机期 `ContentProvider` 阻塞死锁**：
   * Hook 挂载结果先纯内存记录在轻量 `ConcurrentHashMap` 中（耗时 `< 1ms`）；
   * 所有向本模块 `ContentProvider` 上报状态与日志的操作，**必须延迟至开机稳定后（至少延迟 25–30 秒）在单线程低优先级后台线程池中异步执行**，并加装超时保护与静默失败兜底（若 App 尚未就绪则跳过或稍后重试一次，绝不阻塞系统线程、绝不无限重试）。

### 四、消除 `system_server` 中的宽泛 `hookAllMethods` 反优化开销

* 全面审查 `android`（`system_server`）下的 4 个 Hook（`ScreenshotHook`、`ScreenRecordHook`、`AudioCaptureHook`、`CaptureDetectHook`）：
  * **禁止无差别 `XposedBridge.hookAllMethods`** 挂满一个类的全部重载；改为通过 `declaredMethods` 按**精确方法名 + 最小必要参数特征**过滤后，仅对真正命中的 **1–2 个目标方法**调用 `XposedBridge.hookMethod`；
  * 除 `PackageInfoUtils`（若需早期生效）外，其余 3 个非开机必需的 Hook 均放到独立后台线程异步挂载，确保 `system_server` 启动主线程耗时几乎为零。

### 五、新增启动耗时监控与安全自检

* 在 `system_server` 与 `SystemUI` 各自记录真实挂载耗时（`SystemClock.elapsedRealtime()`，精确到毫秒），并在延迟异步上报后展示在 App「日志」页首条记录中（例如 `SystemUI hook ready in 4ms (deferred)`、`system_server hooks ready in 11ms`），方便真机直观验证；
* 保持全部 Hook 独立 `try-catch (Throwable)` 熔断，任何单点失败仅记录内存状态并跳过，绝不抛出异常。

### 六、输出要求

请直接完成上述改造，跑通 Gradle 编译、AI Studio 预览与本地单元测试后，输出：
1. 本次保留的 AI Studio 元文件与删减的冗余业务依赖清单；
2. `SystemUI` 与 `system_server` 的 Hook 延迟/异步化改造摘要；
3. 更新后的 `proguard-rules.pro` 关键规则确认。
