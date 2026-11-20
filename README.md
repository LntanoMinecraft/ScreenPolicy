# 录屏策略（ScreenPolicy）

> 面向 Android 17 的白名单录屏与音频策略管理模块（LSPosed）

[](https://github.com/LntanoMinecraft/ScreenPolicy)
[](https://github.com/LntanoMinecraft/ScreenPolicy)
[](https://github.com/LntanoMinecraft/ScreenPolicy/blob/main/LICENSE)
[Releases](https://github.com/LntanoMinecraft/ScreenPolicy/releases)

## 项目简介

**录屏策略（ScreenPolicy）** 是一款专为类原生 **Android 17** 设计的轻量化 LSPosed 模块，采用**白名单隔离机制**按应用精准解除截屏、录屏画面、音频内录及录屏检测限制，同时修复 Android 17 下 GMS 游戏侧边栏快捷录屏无响应的问题。

模块仅运行于系统框架与系统界面层，**绝不向任何游戏或第三方应用进程注入代码**，默认完整保留银行、支付与密码管理器等敏感应用的系统安全保护。

## 核心功能

| 模块 | 功能说明 |
| :--- | :--- |
| **分应用 4 项独立开关** | 仅对白名单内手动勾选的应用按需开启：`截图限制`、`录屏限制`、`音频内录`、`录屏检测`，分屏与小窗场景下严守隐私隔离 |
| **游戏侧边栏录屏修复** | 修复 Android 17 下 GMS 游戏仪表盘拉起录屏授权时因 `NameNotFoundException` 导致的无响应，并支持可选跳过投屏确认弹窗、自动全屏开录 |
| **零注入与零敏感权限** | LSPosed 仅勾选 `系统框架` 与 `系统界面`，无需授予 Root、联网、无障碍、悬浮窗或存储权限，兼容 Magisk / KernelSU / APatch |
| **防卡开机与双轨日志** | R8 精简瘦身、`SystemUI` 延迟异步挂载且启动期零 IPC，彻底规避系统进程 10 秒启动超时；首页提供三态作用域自检与本地基础日志页 |
| **配置与界面体验** | 白名单修改免重启手机（重启目标应用即可生效），支持系统 SAF 导入/导出 JSON；采用 Material Design 3 Expressive、弹性展开悬浮胶囊导航栏、Google Sans Flex 圆角英文字体、中英文双语切换与动态取色 7 色色盘 |

## 使用方法

1. 前往 [GitHub Releases](https://github.com/LntanoMinecraft/ScreenPolicy/releases) 下载最新签名 Release APK 并安装。
2. 在 **LSPosed 管理器** 中启用「录屏策略」，作用域**仅勾选**：
   * `系统框架`（`android`）
   * `系统界面`（`com.android.systemui`）
   * *请勿勾选任何游戏或普通第三方应用。*
3. 重启手机并解锁进入桌面，等待约 30 秒待后台异步同步完成，打开应用确认首页状态卡显示 `运行正常`，随后按需为指定应用开启对应开关并重启该应用即可。

## 系统要求

* **目标系统**：类原生 Android 17（API 37）
* **依赖环境**：已激活的 LSPosed 框架（与底层 Root 管理器无关，模块本身无需申请超级用户权限）

## 项目信息

* **项目名称**：录屏策略（ScreenPolicy）
* **应用包名**：`com.lntano.screenpolicy`
* **项目主页**：[https://github.com/LntanoMinecraft/ScreenPolicy](https://github.com/LntanoMinecraft/ScreenPolicy)
* **维护者**：[LntanoMinecraft](https://github.com/LntanoMinecraft) · [酷安主页](https://www.coolapk.com/u/34054336)

## 开源许可证

Copyright (C) 2026 LntanoMinecraft

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](https://github.com/LntanoMinecraft/ScreenPolicy/blob/main/LICENSE) 协议开源。你可以在遵守 GPL-3.0 条款的前提下自由使用、修改与分发，分发修改版本时需同步开放对应源代码。

*本项目由 Google AI Studio 中的 Gemini 4 Neon 编写构建。*
