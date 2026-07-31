# Phase 4 encrypted imported private keys (2026-07-31)

## Scope

This Phase 4 slice makes imported SSH private keys explicitly persistable
without persisting plaintext key bytes or passphrases. It builds on the
versioned Room and strict known-host foundation from the preceding slice. It
does not add password persistence, biometric gating, generated device keys, or
the eventual credential-management and deletion screen.

The user must select a private-key file for the current connection. **Save
encrypted on this device** is an explicit opt-in. Without that choice, the file
remains session-only as before.

## Storage and cryptographic boundary

Room schema version 2 adds `imported_private_keys`. Each record contains:

- a random stable record ID and display name;
- detected key format and SSH public-key type;
- the canonical SHA-256 public-key fingerprint;
- AES-GCM ciphertext and its per-encryption initialization vector;
- creation time; and
- a crypto-format version.

The list query projects only non-secret metadata. It does not load ciphertext
into Compose state.

`AndroidKeystorePrivateKeyCipher` owns one app-scoped, non-exportable 256-bit
AES key under the versioned alias `threadline.imported-private-keys.v1`. The key
is authorized only for encrypt/decrypt with GCM and no padding. Android's
cipher provider generates a fresh 12-byte IV for every encryption; the 128-bit
authentication tag remains attached to the stored ciphertext.

Length-prefixed authenticated data binds the ciphertext to:

1. a Threadline-specific context label;
2. crypto version;
3. record ID;
4. detected format; and
5. public-key fingerprint.

Changing metadata or moving ciphertext between records therefore makes GCM
authentication fail. Decrypt never creates a replacement Keystore key. If the
original device key is missing, the saved record fails closed and must be
re-imported.

This follows Android's current recommendation to use Android Keystore when key
material needs stronger protection and AES-256 in GCM mode for symmetric
encryption. Keystore work and private-file reads run off the main thread:

- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)
- [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)
- [`KeyGenParameterSpec` AES-GCM example](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)

Threadline still sets `allowBackup=false`, and its extraction rules exclude
databases and files from cloud backup and device transfer. That matters because
restoring ciphertext without its non-exportable Keystore key would produce
unusable records. Android also documents disabling backup as appropriate for
apps that handle sensitive data:
[Auto Backup guidance](https://developer.android.com/identity/data/autobackup).

## Import, fingerprint, and memory lifetime

Import validates the selected private key and passphrase through sshlib's
supported `SshKeys` facade. The public half is encoded into the canonical SSH
wire blob for Ed25519, ECDSA, or RSA, then hashed with sshlib's SHA-256
fingerprint implementation. The production Ed25519 fixture fingerprint matches
the value reported by OpenSSH `ssh-keygen`.

sshlib's public parsing facade accepts the private key and passphrase as Java
`String` values. Those immutable objects cannot be zeroed, so the conversions
are scoped to the parser call and never enter app or UI state. The parsed JCA
private key is destroyed when its provider supports `Destroyable`. Mutable file
buffers, parser output blobs, passphrase arrays, decrypted arrays, and session
credential arrays are cleared at their shortest practical ownership boundary.

On a later connection, the store reads one encrypted record on an I/O
dispatcher, reconstructs its authenticated data, and decrypts it directly into
`SessionCredential.PrivateKey`. That type takes its own copy; the store clears
the temporary plaintext immediately. `SessionManager` and the production SSH
adapter already clear the credential after authentication on success and
failure. Passphrases remain memory-only and are required again for encrypted
OpenSSH keys.

No credential bytes, passphrases, authentication payloads, usernames, hosts,
or fingerprints are logged.

## Migration and failure behavior

The explicit Room 1→2 migration creates the encrypted-key table and leaves the
existing `known_hosts` table untouched. There is no destructive migration
fallback.

Low-level Keystore and authentication-tag failures become a generic,
non-secret protection error. A specifically missing Keystore key becomes a
re-import message. Invalid SSH format or passphrase becomes a generic import
error. Coroutine cancellation is not swallowed by the UI preparation job.

## Acceptance evidence

The API 35 device suite proves:

- AES-GCM round-trip with two different IVs and ciphertexts for the same input;
- rejection of modified ciphertext and different authenticated metadata;
- fail-closed behavior after deleting the Keystore alias;
- encrypted store metadata, fingerprinting, ciphertext-only persistence, and
  self-clearing credential creation;
- Room 1→2 migration validation while preserving a known-host row; and
- saved-key selection, session-only passphrase delivery, and temporary
  passphrase-array wiping in the Compose connection form.

The credential-gated production fixture additionally:

1. streams the disposable ignored Ed25519 key into debug app-private storage;
2. removes the plaintext fixture file immediately after reading;
3. imports and encrypts the key with the production Room and Keystore classes;
4. verifies the public fingerprint against host `ssh-keygen`;
5. closes and reopens the file-backed Room database;
6. decrypts the saved record through Android Keystore;
7. authenticates through `ConnectBotSshClientAdapter` to the real OpenSSH
   server;
8. completes `encrypted-key-reload-ok` through the structured shell; and
9. verifies the session credential byte array is all zero after auth.

Both credential-gated production tests passed in the final exact-code run in
5.752 seconds.

The final project gate passed `test`, `lint`, `assembleDebug`, and
`assembleRelease`. The routine API 35 suite then finished 35 tests: 33 passed,
and the two production fixture cases skipped as designed because no runtime
credentials were supplied.

Two useful false starts were preserved by the test loop. sshlib's lower-level
reader and public-key encoder appear JVM-public but are Kotlin-internal, so the
implementation moved to its supported public parsing facade and a small
canonical public-blob encoder. The first real Ed25519 run then exposed a
provider-specific algorithm name; accepting the Ed25519 OID as well as common
names kept the check strict across Android providers. The next run completed
key authentication but submitted the proof command before structured Bash
bootstrap was ready. Waiting for `StructuredShellState.Ready` fixed the test
harness rather than weakening production state rules.

## Remaining Phase 4 boundary

Encrypted imported-key persistence is now proven, but credential management is
not complete. Remaining work includes saved-key deletion and naming UX,
optional device-credential or biometric gating, host-profile persistence,
known-host management, bounded transcript persistence, ephemeral sessions,
sanitized diagnostics, and retention controls. Passwords remain session-only.
