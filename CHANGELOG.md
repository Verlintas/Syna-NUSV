# Changelog

All notable changes to **Syna** — LAN instant messenger (Kotlin Multiplatform + Compose
Multiplatform, GPL-3.0). Android · Windows · macOS, fully peer-to-peer, no internet
required.

Format: `version — date — summary`. Releases:
[GitHub Releases](https://github.com/Verlintas/Syna-NUSV/releases)

---

## [0.9.6] — 2026-08-10 — Shield self-protection hardening

### Added
- **Native heartbeat slot** (JVM-hook immune): decrypt path verifies both the JVM and
  native heartbeat; hooking JVM `beat()` can't fake freshness
- **Scanner self-healing**: a scan-round exception no longer kills the detection loop
- **Audit-write failure detection**: 5 consecutive failed audit writes →
  SHIELD_TAMPERED (storage made read-only/filled)
- **Reinstall guard**: device-identity change checked independently of Shield state;
  reinstall with Shield off → notify to re-enable

### Tests
- 83

## [0.9.5] — 2026-08-10 — Image preview, message resend, power-saving discovery, Shield additions

### UX
- Full-screen image preview (tap bubble → fullscreen → tap to close)
- Resend failed messages (long-press → 重发; burn re-send requires biometric)
- Voice playback state on the bubble (播放中 / tap to stop)

### Power saving
- Discovery broadcast adapts: 10 fast announcements → 3× slower; incoming/manual refresh resets

### Shield
- DEVICE_CHANGED (LOW): ANDROID_ID vs encrypted baseline (reinstall / backup-restore)
- Audit-integrity check: audit log deleted/emptied → SHIELD_TAMPERED (审计日志被清除)
- Decrypt-path probe every 6th decrypt; dex hashing finally moved to the heavy scan

### Tests
- 83

## [0.9.4] — 2026-08-10 — Material Icons, Voice Duration, UI Polish

### Changed
- Material icons for send / refresh / play / lock markers (semantic badges stay text: 焚/群主/管理员/禁言/指纹/录音/图片/文件)
- Voice duration transmitted (FileChunk.durationMs) and shown on the bubble (语音 12秒); filename carries seconds for cross-version display
- Fixed duplicate "刷新 刷新" label

### Tests
- 82

## [0.9.3] — 2026-08-10 — Lock-bypass fix, 2FA enable fix, no-emoji UI, proxy detection

### Security fixes
- **Lock bypass closed**: clearing a threat while LOCKED no longer auto-returns to
  ARMED (previously the app silently unlocked without biometrics when the locking
  threat disappeared); locked state persists until real verification
- 2FA enable: stale/corrupt seed files cleared before re-enable; failure now shows a
  notice instead of a dead switch
- No-biometrics devices: unlock taps show a clear notice, never count toward
  self-destruct

### Changed
- All emoji removed from the UI (text replacements: 焚/图片/文件/锁/群主/管理员/禁言/播放/录音)
- Detection: system HTTP proxy (PROXY_SET, LOW advisory) with proxy address detail

### Tests
- 82

## [0.9.2] — 2026-08-10 — Group-file encryption, stealth mode, module details

### Added
- **Group file transfer E2E-encrypted per member** (one ciphertext copy per member,
  mesh + server groups; receivers only process decryptable copies) — last plaintext
  transfer path closed
- **Stealth mode** (Settings): stop broadcasting presence (still discover others,
  manual refresh still finds you)
- **Group member key fingerprints** shown in the administration dialog
- Suspicious-module detection now reports the **actual module paths** on the lock
  screen and in the audit log

### Fixed
- Suspicious-module whitelist switched to **partition prefixes** (`/system` `/apex`
  `/vendor` `/product` `/system_ext` `/odm` `/data/app` `/data/user`) — vendor ROM
  libraries no longer false-trigger "可疑可执行模块" on first enable
- **Mesh-group file sends silently failed** (misdetected as server-group disconnect) —
  real fix + regression test

### Tests
- 81 (new: partition whitelist, anonymous-module detection, encrypted group-file
  round-trip, stealth announce/stop)

## [0.9.1] — 2026-08-10 — Stabilization (full audit pass)

### Fixes (full code audit — see SECURITY_AUDIT_REPORT.md)
- **2FA lockout eliminated** — TOTP seed / key pins / audit / fail-counter now use the
  master Keystore key (previously session-key encryption made them undecryptable while
  locked → permanent lockout)
- Honeypot decoy no longer overwrites real history; burn 60 s TTL fallback actually
  works (marker was never written); server-history replay no longer dropped
- Mesh group file transfer fixed (misdetected as server-group disconnect); ACK
  retransmission fixed (counter reset → infinite retransmission; TEXT now ACK/retried)
- UDP: key exchange uses the real UDP port; 40 KB chunks in UDP mode
- History-wipe race closed (debounced rewrite vs memory release); burn-purge persists
- No-biometrics devices no longer count taps as brute-force; interrupted disable flow
  can't turn the Shield off; WATCHDOG_TRIP auto-clears on heartbeat recovery
- Self-destruct order fixed (audit wiped after event, Shield disabled, key pins & voice
  cache wiped); screen-capture unregister fixed
- Group-admin privilege escalation closed (creator protected)
- Server: zombie-session timeout, kick-all-sessions, server-identity spoofing rejected,
  BURN_ACK/RECALL ownership checks, locked history snapshot, immediate burn sweep
- Burn-send verification failure keeps input; Shield-disabled devices send normally

### Added
- New project logo `Syna_logo_2.png` (Android launcher all densities + desktop tray)
- `SECURITY_AUDIT_REPORT.md` (code review + license audit)
- License audit: CLEAN — 100% Apache-2.0 deps, no copyleft, all in-tree code original

## [0.9.0] — 2026-08-10 — Encrypted file transfer, voice messages, re-auth

### Added
- **File transfer E2E encryption** (1:1 FILE_CHUNK payloads encrypted; group files documented)
- **Voice messages**: long-press 🎤 record (30 s max), encrypted file channel, ▶️ playback; Android AMR-NB / desktop WAV; one-time recording permission
- **Sensitive-operation re-auth**: burn-after-reading sends require biometric confirmation
- **Clipboard short TTL**: copy action with 30 s auto-clear
- **Server**: slow-client isolation (bounded per-session send queue), burn-history 1 h TTL

## [0.8.2] — 2026-08-10 — ACK/retransmission, group administration, no-export policy

### Added
- **Message-level ACK & retransmission** (P2P 1:1; 3 s retry ×3 → offline queue)
- **Group administration**: kick / mute (1 h, toggleable) / set-admin; receiver-side
  permission checks (forged frames ignored); 👑 ⭐ 🔇 badges
- **No-export policy**: `android:allowBackup=false`; no backup/export features by design
- Server kick identity fixed (serverId vs groupId — kick notifications were dropped)

## [0.8.1] — 2026-08-10 — Key pinning (TOFU), encrypt-only mode, replay defense

### Added
- **TOFU key pinning**: public keys pinned on first use (encrypted at rest); fingerprint
  badge in chat header + full fingerprint for out-of-band verification; key changes
  rejected → `KEY_CHANGED` lock; "信任此密钥" re-pin
- **Encrypt-only session mode** (refuse plaintext fallback)
- **Replay defense**: 10-minute window on real-time frames

## [0.8.0] — 2026-08-10 — Deep self-destruct (anti-forensics)

### Added
- `SecureWipe`: 2-pass random overwrite + fsync on all sensitive files (chat history
  incl. `.tmp`, received files, audit log, TOTP seed, session blob, baselines, crash log)
- **Keystore/TEE storage-key destruction** — recovered ciphertext permanently
  undecryptable; desktop key file overwritten
- Audit self-wipe order (event written, then audit file wiped)

## [0.7.9] — 2026-08-10 — Active crash defense & permission self-check

### Added
- Native `SIGABRT` on high-confidence signals (ptrace attach / code or libc hooked) —
  no stable debugging window; audit written best-effort before crash
- Usage-access grant targets Syna (`EXTRA_APP_PACKAGE`); full permission self-check on
  every launch

## [0.7.8] — 2026-08-10 — Expanded detection & active countermeasures

### Added
- Riru / EdXposed / TaiChi + SELinux process-domain in root detection
- IME change / USB attach-detach / suspicious executable module advisories (all LOW)
- **Decrypt-path integrity probing** (every ~8th decrypt); **watchdog self-healing**
  (scanner restart); **honeypot data pollution** (decoy messages)

## [0.7.7] — 2026-08-10 — All client bugs fixed

### Fixed
- TCP failure → offline queue (no silent UDP fallback loss); bidirectional heartbeat
  (PONG reply + 3-cycle timeout); burn-message TTL fallback with BURN_ACK
- Server-group disconnect awareness (FAILED status + notification); READ receipts via
  server channel; discovery resilience; announcement buffering
- Quote-bar rendering; image decode off main thread; EDT file dialogs; username-change
  discovery restart; duplicate-name file suffixes; CA user-cert-only; mirroring both
  directions; exact process matching; watchdog alive semantics; AWT listener cleanup

## [0.7.6] — 2026-08-10 — Review second pass

### Fixed
- Shield lifecycle on dispose; desktop key quarantine (`.corrupt`); captureAuth TOCTOU;
  native maps buffers 64K/128K; outbox flush mutex; receipt/key-frame send guards;
  inbound TCP read timeout (90 s); sendText empty guard

## [0.7.5] — 2026-08-10 — Full security review fix release

### Fixed
- 2FA bypass closed (no unlock while awaiting code; clearThreat can't skip it);
  session-key capture moved after TOTP verification; biometric fail double-count;
  server relay `from` forgery; BURN_ACK/RECALL sender validation; FILE_CHUNK bounds +
  pipeline isolation; TOTP-enable failure guard; DEBUG_MODE downgraded to HIGH;
  honeypot streak no longer reset; watchdog one-shot trip + restart reset
- **Chat persistence wired** (was never injected — chats never survived restarts);
  unlock-time rotation order fixed; atomic writes (tmp+rename); CAS StateFlow updates;
  UDP receive buffer 64 KB; server hardening; macOS close-to-tray fix; tray icon;
  AWAITING_TOTP code screen visible; version alignment

## [0.7.4] — 2026-08-10 — Rotation, unkillable shield, injection traces

### Added
- Session-key rotation per unlock (forward secrecy); dual-factor disable (biometrics +
  TOTP — attacker can't turn the shield off); anonymous rwx segment detection (bit2);
  AWAITING_TOTP lock-screen rendering fix

## [0.7.3] — 2026-08-10 — Native anti-hook

### Added
- syscall-direct I/O (GOT/PLT/LD_PRELOAD dead); own-code-segment memory-vs-disk hashing
  (inline-hook detection); export-entry self-verification; libc entry verification;
  integrity bitmask → SHIELD_TAMPERED / FRIDA_DETECTED

## [0.7.2] — 2026-08-10 — Native anti-debug (NDK)

### Added
- `libsyna_shield.so` (C, GPL-3.0, 4 ABIs): TracerPid/maps/threads read in C;
  JVM + native dual-channel verification; graceful fallback

## [0.7.1] — 2026-08-10 — Data-level key gate

### Added
- Session-key layer wrapped by a biometric-authenticated Keystore key (300 s window) —
  no auth event → newly written data unreadable; lock invalidates session; master-key
  fallback for history (smooth upgrade)

## [0.7.0] — 2026-08-10 — TOTP two-factor unlock

### Added
- RFC 6238 dual verification (biometric + 6-digit code); `otpauth://` seed import;
  wrong codes feed the brute-force pipeline (cooldown + self-destruct)

## [0.6.9] — 2026-08-10 — Picker crash fix

### Fixed
- Gallery/file picker crash on all real devices (androidx.activity requestCode ≥ 65536
  vs platform 16-bit limit) — fixed requestCode `startActivityForResult` path

## [0.6.8] — 2026-08-09 — Network & screen attack surface

### Added
- Screen capture/recording events (API 34); mirroring change detection; CA-cert & ARP
  spoofing detection; SSID fingerprint; Zygisk/Shamiko/LSPosed; SELinux; scan jitter;
  background memory wipe (60 s); unlock cooldown backoff; downgrade defense

## [0.6.7] — 2026-08-09 — Open-source-proof hardening

### Added
- Heartbeat gate (fail-closed decrypt); watchdog ring (3 threads); honeypot fake-lock;
  brute-force protection; dex self-verification; key release on lock; live status panel

## [0.6.6] — 2026-08-09 — Shield upgrade

### Added
- Single master switch (one tap enables everything); clock-tamper & weak-lock
  advisories; Frida port 27043; emulator test-keys; audit encryption at rest

## [0.6.5] — 2026-08-08 — Shield single-switch & chat polish

### Added / Fixed
- Quote-preview rework (visible on phone, no overlap on desktop); phone gallery sending
  (permission-free); Shield single switch + ◇ title; usage-access guidance
  (foreground-app sensing); scan cadence split (light 3 s / heavy 15 s)

## [0.6.4] — 2026-08-08 — Lock screen redesign

### Fixed
- File-picker click crash defense; crash log dual-write (Download/Syna); lock screen
  redesign (pure black / red ◇ / white)

## [0.6.3] — 2026-08-08 — Debug-build compatibility

### Fixed
- Debug builds skip signature verification; global crash log for startup debugging

## [0.6.2] — 2026-08-08 — Compatibility fix

### Fixed
- Android API 30 credential-detector guard (crash prevention on older devices), no
  feature loss

## [0.6.1] — 2026-08-08 — Shield hardening (P0/P1)

### Fixed
- REQ_KEY response no longer throttled (UDP key self-heal restored); audit hash-chain
  parse fix; decrypt-failure frames no longer stored; disable cancels auto-relock;
  file-assembler sweep (10 min); Android biometric unlock fixed (FragmentActivity);
  audit persistence synchronized — 6 bugs, 4 regression tests

## [0.6.0] — 2026-08-08 — Version unification

## [0.5.0] — 2026-08-08 — Shield first release & version unification

### Added (since 0.4.0)
- **◇Mirtazapine Shield** (first release): ARMED/LOCKED/UNLOCKED state machine,
  biometric unlock; Android engine — Root / emulator / USB-debug / VPN change /
  background switch / accessibility abuse / monitoring apps; screen-capture protection
  (FLAG_SECURE); desktop engine — idle auto-lock (10 min); full-screen lock page;
  capability-boundary statement
- Shield hardening: HMAC-signed settings (tamper → force-restore); disable requires
  biometrics; 5-min unlock expiry; back-key blocking; threat severity grading;
  self-destruct protocol; chat-storage AES-GCM encryption (Keystore TEE / 0600 key);
  memory wipe while locked; Frida detection; audit persistence; clipboard protection
- Server group E2E (per-member X25519 ciphertext, server sees only ciphertext)
- Message search + forward + date dividers + unread badge + tray resident
  *(search removed in 0.4.0)*

## [0.4.0] — 2026-08-08 — Search removed, version unification

### Changed
- Message search removed (as requested); version strings unified to 0.4.0

## [0.3.1] — 2026-08-08 — Server launcher app

### Added
- Standalone launcher app via jpackage (macOS .app / Windows / Linux app-image),
  Gradle task `launcherAppImage`

## [0.3.0] — 2026-08-07 — Version unification & tutorials

### Added
- Tutorial split into pure-English (TUTORIAL_EN.md) and pure-Chinese (TUTORIAL_ZH.md)
  versions with entry page; server version unified; server dual-mode (CLI headless +
  `--ui` GUI with live status / members / history / logs)

## [0.2.0] — 2026-08-06 — Private server

### Added
- **Syna Server** headless server (Win/macOS/Linux fat jar): TCP listening, password
  auth, group relay, history persistence (`history.jsonl`), burn-after-reading server
  purge, member online/offline broadcast
- Client join (IP:port + password, group-key encryption, history replay, disconnect
  detection)
- NAT traversal via frp / ngrok / Tailscale port mapping
- Fixed serverId init-order NPE (identity lost on restart)

## [0.1.0] — 2026-08-06 — Initial release

### Added
- Kotlin Multiplatform + Compose Multiplatform project (Android / Windows / macOS)
- **LAN peer discovery**: UDP broadcast + multicast dual-channel, 3 s heartbeat,
  15 s offline timeout, manual refresh
- **E2E encryption**: X25519 key exchange + HKDF-SHA256 + AES-256-GCM; per-peer
  session keys; REQ_KEY self-heal
- **1:1 chat**: conversation list (unread badges / timestamps / previews), bubbles,
  ✓✓ read receipts, connection status
- **P2P mesh group chat**: create → invite → membership sync (JOIN/LEAVE), owner
  dissolve, member leave
- **Burn after reading**: 8 s display, destroyed on both sides (BURN_ACK + 60 s
  fallback)
- **Temporary chat**: auto-purge after TTL (1 h / 24 h / 7 d)
- Connection modes: Auto / TCP / UDP / Host Hotspot; custom username; offline message
  queue; typing indicator; message recall (2 min); quote reply & @mentions; image/file
  transfer (64 KB chunks, progress, ≤4 MB image preview); system notifications;
  dark/light theme; contact management (block / unblock); chat history persistence
  (JSONL, encrypted later); clear local history; proxy-TUN loopback normalization;
  Windows/macOS/Linux support

---

## License

Syna is licensed under **GPL-3.0-only**. All dependencies are Apache-2.0 (compatible);
no copyleft dependencies; all in-tree code is original. See `LICENSE` and
`SECURITY_AUDIT_REPORT.md`.
