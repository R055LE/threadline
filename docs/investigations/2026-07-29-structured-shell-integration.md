# Structured shell integration proof

- Date: 2026-07-29
- Phase: 1 — Structured command lifecycle
- Status: Protocol primitives and live Bash fixture proof complete; Android
  session wiring remains

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

## Current limits

- Only the Bash path is implemented. Zsh and generic POSIX compatibility still
  need explicit probes and downgrade behavior.
- The bootstrap, parser, and live proof are not yet connected to Android's
  `SessionManager` or command state machine.
- The current-directory field follows the Phase 1 specification and is emitted
  directly. Semicolons are supported because the parser treats the final field
  as a remainder, but a directory containing an OSC terminator control
  character cannot be represented by this version.
- Transcript text collection and rendering remain Phase 2 work. This slice
  preserves ordered bytes but does not interpret terminal display operations.
