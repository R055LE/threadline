# AGENTS.md

## Project

Threadline is a transcript-first SSH client for Android.

The default UI is a touch-native command transcript. A real terminal exists underneath and is used as a fallback for interactive programs.

This file is project-specific and tool-neutral. Keep user- and machine-wide
working agreements in each agent's supported global instruction file rather
than copying them into this public repository.

It is committed, so it must stay self-contained: no private repo names, no
sibling directory paths, nothing that assumes where the clone sits on disk. It
was gitignored until 2026-08-09 for exactly that reason, which meant no clone
had it and Claude never loaded it at all.

Read `PROJECT_SPEC.md` before making architectural or product decisions. Read
`docs/STATUS.md` before choosing the next implementation slice.

## Current priority

Implement only the current milestone named by the user.

When no milestone is named, continue the next unfinished boundary in
`docs/STATUS.md`. Do not restart a completed phase merely because the current request omits a phase
number.

Phase 0's dependency spike is accepted historical evidence. Revisit it only when a dependency or
architecture change invalidates that evidence, or when the user explicitly asks. Do not build new
transcript behavior on an unproven SSH/PTY/terminal stack.

## Product invariants

These are non-negotiable unless the user explicitly changes the specification:

1. Commands run in a persistent remote shell.
2. `cd`, environment changes, aliases, and shell functions must persist.
3. Transcript mode and raw terminal mode use the same SSH channel and PTY.
4. The raw terminal receives the exact ordered byte stream even when not visible.
5. Unknown host keys require confirmation.
6. Changed host keys are blocked by default.
7. Never use an accept-all host-key verifier.
8. Never log passwords, private keys, passphrases, decrypted credentials, or raw authentication payloads.
9. No cloud backend is required.
10. No AI features in the MVP.
11. One SSH session and one active transcript command are enough for MVP.
12. If structured shell integration fails, raw terminal mode must still work.

## Preferred implementation

- Kotlin
- Jetpack Compose
- Material 3
- Coroutines and `Flow`
- Room
- Android Keystore
- Foreground service for a live background SSH session
- First SSH candidate: ConnectBot `sshlib`
- First terminal candidate: ConnectBot `termlib`
- SSH fallback candidate: SSHJ

Use adapter interfaces around SSH transport and terminal rendering so a dependency can be replaced without rewriting UI and domain logic.

Start with one Gradle application module and clear packages. Do not create a large multi-module architecture for the spike.

## Coding rules

- Keep networking, session state, and parsing out of composables.
- Model session behavior as an explicit state machine.
- Use immutable UI state.
- Prefer small types with narrow responsibilities.
- Use structured concurrency. Every long-lived coroutine must have an obvious owner and cancellation path.
- Do not use `GlobalScope`.
- Do not swallow exceptions.
- Convert low-level failures into typed domain errors.
- Batch terminal output updates; never recompose per byte.
- Bound all buffers and persisted output.
- Keep dependency versions in a version catalog.
- Add comments only for non-obvious constraints and protocol behavior.
- Do not add analytics, crash reporting, remote configuration, or telemetry unless explicitly requested.
- Do not add SFTP, port forwarding, Mosh, cloud sync, snippets, dashboards, or AI while implementing the MVP.

## Security rules

- Strict host-key verification.
- Store accepted host keys and compare them on future connections.
- Protect imported private keys with Android Keystore-backed authenticated encryption.
- Keep passwords session-only until secure persistence is deliberately implemented.
- Never put secrets in Compose state that may be serialized or debug-printed.
- Disable sensitive packet logging in release builds.
- Redact usernames, hostnames, and command content from exported diagnostics unless the user opts in.
- Treat terminal output as untrusted text.
- Never render remote output as HTML.
- Never execute a detected URL or path automatically.

## Shell integration rules

- Use a random per-session nonce.
- Associate every lifecycle marker with a command ID.
- The marker parser must be incremental and handle arbitrary buffer splits.
- Enforce maximum marker length.
- Unknown control sequences pass through to the raw terminal.
- Recognized protocol markers are not shown in transcript output.
- The command quoting function must have exhaustive tests.
- Do not claim stdout/stderr separation when using a PTY; they are generally merged.
- Bootstrap failure is a compatibility downgrade, not a connection failure.

## Testing requirements

Every meaningful change must add or update tests.

Before declaring a phase complete, run:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Run instrumented tests when an emulator or device is available.

The repository should include a Docker-based OpenSSH fixture for integration testing.

Minimum parser tests:

- Every possible split point inside a marker
- Multiple markers in one buffer
- Partial trailing marker
- Invalid nonce
- Oversized marker
- Unknown OSC sequence
- UTF-8 split across buffers
- CR progress output
- ANSI styles
- High-volume output

Minimum shell-quoting tests:

- Empty command
- Single quotes
- Double quotes
- Backslashes
- Newlines
- Unicode
- Pipes
- Redirects
- Command substitutions
- Here-document
- Leading and trailing whitespace

## Working style

Before editing:

1. Read the relevant specification section.
2. Inspect existing code and tests.
3. State the smallest implementation slice.
4. Record any specification ambiguity as an assumption.

After editing:

1. Run the narrowest relevant tests.
2. Run broader checks when practical.
3. Summarize changed files.
4. State what remains unproven.
5. Do not call a spike successful without a real SSH connection to the fixture.

## Definition of done for a task

A task is done only when:

- The code builds.
- Relevant tests pass.
- Error paths are handled.
- Secrets are not logged.
- Lifecycle cancellation is clear.
- The implementation does not expand scope beyond the requested milestone.
- Documentation is updated when an architectural decision changes.

## Claude Code specifics

`CLAUDE.md` is a symlink to this file. That is deliberate: Codex reads only
`AGENTS.md`, Claude Code reads only `CLAUDE.md`, and neither reads the other's.
A symlink makes one file serve both, so the two cannot drift. Edit `AGENTS.md`;
do not replace the symlink with a real file, and note that `/init` will try to.

On a Windows checkout without symlink support (`core.symlinks=false`, the Git
for Windows default outside Developer Mode), `CLAUDE.md` lands as a text file
containing the string `AGENTS.md` and git reports the tree clean. If Claude
seems to have no project instructions on such a machine, that is why:
`git config --global core.symlinks true` and re-clone.
