# Syna

**Syna** is a LAN instant messaging app built with **Kotlin Multiplatform + Compose Multiplatform**. It runs on **Android**, **Windows**, and **macOS** with a fully peer-to-peer architecture — no central server, no internet required.

## Features

- ✅ **Multi-platform**: Android (APK) + Desktop (Windows/macOS share the same JVM build)
- ✅ **LAN peer discovery**: UDP broadcast + multicast dual-channel, 3s heartbeat, 15s offline timeout
- ✅ **Custom username**: change it anytime in Settings, broadcast to LAN peers instantly
- ✅ **WeChat-style chat**: conversation list (unread badges / timestamps / previews), chat bubbles, ✓✓ read receipts, connection status display
- ✅ **Connection mode switching**: Auto / TCP (reliable) / UDP (fast) / Host Hotspot
- ✅ **P2P mesh group chat**: create → invite → mesh membership sync (JOIN/LEAVE), messages encrypted pairwise and delivered directly to each member — no central node
- ✅ **Enhanced protection**:
  - **End-to-end encryption**: X25519 key exchange + HKDF-SHA256 + AES-256-GCM; private keys stay on your device only
  - **Burn after reading**: message shows for 8 seconds, then is destroyed on both sides with BURN_ACK confirmation + 60s fallback
  - **Temporary chat**: conversations auto-purge after a TTL (1h / 24h / 7d) on both devices
- ✅ **Dark / light / system theme**
- ✅ **Offline messages**: messages queue locally when the target is offline and are flushed automatically when they come back online
- ✅ **Auto-reconnect**: TCP heartbeat keep-alive + on-demand reconnection
- ✅ **Private server (Minecraft-style)**: run your own headless chat server on Windows / macOS / Linux, join by `IP:port + password` from anywhere
  - Persistent history on the server — late joiners pull the full backlog automatically
  - Password-derived AES-GCM channel + password-derived group key encryption
  - Burn-after-reading messages are purged from the server history too
  - **NAT traversal**: just map the server port to the public internet with any tunnel tool (frp / ngrok / Tailscale) — the client only needs the public `address:port`

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

Run your own persistent group chat server on any JVM machine — Windows / macOS / Linux.

```bash
java -jar syna-server.jar -p 45880 -w YourSecretPassword -g "My Group"

# options
#   -p, --port <port>       listen port (default 45880)
#   -w, --password <pw>     join password (default "syna" — change it!)
#   -g, --group <name>      group name (default "Syna 私服")
#   -d, --data-dir <path>   data dir for persistent history (default ./syna-server-data)
#       --history <count>   max history messages (default 200)
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
- [ ] Image / file transfer (chunked, hybrid UDP/TCP)
- [ ] Typing indicator, message recall
- [ ] Android Keystore hardware-backed key storage
- [ ] Server admin UI (member management, kick/ban)
- [ ] Server TLS certificate support (instead of password-derived channel)
