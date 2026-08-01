# Phase 5 accessibility and connection-error UX (2026-07-31)

## Scope

This first Phase 5 slice makes typed connection failures specific, actionable,
and accessible without beginning subjective visual styling. It covers transport
classification, user-facing recovery guidance, keyboard/focus recovery actions,
screen-reader semantics, terminal-key labels, and 200% font-scale reachability.

It is an automated accessibility pass, not a claim that manual TalkBack or
physical Samsung/Pixel testing is complete. Those remain part of the broader
Phase 5 device-validation boundary.

## Typed transport failures

The SSH adapter previously collapsed every transport failure other than an
algorithm mismatch or rejected host key into `ConnectionFailed`. It now maps
the exception types in the bounded cause chain to four additional domain
errors:

- unresolved or unknown host → DNS resolution failed;
- socket timeout → connection timed out;
- connection exception → connection refused; and
- no route to host → network unreachable.

Mapping is based only on exception type. Threadline does not parse, retain, log,
or display the low-level message, which may contain endpoint details. Cause
inspection stops after eight levels and unknown failures retain the generic,
non-secret connection error. The diagnostic report maps the four new variants
to stable codes without adding raw exception data.

## Presentation and recovery contract

Every `SessionError` now maps exhaustively to a presentation containing a
distinct title, the existing safe domain message, specific recovery guidance,
and an optional recovery action. The disconnected form renders all three as
text, so failure is not represented by color alone.

Actions stay narrow:

- DNS, timeout, refusal, unreachable-network, generic connection failure, and
  connection loss focus the retained hostname field;
- authentication rejection focuses the active password or key-passphrase field;
- unsupported private-key failures focus the key-passphrase field; and
- notification/service-start failures open Threadline's Android notification
  settings only after an explicit tap.

Passwords and passphrases are still cleared after every attempt. The
authentication guidance states that behavior before asking the user to re-enter
the credential. A failure to launch Android settings becomes another visible,
non-secret local error rather than disappearing.

Changed-host-key errors preserve their stronger boundary. The presentation
does not place the endpoint or fingerprints in general domain guidance; the
existing dedicated card shows the exact saved and presented fingerprints plus
the explicit forget, independently verify, reconnect, and accept ceremony.

## Accessibility semantics and scaling

Connection errors are assertive live regions and expose navigable heading
semantics. Command-submission errors use the same assertive announcement
boundary. The app title, phase title, connection progress title, authentication
section, connected-session title, and individual command text expose headings
where they create useful navigation landmarks.

The progress indicator has a spoken label. Symbol-only terminal arrow keys and
abbreviated keys expose full labels such as **Arrow up**, **Page down**, and
**Escape**. Existing text buttons retain their visible accessible names.

The connected-session title and actions no longer share one width-constrained
top-app-bar row. Actions live in a horizontally scrollable row beneath the
title, keeping Terminal/Transcript, Ctrl-C when relevant, Diagnostics, and
Disconnect independently reachable when system font scale is 200% or the
viewport is narrow.

Threadline deliberately does not mark streaming terminal output as a live
region; reading every remote update would create an unusable announcement
stream. The raw termlib surface still needs manual TalkBack evaluation on real
devices.

## Acceptance evidence

Pure JVM tests cover every error presentation, safe changed-key guidance, all
new transport mappings, nested and bounded cause handling, and stable diagnostic
codes. Compose tests prove:

- assertive error and command-submission live regions;
- heading semantics;
- authentication and network actions focus the intended field;
- Android settings open only after the explicit action;
- full spoken terminal-key labels;
- command headings; and
- reachability of every connected-session action at 200% font scale.

The focused 29-test Compose run passed on API 35. The complete routine device
suite then finished 61 tests: 59 passed and the two credential-injected
production cases skipped as designed. Both production cases passed separately
against Docker OpenSSH in 5.36 seconds.

The final repository gate passed `test`, `lint`, `assembleDebug`, and
`assembleRelease` after documentation was complete.

## Remaining Phase 5 boundary

Manual TalkBack and hardware-keyboard evaluation, physical Samsung/Pixel
testing, performance profiling, additional large-output work, basic onboarding,
and a signed internal APK remain. Exportable sanitized diagnostics were already
completed in Phase 4.
