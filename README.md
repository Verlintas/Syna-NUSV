# Syna

局域网即时通信应用（LAN messenger），基于 **Kotlin Multiplatform + Compose Multiplatform**。

## 功能规划

- [x] 多端支持（Android / Windows / macOS）
- [x] 自定义用户名
- [x] UI 暗黑模式 / 明亮模式切换（可跟随系统）
- [ ] 局域网用户自动发现（UDP 广播）
- [ ] 微信式聊天（会话列表、消息气泡、送达回执）
- [ ] 连接方式切换（TCP / UDP / 主机热点）
- [ ] P2P 网格群聊（无中心服务器）
- [ ] 增强防护模式：阅后即焚、临时聊天、端到端加密（X25519 + AES-256-GCM）

## 技术栈

| 组件 | 选择 |
| --- | --- |
| 语言 | Kotlin 2.1 |
| UI | Compose Multiplatform 1.8 (Material3) |
| 平台 | Android + JVM 桌面（Windows/macOS 共用） |
| 序列化 | kotlinx.serialization |
| 持久化 | multiplatform-settings |
| 加密 | JDK/Android 内置 X25519 + AES-GCM |

## 构建与运行

```bash
# 桌面端（macOS/Linux/Windows 上均可用）
./gradlew :composeApp:run

# Android APK
./gradlew :composeApp:assembleDebug
# 产物: composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

## 目录结构

```
composeApp/
├── src/commonMain/   # 共享 UI 与逻辑（主题、设置、屏幕）
├── src/androidMain/  # Android 入口与权限
└── src/desktopMain/  # 桌面入口
```

## 已知平台注意点

- Android 14+ 本地网络权限收紧；发现功能依赖 UDP 广播，部分路由器会拦截
- Windows 首次运行会弹出防火墙授权提示
