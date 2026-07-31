# Phase 4 sanitized diagnostics (2026-07-31)

## Scope

This slice adds a local diagnostic preview and Android share flow without
creating a logging subsystem or diagnostic archive. It also pulls the Phase 5
exportable-report deliverable forward: the exact bounded plain-text preview is
the only payload handed to Android's share chooser.

The panel is available while disconnected, connecting, awaiting a host-key
decision, connected, disconnecting, or viewing a typed failure. Opening it
takes a report timestamp; changing the explicit privacy option regenerates the
same report at that timestamp.

## Sanitized-by-default model

The default report is assembled from purpose-built summaries rather than raw
exceptions or object string representations. It contains:

- report format, generation time, app version, Android SDK, and device model;
- stable connection, connection-stage, structured-shell, and typed error codes;
- transcript turn/status counts, active/truncated/approximate/interactive
  indicators, and retained/received output sizes;
- local counts for profiles, trusted servers, encrypted imported keys, and
  saved transcript sessions; and
- an explicit privacy manifest describing what was redacted or never included.

The default path excludes display names, hostnames, ports, usernames,
directories, command text, command output, credentials, private-key material,
host-key bytes, fingerprints, raw SSH packets, exception messages, and stack
traces. Error reporting maps every `SessionError` variant to a stable code and
does not inspect the sensitive fields carried by host-key errors.

## Explicit sensitive-detail option

The diagnostic dialog starts with **Include host fields, directories, and
recent command text** unchecked. Enabling it shows a warning and immediately
updates the preview before sharing. The optional section can contain the
current form's display name, hostname, port, username, current directory, and
up to the newest 20 command records with status and directory.

Command text can itself contain tokens or other secrets, so this is an informed
export choice rather than claimed redaction. Each command is capped at 1,024
characters, general fields are capped at 512 characters, control characters
are escaped or replaced, and the complete report is capped at 65,536
characters.

Command output, credentials, key material, and host-key material remain absent
even after opt-in. They have no field in the optional export model.

## Interaction and failure boundary

Threadline does not write the report to app storage. The user sees selectable
plain text, chooses whether to add sensitive details, and explicitly presses a
share action. Android receives an `ACTION_SEND` intent containing exactly that
preview as `text/plain`; Threadline does not choose or contact a recipient. If
no activity accepts the request, the dialog stays open and displays a local
error.

No analytics, crash reporter, telemetry service, packet logger, or automatic
submission path was added.

## Acceptance evidence

JVM tests prove default redaction against hostile host-key, directory, command,
and output samples; explicit opt-in behavior; permanent output exclusion;
stable mappings for every typed session error; control-character handling; and
the command/report bounds. Compose tests prove default-off behavior, exact
preview-to-share identity, warning and preview changes after opt-in, and visible
share failure. The share-intent test proves the preview is the only plain-text
payload.

The complete API 35 run finished 56 tests: 54 passed and the two
credential-injected production cases skipped as designed. Both production
cases then passed against Docker OpenSSH in 5.094 seconds. The final repository
gate passed `test`, `lint`, `assembleDebug`, and `assembleRelease`.

## Remaining boundary

Device-credential and biometric gating are separately deferred in
[`docs/BACKLOG.md`](../BACKLOG.md). Neither is a Phase 4 exit blocker, and
biometrics require a concrete threat-model justification before reconsideration.
