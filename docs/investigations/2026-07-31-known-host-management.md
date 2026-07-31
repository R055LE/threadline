# Phase 4 known-host management (2026-07-31)

## Scope

This Phase 4 slice makes the existing strict Room-backed host-key decisions
visible and removable. It adds no automatic acceptance, no in-place key
replacement, and no weaker changed-key policy. The user can inspect the trust
record or deliberately forget it; establishing trust in a different key still
requires a later connection and a separate fingerprint decision.

## Metadata boundary

The schema remains at version 3 because the `known_hosts` table already stores
the required endpoint, key algorithm and bytes, and first-trusted plus
last-verified timestamps. A new ordered DAO flow reads those records in the
data layer. `RoomKnownHostStore` emits only management metadata to Compose:

- normalized hostname and port;
- key algorithm;
- canonical SHA-256 fingerprint;
- first-trusted timestamp; and
- last-verified timestamp.

Raw encoded host-key bytes do not enter UI state. Host public keys and their
fingerprints are not secret credentials, but endpoints and trust history can
still be sensitive device-owner metadata. The existing backup and extraction
rules continue to exclude the database from cloud backup and device transfer.

Fingerprint calculation moved into one shared implementation used by both the
strict verifier and management projection. The fingerprint shown beside a
saved record therefore uses the same algorithm and encoding as unknown- and
changed-key decisions.

The metadata flow first completes the existing idempotent legacy-preference
migration. Opening management on an older installation cannot leave a valid
legacy trust record invisible merely because no connection lookup happened
first.

## Delete and replacement semantics

**Forget** opens a confirmation dialog containing the exact endpoint,
algorithm, and fingerprint. Confirmation deletes one row by its normalized
endpoint key. An affected-row count other than one becomes a typed unavailable
error; low-level database failures retain the existing generic, non-secret
known-host storage error. Coroutine cancellation is preserved.

Deletion does not change the server, disconnect a session, delete a host
profile, or remove an imported private key. Management is shown only on the
disconnected or failed connection form, so an active session cannot lose its
trust record through this UI.

A changed key remains blocked without presenting an acceptance prompt or
mutating the saved record. The error now names the endpoint and shows both the
saved and presented fingerprints. Its guidance requires four deliberate steps:

1. forget the matching saved record;
2. reconnect;
3. verify the newly presented fingerprint through a trusted channel; and
4. explicitly accept it through the ordinary unknown-host dialog.

There is intentionally no one-tap “replace” action. Deletion removes the prior
decision; the later prompt creates a new decision only after another network
observation and verification opportunity.

## Acceptance evidence

Thirteen focused API 35 tests covered the Room store and connection form. The
new cases prove:

- sorted metadata with the verifier's canonical fingerprint;
- deletion of one endpoint without disturbing another;
- explicit failure when the target record is already absent;
- changed-key blocking without an acceptance prompt or trust mutation;
- endpoint-aware changed-key guidance;
- no delete callback before confirmation;
- removal of the confirmed record from Compose state; and
- after deletion, the same replacement candidate is unknown and cannot be
  saved until a fresh explicit acceptance decision occurs.

The test compile briefly used two Compose assertion helpers that are not
present in this repository's UI-test API variant. Replacing them with the
supported tag collection plus zero-count assertion fixed the harness without
changing product behavior.

The full `test`, `lint`, `assembleDebug`, and `assembleRelease` gate passed. The
complete API 35 suite finished 46 tests: 44 passed, while the two production
credential-gated cases skipped as designed. Both credential-gated tests then
passed against the Docker OpenSSH fixture in 5.602 seconds, including
prompt-free reuse of a retained real host key and encrypted imported-key
authentication after database reopen.

## Remaining Phase 4 boundary

Known-host trust and management, encrypted imported keys and management, and
host profiles now have production Room paths. Remaining work includes bounded
transcript persistence, ephemeral sessions, optional device-credential or
biometric gating, sanitized diagnostics, and retention controls. Passwords and
private-key passphrases remain session-only.
