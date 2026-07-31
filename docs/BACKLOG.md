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
