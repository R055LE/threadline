# Threadline

Threadline is an exploratory, transcript-first SSH client for Android. The
product idea is that commands should feel like messages and output should feel
like responses, while a real terminal remains underneath for interactive work.

This repository is currently **Phase 0: repository and dependency spike**.
There are no transcript cards yet. The app contains one host form and one raw,
PTY-backed SSH session so the risky dependency and lifecycle assumptions can be
tested before product UI is built.

## What the spike contains

- Native Kotlin, Jetpack Compose, and Material 3 app in one Gradle module
- ConnectBot's coroutine SSH library behind a narrow adapter
- A bundled Conscrypt provider with an Android Ed25519 capability probe and
  modern ECDSA/RSA-SHA2 compatibility fallback
- ConnectBot's libvterm-backed Compose terminal component
- Password and imported OpenSSH private-key authentication
- Explicit confirmation and persistence for unknown host keys
- Default blocking for changed host keys
- PTY creation, raw ordered output, keyboard input, and resize propagation
- A foreground service and visible disconnect notification
- Docker-based OpenSSH fixture with password and generated Ed25519-key auth
- Unit tests for session transitions, host-key decisions, and credential wiping
- Android tests for the exact Ed25519 decode/sign/verify path and selective
  connection-form retention
- A bounded, ordered input queue so rapid IME and paste events cannot reorder
  bytes on the SSH channel
- An opt-in plain-JVM smoke test that compiles the production SSH adapter and
  proves password auth, key auth, PTY resize, and byte exchange against the
  fixture

## Requirements

- JDK 17
- Android SDK Platform 37
- Docker with the Compose plugin
- OpenSSH `ssh-keygen` on the development host

## Build

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

With an emulator or device running:

```bash
./gradlew connectedDebugAndroidTest
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

Windows/WSL setup and APK-install notes are in
[the Android development guide](docs/development/windows-wsl-android.md).

## Run the SSH fixture

```bash
cd fixtures/openssh
cp .env.example .env
# Set a local-only password in .env.
./start.sh
```

The documented test username is `threadline`. The password is whatever you put
in the ignored `.env` file. The startup script also creates an unencrypted
fixture-only Ed25519 key at
`fixtures/openssh/.state/client_ed25519`.

See [the fixture guide](fixtures/openssh/README.md) for host fingerprint,
key-auth, adapter-smoke, output, shutdown, and changed-key test commands.

## Connect from an Android emulator

Use:

- Display name: `Local fixture`
- Host: `10.0.2.2`
- Port: `2222` (or `THREADLINE_TEST_PORT` from `.env`)
- Username: `threadline`
- Password: the local value from `.env`

The first connection pauses at the server fingerprint. Compare it with:

```bash
cd fixtures/openssh
docker compose exec openssh \
  ssh-keygen -lf /var/lib/threadline-ssh/ssh_host_ed25519_key.pub
```

For key auth, copy the generated private key to the emulator, choose **Private
key** in the app, and select it through Android's file picker.

## Architecture

```text
Compose host/terminal UI
        │ events + immutable StateFlow
        ▼
SessionManager ─────────────── Foreground SshSessionService
        │
        ├── StrictHostKeyGate ── SharedPreferences known-host store
        ├── SshClientAdapter ─── ConnectBot sshlib
        └── TerminalBridge ───── ConnectBot termlib/libvterm
```

`TerminalBridge` is process-owned rather than composable-owned. It receives the
PTY byte stream even while the Activity is absent. The foreground service owns
the live-session policy, and its destruction cancels or closes session jobs.
Passwords, passphrases, and imported key bytes are not persisted.

The dependency choice and its open questions are recorded in
[ADR 0001](docs/adr/0001-ssh-and-terminal-libraries.md).
The Android connection failure, isolation method, root cause, fix, and remaining
risks are recorded in
[the July 2026 investigation](docs/investigations/2026-07-27-android-ssh-connection.md).

## Project status

This is a deliberately playful proof of concept, not a production SSH client.
Password auth, imported Ed25519 client-key auth, Ed25519 host verification,
changed-host blocking, a PTY shell, ordered rapid input, ANSI color, Unicode,
carriage-return progress have now been proven on an Android 15 emulator. A
100,000-line stream also drains without a crash or lost SSH session, and the
same PTY visibly returns a follow-up marker. What first looked like a
multi-minute termlib backlog was the bottom half of a 59-row terminal hidden
behind the software keyboard. Applying the IME inset to the terminal host fixed
its PTY and Canvas size without dropping or sampling output bytes. The
specification files describe later phases, but Phase 1 should not begin until
the remaining device/emulator checklist in the ADR is resolved.

## License

Apache License 2.0. See [LICENSE](LICENSE).
