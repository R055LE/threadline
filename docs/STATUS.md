# Threadline current status

Updated: 2026-07-31

This is the canonical execution-status page. `PROJECT_SPEC.md` remains the normative product and
technical specification. Dated investigations are historical evidence for the boundary they
record; their old "next" or "remaining" sections do not override this page.

## Current milestone

**Phase 5 — Alpha polish is in progress.** Phases 0 through 4 are complete.

The first Phase 5 slice is implemented:

- typed, non-secret connection and authentication errors;
- direct recovery actions and focus movement;
- assertive error announcements, navigable headings, and full spoken terminal-key labels; and
- connected-session actions that remain reachable at 200% system font scale.

Its automated, emulator, live OpenSSH-fixture, lint, debug, and release evidence is recorded in
[the Phase 5 accessibility and error investigation](investigations/2026-07-31-phase5-accessibility-error-ux.md).

## Remaining Phase 5 boundaries

- Manual TalkBack validation.
- Physical Samsung and Pixel device validation.
- Performance profiling and further large-output validation.
- Basic onboarding.
- A signed internal APK.
- Technical-alpha use sufficient to evaluate the Phase 5 exit criterion.

Choose the smallest remaining boundary with the user before implementation. Do not infer that the
list order is a priority decision.

## Completed milestone summary

- **Phase 0:** Android SSH, PTY, terminal, authentication, host trust, and lifecycle dependency
  proof.
- **Phase 1:** nonce-scoped structured shell lifecycle over the persistent PTY.
- **Phase 2:** bounded transcript collection and the core command-card interaction model.
- **Phase 3:** same-session raw-terminal fallback and mobile terminal controls.
- **Phase 4:** Room persistence, encrypted imported keys, host and trust management, bounded and
  ephemeral transcript behavior, retention controls, and sanitized diagnostics.

See [the milestone history](HISTORY.md) and [investigation index](investigations/README.md) for the
supporting record.

## Deferred decisions

Device-credential and biometric gating are optional hardening decisions, not Phase 4 or Phase 5
blockers. Their reconsideration criteria are recorded in the [backlog](BACKLOG.md). Passwords and
private-key passphrases remain session-only.
