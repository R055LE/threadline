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

**Status:** Local drafting implemented in the alpha.7 source; command queue
deferred.

Physical testing showed that interrupt and recovery are fast, but disabling the
composer during a running turn made the user wait before typing the likely
follow-up command. The alpha.7 source keeps the composer editable while a turn
runs, preserves the local draft through stopping, failure, raw-mode switching,
rotation, and saved-state restoration, and enables Send only when the shell is
ready again.

The implemented draft behavior remains separate from an explicit command queue.
That queue remains a larger feature and must define ordering,
visibility, reordering or removal, behavior after failure or interruption,
disconnect handling, and whether queued content is persisted. Never send a
queued command merely because the shell returned to readiness unless the UI
made that execution contract unambiguous.

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

Preserve bounded retention, explicit deletion, no-write ephemeral sessions,
and the rule that history must not accidentally become credential storage.

## Home navigation and session dashboard

**Status:** Deferred product architecture; current UI owns one visible active
session.

Threadline currently requires disconnecting before returning to host and
history management. Separate two possible scopes:

1. Allow navigation home while retaining one active foreground-service-backed
   session, with an obvious route back and an equally obvious disconnect state.
2. Consider multiple concurrent sessions only as a larger session-manager
   feature with resource limits, per-session notifications, credential
   lifetime, failure isolation, transcript ownership, process-death recovery,
   and explicit close/disconnect semantics.

Do not imply that leaving a session screen disconnected it, or that a retained
session survived when only its archived transcript remains.

## Raw-terminal IME focus reliability

**Status:** Monitor during Pixel and alpha validation; one Samsung miss was not
reproducible.

On the first physical transition into raw mode, one tap on the terminal surface
did not summon the software keyboard. A later tap worked without any app or
device change, and the problem did not recur through subsequent terminal input
or rotation checks.

If it recurs, capture the entry path, orientation, current IME, prior focus,
and whether a second tap succeeds. Then evaluate an explicit focus requester
and post-attachment IME request rather than treating repeated tapping as the
product behavior.

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

**Status:** Deferred test-infrastructure design; alpha.6 physical acceptance is
complete.

The core debug Android suite and static release-shrinker checks run in public CI,
but alpha.5 showed the remaining gap: assembling a minified APK is not the same
as connecting through its production runtime. Alpha.6 used an isolated in-app
minified probe plus a manual signed update on the physical device to close that
specific regression.

A later automation slice should define how to cover a packaged minified app
through fixture connection, password and imported-key authentication,
structured commands, raw-terminal input, same-session switching, and retained
state across versioned installs. Use a dedicated test application identity and
disposable test signing material. The permanent release key and its passwords
must remain outside public CI. Device or OEM automation can strengthen the
matrix, but it does not make every physical keyboard, lifecycle, accessibility,
or update behavior universal.
