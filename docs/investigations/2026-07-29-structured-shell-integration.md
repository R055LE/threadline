# Structured shell integration proof

- Date: 2026-07-29
- Phase: 1 — Structured command lifecycle
- Status: Complete — Phase 1 exit criterion met

## Goal

Prove that Threadline can recover command boundaries, exit status, and current
directory from one persistent PTY shell without changing the exact byte stream
delivered to the raw terminal.

## Protocol contract

Each SSH session creates a random 128-bit nonce encoded as 32 lowercase
hexadecimal characters. Each command uses a protocol-safe UUID. The temporary
Bash integration emits these BEL-terminated OSC sequences:

```text
ESC ] 777;threadline;<nonce>;start;<command-id> BEL
ESC ] 777;threadline;<nonce>;output;<command-id> BEL
ESC ] 777;threadline;<nonce>;end;<command-id>;<exit-status>;<pwd> BEL
```

The parser also accepts the standard `ESC \` string terminator. It recognizes
only Threadline markers carrying the current session nonce and a valid command
ID. Recognized markers become typed lifecycle events and are omitted from
transcript-visible bytes. Wrong-nonce, unknown, malformed, incomplete-at-EOF,
and oversized sequences pass through unchanged.

Parser output is one ordered list containing transcript-byte segments and
lifecycle events. Keeping them in one list matters when several markers and
command output arrive in the same SSH read. The original unmodified SSH bytes
remain the input to the raw terminal; filtered bytes are for the future
transcript collector only.

## Shell bootstrap and command quoting

The Bash bootstrap installs a nonce-derived temporary function in the existing
interactive shell. It runs a no-op command through that function as its
handshake, so bootstrap success uses the same start, output, and end events as
every later command.

User command text is passed to the function as exactly one POSIX shell word.
The quoting rule wraps the value in single quotes and represents each embedded
single quote as a close, escaped quote, and reopen sequence. NUL is rejected
because it cannot exist in a shell argument. The function deliberately uses
`eval` after reconstructing that one argument so `cd`, exports, aliases,
functions, pipelines, redirects, substitutions, and multiline syntax execute
inside the persistent shell.

## Evidence

Local unit tests cover:

- every possible split point within a marker;
- multiple markers in one buffer and byte/event ordering;
- partial, wrong-nonce, malformed, oversized, and unknown OSC sequences;
- BEL and `ESC \` terminators;
- UTF-8 splits, carriage-return progress, ANSI styles, and 200 KB of output;
- empty commands, quotes, backslashes, newlines, Unicode, pipes, redirects,
  substitutions, a here-document, and surrounding whitespace; and
- nonce-scoped bootstrap and invocation generation.

On 2026-07-29, all three `FixtureIntegrationTest` cases ran against the
loopback Docker OpenSSH fixture with zero skips or failures. The structured
case proved, in one SSH channel and PTY:

- bootstrap start, output, and successful end events;
- `cd /tmp` changed the end marker's current directory to `/tmp`;
- an exported variable remained available to the following command;
- a successful state-reading command returned exit status 0;
- `false` returned exit status 1; and
- a multiline assignment and `printf` completed successfully.

`SessionManager` unit tests additionally prove:

- bootstrap markers fragmented across SSH output reads reach structured
  `Ready` state;
- one accepted command blocks a second transcript submission until completion;
- start, output, exit status, and current directory update immutable
  structured-shell state;
- the exact original marker-bearing byte stream still reaches the raw terminal;
- bootstrap timeout becomes structured `Unavailable` while the SSH connection
  and raw input remain usable; and
- an exception during structured setup has the same raw-only downgrade
  behavior.

The final Android gate ran on the Pixel 6 API 35 emulator:

- all three routine connected Android tests passed;
- the production app authenticated to the loopback fixture with its disposable
  Ed25519 client key;
- the production `SessionManager` installed the temporary Bash integration,
  parsed its live SSH output, and reached structured `Ready`; and
- the Compose terminal header rendered `Structured shell ready · same PTY`.

The session then disconnected cleanly, and the fixture-only key copied to the
emulator for this check was removed.

An opt-in fourth Android test then exercised the complete production path
without UI text injection. Its runtime-only fixture password was read from the
container and passed directly to the instrumentation process without entering
source, Gradle properties, or test reports. In 0.93 seconds it:

- accepted the fixture's first-seen key through `StrictHostKeyGate`;
- connected through `ConnectBotSshClientAdapter`;
- reached structured `Ready` through the real `SessionManager`;
- rejected a second submission while `cd /tmp` was active;
- retained `/tmp` and an exported variable across later commands;
- recorded exit status 0 for successful checks and 1 for `false`; and
- completed a multiline assignment and test.

Together with the every-split-point parser tests, this satisfies Phase 1's exit
criterion for persistent `cd`, `export`, success, failure, multiline input, and
fragmented markers in one shell.

## Current limits

- Only the Bash path is implemented. Zsh and generic POSIX compatibility still
  need explicit probes and downgrade behavior.
- The current-directory field follows the Phase 1 specification and is emitted
  directly. Semicolons are supported because the parser treats the final field
  as a remainder, but a directory containing an OSC terminator control
  character cannot be represented by this version.
- The transcript collector is deliberately not a terminal emulator. Unsupported
  terminal operations mark a turn approximate, and the persistent raw terminal
  remains the exact rendering fallback.
- During a structured command, the wrapper temporarily owns the shell's INT
  trap so it can complete lifecycle reporting after Ctrl-C. A command that
  inspects or changes the INT trap will observe different semantics, and INT
  trap changes do not persist after the wrapper restores the previous trap.
  Raw terminal mode remains the compatibility fallback for that case.
- Transcript history is in memory for the current session. Persistence remains
  Phase 4 work.

## Phase 2 first vertical slice

The first Phase 2 slice now treats the structured-shell layer as a stable
boundary:

- Every PTY read still reaches the raw terminal unchanged and in order.
- The transcript path receives an ordered stream of output-byte segments and
  typed command lifecycle events with recognized Threadline markers removed.
- At most one transcript command can be active, and each accepted command has a
  stable ID, start event, end event, exit status, and resulting directory.
- Structured setup failure leaves the SSH session usable in raw-only mode.

`TranscriptCollector` incrementally decodes UTF-8 and supports LF, CR/CRLF,
backspace, eight-column tabs, repeated-CR line replacement, and ANSI SGR
standard, bright, indexed, and truecolor styles. Unsupported control sequences
are omitted from display text and mark the output approximate. Its mutable
cells and parser state stay below Compose; the UI receives immutable plain text
and offset-based styled runs.

The selected initial bounds are constants with tests:

- 131,072 rendered UTF-16 code units retained as a tail per command;
- 100 command turns retained for the current session;
- 4 KiB maximum pending display escape sequence;
- eight-column tab stops; and
- one transcript state publication per 50 ms during streaming.

`SessionManager` attributes only bytes ordered between a command's
output-start and end events. Wrapper echo, bootstrap traffic, and the next
prompt are excluded. Submission and lifecycle changes publish immediately,
while output-only changes use a conflated 50 ms channel. On completion, the
turn is published before structured shell readiness, preventing observers from
seeing a ready shell with a still-running card.

The initial Compose surface is intentionally neutral. It provides a saved
multiline composer, streaming cards, status, live and completed duration, exit
code, submission directory, ANSI rendering, long-output collapse, explicit
truncation and approximation notices, stop, copy, edit, rerun, and switching
to the same persistent raw terminal.

Unit tests cover every UTF-8 and ANSI split, line controls, CR progress,
indexed and truecolor styles, unsupported operations, truncation, lifecycle
attribution, history bounds, stop status, and protocol failure. Compose tests
prove exact multiline submission and semantic completed-card content. The live
production Android fixture test additionally proves ANSI color, repeated-CR
progress, Unicode, and successful transcript completion through
`ConnectBotSshClientAdapter` and `SessionManager`.

## Phase 2 interaction-hardening follow-up

The next slice makes active command state observable and Stop deliberately
one-shot:

- active cards recompute elapsed duration once per second;
- the first Stop records its request time and moves the turn to `Stopping`;
- repeat Stop requests do not mutate the turn or replace that timestamp;
- the card reports that the interrupt was sent; and
- after three seconds, a still-stopping card offers explicit session
  disconnect rather than sending Ctrl-C repeatedly.

The list follows the tail on first render and as turns or active output change,
but a user drag away from the bottom disables following until the user returns
to the tail. The first device test exposed a race in the original approach:
initial layout could report “not at bottom” before the first programmatic
scroll and disable following immediately. User-drag state now controls that
decision, and the Compose test checks the actual lazy-list tail sentinel before
and after adding a turn.

The collector now tracks its rendered character count incrementally and stores
lines in a deque. Trimming a large stream therefore does not repeatedly scan
every retained line or shift the complete line list. A unit test feeds 20,000
small line chunks and verifies the exact configured tail, byte count, and
truncation flag.

The production Android fixture then ran
`yes 0123456789 | head -n 20000`. The completed turn:

- retained exactly 131,072 rendered characters;
- reported a larger total byte count;
- kept the final complete line; and
- marked the earlier output truncated.

The same fixture found that sending Ctrl-C to `sleep 30` interrupted Bash's
temporary wrapper function before its end marker ran. Structured state would
otherwise remain `Running` even though the command and prompt had returned.
The wrapper now installs a temporary INT trap around the user command, records
an interrupt as exit 130, restores the shell's previous INT trap, and emits the
normal end marker. The live rerun completed the turn as `Interrupted` through
the production SSH adapter and the same PTY.

One final harness issue also surfaced: Android's instrumentation command can
exit zero while its textual result reports failed tests. The opt-in fixture
script now captures that result and exits nonzero for failures or any result
without a recognized `OK` summary.

On 2026-07-30, `test`, `lint`, `assembleDebug`, and the routine connected
Android suite passed on the API 35 emulator. The password-gated production
fixture test then passed separately in 1.76 seconds with the long-output and
Ctrl-C checks enabled.

Remaining Phase 2 work includes richer history behavior, URL interaction,
interactive-command suggestions, selection, user-scrolled streaming behavior,
rotation, and background transitions. Room transcript persistence remains
deferred to Phase 4.
