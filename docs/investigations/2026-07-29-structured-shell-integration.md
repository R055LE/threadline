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
- `SessionManager` exposes submission and structured lifecycle state, but the
  Compose UI remains the raw terminal. Transcript collection and command UI are
  Phase 2 work.
- The current-directory field follows the Phase 1 specification and is emitted
  directly. Semicolons are supported because the parser treats the final field
  as a remainder, but a directory containing an OSC terminator control
  character cannot be represented by this version.
- Transcript text collection and rendering remain Phase 2 work. This slice
  preserves ordered bytes but does not interpret terminal display operations.
