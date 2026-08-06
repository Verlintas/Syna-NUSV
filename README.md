# Syna

局域网即时通信应用（LAN messenger），基于 **Kotlin Multiplatform + Compose Multiplatform**，Android / Windows / macOS 多端支持，无中心服务器，纯 P2P。

## 功能

- ✅ **多端支持**：Android（APK）+ 桌面（Windows/macOS 共用 JVM 产物）
- ✅ **局域网用户发现**：UDP 广播 + 多播双通道，3 秒心跳，15 秒超时判定离线
- ✅ **自定义用户名**：设置页修改，实时广播同步
- ✅ **微信式聊天**：会话列表（未读角标/时间/预览）、聊天气泡、送达回执 ✓✓、正在输入提示（协议预留）
- ✅ **连接方式切换**：自动 / TCP（可靠）/ UDP（快速）/ 主机热点
- ✅ **P2P 网格群聊**：群主创建邀请 → 成员网格同步（JOIN/LEAVE），消息按成员直连加密广播，无中心节点
- ✅ **增强防护**：
  - 端到端加密：X25519 密钥交换 + HKDF-SHA256 + AES-256-GCM，密钥仅存本机
  - 阅后即焚：接收方显示 8 秒后双向销毁 + BURN_ACK 确认 + 60 秒兜底
  - 临时聊天：会话 TTL（1小时/24小时/7天）到期双方记录自动清除
- ✅ **UI 主题**：暗黑 / 明亮 / 跟随系统
- ✅ **离线消息**：目标离线时进入待发送队列，上线自动补发
- ✅ **断线重连**：TCP 心跳保活 + 按需自动重连

## 技术架构

```
composeApp/
├── src/commonMain/          # 共享 UI + 领域逻辑
│   ├── ui/                  # 会话/联系人/群创建/聊天/设置页
│   ├── net/                 # SynaEngine、协议帧、传输抽象
│   ├── chat/                # ChatStore（会话与消息状态）
│   ├── crypto/              # 加密抽象（expect）
│   └── storage/             # 设置持久化
├── src/jvmShared/           # 两端共用的 JVM 实现
│   ├── net/                 # java.net 传输、UDP 发现、心跳
│   └── crypto/              # X25519/AES-GCM/HKDF 实现、密钥文件
├── src/androidMain/         # Android 入口、MulticastLock、权限
└── src/desktopMain/         # 桌面入口、密钥目录
```

**通信协议**（端口：45877 发现 / 45878 UDP 数据 / TCP 临时端口）：

- 帧结构：`{type, from, to, msgId, ts, body, enc, burn}` JSON，kotlinx.serialization
- 帧类型：HELLO / KEY / TEXT / GROUP_MESSAGE / GROUP_INVITE / GROUP_JOIN / GROUP_LEAVE / READ / BURN_ACK / PING / PONG
- 端到端加密：TCP 连接时 HELLO 携带公钥，对端回发 KEY；会话密钥按 `sorted(双方id)` 派生保证双向一致

## 构建与运行

```bash
# 桌面端（macOS/Windows/Linux）
./gradlew :composeApp:run

# Android APK
./gradlew :composeApp:assembleDebug
# 产物: composeApp/build/outputs/apk/debug/composeApp-debug.apk

# 测试（16 项：加密/协议/环回聊天/群聊/阅后即焚/临时聊天/离线补发）
./gradlew :composeApp:desktopTest
```

## 平台注意点

- **Windows**：首次运行防火墙弹窗需允许；分发可用 `nativeDistributions` 生成 Msi（需 Windows 环境 + WiX）
- **Android 14+**：本地网络权限收紧，部分路由器禁用 UDP 广播（多播通道作为备选）
- **群聊**：无中心节点，离线成员会漏收消息（发送方暂存补发仅覆盖单聊）
- **消息持久化**：当前为内存存储，重启清空；SQLDelight 持久化在路线图中
- **单机多实例**：桌面端设置存于用户级 Java 偏好（`Preferences.userRoot`），同一系统账号下多个实例共享同一身份
- **调试日志**：`[Syna:Engine/Discovery/Send/Outbox/Crypto]` 前缀输出到 stdout / logcat

## 路线图

- [ ] 消息历史持久化（SQLDelight）
- [ ] 群消息存储转发（离线补发）
- [ ] 图片/文件传输（分块 + UDP/TCP 混合）
- [ ] 正在输入指示、消息撤回
- [ ] Android Keystore 硬件级密钥存储
