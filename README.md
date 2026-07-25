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
- ConnectBot's libvterm-backed Compose terminal component
- Password and imported OpenSSH private-key authentication
- Explicit confirmation and persistence for unknown host keys
- Default blocking for changed host keys
- PTY creation, raw ordered output, keyboard input, and resize propagation
- A foreground service and visible disconnect notification
- Docker-based OpenSSH fixture with password and generated Ed25519-key auth
- Unit tests for session transitions, host-key decisions, and credential wiping
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

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

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

## Project status

This is a deliberately playful proof of concept, not a production SSH client.
The specification files describe later phases, but Phase 1 should not begin
until a developer has completed the real-device/emulator checklist in the ADR.

## License

Apache License 2.0. See [LICENSE](LICENSE).
