# Syna Security & License Audit Report

**Version audited:** v0.9.1 (candidate)
**Date:** 2026-08-10
**Scope:** full codebase (common / Android / desktop / server / native NDK / tests)

---

## 1. Code review & bug-fix summary

Three independent review passes (network/protocol, storage/UI/voice, Shield/server) plus
verification against the full test suite. **~60 confirmed issues found; all fixed.**

### Critical fixes (data safety & lockout)

| # | Issue | Fix |
|---|---|---|
| 1 | TOTP seed / key pins / audit / fail-counter encrypted with the **session key** — after lock-out (no session key) 2FA verification, TOFU checks and audit loading could never decrypt → **permanent 2FA lockout** | New `encryptWithMaster`/`decryptWithMaster` channel; all metadata now uses the master key (Keystore) — data files keep session-key gating |
| 2 | Honeypot decoy write did `rewriteNow()` with empty memory → **overwrote real chat history permanently** | Decoy only rewrites disk when real messages exist in memory (`hasMessagesInMemory`), else memory-only |
| 3 | `burnSweepMarks` was never written → the 60 s burn TTL fallback was dead code (unviewed burn messages never destroyed) | Marks written on enqueue |
| 4 | Server-history replay was killed by the replay guard (join → old history invisible) | `routeServerFrame(..., fromHistory = true)` bypasses replay for persisted history |
| 5 | Mesh group file transfer misdetected as "server group disconnected" → **group files never sent** | Server-group detection now uses explicit `serverGroupId` |
| 6 | ACK retransmission counter was reset to 0 on every recursion → **infinite retransmission**; ACK race could resurrect the entry; TEXT never used ACK at all | Insert-once + recheck-before-resend + TEXT wired into ACK/retry |
| 7 | UDP key exchange sent to a dead default port (45878) → encryption could never establish in UDP mode | Key frames carry the announcement's real UDP port |
| 8 | 64 KB file chunks exceed the 65 KB UDP payload → UDP file transfer unusable | Chunk size 40 KB in UDP mode |
| 9 | Debounced rewrite could overwrite disk with empty memory during the 500 ms window (lock/memory-wipe) → **history wipe** | `rewriteJob` cancelled in `releaseMemory` |
| 10 | Burn-purge path never persisted → destroyed messages resurrected on restart | `scheduleRewrite` added to remove/purge paths |

### Shield & state machine

| # | Issue | Fix |
|---|---|---|
| 11 | Devices without biometrics: every unlock tap counted as brute-force → **5 taps = self-destruct** | `ERROR_NO_BIOMETRICS/HW_UNAVAILABLE/NO_DEVICE_CREDENTIAL` no longer counted |
| 12 | `disablePending` residue: unlock after an interrupted disable flow **silently turned the Shield off** | Any new LOCKED transition clears the disable flow |
| 13 | `WATCHDOG_TRIP` never cleared → permanent 30 s re-lock loop | Auto-cleared when the heartbeat recovers (3 s) |
| 14 | Self-destruct order: audit event written *after* the file wipe → audit "resurrected" with a recreated key | Shield disabled at the end of full destruct; key pins & voice cache added to wipe list |
| 15 | Screen-capture callback unregister was a no-op (activity nulled first) | Order fixed |
| 16 | Group admins could promote/demote admins & kick the creator (privilege escalation) | SET_ADMIN/REMOVE_ADMIN creator-only on both ends; creator protected |
| 17 | `verifyIdentity` no-op when Shield disabled → burn messages un-sendable | Null-safe fallthrough |
| 18 | Burn send verification failure still cleared the input | Input cleared only inside `doSend` |
| 19 | Voice recording: no cleanup path (microphone leak, unbounded WAV buffer on desktop) | `DisposableEffect` cancels recording; start-failure handled; AMR-on-desktop play gives explicit notice |

### Server

| # | Issue | Fix |
|---|---|---|
| 20 | Zombie sessions (no read timeout) could exhaust the connection cap | 180 s read timeout |
| 21 | Kick only closed the first session of a multi-session user | All sessions kicked |
| 22 | Any member could forge `from = groupId` (server impersonation) | Relay requires `from == authenticated userId` |
| 23 | BURN_ACK could purge any message; RECALL could recall anyone's message | Ownership + burn-type checks |
| 24 | `SRV_AUTH_OK` history read unsynchronized → CME crashed joins | Locked snapshot |
| 25 | Expired burn history visible for the first hour after start | Immediate sweep on boot |

### Robustness

- `fileAssemblers` → concurrent map; watchdog cascade trip fixed; `ShieldGate.beat` atomic; persistence writes mutexed (random tmp suffix) + `fd.sync`; `MAGIC` boundary; `open` flag volatile; outbox flush semantics; UDP send exception guard; `removeMessageById` CAS; purge paths persist.

---

## 2. License (contamination) audit

### Result: **CLEAN — no license contamination**

| Check | Result |
|---|---|
| Direct & transitive runtime dependencies | **100% Apache-2.0** (JetBrains Kotlin/Compose, androidx, `com.russhwolf:multiplatform-settings`, `com.google.guava:listenablefuture`, `org.jspecify:jspecify`) |
| Copyleft (AGPL/GPL/LGPL) dependencies | **None** |
| Native toolchain (NDK / CMake) | Apache-2.0 tooling, no runtime linkage |
| In-tree third-party code | **None** — SHA-256, base32, TOTP, native anti-hook layer, voice recorders all original |
| Icon assets | `Syna_logo_2.png` (user-provided project asset) |
| License file | `LICENSE` — GPL-3.0 full text present |
| SPDX headers | All source files carry `SPDX-License-Identifier: GPL-3.0-only` (3 test files were missing headers — fixed) |
| AI-generated-code notice | README notice present |

**GPL-3.0 compatibility note:** Apache-2.0 dependencies are compatible with a GPL-3.0
project (GPL-3.0 may link Apache-2.0 code; the reverse direction would be the concern,
which does not apply here).

---

## 3. Test status

- **76/76 automated tests passing** (serial execution; multicast flakiness eliminated)
- Coverage includes: ACK flow, group admin (kick/mute/admins), TOFU pin/reject/retrust,
  TOTP vectors + 2FA flow, hash-chain tamper, gate fail-closed, honeypot, brute-force,
  watchdog, burn/temp chat, server join/kick/ban, file transfer, offline outbox.
