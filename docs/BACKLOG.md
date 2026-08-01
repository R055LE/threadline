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

## Drafting and queued commands while a turn runs

**Status:** Deferred interaction design; not a Phase 5 interruption blocker.

The transcript composer is currently disabled whenever the structured shell is
not ready. Physical testing showed that interrupt and recovery are fast, but it
also made the user wait before typing the likely follow-up command.

Treat two possible improvements separately:

1. Allow editing a local draft while a command is running, while keeping Send
   unavailable until the shell is ready. Preserve the draft through stopping,
   failure, raw-mode switching, rotation, and saved-state restoration.
2. Consider an explicit command queue only as a larger feature. It must define
   ordering, visibility, reordering or removal, behavior after failure or
   interruption, disconnect handling, and whether queued content is persisted.
   Never send a queued command merely because the shell returned to readiness
   unless the UI made that execution contract unambiguous.

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
