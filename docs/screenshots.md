# Threadline prototype screenshots

These screenshots were captured on 2026-08-01 from the current Android prototype
on an API 35 emulator at the default font scale. They use the repository's local
OpenSSH fixture, its documented emulator address, and synthetic test output. No
production host, credential, command, or server fingerprint is shown.

The screenshots document working behavior. Typography, spacing, color, and
responsive layout remain prototype UI rather than a final visual design.

## Getting oriented

| First-run introduction | Blank connection setup |
| --- | --- |
| [![Threadline first-run introduction explaining the transcript, terminal, connection, credential, and history boundaries](images/prototype-onboarding.png)](images/prototype-onboarding.png) | [![Threadline connection form with blank production defaults and credential-retention guidance](images/prototype-connection.png)](images/prototype-connection.png) |

## Working in one SSH session

| Structured transcript | Raw terminal |
| --- | --- |
| [![Synthetic ANSI and Unicode output rendered in a successful structured command card](images/prototype-transcript.png)](images/prototype-transcript.png) | [![A clean raw terminal view in the same local fixture session](images/prototype-terminal.png)](images/prototype-terminal.png) |

The transcript view keeps command status, working directory, duration, exit
code, output, and follow-up actions together. The terminal view remains attached
to the same PTY for interactive work and exposes mobile modifier and navigation
keys.

## Local transcript history

[![The saved-transcript dialog showing one synthetic local fixture session](images/prototype-history.png)](images/prototype-history.png)

History is local, bounded, excluded from Android backup, and not encrypted.
Ephemeral sessions bypass it entirely; see the [current status](STATUS.md) and
[project specification](../PROJECT_SPEC.md) for the exact product boundaries.
