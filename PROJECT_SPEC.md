# Threadline — Product and Technical Specification

> Working title. Rename freely.
>
> **Product:** A transcript-first, touch-native SSH client for Android with a real terminal as a seamless fallback.
>
> **Status:** Exploratory MVP specification
>
> **Primary implementation target:** Native Android, Kotlin, Jetpack Compose

---

## 1. Product thesis

Most Android SSH clients optimize for faithfully reproducing a desktop terminal on a phone.

Threadline optimizes for **getting command-line work done on a phone**.

The default experience resembles a messaging or notebook interface:

- The user writes a command in a normal Android text composer.
- The command is submitted to a persistent remote shell.
- Output streams into a discrete command card.
- The card records status, duration, exit code, and the working directory.
- Long output can be collapsed, copied, searched, or rerun.
- When a command becomes genuinely interactive, the same live session opens in a conventional terminal surface.

The terminal is not removed. It is demoted from the entire product to an escape hatch.

### One-sentence pitch

> An Android SSH client where commands feel like messages, output feels like responses, and a full terminal appears only when needed.

---

## 2. Intended user

The MVP is for technical users who already understand SSH and shell commands:

- DevOps, SRE, platform, infrastructure, and application engineers
- Homelab and self-hosting operators
- Raspberry Pi, Home Assistant, NAS, and small-server users
- Developers checking deployments, logs, services, containers, disk space, or configuration
- Anyone who occasionally needs to fix or inspect something before reaching a laptop

This is not initially a beginner shell, a server dashboard, or a replacement for a workstation.

---

## 3. Design principles

1. **Transcript first**
   Ordinary command execution is represented as persistent, discrete turns rather than an undifferentiated terminal scrollback buffer.

2. **Real shell underneath**
   Commands run in a persistent remote shell with a PTY. `cd`, `export`, aliases, functions, virtual environments, and other shell state must persist.

3. **Raw terminal is always available**
   The user can switch to the terminal at any time without starting a new SSH connection or losing the running process.

4. **Touch-native composition**
   Command entry uses a normal multiline Android editor with selection handles, clipboard, voice input, and familiar cursor movement.

5. **Do not fake semantic certainty**
   The app may infer that a program is interactive, but it must always provide a manual terminal switch.

6. **Secure by default**
   Host keys are verified. Private key material is protected. Secrets are not logged. No cloud service is required.

7. **Small MVP**
   Do not turn the first version into Termius, Termux, an IDE, a monitoring platform, or an AI agent.

---

## 4. MVP experience

### 4.1 First launch

The user sees a host list and an action to add a host.

A host contains:

- Display name
- Hostname or IP address
- Port, default `22`
- Username
- Authentication method
- Optional startup directory
- Optional shell preference
- Optional “keep active in background” preference

Supported authentication for MVP:

- Password
- Imported private key
- Newly generated Ed25519 key, when supported by the selected SSH library

### 4.2 First connection

1. Resolve and connect to the host.
2. Show the server host-key algorithm and fingerprint.
3. Require explicit acceptance for an unknown host key.
4. Store the accepted key.
5. Block the connection if a previously accepted key changes.
6. Authenticate.
7. Start a PTY-backed shell.
8. Install a temporary, session-scoped shell integration function.
9. Show the transcript screen.

Never implement “accept all host keys.”

### 4.3 Transcript screen

The primary session screen contains:

- Top bar:
  - Host display name
  - Connection state
  - Current working directory
  - Button to open raw terminal
- Transcript:
  - Command cards in chronological order
  - Streaming output
  - Status and metadata
- Composer:
  - Multiline text editor
  - Send button
  - Command history button
  - Compact shortcut row for common shell characters and controls

Suggested shortcut row:

- `Tab`
- `Ctrl`
- `Esc`
- `/`
- `|`
- `-`
- Up-history
- Down-history

Do not reproduce a full desktop keyboard.

### 4.4 Command card

Each submitted command creates one card.

A card contains:

- Exact submitted command
- Working directory at submission
- Start time
- Running, succeeded, failed, interrupted, disconnected, or unknown status
- Elapsed time
- Exit code when known
- Rendered output
- Truncation indicator when output exceeds local limits
- Actions:
  - Stop
  - Copy command
  - Copy output
  - Edit and rerun
  - Rerun
  - Open at this point in raw terminal, where practical
  - Delete from local history

Output rules:

- Preserve line breaks and monospaced alignment.
- Render common ANSI SGR styles.
- Correctly handle carriage-return progress updates.
- Make URLs tappable.
- Detect obvious absolute paths and `file:line[:column]` references as future-enhancement hooks.
- Collapse very long output behind a summary.
- Never execute links or paths automatically.

### 4.5 Interactive fallback

The app must support two paths into raw terminal mode:

1. **Manual:** the user taps the terminal button.
2. **Suggested:** the app detects terminal behavior associated with an interactive or full-screen program and offers to switch.

Possible signals:

- Alternate-screen buffer enablement
- Cursor-addressing sequences
- Mouse-tracking enablement
- Bracketed-paste mode changes
- No command-completion marker while the process is awaiting input
- Known command heuristics such as `vim`, `nano`, `less`, `top`, `htop`, `tmux`, or a REPL

Heuristics must not be treated as authoritative.

Raw mode must:

- Use the same SSH channel and PTY.
- Continue receiving the exact same byte stream.
- Support resize events.
- Expose Ctrl, Alt, Esc, Tab, arrows, and common terminal keys.
- Return to transcript mode without terminating the process.
- Preserve terminal state while its UI is not visible.

### 4.6 Background behavior

An active SSH session may continue when the app is backgrounded only through an Android foreground service with a visible notification.

The notification should show:

- Connected host
- Whether a command is running
- Disconnect action
- Return-to-session action

If there is no active connection, no foreground service should run.

A user setting may choose whether an idle connection remains alive or disconnects when the app backgrounds. The MVP may begin with a conservative fixed policy, but it must never silently promise indefinite background execution.

---

## 5. Explicit non-goals for MVP

Do not implement these before the core transcript interaction is proven:

- SFTP or file browser
- SCP
- Port forwarding
- Mosh
- Local Android shell
- Docker or Kubernetes-specific UI
- Server metrics dashboard
- Snippet marketplace
- Cloud sync
- Team sharing
- Shared credential vault
- AI command generation or explanation
- Voice-driven operations beyond standard Android dictation
- Multiple simultaneous visible sessions
- Split panes
- Plugin system
- Remote agent installation
- Web application or iOS client
- Full POSIX shell parsing
- Perfect semantic understanding of arbitrary TUI programs

Nested SSH should still work as terminal data, but transcript boundaries beyond the first shell are not an MVP requirement.

---

## 6. Technical architecture

### 6.1 Recommended stack

- Kotlin
- Jetpack Compose and Material 3
- Kotlin coroutines and `Flow`
- Room for local metadata and transcript persistence
- Android Keystore for protecting app encryption keys
- A foreground service for live background SSH sessions
- ConnectBot `sshlib` as the first SSH-library candidate
- ConnectBot `termlib` as the first raw-terminal candidate

Both ConnectBot libraries are Android-oriented, actively maintained as of 2026, and Apache-2.0 licensed.

Fallback SSH candidate:

- SSHJ, if a dependency spike finds ConnectBot `sshlib` unsuitable

Avoid directly embedding Termux terminal libraries unless the entire project intentionally adopts GPL-3.0-compatible distribution terms.

### 6.2 Dependency spike before main implementation

Create a short-lived spike branch that proves:

1. Connect to an OpenSSH server.
2. Verify and persist a host key.
3. Authenticate with password and an Ed25519 key.
4. Request a PTY.
5. Start a shell.
6. Send and receive raw bytes.
7. Render the live stream through ConnectBot `termlib`.
8. Resize the PTY from an Android layout change.
9. Keep the connection alive in a foreground service.
10. Cleanly disconnect without leaked threads.

If ConnectBot `sshlib` blocks the spike, repeat only the SSH portion with SSHJ and record the reason in an architecture decision record.

Do not build the full UI until this spike passes.

### 6.3 High-level components

```text
Compose UI
├── Host list and editor
├── Transcript session screen
├── Raw terminal screen
└── Settings

Session domain
├── SessionController
├── CommandQueue
├── CommandLifecycleTracker
├── ShellIntegration
├── TranscriptCollector
└── InteractiveModeDetector

Transport
├── SshClientAdapter
├── HostKeyVerifier
├── AuthenticationProvider
├── PtyShellChannel
└── KeepAliveController

Persistence
├── HostRepository
├── CredentialRepository
├── KnownHostRepository
├── SessionRepository
└── TranscriptRepository

Android runtime
├── SshSessionService
├── NotificationController
└── KeystoreCipher
```

### 6.4 Suggested package layout

Start with one Gradle application module. Use packages, not premature Gradle-module decomposition.

```text
com.example.threadline
├── app
├── core
│   ├── model
│   ├── security
│   ├── ssh
│   ├── session
│   └── terminal
├── data
│   ├── db
│   ├── host
│   ├── credential
│   └── transcript
├── feature
│   ├── hosts
│   ├── session
│   ├── terminal
│   └── settings
└── service
```

Split modules only after build time, ownership, or dependency boundaries justify it.

---

## 7. Session model

### 7.1 Session state machine

```text
Disconnected
    ↓ connect
Resolving
    ↓
Connecting
    ↓
VerifyingHostKey
    ↓
Authenticating
    ↓
StartingShell
    ↓
Ready
    ├── submit command → RunningCommand
    ├── open raw mode → RawInteractive
    └── disconnect → Disconnecting
RunningCommand
    ├── command completed → Ready
    ├── terminal behavior → RawInteractive
    ├── cancel requested → Cancelling
    └── connection lost → Failed
RawInteractive
    ├── command completed and user returns → Ready
    ├── user remains raw → RawInteractive
    └── connection lost → Failed
```

The UI must render from one immutable session state model and send events to a state holder. Avoid networking logic inside composables.

### 7.2 Core models

```kotlin
data class HostProfile(
    val id: HostId,
    val name: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val credentialId: CredentialId,
    val startupDirectory: String?,
)

data class SessionSnapshot(
    val id: SessionId,
    val hostId: HostId,
    val connectionState: ConnectionState,
    val currentDirectory: String?,
    val activeCommandId: CommandId?,
    val mode: SessionMode,
)

data class CommandTurn(
    val id: CommandId,
    val sessionId: SessionId,
    val command: String,
    val directoryAtStart: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
    val exitCode: Int?,
    val status: CommandStatus,
    val output: CommandOutput,
)

data class CommandOutput(
    val plainText: String,
    val styledRuns: List<StyledRun>,
    val truncated: Boolean,
    val byteCount: Long,
)
```

Exact types may change. Keep stable IDs and separate persisted entities from UI models.

---

## 8. Shell integration protocol

### 8.1 Why shell integration exists

A PTY provides a stream of terminal bytes, not structured command records. The app needs reliable boundaries for:

- Command accepted
- Output started
- Command completed
- Exit status
- Current working directory

Modern terminals solve similar problems with shell-emitted OSC escape sequences. Threadline should use the same general approach while adding a random per-session nonce to reduce accidental marker collisions.

### 8.2 Requirements

The protocol must:

- Work in a persistent shell.
- Preserve state changes such as `cd` and `export`.
- Allow multiline commands.
- Survive markers split across arbitrary network reads.
- Avoid displaying protocol markers.
- Associate every event with a command ID.
- Carry a random session nonce.
- Leave the raw byte stream usable by the terminal emulator.
- Fail gracefully into raw mode if bootstrap fails.

### 8.3 Temporary shell function

For Bash and compatible shells, install a session-scoped function similar to:

```sh
__threadline_run() {
    __tl_id=$1
    __tl_command=$2

    printf '\033]777;threadline;%s;start;%s\007' "$__THREADLINE_NONCE" "$__tl_id"
    printf '\033]777;threadline;%s;output;%s\007' "$__THREADLINE_NONCE" "$__tl_id"

    eval "$__tl_command"
    __tl_exit=$?

    printf '\033]777;threadline;%s;end;%s;%s;%s\007' \
        "$__THREADLINE_NONCE" \
        "$__tl_id" \
        "$__tl_exit" \
        "$PWD"

    return "$__tl_exit"
}
```

This is illustrative, not production-ready shell code.

The Android client invokes it with:

- A generated command UUID
- The user command encoded using a rigorously tested shell single-quote function

The quoting function must safely transform any command without a NUL byte into one shell word. Test embedded quotes, newlines, backslashes, Unicode, command substitutions, and here-documents.

### 8.4 Shell support tiers

MVP:

1. Bash
2. Zsh in a Bash-compatible invocation path
3. Generic POSIX-ish shell best effort

Later:

- Fish
- PowerShell over SSH
- Nushell
- Windows `cmd.exe`

If bootstrap cannot be installed, show:

> Structured transcript unavailable for this shell. Raw terminal mode is still available.

Never prevent access merely because semantic integration failed.

### 8.5 Standard sequence compatibility

The parser should be designed so OSC 133 command-boundary support can be added later. OSC 133 conventionally represents prompt start, command start, output start, and command completion with an optional exit code.

Do not make standards support a blocker for the MVP’s nonce-scoped protocol.

---

## 9. Byte-stream processing

Every byte received from the PTY should flow through a single ordered pipeline.

```text
SSH PTY bytes
    ├── Protocol marker scanner
    ├── Transcript collector
    ├── Interactive behavior detector
    └── Raw terminal emulator
```

### 9.1 Protocol marker scanner

Responsibilities:

- Incrementally parse OSC sequences.
- Handle escape sequences split across buffers.
- Validate the session nonce.
- Emit typed lifecycle events.
- Remove recognized Threadline markers from transcript-visible output.
- Pass unknown OSC sequences through unchanged to the terminal.
- Enforce a maximum marker length to prevent unbounded memory use.

### 9.2 Transcript collector

The collector is not a full terminal emulator.

It should initially support:

- UTF-8 decoding across buffer boundaries
- LF
- CR and CRLF
- Backspace
- Tab expansion for display
- ANSI SGR foreground/background/style runs
- Repeated carriage-return line replacement used by progress output

If it detects terminal operations it cannot represent faithfully, it should:

- Preserve raw bytes for the terminal.
- Mark the transcript rendering as approximate.
- Suggest raw terminal mode.

### 9.3 Raw terminal emulator

The terminal emulator receives the exact stream in order, including output that arrived while raw mode was not visible.

The terminal must therefore be a persistent session model, not a composable created only when the raw terminal screen opens.

### 9.4 Backpressure and output limits

The UI must not append one Compose state update per byte or line.

Use buffered batches and a bounded update cadence.

Suggested constraints for the first implementation:

- Batch transcript UI updates.
- Cap persisted output per command.
- Retain a larger in-memory tail for active commands.
- Write large output incrementally.
- Clearly mark truncation.
- Never let one runaway command exhaust device memory.

Exact thresholds should be constants with tests, not scattered magic numbers.

---

## 10. Sending input and cancellation

### 10.1 Transcript-mode submission

When ready:

1. Create a `CommandTurn` in `Running` state.
2. Generate a command ID.
3. Shell-quote the command.
4. Write the wrapper invocation and newline to the PTY.
5. Disable duplicate submission until the shell confirms start or a timeout occurs.
6. Stream output into that card.
7. Complete the card when the end marker arrives.

Only one transcript command may be active per shell session in MVP.

### 10.2 Interactive input

When raw mode is open, keyboard and control-key input writes directly to the PTY.

When a transcript command appears to be waiting for input, the UI should offer:

> This command may need interactive input. Open terminal.

Do not inject guessed answers.

### 10.3 Stop action

The first Stop action sends `Ctrl-C` (`0x03`) to the PTY.

If the command does not complete, provide a second explicit action:

> Disconnect session

Do not silently send escalating signals whose consequences the user cannot see.

---

## 11. Persistence

### 11.1 Room entities

Suggested entities:

- `HostEntity`
- `CredentialMetadataEntity`
- `KnownHostEntity`
- `SessionEntity`
- `CommandTurnEntity`
- `OutputChunkEntity`

Store output in chunks rather than repeatedly rewriting one large text field.

### 11.2 Retention

MVP behavior:

- Persist host profiles.
- Persist known host keys.
- Persist command transcript metadata.
- Persist bounded output history.
- Provide clear-all and per-session deletion.
- Provide an ephemeral-session option that does not retain transcript data after disconnect.

Never store:

- Plaintext passwords
- Private-key passphrases
- Decrypted private keys
- Authentication responses
- Full debug packet dumps in release builds

---

## 12. Credential and host-key security

### 12.1 Private keys

Imported private keys should be encrypted at rest using an app-generated symmetric key protected by Android Keystore.

Recommended pattern:

1. Generate an AES key in Android Keystore.
2. Encrypt imported key bytes with authenticated encryption.
3. Store ciphertext, nonce/IV, format metadata, and public-key fingerprint in app-private storage.
4. Decrypt only for the shortest practical authentication window.
5. Zero temporary byte arrays where feasible.
6. Never place key material in logs, exceptions, analytics, or clipboard.

If direct use of an Android Keystore asymmetric key is supported by the SSH library, prefer it for newly generated keys. Imported OpenSSH keys will generally require encrypted app storage unless the library can use platform key handles directly.

### 12.2 Passwords

For the first exploratory build, password authentication may be session-only.

If password persistence is added:

- Encrypt it using Android Keystore-backed authenticated encryption.
- Require an explicit “save password” choice.
- Support optional biometric/device-credential gating.
- Never expose the password through general app state or logs.

Implementation decision (2026-07-31): passwords and passphrases remain
session-only. Device-credential and biometric gating are deferred optional
hardening decisions, not Phase 4 exit blockers. Biometrics require a concrete
threat-model justification before reconsideration; see `docs/BACKLOG.md`.

### 12.3 Known hosts

Store:

- Hostname
- Port
- Key algorithm
- Public host key or canonical fingerprint
- First-seen timestamp
- Last-seen timestamp

Unknown key:

- Display and require acceptance.

Changed key:

- Block by default.
- Show old and new fingerprints.
- Require deliberate replacement.
- Do not reduce this to a generic retry dialog.

---

## 13. UI sketches

### 13.1 Host list

```text
┌──────────────────────────────────────┐
│ Threadline                       +   │
├──────────────────────────────────────┤
│ home-server                          │
│ ross@10.0.10.10                  ›   │
│                                      │
│ production-api                       │
│ deploy@example.com               ›   │
└──────────────────────────────────────┘
```

### 13.2 Transcript

```text
┌──────────────────────────────────────┐
│ ‹ home-server       /srv/api     >_  │
├──────────────────────────────────────┤
│ $ docker compose ps                  │
│ /srv/api · 0.8s · exit 0             │
│                                      │
│ NAME       STATUS       PORTS         │
│ api        running      0.0.0.0:8080 │
│ postgres   running      5432          │
│                         Copy  Rerun   │
├──────────────────────────────────────┤
│ $ journalctl -u api -n 50             │
│ /srv/api · running                    │
│                                      │
│ Jul 24 ... listening on :8080         │
│ Jul 24 ... request completed          │
│                                      │
│                         Stop  Terminal│
├──────────────────────────────────────┤
│ type a command…                 Send │
│ Tab  Ctrl  Esc   /   |   -   ↑   ↓  │
└──────────────────────────────────────┘
```

### 13.3 Interactive suggestion

```text
┌──────────────────────────────────────┐
│ This command is using the terminal   │
│ screen and may need keyboard input.  │
│                                      │
│         Stay here   Open terminal    │
└──────────────────────────────────────┘
```

---

## 14. Accessibility and mobile behavior

- All primary actions must have content descriptions.
- Respect system font scaling where practical.
- Do not rely only on color for success and failure.
- Support portrait first.
- Landscape should be usable, especially in raw terminal mode.
- Preserve composer text across rotation and process recreation.
- Use Android clipboard APIs deliberately.
- Make copying command and output separate actions.
- Support hardware keyboards without changing the touch-first design.
- Avoid tiny tap targets and hover-dependent behavior.

---

## 15. Error handling

Errors should be domain-specific and actionable.

Examples:

- DNS resolution failed
- Connection timed out
- Host key unknown
- Host key changed
- Authentication rejected
- Unsupported private-key format
- Shell bootstrap unavailable
- Connection lost while command was running
- Android stopped the background session
- Output was truncated locally
- Terminal renderer failed but SSH session remains connected

Never display only a raw exception class when a user-facing interpretation is available.

A diagnostics panel may expose sanitized technical details for bug reports.

---

## 16. Testing strategy

### 16.1 Unit tests

Required:

- Shell single-quote encoder
- OSC marker parser with every possible buffer split
- Marker maximum-length handling
- Nonce validation
- UTF-8 decoder across buffer boundaries
- CR progress-line behavior
- ANSI SGR conversion
- State-machine transitions
- Output truncation
- Known-host matching
- Credential encryption round trip
- No-secret logging tests

### 16.2 Integration fixture

Provide a Docker Compose test fixture containing:

- OpenSSH server
- Test user
- Password authentication
- Ed25519 key authentication
- Bash
- Zsh if inexpensive
- Commands that generate:
  - stdout
  - stderr
  - nonzero exit
  - ANSI color
  - carriage-return progress
  - no final newline
  - Unicode
  - high-volume output
  - delayed output
  - interactive input
  - alternate screen

Note: a PTY generally merges stdout and stderr. The MVP transcript should not claim to separate them.

### 16.3 Command test matrix

At minimum:

```sh
pwd
cd /tmp
pwd
export THREADLINE_TEST=works
printf '%s\n' "$THREADLINE_TEST"
false
printf 'without newline'
printf '\033[31mred\033[0m\n'
for i in 1 2 3; do printf '\rstep %s' "$i"; sleep 1; done; echo
printf 'unicode: π 日本語 🚀\n'
printf 'quote: %s\n' "'"
sh -c 'echo error >&2; exit 7'
yes line | head -n 100000
read -r -p 'name: ' name; echo "hello $name"
less /etc/services
top
vim
```

Also test:

- Multiline shell blocks
- Pipes and redirects
- Command substitutions
- Here-documents
- `sudo` password prompt
- Network interruption
- App rotation
- App background/foreground
- Process recreation
- Reconnect after remote reboot

### 16.4 UI tests

Critical flows:

1. Add host.
2. Accept first host key.
3. Connect.
4. Run successful command.
5. Run failed command.
6. Confirm `cd` persists.
7. Edit and rerun.
8. Open raw terminal during `less`.
9. Send Ctrl-C.
10. Background with active command and return.
11. Detect changed host key.
12. Clear transcript.

---

## 17. Milestones

### Phase 0 — Repository and dependency spike

Deliverables:

- Android project builds from command line.
- Compose host screen placeholder.
- Architecture decision record for SSH and terminal libraries.
- OpenSSH Docker fixture.
- Raw PTY connection renders in terminal.
- Password and key authentication proven.
- Host-key verification proven.
- Foreground-service lifecycle proven.

Exit criterion:

> A developer can connect to the fixture, use a raw shell, rotate the phone, background briefly, return, and disconnect cleanly.

### Phase 1 — Structured command lifecycle

Deliverables:

- Session state machine
- Temporary shell integration bootstrap
- Safe command quoting
- Nonce-scoped OSC parser
- Command start/end events
- Exit status
- Current working directory
- One active command at a time
- Unit and integration tests

Exit criterion:

> `cd`, `export`, success, failure, multiline input, and fragmented markers all work reliably in one persistent shell.

### Phase 2 — Transcript UX

Deliverables:

- Command cards
- Streaming output
- ANSI SGR rendering
- CR progress updates
- Duration and status
- Copy, edit, rerun, stop
- Output truncation
- Command history
- Composer state preservation

Exit criterion:

> Common operational commands are more comfortable in transcript mode than in a conventional phone terminal.

### Phase 3 — Seamless raw fallback

Deliverables:

- Same-session raw terminal
- Persistent terminal model
- Automatic interactive suggestion
- Manual switch
- Resize
- Extra-key row
- Return to transcript

Exit criterion:

> `less`, `top`, and `vim` can be entered from transcript mode and used without reconnecting.

### Phase 4 — Security and persistence

Deliverables:

- Room persistence
- Encrypted imported keys
- Known-host management
- Ephemeral sessions
- Sanitized diagnostics
- Retention controls
- Changed-key blocking

Exit criterion:

> The app can be handed to a technical alpha tester without knowingly unsafe defaults.

Implementation status (2026-07-31): the required deliverables are implemented.
Device-credential and biometric gating remain optional backlog decisions rather
than phase exit criteria.

### Phase 5 — Alpha polish

Deliverables:

- Accessibility pass
- Connection and authentication error UX
- Performance profiling
- Large-output tests
- Samsung/Pixel device testing
- Basic onboarding
- Exportable sanitized bug report
- Signed internal APK

Exit criterion:

> Ten real users can perform small remote tasks for two weeks and provide useful product feedback.

Implementation status (2026-08-01): the accessibility and
connection/authentication-error pass is implemented, including typed network
failures, recovery actions, screen-reader semantics, and 200% font-scale action
reachability. Production-path large-output profiling now covers styled Unicode
volume, long lines, progress rewrites, sustained-output interruption, bounded
memory, and post-load recovery. A Galaxy S25 Ultra running Android 16 / One UI
8.5 has passed the physical lifecycle, large-font, raw-terminal, persistence,
and manual TalkBack checks; Pixel-specific validation remains. Exportable
sanitized diagnostics were completed during Phase 4.

---

## 18. Product validation

Do not initially measure success by downloads.

The first useful questions are:

- Did users reach for the app instead of postponing the task until they had a laptop?
- Which commands stayed in transcript mode?
- Which commands immediately forced raw mode?
- Did users understand session state and current directory?
- Did cards make logs and failures easier to inspect?
- Did users trust credential and host-key handling?
- Did they return after the novelty wore off?

Suggested alpha interview prompt:

> Tell me about the last time you used Threadline. What were you trying to accomplish, and what would you have done without it?

Avoid leading with “Did you like the card interface?”

---

## 19. Risks

### Risk: Shell integration is less universal than expected

Mitigation:

- Bash-first MVP
- Visible compatibility status
- Reliable raw fallback
- Never block connection on failed bootstrap

### Risk: Transcript output is inaccurate for terminal-heavy commands

Mitigation:

- Limited, explicit renderer scope
- Detect unsupported control behavior
- Preserve exact bytes in raw terminal
- Mark approximate output
- Switch early rather than pretending

### Risk: Android background restrictions terminate sessions

Mitigation:

- Foreground service with visible notification
- Clear lifecycle messaging
- No promise of invisible indefinite execution
- Test on major OEM devices

### Risk: Credential handling undermines trust

Mitigation:

- No cloud
- Keystore-backed encryption
- Strict known-host handling
- Public security design
- Minimal analytics, preferably none in MVP
- External review before broad release

### Risk: Dependency libraries are immature or difficult to integrate

Mitigation:

- Mandatory Phase 0 spike
- Adapter interfaces around SSH and terminal dependencies
- Record fallback decision before building product code

### Risk: Scope balloons

Mitigation:

- Treat every feature not required by Phases 0–3 as deferred
- No AI, SFTP, dashboards, sync, or collaboration before validation
- Keep one session and one active transcript command in MVP

---

## 20. Definition of MVP complete

The MVP is complete when all of the following are true:

- An Android user can create a host and connect securely.
- The user can run commands from a normal multiline composer.
- Shell state persists between commands.
- Each command appears as a discrete card with streaming output.
- Success, failure, duration, and current directory are visible.
- The user can copy, edit, rerun, and interrupt commands.
- The same live session can switch into a functional raw terminal.
- Interactive programs remain usable.
- Host-key changes are blocked.
- Imported keys are encrypted at rest.
- Large output does not crash or freeze the app.
- The app contains no required cloud backend.
- The core flows are covered by automated tests.
- The project has a reproducible build and test fixture.

Everything else is post-MVP.

---

## 21. Technical references

These are starting points, not mandates:

- Android Developers: Jetpack Compose architecture, state, and state hoisting
- Android Developers: Android Keystore
- Android Developers: foreground services and long-running work
- ConnectBot `sshlib`
- ConnectBot `termlib`
- ConnectBot application source
- OSC 133 shell-integration conventions used by modern terminals
- SSHJ as a fallback SSH implementation

When implementation begins, pin dependency versions through Gradle version catalogs and record the chosen versions in the repository. Do not blindly paste versions from this document.
