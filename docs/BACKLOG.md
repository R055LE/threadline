# Threadline backlog

This file records deliberately deferred product and hardening decisions. Items
here are not part of the current phase exit criteria unless they are explicitly
promoted into a milestone.

## Device-credential gating

**Status:** Deferred optional hardening; not a Phase 4 blocker.

Threadline does not currently persist passwords or private-key passphrases.
Imported private keys are encrypted at rest by an app-scoped Android Keystore
key, but using one does not require a fresh device-credential challenge.

Reconsider device-credential gating only alongside a concrete saved-secret
access policy or threat model. That decision must define which asset is gated,
when reauthentication occurs, how background sessions behave, and what recovery
looks like when the platform credential is unavailable.

## Biometric gating

**Status:** Deferred optional hardening; not a Phase 4 blocker.

Biometrics are not desired where they do not address a demonstrated risk. Do
not add a biometric prompt merely because the platform supports one. Reconsider
only when a concrete threat model justifies it, and evaluate device-credential
fallback, accessibility, cancellation, lockout, reauthentication cadence, and
the effect on active SSH sessions before implementation.

The present security boundary remains explicit: passwords and passphrases are
session-only, saved private keys are encrypted at rest, Android backup and
device transfer are disabled, and no biometric or device-credential gate is
claimed.

## Expanded large-output navigation

**Status:** Deferred visual and interaction polish; not a Phase 5 performance
blocker.

Physical Samsung validation confirmed that expanding and collapsing the
retained 128 KiB transcript tail remains functional without freezing. The
expanded single text layout took roughly one to two seconds to appear, however,
and very repetitive output made position and recovery awkward.

When transcript polish becomes active work, evaluate chunked or lazy rendering,
a useful scroll-position indicator for unusually long retained output, and a
collapse action that remains reachable without scrolling to the bottom. Keep
selection, styled spans, web-link confirmation, TalkBack traversal, and the
exact bounded-tail contract intact rather than optimizing only the common
visual case.

## Transcript viewport with the software keyboard

**Status:** Closed in the signed alpha.11 build after alpha.9 and alpha.10
failed physical validation. Keep the regressions.

On the Galaxy S25 Ultra, focusing the transcript composer and sending commands
with the software keyboard open moved the useful transcript content above the
visible viewport. The composer remained usable and commands completed, but the
user had to scroll back up to recover the new cards and output. Connection,
execution, and data remained intact. The recovery scrolling is repeated
friction in the primary interaction path.

Alpha.9 made tail-following react to list viewport resizes while preserving the
IME padding that keeps the composer above the keyboard. Its first physical
check exposed a second boundary: the production activity left soft-input
adjustment unspecified, so the Galaxy S25 Ultra panned the whole window instead
of resizing the viewport. A completed first turn existed but remained outside
the visible window until the keyboard closed. The session and command remained
functional.

Alpha.10 explicitly requests resize behavior for `MainActivity`. An
instrumented contract checks the production manifest, and the real-Gboard
connected-session regression starts from an empty transcript and submits the
first turn. Its physical test confirmed that the header stayed stable and the
viewport resized, but tail-following aligned the bottom action rows of the new
card. The command, status, and output remained above the visible window until
the keyboard closed.

Alpha.11 anchors a completed turn at the start of its card. Active streamed
output still follows the transcript tail. A constrained-height regression
requires the first completed card to stay at offset 0 with its output visible;
the unchanged alpha.10 policy failed it at a 218 px offset. The raw terminal
keeps its separate IME handling.

The signed alpha.11 owner-device sequence passed. With Gboard still open, the
completed first card showed its command, success metadata, working directory,
timing, exit code, and output without a recovery scroll. Dismissing Gboard
immediately exposed the full card and actions. Existing tail-following behavior
is unchanged, including the rule that deliberate user scrolling into older
output must not be overridden.

## Responsive onboarding and edge-to-edge polish

**Status:** Deferred visual and responsive-layout polish; monitor action
reachability during alpha.

The first permanent-key alpha installed successfully on the Galaxy S25 Ultra,
but its onboarding screenshot exposed an awkward compact-height composition:
the persistent Continue action visually collides with Samsung's navigation area,
the stacked cards dominate the viewport, and a scrolled position can leave the
introductory sentence without much context. No tap failure or inaccessible
content was reported.

During the visual-design pass, revisit navigation-bar insets, the relationship
between a sticky action and scrollable content, card density, responsive spacing,
and scroll-position cues across gesture and three-button navigation, landscape,
and large font scales. If the primary action becomes untappable, obscured, or
unreadable on any supported configuration, promote that case to a functional
layout bug instead of leaving it in polish.

## Compact-height connected-session layout

**Status:** Deferred responsive-layout work; the alpha.3 session/resize behavior
is accepted, but raw-terminal visibility with a landscape software keyboard is
not a finished experience.

Permanent alpha.3 testing on the Galaxy S25 Ultra confirmed that a running
command survives transcript/raw-terminal switching and rotation in both views.
The landscape raw-terminal screenshot also showed that the connection title,
status, and action row consume nearly all height left above the software
keyboard, leaving little or no readable terminal surface. A Termius comparison
preserved roughly one prompt row by using much denser landscape chrome. That is
evidence about the constraint, not a request to reproduce its layout or style.

Keep rotation support: it is normal Android behavior, useful for wide terminal
lines, and more important on tablets, foldables, and hardware keyboards. During
the visual/responsive pass, evaluate a compact connected header, moving
secondary actions into overflow, and an optional focused or immersive terminal
presentation. Define a minimum visible terminal viewport when the IME is open,
while keeping connection state and a route back to the transcript reachable.
Portrait remains the primary phone layout.

## Drafting and queued commands while a turn runs

**Status:** Local drafting implemented and physically accepted in alpha.7;
command queue deferred.

Physical testing showed that interrupt and recovery are fast, but disabling the
composer during a running turn made the user wait before typing the likely
follow-up command. The alpha.7 source keeps the composer editable while a turn
runs, preserves the local draft through stopping, failure, raw-mode switching,
rotation, and saved-state restoration, and enables Send only when the shell is
ready again.

On the signed alpha.7 candidate, a `pwd` draft remained editable while
`sleep 10` ran, Send stayed disabled until the shell returned to ready, and the
draft then executed normally.

The implemented draft behavior remains separate from an explicit command queue.
That queue remains a larger feature and must define ordering,
visibility, reordering or removal, behavior after failure or interruption,
disconnect handling, and whether queued content is persisted. Never send a
queued command merely because the shell returned to readiness unless the UI
made that execution contract unambiguous.

## Persistent-shell exit recovery

**Status:** Isolated execution implemented and physically accepted in alpha.8;
automatic shell restart remains deferred.

Transcript Send intentionally evaluates inside the persistent Bash shell so
directory changes, exports, aliases, and functions survive between cards. That
also means a command can enable `errexit` or `nounset`, call `exit` or `exec`,
and terminate the shell before Threadline emits its completion marker.

Run isolated now executes script-like input in a child Bash process. A strict
block can fail with its real exit status while the persistent shell remains
available for the next command. Common strict-option prologues show an advisory
warning, isolated cards are labeled, and reruns preserve their execution mode.
The mode is also retained in saved transcript history. Persistent Send remains
available because changing live shell state is sometimes intentional.

The permanent-key alpha.8 build passed the physical fixture boundary: an
isolated strict failure reported exit 1, did not run the command after `false`,
did not leak its directory or exported-variable changes, and left the original
persistent state available to the next successful command.

A later recovery slice may retain the transcript and offer a fresh shell when
the persistent shell itself exits. It must say that shell state was lost. Do
not imply that a restarted shell preserved a directory, variable, function, or
process that died with the old shell.

## Responses to running commands

**Status:** Deferred interaction and security design; same-session raw terminal
input is the current path.

Plain prompts such as `read -p`, package-manager confirmations, and `sudo`
password requests do not necessarily emit terminal control sequences. The
current interactive suggestion can therefore miss them even though the raw
terminal can already send exact input to the waiting process.

A transcript-mode response control should remain separate from both the next
command draft and a future command queue. Ordinary replies may be sent as
ephemeral PTY input without entering command history. Sensitive replies need a
masked, short-lived entry path that is never persisted or copied into
diagnostics. Threadline cannot promise that a reply stays out of the transcript
if the remote program leaves terminal echo enabled or prints it back.

Prompt recognition is advisory only. Remote output is untrusted and any
program can print text resembling a password request, so Threadline must not
present a guessed prompt as authenticated `sudo` or inject a guessed answer.
Keep a manual terminal handoff reachable for every running command.

## Opt-in saved password authentication

**Status:** Deferred product and security decision; not an assumed future
feature.

Passwords are currently session-only by design. Threadline already has
app-scoped Android Keystore encryption for imported private keys, but applying
encryption to passwords would not by itself define an acceptable saved-secret
policy.

Reconsider only with an explicit opt-in design that covers the threat model,
Keystore failure and device-transfer behavior, whether device credentials are
required, how a saved password is selected and replaced, exact deletion, and
what remains visible while the device is unlocked. Do not silently turn a
saved host profile into a saved credential.

## Transcript-history presentation

**Status:** Deferred UX polish; current bounded plain history is functional.

Physical testing confirmed that persisted sessions and turns can be recovered,
but the archive presentation is deliberately utilitarian. A later design pass
may improve session summaries, command-card hierarchy, navigation, search, and
the distinction between live styled output and persisted plain text.

Alpha.8 exposed one concrete omission: Room retains whether a command ran
isolated, but the saved-transcript dialog formats only status and exit code. An
isolated failed turn therefore appears as `Failed · exit 1` without its
execution contract. Add the execution-mode label to saved cards when history
presentation is revised, and cover it with a UI test.

Preserve bounded retention, explicit deletion, no-write ephemeral sessions,
and the rule that history must not accidentally become credential storage.

## Home navigation and session dashboard

**Status:** One retained active session implemented and physically accepted in
alpha.7; multiple concurrent sessions deferred.

Threadline now allows navigation Home while retaining the one active
foreground-service-backed session. Home identifies the active session, offers
explicit Return and Disconnect actions, and prevents a second connection while
the first remains active. Leaving the session screen does not disconnect it.
The signed alpha.7 candidate passed this Home and Return path on the Galaxy S25
Ultra.

The remaining scope is separate:

Consider multiple concurrent sessions only as a larger session-manager feature
with resource limits, per-session notifications, credential lifetime, failure
isolation, transcript ownership, process-death recovery, and explicit
close/disconnect semantics.

Do not imply that a retained session survived when only its archived transcript
remains.

## Raw-terminal keyboard recovery

**Status:** Implemented in the alpha.12 source candidate with API 35 regression
coverage; owner-device validation remains pending.

Physical testing found two related paths. On one early transition, tapping the
terminal did not summon the software keyboard, though later taps worked. More
importantly, once the keyboard is deliberately dismissed in raw mode, the UI
has no explicit way to request it again.

Alpha.12 adds a fixed Keyboard control at the right end of the raw-terminal key
row. It reasserts the terminal library's software-keyboard request, including
input focus. An instrumented regression opens the real terminal, dismisses the
IME, uses the new control, and observes the IME return. A tap
on the terminal surface may remain a convenience, but it should not be the only
contract because taps are also used for positioning, selection, and scrolling.
The owner-device check still needs to cover recovery after mode switching,
rotation, and background return.

## Transcript and terminal switch reachability

**Status:** Implemented in the alpha.12 source candidate with Compose
regression coverage; owner-device validation remains pending and handedness
mode is deferred.

The current Transcript/Terminal switch is the first item in the connected
action row, placing it at the far-left edge on a portrait phone. That is poor
thumb reach for the right-handed owner-device path even though it remains
functional and accessible.

Alpha.12 moves the switch out of the horizontally scrolling secondary-action
row and fixes it at the right side of the connected header in both views. A
200% font-scale regression requires it to remain displayed in the trailing
half of the screen. Do not add a left/right-handed setting for one control.
Reconsider handedness when several directional controls create a coherent
layout choice that can be tested in both modes.

## Deferred connection-profile options

**Status:** Deferred product design; not required for the current MVP or Phase 5.

The current host profile intentionally stores display name, endpoint, and
username without an authentication mode or credentials. Before adding an
authentication mode, startup directory, shell preference, or keep-active
preference, define validation, failure behavior, profile migration, and whether
each setting affects only a new connection or an already active session.
Keep-active behavior must also agree with the foreground service and explicit
disconnect contract.

## Generated device keys

**Status:** Deferred authentication feature; imported keys cover current key auth.

The SSH stack can use Ed25519 and the app probes Android's provider support, but
Threadline does not currently offer user key generation. A future design must
cover private-key ownership, Keystore compatibility, public-key export and copy,
labels, rotation, deletion, backup expectations, and recovery before presenting
a generated device key as safer or simpler than an imported key.

## Structured-composer shortcut row

**Status:** Deferred interaction polish; the raw terminal already has mobile keys.

If structured-command testing shows repeated friction entering shell punctuation
or controls, evaluate a compact composer-specific row separately from the raw
terminal keyboard. It must remain usable with large fonts, screen readers,
landscape keyboards, selection, multiline input, and ordinary IME behavior.

## Per-command transcript deletion

**Status:** Deferred history semantics; saved-session deletion is implemented.

Deleting one command card needs a clear contract across the live transcript,
Room output chunks, session summaries, retention counts, currently running
turns, and ephemeral sessions. Do not imply that a card was deleted while its
persisted output or metadata remains reachable elsewhere.

## Live transcript search

**Status:** Deferred transcript polish; persisted-history search is also future work.

Search must define whether it covers the retained in-memory tail, collapsed
content, ANSI-styled text, the current session, saved history, or all of them.
It should expose truncation boundaries honestly and avoid creating an unbounded
secondary index of terminal output.

## Technical-alpha evidence collection

**Status:** Design before implementation; no remote analytics are authorized.

The alpha needs enough evidence to distinguish isolated test success from useful
day-to-day behavior. Start with an explicit tester checklist, a short feedback
template, and the existing user-triggered sanitized diagnostics export. Consider
optional on-device counters or an exportable local summary only after defining
the exact questions, fields, retention, redaction, consent, and deletion model.
Do not add a telemetry SDK, background upload, stable user identifier, command
content, host data, or terminal output merely to claim that metrics exist.

## Automated release-path and device acceptance

**Status:** Deferred test-infrastructure design; manual physical acceptance
through alpha.8 is complete.

The core debug Android suite and static release-shrinker checks run in public CI,
but alpha.5 showed the remaining gap: assembling a minified APK is not the same
as connecting through its production runtime. Alpha.6 used an isolated in-app
minified probe plus a manual signed update on the physical device to close that
specific regression.

Alpha.7 continued to use the public merged-main gate and local signing path,
then completed manual drafting and retained-session Home acceptance on the
physical device. Automating those signed release paths remains future test
infrastructure work.

Alpha.8 used the same path, then manually proved isolated strict failure,
state containment, saved transcript recovery, and a successful persistent
follow-up without reconnecting on the physical device.

A later automation slice should define how to cover a packaged minified app
through fixture connection, password and imported-key authentication,
structured commands, raw-terminal input, same-session switching, and retained
state across versioned installs. Use a dedicated test application identity and
disposable test signing material. The permanent release key and its passwords
must remain outside public CI. Device or OEM automation can strengthen the
matrix, but it does not make every physical keyboard, lifecycle, accessibility,
or update behavior universal.
