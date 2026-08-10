# ◇ Mirtazapine Shield

**Real-time security monitor & application lock for Syna.**

The Shield is the security core of Syna: it continuously watches the device for compromise
indicators, locks the app behind a full-screen biometric gate when a threat is detected,
and — at the highest severity — destroys local data rather than letting it fall into
wrong hands.

> This document describes the design, the detection matrix, the countermeasures, and —
> most importantly — **why the protection remains meaningful even though Syna is fully
> open source (GPL-3.0)**.

---

## 1. Design philosophy: security under full disclosure

Every line of Shield code is public. An attacker can read the detectors, the thresholds,
the key locations, and the lock logic. Classic "security by obscurity" is therefore
**explicitly rejected**. Instead, the Shield relies on five properties that survive source
disclosure:

| Property | Mechanism | Why it survives disclosure |
|---|---|---|
| **Fail-closed gate** | Every decrypt path checks a heartbeat gate first. If the detector loop is paused, killed, or hooked, decryption is *refused* — equivalent to a lock even with the UI bypassed. | The attacker must make the gate appear fresh. Faking the gate requires injecting code into the process — which is itself what the detectors watch for. |
| **Redundant orthogonal detection** | The same attack is observed from multiple independent channels (files, ports, procfs, maps, threads, system callbacks, integrity hashes, watchdog timers). | Bypassing one channel is not enough; every channel must be bypassed, and each bypass is a detectable modification of the process. |
| **Meta-detection (watchdogs)** | 3 daemon threads monitor each other in a ring with randomized cadence; the engine heartbeat is HMAC-signed. | Killing any thread stalls its slot → neighbor watchdog trips. Killing everything stalls the heartbeat → the gate fails closed. |
| **Key material outside the process** | Android keys live in the Keystore / TEE (never exported to JVM memory); the session capability is released on lock and re-derived on unlock. | Source code contains no keys and no key derivation that can be replayed without the TEE. |
| **Honesty** | What cannot be detected at app layer is stated plainly, in-app and here. | No false promise, no false sense of safety. |

The realistic attacker model for a LAN messenger is: a compromised device, a device with
root, an injected process, a repackaged APK, a network MITM, or a physically stolen
device. The Shield addresses each one as documented below.

---

## 2. Threat model & honest boundary

**Detectable at app layer (detected by the Shield):**

- Root / Magisk / Xposed / Zygisk / Shamiko / LSPosed
- Frida injection (server paths, ports 27042 + 27043, `/proc/self/maps` library maps,
  thread names, `TracerPid` ptrace)
- Emulator environments (fingerprint, model, brand, test-keys build tags, goldfish /
  ranchu / vbox86 hardware)
- USB debugging / ADB, attached debuggers
- Repackaging (APK signature fingerprint) and runtime tampering (dex hash baseline)
- SELinux non-enforcing mode
- Clock tampering (wall clock vs. uptime divergence)
- Missing lock screen (weak lock advisory)
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

For those scenarios the only app-layer answer is the **self-destruct protocol** and the
**fail-closed gate**: if the process cannot prove it is healthy, data access stops.

---

## 3. Detection matrix

### 3.1 Static & environment (files, packages, system state)

| Detector | Indicators |
|---|---|
| `isRooted` | `su` binaries, Superuser APK, Magisk paths (`/sbin/.magisk`, `/data/adb/*`) & packages, Xposed paths & installer, Zygisk/Shamiko/LSPosed data dirs, LSPosed manager package |
| `hasFrida` | `/data/local/tmp/frida-server*`, TCP listen on 127.0.0.1:27042/27043 |
| `isEmulator` | fingerprint/model/brand markers, `test-keys` build tags, goldfish/ranchu/vbox86 hardware |
| `isDebugMode` | `ADB_ENABLED` setting, `Debug.isDebuggerConnected()` |
| `isSelinuxPermissive` | `/sys/fs/selinux/enforce` == 0 (advisory) |
| `checkWeakLock` | Keyguard not secure (advisory) |
| `hasMonitoringApps` | Known monitoring/remote-control package fragments |

### 3.2 Runtime process inspection

| Detector | Indicators |
|---|---|
| `detectProcessInjection` | `TracerPid` ≠ 0 in `/proc/self/status` (ptrace), `frida-gadget`/`frida-agent` in `/proc/self/maps`, frida/gum-js-loop thread names in `/proc/self/task/*/comm` |
| `hasJavaAgent` (desktop) | `-javaagent` / `-agentlib` / `-agentpath` JVM args |
| `remoteControlProcesses` (desktop) | TeamViewer / AnyDesk / OBS / VNC / scrcpy / RustDesk / ToDesk / SunLogin processes |

### 3.3 Integrity (anti-repackaging / anti-tamper)

| Detector | Mechanism |
|---|---|
| `verifySignature` | APK signing-certificate SHA-256 compared against the release hash compiled into `BuildConfig` (debug builds skip) |
| `verifyDexIntegrity` | SHA-256 of all dex files vs. an encrypted baseline stored at first run; normal upgrades re-baseline via `versionCode`; any un-versioned change → CRITICAL `SHIELD_TAMPERED` |
| `checkDowngradeAttempt` | `versionCode` rollback vs. encrypted baseline → CRITICAL `DOWNGRADE_ATTEMPT` |
| `ShieldConfigGuard` | Settings file is HMAC-signed; tamper or wipe → force-restore + `SHIELD_TAMPERED` |

### 3.4 Native anti-debug layer (NDK, since v0.7.2)

`libsyna_shield.so` (C, same GPL-3.0 license, all 4 ABIs) reads the same signals in C:

| Signal | Notes |
|---|---|
| `TracerPid` | kernel-maintained ptrace flag, read via `/proc/self/status` in C |
| Frida / gum maps | `/proc/self/maps` scanned in C |
| Frida / gum-js thread names | `/proc/self/task/*/comm` scanned in C |

JVM-level hooks (Java API hooking, repackaging hooks) cannot cover these call sites —
only gadget-level native hooks could, a far higher bar. **Every scan runs both the JVM
and the native channel; either one hitting triggers the lock.** If the library cannot
load, the JVM implementation takes over (no loss of protection).

### 3.5 Meta-detection (the Shield watching the Shield)

| Mechanism | Behavior |
|---|---|
| `ShieldGate` | Engine writes a timestamp + HMAC fingerprint each scan round (randomized cadence). **Every decrypt path checks freshness**: stale or forged → decrypt refused. Locking releases the session; unlocking re-arms. |
| `WatchdogRing` | 3 daemon threads, randomized 4–7 s cadence, each monitors the next slot (ring). Stale/fingerprint-broken slot → CRITICAL `WATCHDOG_TRIP` + lock. |
| State HMAC | In-memory `ShieldState` is HMAC-signed on every transition; any inconsistent read forces a lock (`SHIELD_TAMPERED`). |

### 3.6 Network environment (LAN MITM defense)

| Detector | Indicators |
|---|---|
| `checkCaChange` | New certificates in `AndroidCAStore` (sideloaded MITM certs) → `NETWORK_MITM` |
| `checkArpSpoof` | `/proc/net/arp` default-gateway MAC change → `NETWORK_MITM`; gateway IP change (network switch) re-baselines instead of alarming |
| `checkNetworkFingerprint` | SSID change → advisory `NETWORK_CHANGED` |
| VPN callback | `ConnectivityManager` transport VPN change → `VPN_CHANGE` |

### 3.7 Screen attack surface

| Detector | Behavior |
|---|---|
| Screen capture/recording (API 34+) | `Activity.ScreenCaptureCallback` system event → immediate `SCREEN_RECORDING` lock (older Android: `FLAG_SECURE` content blackout) |
| `checkScreenMirroring` | Presentation display appears/disappears → `SCREEN_SHARE_SUSPECT` lock (change-based, no re-lock spam) |
| Desktop display count | Screen count change → `SCREEN_SHARE_SUSPECT` |
| `BACKGROUND_SWITCH` | Foreground → background → foreground within 1.5 s → lock (screen-share / monitoring pattern) |

---

## 4. Response chain

| Severity | Threats | Response |
|---|---|---|
| CRITICAL | Root, debug mode, credential change, device-admin takeover, Frida, tampering, watchdog trip, brute force, downgrade | Lock + self-destruct protocol (if enabled) + re-lock 30 s after unlock while threat persists |
| HIGH | Emulator, monitoring apps, accessibility abuse, background switch, screen recording, screen share | Lock + audit |
| MEDIUM | VPN change, network MITM (CA / ARP) | Lock + audit |
| LOW | Inactivity, clock change, weak lock, network change, SELinux off | Audit only, no forced lock (no false locks) |

### 4.1 The lock screen

Pure-black background, red ◇, white text, white unlock button. All keys are intercepted
(including ESC/back). Unlock requires the system biometric prompt; the session stays open
for 5 minutes (`UNLOCK_TTL`) then re-locks automatically; critical threats re-lock after
30 s even after a successful unlock.

### 4.1.1 Two-factor unlock (TOTP, since v0.7.0)

Optional **dual verification** (Settings → ◇Mirtazapine Shield → 双重验证): after the
biometric passes, the lock screen turns into a code-entry view and a **6-digit RFC 6238
TOTP code** from your authenticator app is required. The seed is generated on-device and
shown as an `otpauth://` URI to import into Google Authenticator / Microsoft
Authenticator / Aegis etc. (stored encrypted in the same Keystore/0600 key domain).

- Wrong codes feed the existing brute-force pipeline: fail limit → key release →
  self-destruct; exponential unlock cooldown applies.
- Security rests on the seed (encrypted at rest) — the algorithm is fully public and
  that does not weaken it (RFC 6238 is a standard).

### 4.2 Honeypot fake-lock

Injection-class threats (Frida, integrity tampering) engage a **honeypot lock**: the
screen is identical, but the session keys are *really released* and unlock requires
**3 consecutive biometric successes**. An attacker without the owner's biometrics cannot
escape; every attempt is written to the audit log.

### 4.3 Brute-force protection

- 5 consecutive biometric failures → keys released + self-destruct protocol triggered
  (if enabled) + CRITICAL `BRUTE_FORCE` audit.
- The failure counter is **encrypted at rest** — restarting the app cannot reset it.
- Exponential unlock cooldown: after a failure the prompt is silently suppressed for
  1 s → 2 s → 4 s … capped at 64 s.

### 4.4 Self-destruct protocol

On CRITICAL compromise signs (root / debug / credential change / device-admin takeover /
brute force), if enabled: **all local chat history and received files are destroyed**,
clipboard and notifications are cleared, and the event is audited. Each threat triggers
destruction only once.

### 4.5 Session-key lifecycle

- Armed & heartbeating → decrypt allowed.
- Locked (or heartbeat stalled / watchdog tripped / honeypot engaged) → gate fails
  closed: **decrypt refused, session capability released**.
- Unlocked → gate re-armed, keys re-derived from Keystore-backed storage.

### 4.6 Data-level key gate (since v0.7.1)

A **session key layer** sits between the data and the Keystore master key:

- Data is encrypted with a random session key; the session key is wrapped in a blob by
  a **biometric-authenticated Keystore key** (`setUserAuthenticationRequired`, 300 s
  window).
- **Without an authentication event, newly written data is unreadable** — a stolen or
  compromised device cannot decrypt anything written after the last lock, regardless of
  what detection sees. Locking invalidates the in-memory session key immediately.
- Historical data (master-key encryption) still decrypts via fallback — smooth upgrade,
  no data loss; new writes transition to session-key encryption automatically.
- The biometric prompt carries a `CryptoObject` bound to the authenticated key; a
  post-auth `captureAuth` guarantees the session key even when the prompt started
  outside the auth window.
- Desktop has no system biometric gate — honestly unchanged (master-key path).

---

## 5. Data protection at rest

| Data | Protection |
|---|---|
| Chat history | AES-256-GCM, key in Android Keystore (TEE, non-exportable) / 0600-permission key file on desktop; format `SYNA1\n` + nonce + ciphertext |
| Audit log | AES-GCM encrypted lines + SHA-256 **hash chain** (any tampering breaks the chain and stops loading) |
| Biometric-fail counter, version baseline, dex baseline | AES-GCM encrypted files |
| Received files | Same storage key domain |

Memory: decrypted message memory is released while locked, and — since v0.6.8 — also
wiped after 60 s in the background (restored from encrypted storage on return).

---

## 6. Live status panel

Settings → ◇Mirtazapine Shield shows real-time state:

- Gate freshness (fail-closed active or not)
- Watchdog trips / ring alive
- Honeypot engagement
- Biometric failure counter (limit 5)
- Latest audit events (timestamp, threat, action)

This transparency is deliberate: a user can *see* when a detector is silent, which is
itself an anomaly signal.

---

## 7. Version history

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

---

## 8. Verification

- 68 automated tests, including: gate fail-closed behavior, watchdog trip, honeypot
  repeated-verification, brute-force self-destruct, hash-chain round-trip & tamper
  detection, unlock cooldown, background memory wipe/restore, and the **official
  RFC 6238 TOTP vectors** plus the full 2FA state-machine flow.
- Release artifacts are signed; each release ships a SHA-256 manifest in the GitHub
  release.

---

## 9. Honest boundary (again, plainly)

The Shield is an **app-layer** defense. A device-owner-level attacker (pre-installed
system spyware, enterprise MDM, kernel rootkits that hook below the runtime) is outside
what any app can detect. What the Shield guarantees even then: **the fail-closed gate and
self-destruct protocol ensure that a process which cannot prove its health cannot read
your data.**

Trust your network. Trust your device. The Shield makes betrayal expensive.
