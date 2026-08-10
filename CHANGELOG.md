# Changelog

All notable changes to **Syna** — LAN instant messenger (GPL-3.0).

Format: version — date — summary. Releases: [GitHub Releases](https://github.com/Verlintas/Syna-NUSV/releases).

---

## [0.9.1] — 2026-08-10 — Stabilization (full audit pass)

### Fixes (from full code audit — see SECURITY_AUDIT_REPORT.md)
- **2FA lockout eliminated** — metadata (TOTP seed / key pins / audit / fail-counter) now uses the master Keystore key; previously session-key encryption made them undecryptable while locked
- Honeypot decoy no longer overwrites real history; burn-message 60 s TTL fallback actually works; server-history replay no longer dropped
- Mesh group file transfer fixed; ACK retransmission fixed (counter reset bug → infinite retransmission; TEXT now ACK/retried)
- UDP: key exchange uses real UDP port; 40 KB chunks in UDP mode
- History-wipe race (debounced rewrite vs memory release) closed; burn-purge persists
- No-biometrics devices no longer count taps as brute-force; interrupted disable flow can't turn Shield off
- WATCHDOG_TRIP auto-clears on heartbeat recovery; self-destruct order fixed; screen-capture unregister fixed
- Group-admin privilege escalation closed (creator protected)
- Server: zombie-session timeout, kick-all-sessions, spoofed-identity rejection, BURN_ACK/RECALL ownership, locked history snapshot, immediate burn sweep
- Burn-send verification failure keeps input; Shield-disabled devices send normally

### Added
- New project logo (Android launcher all densities + desktop tray)
- `SECURITY_AUDIT_REPORT.md` (code review + license audit)

### License audit
- CLEAN: 100% Apache-2.0 deps (GPL-3.0 compatible); no copyleft deps; all in-tree code original; SPDX headers complete

## [0.9.0] — 2026-08-10 — Encrypted file transfer, voice messages, re-auth

### Added
- **File transfer E2E encryption** (1:1 FILE_CHUNK payloads encrypted; group files documented)
- **Voice messages**: long-press 🎤 record (30 s), encrypted file channel, ▶️ playback; Android AMR-NB / desktop WAV; one-time recording permission
- **Sensitive-operation re-auth**: burn-after-reading sends require biometric confirmation
- **Clipboard short TTL**: copy action with 30 s auto-clear
- **Server**: slow-client isolation (bounded per-session send queue), burn-history 1 h TTL

### Fixed
- (see 0.9.1 audit for the deep pass)

## [0.8.2] — 2026-08-10 — ACK/retransmission, group administration, no-export policy

### Added
- **Message-level ACK & retransmission** (P2P 1:1; 3 s retry ×3 → offline queue)
- **Group administration**: kick / mute (1 h) / set-admin; receiver-side permission checks
- **No-export policy**: `android:allowBackup=false`; no backup/export features by design
- Server kick identity fixed (serverId vs groupId — kick notifications were dropped)

## [0.8.1] — 2026-08-10 — Key pinning (TOFU), encrypt-only mode, replay defense

### Added
- **TOFU key pinning**: public keys pinned on first use; fingerprint badge + full fingerprint; key changes rejected → `KEY_CHANGED` lock; re-trust button
- **Encrypt-only session mode** (refuse plaintext fallback)
- **Replay defense**: 10-minute window on real-time frames

## [0.8.0] — 2026-08-10 — Deep self-destruct (anti-forensics)

### Added
- `SecureWipe`: 2-pass random overwrite + fsync on all sensitive files (chat history, received files, audit, TOTP seed, session blob, baselines, crash log)
- **Keystore/TEE storage-key destruction** — recovered ciphertext permanently undecryptable
- Audit self-wipe order; desktop key-file overwrite

## [0.7.9] — 2026-08-10 — Active crash defense & permission self-check

### Added
- Native `SIGABRT` on high-confidence signals (ptrace attach / code or libc hooked) — no stable debugging window
- Usage-access grant targets Syna (`EXTRA_APP_PACKAGE`); full permission self-check on every launch

## [0.7.8] — 2026-08-10 — Expanded detection & active countermeasures

### Added
- Riru/EdXposed/TaiChi + SELinux domain in root detection; IME / USB / suspicious-module advisories
- Decrypt-path integrity probing; watchdog self-healing; honeypot data pollution (decoy messages)

## [0.7.7] — 2026-08-10 — All client bugs fixed

### Fixed
- TCP failure → offline queue; bidirectional heartbeat (PONG); burn TTL fallback; server-group disconnect awareness; discovery resilience; UI fixes (quote bar, image decode, EDT dialogs); CA user-cert-only; mirroring both directions; exact process matching

## [0.7.6] — 2026-08-10 — Review second pass

### Fixed
- Shield lifecycle on dispose; desktop key quarantine; captureAuth TOCTOU; native maps buffers; outbox mutex; receipt/key-frame guards; inbound TCP read timeout

## [0.7.5] — 2026-08-10 — Full security review fix release

### Fixed
- 2FA bypass closed; session-key capture after TOTP; biometric fail double-count; server relay forgery; BURN_ACK/RECALL sender validation; FILE_CHUNK bounds + pipeline isolation; TOTP-enable failure guard; DEBUG_MODE downgraded; honeypot streak; watchdog one-shot trip
- **Chat persistence wired** (never was); atomic writes; CAS StateFlow updates; UDP buffer 64 KB; server hardening; macOS close-to-tray; tray icon; TOTP code screen visible; version alignment

## [0.7.4] — 2026-08-10 — Rotation, unkillable shield, injection traces

### Added
- Session-key rotation per unlock (forward secrecy); dual-factor disable (biometrics + TOTP); anonymous rwx segment detection (bit2)

## [0.7.3] — 2026-08-10 — Native anti-hook

### Added
- syscall-direct I/O (GOT/PLT/LD_PRELOAD dead); own-code-segment memory-vs-disk hashing; export-entry self-verification; libc entry verification; integrity bitmask

## [0.7.2] — 2026-08-10 — Native anti-debug (NDK)

### Added
- TracerPid/maps/threads read in C (4 ABIs); JVM + native dual-channel; graceful fallback

## [0.7.1] — 2026-08-10 — Data-level key gate

### Added
- Session-key layer wrapped by biometric-authenticated Keystore key; no auth → new data unreadable; lock invalidates session; master-key fallback for history

## [0.7.0] — 2026-08-10 — TOTP two-factor unlock

### Added
- RFC 6238 dual verification (biometric + 6-digit code); `otpauth://` seed import; wrong codes feed brute-force pipeline

## [0.6.9] — 2026-08-10 — Picker crash fix

### Fixed
- Gallery/file picker crash on all real devices (androidx.activity requestCode ≥ 65536 vs platform 16-bit limit) — fixed requestCode `startActivityForResult`

## [0.6.8] — 2026-08-10 — Network & screen attack surface

### Added
- Capture/recording events (API 34); mirroring change detection; CA-cert & ARP-spoof detection; SSID fingerprint; Zygisk/Shamiko/LSPosed; SELinux; scan jitter; background memory wipe; unlock cooldown; downgrade defense

## [0.6.7] — 2026-08-10 — Open-source-proof hardening

### Added
- Heartbeat gate (fail-closed); watchdog ring; honeypot fake-lock; brute-force protection; dex self-verification; key release on lock; live status panel

## [0.6.6] — 2026-08-10 — Shield upgrade

### Added
- Clock-tamper & weak-lock advisories; Frida port 27043; emulator test-keys; audit encryption

## [0.6.5] — 2026-08-10 — Single-switch Shield

### Added
- One master switch (everything on); usage-access guidance; foreground-app sensing; split scan cadence

## [0.6.4] — 2026-08-10 — Lock screen redesign

## [0.6.3] — 2026-08-10 — Audit encryption at rest

## [0.6.2] — 2026-08-10 — Hash-chained audit persistence

## [0.6.1] — 2026-08-10 — In-memory state HMAC

## [0.6.0] — 2026-08-10 — Desktop engine

## [0.5.0] — 2026-08-10 — Detection expansion

## [0.4.0] — 2026-08-10 — ◇Mirtazapine Shield first release
