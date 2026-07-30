# Phase 3 seamless raw fallback (2026-07-30)

## Scope

Phase 3 did not begin with a new terminal implementation. Five of its seven
deliverables were already established by the dependency spike and preserved
through the structured-transcript work:

- the raw terminal and transcript consume one SSH channel and PTY;
- `TerminalBridge` owns one process-lifetime terminal emulator;
- every original PTY byte reaches that emulator unchanged and in order;
- manual terminal switching, return to transcript, and remote resize work; and
- the terminal continues updating while its composable is absent.

The missing product behaviors were an automatic but non-authoritative
interactive suggestion and a mobile extra-key row. The first slice implemented
and proved the suggestion; the completion slice added the keys and proved the
full Phase 3 exit.

## Detection boundary

Interactive detection runs only on bytes attributed to the active command
between Threadline's output-start and end lifecycle markers. It does not inspect
the cleaned terminal snapshot, command text, or raw bytes outside that command.
The original stream still goes first to the persistent terminal; detection
changes neither terminal input nor output.

The existing incremental transcript collector already recognizes complete CSI
sequences across arbitrary SSH buffer splits and caps a pending escape sequence
at 4,096 bytes. When it completes a CSI sequence, the new detector records the
first strong hint:

- alternate-screen enablement (`47`, `1047`, or `1049`);
- absolute cursor positioning (`H` or `f`);
- common mouse-tracking enablement; or
- bracketed-paste enablement (`2004`).

SGR styling, private-mode disablement, and unrelated control sequences do not
produce a hint. Detection is advisory: a program can emit one of these
sequences without needing input, and an interactive program can emit none of
them. The model therefore stores a typed `InteractiveTerminalHint`, while the
UI says only:

> This command may need interactive input.

Threadline never switches modes or injects an answer automatically. Open
terminal is an explicit action.

## Same-session handoff

The running command card passes Open terminal back to
`ConnectedSessionScreen`, which changes only its saveable presentation mode.
The already-live `TerminalBridge` emulator is composed in place of the
transcript. The top-bar Transcript action reverses that state change without
reconnecting, restarting the command, clearing the terminal, or discarding the
card.

The raw-terminal composable is injectable in the focused Compose test so the
test can prove the state transition without constructing a second terminal
emulator. Production continues to use the process-owned emulator from
`SessionRuntime`.

## Mobile terminal input

The raw view now keeps the normal Android IME and adds a horizontally scrollable
row for Ctrl, Alt, Esc, Tab, arrows, Home, End, Page Up, Page Down, and Delete.
Ctrl and Alt are one-shot modifiers owned by the process-lifetime
`TerminalBridge`, so they apply equally to the next IME callback or extra-key
press and then reset. Entering or leaving the raw view also clears them.

The encoder maps single-byte Ctrl input to the corresponding control character,
prefixes Alt input with Escape, and uses conventional xterm modifier parameters
for navigation keys. A multi-byte IME callback is copied intact instead of being
mistaken for a single Ctrl character. Ctrl and Alt can still be combined.

## Acceptance evidence

Plain-JVM tests prove:

- alternate-screen detection across every possible two-chunk split;
- typed cursor-addressing, mouse-tracking, and bracketed-paste hints; and
- no hint for ordinary ANSI styling or alternate-screen disablement;
- Ctrl and Alt character encoding, including combined modifiers and multi-byte
  input safety; and
- unmodified and xterm-modified terminal-key sequences.

The focused API 35 Compose test proves that an active hinted card offers Open
terminal, the action opens the raw surface, and Transcript returns to the same
card and suggestion. A second focused Compose case exposes every extra key,
shows one-shot modifier selection, sends a navigation key, and observes the
modifier reset. Two device tests exercise the real `TerminalBridge` input
callback and verify one-shot consumption plus stale-modifier clearing.

The credential-gated production Android fixture test then launched
`less /etc/services`, `top`, and
`vim -Nu NONE -n -i NONE /tmp/threadline-vim-proof` through the structured
command wrapper. The real ConnectBot SSH adapter and `SessionManager` observed
an interactive hint from each program's PTY output, sent `q`, `q`, and
Escape-`:q!`-Enter through the same input queue and PTY, and received the
ordinary Threadline completion marker with exit 0 for every command. The
complete production fixture test passed in 2.737 seconds.

Afterward, `test`, `lint`, `assembleDebug`, and the routine connected Android
suite passed. All 22 routine device tests were green; the production fixture
case skipped in that routine run because its password argument was deliberately
absent.

## Phase 3 boundary

The detector may later add carefully bounded command heuristics or
waiting-for-input timing, but neither is required to claim certainty. Manual
terminal switching remains available when no hint appears.

The Phase 3 exit is complete: `less`, `top`, and `vim` were each used and exited
without reconnecting. Phase 4 is security and persistence. Deferred
transcript-hardening work remains tracked separately.
