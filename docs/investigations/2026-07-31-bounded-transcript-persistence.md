# Phase 4 bounded transcript persistence (2026-07-31)

## Scope

This Phase 4 slice makes completed command transcripts durable without turning
the live PTY stream into an unbounded database workload. It adds an explicit
ephemeral-session choice, local history viewing, per-session deletion, and
clear-all. It does not persist raw-terminal scrollback, credentials, ANSI style
runs, or an unfinished session across abrupt process death.

## Ownership and durability boundary

`SessionManager` owns one archive context for the live SSH session. The context
contains a random session ID, a copy of the non-credential host profile, the
retention choice, and the time at which the SSH adapter successfully returned a
shell. A failed connection attempt never becomes a history row.

Live transcript output stays in the existing bounded in-memory collector. On
explicit disconnect, foreground-service destruction, or a typed connection
failure, the manager first marks an active turn disconnected, atomically takes
the archive context, and can hand it to the archive sink only once. An empty or
raw-only session creates no history record. The default is local retention;
checking **Ephemeral session** prevents the archive sink from receiving any
session, command, or output data.

The snapshot-at-session-end decision keeps Room off the SSH output path and
avoids repeatedly rewriting output while it streams. It also establishes an
honest durability limit: an abrupt process kill that does not run a session
finalization hook can lose the current session's transcript. Completed,
previously finalized sessions survive process and database reopen. Incremental
completed-turn checkpoints can be considered later if alpha use shows that
crash recovery is worth the additional lifecycle and write coordination.

Archive failures never prevent SSH cleanup or leave the app stuck in
disconnecting state. The low-level exception is discarded, while the
disconnected form reports that the last transcript could not be saved.
Coroutine cancellation is still rethrown.

## Schema and bounds

Room schema version 4 adds three credential-free tables:

- `transcript_sessions` stores the random session ID, host display metadata,
  session times, and whether older turns were omitted;
- `transcript_turns` stores ordered lifecycle metadata, a bounded command, exit
  state, directory state, original output byte count, and truncation or
  approximation flags; and
- `transcript_output_chunks` stores ordered plain-text output chunks with a
  composite foreign key to their turn.

Deleting a session cascades through its turns and output chunks. The exact 3→4
migration only creates these tables and indices; it preserves the preceding
known-host, encrypted-key, and host-profile records.

The constants are centralized in `RoomTranscriptHistoryStore`:

- newest 20 sessions;
- newest 50 turns per saved session;
- first 16,384 UTF-16 code units of command text;
- last 65,536 UTF-16 code units of plain output per turn; and
- 16,384-code-unit output chunks.

Chunk and truncation boundaries avoid splitting a UTF-16 surrogate pair. A
boundary can therefore retain one fewer code unit rather than persist a broken
character. Session pruning and archive replacement run inside the Room DAO
transaction, so partial sessions and orphaned output are not observable.

The live transcript still has its separate, larger bounds of 100 turns and
131,072 rendered characters per turn. Saved history deliberately retains a
smaller tail.

## Privacy and interaction semantics

Transcript history can contain secrets because a user can type a token into a
command or a remote program can print sensitive output. Threadline does not
pretend it can safely infer and redact those values. The connection form says
that retained history is local but unencrypted, states the per-turn output cap,
and exposes ephemeral mode before connection. Existing manifest and extraction
rules disable cloud backup and device transfer for the database.

The disconnected form exposes a bounded saved-session list. Opening a session
shows selectable command and output text with status and exit code. Restored
output is intentionally plain and inert: ANSI style runs are not stored, and
HTTP text is not made tappable in the history viewer.

Delete requires confirmation naming the exact session endpoint. Clear-all has
a separate confirmation and does not change host profiles, host-key trust, or
saved private keys. Both dialogs state that ordinary SQLite deletion is
logical and is not a claim of forensic erasure from storage pages.

Passwords, private-key bytes, passphrases, saved-key IDs, authentication
responses, and raw SSH payloads have no field in the archive domain model or
schema. Hostnames, usernames, command text, and output are sensitive local
metadata even though they are not authentication credentials.

## Acceptance evidence

New JVM tests prove that a durable live session produces one final archive,
ephemeral mode produces none, and a failing archive sink cannot prevent a
clean disconnect. On-device migration, Room, and Compose tests prove:

- exact schema 3→4 migration with a prior host profile preserved;
- survival across closing and reopening a file-backed database;
- newest-session and newest-turn pruning;
- command and output caps;
- ordered multi-chunk reconstruction across a surrogate-pair boundary;
- output-tail and original-byte-count preservation;
- foreign-key cascade behavior for exact deletion and clear-all;
- an explicit ephemeral request reaching `SessionManager`;
- selectable saved history opening; and
- confirmation before one-session deletion or clear-all.

The complete routine API 35 suite finished 52 tests: 50 passed and the two
credential-gated production cases skipped as designed. Both credential-gated
tests then passed against Docker OpenSSH in 5.219 seconds. The password-auth
production case archived its real structured session through `SessionManager`,
restored ordinary and 65,536-character large-output turns from Room, and
preserved the interrupted turn. The encrypted imported-key
authentication/reopen case also remained green.

The final repository gate passed `test`, `lint`, `assembleDebug`, and
`assembleRelease` after the implementation and documentation were complete.

## Remaining Phase 4 boundary

Phase 4 now has strict host-key persistence and management, encrypted imported
keys and management, host profiles, bounded transcript persistence, retention
controls, ephemeral sessions, and sanitized exportable diagnostics.
Device-credential and biometric gating are optional hardening decisions tracked
in `docs/BACKLOG.md`, not Phase 4 blockers. Passwords and private-key
passphrases remain session-only.
