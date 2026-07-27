# Android SSH connection investigation — 2026-07-27

## Outcome

Threadline now completes a strict Ed25519-host-key SSH connection from a Pixel
9 Android 15 emulator to the Docker OpenSSH fixture. It displays the correct
SHA-256 fingerprint, requires explicit acceptance, authenticates by password,
opens a PTY-backed shell, preserves rapid input order, and renders returned
output in the raw terminal.

Two independent Android-only defects were found:

1. cbssh `0.4.1` selected Android's nominal Ed25519 implementation, then failed
   to decode the standard X.509 public-key specification used for the server
   host signature.
2. Threadline launched one coroutine per terminal input callback, allowing a
   rapid event burst to reach the SSH session out of order.

The first is fixed with a bundled Conscrypt provider, a real capability probe,
and a modern algorithm fallback. The second is fixed with one bounded,
session-bound input queue.

## What was initially observed

- The Docker fixture was healthy and listening only on host
  `127.0.0.1:2222`.
- The Android emulator could open a raw TCP connection to `10.0.2.2:2222`.
- OpenSSH logs showed Threadline reaching the server and closing before
  authentication.
- Threadline displayed only `Could not establish the SSH connection`.
- No known-host entry was written and no fingerprint dialog appeared.
- The exact production adapter's JVM fixture test passed password auth, key
  auth, PTY creation, resize, and byte exchange against the same server.

Those facts ruled out the fixture password, Docker port mapping, emulator host
alias, and the general SSH adapter flow.

## Isolation methodology

The investigation deliberately changed one layer at a time.

### 1. Prove the server without Android

The fixture was checked for:

- healthy container state;
- loopback-only port mapping;
- password authentication;
- generated Ed25519 client-key authentication; and
- expected Ed25519 and RSA host fingerprints.

### 2. Prove emulator reachability without SSH semantics

ADB verified that the emulator could reach the host alias and port. This
separated `10.0.2.2` routing from SSH negotiation.

### 3. Run the exact production adapter on a plain JVM

The `ssh-integration` module passed against the live fixture. This was useful,
but ultimately insufficient: the bug lived in Android's JCA provider behavior,
not in OpenSSH or the adapter's high-level sequence.

### 4. Use a separate diagnostic application ID

A diagnostic APK was installed beside the original app. It logged only
allowlisted exception classes and stage categories—never credentials,
hostnames, usernames, key bytes, or packet contents. The original app and its
private data were not overwritten.

### 5. A/B the negotiated host-key algorithm

The default negotiation failed with:

```text
java.security.spec.InvalidKeySpecException
```

Forcing only `rsa-sha2-512,rsa-sha2-256` reached the expected unknown-server
dialog and completed authentication and PTY creation. This proved that key
exchange, strict host verification, credentials, shell startup, and terminal
rendering worked when Ed25519 host-signature verification was removed from the
path.

### 6. Inspect the exact dependency source

cbssh tag `v0.4.1` and its current main branch were inspected.

`SshKeys.ensureEd25519Support()` checks only:

```kotlin
KeyFactory.getInstance("Ed25519")
```

and installs its fallback only for `NoSuchAlgorithmException`.

Android returned a factory for that name, so the fallback was skipped. Later,
`Ed25519SignatureAlgorithm` called `generatePublic(X509EncodedKeySpec(...))`,
which Android rejected. The distinction was not “Ed25519 exists” versus
“Ed25519 is absent”; it was “the name exists” versus “the exact operation
works.”

The upstream main branch still had the same check when inspected on
2026-07-27, and `v0.4.1` was the newest tag.

### 7. Reuse the official Android provider strategy

The current ConnectBot Android app's open-source flavor installs
`org.conscrypt:conscrypt-android` at provider position one. Threadline adopted
that supported pattern with version `2.6.1`.

Threadline then performs a real probe:

1. generate an Ed25519 key pair;
2. decode its X.509 public key through the selected `KeyFactory`;
3. sign a fixed, non-secret probe message; and
4. verify the signature through the decoded key.

If any step fails, the client offers modern ECDSA and RSA-SHA2 host keys only.
It does not offer legacy `ssh-rsa`.

An Android instrumentation test covers this exact provider path.

### 8. Repeat the full Android flow

The validation build negotiated `ssh-ed25519`, displayed the fixture's matching
fingerprint, accepted and saved it, authenticated, opened the PTY, and reached
the raw terminal.

A diagnostic attempt that spent more than 30 seconds inspecting the fingerprint
failed with `Connection timed out`. cbssh includes host-key decision time inside
its hard-coded 30-second connect timeout. A tightly timed replay succeeded.
This is a real usability/security follow-up, not an Ed25519 failure.

### 9. Exercise rapid terminal input

ADB injected a short command rapidly. Before the input fix, characters arrived
reordered (`echo` became `ehoc`). Each callback had launched an independent
coroutine on a multi-threaded dispatcher.

After replacing that fan-out with one bounded queue, the same rapid command and
its output appeared byte-for-byte in order. A unit test adds an artificial send
delay and asserts the alphabet reaches a fake SSH session in exact sequence.

## Decisions and rationale

### Keep strict host verification

At no point was an accept-all verifier considered an acceptable workaround.
Unknown keys still require explicit confirmation, and changed keys remain
blocked.

### Bundle Conscrypt instead of forcing RSA permanently

RSA-SHA2 was a useful diagnostic control and remains a safe fallback, but an
RSA-only product would fail against Ed25519-only servers and hide the actual
Android defect. Conscrypt restores the preferred path.

### Probe capability, not provider names

Provider names and advertised services are claims. The probe executes the same
class of key decode and signature operation the SSH handshake needs.

### Keep the fallback modern

If the provider cannot be installed, Threadline offers:

- ECDSA NIST P-256, P-384, and P-521; and
- RSA-SHA2-512 and RSA-SHA2-256.

It excludes Ed25519/Ed448 for that process and never adds SHA-1 `ssh-rsa`.

### Serialize input and bind queued data to a session

The queue is bounded at 256 chunks. Each queued item contains the exact
`LiveSshSession` that was active when the input was accepted, so stale input
cannot leak into a later reconnection. Queue overflow fails visibly rather than
silently dropping or reordering keystrokes.

## Operational lessons

- `127.0.0.1` is namespace-relative. In the emulator it means the emulator;
  `10.0.2.2` means the development host.
- A passing JVM integration test does not prove Android cryptography.
- A provider advertising an algorithm does not prove every required key format.
- A real fixture and algorithm A/B test can separate transport, crypto, auth,
  PTY, and UI failures quickly.
- Diagnostic builds should use a separate package ID and sanitized categories.
- Build success is not runtime success. The terminal screen and returned remote
  bytes were part of the proof.
- Rapid synthetic input is valuable even for a touch-first app because it
  approximates paste, IME composition, and hardware keyboard bursts.

## Known follow-ups

### Host-key confirmation timeout

cbssh's hard-coded 30-second connect timeout includes the time a user spends
verifying an unknown fingerprint through a trusted channel. Options:

1. propose an upstream configurable/pauseable timeout;
2. fork cbssh temporarily; or
3. redesign unknown-host handling as a first rejected probe followed by a new
   connection after acceptance.

The third avoids a fork but requires careful state-machine and changed-key
handling. Until resolved, the UI should eventually expose the timeout
specifically rather than a generic connection failure.

### Host-form persistence

Resolved in the follow-up to this investigation. The form originally owned all
of its fields, so Compose discarded them when the UI switched to connection
progress and created a new form after failure.

The non-secret display name, host, port, username, and authentication selection
are now held by the parent application UI and survive that state transition.
They are also saveable across Android state restoration. Passwords and
passphrases remain short-lived form state: they are never saveable and are
cleared as soon as a connection request is prepared. An Android Compose
instrumentation regression removes and recreates the form to verify both halves
of that policy.

### Remaining Phase 0 device checks

- Android Ed25519 client-key authentication through the file picker;
- changed-host-key block after regenerating the fixture volume;
- ANSI, Unicode, carriage-return, and high-volume visual rendering;
- PTY resize confirmed remotely after rotation;
- foreground/background terminal preservation; and
- disconnect leak/thread inspection.

## Upstream sources inspected

- cbssh `v0.4.1`
  [`SshKeys.ensureEd25519Support()`](https://github.com/connectbot/cbssh/blob/v0.4.1/sshlib/src/main/kotlin/org/connectbot/sshlib/SshKeys.kt)
  and
  [`Ed25519SignatureAlgorithm`](https://github.com/connectbot/cbssh/blob/v0.4.1/sshlib/src/main/kotlin/org/connectbot/sshlib/crypto/Ed25519SignatureAlgorithm.kt);
- cbssh
  [current-main `SshKeys`](https://github.com/connectbot/cbssh/blob/main/sshlib/src/main/kotlin/org/connectbot/sshlib/SshKeys.kt)
  for the upstream-status comparison;
- ConnectBot's OSS
  [`ProviderLoader`](https://github.com/connectbot/connectbot/blob/main/app/src/oss/java/org/connectbot/util/ProviderLoader.kt)
  and
  [version catalog](https://github.com/connectbot/connectbot/blob/main/gradle/libs.versions.toml)
  for its Android Conscrypt strategy; and
- the official
  [Conscrypt repository](https://github.com/google/conscrypt)
  for provider context and licensing.

## Security and privacy notes

- The fixture password stayed in its ignored `.env`.
- No password, passphrase, private key, raw auth payload, hostname, username, or
  packet dump was added to source or Logcat.
- Conscrypt and cbssh remain Apache-2.0 dependencies; distribution still needs a
  complete transitive-license report.
- The fixture remains loopback-bound on the host and is not exposed to the LAN.
