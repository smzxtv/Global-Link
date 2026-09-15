# 环球通 Global Link

## 一款独立的 sing-box 网络客户端（Windows / Android / iOS）

支持系统级 VPN 路由和本地 SOCKS5 代理，内置 sing-box 1.14.0 核心。

| 平台 | 技术栈 | 状态 |
|---|---|---|
| **Windows** | Tauri v2 + React + Rust | ✅ 完整可用 |
| **Android** | Kotlin + Jetpack Compose + VpnService | ✅ 代码完成（需 Android Studio 构建） |
| **iOS** | SwiftUI + NetworkExtension | ✅ 代码完成（需 Xcode + Apple Developer 账号） |

> 由「数码解码」出品并维护 · 技术支持请加入 [Telegram 群组](https://t.me/+tVg48WK48tlkNGVl)

---

## ✨ 功能特性

### 核心功能
| 功能 | 说明 |
|---|---|
| **VPN 模式（TUN）** | 系统级全局路由，所有程序无需配置直接走代理 |
| **手动 SOCKS5 模式** | 本地混合代理（HTTP + SOCKS5 同端口） |
| **自动系统代理** | SOCKS5 模式连接时自动设置 Windows 系统代理，断开自动还原 |
| **多协议支持** | Shadowsocks / VMess / VLESS / Trojan / Hysteria2 / TUIC / HTTP |
| **传输层支持** | TCP / WebSocket / HTTP/2 / gRPC，支持 TLS（含 SNI） |
| **订阅节点** | 支持从 V2Ray/Clash 订阅链接导入节点 |

### 路由与诊断
| 功能 | 说明 |
|---|---|
| **预检（Pre-flight）** | 连接 VPN 前自动检查管理员权限、wintun.dll、残留适配器 |
| **路由诊断** | 实时查看网卡、路由表、DNS 配置快照 |
| **残留适配器恢复** | 自动检测并清理崩溃会话遗留的 TUN 适配器 |
| **会话回收** | 核心启动失败时自动清除卡死的会话状态 |
| **实时日志** | 底部诊断面板实时显示核心输出 |

### 更新系统
| 功能 | 说明 |
|---|---|
| **自动检查** | 启动时 + 每 12 小时自动检查 GitHub Releases |
| **手动检查** | 设置页「立即检查」按钮 |
| **应用内下载** | 显示下载进度条，下载完成自动运行安装程序 |
| **SHA-256 校验** | 下载完成后校验文件完整性 |

### 界面
- 🇨🇳 **全中文界面**（连接 / 配置 / 设置三页）
- 深色主题，现代化设计
- 状态徽章：已断开 / 连接中… / 已连接 / 错误
- 内置「数码解码」技术支持入口与 Telegram 群组链接

---

## 📦 安装使用

### Windows（普通用户）
1. 到 [Releases](https://github.com/smzxtv/Global-Link/releases) 页面下载 `Global-Link-v2.1.3-Windows-x64-Installer.exe`
   （v2.1.3 修复了 v2.1.0–v2.1.2 的致命打包缺陷：安装包内 sing-box 内核被
   打包器拍平到上一层目录而程序只会在子目录里找，导致客户机上「核心缺失、
   无法连接」；本版已兼容两种布局并改进报错提示）
2. 双击安装（Windows 可能提示 SmartScreen，点「仍要运行」）
3. 启动 环球通 Global Link
4. 如果你正在使用 v2.1.0 / v2.1.1 / v2.1.2：请手动安装本版一次；
   从 v2.1.2 起，设置页的「检查更新 / 下载更新」即可正常使用

### Android（普通用户）
1. 到 [Releases](https://github.com/smzxtv/Global-Link/releases) 页面下载 `Global-Link-v2.1.0-Android-Universal.apk`
2. 安装 APK（首次安装需允许未知来源）
3. 启动应用，首次连接时授予 VPN 权限

### iOS（普通用户）
1. 到 [Releases](https://github.com/smzxtv/Global-Link/releases) 页面下载 `Global-Link-v2.1.0-iOS.ipa`
2. 通过 AltStore 或企业签名方式安装
3. 启动应用，首次连接时授予 VPN 权限

---

## 🔧 开发构建

### Windows
```powershell
npm ci
npm run fetch:core    # 下载 sing-box 核心
npm test              # 类型检查 + 前端构建
npm run tauri dev     # 开发模式
npm run tauri build   # 发布构建（NSIS 安装包）
```

### Android
```powershell
cd android
./gradlew assembleRelease    # 构建 APK
./gradlew bundleRelease      # 构建 AAB（Play Store）
```

### iOS
```bash
cd ios/AethonReplica
xcodebuild -scheme AethonReplica -configuration Release
```

---

## 📁 项目结构

```
Global-Link/
├── src/                    # Windows 前端（React + TypeScript）
├── src-tauri/              # Windows 后端（Rust + Tauri）
│   └── resources/sing-box/ # Windows 核心二进制
├── android/                # Android 客户端（Kotlin + Jetpack Compose）
│   └── app/src/main/java/io/github/aethonreplica/
│       ├── core/           # SingBoxCore, VpnService, ShareLinkParser
│       ├── data/           # AppConfig, ProfileStorage
│       └── ui/             # ConnectScreen, ConfigurationsScreen, SettingsScreen
├── ios/                    # iOS 客户端（SwiftUI）
│   └── AethonReplica/
│       ├── App.swift       # 主应用 + AppState
│       ├── ConnectView.swift
│       ├── ConfigurationsView.swift
│       ├── SettingsView.swift
│       └── AethonReplicaCore/  # SingBoxCore.swift
├── scripts/                # 构建脚本
└── docs/                   # 文档
```

---

## 🛠️ 构建需求

| 平台 | 工具链 |
|---|---|
| Windows | Node.js 22+, Rust 1.80+, VS Build Tools, WiX |
| Android | Android Studio, NDK 27+, JDK 17 |
| iOS | macOS 14+, Xcode 15+, Apple Developer 账号 |

---

## 🌐 订阅导入

应用支持以下格式的分享链接和订阅：
- `vless://...`
- `vmess://...`
- `trojan://...`
- `ss://...`
- `hysteria2://...`
- `tuic://...`
- Base64 编码的订阅内容

---

## ❓ 常见问题

**Q: 连接后浏览器打不开网页？**
A: SOCKS5 模式会自动设置系统代理。如果手动关闭了系统代理，请重新点一次「连接」。

**Q: VPN 模式报「需要管理员权限」？**
A: 右键程序图标 → 以管理员身份运行。TUN 网卡创建需要管理员权限。

**Q: Android 上首次连接没反应？**
A: 首次连接需要授予 VPN 权限，系统会弹出授权对话框，请点击「确定」。

**Q: iOS 无法安装？**
A: iOS 需要通过 AltStore（免费账号）或企业签名方式安装。需要 Apple Developer 账号才能发布到 App Store。

**Q: 断开后上不了网？**
A: 点「恢复网络」按钮，会停止核心 + 还原系统代理 + 刷新 DNS。

---

## 📄 许可

本项目为独立前端，非上游项目。网络引擎使用 [sing-box](https://github.com/SagerNet/sing-box)（GPL-3.0）。

## 💬 技术支持

- Telegram 群组：https://t.me/+tVg48WK48tlkNGVl
- **数码解码** · 出品

