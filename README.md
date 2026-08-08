# Syna

**Syna** is a LAN instant messaging app built with **Kotlin Multiplatform + Compose Multiplatform**. It runs on **Android**, **Windows**, and **macOS** with a fully peer-to-peer architecture — no central server, no internet required.

> 🤖 **AI-generated code notice / AI 生成代码声明**: This project's source code was predominantly generated with the assistance of AI agents (LLM-based coding assistants), then reviewed and tested by humans. Please review code before relying on it in production.
> 本项目源代码主要由 AI 智能体（大语言模型编程助手）生成，经人工审查与测试后发布。生产环境使用前请自行复核。

> 📖 **New to Syna? Read the tutorial** — quick start, LAN chat, private server setup, NAT traversal (frp/ngrok/Tailscale), security model & troubleshooting.
> 📖 **初次使用？请看教程** — 快速开始、局域网聊天、私人服务器搭建、内网穿透、安全模型与故障排查。
>
> **English**: [TUTORIAL_EN.md](TUTORIAL_EN.md) · **中文**: [TUTORIAL_ZH.md](TUTORIAL_ZH.md) · **入口**: [TUTORIAL.md](TUTORIAL.md)

## Features

- ✅ **Multi-platform**: Android (APK) + Desktop (Windows/macOS share the same JVM build)
- ✅ **LAN peer discovery**: UDP broadcast + multicast dual-channel, 3s heartbeat, 15s offline timeout, **manual refresh button** for troubleshooting
- ✅ **Custom username**: change it anytime in Settings, broadcast to LAN peers instantly
- ✅ **WeChat-style chat**: conversation list (unread badges / timestamps / previews), chat bubbles, ✓✓ read receipts, connection status display
- ✅ **Images & files**: chunked 64KB transfer with progress bar, image preview in bubbles, system file picker (Android / desktop)
- ✅ **Typing indicator**: live "正在输入…" status in 1:1, LAN groups and server groups
- ✅ **Message recall**: long-press your message → recall within 2 minutes, both sides marked
- ✅ **Quote reply & @mentions**: long-press → reply with quoted preview; @ member picker in groups
- ✅ **System notifications**: Android notification bar / desktop tray popup for new messages
- ✅ **Connection mode switching**: Auto / TCP (reliable) / UDP (fast) / Host Hotspot
- ✅ **P2P mesh group chat**: create → invite → mesh membership sync (JOIN/LEAVE), **group owner can dissolve the group**, members can leave
- ✅ **Enhanced protection**:
  - **End-to-end encryption**: X25519 key exchange + HKDF-SHA256 + AES-256-GCM; private keys stay on your device only
  - **Burn after reading**: message shows for 8 seconds, then is destroyed on both sides with BURN_ACK confirmation + 60s fallback
  - **Temporary chat**: conversations auto-purge after a TTL (1h / 24h / 7d) on both devices
- ✅ **Dark / light / system theme**
- ✅ **Contact management**: delete contacts (persistent block, re-discover after unblock), blocked list management in Settings
- ✅ **Offline messages**: messages queue locally when the target is offline and are flushed automatically when they come back online
- ✅ **Auto-reconnect**: TCP heartbeat keep-alive + on-demand reconnection
- ✅ **Private server (Minecraft-style)**: run your own headless chat server on Windows / macOS / Linux, join by `IP:port + password` from anywhere
  - Persistent history on the server — late joiners pull the full backlog automatically
  - Password-derived AES-GCM channel + password-derived group key encryption
  - **Server management**: kick & ban members (persistent blacklist), group announcements, GUI dashboard or headless CLI
  - Burn-after-reading messages are purged from the server history too
  - **NAT traversal**: just map the server port to the public internet with any tunnel tool (frp / ngrok / Tailscale) — the client only needs the public `address:port`
- ✅ **CI**: GitHub Actions auto-builds tests, Android APK, server jar and macOS DMG; tag pushes publish a GitHub Release

## Architecture

```
composeApp/
├── src/commonMain/          # Shared UI + domain logic
│   ├── ui/                  # Chats / Contacts / CreateGroup / Chat / Settings screens
│   ├── net/                 # SynaEngine, protocol frames, transport abstraction
│   ├── chat/                # ChatStore (conversations & messages state)
│   ├── crypto/              # Crypto abstraction (expect)
│   └── storage/             # Settings persistence
├── src/jvmShared/           # JVM implementations shared by Android & Desktop
│   ├── net/                 # java.net transports, UDP discovery, heartbeat
│   └── crypto/              # X25519 / AES-GCM / HKDF implementation, key files
├── src/androidMain/         # Android entry, MulticastLock, permissions
└── src/desktopMain/         # Desktop entry, key directory
```

**Wire protocol** (ports: 45877 discovery / 45878 UDP data / ephemeral TCP; server default 45880):

- Frame: `{type, from, to, msgId, ts, body, enc, burn}` JSON via kotlinx.serialization
- Frame types: HELLO / KEY / TEXT / GROUP_MESSAGE / GROUP_INVITE / GROUP_JOIN / GROUP_LEAVE / READ / BURN_ACK / PING / PONG / SRV_HELLO / SRV_AUTH / SRV_AUTH_OK / SRV_LEAVE
- LAN E2E encryption: public key is carried in the HELLO frame on TCP connect; peers reply with their own KEY. Session keys are derived from `sorted(peerIds)` so both sides always agree.
- Server security model: the server sends a salt in `SRV_HELLO`; both sides derive an AES-GCM channel key from the server password (HKDF-SHA256). Group messages use a password-derived group key, so any member who knows the password can decrypt history — the private-server trust model. Metadata routing stays visible to the server operator.

## Private Server (Syna Server)

Run your own persistent group chat server on any JVM machine — Windows / macOS / Linux. Two modes for different preferences:

**① CLI (headless)** — perfect for servers / remote machines / systemd:
```bash
java -jar syna-server.jar -p 45880 -w YourSecretPassword -g "My Group"
```

**② Launcher (GUI)** — visual launcher for everyone else:
- **macOS / Windows / Linux**: download the **SynaServer launcher app** from [GitHub Releases](https://github.com/Verlintas/Syna-NUSV/releases), double-click to open — no terminal needed.
- Or run the jar directly on a machine with a display:
```bash
java -jar syna-server.jar            # auto-launches the launcher on desktop; falls back to CLI headless
java -jar syna-server.jar --launcher # force launcher mode
```
The launcher provides:
- **Persistent config** — port / password / group / data dir saved to `~/.syna-server/launcher.json`, restored on next launch
- **One-click start / stop**, live status (port, LAN addresses, member list, history count, scrolling logs)
- **Crash auto-restart** — the server is pulled back up 3s after an unexpected exit (manual stop is never restarted)
- **Start at login** — macOS LaunchAgent / Linux autostart / Windows Startup folder, toggle in the UI
- **Open data folder** button, server moderation (kick/ban/announcements)

```
# options
#   -p, --port <port>       listen port (default 45880)
#   -w, --password <pw>     join password (default "syna" — change it!)
#   -g, --group <name>      group name (default "Syna 私服")
#   -d, --data-dir <path>   data dir for persistent history (default ./syna-server-data)
#       --history <count>   max history messages (default 200)
#       --ui                launch the graphical dashboard
```

The server persists encrypted message history to `history.jsonl` and survives restarts.

**Access from the internet (NAT traversal):** the server is a plain TCP listener, so map its port to the public internet with any tunnel client and join via the public address:

| Tool | Command / config |
| --- | --- |
| frp | `[synaserver] type = tcp, local_port = 45880, remote_port = 45880` |
| ngrok | `ngrok tcp 45880` |
| Tailscale | use the node IP directly inside your Tailnet |

In the Syna app: **Contacts → Join Server → enter `public-address:port` + password** → the server group chat opens with full history.

## Build & Run

```bash
# Desktop (macOS / Windows / Linux)
./gradlew :composeApp:run

# macOS 安装包 (dmg)
./gradlew :composeApp:packageDmg
# Output: composeApp/build/compose/binaries/main/dmg/Syna-1.0.0.dmg

# Windows 安装包 (msi, 需 Windows + WiX) / Linux (deb/rpm)
./gradlew :composeApp:packageMsi
./gradlew :composeApp:packageDeb

# Syna server fat jar (headless, all platforms)
./gradlew :composeApp:serverFatJar
# Output: composeApp/build/server/syna-server.jar  →  java -jar syna-server.jar

# Android debug APK
./gradlew :composeApp:assembleDebug
# Output: composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Android release APK (requires signing config in local.properties)
./gradlew :composeApp:assembleRelease

# Tests (21: crypto / protocol / loopback chat / group mesh / burn-after-reading / temp chat / offline outbox / server join+chat+history+burn)
./gradlew :composeApp:desktopTest
```

## Platform Notes

- **Windows**: allow the app through the firewall on first launch (both the GUI client and the server); an MSI installer can be produced with `nativeDistributions` (requires Windows + WiX)
- **Android 14+**: local network access is restricted; some routers block UDP broadcast (the multicast channel is the fallback)
- **Group chat (LAN mesh)**: there is no central node — members who are offline miss group messages; the **private server** fixes this with persistence
- **Message persistence**: LAN chats live in memory; the private server persists history to disk
- **Multiple instances on one machine**: desktop settings are stored in user-level Java preferences (`Preferences.userRoot`), so multiple instances under the same OS account share one identity
- **Debug logging**: `[Syna:Engine/Discovery/Send/Outbox/Crypto]` prefix on stdout / logcat; server logs `[SynaServer]`

## Roadmap

- [ ] LAN message history persistence (SQLDelight)
- [ ] Server TLS certificate support (instead of password-derived channel)
- [ ] Multiple groups per server
- [ ] Voice messages

## License / 许可证

This project is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE). The `LICENSE` file contains the **verbatim, unmodified official GPL-3.0 text** (no additional terms appended).

本项目使用 **GNU GPL v3.0** 许可证发布，详见 [LICENSE](LICENSE)。`LICENSE` 文件为**未经修改的 GPL-3.0 官方原文**（未附加任何额外条款）。

**Source headers / 源码版权头**: every source file (Kotlin / Gradle scripts / tools / CI workflow) carries the standard GPL short license header with `SPDX-License-Identifier: GPL-3.0-only`.

每个源码文件（Kotlin / Gradle 脚本 / 工具 / CI 工作流）均带有标准 GPL 短版权头与 `SPDX-License-Identifier: GPL-3.0-only` 标识。

**Dependency license audit / 依赖许可证审计**: all runtime dependencies use permissive licenses (Apache-2.0 / MIT), fully compatible with GPL-3.0 — no copyleft or GPL-incompatible dependencies. Audit report generated from the Gradle dependency cache; see the audit script and report in `tools/license-audit/`.

所有运行时依赖均为宽松许可证（Apache-2.0 / MIT），与 GPL-3.0 完全兼容，不存在许可证污染；审计脚本与报告见 `tools/license-audit/`。

**AI generation / AI 生成声明**: see the notice at the top of this README (the AI-generated-code statement and audit notes live in the docs, **not** in the LICENSE file).

**Assets / 资源**: `Syna_logo.png` is provided by the project owner. Other assets (icons, documentation) are generated by this project.
