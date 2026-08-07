# Syna Tutorial / Syna 使用教程

> **Bilingual tutorial / 双语教程** — English first, then 中文 for each section.
> 每个章节先英文后中文。

This tutorial walks you through everything: LAN chat, private server, remote access via NAT traversal, security features, and troubleshooting.

本教程覆盖：局域网聊天、私人服务器、内网穿透远程接入、安全功能与故障排查。

---

## Table of Contents / 目录

1. [Quick Start / 快速开始](#1-quick-start--快速开始)
2. [LAN Features / 局域网功能](#2-lan-features--局域网功能)
3. [Private Server / 私人服务器](#3-private-server--私人服务器)
4. [Remote Access (NAT Traversal) / 远程接入（内网穿透）](#4-remote-access-nat-traversal--远程接入内网穿透)
5. [Security Model / 安全模型](#5-security-model--安全模型)
6. [Troubleshooting / 故障排查](#6-troubleshooting--故障排查)
7. [Build & Distribute / 构建与分发](#7-build--distribute--构建与分发)

---

## 1. Quick Start / 快速开始

### English

**Requirements / 环境要求**

- JVM 17+ (desktop) — download from [Adoptium](https://adoptium.net) if needed
- Android 9+ (API 28+) for the Android app
- Windows / macOS / Linux for the desktop app and server

**1. Run the desktop app**

```bash
git clone https://github.com/Verlintas/Syna-NUSV.git
cd Syna-NUSV
./gradlew :composeApp:run
```

A window titled **Syna** opens. Three tabs at the bottom: **Chats (会话)**, **Contacts (联系人)**, **Settings (设置)**.

**2. Run the Android app**

```bash
./gradlew :composeApp:assembleDebug
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

**3. Chat with someone on the same LAN**

1. Connect both devices to the **same Wi-Fi / LAN**.
2. Open Syna on both. They find each other automatically (~3 seconds) and appear in **Contacts / 联系人** with a green dot (online).
3. Tap a user → chat screen → type a message → send. The other side sees it instantly (✓✓ = read).
4. Enjoy! No server, no internet needed.

### 中文

**环境要求**

- 桌面端需要 JVM 17+（可从 [Adoptium](https://adoptium.net) 下载）
- Android 端需要 Android 9（API 28）及以上
- 桌面应用与服务器支持 Windows / macOS / Linux

**1. 运行桌面应用**

```bash
git clone https://github.com/Verlintas/Syna-NUSV.git
cd Syna-NUSV
./gradlew :composeApp:run
```

会打开标题为 **Syna** 的窗口，底部有三个标签：**会话**、**联系人**、**设置**。

**2. 运行 Android 应用**

```bash
./gradlew :composeApp:assembleDebug
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

**3. 与同一局域网的人聊天**

1. 两台设备连接**同一个 Wi-Fi / 局域网**。
2. 双方打开 Syna，应用会自动互相发现（约 3 秒），对方会出现在**联系人**列表，带绿点表示在线。
3. 点击用户进入聊天页，输入消息发送，对方即时收到（✓✓ 表示已读）。
4. 无需服务器、无需联网即可使用。

---

## 2. LAN Features / 局域网功能

### English

| Feature / 功能 | How to use / 使用方法 |
| --- | --- |
| **Discovery / 自动发现** | Automatic. Every device broadcasts its presence every 3s via UDP (broadcast + multicast). Offline after 15s of silence. |
| **Custom username / 自定义用户名** | Settings (设置) → 自定义用户名 → type & wait 1s. The new name is broadcast live. |
| **1:1 chat / 单聊** | Contacts → tap a user. Supports read receipts ✓✓, encryption 🔒, burn-after-reading 🔥. |
| **Group chat (P2P mesh) / 群聊（P2P 网格）** | Contacts → 发起群聊 → select members → name the group → create. Messages are sent directly to every member, pairwise encrypted. |
| **Connection mode / 连接方式** | Settings → 连接方式: Auto (default, TCP reliable) / TCP / UDP (fast) / Host Hotspot. |
| **Dark / light theme / 明暗主题** | Settings → 外观: System / Light / Dark. |
| **Burn after reading / 阅后即焚** | Toggle 🔥 next to the input box (or set as default in Settings → 增强防护). The message shows for 8 seconds on the receiver's side, then is destroyed on **both** devices. |
| **Temporary chat / 临时聊天** | Settings → 增强防护 → 临时聊天 ON, choose TTL (1h / 24h / 7d). Conversations auto-purge after inactivity. |
| **Offline messages / 离线消息** | If the target is offline, messages queue locally and flush automatically when they come back online. |

**Tip:** in the chat header you can see connection mode, encryption status, and pending offline messages (`N 条待发送`).

### 中文

| 功能 | 使用方法 |
| --- | --- |
| **自动发现** | 自动。每台设备每 3 秒通过 UDP（广播 + 多播）宣告在线；15 秒无心跳判定离线。 |
| **自定义用户名** | 设置 → 自定义用户名 → 输入后稍等 1 秒，新名字会实时广播给局域网。 |
| **单聊** | 联系人 → 点击用户。支持已读回执 ✓✓、加密 🔒、阅后即焚 🔥。 |
| **群聊（P2P 网格）** | 联系人 → 发起群聊 → 勾选成员 → 命名 → 创建。消息直连每个成员，逐对加密。 |
| **连接方式** | 设置 → 连接方式：自动（默认，TCP 可靠）/ TCP / UDP（快速）/ 主机热点。 |
| **明暗主题** | 设置 → 外观：跟随系统 / 明亮 / 暗黑。 |
| **阅后即焚** | 输入框旁 🔥 开关（也可在 设置 → 增强防护 设为默认）。消息在接收方显示 8 秒后，**双方**设备同时销毁。 |
| **临时聊天** | 设置 → 增强防护 → 临时聊天 开启，选择 TTL（1小时/24小时/7天）。会话超过无活动时间自动清除。 |
| **离线消息** | 对方离线时消息进入本地队列，对方上线后自动补发。 |

**小技巧：** 聊天页顶部可看到连接方式、加密状态和待发送消息数（`N 条待发送`）。

---

## 3. Private Server / 私人服务器

### English

A private server is a persistent group chat hosted by you — like a Minecraft server. Anyone who knows the `IP:port + password` can join, and history is kept on the server even when everyone is offline.

**1. Start the server (two modes)**

```bash
# CLI mode (headless) — good for servers / remote machines
java -jar syna-server.jar -p 45880 -w YourSecretPassword -g "My Group"

# GUI mode — visual dashboard
java -jar syna-server.jar --ui
```

**2. All options / 所有参数**

| Option | Description / 说明 | Default / 默认 |
| --- | --- | --- |
| `-p, --port` | Listen port / 监听端口 | 45880 |
| `-w, --password` | Join password / 连接密码 | `syna` (change it!) |
| `-g, --group` | Group name / 群名称 | `Syna 私服` |
| `-d, --data-dir` | Data directory (history persisted here) / 数据目录（历史持久化） | `./syna-server-data` |
| `--history` | Max history messages / 历史消息条数上限 | 200 |
| `--ui` | Launch GUI dashboard / 启动图形界面 | — |

**3. Join the server from the client**

1. In the app: **Contacts → 加入服务器**.
2. Enter the server address (`IP:port` — LAN IP, public IP, or a tunnel domain), the password, and tap **加入服务器**.
3. The server group chat opens with the full message history. The header shows 🖥 and connection state.
4. If the server is behind NAT, see section 4 for remote access.

**4. Data & persistence / 数据与持久化**

- History is stored in `history.jsonl` inside the data directory, encrypted at the group level.
- The server survives restarts: it keeps its identity (`server-id`) and full history.
- Burn-after-reading messages are also removed from the server history when burned.

### 中文

私人服务器就是由你自己架设的持久化群聊——类似 Minecraft 服务器。知道 `IP:端口 + 密码` 的人都能加入，即使所有人离线，历史也保存在服务器上。

**1. 启动服务器（两种模式）**

```bash
# 命令行模式（无头）—— 适合服务器 / 远程机器
java -jar syna-server.jar -p 45880 -w 你的密码 -g "群名称"

# 图形界面模式 —— 可视化仪表盘
java -jar syna-server.jar --ui
```

**2. 所有参数**

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `-p, --port` | 监听端口 | 45880 |
| `-w, --password` | 连接密码 | `syna`（请务必修改！） |
| `-g, --group` | 群名称 | `Syna 私服` |
| `-d, --data-dir` | 数据目录（历史持久化于此） | `./syna-server-data` |
| `--history` | 历史消息条数上限 | 200 |
| `--ui` | 启动图形界面 | — |

**3. 客户端加入服务器**

1. 应用中：**联系人 → 加入服务器**。
2. 输入服务器地址（`IP:端口`——局域网 IP、公网 IP 或穿透域名均可）、密码，点击**加入服务器**。
3. 自动进入服务器群聊，并拉取完整历史记录。标题栏显示 🖥 与连接状态。
4. 服务器在路由器后面时，参考第 4 节远程接入。

**4. 数据与持久化**

- 历史以群密钥加密后存储在数据目录的 `history.jsonl` 中。
- 服务器重启后身份（`server-id`）与全部历史均保留。
- 阅后即焚消息焚毁时，服务器历史中的记录也会同步清除。

---

## 4. Remote Access (NAT Traversal) / 远程接入（内网穿透）

### English

Your home server usually sits behind a router (NAT), so friends outside the LAN cannot reach it directly. The simplest solution: use a third-party tunnel client to map the server port to the public internet — then everyone joins via the public `address:port`.

**Option A — frp (self-hosted, free, most popular)**

1. Rent a small public server (any cloud provider). Install `frps` on it:
   ```bash
   # frps.toml
   bindPort = 7000
   # run
   ./frps -c frps.toml
   ```
2. On your Syna server machine, run the `frpc` client:
   ```ini
   # frpc.toml
   serverAddr = "your-public-server-ip"
   serverPort = 7000

   [[proxies]]
   name = "synaserver"
   type = "tcp"
   localIP = "127.0.0.1"
   localPort = 45880
   remotePort = 45880
   ```
   ```bash
   ./frpc -c frpc.toml
   ```
3. Friends now join with `your-public-server-ip:45880` + password. Done!

**Option B — ngrok (no server needed, free tier)**

```bash
ngrok tcp 45880
```
ngrok prints a public address like `0.tcp.jp.ngrok.io:12345` — share that with the password.

**Option C — Tailscale (private network overlay, easiest for family/friends)**

1. Install Tailscale on your server machine and on friends' devices; log into the same account (Tailnet).
2. Everyone joins with the server's Tailscale IP: `100.x.x.x:45880` + password — works even across different networks, fully encrypted.

**Client side / 客户端操作**

In the app: **Contacts → 加入服务器** → fill in the public `address:port` + password → join. Everything else is automatic.

> **Note:** The Syna server itself is just a plain TCP listener. We deliberately do NOT build tunneling into the app — use whichever tunnel fits your habits.

### 中文

家里的服务器通常在路由器（NAT）后面，局域网外无法直接访问。最简单的办法：用第三方隧道客户端把服务器端口映射到公网，大家通过公网 `地址:端口` 加入。

**方案 A — frp（自建、免费、最流行）**

1. 租一台小公网服务器（任意云厂商），在上面安装并运行 `frps`：
   ```bash
   # frps.toml
   bindPort = 7000
   # 运行
   ./frps -c frps.toml
   ```
2. 在运行 Syna 服务器的机器上运行 `frpc` 客户端：
   ```ini
   # frpc.toml
   serverAddr = "你的公网服务器IP"
   serverPort = 7000

   [[proxies]]
   name = "synaserver"
   type = "tcp"
   localIP = "127.0.0.1"
   localPort = 45880
   remotePort = 45880
   ```
   ```bash
   ./frpc -c frpc.toml
   ```
3. 朋友用 `你的公网服务器IP:45880` + 密码 即可加入。

**方案 B — ngrok（无需服务器，有免费额度）**

```bash
ngrok tcp 45880
```
ngrok 会输出一个公网地址（如 `0.tcp.jp.ngrok.io:12345`），连同密码分享给朋友即可。

**方案 C — Tailscale（组网最简单，适合家人朋友）**

1. 在服务器机器和朋友设备上安装 Tailscale，登录同一账号（同一 Tailnet）。
2. 大家用服务器的 Tailscale IP：`100.x.x.x:45880` + 密码 加入——跨网络也能通，且全程加密。

**客户端操作**

应用中：**联系人 → 加入服务器** → 填公网 `地址:端口` + 密码 → 加入，其余全自动。

> **注意：** Syna 服务器本身只是一个普通的 TCP 监听器。我们刻意不在应用内内置穿透——用哪个隧道工具由你按习惯选择。

---

## 5. Security Model / 安全模型

### English

**LAN peer-to-peer (1:1 and mesh groups)**

- Every device generates an X25519 key pair on first launch (stored locally, never uploaded).
- Session keys are derived per peer (HKDF-SHA256) and messages are encrypted with **AES-256-GCM** — true end-to-end encryption, the server-free network path.
- Public keys are exchanged over the HELLO/KEY handshake frames.

**Private server**

- The server sends a random salt in `SRV_HELLO`; both sides derive an **AES-GCM channel key** from the join password (HKDF-SHA256) — all traffic is authenticated and encrypted, including the password itself.
- Group messages use a **password-derived group key**, so any member who knows the password can decrypt history — this is the "private server trust model" (the server operator is trusted, like a Minecraft server whitelist).
- LAN peer-to-peer chat remains fully E2E; only server-hosted groups use the group key.

**Burn after reading**

- The receiver shows the message for 8 seconds, then destroys it locally and sends `BURN_ACK`.
- The sender deletes its copy on ACK (with a 60s fallback timer).
- The private server also purges the message from its persisted history.

**What is NOT covered / 未覆盖**

- The server operator can read server-group messages (they know the password). If you need E2E through a server, a full group-key distribution protocol is on the roadmap.
- Metadata (sender IDs, message sizes, timing) is visible to the server operator.

### 中文

**局域网点对点（单聊与网格群聊）**

- 每台设备首次启动生成 X25519 密钥对（只存本地，永不上传）。
- 会话密钥按对端派生（HKDF-SHA256），消息用 **AES-256-GCM** 加密——真正的端到端加密，不经过任何服务器。
- 公钥通过 HELLO/KEY 握手帧交换。

**私人服务器**

- 服务器在 `SRV_HELLO` 中下发随机盐；双方用加入密码派生 **AES-GCM 通道密钥**（HKDF-SHA256）——所有流量（含密码本身）均加密且防篡改。
- 群消息使用**密码派生的群密钥**加密，任何知道密码的成员都能解密历史——这就是"私服信任模型"（服务器管理员可信，类似 Minecraft 白名单）。
- 局域网点对点聊天仍是完整 E2E；只有服务器托管的群聊使用群密钥。

**阅后即焚**

- 接收方显示消息 8 秒后本地销毁，并回发 `BURN_ACK`。
- 发送方收到 ACK 后删除本地副本（另有 60 秒兜底定时器）。
- 私人服务器也会从持久化历史中清除该消息。

**未覆盖的边界**

- 服务器管理员可以阅读服务器群聊消息（他们知道密码）。如需经服务器实现完整 E2E，群密钥分发协议在路线图中。
- 元数据（发送者 ID、消息大小、时间）对服务器管理员可见。

---

## 6. Troubleshooting / 故障排查

### English

| Problem / 问题 | Cause & fix / 原因与解决 |
| --- | --- |
| **Can't find anyone on the LAN / 找不到局域网用户** | 1) Both devices on the **same** Wi-Fi/subnet. 2) Some routers block UDP broadcast — the app falls back to multicast, but very restrictive networks block both; try enabling "Host Hotspot" mode instead. 3) Windows firewall / macOS firewall may block the app — allow it. |
| **Windows firewall popup / Windows 防火墙弹窗** | Click **Allow** for both private and public networks (needed for the client **and** the server). |
| **Server not reachable from another device / 服务器连不上** | 1) Check `java -jar syna-server.jar` shows the port listening. 2) `telnet <ip> <port>` from the other device — if it fails, the router's port forwarding / firewall is blocking it. 3) On the server machine, `lsof -iTCP:<port> -sTCP:LISTEN` (macOS/Linux) or `netstat -ano | findstr <port>` (Windows). |
| **Join server says "authentication failed" / 提示认证失败** | Wrong password, or the server operator changed it. Note: empty password is not accepted. |
| **History not loading / 历史没加载** | Server history is capped (default 200 messages) and burn-after-reading messages are intentionally removed. Oldest messages drop off first. |
| **Messages show 🔒 but can't decrypt / 消息显示🔒但解不开** | Normally impossible (keys auto-exchange). If seen, delete local app data and re-join — identity keys regenerate. |
| **Android can't discover / Android 无法发现** | Android 9+ needs location permission **only** for Wi-Fi scan features; make sure you're on the same subnet, and that "private DNS" isn't breaking multicast. On Android 14+ local network permissions are stricter. |
| **Port already in use / 端口被占用** | Change the port with `-p`, e.g. `-p 45900`. |
| **Multiple instances on one machine share identity / 同机多实例共享身份** | Desktop settings are stored in user-level Java preferences; different OS accounts get different identities. This is by design. |

### 中文

| 问题 | 原因与解决 |
| --- | --- |
| **找不到局域网用户** | 1) 两台设备必须在**同一个** Wi-Fi/子网。2) 部分路由器拦截 UDP 广播——应用有广播+多播双通道兜底，但极严格网络两者都拦；可改用"主机热点"模式。3) Windows/macOS 防火墙可能拦截应用，需放行。 |
| **Windows 防火墙弹窗** | 请点击**允许**（专用和公用网络都要）——客户端和服务器都需要。 |
| **其他设备连不上服务器** | 1) 确认服务器已启动且显示端口。2) 在另一台设备执行 `telnet <IP> <端口>`，失败说明路由器端口转发或防火墙在拦。3) 服务器机器上执行 `lsof -iTCP:<端口> -sTCP:LISTEN`（macOS/Linux）或 `netstat -ano | findstr <端口>`（Windows）。 |
| **加入服务器提示认证失败** | 密码错误，或管理员改过密码。注意：不允许空密码。 |
| **历史没加载** | 服务器历史有上限（默认 200 条），阅后即焚消息会被刻意清除；最早的消息最先被挤出。 |
| **消息显示🔒但解不开** | 正常情况下不可能（密钥自动交换）。若出现，清除应用数据重新加入即可——身份密钥会重新生成。 |
| **Android 无法发现设备** | 确认同一子网；Android 14+ 本地网络权限更严格；部分系统的"私人 DNS"会干扰多播。 |
| **端口被占用** | 用 `-p` 换端口，如 `-p 45900`。 |
| **同机多实例共享身份** | 桌面端设置存于用户级 Java 偏好，不同系统账号身份不同。这是设计使然。 |

---

## 7. Build & Distribute / 构建与分发

### English

```bash
# Desktop app (dev) / 桌面应用（开发运行）
./gradlew :composeApp:run

# Server fat jar (all platforms) / 服务器打包（跨平台）
./gradlew :composeApp:serverFatJar
# → composeApp/build/server/syna-server.jar

# Android APK / Android 安装包
./gradlew :composeApp:assembleDebug            # debug
./gradlew :composeApp:assembleRelease          # release (needs keystore in local.properties)

# Tests / 测试（21 项）
./gradlew :composeApp:desktopTest
```

**Desktop native packages / 桌面原生包**

```bash
./gradlew :composeApp:createDistributable
# macOS:  .app bundle; Windows MSI needs a Windows machine with WiX; Linux: Deb/Rpm via packageDistributable
```

**Server as a service (Linux, systemd example) / Linux 服务化示例**

```ini
# /etc/systemd/system/syna-server.service
[Unit]
Description=Syna Private Chat Server
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/syna/syna-server.jar -p 45880 -w YourPassword -g "My Group"
Restart=on-failure
User=syna

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now syna-server
```

### 中文

```bash
# 桌面应用（开发运行）
./gradlew :composeApp:run

# 服务器打包（跨平台 fat jar）
./gradlew :composeApp:serverFatJar
# → composeApp/build/server/syna-server.jar

# Android 安装包
./gradlew :composeApp:assembleDebug            # 调试版
./gradlew :composeApp:assembleRelease          # 发布版（需在 local.properties 配置签名）

# 测试（21 项）
./gradlew :composeApp:desktopTest
```

**桌面原生包**

```bash
./gradlew :composeApp:createDistributable
# macOS: .app 应用包；Windows MSI 需 Windows 机器 + WiX；Linux: 用 packageDistributable 生成 Deb/Rpm
```

**Linux 服务化示例（systemd）**

```ini
# /etc/systemd/system/syna-server.service
[Unit]
Description=Syna 私人聊天服务器
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/syna/syna-server.jar -p 45880 -w 你的密码 -g "群名称"
Restart=on-failure
User=syna

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now syna-server
```

---

## More resources / 更多资源

- README: architecture, protocol, platform notes / 架构、协议、平台注意点
- GitHub Releases: prebuilt APK, macOS bundle, server jar / 预编译产物
- Issues: bug reports & feature requests welcome / 欢迎提交 Issue
