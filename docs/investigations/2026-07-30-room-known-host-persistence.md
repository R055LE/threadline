# Phase 4 Room known-host persistence (2026-07-30)

## Scope

Phase 4 includes Room persistence, encrypted imported keys, known-host
management, ephemeral sessions, sanitized diagnostics, retention controls, and
changed-key blocking. The first slice establishes the database around the trust
decision that already gates every SSH connection.

Changed keys were already blocked against the Phase 0
`SharedPreferencesKnownHostStore`. That store was deliberately small and had no
management model, timestamps, schema history, or place for the other Phase 4
entities. This slice replaces it in production without expanding into host
profiles, transcripts, credential storage, or management UI.

## Database boundary

Threadline now uses Room 2.8.4 with KSP and checks the exported version 1 schema
into `app/schemas`. The first table is `known_hosts`:

- normalized endpoint key;
- normalized hostname and port;
- exact SSH host-key algorithm and encoded public key;
- first-seen timestamp; and
- last-trusted-seen timestamp.

The process-owned runtime owns one `ThreadlineDatabase`. SSH verification uses
a `RoomKnownHostStore` through the existing narrow `KnownHostStore` interface.
Store operations are suspend functions and execute from the session manager's
owned I/O scope. Cancellation remains cancellation rather than being converted
into a storage error.

An accepted unknown key is inserted with conflict-abort semantics. That matters:
a concurrent or stale write cannot silently replace an existing trust
decision. A matching key advances its last-seen timestamp monotonically. A
different algorithm or byte sequence remains changed, does not prompt, does not
update the saved record, and does not authenticate.

Room failures become `KnownHostStoreException`. The strict gate converts that
to `SessionError.KnownHostStorageFailed`, returns false to the SSH verifier, and
shows only a non-secret user message. An unreadable or unwritable trust store
therefore fails closed.

## Legacy migration

The Phase 0 preference file contains endpoint keys and an algorithm-plus-Base64
record. On the first Room-store operation, Threadline:

1. reads every valid legacy record;
2. assigns the import time as both first-seen and last-seen because the old
   format did not retain those timestamps;
3. inserts the records with conflict-ignore semantics; and
4. clears the legacy file after the Room insert.

Conflict-ignore is intentional. If Room already contains a decision for an
endpoint, an older preference value cannot replace it. If clearing preferences
fails, Room remains authoritative in the current process and a later process
retries the idempotent import.

Malformed legacy values are not trusted. They are discarded, so the next real
connection returns to the ordinary unknown-key confirmation path.

## Backup boundary

The application still sets `allowBackup=false`, and both legacy and current
Android extraction rules exclude databases, preferences, files, root data, and
external data from cloud backup and device transfer. This slice does not turn a
host trust decision into portable account data.

## Acceptance evidence

Plain-JVM tests preserve the unknown, trusted, and changed policy and prove:

- accepted keys receive explicit first/last timestamps;
- matching keys update last-seen without prompting;
- changed keys remain blocked without prompting; and
- storage failure blocks verification with a typed error.

Five focused API 35 Room tests prove:

- record read/write and a last-seen timestamp that never moves backward;
- migration of the exact legacy preference format;
- a stale legacy record cannot replace a newer Room key;
- a changed key cannot mutate Room trust; and
- the record survives closing and reopening a file-backed database.

The credential-gated production Android fixture then used
`RoomKnownHostStore` with the real ConnectBot adapter and OpenSSH server. The
first connection required acceptance and wrote the served Ed25519 key to Room.
After the complete structured-session suite, Threadline disconnected and a new
`SessionManager` connected to the same endpoint using the same Room store.
Because the test supplied no second host-key decision, reaching `Connected`
proved the real key was read back and trusted without another prompt. The
complete production case passed in 3.755 seconds.

The first production invocation never reached the network because adding the
reconnect block caused Kotlin to infer a non-`Unit` JUnit method. Making the
method result explicit fixed the harness. The next invocation reached the
pre-existing final Ctrl-C case, which timed out once. An exact rerun passed,
including reconnect; the full routine gate passed afterward. The timeout did
not reproduce, but remains recorded for future fixture runs.

Finally, `test`, `lint`, `assembleDebug`, and `connectedDebugAndroidTest`
passed. All 27 routine device tests were green; the production fixture case
skipped in that routine invocation because its runtime password was
deliberately absent.

## Remaining Phase 4 boundary

This was the Room and known-host persistence foundation, not completion of
Phase 4. Subsequent slices added
[encrypted imported keys](2026-07-31-encrypted-imported-private-keys.md),
[saved-key management](2026-07-31-saved-key-management.md), and
[host-profile persistence](2026-07-31-host-profile-persistence.md).
Remaining deliverables include:

- bounded transcript history;
- known-host listing, deletion, and deliberate changed-key replacement;
- ephemeral sessions;
- sanitized diagnostics; and
- transcript deletion and retention controls.

Passwords remain session-only. Imported private-key bytes and passphrases remain
memory-only and are cleared after authentication; they are not persisted by
this slice.
