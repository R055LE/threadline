# Threadline milestone history

This is a compact chronology, not the current execution plan. See [STATUS.md](STATUS.md) for the
current phase and remaining work, and the [investigation index](investigations/README.md) for dated
acceptance evidence.

## Phase 0 — Repository and dependency spike

The Android prototype proved password and imported-key authentication, strict host-key handling, a
real PTY, ordered terminal output, resize propagation, rotation, foreground/background lifecycle,
and high-volume output against the Docker OpenSSH fixture. A misleading apparent terminal backlog
was ultimately an IME-inset viewport problem. The accepted dependency decision is recorded in
[ADR 0001](adr/0001-ssh-and-terminal-libraries.md).

## Phase 1 — Structured command lifecycle

Threadline added a random per-session nonce, typed command lifecycle markers, safe shell quoting,
an incremental bounded OSC parser, persistent shell state, exit status, current directory, and a
raw-terminal compatibility downgrade when bootstrap fails.

## Phase 2 — Transcript UX

The project built bounded, session-local command turns with batched streaming, ANSI SGR rendering,
carriage-return progress, approximation and truncation state, duration, stop/disconnect behavior,
history with draft restoration, copy/edit/rerun, confirmed links, and reliable tail-following that
yields to user scrolling.

## Phase 3 — Seamless raw fallback

The transcript and terminal views were proven to share one SSH channel and PTY. Incremental
interactive hints, explicit switching, resize, one-shot Ctrl and Alt, and mobile navigation keys
were accepted with live `less`, `top`, and `vim` fixture runs.

## Phase 4 — Security and persistence

Room became the explicit owner of known-host trust, encrypted imported keys, non-credential host
profiles, and bounded transcript history. The phase added exact management and confirmation
boundaries, ephemeral no-write sessions, retention controls, migration evidence, and diagnostics
whose default schema cannot contain session content or credentials. Phase 4's required deliverables
are complete; device-credential and biometric gating remain optional backlog decisions.

## Phase 5 — Alpha polish

The first slice added typed safe error presentation, recovery actions and focus behavior,
screen-reader semantics, complete terminal-key labels, and 200%-font-scale access to connected
session actions. Manual accessibility and physical-device validation, profiling, onboarding, and a
signed internal APK remain. See [STATUS.md](STATUS.md) rather than this chronology for the active
boundary.
