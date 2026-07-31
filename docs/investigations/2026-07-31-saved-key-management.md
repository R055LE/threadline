# Phase 4 saved-key management (2026-07-31)

## Scope

This Phase 4 follow-up makes encrypted imported-key records manageable from the
connection form. Each saved key can be renamed or deleted beside the existing
display name, key type, and public fingerprint. It does not add password
persistence, key generation, server-side key revocation, biometric gating, or
a separate settings screen.

## Semantics and security boundary

Rename changes only the mutable display label. It trims surrounding whitespace
and rejects an empty result. The stable record ID, format, public fingerprint,
crypto version, ciphertext, and initialization vector remain unchanged, so a
rename never decrypts or re-encrypts the credential. The fingerprint remains
the user-visible identity check.

Delete requires an explicit confirmation dialog that repeats the key type and
fingerprint. It deletes exactly the selected `imported_private_keys` Room row.
If that row was selected for the next connection, the UI also clears the
selection and its memory-only passphrase. Deleting another row does not disturb
the selected credential.

The Android Keystore AES wrapping key is app-scoped and shared by saved records.
Deleting one row therefore does not remove or rotate that key: doing so would
make every other saved credential unavailable, and retaining it also permits
future imports. This operation is logical database deletion, not a claim of
cryptographic or physical erasure from prior SQLite pages. It also does not
revoke the corresponding public key from any SSH server, which the confirmation
copy states explicitly.

Room update and delete operations return their affected-row count. A missing or
concurrently removed record fails with a typed unavailable error. Other Room
exceptions are wrapped in a typed storage error with a fixed, non-secret user
message, while coroutine cancellation is preserved.

## Acceptance evidence

The API 35 tests prove:

- Room rename changes only the target label and preserves ciphertext and IV;
- Room delete removes only the target while leaving another encrypted record
  untouched;
- the encrypted store normalizes the label without decrypting or re-encrypting
  key material;
- a deleted record can no longer produce a session credential;
- rename and delete are exposed as distinct actions beside a fingerprint;
- delete does nothing until its explicit confirmation is pressed; and
- deleting the selected record clears its selection and temporary passphrase.

The focused four-test connection-form class passed first. The complete device
suite then finished 38 tests with only the two credential-gated production
fixture cases skipped as designed. The full `test`, `lint`, `assembleDebug`, and
`assembleRelease` gate passed.

Finally, both credential-gated Android production tests passed against the
Docker OpenSSH fixture in 5.478 seconds. That regression includes closing and
reopening the file-backed Room database, decrypting the imported Ed25519 key
through Android Keystore, authenticating through the production SSH adapter,
running the structured-shell proof command, and confirming credential-byte
cleanup.

## Remaining Phase 4 boundary

Saved encrypted-key persistence and basic management are now proven. Remaining
work includes optional device-credential or biometric gating, host-profile
persistence, known-host management, bounded transcript persistence, ephemeral
sessions, sanitized diagnostics, and retention controls. Passwords remain
session-only.
