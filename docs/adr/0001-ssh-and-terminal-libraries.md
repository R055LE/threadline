# ADR 0001: SSH and terminal libraries

- Status: Provisional — implementation selected, device spike pending
- Date: 2026-07-24

## Context

Threadline needs one persistent SSH shell channel with a PTY. Every received
byte must continuously feed a real terminal emulator, even when the terminal UI
is not visible. The same channel must support keyboard input and PTY resize.
Host verification must never fall back to an accept-all policy.

The original specification says “ConnectBot sshlib,” but that name is now
ambiguous:

- `org.connectbot:sshlib` is the older, Trilead-derived line. Its project now
  describes it as deprecated.
- `org.connectbot.sshlib:sshlib` is ConnectBot's newer Kotlin/coroutine client.

The dependency spike must use released artifacts rather than unreleased main
branches.

## Decision

Use:

- `org.connectbot.sshlib:sshlib:0.4.1`
- `org.connectbot:termlib:0.1.0`

Both versions are pinned in the Gradle version catalog.

The new sshlib is used through `SshClientAdapter`, not directly by UI or session
state code. Its released API supports an injected suspending `HostKeyVerifier`,
password auth, OpenSSH private-key auth, PTY requests, shells, raw byte
channels, and terminal resize.

An opt-in `ssh-integration` JVM module compiles the production model and adapter
source files directly. This avoids Android's deliberately unimplemented local
test stubs while preventing a second adapter implementation from drifting away
from the app.

The termlib component is held by a process-owned `TerminalBridge`. It wraps
libvterm and exposes a Compose terminal, raw input feeding, keyboard output,
and resize callbacks. Terminal callbacks only enqueue session work; they never
call back into the emulator and therefore avoid termlib's callback reentrancy
constraint.

Known hosts use a small private `SharedPreferences` store for this spike. An
unknown key suspends the SSH handshake until the user accepts or rejects its
SHA-256 fingerprint. Acceptance persists the exact algorithm and encoded key.
A different saved key is blocked without offering a one-tap replacement.

An SLF4J no-op runtime provider disables dependency logging in the POC. This
avoids library messages containing host or username context entering Logcat.

## License

- The new ConnectBot sshlib is Apache-2.0.
- ConnectBot termlib is Apache-2.0.
- termlib embeds libvterm, which is MIT licensed.

Before distribution, generate and review a complete dependency license report;
the two libraries have additional transitive dependencies.

## Consequences and limitations

- sshlib `0.4.1` targets JVM 17 and uses Ktor networking. Android build and
  runtime compatibility must be proven on the target emulator/device matrix.
- sshlib exposes stdout and SSH extended-data streams separately. OpenSSH
  merges stdout and stderr for a PTY, which preserves the expected ordered raw
  terminal stream in the fixture. A server that emits extended data despite a
  PTY would require an upstream ordered-packet API to make a stronger claim.
- termlib contains native code. ABI packaging, rotation, font-driven resize,
  and background/foreground behavior require device testing.
- A foreground service keeps the process eligible to run; it does not promise
  an immortal network connection under OEM process management.
- The host-key store is intentionally smaller than the later Room-backed
  persistence design.
- Private keys are imported only for the authentication window and are not
  persisted in Phase 0.

## Spike evidence

On 2026-07-24, the Docker fixture and production adapter passed:

- exact SHA-256 server-fingerprint verification;
- password and generated Ed25519-key authentication;
- PTY-backed shell creation, keyboard-byte input, and returned raw bytes;
- a terminal resize from 24×80 to 41×101, confirmed by remote `stty size`; and
- host-key persistence across a fixture restart using the same Docker volume.

The fixture also produced ANSI, Unicode, carriage-return progress, and
high-volume output. Those modes have not yet been visually verified through
termlib on Android, so the decision remains provisional.

## Alternatives considered

### Older ConnectBot/Trilead sshlib

Not selected because its own artifact line is deprecated and the newer
coroutine library already exposes the Phase 0 primitives. It remains useful as
a compatibility reference, not the default dependency.

### SSHJ

Keep SSHJ as the first fallback. Re-run only the transport portion of the spike
with SSHJ if the selected sshlib fails Android networking, authentication,
server compatibility, cancellation, or ordered-stream requirements.

### Termux terminal libraries

Not selected because adopting them would require an intentional GPL-compatible
distribution decision for the whole application.

## Exit checklist

Do not mark this ADR accepted, or Phase 0 complete, until all of these have been
observed against the Docker fixture:

1. Password authentication succeeds.
2. Generated Ed25519-key authentication succeeds.
3. The shown fingerprint matches the fixture host key.
4. A restarted fixture with the same volume reconnects without prompting.
5. A regenerated host-key volume is blocked as changed.
6. Raw commands, ANSI output, Unicode, carriage-return progress, and high-volume
   output render in termlib.
7. Keyboard input reaches the same PTY.
8. Rotation changes PTY dimensions and `stty size` confirms them.
9. Backgrounding shows the foreground notification; returning preserves
   terminal state.
10. Disconnect closes the channel without leaked jobs or threads.
