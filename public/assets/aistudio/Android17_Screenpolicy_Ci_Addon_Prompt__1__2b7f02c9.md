# 增量补充指南（给 AI Studio / Gemini 4 Neon）：微调修复 + 体验升级 + GitHub Actions 自动编译发包

现在请基于上面已经生成的 Android 17「录屏策略（ScreenPolicy）」工程，按以下 3 个部分进行增量修改与补充（保持整体零敏感权限、仅 `android` + `com.android.systemui` 作用域、去 AI 味极简文案风格不变）：

### 一、稳定性与兼容性微调（3 项）

1. **SDK 版本标准化**：将 `app/build.gradle.kts` 中的 `compileSdk` 和 `targetSdk` 改为标准整数 **`36`**（避免 GitHub Actions 云端环境找不到 `36.1` 小版本 SDK 导致编译失败），`minSdk` 设为 `35` 或 `36`（Hook 反射逻辑仍严格面向 Android 17）。
2. **收紧 `DisplayContent.hasSecureWindowOnScreen`**：检查该 Hook 逻辑，确保在分屏或小窗同时存在未勾选的银行/支付应用时**绝不会被连带放行**；若仅靠窗口级 `WindowState.isSecureLocked` 已足够完成白名单放行，请直接精简移除 `hasSecureWindowOnScreen` 的 Hook。
3. **核对 `MediaProjectionPermissionActivity` 兜底字段同步**：确保兜底为 `com.google.android.gms` 时，Activity 内部保存包名的字段（如 `mPackageName`）与恢复返回的 `ApplicationInfo.uid` 保持严格一致，避免后续向系统申请 `MediaProjection` Token 时因包名与 UID 校验不匹配再次失败。

### 二、UI 与体验升级（3 项）

1. **完整中英文双语切换**：
   * 默认中文（`values/strings.xml` 为简体中文，新增 `values-en/strings.xml` 为克制、原生的英文文案，所有界面与日志筛选文案全部走字符串资源，禁止硬编码中文）；
   * 在「设置」中新增 **语言（Language）** 选项，支持切换 `跟随系统 / 简体中文 / English`（使用 Android 13+ 原生 `LocaleManager` 或 `AppCompatDelegate.setApplicationLocales` 实现即时切换与持久化）。
2. **Material Design 3 Expressive 悬浮式导航栏与动效系统**：
   * 将底部导航栏改造为 **Material Design 3 Expressive 悬浮胶囊式导航栏（Floating Pill Navigation Bar）**：悬浮于底部手势条上方（妥善处理 `navigationBarsPadding()`），采用大圆角胶囊容器、柔和层级阴影/色调表面，并在页面滚动时保持视觉通透；
   * 全面遵循 M3 Expressive 弹簧动效体系（`spring` 物理参数），为导航项选中指示器切换、图标微缩放、页面过渡以及开关/底部抽屉展开提供丝滑且克制的弹簧微动效。
3. **极简高级感自适应图标 + 动态取色主题图标**：
   * 重新设计一套更加简洁高级的极简几何矢量图标（例如抽象取景框 + 声波/录屏圆点的极简构图，线条利落、留白克制，视觉贴近 Pixel 原生系统工具风格）；
   * 完整提供 `ic_launcher_background.xml`、`ic_launcher_foreground.xml` 与 **`ic_launcher_monochrome.xml`**，并在 `mipmap-anydpi-v26/ic_launcher.xml` 及 `ic_launcher_round.xml` 中正确声明 `<monochrome>` 节点，确保完整适配系统的**自适应图标裁剪**与 **动态取色主题图标（Themed Icons）**。

### 三、GitHub Actions 云编译与自动发包（双轨版本控制）

1. **修改 `app/build.gradle.kts`**：
   * **`versionCode` 自动递增**：读取环境变量 `GITHUB_RUN_NUMBER`（转为 Int，缺省为 `1`），确保每次云编译产出的 APK 均可直接覆盖安装。
   * **`versionName` 手动掌控**：
     * 优先读取环境变量 `RELEASE_VERSION`（若存在则去掉前缀 `v`，如 `v1.0.0` → `1.0.0`）；
     * 其次若存在 `GITHUB_SHA`，使用 `dev-${sha.take(7)}`（日常 Debug 验货用，不干扰正式版本号）；
     * 本地无环境变量时默认 `0.1.0-dev`。
   * **Release 签名与安全回退**：从环境变量 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 读取签名配置；仅当环境变量与签名文件均存在时才绑定 `release` 签名，不影响 Debug 包免签名构建。
   * **Release Lint 避坑**：在 `android { ... }` 中显式配置 `lint { checkReleaseBuilds = false; abortOnError = false }`。
2. **新增 `.github/workflows/android-ci.yml`**：
   * 顶层声明 `permissions: contents: write`；
   * 触发条件：`push`（`main` 分支）、`workflow_dispatch`、`release: types: [published]`；
   * **Job 1（`build-debug`，仅 `push` / `workflow_dispatch` 触发）**：配置 JDK 17/21 与 Gradle 缓存，`chmod +x gradlew`，仅编译 `./gradlew assembleDebug --no-configuration-cache`，通过 `actions/upload-artifact@v4` 上传 Debug APK；
   * **Job 2（`build-release`，仅 `github.event_name == 'release'` 触发）**：将 `${{ github.event.release.tag_name }}` 注入 `RELEASE_VERSION`，从 Secrets 解码 `KEYSTORE_BASE64` 至临时文件并注入 `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，执行 `./gradlew assembleRelease -x lintVitalAnalyzeRelease --no-configuration-cache`，重命名为 `ScreenPolicy-${{ github.event.release.tag_name }}-release.apk`，通过 `softprops/action-gh-release@v2` 自动挂载到当前 Release 附件中，并在结束后清理临时密钥文件。

### 四、输出要求

请直接输出本次**新增或修改**的文件完整内容（无需重复输出未改动的文件），并附上一份简短的变更清单。
