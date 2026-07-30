# Threadline

Threadline is an exploratory, transcript-first SSH client for Android. The
product idea is that commands should feel like messages and output should feel
like responses, while a real terminal remains underneath for interactive work.

**Phase 2: transcript UX is in progress.** The app now opens on a deliberately
plain command transcript with a saved multiline composer, streaming command
cards, bounded ANSI-aware output, lifecycle status, and one-tap access to the
same persistent raw terminal.

## What the prototype contains

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
- Unit tests for session transitions, host-key decisions, credential wiping,
  safe shell quoting, bootstrap generation, and the incremental marker parser
- Android tests for the exact Ed25519 decode/sign/verify path and selective
  connection-form retention
- A bounded, ordered input queue so rapid IME and paste events cannot reorder
  bytes on the SSH channel
- A bounded incremental transcript collector for UTF-8, line controls,
  repeated-carriage-return progress, and ANSI SGR style runs
- Immutable, session-local command turns with batched streaming updates,
  live duration, status, exit code, directory, truncation, and approximation
  state
- A multiline command composer and neutral command cards with one-shot stop,
  delayed explicit disconnect, Older/Newer command history with draft
  restoration, copy, edit, rerun, output collapsing, and raw-terminal switching
- An opt-in plain-JVM smoke test that compiles the production SSH and structured
  shell code, then proves auth, PTY resize, persistent state, multiline input,
  lifecycle markers, current directory, and exit status against the fixture

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
remaining rotation, background, and disconnect checks also pass, so
[ADR 0001](docs/adr/0001-ssh-and-terminal-libraries.md) is accepted and Phase 1
may proceed.

Phase 1 now has random session nonces, typed command lifecycle events, and a
bounded incremental OSC parser. Safe shell-word quoting and the temporary Bash
bootstrap have also passed the live OpenSSH fixture with persistent `cd` and
`export` state, success and failure statuses, and multiline input. The parser
preserves byte/event ordering across arbitrary buffer splits, removes only
valid same-session Threadline markers from transcript output, and passes
unknown or malformed sequences through unchanged. Android's `SessionManager`
now bootstraps this path, exposes immutable structured-shell state, enforces one
active command, records exit status and directory, and downgrades failures to
the still-connected raw terminal.

The Phase 1 exit cases—persistent `cd` and `export`, success, failure,
multiline input, fragmented markers, and one active command—pass through the
production Android adapter and `SessionManager` against the Docker fixture.
Phase 2's first vertical slice now consumes that lifecycle into bounded
session-local command turns. The collector supports incremental UTF-8, LF,
CR/CRLF, backspace, tabs, repeated-CR progress replacement, standard/indexed/
truecolor ANSI SGR runs, and explicit approximation for unsupported terminal
operations. Output publication is capped at 20 updates per second, each card
retains at most 131,072 rendered UTF-16 code units, and a session retains at
most 100 turns. The production Android fixture test proves ANSI, progress,
Unicode, and completed transcript status through the real SSH adapter and
`SessionManager`.

The next interaction-hardening slice adds once-per-second live duration,
idempotent Stop state, a three-second grace period before offering explicit
session disconnect, and reliable initial/output-follow scrolling that yields
when the user drags away from the tail. The Android fixture now also proves a
20,000-line command retains exactly the configured 131,072-character tail and
that Ctrl-C returns exit 130 and an `Interrupted` turn through the same PTY.
The Bash wrapper temporarily catches INT so it can emit the end marker, then
restores the shell's prior INT trap.

The composer/history follow-up uses the bounded session turns as its single
history source. Older navigation saves the exact unfinished draft, history
entries preserve multiline commands, and Newer returns to that draft after the
latest entry. Typing, editing a card, or accepting any submission leaves
history-navigation mode deliberately. Composer text, history position, and the
saved draft survive Android saved-state recreation.

The remaining Phase 2 work is interaction hardening rather than visual polish:
history deletion and retention controls, interactive-command suggestions, URL
handling, selection, user-scrolled streaming behavior, and device testing of
rotation and background transitions.

## License

Apache License 2.0. See [LICENSE](LICENSE).
