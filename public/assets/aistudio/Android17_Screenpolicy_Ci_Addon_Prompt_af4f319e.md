# 增量优化指南（给 AI Studio / Gemini 4 Neon）：开关拆分 + 三态激活检测 + 图标高阶精修

> 使用方式：基于 Gemini 4 Neon 最新一轮已完成的代码（已含 SDK 36、双语切换、M3 Expressive 悬浮导航栏与 GitHub Actions 工作流），直接复制下方「增量 Prompt 正文」发给它即可完成最后 3 项打磨。

---

## 增量 Prompt 正文

上一轮的 SDK 标准化、中英文双语、M3 Expressive 悬浮胶囊导航栏、`MediaProjectionPermissionActivity` 字段同步以及双轨 GitHub Actions 工作流都完成得很好，**请保持以上已完成部分完全不变**。在此基础上，仅对以下 **3 项内容** 做增量升级：

### 1. 将「画面限制」拆分为「截图限制」与「录屏限制」两个独立开关

* **数据模型与 UI（每应用共 4 项独立开关）**：
  * 将原先单一的 `disableSecure`（画面限制）拆分为两项独立布尔开关，与现有两项并列展示在应用的底部抽屉中：
    1. `截图限制`（`allowScreenshot`）— 中文副标题：`允许对该应用截屏` / 英文：`Screenshots` — `Allow taking screenshots`
    2. `录屏限制`（`allowScreenRecord`）— 中文副标题：`允许录制该应用画面` / 英文：`Screen recording` — `Allow recording screen video`
    3. `音频内录`（`allowAudioCapture`）— 保持现有
    4. `录屏检测`（`blockCaptureDetect`）— 保持现有
  * 同步更新白名单 JSON 导出/导入字段（兼容旧字段回退）、中英文 `strings.xml` 及相关单元测试。
* **Hook 层区分实现（Android 17 `system_server`）**：
  * **仅开启「截图限制」时**：在系统截屏路径（如 `ScreenCapture.CaptureArgs` / `captureDisplay` / `captureLayers` 等截屏调用链）中针对白名单包名放行安全层（`captureSecureLayers`），不连带放开录屏通道；
  * **开启「录屏限制」时**：在窗口级 `WindowState.isSecureLocked()` 按窗口所属 `owningPackage` 精准放行（继续保持不 Hook 整屏级 `DisplayContent.hasSecureWindowOnScreen`，确保分屏/小窗下未勾选的银行/支付 App 绝不被连带放行）；
  * 首页摘要中的系统框架计数同步更新为 **4 项**（`截图限制 / 录屏限制 / 音频内录 / 录屏检测`）。

### 2. 首页新增三态激活与作用域检测提示

通过检测本地 `ContentProvider` 是否收到 `android`（系统框架）与 `com.android.systemui`（系统界面）在本次开机后的状态心跳，在首页顶部状态卡精确区分 **3 种状态**（文案保持克制、不出现敏感词，并同步提供中英文资源）：

* **状态一：完全未生效**（未收到 `android` 和 `com.android.systemui` 任何一方的心跳）
  * 使用醒目的 M3 `errorContainer` / 警示色调 Expressive 卡片；
  * 标题：`未检测到模块生效`（英文：`Module not active`）；
  * 说明：`请在管理器中启用本应用，勾选「系统框架」与「系统界面」后重启设备`（英文：`Enable this app in manager, select System Framework and System UI, then reboot`）。
* **状态二：作用域缺失**（仅检测到其中一方生效）
  * 使用 `tertiaryContainer` / 提醒色调卡片，精确指出缺少哪一项及影响：
    * 仅缺系统框架：`未检测到系统框架生效，白名单策略暂不可用`；
    * 仅缺系统界面：`未检测到系统界面生效，侧边栏录屏修复暂不可用`。
* **状态三：运行正常**（两方均已生效）
  * 使用柔和的 M3 `primaryContainer` / `secondaryContainer` 正常态卡片，展示 `运行正常 · 系统框架 4/4 · 系统界面 1/1`，点击可展开逐项查看 5 项 Hook 的挂载状态。

### 3. 应用图标高阶精修（去简陋 / 去 AI 味）

上一版把「四角取景框 + 圆点 + 声波胶囊」三个元素同时塞进图标里，视觉上偏杂乱且有明显 AI 拼贴感。请重新精修图标，追求 **Google Pixel 原生系统工具的极简克制高级感**：

* **减法原则（核心）**：**只保留 1–2 个干净的几何焦点元素**，严禁堆砌多余装饰线条或小元素。例如：
  * 方案参考：深邃柔和的 M3 靛蓝/青灰纯色底板（`ic_launcher_background.xml`）+ 前景仅由一个极简圆角方框（带精致比例切口）与居中柔和的实心焦点圆构成，线条粗细完全统一、留白充足（核心图形严格控制在 `108dp` 画布内的 `56dp–62dp` 黄金视觉区内，避免显得局促或被裁剪）；
* **完整适配系统特性**：
  * 同步更新 `ic_launcher_foreground.xml`、`ic_launcher_background.xml` 与极简负空间剪影的 **`ic_launcher_monochrome.xml`**；
  * 确认 `mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 正确声明 `<monochrome>` 节点，完整支持系统**动态取色主题图标（Themed Icons）**与任意形状自适应裁切，并同步刷新 5 套分辨率的 PNG 回退图标。

### 4. 输出要求

请仅输出本次**新增或修改**的文件完整内容，并跑通编译与 Robolectric 测试后给出简明变更清单。
