# Phase 4 host-profile persistence (2026-07-31)

## Scope

This Phase 4 slice persists explicitly saved SSH connection metadata and makes
those records selectable and manageable from the existing connection form. It
does not persist credentials, automatically save every connection, link a
profile to a saved private key, or add transcript persistence.

## Persistence boundary

Room schema version 3 adds `host_profiles`. Each row contains:

- a random stable profile ID;
- display name;
- hostname;
- port;
- username; and
- creation and update timestamps.

The explicit 2→3 migration creates only this table. It does not rewrite the
known-host or imported-private-key tables. Migration validation starts from the
exported version 2 schema, inserts an encrypted-key record, runs the production
migration, and confirms that the record's identity and ciphertext remain
unchanged.

Passwords, private-key passphrases, selected saved-key IDs, pending private-key
file URIs, authentication mode, and authentication responses are absent from
the entity and schema. Profiles are connection metadata, not credentials. The
app's existing backup and extraction exclusions continue to keep its database
out of cloud backup and device transfer.

## Interaction semantics

Saving is explicit. With no selected profile, **Save profile** creates a new
stable record from the validated and trimmed connection fields. Selecting a
saved profile fills only those fields and clears every session-only credential
input. It deliberately preserves the current authentication mode while not
persisting that choice.

Editing a selected profile's fields and choosing **Update profile** changes
only that stable record. **Use as new** clears the selected-record association
and all credential inputs while preserving the connection fields, so the next
save creates a separate record rather than silently overwriting the original.

Delete requires an explicit dialog showing the profile name and exact
`username@hostname:port`. It removes only that profile row. Known-host trust and
encrypted imported keys live in separate stores and are not changed. If the
deleted profile was selected, its selection and current credential inputs are
cleared; the non-secret draft fields remain available.

DAO update and delete operations report their affected-row count. A missing or
concurrently removed record produces a typed unavailable error. Other Room
failures become typed storage errors with fixed, non-secret messages, while
coroutine cancellation is rethrown.

## Acceptance evidence

Ten focused API 35 tests covered the connection form, host-profile store, and
Room migrations. They prove:

- normalized save and targeted update/delete behavior;
- isolation between two profile records;
- profile survival after closing and reopening the file-backed database;
- sanitization of a low-level DAO failure;
- exact 1→2 and 2→3 schema migrations with preceding records preserved;
- explicit UI save, selection, update, and confirmation-gated delete; and
- credential clearing when a profile is selected or its selected record is
  deleted.

The first focused run exposed a test visibility assumption: adding the profile
controls made the scrollable form longer, so an older saved-key test clicked
Connect without first scrolling it into view. Making the scroll explicit fixed
the harness; the credential path itself was unchanged.

The full `test`, `lint`, `assembleDebug`, and `assembleRelease` gate passed. The
complete API 35 suite finished 43 tests: 41 passed, while the two production
credential-gated cases skipped as designed. Both credential-gated tests then
passed against the Docker OpenSSH fixture in 4.795 seconds, including known-host
reconnection and encrypted imported-key authentication after database reopen
under schema version 3.

## Remaining Phase 4 boundary

Known-host trust, encrypted imported keys, saved-key management, and host
profiles now have Room-backed production paths. Remaining work includes
known-host management, bounded transcript persistence, ephemeral sessions,
optional device-credential or biometric gating, sanitized diagnostics, and
retention controls. Passwords and private-key passphrases remain session-only.
