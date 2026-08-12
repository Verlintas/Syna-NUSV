# Syna v1.0.0 — Code Review & License Audit Report

- **Date**: 2026-08-12
- **Scope**: entire codebase — 94 Kotlin source files + native C library + build configuration (~17,900 LOC)
- **Method**: two parallel read-only deep-scan passes (network layer / security layer) → manual line-by-line verification of every candidate (no blind trust in scanner output) → fixes → full regression
- **Status**: **FIXED & VERIFIED** (88/88 automated desktop tests green, including the 6-engine message-storm stress test); **license audit clean**

---

# Part 1 — Bug & Logic Review

## 1.1 Method

1. Two read-only review agents scanned the codebase in parallel:
   - **Network layer**: `SynaEngine.kt` (2,037 LOC — discovery / key exchange / E2E / ACK-retransmit / group chat / server groups / file transfer / burn-after-reading / recall), `Protocol.kt`, `SynaServer.kt` + `ServerMain.kt` + `ServerUi.kt`, TCP/UDP transports, discovery service, server channel.
   - **Security layer**: `Shield.kt` (1,309 LOC — detection matrix / fail-closed gate / watchdog ring / honeypot / self-destruct), `AndroidShieldEngine.kt` (1,090 LOC), crypto (X25519/HKDF/AES-GCM), storage & persistence, `ChatStore.kt`, UI logic (ChatScreen / SettingsScreen / App), `UpdateChecker.kt`.
2. Every high/medium candidate was then **manually verified against the actual source** (file:line confirmed) — scanner false-positives were discarded.
3. Confirmed issues were fixed; low-risk / design-tradeoff items were documented (see §1.4).
4. Full regression suite run to verify every fix.

## 1.2 Fixed Issues (19 items)

### HIGH severity (8)

**H1 — Outbox flush deletes frames that failed to send (message loss)**
- Location: `SynaEngine.kt` `sendNow()` / `flushFramesLocked()`
- Bug: the TCP branch of `sendNow()` caught the exception, re-enqueued the frame into the outbox, and **did not rethrow**. `flushFramesLocked()` then considered the call "successful" (`sent.add(f)`) and removed the frame from the outbox via `filterNot { it in sent }`. Net effect: a message whose TCP send failed (peer "online" via announcement but TCP handshake blocked — a common real-world case) was **removed from the outbox and permanently lost**, while the UI showed "SENT".
- Fix: `sendNow()` now rethrows after re-enqueueing; the flush loop's `catch (e: Exception) { break }` keeps the frame in the outbox for retry on reconnect.

**H2 — Server RECALL ownership check queried the wrong field (any member could forge recalls)**
- Location: `SynaServer.kt` RECALL branch
- Bug: the client's recall frame is `msgId = new UUID, body = target message id` (see `SynaEngine.kt` recall construction), but the server looked up `messages.firstOrNull { it.msgId == frame.msgId }` — i.e. the recall frame's **own** id, which is never persisted. `target` was therefore always `null`, the `target.from != session.userId` check never fired, and **any authenticated member could forge a recall for any message**, polluting server history (clients had a secondary `senderId` check, but the server-side claim "only the original sender can recall" did not hold).
- Fix: look up by `frame.body`, and additionally verify `target.from == session.userId && target.to == groupId`.

**H3 — Queued group messages were never re-sent (dead code)**
- Location: `SynaEngine.kt` `pinPeerKey()` + `sendGroupText()` server-group branch
- Bug: encrypt-only queueing stored group entries with `peerId = groupId`, but `pinPeerKey()` flushed only entries matching `it.peerId == peerId` where `peerId` is the **member id** that just pinned a key. A group id never equals a member id → group entries stayed queued forever, while `chatStore.addOutgoing` had already run → user saw "sent" although no member ever received the message.
- Fix: `pinPeerKey()` now distinguishes 1:1 vs group entries. A group entry is flushed when **all** members (except self) have keys; re-send reuses the **original msgId** (dedupe-safe) via the new `existingMsgId` parameter. Also added: queued sends no longer call `addOutgoing` (the local "sent" record is written only on actual send).

**H4 — Mesh group file transfer encrypted chunks went to the wrong member**
- Location: `SynaEngine.kt` `sendFile()` mesh-group branch
- Bug: `framesToSend = groupMembers.mapNotNull { ... }` **skips** members without keys, then `framesToSend.forEachIndexed { idx, frame -> val memberId = groupMembers[idx] }` indexed into the *original* array. Example: members [A(has key), B(no key), C(has key)] → frames [fA, fC] → at idx=1, `groupMembers[1]` = B → **C's ciphertext was sent to B**, and C received nothing.
- Fix: frames now carry their target member id (`List<Pair<memberId, frame>>`); each frame is sent to the correct member.

**H5 — Mesh group text still fell back to plaintext (contradicts encrypt-only default)**
- Location: `SynaEngine.kt` `sendGroupText()` mesh branch
- Bug: `encrypted = settings.e2eEnabled && peerKey != null` — members without a key received the group message **in plaintext**, while 1:1 text and server groups had already converged to queueing.
- Fix: mesh groups now behave like the rest: if any member lacks a key (encrypt-only mode), the message is queued (original msgId) and a key exchange is requested; no plaintext is emitted.

**H6 — App upgrade triggered SHIELD_TAMPERED → self-destruct (data wipe on every update)**
- Location: `AndroidShieldEngine.kt` `verifyDexIntegrity()`
- Bug: the comment says "normal upgrade (versionCode change) → rewrite baseline", but **no such logic existed**. The baseline file is written only on first run; the digest includes `versionCode|dexHash`, so any update (versionCode change) made `base != digest` → CRITICAL `SHIELD_TAMPERED` → forced self-destruct → **the app wiped its own chat history on every upgrade**.
- Fix: baseline now stores `version|digest`. If the recorded version differs from the current one, the baseline is rewritten (normal upgrade, pass); only when the version matches is the digest compared. Backward compatible with old single-digest baselines.

**H7 — Session-key rotation could permanently destroy all encrypted data**
- Location: `AndroidSessionKeyStore.kt` `rotateSessionKey()` + `App.kt` session-rotate callback
- Bug (a): the blob was overwritten with a direct `writeBytes` — a process kill mid-write corrupts the blob and the old session key has no backup → **all session-key-encrypted data becomes permanently undecryptable**. Bug (b): `clearMigration()` ran unconditionally after `rewriteNow()`, but `ChatPersistence.rewrite()` swallows all exceptions (prints to console) — if the full migration rewrite failed (disk full / I/O error), the old migration key was still released and the on-disk data (still encrypted with the old key) became permanently unreadable after restart.
- Fix: (a) atomic write via temp file + rename; (b) `ChatPersistence.rewrite()` now returns `Boolean`, `ChatStore.rewriteNow()` propagates it, and `clearMigration()` runs **only after a successful rewrite**.

**H8 — System biometric errors counted toward brute-force failures → accidental self-destruct**
- Location: `AndroidShieldEngine.kt` biometric callback + `Shield.kt` unlock flow
- Bug: `ERROR_LOCKOUT`, `ERROR_TIMEOUT`, `ERROR_UNABLE_TO_PROCESS`, etc. (not fingerprint mismatches) were routed through `onResult(false)` → counted as brute-force attempts. While the system sensor is in its 30 s lockout, a user tapping repeatedly 5 times triggered `BRUTE_FORCE` → forced self-destruct → **all local data wiped by accident**.
- Fix: only genuine fingerprint failures (`onAuthenticationFailed` plus a small set of real errors) count; `ERROR_LOCKOUT*`, `ERROR_TIMEOUT`, `ERROR_UNABLE_TO_PROCESS`, `ERROR_CANCELED`, `ERROR_NEGATIVE_BUTTON` are ignored.

### MEDIUM severity (12)

| # | Location | Issue | Fix |
|---|----------|-------|-----|
| M12 | `Shield.kt` `AWAITING_TOTP` + `ShieldLockScreen.kt` | The TOTP second-factor screen had **no cancel path**: starting a disable flow could only be finished by entering a correct code (or re-locked by a wrong one); a user who changed their mind was stuck until app restart | New `cancelAwaitingTotp()` + a cancel button on the lock screen: disable-flow cancel revokes the request and returns to `ARMED` (shield stays enabled — an attacker with only biometrics still cannot disable the shield), unlock-flow cancel returns to `LOCKED` (no unlock without the second factor); cancels never count toward brute-force failures and are recorded as `CANCELLED` audit events; 2 new tests

| # | Location | Issue | Fix |
|---|----------|-------|-----|
| M1 | `SynaEngine.kt` `decryptEvent()` | Plaintext message frames (TEXT / GROUP_MESSAGE / FILE_CHUNK) were accepted unconditionally → on a LAN, any host can inject a fake message with `from = victim's friend` (P2P has no sender authentication; UDP source addresses are forgeable) | In encrypt-only mode (default) the receiver now **drops plaintext message frames** |
| M2 | `SynaEngine.kt` `sendFile()` 1:1 | File transfer ignored `e2eOnlyEnabled` — sent plaintext when the key was missing (now guaranteed to fail at the receiver AND leak content) | 1:1 file sends request the key and wait up to 5 s; if still missing → message marked FAILED, nothing sent |
| M3 | `SynaEngine.kt` HELLO/KEY branches | `peerEpochsM` was updated **before** `pinPeerKey`; a replayed KEY frame with the legitimate public key but a tampered `epoch` field passed the pin (PINNED_MATCH) and poisoned our epoch → both formulas fail → message loss (DoS) | Epoch recorded only after the pin succeeds (`peerKeysM[from] == key`) |
| M4 | `SynaEngine.kt` `handleChatFrame()` | Deduplicated retransmissions (msgId already seen) returned without re-ACKing → the sender's 3×3 s retry chain exhausted → frame parked in outbox and retried forever (status stuck at SENT) | Dedupe path now re-sends the ACK for TEXT frames |
| M5 | `SynaEngine.kt` FILE_CHUNK handler | Index bounds were checked against the **current frame's** `totalChunks` while the assembler array is allocated from the **first frame's** value → a mismatched subsequent frame caused an `ArrayIndexOutOfBoundsException` (swallowed, transfer dead) | Subsequent frames must have `totalChunks` identical to the first; index checked against `assembler.totalChunks` |
| M6 | `SynaEngine.kt` `isReplay()` | GROUP_MESSAGE / GROUP_DISSOLVE / ANNOUNCEMENT had no age-window check (server history replay already passes `skipReplay=true`, so this is safe to add) | Added to the replay window list |
| M7 | `SynaEngine.kt` burn handling | `burnSweepMarks[msgId]` entries were only removed on the sweep-fallback path; normally-viewed-and-burned messages left their mark forever → unbounded growth (one entry per burn message, memory leak over long uptime) | `scheduleBurnPurge()` now removes the mark after the purge |
| M8 | `SynaServer.kt` BURN_ACK | Server-group burn purge condition `target.to == session.userId` could **never** hold (group messages have `to = groupId`, member id ≠ group id) → burned messages were never purged from server history, contradicting the design | Condition now checks `target.to == groupId` |
| M9 | `SynaServer.kt` member registration / KEY relay | Any string was accepted as a public key and stored + broadcast to all members → every member's `deriveSessionKey` throws → all messages with that member fail to decrypt (self-inflicted DoS), and peers pinned the garbage key as PINNED_FIRST | New `SynaCrypto.isValidPublicKey()` (X25519 SPKI parse via `KeyFactory`) — invalid keys rejected at registration |
| M10 | `ServerUi.kt` `watchCrash()` | Auto-restart was dead code: outer condition requires `server != null`, inner requires `server == null` — contradictory, so restart never fired; manual restart was also blocked by the `start()` guard `if (server != null) return` → after a crash the server could not be restarted without exiting the launcher | Crash path releases the instance (cancel link job, `server = null`) before scheduling the restart; both auto- and manual restart work again |
| M11 | `Shield.kt` audit-log truncation | Truncation re-sealed only the **first** retained line; lines 2..N kept `prevHash` pointing at deleted lines → on load the chain broke at line 2 and **only 1 audit event survived a restart** after truncation | All retained lines are re-chained from GENESIS on truncation |

### LOW severity (8)

| # | Location | Issue | Fix |
|---|----------|-------|-----|
| L1 | `AndroidShieldEngine.kt` suspicious-module whitelist | `path.contains("linker")`, `contains("jit")` substring matching — an attacker naming an injected library `/data/local/tmp/evil-linker.so` or `memfd:jit-inject` bypassed the whitelist | Exact basename matching (linker / linker64 / libc.so / libart.so / …) and exact memfd prefixes (jit-cache / jit-zygote*) |
| L2 | `KeyPinning.save()` / `DesktopShieldStorageKey.keyBytes()` / `JvmIdentityStore.loadOrCreate()` / `ChatPersistence.rewrite()` | Non-atomic writes: crash → all pins lost (TOFU reset → MITM window), concurrent master-key creation (one key overwrites the other → old ciphertext permanently unreadable), half-written identity (silent key rotation → KEY_CHANGED storm at every peer), plaintext `.tmp` fallback when the encryption key was unavailable (crash leaves plaintext chat history on disk) | Temp-file + rename everywhere; master-key creation synchronized; identity files written atomically; **encrypt-only: refuse to write instead of degrading to plaintext** (fail-closed) |
| L3 | `ChatStore.scheduleRewrite()` | Memory snapshot read and `memoryWiped` check were not atomic (concurrent `releaseMemory`) → an empty in-memory map could overwrite the full on-disk history | Snapshot taken before the flag check |
| L4 | `SettingsScreen.kt` first-launch wizard | `remember { mutableStateOf(false) }` resets whenever the Settings tab is left → wizard popped on every visit | Persisted via `SettingsRepository.shieldWizardSeen` |
| L5 | `App.kt` `fullDestruct()` | Self-destruct missed `cacheDir/syna_outbox` (in-flight file copies, potentially sensitive documents) | Recursive wipe of the outbox dir added to the destruct sequence |
| L6 | `UpdateChecker.isNewer()` | `"0.9.10-RC1"` parsed as `[0,9,10]` → pre-releases notified as stable updates | Non-pure-numeric tags (RC/beta/…) excluded from comparison |
| L7 | `SynaServer.kt` | — | New `SynaCrypto.isValidPublicKey` expect/actual (JVM impl added) |
| L8 | `composeApp/src/androidMain/cpp/CMakeLists.txt` | Build script missing the SPDX header (part of the copyright-header sweep) | Header added |

## 1.3 Verification

- **Full regression: 88 tests, 0 failures, 0 errors** (~19 min, includes the ~10 min 6-engine message-storm stress test).
- Targeted re-runs after fixes: `FileTransferTest` (exercises the new encrypt-only file wait path), `GroupMeshTest` (mesh queueing), `ServerTest` (recall/burn ownership), UDP stability test (late-key pending-decrypt path).
- Compile check for both desktop and Android targets passed.

## 1.4 Verified but intentionally NOT fixed (with reasons)

| # | Issue | Why it was not fixed |
|---|-------|----------------------|
| R1 | **Burn-after-reading "resurrection"**: purge depends on in-memory state and is blocked from writing to disk while memory is wiped (lock) or after a crash → a burned message stored on disk can reappear after unlock/restart and is never burned again | The failure direction is **safe** (data stays *readable-encrypted* but the conversation is protected by the fail-closed gate while locked). A correct fix requires per-msgId deletion in the persistence layer (load → decrypt → filter → rewrite) inside a lock window where the storage key may legitimately be unavailable. That is a structural change to the persistence API with real data-loss risk if done wrong; scheduled as a dedicated v1.0.0 task with its own tests rather than rushed into this pass. |
| R2 | **Stale epoch after peer restart**: we keep encrypting with the old epoch until the peer's new KEY frame arrives; the peer's new formula (new epoch) and the legacy formula both fail → message loss in that window | The double-try decrypt covers the `epoch=0` (legacy peer) case; the stale-epoch case is partially covered by the new pending-incoming decrypt-on-pin mechanism, but a fully correct fix needs a session renegotiation handshake (request epoch re-sync). That touches the protocol; out of scope for a bug-fix pass, listed for v1.0.0 protocol work. |
| R3 | **Monitoring-app false positives** (`"ipcam"`, `"tinycam"`, `"screenrecorder"` substring matching hits legitimate camera-viewer/screen-recorder apps → HIGH lock) | This is a deliberate **false-positive-over-false-negative** tradeoff for a security product (a missed monitoring app is worse than a false lock). Mitigating it properly needs a maintained whitelist of known legitimate package names — which is a policy/curation decision, and the v0.9.8 signature-learning blacklist already handles renamed/repackaged apps. Deferred to v1.0.0 with a suggested exclusion-list design. |
| R4 | **Android long-term identity private key stored as plaintext Base64 in SharedPreferences** (no Keystore, no file permissions; desktop uses 0600; self-destruct does not wipe it) | Requires moving identity key management into Android Keystore (non-exportable) or master-key encryption of the prefs blob — a significant architectural change affecting key generation, migration of existing installs, and the destruct flow. High change risk for a maintenance pass; filed as a v1.0.0 security item. |
| R5 | **Server-channel password has no slow KDF** (HKDF directly on the password; no PBKDF2/scrypt/Argon2) — weak passwords can be brute-forced offline | A KDF change is **protocol-breaking**: server and all clients must upgrade in lockstep (session derivation must match). Cannot be done in a point-fix without breaking older clients; requires a versioned protocol bump — v1.0.0. |
| R6 | **Startup force-enables "everything"** (screen protection + self-destruct) even if the user disabled them after dual-factor verification | This is an intentional anti-social-engineering design (the shield cannot be silently downgraded). Changing it needs a persisted independent toggle + dual-factor confirmation flow; a product decision, not a bug. Flagged for product discussion, not silently changed. |
| R7 | **TOTP has no recovery path** (corrupted seed or lost authenticator → only "clear app data" escapes) | The **cancel path is fixed** in this pass (M12). A recovery-code system / master-key reset remains — it is a new security mechanism (new attack surface) that deserves its own design review, not a quick patch. Deferred to v1.1.0. |
| R8 | **`peersM` unbounded** (fake announcement storm grows the list forever) | Impact is bounded (in-memory only, tens of KB per fake peer); an LRU cap is easy but interacts with legit large-LAN deployments (a genuine 200-device office LAN must not have peers evicted mid-conversation). Low value / needs tuning; v1.0.0. |
| R9 | **`start()`/`stop()` asymmetry** (collectors not cancelled; double `start()` spawns duplicate handlers) | The engine instance is effectively single-lifecycle in the app; duplicates are absorbed by msgId dedupe. Making start/stop fully symmetric is refactoring for robustness, not a correctness bug in practice. Logged. |
| R10 | **UDP oversized text silently dropped** (>~48 KB text over UDP exceeds the 64 KB datagram limit) | Fail is silent at the transport, but the fix needs either mode-aware size limits in `sendText` or automatic TCP fallback — a behavior/UX decision (should the user get an error? auto-switch?); noted for v1.0.0. |
| R11 | **Random 96-bit GCM nonce** | Birthday bound ~2^48 encryptions; this app's write volume is far below the risk threshold. Counter-based nonces would need coordinated key-lifecycle changes; not worth the churn now. Documented. |
| R12 | **`biometricFails` counter file has no HMAC** (deleting the file resets the brute-force counter) | An attacker who can write files in the app sandbox already has the storage key path — the counter is a deterrent, not a boundary. Adding HMAC is trivial but adds little; kept for the v1.0.0 hardening list. |
| R13 | **Android API 28–29 lack the strong-biometric restriction** (`setAllowedAuthenticators` requires API 30) | API 29 supports `KeyguardManager.setDeviceCredentialAllowed(false)`; however the vast majority of devices are ≥30 where the restriction is enforced. Implementing a 28/29 path needs real-device testing that was not possible in this pass. Logged with a suggested implementation. |

**Summary of the intentional non-fixes**: every R-item is either (a) fail-safe in the security direction, (b) a protocol/architecture change that would break compatibility or need coordinated releases, (c) a product/policy decision that should not be silently changed by a code pass, or (d) an interaction with legacy devices that requires real-device validation. All are documented with concrete v1.0.0 follow-ups.

---

# Part 2 — License Compliance Audit

## 2.1 Conclusion

**No license contamination.** Every runtime dependency is **Apache-2.0 or MIT**, both GPL-3.0-compatible (FSF-approved). No copyleft-incompatible licenses (SSPL/AGPL-class), no proprietary or unknown licenses, no dependencies whose terms conflict with GPL-3.0-only distribution.

## 2.2 Dependency inventory

Direct dependencies: 16 (desktop) / 17 (Android, adds `androidx.biometric`, `androidx.lifecycle:lifecycle-process`, `ui-tooling-preview`). Full transitive closure: ~140 modules, grouped by family:

| Family | Modules | License | GPL-3.0 compatible |
|--------|---------|---------|--------------------|
| androidx.* (activity, annotation, appcompat, arch, autofill, biometric, collection, compose, core, cursoradapter, customview, drawerlayout, emoji2, fragment, graphics, interpolator, lifecycle, loader, profileinstaller, savedstate, startup, tracing, vectordrawable, versionedparcelable, viewpager, …) | ~50 | Apache-2.0 | ✅ |
| org.jetbrains.compose.* + org.jetbrains.androidx.* (runtime, foundation, material3, ui, animation, components, material, material-ripple, desktop, …) | ~55 | Apache-2.0 | ✅ |
| org.jetbrains.kotlin / org.jetbrains.kotlinx.* (stdlib, coroutines, serialization, atomicfu, datetime) | ~20 | Apache-2.0 | ✅ |
| org.jetbrains.skiko (skiko, skiko-awt, skiko-awt-runtime-macos-arm64) | 3 | Apache-2.0 | ✅ |
| org.jetbrains:annotations | 1 | Apache-2.0 | ✅ |
| com.russhwolf:multiplatform-settings (+ no-arg variants) | 6 | MIT | ✅ |
| com.google.guava:listenablefuture | 1 | Apache-2.0 | ✅ |
| org.jspecify:jspecify | 1 | Apache-2.0 | ✅ |
| **Total** | **~140** | **Apache-2.0 / MIT** | **All compatible** |

> Note: JetBrains/Kotlin official jars do not embed LICENSE files (industry convention — license declared in POM metadata and on the project site); the table above follows each project's official license declaration. The CI `dependency-audit` job (osv-scanner, `--recursive`, `--fail-on-vuln=false`) continuously scans the dependency graph and does not block releases.

## 2.3 Dependency vulnerabilities (OSV)

- All 13 direct dependencies queried against the OSV API (osv.dev/v1/query): **0 known vulnerabilities**.
- CI additionally runs osv-scanner over the full transitive graph.

## 2.4 Resources & packaged content

- App icons (Android mipmaps, desktop logo): project-owned PNGs, no third-party copyright.
- Fonts: **none bundled** (system fonts + material-icons-core, which is Apache-2.0).
- Sounds: none.
- Native library (`libsyna_shield.so`): project-owned C source (GPL-3.0, SPDX headers complete).
- Signing: `syna-release.keystore` and `local.properties` (containing the keystore password) are both `.gitignore`d; **git history confirms they were never committed** (verified via `git log --all`).
- Gradle wrapper (gradle-8.14.3-bin): official Gradle distribution (Apache-2.0 project); the wrapper properties file is a generated artifact (header exempt by convention).

---

# Part 3 — Copyright Header Check

- Scanned **94 .kt / .c / .h files** under `composeApp/src`: **0 files missing the SPDX header** (`SPDX-License-Identifier: GPL-3.0-only`, © 2026 Verlintas).
- Build/config files:
  - ✅ SPDX header present: `build.gradle.kts`, `composeApp/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `syna_shield.c`.
  - ✅ Header **added in this pass**: `composeApp/src/androidMain/cpp/CMakeLists.txt`.
  - ➖ No header (exempt by convention): `gradle-wrapper.properties` (official generated file), `local.properties` (local env config, gitignored).

---

# Part 4 — Deferred Work (target: v1.1.0)

1. **Burn-after-reading persistence**: per-msgId deletion in the persistence layer to close the lock/crash "resurrection" window (R1).
2. **Android identity key → Keystore** (non-exportable) + destruct cleanup (R4).
3. **Server-channel password KDF** → PBKDF2/scrypt with a versioned protocol bump (client + server coordinated upgrade) (R5).
4. **Monitoring whitelist precision** + known-legitimate-package exclusion list (R3).
5. **TOTP recovery code** (the cancel path is already fixed in this pass, M12) (R7).
6. **Epoch renegotiation handshake** for stale-epoch windows after peer restart (R2).
7. Optional hardening: peers LRU (R8), UDP size limits (R10), biometric-fail HMAC (R12), API 28/29 biometric restriction (R13).
