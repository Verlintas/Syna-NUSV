# ◇ Mirtazapine Shield

**Real-time security monitor & application lock for Syna.**

The Shield is the security core of Syna: it continuously watches the device for compromise
indicators, locks the app behind a full-screen gate when a threat is detected, refuses to
decrypt anything when the process cannot prove it is healthy, and — at the highest
severity — destroys local data rather than letting it fall into wrong hands.

This document is an engineering specification: every detector, every response, every
mechanism below is implemented, tested, and fully open source. Nothing in this document
is security-by-obscurity; the protection survives the fact that **all source code is
public (GPL-3.0)**.

---

## Table of contents

1. [Design philosophy](#1-design-philosophy-security-under-full-disclosure)
2. [Threat model & honest boundary](#2-threat-model--honest-boundary)
3. [Detection matrix (every detector in detail)](#3-detection-matrix)
4. [Meta-detection: heartbeat gate & watchdog ring](#4-meta-detection-heartbeat-gate--watchdog-ring)
5. [Network environment detection (LAN MITM)](#5-network-environment-detection-lan-mitm)
6. [Screen attack surface](#6-screen-attack-surface)
7. [Response chain](#7-response-chain)
8. [The unlock pipeline (biometric → TOTP → session key)](#8-the-unlock-pipeline)
9. [Data protection: key hierarchy & formats](#9-data-protection-key-hierarchy--formats)
10. [State machine reference](#10-state-machine-reference)
11. [Attack scenario walkthroughs](#11-attack-scenario-walkthroughs)
12. [Platform comparison (Android vs desktop)](#12-platform-comparison-android-vs-desktop)
13. [False-positive control & known trade-offs](#13-false-positive-control--known-trade-offs)
14. [Version history](#14-version-history)
15. [Verification & testing](#15-verification--testing)
16. [Honest boundary (again, plainly)](#16-honest-boundary-again-plainly)

---

## 1. Design philosophy: security under full disclosure

Every line of Shield code is public. An attacker can read the detectors, the thresholds,
the key locations, and the lock logic. Classic "security by obscurity" is therefore
**explicitly rejected**. Instead, the Shield relies on five properties that survive
source disclosure:

| Property | Mechanism | Why it survives disclosure |
|---|---|---|
| **Fail-closed gate** | Every decrypt path checks a heartbeat gate first. If the detector loop is paused, killed, or hooked, decryption is *refused* — equivalent to a lock even with the UI bypassed. | The attacker must make the gate appear fresh. Faking the gate requires injecting code into the process — which is itself what the detectors watch for. |
| **Redundant orthogonal detection** | The same attack is observed from multiple independent channels (files, ports, procfs, maps, threads, system callbacks, integrity hashes, watchdog timers, native C code). | Bypassing one channel is not enough; every channel must be bypassed, and each bypass is a detectable modification of the process. |
| **Meta-detection (watchdogs)** | 3 daemon threads monitor each other in a ring with randomized cadence; the engine heartbeat is HMAC-signed. | Killing any thread stalls its slot → neighbor watchdog trips. Killing everything stalls the heartbeat → the gate fails closed. |
| **Key material outside the process** | Android keys live in the Keystore / TEE (never exported to JVM memory); the session capability is released on lock and re-derived on unlock; session keys are wrapped by biometric-authenticated Keystore keys. | Source code contains no keys and no key derivation that can be replayed without the TEE or without an authentication event. |
| **Honesty** | What cannot be detected at app layer is stated plainly, in-app and here. | No false promise, no false sense of safety. |

**Kerckhoffs applied:** the algorithm, the layout, the thresholds — all public. What an
attacker still must do: (a) modify the running process without being observed
(hard — dual-channel + watchdog + gate), or (b) extract data without authentication
(hard — TEE-bound master key + biometric-authenticated session key + fail-closed gate).

The realistic attacker model for a LAN messenger: a compromised device, a device with
root, an injected process, a repackaged APK, a network MITM, or a physically stolen
device. The Shield addresses each one — see [Attack scenario walkthroughs](#11-attack-scenario-walkthroughs).

---

## 2. Threat model & honest boundary

**Detectable at app layer (detected by the Shield):**

- Root / Magisk (incl. hidden-mount variants) / Xposed / Zygisk / Shamiko / LSPosed
- Frida injection (server paths, ports 27042 + 27043, `/proc/self/maps` library maps,
  thread names, `TracerPid` ptrace) — via **JVM and native (NDK) channels**
- Emulator environments (fingerprint, model, brand, test-keys build tags, goldfish /
  ranchu / vbox86 hardware)
- USB debugging / ADB, attached debuggers
- Repackaging (APK signature fingerprint) and runtime tampering (dex hash baseline)
- Version downgrade attempts (versionCode rollback)
- SELinux non-enforcing mode
- Clock tampering (wall clock vs. uptime divergence)
- Missing lock screen (weak-lock advisory)
- Credential change (biometrics removed — device possibly re-owned)
- Device-admin takeover (MDM activation)
- VPN / proxy change
- User CA certificate addition (MITM certificate precondition)
- ARP spoofing (default-gateway MAC change)
- Wi-Fi network change (SSID fingerprint)
- Accessibility service abuse and service-list changes
- Known monitoring / remote-control / screen-recording apps (installed **and** in
  foreground via usage access)
- Rapid background switching (screen-share / monitoring behavior)
- Screen mirroring (presentation display appears)
- Screen capture / recording events (Android 14+, system callback)
- Desktop: idle lock, display-count change, JVM `-javaagent` injection, remote-control
  processes

**Not detectable at app layer (stated honestly, in-app and here):**

- System-level pre-installed monitoring (carrier / OEM spyware with system privileges)
- Enterprise MDM with device-owner privileges
- Root-level kernel monitoring that hooks below the Java runtime

For those scenarios the only app-layer answers are the **self-destruct protocol**, the
**data-level key gate** and the **fail-closed gate**: if the process cannot prove it is
healthy, data access stops; if the device is in hostile hands, data written after the
last authentication is unreadable.

---

## 3. Detection matrix

Scan cadence (Android): **light scan every 3 s (+0–2 s random jitter)** — signature,
root, frida, emulator, debug, SELinux, mirroring, downgrade, credential, foreground
app, network fingerprint, clock, weak-lock, IME, USB, suspicious modules; **heavy scan
every 15 s** (every 5th tick) — monitoring apps, accessibility, device-admin, CA store,
ARP, **dex integrity** (full APK hashing moved off the light loop to keep the heartbeat
budget). The jitter prevents an attacker from predicting exactly when the next scan
happens.

### 3.1 Static & environment (files, packages, system state)

| Detector | Signals checked | Platform |
|---|---|---|
| `isRooted` | `su` binaries (`/system/bin/su`, `/system/xbin/su`, `/sbin/su`), `Superuser.apk`, init.d script; Magisk paths (`/sbin/.magisk`, `/data/adb/magisk`, `/data/adb/.magisk`, magisk logs) & packages (`com.topjohnwu.magisk`, `com.magisk`); Xposed (`/system/framework/xposed.jar`, `libxposed_art.so`, installer package); Zygisk (`/data/adb/zygisk`, `/data/adb/modules/zygisk`); Shamiko (`/data/adb/modules/shamiko`, `/data/adb/shamiko`); LSPosed (`/data/adb/lspd`, `/data/adb/modules/lsposed`, `org.lsposed.manager`); **Riru / EdXposed / TaiChi** data dirs; **SELinux process-context** (magisk/zygisk domains); `su` in `$PATH` | Android |
| `hasFrida` | `/data/local/tmp/frida-server`, `/data/local/tmp/frida`, `/data/local/tmp/re.frida.server`; TCP connect to 127.0.0.1:27042 and 27043 (300 ms timeout each) | Android |
| `isEmulator` | `Build.FINGERPRINT` containing `generic`; `MODEL` `google_sdk`/`Emulator`/`Android SDK built for`; `BRAND` `generic`; `Build.TAGS` containing `test-keys`; `Build.HARDWARE` `goldfish`/`ranchu`/`vbox86` | Android |
| `isDebugMode` | `Settings.Global.ADB_ENABLED` == 1; `Debug.isDebuggerConnected()` | Android |
| `isSelinuxPermissive` | `/sys/fs/selinux/enforce` == "0" (advisory) | Android |
| `checkWeakLock` | `KeyguardManager.isKeyguardSecure` == false (advisory) | Android |
| `hasMonitoringApps` | Case-insensitive package fragments (incl. screen-recorder families, spyware, family-lock tools) **+ signature-learning blacklist**: first seen monitoring app's cert hash is recorded; any later package carrying that signature (renamed / repackaged) is detected (v0.9.8) | Android |
| `hasAbusiveAccessibility` | `ENABLED_ACCESSIBILITY_SERVICES` containing `com.teamviewer` / `com.anydesk` / `screenrecord` | Android |
| `remoteControlProcesses` | `ps -e -o comm=` (or `tasklist` on Windows) scanned for teamviewer / anydesk / obs / vnc / scrcpy / rustdesk / todesk / sunloginclient / 向日葵 | Desktop |
| `checkImeChange` | `Settings.Secure.DEFAULT_INPUT_METHOD` changes (keylogging-IME swap) | Android, LOW advisory |
| `checkUsbChange` | `UsbManager.deviceList` attach/detach (debug/data-extraction window) | Android, LOW advisory |
| `suspiciousModules` | Multi-dimensional executable-mapping check in `/proc/self/maps`: regular file paths outside the trusted **partition prefixes** (`/system` `/apex` `/vendor` `/product` `/system_ext` `/odm` `/data/app` `/data/user`); **non-JIT `memfd:` mappings** (Frida-style injection, name-independent — ART JIT `memfd:jit-*` is whitelisted since v0.9.8); reports actual module paths on the lock screen/audit | Android, LOW advisory |

### 3.2 Runtime process inspection

| Detector | Signals checked | Channel |
|---|---|---|
| `TracerPid` | `/proc/self/status` `TracerPid:` ≠ 0 — kernel-maintained ptrace flag; catches Frida / gdb / strace / any ptrace attach | **native + JVM dual** |
| Frida / gum maps | `/proc/self/maps` containing `frida-gadget`, `frida-agent`, `gum-js`, `libgadget` | **native + JVM dual** |
| Frida thread names | `/proc/self/task/*/comm` containing `frida` / `gum-js` | **native + JVM dual** |
| `hasJavaAgent` | `ManagementFactory.getRuntimeMXBean().inputArguments` with `-javaagent` / `-agentlib` / `-agentpath` → reported as Frida-class injection | Desktop |

### 3.3 Integrity (anti-repackaging / anti-tamper)

| Detector | Mechanism | Response |
|---|---|---|
| `verifySignature` | SHA-256 of the APK signing certificate compared against the release fingerprint compiled into `BuildConfig` (`745317298590e69ddd48c94902c24209918fe1c19e104bb3ce1ca05263c2c4d7`). Debug builds skip. A repackaged/re-signed APK can never match. | CRITICAL `SHIELD_TAMPERED` → honeypot lock |
| `verifyDexIntegrity` | SHA-256 of every dex (incl. split APKs) combined with `versionCode`, compared against an **encrypted baseline** stored at first run (`syna_dex_base`). Normal upgrades (versionCode changed) re-baseline; any difference at an unchanged versionCode = tampering, even if the signature check is hooked. | CRITICAL `SHIELD_TAMPERED` |
| `checkDowngradeAttempt` | `versionCode` compared against an encrypted baseline (`syna_version_base`); rollback → CRITICAL `DOWNGRADE_ATTEMPT` (downgrade is a common way to re-enable a patched build). Normal upgrades update the baseline. | CRITICAL `DOWNGRADE_ATTEMPT` |
| `ShieldConfigGuard` | Settings file carries an HMAC signature; tamper or wipe → force-restore of safe defaults + `SHIELD_TAMPERED` | CRITICAL + forced restore |

### 3.4 Native anti-debug & anti-hook layer (NDK, v0.7.2 → v0.7.3)

`libsyna_shield.so` (C, GPL-3.0, shipped for arm64-v8a / armeabi-v7a / x86_64 / x86)
reads process signals **in C** and actively fights hooking of the detectors themselves.

#### Detection channels

| C function | What it reads |
|---|---|
| `tracerPid()` | `/proc/self/status` `TracerPid` (kernel-maintained, reliable — unlike self-ptrace probes which false-positive under SELinux) |
| `fridaMaps()` | `/proc/self/maps` scan for frida / gum / libgadget mappings |
| `fridaThreads()` | `/proc/self/task/*/comm` scan for frida / gum-js thread names |
| `integrity()` | bitmask: bit0 = own code segment / export entries modified, bit1 = libc functions modified |

JVM-level hooks (Java API hooking, repackaged dex hooks) cannot cover C call sites at
all. Every scan runs **both channels**; either one hitting triggers the lock. If the
library fails to load, the JVM implementation continues alone.

#### Anti-hook mechanisms (v0.7.3)

| Threat | Countermeasure |
|---|---|
| **GOT / PLT / LD_PRELOAD hook** of libc | All detection I/O uses raw `SYS_openat` / `SYS_read` / `SYS_close` / `SYS_getdents64` syscalls — GOT rewriting cannot reach a syscall instruction. String primitives (strstr / strlen / atoi / compare) are self-implemented, so the detectors never call hookable libc routines. |
| **Inline hook** of `libsyna_shield.so` code | `integrity()` computes a self-contained SHA-256 (no crypto library) over every `PT_LOAD` executable segment **in memory vs. on disk** (`dl_iterate_phdr` resolves the load mapping; `useLegacyPackaging` guarantees the .so is a real file). Any byte patch → mismatch → bit0. |
| **Inline hook of the detectors themselves** | Every JNI export entry — including `integrity()` itself — is compared byte-wise against the disk file (self-referential: hooking the verifier is also a code modification). |
| **Inline hook of libc functions** | `openat` / `read` / `close` / `getdents64` / `dlsym` / `dlopen` / `pthread_create` entry bytes are compared against the on-disk bionic libc; the address is resolved via `dlsym` (**not** through GOT) → mismatch → bit1. |

Response: bit0 → CRITICAL `SHIELD_TAMPERED` (honeypot lock, key release); bit1 →
CRITICAL `FRIDA_DETECTED` (injection). Combined with the watchdog ring, the fail-closed
gate, and the JVM channel, the cost of a silent bypass is deliberately very high.

Honest ceiling (app-layer): hooking the verifier *and* suppressing the execution chain
is the theoretical limit of any in-process defense — but that itself requires an
injection which the remaining channels are watching for.

### 3.5 Runtime change detectors (Android)

| Detector | Mechanism | Threat |
|---|---|---|
| `checkCredentialChange` | API 30+: `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` transitions from SUCCESS to unavailable → device possibly re-owned. Skipped below API 30 (no crash, no feature loss). | CRITICAL `CREDENTIAL_CHANGED` |
| `checkDeviceAdminChange` | `DevicePolicyManager.activeAdmins` signature changes → MDM takeover warning | CRITICAL `DEVICE_ADMIN_CHANGE` |
| `checkAccessibilityChange` | `ENABLED_ACCESSIBILITY_SERVICES` string changes | HIGH `ACCESSIBILITY_ABUSE` |
| `checkForegroundApp` | Usage-access query of last 60 s: foreground package matches monitoring fragments → immediate lock (stronger than mere installation) | HIGH `MONITORING_APP` |
| `checkClockChange` | Wall clock delta vs. `SystemClock.elapsedRealtime` delta diverges > 5 min (time-travel forgery of audit/message timestamps) | LOW advisory `CLOCK_CHANGED` |
| VPN callback | `ConnectivityManager.NetworkCallback` on `TRANSPORT_VPN` toggling | MEDIUM `VPN_CHANGE` |
| Background switch | `ProcessLifecycleOwner`: ON_STOP → ON_START within 1.5 s (screen-share / monitoring pattern) | HIGH `BACKGROUND_SWITCH` |

### 3.6 Screen attack surface

| Detector | Behavior | Threat |
|---|---|---|
| Screen capture/recording (API 34+) | `Activity.ScreenCaptureCallback` system event → lock at the moment of capture; older Android is covered by `FLAG_SECURE` content blackout | HIGH `SCREEN_RECORDING` |
| `checkScreenMirroring` | Presentation display (`DISPLAY_CATEGORY_PRESENTATION`) appears/disappears — **change-based**, so a permanently-present display cannot re-lock forever | HIGH `SCREEN_SHARE_SUSPECT` |
| Desktop display count | `GraphicsEnvironment.screenDevices` count changes | HIGH `SCREEN_SHARE_SUSPECT` |

---

## 4. Meta-detection: heartbeat gate & watchdog ring

### 4.1 ShieldGate (fail-closed decrypt gate)

The single most important mechanism. The engine heartbeat drives it; every decrypt
path checks it.

**Heartbeat slot:** a timestamp + HMAC-SHA256 fingerprint (32-byte random key per
process, never persisted). Updated by the engine at the top of every scan round.

- `arm()` — on Shield start; immediately stamps one fresh heartbeat (so startup
  decryption works).
- `beat()` — engine loop, every scan round.
- `releaseSession()` — **lock event**: zeroes the timestamp and scrambles the
  fingerprint; the gate now refuses everything, and the in-memory session key is
  invalidated. This is the "locking releases the keys" primitive.
- `restoreSession()` — unlock event: gate re-armed and given a fresh heartbeat.
- `disarm()` — Shield disabled: gate fully bypassed (decrypt always allowed).

**Gate check (`isFresh()`)** — inside `ShieldStorageKey.decrypt` on both platforms:

1. not armed → allow (Shield off)
2. lockedOut → **deny**
3. never beat → **deny**
4. `now - lastBeat > 12 s` → **deny** (detector paused/killed)
5. HMAC fingerprint mismatch → **deny** (timestamp frozen by a hook)

An attacker who pauses the detector thread gets **no data at all** — the UI lock is
only the visible half; the invisible half is that decryption refuses. This is the
property that holds even when the UI itself is bypassed.

### 4.2 WatchdogRing (dead-letter sentinels)

3 daemon threads (`syna-watchdog-0..2`), each with its own randomized cadence
(4 000–7 000 ms), arranged in a ring:

```
W0 monitors slot[1]   →   W1 monitors slot[2]   →   W2 monitors slot[0]
```

Each slot is a timestamp + HMAC fingerprint with its own random key. On each tick a
watchdog checks its target slot: stale (> 12 s) or fingerprint-broken → `trips++` +
CRITICAL `WATCHDOG_TRIP` (audit action `WATCHDOG`, then the normal threat pipeline:
lock + self-destruct if enabled).

Why a ring and not a single watcher: killing **any one** thread leaves its slot
stalled and its neighbor reports it. Killing **all three** stalls the engine heartbeat
as well — the gate fails closed. There is no "kill the watcher" win.

### 4.3 Active countermeasures (since v0.7.8)

Beyond passive detection, the Shield actively fights back:

| Countermeasure | Mechanism |
|---|---|
| **Operation-triggered integrity probing** | Every ~8th decrypt runs a native code-integrity check. The periodic scanner can be paused by an attacker — but the decrypt path is one they must use, turning every decrypt into a detection window (millisecond overhead) |
| **Active crash defense (v0.7.9)** | High-confidence signals — `TracerPid ≠ 0` (ptrace-attached) and integrity bits 0/1 (own code / libc hooked) — trigger an immediate native `SIGABRT` instead of a polite lock. An attacker who attaches a debugger or hooks a detector gets a crash on **every** attempt; there is no stable window to debug step-by-step. Audit is written best-effort before the crash. Low-confidence signals (ports/paths/maps) keep the honeypot flow, so false-positive crashes are impossible |
| **Watchdog self-healing** | A watchdog trip immediately restarts the detection loop — a paused scanner is revived, a killed one reborn; the heartbeat resumes instead of staying dead |
| **Honeypot data pollution** | Engaging the fake-lock writes decoy messages into the local store (audited). An attacker who eventually unlocks faces polluted data and cannot tell real records from decoys |

### 4.4 In-memory state HMAC

`ShieldState` transitions write an HMAC signature of the state name; `stateIntact()`
re-verifies on every critical path. If the in-memory state was rewritten (e.g. a hook
forcing `UNLOCKED`), the signature no longer matches → forced `LOCKED` + `SHIELD_TAMPERED`
audit. Combined with the gate, patching the state variable in memory does nothing.

---

## 5. Network environment detection (LAN MITM)

Syna is a LAN messenger; the LAN is exactly where ARP spoofing and CA injection live.

| Detector | Mechanism | Response |
|---|---|---|
| `checkCaChange` | Enumerate `AndroidCAStore` aliases (system + user certs); the alias set changing means a certificate appeared/disappeared — the classic precondition for intercepting TLS with a planted CA | MEDIUM `NETWORK_MITM` (lock + audit) |
| `checkArpSpoof` | Read `/proc/net/arp`, resolve the default gateway from `LinkProperties.routes`, compare the gateway MAC across scans. **Gateway IP change (network switch / DHCP renew) re-baselines instead of alarming** — only a MAC change at a stable gateway raises the flag | MEDIUM `NETWORK_MITM` |
| `checkNetworkFingerprint` | SSID via `WifiInfo` (API 31+ reads `NetworkCapabilities.transportInfo`; the no-permission placeholder `<unknown ssid>` is filtered out) | LOW advisory `NETWORK_CHANGED` |

---

## 6. Screen attack surface

See [3.6](#36-screen-attack-surface). Additional behavior:

- `FLAG_SECURE` is set while Shield is enabled — the OS renders the app as black in
  screenshots and screen recordings on all API levels.
- The capture callback (API 34+) fires at the moment of capture — the app locks and
  the event is audited before anything can be done with the frame.
- Clipboard: Syna's own clipboard writes are cleared on background/lock; notifications
  are hidden while locked (burn-after-reading content never leaks through the shade).

---

## 7. Response chain

| Severity | Threats | Response |
|---|---|---|
| CRITICAL | ROOT_DETECTED, CREDENTIAL_CHANGED, DEVICE_ADMIN_CHANGE, SHIELD_TAMPERED, FRIDA_DETECTED, WATCHDOG_TRIP, BRUTE_FORCE, DOWNGRADE_ATTEMPT | Lock + **self-destruct** (if enabled) + re-lock 30 s after any unlock while the threat persists |
| HIGH | EMULATOR_DETECTED, DEBUG_MODE, MONITORING_APP, ACCESSIBILITY_ABUSE, BACKGROUND_SWITCH, SCREEN_RECORDING, SCREEN_SHARE_SUSPECT, KEY_CHANGED | Lock + audit |
| MEDIUM | VPN_CHANGE, NETWORK_MITM (CA / ARP) | Lock + audit |
| LOW | INACTIVE, CLOCK_CHANGED, WEAK_LOCK, NETWORK_CHANGED, SELINUX_DISABLED, IME_CHANGED, USB_CHANGED, SUSPICIOUS_MODULE, PROXY_SET, DEVICE_CHANGED | **Audit only** — no forced lock (no false locks) |

- **Deduplication:** a threat already present is not re-reported per scan (audit stays
  clean); `clearThreat` removes it when the signal clears.
- **Lock:** full-screen pure-black page, red ◇, white text, white unlock button; all
  keys intercepted (including ESC/back via the activity back callback + preview key
  handler).
- **Auto re-lock after unlock:** 5-minute unlock TTL (`UNLOCK_TTL_MS`), plus a 30 s
  re-lock if a CRITICAL threat is still present.
- **Self-destruct protocol (deep, anti-forensics since v0.8.0):** on a CRITICAL
  signal, if enabled:
  1. `SecureWipe` — random-data overwrite ×2 passes + `fsync` + delete + parent-dir
     `fsync` on chat history (incl. `.tmp` remnants), received files, audit log
     (+ fail counter), TOTP seed, session blob, dex/version baselines, crash log;
  2. `ShieldStorageKey.wipe()` — master & session-auth keys deleted from the
     **Keystore/TEE**: even forensically recovered ciphertext is **permanently
     undecryptable** (desktop: key file securely overwritten);
  3. audit event written first, then the audit file itself wiped — no trail left.
  Clipboard and notifications cleared. Each threat triggers destruction only once.
  Honest limit: SSD wear leveling means overwrite alone cannot be guaranteed at block
  level — the real guarantee is Keystore key destruction + Android FBE.

### 7.1 Honeypot fake-lock (injection-class threats)

Frida / integrity tampering engage a **honeypot**: the lock screen is identical to a
real lock, but

- session keys are **really released** (gate closed, session key invalidated),
- unlock requires **3 consecutive biometric successes**,
- every attempt is audited (`HONEYPOT` action).

If 2FA is enabled, the second factor (TOTP) is required first — the attacker would
need the owner's biometrics *and* the second-factor seed, both of which they lack; the
honeypot doubles as a decoy that maximizes audit trails while the owner's data stays
locked.

### 7.2 Brute-force protection

- **Fail counter:** incremented per biometric failure, **encrypted at rest**
  (`ShieldStorageKey`-wrapped file) — restarting the app cannot reset it. Cleared on
  success.
- **Limit:** 5 consecutive failures → CRITICAL `BRUTE_FORCE`: session key released,
  self-destruct protocol triggered (if enabled), lock.
- **Exponential unlock cooldown:** after a failure the unlock prompt is silently
  suppressed for 1 s → 2 s → 4 s → … capped at 64 s. Silent: no dialog, no counter
  feedback to the attacker.

---

## 8. The unlock pipeline

```
LOCKED
  │  user taps unlock
  ▼
biometric prompt (**BIOMETRIC_STRONG only since v0.9.8** — device-credential auth windows cannot auto-pass; CryptoObject bound to the authenticated Keystore key when possible)
  │  success → captureAuth(): decrypt session blob → session key cached in memory
  ▼
2FA enabled? ── yes ──► AWAITING_TOTP: 6-digit RFC 6238 code entry view
  │ no                     │ correct → proceed        │ wrong → LOCKED + fail counter
  ▼                         ▼
honeypot active? ── yes ──► streak++ (needs 3 consecutive successes) → UNLOCKED
  │ no
  ▼
UNLOCKED  (gate restored, heartbeat re-armed, fail counter cleared,
           TTL 5 min → auto LOCKED; CRITICAL threats re-lock in 30 s)
```

- **TOTP (v0.7.0):** RFC 6238, HMAC-SHA1, 30 s step, 6 digits, ±1 step drift tolerance.
  Seed = 20 random bytes, stored **encrypted at rest** in the same Keystore/0600 key
  domain; exposed as an `otpauth://totp/Syna:<label>?secret=<base32>&issuer=Syna&digits=6&period=30`
  URI in Settings for import into Google Authenticator / Microsoft Authenticator /
  Aegis / any TOTP app. Security rests on the seed — the algorithm being public is
  irrelevant (it is a public standard).
- **captureAuth (v0.7.1, timing corrected in v0.7.5):** the session blob is decrypted
  inside the freshly refreshed auth window and the session key is cached — but only
  when the unlock actually completes: with 2FA enabled the capture happens **after**
  the TOTP code verifies, so a single biometric success alone cannot decrypt data.
- **Wrong TOTP codes** feed the same brute-force pipeline (fail counter, cooldown,
  self-destruct at the limit).
- **Disabling the Shield is equally protected (v0.7.4)**: turning it off requires
  biometrics + TOTP — an attacker without both cannot disable an enabled shield, even
  from the lock screen.

---

## 9. Data protection: key hierarchy & formats

### 9.1 Key hierarchy (Android)

```
┌─────────────────────────────────────────────────────────────┐
│ Keystore / TEE (never exported to JVM memory)               │
│   master key  ("syna_storage_key", AES-256-GCM)             │
│   auth key    ("syna_session_auth", AES-256-GCM,            │
│                setUserAuthenticationRequired(true),         │
│                300 s window, NOT invalidated-by-enrollment) │
└─────────────────────────────────────────────────────────────┘
        │                              │
        │ master key encrypts          │ auth key encrypts (only inside
        ▼                              ▼ an authentication window)
  [historical data]              [session blob: syna_session_blob]
  (pre-v0.7.1)                          │
                                         ▼
                                 [session key: 32 random bytes,
                                  cached in memory while unlocked]

  data written while unlocked  ──encrypt with session key──►
  decrypt path: session key first → master key fallback (history/upgrade)
```

- **New writes use the session key.** Without an authentication event (device stolen,
  app locked, auth window expired) the session key is unobtainable → **new data is
  unreadable** — a data-level guarantee independent of what detection sees.
- **Locking invalidates the in-memory session key** (via `SessionKeyStore.invalidateSession`
  in the state transition to LOCKED and on Shield disable).
- **Historical data** (master-key encryption) decrypts via fallback — upgrade is smooth,
  nothing is lost; new writes transition to session-key encryption automatically.
- **Desktop:** no system biometric gate exists; the desktop implementation honestly
  keeps the master-key path.

### 9.2 Chat storage format

JSONL file; every line:

```
SYNA1\n + AES-GCM(nonce 12 B ‖ ciphertext ‖ tag 16 B)
```

Written via `ShieldStorageKey.encrypt` (which applies the session-key-first policy);
read via the gate-checked decrypt path.

### 9.3 Audit log format

```
base64(AES-GCM(json)) | prevHash | sha256(prevHash | content)
GENESIS_HASH = "genesis"
```

- Content is AES-GCM encrypted with the **master key** (Keystore — decryptable even
  while locked, so the audit survives lock-outs; metadata always uses the master key,
  only chat-data files use the session key).
- The chain binds every record to its predecessor — tamper with any record and every
  subsequent one fails verification on load; loading stops at the break (the broken
  record is not silently accepted).
- Cap: 100 events kept in memory; persisted file capped at 2 000 lines (oldest
  trimmed), chain re-sealed after trimming.
- Recorded actions: DETECTED / CLEARED / LOCKED / UNLOCKED / SELF_DESTRUCT / DISABLED /
  KEY_RELEASED / HONEYPOT / WATCHDOG.

### 9.4 Other encrypted-at-rest artifacts

| Artifact | Purpose |
|---|---|
| `syna_dex_base` | dex integrity baseline |
| `syna_version_base` | downgrade baseline |
| `syna_totp_seed` | TOTP seed (master key) |
| `syna_key_pins` | TOFU key pins (master key) |
| `<events>.fails` | biometric fail counter (master key) |

---

## 10. State machine reference

```
          ┌────────────────────────────────────────────┐
          │                                            │
          v                                            │
        ARMED ──threat detected────────► LOCKED ──user taps unlock──► (biometric)
          ▲                              │  ▲                            │
          │ threat cleared               │  │ wrong TOTP / fail          │ success
          │                              ▼  │                            ▼
          └──────────────────────    AWAITING_TOTP ──correct code──► UNLOCKED
                                     (only when 2FA enabled)              │
          UNLOCKED ──5 min TTL / CRITICAL re-lock / manual lock──► LOCKED │
          UNLOCKED ──Shield disabled──► (gate disarmed)                   │
          LOCKED ──Shield disabled──► (gate disarmed)                     │
                                                                          │
          every LOCKED entry: gate releaseSession + session invalidate + │
          KEY_RELEASED audit ─────────────────────────────────────────────┘
```

Transitions are HMAC-signed; any detected inconsistency forces LOCKED.

---

## 11. Attack scenario walkthroughs

### 11.1 Device is rooted
Root signals appear (su/magisk/xposed/zygisk/shamiko/lsposed) → CRITICAL
`ROOT_DETECTED` → lock screen, audit, **self-destruct** (if enabled) → after any unlock
attempt, 30 s re-lock while root persists. Even if the attacker kills the scanner: the
heartbeat stalls → gate refuses decryption.

### 11.2 Repackaged / re-signed APK
Signature fingerprint mismatch → `SHIELD_TAMPERED` → honeypot lock (keys really
released, 3× biometric required). If the attacker hooks the signature check instead:
the dex-hash baseline still trips, and if they hook that too, the gate's HMAC heartbeat
fingerprint check catches a frozen timestamp. Patched dex also breaks `verifyDexIntegrity`
because the versionCode did not change.

### 11.3 Frida injection
Four independent signals: native TracerPid (ptrace), native maps scan, native thread
names, JVM mirror of all three, plus frida-server ports/paths. Hooking the JVM channel
leaves the native channel live; hooking both requires gadget-level native scripting.
Meanwhile the gate keeps the heartbeat — any pause in the detector thread (the usual
first step) → fail-closed. Since v0.7.9 the highest-confidence signals escalate
further: `TracerPid ≠ 0` and code-integrity hits trigger an immediate native
`SIGABRT` — the process dies on every attach attempt, leaving no window for
step-by-step debugging.

### 11.4 Device physically stolen
- The lock screen requires biometrics (TEE-verified, cannot be faked by software).
- With 2FA on: plus a TOTP code from the owner's authenticator.
- Brute-forcing the biometric → fail counter → session release + self-destruct.
- Data-level gate: anything written after the last authentication is unreadable
  without an auth event; the master key never leaves the TEE.

### 11.5 LAN MITM (ARP spoofing / planted CA)
Gateway MAC change at a stable gateway → `NETWORK_MITM` lock; a planted user CA →
`NETWORK_MITM` lock. Combined with E2E encryption (X25519 + AES-256-GCM) the attacker
gets encrypted blobs at most.

### 11.6 Screen theft (shoulder surfing / screen share / capture)
Capture event (API 34+) → instant lock; mirroring display appears → lock; rapid
background switching (1.5 s) → lock; `FLAG_SECURE` blacks out content on older Android;
clipboard and notifications are cleared/hidden while locked.

---

## 12. Platform comparison (Android vs desktop)

| Capability | Android | Desktop |
|---|---|---|
| Root / injection / emulator / debug detection | ✅ full matrix | ⚠️ process-level only (`javaagent`, remote-control processes) |
| Network MITM / CA / ARP / SSID | ✅ | ❌ (OS-managed) |
| Screen capture events / mirroring | ✅ (API 34+ events, presentation display) | ✅ display-count change |
| Biometric unlock | ✅ Keystore-verified | ❌ — confirm button (honest) |
| TOTP 2FA | ✅ | ✅ (code entry replaces the confirm button) |
| Session-key data gate | ✅ biometric-authenticated Keystore wrap | ❌ master-key path (no OS biometric gate) |
| Idle auto-lock | ❌ (background switching detector instead) | ✅ 10 min mouse-idle |
| Fail-closed gate, watchdog, honeypot, brute-force, self-destruct, audit | ✅ | ✅ (shared core) |

---

## 13. False-positive control & known trade-offs

- **Advisory tier (LOW):** clock change, weak lock, network change, SELinux — audited,
  never force-lock.
- **Change-based detectors:** mirroring, ARP, SSID, CA, device-admin, accessibility —
  report on *change*, not on *state*; a permanently present condition cannot re-lock
  endlessly.
- **Re-baselining:** gateway IP change (network switch) resets the ARP baseline; dex
  and version baselines refresh on legit upgrades; normal CA store evolution is
  accepted (MEDIUM, user can clear).
- **Known trade-offs:**
  - `com.android.shell` was *removed* from LSPosed features in v0.6.8 — it is a system
    package on every device and would have false-locked everyone (caught in review).
  - The `<unknown ssid>` placeholder (no location permission, API 31+) is filtered so
    it cannot trigger network-change alerts.
  - App-layer detection cannot see device-owner-level monitoring — stated in-app and
    here; the fail-closed gate and data-level gate are the compensating controls.
  - P2P key changes are rejected by TOFU (KEY_CHANGED lock); re-pin after verifying
    out-of-band (reinstall case).
  - File transfers are E2E-encrypted for 1:1; group-file transfer is plaintext
    (per-member ciphertext fan-out not yet implemented) — documented honestly.

---

## 14. Version history

| Version | What was added |
|---|---|
| v0.4.0 | First release: root / Frida / emulator / USB-debug detection, biometric lock page, screen-capture protection, self-destruct protocol, audit timeline |
| v0.5.0 | Device-admin & credential change, VPN change, background switch, accessibility abuse, monitoring apps, APK signature verification, HMAC-signed settings, clipboard & notification protection, honest capability boundary |
| v0.6.0 | Desktop engine (idle lock, screen count, JVM agent, remote-control processes), desktop storage key |
| v0.6.1 | In-memory state HMAC (anti state-write bypass) |
| v0.6.2 | Hash-chained audit persistence |
| v0.6.3 | Audit log AES-GCM encryption at rest |
| v0.6.4 | Lock screen redesign (black / red ◇ / white) |
| v0.6.5 | Single master switch (one tap enables everything), usage-access grant guidance, foreground-app sensing, split scan cadence (light 3 s / heavy 15 s) |
| v0.6.6 | Clock-tamper & weak-lock advisories, Frida port 27043, emulator test-keys, audit encryption harden |
| v0.6.7 | **Open-source-proof hardening**: heartbeat gate (fail-closed), watchdog ring, honeypot fake-lock, brute-force protection, dex self-verification, key release on lock, live status panel |
| v0.6.8 | **Network & screen attack surface**: capture/recording events (API 34), mirroring change detection, CA-cert & ARP-spoof detection, SSID fingerprint, Zygisk/Shamiko/LSPosed, SELinux, scan jitter, background memory wipe, unlock cooldown backoff, downgrade defense |
| v0.6.9 | Fixed gallery/file picker crash on all real devices (androidx.activity requestCode ≥ 65536 vs. platform 16-bit limit) — moved to fixed requestCode `startActivityForResult` |
| v0.7.0 | **TOTP 2FA**: biometric + 6-digit dynamic code dual unlock, `otpauth://` seed import, wrong codes feed brute-force pipeline |
| v0.7.1 | **Data-level key gate**: session-key layer wrapped by biometric-authenticated Keystore key; no auth → new data unreadable; lock invalidates session; master-key fallback for history |
| v0.7.2 | **Native anti-debug (NDK)**: TracerPid/maps/threads read in C (4 ABIs), JVM + native dual-channel verification, graceful fallback |
| v0.7.3 | **Native anti-hook**: syscall-direct I/O (GOT/PLT/LD_PRELOAD dead), own-code-segment memory-vs-disk hashing (inline-hook detection), export-entry self-verification, libc entry verification — bitmask wired into SHIELD_TAMPERED / FRIDA_DETECTED |
| v0.7.4 | **Rotation & unkillable shield**: session-key rotation on every unlock (forward secrecy: old memory dumps die), dual-factor disable (biometrics + TOTP — an attacker cannot turn an enabled shield off, even from the lock screen), anonymous rwx segment detection (bit2), AWAITING_TOTP lock-screen rendering fix |
| v0.7.5 | **Full review fix release**: 2FA bypass closed (no unlock while awaiting code; clearThreat cannot skip it), session-key capture moved after TOTP verification, biometric fail double-count fixed, server relay `from` forgery closed, BURN_ACK/RECALL sender validation, FILE_CHUNK bounds + pipeline isolation, TOTP-enable failure guard, DEBUG_MODE downgraded to HIGH, honeypot streak no longer reset by re-reports, watchdog one-shot trip + restart reset |
| v0.7.6 | Review second pass: shield lifecycle on dispose, desktop key quarantine, captureAuth TOCTOU, native maps buffers, outbox mutex, receipt/key-frame guards, inbound TCP read timeout |
| v0.7.7 | All client bugs closed: TCP-failure → offline queue, bidirectional heartbeat (PONG), burn TTL fallback, server-group disconnect awareness, discovery resilience, quote-bar/image-decode/EDT UI fixes, CA user-cert-only, mirroring both directions, exact process matching |
| v0.7.8 | **Expanded detection & active countermeasures**: Riru/EdXposed/TaiChi + SELinux domain in root detection, IME/USB/suspicious-module advisories (LOW); **operation-triggered integrity probing on the decrypt path**, **watchdog self-healing** (scanner restart), **honeypot data pollution** (decoy messages) |
| v0.7.9 | **Active crash defense**: native `SIGABRT` on high-confidence signals (ptrace attach / code or libc hooked) — no stable debugging window; usage-access grant now targets Syna (`EXTRA_APP_PACKAGE`); full permission self-check on every launch |
| v0.8.0 | **Deep self-destruct (anti-forensics)**: `SecureWipe` 2-pass random overwrite + fsync on every sensitive file, **Keystore/TEE storage-key destruction** (recovered ciphertext permanently undecryptable), audit log self-wiped after the event, TOTP seed / session blob / baselines / crash log all wiped |
| v0.8.1 | **Key pinning (TOFU) & P2P MITM closure**: public keys pinned on first use (encrypted at rest), fingerprint badge in chat header (full fingerprint for out-of-band verification), key changes rejected + `KEY_CHANGED` lock (no more HELLO/KEY forgery poisoning); **encrypt-only session mode** (refuse plaintext fallback); **replay defense** (10-min window on real-time frames) |
| v0.8.2 | **Message ACK/retransmission** (P2P reliability root fix), **group administration** (kick/mute/admins, receiver-side permission checks), **no-export policy** (`allowBackup=false`; no backup/export features by design) |
| v0.9.0 | **Encrypted file transfer** (1:1 FILE_CHUNK payloads E2E-encrypted; group files documented), **voice messages** (long-press record, encrypted channel, AMR/WAV), **burn-send biometric re-auth**, **clipboard 30 s TTL**, server slow-client isolation & burn-history TTL |
| v0.9.1 | **Full audit stabilization**: 2FA lockout eliminated (metadata moved to master key), honeypot decoy no longer overwrites history, burn-TTL dead code fixed, server-history replay unblocked, mesh group files fixed, ACK infinite-retransmission fixed, UDP key-port & chunk-size fixes, no-biometrics tap-counting fixed, server ownership checks — 60 issues fixed, `SECURITY_AUDIT_REPORT.md` published |
| v0.9.2 | **Group-file per-member encryption** (last plaintext path closed), **stealth mode**, **suspicious-module partition whitelist + concrete module reporting**, group member fingerprints |
| v0.9.3 | **Lock-bypass fix** (threat-cleared-while-locked no longer unlocks), 2FA enable robustness, no-biometrics device notice, **system HTTP proxy detection** (PROXY_SET, LOW advisory) |
| v0.9.5 | **Device identity detection** (DEVICE_CHANGED: ANDROID_ID vs encrypted baseline), **audit-integrity check** (deleted audit → SHIELD_TAMPERED), decrypt-path probe every 6th decrypt, dex hashing moved to the heavy scan |
| v0.9.6 | **Self-protection hardening**: native heartbeat slot (JVM-hook immune, dual-gate decrypt check), scanner exception self-healing, audit-write-failure detection (SHIELD_TAMPERED), reinstall guard (identity change prompts Shield re-enable) |
| v0.9.7 | **3rd full review**: shield-off lockout fixed, audit recursion fixed, TCP-heartbeat new-connection kill fixed, locked-state disk-wipe prevented, group-file O(N²)/REQ_KEY storm fixed, resend dead-code fixed, dex hashing finally removed from light scan, server membership/BURN_ACK auth |
| v0.9.8 | **ART JIT `memfd:jit-*` whitelist** (no lock-on-enable), name-independent non-JIT memfd injection detection, **BIOMETRIC_STRONG-only unlock**, **signature-learning monitoring blacklist** (renames no longer bypass) |
| v0.9.9 | **Process-epoch forward secrecy**: every start derives a fresh random epoch; session keys use `base\|min(epoch)\|max(epoch)` — a stolen session key from this run cannot decrypt the previous run; **double-try decrypt** (new formula → legacy fallback) keeps both sides symmetric regardless of key-exchange ordering; **encrypt-only now default** (no plaintext fallback, keys-missing messages queue + auto-send on pin); **no plaintext group fallback** (LAN mesh + server groups queue instead); **late-key frames** (UDP reordering) held in a bounded pending queue and decrypted on pin — never stored as raw ciphertext |

---

## 15. Verification & testing

86 automated desktop tests, including:

- gate fail-closed behavior (stall → decrypt refused; lock → refused; unlock → restored)
- watchdog trip → forced CRITICAL lock
- honeypot: repeated-verification requirement, key-release on engage
- brute-force: 5-fail limit → lock + self-destruct; cooldown backoff (silent ignore
  during cooldown)
- hash-chain round-trip & tamper detection (broken chain stops loading)
- audit-log unreadable while locked
- background memory wipe / restore (shortened delay injected)
- **official RFC 6238 test vectors** (all 6, 8-digit) + 6-digit default + ±1-window
  drift tolerance + base32 round-trip + `otpauth://` URI format + full 2FA state-machine
  flow (enable → lock → biometric → wrong code → cooldown → correct code → unlock →
  disable)

Plus a security review pass after every release (the v0.6.8 `com.android.shell`
false-lock and the v0.6.9 picker crash were both caught in review / on real devices and
fixed with dedicated tests).

Release artifacts are signed; each GitHub release ships a SHA-256 manifest.

---

## 16. Honest boundary (again, plainly)

The Shield is an **app-layer** defense, layered like an onion: static detection →
runtime inspection → integrity → meta-detection (gate/watchdog) → native anti-hook →
data-level gates → self-destruct. Every layer can *individually* be defeated by a
sufficiently skilled attacker; the design goal is that **silently defeating all of
them at once** requires capabilities outside the app layer. What is honestly outside
this scope:

| Boundary | Why | Compensating control |
|---|---|---|
| **Device-owner level**: pre-installed system spyware, enterprise MDM | App cannot see device-owner privileges (v0.5.0, stated in-app since) | fail-closed gate, data-level key gate, self-destruct |
| **Kernel level**: rootkits that hook below the runtime and **forge /proc** (fake TracerPid, fake maps) | Every detector (JVM and native) ultimately reads kernel-provided data | data-level key gate (no auth → no session key); self-destruct on CRITICAL signals still observed |
| **Native hooking the verifier itself** (v0.7.3): inline-hooking `integrity()` *and* suppressing its execution chain | The theoretical limit of any in-process defense | doing so requires an injection that the JVM channel, watchdog ring, and heartbeat gate are watching for; a paused detector stalls the heartbeat → fail-closed |
| **Perfect memory dump** (root + immediate dump) inside the 300 s auth window | The session key legitimately lives in memory while unlocked | lock releases it instantly; outside the window the authenticated Keystore key refuses to unwrap the blob |

What the Shield **guarantees even then**:

- **The fail-closed gate** — a process that cannot prove its health cannot read data.
- **The data-level key gate** — data written after the last authentication is
  unreadable without one.
- **Native anti-hook** — GOT/PLT/LD_PRELOAD and naive inline hooks are detected, not
  just survived.
- **Self-destruct** — critical compromise erases local data rather than surrendering it.

Trust your network. Trust your device. The Shield makes betrayal expensive.
