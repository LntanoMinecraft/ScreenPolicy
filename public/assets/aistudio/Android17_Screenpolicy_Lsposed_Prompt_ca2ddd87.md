# Android 17 专属录屏与音频策略 LSPosed 模块 — AI Studio (Gemini 4 Neon) 开发规范与 Prompt

> 使用方式：直接将下方「完整 Prompt 正文」复制粘贴（或将本文件作为附件上传）到 Google AI Studio 的 **Gemini 4 Neon** 中即可开始生成完整 Android 工程。

---

## 完整 Prompt 正文

你是一位资深 Android Framework、LSPosed 模块与 Jetpack Compose (Material Design 3 Expressive) 开发工程师。请严格遵循以下技术规范，为我设计并编写一个**仅适配 Android 17（API 37）**的轻量化、高安全性 LSPosed 模块完整工程（Kotlin + Gradle Kotlin DSL）。

### 1. 项目定位与硬性约束

* **目标系统**：仅适配类原生 **Android 17（API 37）**（`minSdk = 37`, `compileSdk = 37`, `targetSdk = 37`）。**禁止**编写任何面向 Android 12–16 的旧版本兼容分支或冗余反射代码，保持代码极简、体积轻量。
* **命名与隐蔽性**：
  * 推荐包名：`com.lntano.screenpolicy`（禁止在包名、应用名或 UI 中出现 `xposed`、`lsp`、`hook`、`bypass`、`unlock`、`cheat` 等敏感词）。
  * 应用名称：`录屏策略`（英文 `ScreenPolicy`）。
* **零敏感权限原则**：
  * **严禁**申请或调用 Root（`su`）、联网（`INTERNET`）、无障碍服务、悬浮窗或外部存储权限。
  * 模块仅依赖标准 LSPosed（Xposed API），与底层 Root 管理器（Magisk / KernelSU / APatch）完全解耦。
* **LSPosed 作用域（`res/values/arrays.xml` -> `xposed_scope`）**：
  * **仅包含两项**：`android`（系统框架）与 `com.android.systemui`（系统界面）。
  * **安全红线**：绝不要求、也不推荐用户勾选任何第三方应用或游戏进程，确保对竞技手游（如带 ACE 反作弊的游戏）实现**零进程注入**。
* **白名单机制（Allowlist）**：
  * 默认所有应用完全保持系统原样（银行、支付、密码管理器默认受系统保护）。
  * 仅对用户在模块 UI 中明确开启开关的应用生效。

### 2. 核心 Hook 功能架构（Android 17）

请将 Hook 逻辑按职责拆分为独立类，全部置于 `hook/` 包下：

#### 2.1 系统框架层（作用域：`android` / `system_server`）

为白名单中的每个应用提供 **3 项独立开关**，互相解耦：

1. **画面限制（`disableSecure`）**：
   * 解除 `FLAG_SECURE` 导致的禁止截屏与录屏黑屏。
   * 在 Android 17 的 `WindowManagerService` / `WindowState` 安全窗口判定点（如 `WindowState.isSecureLocked` 及关联的 `ScreenCapture` 安全层检查）进行拦截，提取窗口所属 `owningPackage`（或 UID 对应包名），仅当该包名开启 `disableSecure` 时放行返回 `false`。
   * 可参考开源基线：[LSPosed/DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure)。

2. **音频内录（`allowAudioCapture`）**：
   * 解除应用通过 Manifest 声明 `android:allowAudioPlaybackCapture="false"` 或运行时策略禁止录制应用音频的限制（参考 [Android AudioPlaybackCapture 官方文档](https://developer.android.com/media/platform/av-capture) 与 [XAudioCapture](https://github.com/wzhy90/XAudioCapture)）。
   * **双层放行**：
     * 在系统解析/读取应用信息时，针对开启 `allowAudioCapture` 的白名单包名，强制置位 `ApplicationInfo.privateFlags` 中的 `PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE` 标志位；
     * 在 `AudioService` 拦截捕获策略校验/设置逻辑，对白名单包名/UID 强制覆写为 `AudioAttributes.ALLOW_CAPTURE_BY_ALL`（值为 `1`）。
   * **严禁**修改无关的蓝牙 A2DP 或通话音频路由，确保外接蓝牙耳机音质与延迟不受影响。

3. **录屏检测（`blockCaptureDetect`）**：
   * 针对开启 `blockCaptureDetect` 的白名单包名，在 `system_server` 中拦截系统截屏回调（`ScreenCaptureCallback`）与录屏状态回调（`ScreenRecordingCallback`）的注册或分发，防止应用检测到截屏/录屏后弹窗、黑屏或中断。

#### 2.2 系统界面层（作用域：`com.android.systemui`）

提供一个独立的**全局开关**：**修复游戏侧边栏录屏（`fixGameDashboardRecord`）**。

* **已验证的真机故障日志（Android 17 + GMS 游戏仪表盘）**：
  * 当游戏侧边栏（`com.google.android.gms`）通过 `android/.service.games.GameSessionTrampolineActivity`（`realCallingUid=1000`）拉起 `com.android.systemui/.mediaprojection.permission.MediaProjectionPermissionActivity` 时，触发以下异常导致直接返回 `RESULT_CANCELED`：
    ```text
    E/MediaProjectionPermissionActivity: Unable to look up package name
    android.content.pm.PackageManager$NameNotFoundException
      at android.app.ApplicationPackageManager.getApplicationInfo(ApplicationPackageManager.java:540)
      at com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity.onCreate(...)
    I/ScreenRecordingManager: Failed to get media projection: CancellationException: User cancelled media projection dialog
    ```
  * 参考 AOSP [`MediaProjectionPermissionActivity.java`](https://android.googlesource.com/platform/frameworks/base/+/9450179a6182/packages/SystemUI/src/com/android/systemui/media/MediaProjectionPermissionActivity.java) 逻辑。
* **修复实现要求**：
  * 在 `fixGameDashboardRecord` 开启时，Hook `com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity` 的 `onCreate` 及/或其内部获取发起包名的调用（如 `getCallingPackage()`）。
  * 当检测到 `getCallingPackage()` 为 `null`/空或 `getApplicationInfo` 抛出 `NameNotFoundException`，且启动来源/Intent 符合游戏仪表盘跳板特征时，兜底将其修正为 `"com.google.android.gms"`，使系统录屏授权弹窗与录制流程正常继续。

### 3. 稳定性、性能与双轨日志设计

1. **防软重启与崩溃熔断**：
   * 所有 Hook 挂载与回调执行体**必须**各自包裹独立的 `try-catch (t: Throwable)`。
   * 若 Android 17 月度安全补丁导致某个内部方法签名变化，只记录失败状态与日志并安全跳过，**绝不允许任何异常逃逸导致 `system_server` 或 `SystemUI` 崩溃**。
   * 优先使用 `XposedBridge.hookAllMethods` 配合参数类型校验，避免硬编码脆弱的单一方法签名。
2. **高频路径零磁盘 IO 缓存**：
   * `WindowState.isSecureLocked` 在 120Hz 渲染时属于超高频调用。必须在内存中维护 `ConcurrentHashMap<String, AppPolicy>` 缓存，仅通过节流检查（例如间隔 >= 1000ms 且 `XSharedPreferences.hasFileChanged()` 为真）时才重新加载配置。
   * 配置持久化采用 LSPosed 支持的 `XSharedPreferences`（`MODE_WORLD_READABLE`），修改白名单后**无需重启手机**，重启目标应用即可生效。
3. **双轨基础日志（LSPosed + App 内置）**：
   * **轨道一**：所有关键事件照常输出到 `XposedBridge.log(...)`，可在 LSPosed 管理器日志中完整查看。
   * **轨道二**：在模块 App 内实现一个轻量 `ContentProvider`（严格校验调用方为 `Process.SYSTEM_UID` 或 `SystemUI` 对应 UID），本地循环保留最近 200 条日志供 App 内「日志」页直接查阅、一键复制与清空。
   * **上报节流策略**：仅异步上报三类信息——(1) 启动时各 Hook 挂载成功/失败状态；(2) 去重限流后的运行时异常；(3) 用户手动开启「详细日志」开关后的白名单命中记录（默认关闭）。

### 4. UI 设计规范（Material Design 3 Expressive & 去 AI 味文案）

1. **严格遵循 Google Material Design 3 Expressive**：
   * 使用 Jetpack Compose + 最新 Material 3 Expressive 组件与样式（分组圆角卡片、Expressive 字体层级、大圆角切换控件与弹簧微动效、边缘到边缘 Edge-to-Edge 沉浸式布局）。
   * **主题与配色**：
     * 完整适配 **动态取色（Material You）**；
     * 支持 **跟随系统 / 浅色 / 深色** 模式切换；
     * 支持 **应用内配色方案切换**（动态取色 + 4 套内置 M3 色调预设：如青灰、海蓝、森绿、暖橙）。
2. **页面结构（极简 3 Tab 导航栏）**：
   * **应用（主页面）**：
     * 顶部状态卡：简明展示模块激活状态、4 项 Hook 挂载摘要（如 `系统框架 3/3 · 系统界面 1/1`）；
     * 搜索栏 + 筛选芯片（全部 / 已配置 / 显示系统应用）；
     * 应用列表：点击应用展开或弹出底部抽屉（ModalBottomSheet），展示 **3 个独立开关**：`画面限制`、`音频内录`、`录屏检测`；修改后底部弹出轻量 Snackbar 提示「重启应用后生效」。
   * **日志**：
     * 单色等宽字体展示本模块留存的基础日志，支持按级别过滤（全部 / 仅异常）、一键复制、清空日志。
   * **设置**：
     * 「游戏侧边栏录屏修复」全局开关；
     * 「详细调试日志」开关（默认关闭）；
     * 外观设置（深浅色模式、动态取色开关、应用主题色盘）；
     * 配置管理（通过系统 SAF 选择器导出 / 导入 JSON 白名单，零存储权限）。
3. **去 AI 味文案硬性要求**：
   * **严禁**堆砌宣传词汇与冗长解释（禁止出现「智能守护、一键赋能、全方位、无缝体验、轻松解除」等 AI 风格表达）。
   * 全程对齐 Pixel 原生系统设置的克制、短促风格。参考标准文案：
     * 开关标题与说明：
       * `画面限制` — `允许截屏与录屏画面`
       * `音频内录` — `允许录制应用内部音频`
       * `录屏检测` — `屏蔽截屏与录屏状态回调`
       * `修复游戏侧边栏录屏` — `修正游戏仪表盘授权异常`
       * `详细日志` — `记录白名单命中详情，仅排错时开启`
     * 状态提示：`已激活`、`未激活`、`重启应用后生效`、`暂无日志`。

### 5. 输出要求

请分步骤、完整输出以下内容，确保工程可直接导入 Android Studio 编译运行：
1. 完整项目目录结构；
2. 根级与 `app` 级 `build.gradle.kts`、`libs.versions.toml`（包含 Xposed API `compileOnly` 与最新 Compose M3 Expressive 依赖）；
3. `AndroidManifest.xml`、`xposed_init`、`xposed_scope`（仅 `android` 和 `com.android.systemui`）；
4. 完整 Kotlin 源码：配置模型与缓存、4 个 Hook 实现类、双轨日志 `ContentProvider`、M3 Expressive 主题与全部 UI 页面；
5. 编译后在 Android 17 真机上首次测试时的 3 步验证清单（包含若某项 Hook 因月度补丁签名变化未命中时，如何通过 App 内日志快速定位并修正方法签名）。
