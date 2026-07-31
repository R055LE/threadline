# Threadline

Threadline is an exploratory, transcript-first SSH client for Android. The
product idea is that commands should feel like messages and output should feel
like responses, while a real terminal remains underneath for interactive work.

**Phase 4: security and persistence is implemented.** The app opens on a
deliberately plain command transcript with a saved multiline composer,
streaming command cards, bounded ANSI-aware output, lifecycle status,
interactive-terminal suggestions, and one-tap access to the same persistent raw
terminal with mobile modifier and navigation keys.

## What the prototype contains

- Native Kotlin, Jetpack Compose, and Material 3 app in one Gradle module
- ConnectBot's coroutine SSH library behind a narrow adapter
- A bundled Conscrypt provider with an Android Ed25519 capability probe and
  modern ECDSA/RSA-SHA2 compatibility fallback
- ConnectBot's libvterm-backed Compose terminal component
- Password and imported OpenSSH private-key authentication, with an explicit
  option to save keys under Android Keystore-backed AES-GCM encryption and to
  rename or confirmation-delete saved records
- Explicit Room-backed save, select, update, copy, and confirmation-delete for
  non-credential host profiles
- Explicit confirmation and Room persistence for unknown host keys
- Default blocking for changed host keys
- Trusted-server listing with fingerprints and timestamps, confirmation-gated
  forgetting, and no one-tap changed-key replacement
- Bounded Room-backed transcript history with a default 20-session retention
  window, newest-50-turn session snapshots, chunked 65,536-character output tails,
  confirmation-gated per-session deletion, and clear-all
- An explicit ephemeral-session option that never hands commands or output to
  the transcript archive
- A selectable, bounded diagnostic preview that redacts host fields,
  usernames, directories, commands, output, credentials, and host-key material
  by default, with explicit opt-in only for host fields, directories, and recent
  command text before Android sharing
- Idempotent migration from the dependency spike's private known-host
  preferences without allowing stale trust to replace a Room record
- PTY creation, raw ordered output, keyboard input, and resize propagation
- A foreground service and visible disconnect notification
- Docker-based OpenSSH fixture with password and generated Ed25519-key auth
- Unit tests for session transitions, host-key decisions, credential wiping,
  safe shell quoting, bootstrap generation, and the incremental marker parser
- Android tests for the exact Ed25519 decode/sign/verify path, selective
  connection-form retention, Room migration, and Android Keystore tamper
  detection
- A bounded, ordered input queue so rapid IME and paste events cannot reorder
  bytes on the SSH channel
- A bounded incremental transcript collector for UTF-8, line controls,
  repeated-carriage-return progress, and ANSI SGR style runs
- Immutable, session-local command turns with batched streaming updates,
  live duration, status, exit code, directory, truncation, and approximation
  state
- A multiline command composer and neutral command cards with one-shot stop,
  delayed explicit disconnect, Older/Newer command history with draft
  restoration, copy, edit, rerun, output collapsing, selectable output,
  confirmed HTTP(S) links, and raw-terminal switching
- Advisory detection of alternate-screen, cursor-addressing, mouse-tracking,
  and bracketed-paste control sequences with an explicit same-session terminal
  handoff
- A horizontally scrollable terminal key row with one-shot Ctrl and Alt plus
  Esc, Tab, arrows, Home, End, Page Up, Page Down, and Delete
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
key** in the app, and select it through Android's file picker. Check **Save
encrypted on this device** before connecting to retain an encrypted copy. A
saved key appears by name and public fingerprint on later connections; its
passphrase, if any, must still be entered for each connection and is never
saved. Saved-key labels can be renamed without decrypting or re-encrypting the
credential. Delete shows the key fingerprint for confirmation, removes only
that encrypted local record, and does not revoke its public key on a server.

Connection details can be saved explicitly as a host profile. A profile stores
only its name, hostname, port, username, stable ID, and timestamps. Selecting
one fills those fields and clears any password, private-key passphrase, saved
key choice, or pending file import. Authentication mode and credentials are not
linked to profiles. Edit the fields and choose **Update profile**, or choose
**Use as new** to preserve the fields while creating a separate profile.

Accepted host keys appear under **Trusted servers** with their algorithm,
fingerprint, first-trusted time, and last-verified time. **Forget** removes only
that trust decision after confirmation. A changed key is still blocked without
prompting: deliberate replacement requires forgetting the exact old record,
reconnecting, verifying the newly presented fingerprint through a trusted
channel, and explicitly accepting it as unknown.

Completed transcript sessions are retained locally by default. **Saved
transcripts** opens selectable plain-text history, including command status,
exit code, and truncation notices. Threadline retains at most 20 sessions, the
newest 50 turns in each saved session, the first 16,384 characters of each
command, and the last 65,536 characters of plain output per turn. Saved output
does not retain ANSI styling and does not create active links. Per-session delete and clear-all are both
confirmation-gated; they are logical SQLite deletion, not guaranteed forensic
erasure.

Transcript commands and output may themselves contain sensitive values. The
history database is local and excluded from backup and device transfer, but it
is not encrypted. Select **Ephemeral session** before connecting when commands
and output should not be archived after disconnect. A finalized saved session
survives database reopen; an abrupt process kill can still lose the current
unfinished session because archive writes happen at session finalization.

## Architecture

```text
Compose host/terminal UI
        │ events + immutable StateFlow
        ▼
SessionManager ─────────────── Foreground SshSessionService
        │
        ├── StrictHostKeyGate ── Room known-host store
        ├── Imported-key store ─ Room ciphertext + Android Keystore AES key
        ├── Host-profile store ─ Room connection metadata
        ├── Transcript store ─── Room sessions, turns, and output chunks
        ├── SshClientAdapter ─── ConnectBot sshlib
        └── TerminalBridge ───── ConnectBot termlib/libvterm
```

`TerminalBridge` is process-owned rather than composable-owned. It receives the
PTY byte stream even while the Activity is absent. The foreground service owns
the live-session policy, and its destruction cancels or closes session jobs.
Passwords and private-key passphrases are not persisted. A private key is
memory-only unless the user explicitly saves it; saved keys are persisted only
as authenticated ciphertext plus non-secret format and public-fingerprint
metadata.

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

The output-interaction follow-up keeps rendered command output selectable while
underlining only conservative HTTP(S) detections. Remote text remains plain,
untrusted text: unsupported schemes, malformed or credential-bearing URLs, and
oversized candidates stay inert. Tapping a detected link first shows the exact
address in a confirmation dialog; only an explicit Open action hands it to
Android, and a missing handler becomes a visible error rather than a crash.

Phase 3 builds mostly on already-proven infrastructure: the raw terminal uses
the same SSH channel and PTY, its emulator remains process-owned while hidden,
manual switching and return already work, and terminal measurements already
resize the remote PTY. The first missing slice added bounded, incremental
interactive hints for strong terminal control sequences. A running card says
only that the command *may* need input and requires an explicit Open terminal
action.

The completion slice adds a mobile extra-key row. Ctrl and Alt are explicit
one-shot modifiers shared by normal IME input and the row; they clear after the
next input and whenever the raw view is entered or left. Navigation keys use
conventional xterm modifier parameters, and Ctrl never rewrites a multi-byte
IME callback. A production Android fixture run launched real `less`, `top`, and
`vim` in sequence, observed an interactive hint for each, sent their exit input
through the same manager and PTY, and received exit 0 for every structured
command in 2.737 seconds. JVM tests, lint, the debug build, and all 22 routine
device tests also pass. The boundary and acceptance evidence are recorded in the
[Phase 3 investigation](docs/investigations/2026-07-30-seamless-raw-fallback.md).

Phase 3 is complete. Phase 4 is security and persistence. Deferred transcript
hardening still includes history deletion and retention controls, user-scrolled
streaming behavior, and focused rotation and background-transition checks.

The first Phase 4 slice establishes a versioned Room database and exported
schema for known-host trust. Records contain the normalized endpoint, exact key
algorithm and bytes, and first-seen plus last-trusted-seen timestamps. Existing
accepted keys migrate idempotently from the Phase 0 private preference store;
an older legacy record can never overwrite a Room decision. Database failures
block verification with a typed, non-secret error, and coroutine cancellation
is preserved.

Five focused API 35 tests prove Room read/write, monotonic last-seen behavior,
legacy import, stale-record non-overwrite, changed-key immutability, and
survival across database reopen. The production Android fixture accepted the
real OpenSSH key into Room, completed the existing session suite, disconnected,
and reconnected from the stored key without a second approval in 3.755 seconds.
The full gate then passed with all 27 routine device tests green. See the
[Phase 4 persistence investigation](docs/investigations/2026-07-30-room-known-host-persistence.md).

The encrypted-key slice advances the database to schema version 2 and adds an
explicit save/use path for imported private keys. A non-exportable app-scoped
AES-256 key in Android Keystore protects each record with AES-GCM, a fresh IV,
and authenticated metadata binding the record ID, format, public fingerprint,
and crypto version. Metadata lists do not load ciphertext, and decrypt happens
on an I/O dispatcher immediately before the existing self-clearing session
credential is prepared. Missing Keystore material, modified ciphertext, and
modified metadata all fail closed.

API 35 tests cover the exact Room 1→2 migration, encryption randomness,
ciphertext and metadata tampering, missing Keystore material, encrypted-store
round trips, and saved-key UI/passphrase handling. The production fixture
matched the imported Ed25519 fingerprint to `ssh-keygen`, closed and reopened
Room, decrypted with Android Keystore, authenticated through the production SSH
adapter, ran a structured proof command, and confirmed the credential bytes
were zeroed after authentication. See the
[encrypted imported-key investigation](docs/investigations/2026-07-31-encrypted-imported-private-keys.md).
The final JVM/lint/debug/release gate passed, followed by 33 green routine API
35 tests; the two credential-gated fixture cases skipped in that routine run as
designed.

The saved-key management follow-up adds label-only rename and explicit local
deletion beside each fingerprinted record. Rename leaves ciphertext and its IV
unchanged. Delete removes exactly one Room row, leaves the app-scoped wrapping
key available to every other and future record, and clears the selected key and
its memory-only passphrase when applicable. Storage failures remain typed and
surface only non-secret messages. Deletion is logical database deletion; it is
not represented as secure physical erasure of prior SQLite pages.

Room, encrypted-store, and Compose tests cover single-record isolation,
ciphertext preservation, unavailable-after-delete behavior, confirmation, and
selection/passphrase cleanup. The complete API 35 run finished 38 tests with
only the two expected credential-gated cases skipped. Both credential-gated
production fixture cases then passed in 5.478 seconds, including encrypted-key
authentication after a database reopen. See the
[saved-key management investigation](docs/investigations/2026-07-31-saved-key-management.md).

The host-profile slice advances Room to schema version 3 with stable,
explicitly managed connection records. Profile selection fills only display
name, hostname, port, and username while wiping session-only credential inputs;
neither authentication mode nor a credential reference enters the table.
Update targets one stable ID, **Use as new** breaks that association before a
new save, and delete confirms the exact endpoint without changing known-host
trust or encrypted private keys. Low-level writes become typed, non-secret
errors and preserve coroutine cancellation.

The exact Room 2→3 migration preserves an encrypted-key row. Store tests cover
normalization, targeted update/delete, sanitized failures, and survival across
a database reopen; Compose covers save, select, update, explicit delete, and
credential clearing. The complete API 35 run finished 43 tests: 41 passed and
the two credential-gated cases skipped as designed. Both production fixture
cases then passed against OpenSSH in 4.795 seconds. See the
[host-profile persistence investigation](docs/investigations/2026-07-31-host-profile-persistence.md).

The known-host management follow-up exposes Room trust metadata without
exposing raw encoded keys to Compose. Each record shows its normalized
endpoint, algorithm, canonical SHA-256 fingerprint, and trust timestamps.
Forgetting is exact-row and confirmation-gated. Changed-key errors now identify
the endpoint and explain the multi-step replacement ceremony; the verifier
still refuses to prompt or mutate trust when a changed key is presented.

Room and Compose tests prove sorted metadata, shared fingerprint calculation,
record isolation, missing-record failure, unchanged blocking before deletion,
and a fresh unknown-host decision after deletion. The complete API 35 run
finished 46 tests: 44 passed and the two credential-gated cases skipped as
designed. Both production fixture cases then passed against OpenSSH in 5.602
seconds. See the
[known-host management investigation](docs/investigations/2026-07-31-known-host-management.md).

The transcript-persistence slice advances Room to schema version 4 with
session, turn, and output-chunk tables. A connected session archives at most
once when it ends; failed connection attempts, empty/raw-only sessions, and
explicitly ephemeral sessions create no history. Saved data is capped at 20
sessions, 50 turns per session, 16,384 characters per command, and a
65,536-character output tail per turn. Unicode-safe 16 KiB chunks avoid
repeated large-field rewrites, while transactional pruning and foreign-key
cascades keep retention and deletion atomic. The viewer renders restored output
as selectable, inert plain text.

JVM tests prove durable, ephemeral, and archive-failure lifecycle boundaries.
Migration, Room, and Compose tests prove schema 3→4 preservation, database
reopen, ordered Unicode-safe chunks, every cap, exact deletion, clear-all, and
the UI confirmations. The complete API 35 run finished 52 tests: 50 passed and
the two credential-gated cases skipped as designed. Both production fixture
cases then passed against OpenSSH in 5.219 seconds; the password-auth path also
archived and restored its real structured turns, bounded 65,536-character
large-output tail, and interrupted status. The final JVM, lint, debug, and
release gate passed. See the
[bounded transcript persistence investigation](docs/investigations/2026-07-31-bounded-transcript-persistence.md).

The sanitized-diagnostics slice exposes stable error/state codes, app and
device compatibility fields, transcript/output-size summaries, and local record
counts without serializing raw exceptions or session content. Its sensitive
detail switch is off by default and warns before adding bounded host fields,
directories, and the newest 20 commands. Command output, credentials, private
keys, host keys, and fingerprints cannot enter either report form. The exact
selectable preview is the only text sent to Android's share chooser; no report
is saved or submitted automatically. See the
[sanitized diagnostics investigation](docs/investigations/2026-07-31-sanitized-diagnostics.md).

JVM and Compose tests cover hostile redaction samples, every typed session
error, report bounds, explicit opt-in, preview-to-share identity, and visible
share failure. The complete API 35 run finished 56 tests: 54 passed and the two
credential-injected cases skipped as designed. Both live Docker/OpenSSH cases
then passed in 5.094 seconds. The final JVM, lint, debug, and release gate also
passed.

Phase 4's required deliverables are now implemented. Device-credential and
biometric gating are optional decisions recorded in the
[backlog](docs/BACKLOG.md), not Phase 4 blockers. Biometrics require a concrete
threat-model justification before reconsideration. Passwords and passphrases
remain session-only.

## License

Apache License 2.0. See [LICENSE](LICENSE).
