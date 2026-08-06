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

**Wire protocol** (ports: 45877 discovery / 45878 UDP data / ephemeral TCP):

- Frame: `{type, from, to, msgId, ts, body, enc, burn}` JSON via kotlinx.serialization
- Frame types: HELLO / KEY / TEXT / GROUP_MESSAGE / GROUP_INVITE / GROUP_JOIN / GROUP_LEAVE / READ / BURN_ACK / PING / PONG
- E2E encryption: public key is carried in the HELLO frame on TCP connect; peers reply with their own KEY. Session keys are derived from `sorted(peerIds)` so both sides always agree.

## Build & Run

```bash
# Desktop (macOS / Windows / Linux)
./gradlew :composeApp:run

# Android debug APK
./gradlew :composeApp:assembleDebug
# Output: composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Android release APK (requires signing config in local.properties)
./gradlew :composeApp:assembleRelease

# Tests (16: crypto / protocol / loopback chat / group mesh / burn-after-reading / temp chat / offline outbox)
./gradlew :composeApp:desktopTest
```

## Platform Notes

- **Windows**: allow the app through the firewall on first launch; an MSI installer can be produced with `nativeDistributions` (requires Windows + WiX)
- **Android 14+**: local network access is restricted; some routers block UDP broadcast (the multicast channel is the fallback)
- **Group chat**: there is no central node — members who are offline miss group messages (store-and-forward for groups is on the roadmap)
- **Message persistence**: messages live in memory and are cleared on restart; SQLDelight persistence is on the roadmap
- **Multiple instances on one machine**: desktop settings are stored in user-level Java preferences (`Preferences.userRoot`), so multiple instances under the same OS account share one identity
- **Debug logging**: `[Syna:Engine/Discovery/Send/Outbox/Crypto]` prefix on stdout / logcat

## Roadmap

- [ ] Message history persistence (SQLDelight)
- [ ] Group message store-and-forward (offline delivery)
- [ ] Image / file transfer (chunked, hybrid UDP/TCP)
- [ ] Typing indicator, message recall
- [ ] Android Keystore hardware-backed key storage
