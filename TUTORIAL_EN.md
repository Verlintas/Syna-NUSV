# Syna Tutorial (English)

Step-by-step guide: LAN chat, private server, remote access (NAT traversal), security, and troubleshooting.

**Chinese version?** → [中文版教程](TUTORIAL_ZH.md)

---
## Table of Contents

- [Quick Start](#1-quick-start)
- [LAN Features](#2-lan-features)
- [Private Server](#3-private-server)
- [Physical Machine Setup](#4-physical-machine-setup)
- [Remote Access (NAT Traversal)](#5-remote-access-nat-traversal)
- [Security Model](#6-security-model)
- [Troubleshooting](#7-troubleshooting)
- [Build & Distribute](#8-build-distribute)
- [More Resources](#more-resources)

---

## 1. Quick Start


**Requirements**

- JVM 17+ (desktop) — download from [Adoptium](https://adoptium.net) if needed
- Android 9+ (API 28+) for the Android app
- Windows / macOS / Linux for the desktop app and server

**1. Run the desktop app**

```bash
git clone https://github.com/NUSV/Syna-NUSV.git
cd Syna-NUSV
./gradlew :composeApp:run
```

A window titled **Syna** opens. Three tabs at the bottom: **Chats**, **Contacts**, **Settings**.

**2. Run the Android app**

```bash
./gradlew :composeApp:assembleDebug
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

**3. Chat with someone on the same LAN**

1. Connect both devices to the **same Wi-Fi / LAN**.
2. Open Syna on both. They find each other automatically (~3 seconds) and appear in **Contacts** with a green dot (online).
3. Tap a user → chat screen → type a message → send. The other side sees it instantly (✓✓ = read).
4. Enjoy! No server, no internet needed.


## 2. LAN Features


| Feature | How to use |
| --- | --- |
| **Discovery** | Automatic. Every device broadcasts its presence every 3s via UDP (broadcast + multicast). Offline after 15s of silence. |
| **Manual refresh** | Contacts top-right ⟳ Refresh: immediately re-broadcast your presence and re-compute online status — great for troubleshooting. |
| **Custom username** | Settings → Custom username → type & wait 1s. The new name is broadcast live. |
| **1:1 chat** | Contacts → tap a user. Supports read receipts ✓✓, encryption 🔒, burn-after-reading 🔥. |
| **Images & files** | Tap 📎 in the chat input row → pick a file (system picker). Transfer is chunked with a live progress %; images render inline in the bubble. |
| **Typing indicator** | Automatically shows "xxx is typing…" in the chat header while the other side is typing. |
| **Message recall** | Long-press your own message → recall within 2 minutes. Both sides see "[Message recalled]". |
| **Quote reply** | Long-press any message → Reply. A quoted preview appears above your input and in the bubble. |
| **@mentions** | In group chats, tap the @ button in the input row → pick a member → `@name` is inserted and carried in the message. |
| **Group chat (P2P mesh)** | Contacts → New Group → select members → name the group → create. Messages are sent directly to every member, pairwise encrypted. |
| **Dissolve** | In a group chat header: the owner sees Dissolve (notifies everyone & wipes the group), members see Leave. |
| **Delete contact** | Contacts → tap ✕ on a row: removes the contact & conversation and blocks their broadcasts. Undo anytime in Settings → Blocked contacts → Unblock. |
| **Connection mode** | Settings → Connection mode: Auto (default, TCP reliable) / TCP / UDP (fast) / Host Hotspot. Each device gets its own UDP port, so multiple instances on one machine work too. |
| **Dark** | Settings → Appearance: System / Light / Dark. |
| **Burn after reading** | Toggle 🔥 next to the input box (or set as default in Settings → Enhanced protection). The message shows for 8 seconds on the receiver's side, then is destroyed on **both** devices. |
| **Temporary chat** | Settings → Enhanced protection → Temporary chat ON, choose TTL (1h / 24h / 7d). Conversations auto-purge after inactivity. |
| **System notifications** | New messages pop up in the Android notification bar or the desktop tray while the chat is not open (burn-after-reading content stays hidden). |
| **Offline messages** | If the target is offline, messages queue locally and flush automatically when they come back online. |
| **◇Mirtazapine Shield** | Settings → ◇Mirtazapine Shield → one switch enables real-time monitoring + app lock (see section 2.5): detection, biometric + optional TOTP unlock, data-level key gate, self-destruct, live status panel. |

**Tip:** in the chat header you can see connection mode, encryption status, and pending offline messages (`N pending`).

## 2.5 ◇Mirtazapine Shield

A real-time security monitor and app lock built into the client. Full design & detection matrix: [MIRTAZAPINE_SHIELD.md](MIRTAZAPINE_SHIELD.md).

- **Enable**: Settings → ◇Mirtazapine Shield → toggle on (disabling requires biometric verification). One switch enables every protection.
- **What it detects**: Root (incl. Magisk hidden root, Xposed, Zygisk/Shamiko/LSPosed/Riru/EdXposed/TaiChi), Frida injection (paths, ports 27042+27043, memory maps, threads, TracerPid — **JVM + native (NDK) dual-channel**), emulator (incl. test-keys), USB debugging/ADB, SELinux, device-admin takeover (MDM), credential changes, VPN/proxy changes, **user CA additions & ARP spoofing (LAN MITM)**, network fingerprint (SSID), system proxy, device identity, rapid background switching, accessibility abuse, monitoring apps (installed + foreground; **signature-learning blacklist — renames don't bypass**), **screen capture/recording events (API 34+)**, mirroring changes, clock tampering, weak lock screen, IME/USB changes, **suspicious executable modules (partition whitelist + non-JIT memfd, name-independent)**, downgrade attempts, JVM agents, remote-control processes.
- **When a threat is detected**: full-screen shield page listing the threat(s) (with concrete module/proxy details); unlock requires **biometrics (strong-only, device-credential window ineffective) + (optional) TOTP second factor** (6-digit RFC 6238 code from your authenticator app; seed via `otpauth://` import in Settings). Unlock expires after 5 minutes; critical threats re-lock after 30s; injection threats engage a honeypot fake-lock (real key release, repeated verification). **Disabling the shield requires biometrics + TOTP too — an attacker cannot turn it off.**
- **Session-key rotation**: every unlock rotates the session key (forward secrecy).
- **Data-level key gate**: chat data is encrypted with a session key wrapped by a **biometric-authenticated Keystore key** — without an authentication event, newly written data is unreadable (data-level protection on device loss/compromise, independent of detection); locking releases the in-memory session key.
- **Self-destruct protocol (optional)**: a critical compromise signal wipes local chats & received files immediately and clears clipboard & notifications.
- **Self-protection**: APK signature verification (anti-repackaging), dex hash self-verification, downgrade defense, HMAC-signed settings, in-memory state HMAC, **fail-closed heartbeat gate** (stalled detector → decrypt refused), **watchdog ring** (3 threads monitor each other), **native anti-hook layer** (NDK: syscall-direct I/O defeats GOT/PLT/LD_PRELOAD hooks; own-code-segment memory-vs-disk hashing + export-entry self-verification defeat inline hooks; libc entry verification — JVM + native dual-channel), screen-capture protection & capture-event detection, clipboard/notification protection, hash-chained + AES-GCM audit log, brute-force protection (fail limit + exponential cooldown), 60s background memory wipe.
- **Live status panel**: gate freshness, watchdog trips, honeypot state, biometric fail counter, latest audit events.
- **Honest boundary**: device-owner-level monitoring (pre-installed spyware / MDM) and kernel-level rootkits (forged /proc) cannot be detected by an app; the fail-closed gate, data-level key gate, native anti-hook and self-destruct are the compensating controls — see [MIRTAZAPINE_SHIELD.md §16](MIRTAZAPINE_SHIELD.md#16-honest-boundary-again-plainly). Protection stays effective under full disclosure (GPL-3.0).

## 3. Private Server


A private server is a persistent group chat hosted by you — like a Minecraft server. Anyone who knows the `IP:port + password` can join, and history is kept on the server even when everyone is offline.

**1. Start the server (two modes)**

```bash
# CLI mode (headless) — good for servers / remote machines
java -jar syna-server.jar -p 45880 -w YourSecretPassword -g "My Group"

# Launcher (GUI) — auto-launches on any machine with a display;
# falls back to headless CLI on servers
java -jar syna-server.jar            # auto-launches the launcher
java -jar syna-server.jar --launcher # force launcher mode
```
The launcher keeps its config in `~/.syna-server/launcher.json`, offers one-click start/stop, live status (port, LAN addresses, member list, history count, scrolling logs), **crash auto-restart** (3s), **start at login** (macOS / Linux / Windows), an open-data-folder button, and server moderation (kick / ban / announcements).

**2. All options**

| Option | Description | Default |
| --- | --- | --- |
| `-p, --port` | Listen port | 45880 |
| `-w, --password` | Join password | `syna` (change it!) |
| `-g, --group` | Group name | `Syna Private` |
| `-d, --data-dir` | Data directory (history persisted here) | `./syna-server-data` |
| `--history` | Max history messages | 200 |
| `--ui` | Launch GUI dashboard | — |

**3. Join the server from the client**

1. In the app: **Contacts → Join Server**.
2. Enter the server address (`IP:port` — LAN IP, public IP, or a tunnel domain), the password, and tap **Join Server**.
3. The server group chat opens with the full message history. The header shows 🖥 and connection state.
4. If the server is behind NAT, see [section 5](#5-remote-access-nat-traversal) for remote access.

**4. Data & persistence**

- History is stored in `history.jsonl` inside the data directory, encrypted at the group level.
- The server survives restarts: it keeps its identity (`server-id`) and full history.
- Burn-after-reading messages are also removed from the server history when burned.
- Recalled messages stay marked as recalled in the history, so late joiners see the correct state.

**5. Server management (GUI mode)**

Run `java -jar syna-server.jar --ui` and start the server to get the dashboard:

- **Kick & ban**: each member row has a Kick button — the member is disconnected, notified (`GROUP_KICK`), and their user ID is added to a **persistent blacklist** (`bans.json`). Banned users cannot rejoin until you click Unban in the banned list.
- **Announcements**: type a message in the Announcement box and click Post. It is broadcast to all online members, shown as a 📢 banner at the top of their chat, and sent to newly joined members automatically. Clients can dismiss the banner with ✕.
- **Live status**: member list with online dots, history message count, running port, LAN addresses, and a scrolling log console.


## 4. Physical Machine Setup


**Who is this for?** Anyone with a spare PC, an old laptop, a NAS, or a Raspberry Pi who wants a **dedicated, always-on** chat server at home. Syna Server is very light — it uses only ~30–60 MB RAM, so even a 10-year-old machine runs it easily.

**What you need**

- One physical machine (Windows / macOS / Linux — see details below). A Raspberry Pi 3B+ or better works great.
- Java 17 (JRE is enough, you do NOT need the full JDK).
- The server jar: download `Syna-server-v0.3.0.jar` from the [GitHub Releases](https://github.com/NUSV/Syna-NUSV/releases) page.
- (Optional) Your router admin password for port forwarding — see [Router port forwarding](#router-port-forwarding) below.

**The 4-step formula for every OS**

1. Install Java 17
2. Put `syna-server.jar` in a fixed folder
3. Start it with your password & group name
4. Open the firewall port (and optionally enable auto-start)

After that, anyone on your LAN joins with `YOUR_LAN_IP:45880` + password. For friends outside your home, follow [Section 5](#5-remote-access-nat-traversal).

---

#### Windows (easiest — just click along)

**Step 1 — Install Java 17**

1. Open [adoptium.net](https://adoptium.net) in a browser.
2. Click **Windows x64** → download the `.msi` installer → run it → click **Next** all the way (keep all defaults).
3. Verify: press `Win+R`, type `cmd`, press Enter, then type:
   ```
   java -version
   ```
   You should see something like `openjdk version "17..."`. Done.

**Step 2 — Place the server program**

1. Create a folder: `D:\SynaServer` (or anywhere you like, avoid Chinese characters in the path).
2. Move `syna-server.jar` into it.

**Step 3 — Start the server**

Open CMD (`Win+R` → `cmd`) and run:

```
java -jar D:\SynaServer\syna-server.jar -p 45880 -w YOUR_PASSWORD -g YOUR_GROUP_NAME
```

You should see the banner with `Port: 45880`. **Keep this window open** — closing it stops the server.

> Prefer a GUI? Run `java -jar D:\SynaServer\syna-server.jar --ui` and click "Start server" in the window.

**Step 4 — Allow it through the firewall (where beginners get stuck!)**

1. Press `Win` key, type **Windows Defender Firewall**, open it.
2. Click **Advanced settings** (left panel, needs admin).
3. Click **Inbound rules** → right panel **New Rule…**
4. Choose **Port** → Next → **TCP** + specific local ports: `45880` → Next → **Allow the connection** → Next → tick all three (Domain / Private / Public) → Next → name it `Syna Server` → **Finish**.
5. Now other devices can connect. (If you changed the port with `-p`, use that number instead.)

**Optional — Auto-start on boot**

1. Press `Win+R`, type `shell:startup`, press Enter — a folder opens.
2. Create a file `SynaServer.bat` inside it with:
   ```bat
   @echo off
   java -jar D:\SynaServer\syna-server.jar -p 45880 -w YOUR_PASSWORD -g YOUR_GROUP_NAME
   ```
3. Done — the server starts automatically every login.

**Find your LAN IP (to share with friends/family)**

Run `ipconfig` in CMD and find the **IPv4 address** (like `192.168.1.100`). Others on the LAN join with `192.168.1.100:45880`.

---

#### macOS (just as easy)

**Step 1 — Install Java 17**

- Option A (recommended for beginners): download the **macOS .dmg** from [adoptium.net](https://adoptium.net) (choose `Apple Silicon` if you have an M-series Mac, otherwise `x64`), open the dmg, double-click to install.
- Option B (if you have Homebrew): `brew install --cask temurin@17`

Verify in Terminal (`Cmd+Space` → `Terminal`):

```
java -version
```

**Step 2 — Place & start**

```bash
mkdir -p ~/SynaServer
# move the downloaded syna-server.jar into it, then:
java -jar ~/SynaServer/syna-server.jar -p 45880 -w YOUR_PASSWORD -g YOUR_GROUP_NAME
```

Or use the GUI mode if you prefer clicking: `java -jar ~/SynaServer/syna-server.jar --ui`

**Step 3 — Firewall (if you ever turned it on)**

1. System Settings → Network → Firewall.
2. If it's on and Java isn't listed as allowed, click Options → add `java` and allow incoming connections.
3. (Usually fine if the firewall was never turned on.)

**Optional — Auto-start (launchd)**

Create `/Library/LaunchDaemons/com.syna.server.plist` (needs admin):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.syna.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/java</string>
        <string>-jar</string>
        <string>/Users/YOUR_USERNAME/SynaServer/syna-server.jar</string>
        <string>-p</string>
        <string>45880</string>
        <string>-w</string>
        <string>YOUR_PASSWORD</string>
        <string>-g</string>
        <string>YOUR_GROUP_NAME</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
</dict>
</plist>
```

Then:

```bash
sudo launchctl load /Library/LaunchDaemons/com.syna.server.plist
```

**Find your LAN IP**: System Settings → Wi-Fi → Details → IP address (e.g. `192.168.1.100`).

---

#### Linux (Ubuntu / Debian / Raspberry Pi — great for always-on)

> Raspberry Pi works too: after installing a 64-bit OS, the commands below are identical.

**Step 1 — Install Java 17**

```bash
# Ubuntu / Debian / Raspberry Pi (64-bit OS)
sudo apt update
sudo apt install -y openjdk-17-jre-headless

# CentOS / RHEL / Rocky / AlmaLinux
sudo dnf install -y java-17-openjdk-headless
```

Verify: `java -version`

**Step 2 — Dedicated user & directory (recommended)**

```bash
sudo useradd -r -m -d /opt/syna syna          # system user that only runs the server
sudo mkdir -p /opt/syna
sudo cp ~/Downloads/syna-server.jar /opt/syna/      # copy the jar over
sudo chown -R syna:syna /opt/syna
```

**Step 3 — Firewall (when UFW is on)**

```bash
# Ubuntu UFW
sudo ufw allow 45880/tcp
sudo ufw status          # confirm 45880 is ALLOW

# CentOS firewalld
sudo firewall-cmd --permanent --add-port=45880/tcp
sudo firewall-cmd --reload
```

**Step 4 — Register as a systemd service (auto-start + auto-restart)**

Create `/etc/systemd/system/syna-server.service`:

```ini
[Unit]
Description=Syna Private Chat Server
After=network.target

[Service]
Type=simple
User=syna
WorkingDirectory=/opt/syna
ExecStart=/usr/bin/java -jar /opt/syna/syna-server.jar -p 45880 -w YOUR_PASSWORD -g YOUR_GROUP_NAME
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now syna-server          # start now + enable at boot
systemctl status syna-server                     # status (green active = success)
```

**Step 5 — Verify the port is listening**

```bash
ss -tlnp | grep 45880
```

Seeing `LISTEN` means success. Get the LAN IP with `hostname -I` (the first one like `192.168.1.100`).

> Server management: `sudo systemctl restart syna-server` restart / `stop` stop / `journalctl -u syna-server -f` follow the logs.

---

#### Router Port Forwarding (for direct public access)

> If you use frp/ngrok/Tailscale (see [section 5](#5-remote-access-nat-traversal)), **skip this**. Only needed when you want people to connect directly to your public IP.

1. Open your router admin page in a browser (usually `192.168.1.1` or `192.168.0.1`, see the sticker on the router), then log in.
2. Find **Port Forwarding / Virtual Server / NAT settings** (names vary by brand; usually under "Advanced settings").
3. Add a rule:
   - External port: `45880`
   - Internal IP: the server's LAN IP (e.g. `192.168.1.100`)
   - Internal port: `45880`
   - Protocol: `TCP`
   - Save, and reboot the router if asked.
4. Public access: open [ip.sb](https://ip.sb) to find your **public IP**; others join with `publicIP:45880` + the password.

**Two common pitfalls:**
- **Carrier-grade NAT (NAT444)**: if your IP starts with `100.64.x.x` it is not truly public — port forwarding won't work → use frp/ngrok/Tailscale instead.
- **Home public IPs change**: rebooting the router may change it. For a fixed address use DDNS (Oray, Aliyun, no-ip, ...) or simply Tailscale/frp.

#### Verification Checklist

1. **Local test**: Syna app → Contacts → Join Server → `127.0.0.1:45880` → you should join.
2. **LAN test**: phone on the same Wi-Fi → Join Server → the server's LAN IP:45880 → you should join.
3. **Public test (if configured)**: turn off Wi-Fi, use mobile data → Join Server → public IP:45880 → you should join.
4. **Restart test**: reboot the machine → the server comes back automatically (if auto-start is configured) → rejoin from your phone → the history is still there.


## 5. Remote Access (NAT Traversal)


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

**Client side**

In the app: **Contacts → Join Server** → fill in the public `address:port` + password → join. Everything else is automatic.

> **Note:** The Syna server itself is just a plain TCP listener. We deliberately do NOT build tunneling into the app — use whichever tunnel fits your habits.


## 6. Security Model


**LAN peer-to-peer (1:1 and mesh groups)**

- Every device generates an X25519 key pair on first launch (stored locally, never uploaded).
- Session keys are derived per peer (HKDF-SHA256) and messages are encrypted with **AES-256-GCM** — true end-to-end encryption, the server-free network path.
- Public keys are exchanged over the HELLO/KEY handshake frames.

**Private server**

- The server sends a random salt in `SRV_HELLO`; both sides derive an **AES-GCM channel key** from the join password (HKDF-SHA256) — all traffic is authenticated and encrypted, including the password itself.
- Group messages use a **password-derived group key**, so any member who knows the password can decrypt history — this is the "private server trust model" (the server operator is trusted, like a Minecraft server whitelist).
- LAN peer-to-peer chat remains fully E2E.
- On-device: chat history is additionally **encrypted at rest** (AES-GCM), and the ◇Mirtazapine Shield layer protects the running app (see section 2.5).

**Burn after reading**

- The receiver shows the message for 8 seconds, then destroys it locally and sends `BURN_ACK`.
- The sender deletes its copy on ACK (with a 60s fallback timer).
- The private server also purges the message from its persisted history.

**What is NOT covered**

- The server operator can read server-group messages (they know the password). If you need E2E through a server, a full group-key distribution protocol is on the roadmap.
- Metadata (sender IDs, message sizes, timing) is visible to the server operator.


## 7. Troubleshooting


| Problem | Cause & fix |
| --- | --- |
| **Can't find anyone on the LAN** | 1) Both devices on the **same** Wi-Fi/subnet. 2) Some routers block UDP broadcast — the app falls back to multicast, but very restrictive networks block both; try enabling "Host Hotspot" mode instead. 3) Windows firewall / macOS firewall may block the app — allow it. |
| **Windows firewall popup** | Click **Allow** for both private and public networks (needed for the client **and** the server). |
| **Server not reachable from another device** | 1) Check `java -jar syna-server.jar` shows the port listening. 2) `telnet <ip> <port>` from the other device — if it fails, the router's port forwarding / firewall is blocking it. 3) On the server machine, `lsof -iTCP:<port> -sTCP:LISTEN` (macOS/Linux) or `netstat -ano | findstr <port>` (Windows). |
| **Join server says "authentication failed"** | Wrong password, or the server operator changed it. Note: empty password is not accepted. |
| **History not loading** | Server history is capped (default 200 messages) and burn-after-reading messages are intentionally removed. Oldest messages drop off first. |
| **Messages show 🔒 but can't decrypt** | Normally impossible (keys auto-exchange). If seen, delete local app data and re-join — identity keys regenerate. |
| **Android can't discover** | Android 9+ needs location permission **only** for Wi-Fi scan features; make sure you're on the same subnet, and that "private DNS" isn't breaking multicast. On Android 14+ local network permissions are stricter. |
| **Port already in use** | Change the port with `-p`, e.g. `-p 45900`. |
| **Multiple instances on one machine share identity** | Desktop settings are stored in user-level Java preferences; different OS accounts get different identities. This is by design. |


## 8. Build & Distribute


```bash
# Desktop app (dev)
./gradlew :composeApp:run

# Server fat jar (all platforms)
./gradlew :composeApp:serverFatJar
# → composeApp/build/server/syna-server.jar

# Android APK
./gradlew :composeApp:assembleDebug            # debug
./gradlew :composeApp:assembleRelease          # release (needs keystore in local.properties)

# Tests (34)
./gradlew :composeApp:desktopTest
```

**Desktop native packages**

```bash
./gradlew :composeApp:packageDmg   # macOS installer (dmg)
./gradlew :composeApp:packageMsi   # Windows installer (needs Windows + WiX)
./gradlew :composeApp:packageDeb   # Linux Deb
./gradlew :composeApp:packageRpm   # Linux Rpm
```

**Server as a service (Linux, systemd example)**

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


## More Resources
- README: architecture, protocol, platform notes
- GitHub Releases: prebuilt APK, macOS DMG, server jar
- Issues: bug reports & feature requests welcome
