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
session actions. The next slice added repeatable production-path large-output profiling and fixed
an INT handler that could let builtin-only infinite loops resume after Ctrl-C or expose a startup
gap before the command became safely stoppable. A Galaxy S25 Ultra then passed physical Android 16
/ One UI 8.5 validation across lifecycle, rendering, raw fallback, large font, and manual TalkBack.
Basic onboarding then added a one-screen product explanation, contextual security and retention
guidance, a persistent completion marker, a Help reopen path, and blank production connection
defaults. Alpha packaging groundwork then fixed the release identity and version, isolated debug
data, added off-repository signing and verification tooling, and defined tester feedback without
premature telemetry. The owner-created `0.1.0-alpha.1` candidate then passed checksum, certificate,
alignment, exact installation, and API 35 cold-launch verification before its first physical
connection attempt exposed a release-only R8/JNI field-renaming crash. Alpha.1 was rejected;
alpha.2 added an explicit native field contract plus CI verification and completed a disposable-key
minified SSH and structured-command proof. The permanent-key alpha.2 then installed over physical
alpha.1 with data preserved, authenticated by password, and completed a structured command, but
opening its raw terminal exposed a second release-only native field-name contract. Alpha.2 was
rejected; alpha.3 now preserves and verifies both contracts and has passed a disposable-key
minified terminal open, raw input, resize, lifecycle, transcript-return, and follow-up-command
proof. The permanent alpha.3 then verified against the established certificate and passed the
corrected Galaxy S25 Ultra path; a running `ping` survived repeated transcript/terminal switches
and rotation in both views. That closed the release/JNI blocker while exposing a deferred
compact-height terminal-layout issue. Two encrypted off-machine signing-key copies with separately
held decryption material subsequently established the backup set. They were replaced with portable
AES-256-encrypted archives, and independent downloads, extractions, and key-identity checks passed
for both provider copies, closing backup recovery.
The permanent alpha.3 then authenticated with an encrypted saved fixture key before and after a
force-stop/reopen cycle, and its default diagnostic preview matched the documented privacy boundary.
Public CI then began producing source-identified unsigned candidates while permanent signing stayed
local. The permanent alpha.4 installed over alpha.3 as the same app on the Galaxy S25 Ultra. Its
onboarding state, profile, trusted host, transcript history, settings, and encrypted imported key
survived, and the retained key authenticated and completed `pwd` without re-import. This closed the
installed-update preservation boundary. Direct invited sharing was selected for the open Phase 5
alpha, with no public prerelease until the remaining technical-alpha use boundary is evaluated.
Alpha.5 then proved the resumable signing and in-place installation path but is
rejected because R8 relocation broke sshlib 0.4.2's bundled Ed25519 provider
before authentication. An isolated minified probe reproduced the failure and
proved the narrow keep-rule correction. The alpha.6 source now applies that rule
and adds a release mapping/DEX gate for the provider class names. Independent
CI passed, and the permanent-key alpha.6 installed over alpha.5 with retained
state intact. Password and retained imported-key authentication, Diagnostics,
structured commands, and same-session raw-terminal behavior passed on the
Galaxy S25 Ultra, making alpha.6 the accepted tester build.
Additional device and OEM coverage is opportunistic alpha evidence rather than a separate Pixel
gate. See
[STATUS.md](STATUS.md) rather than this chronology for the active boundary.
