# Threadline current status

Updated: 2026-08-02

This is the canonical execution-status page. `PROJECT_SPEC.md` remains the normative product and
technical specification. Dated investigations are historical evidence for the boundary they
record; their old "next" or "remaining" sections do not override this page.

## Current milestone

**Phase 5 — Alpha polish is in progress.** Phases 0 through 4 are complete.

Four Phase 5 slices are implemented:

- **Accessibility and error UX:** typed, non-secret errors; direct recovery and focus movement;
  assertive announcements; navigable headings; spoken terminal-key labels; and connected-session
  actions reachable at 200% system font scale.
- **Large-output performance:** repeatable production-path profiling for styled Unicode volume,
  long lines, progress rewrites, sustained-output interruption, and post-load recovery; plus a
  corrected INT handler that completes builtin-only infinite loops as exit 130.
- **Physical-device validation:** Galaxy S25 Ultra testing on Android 16 / One UI 8.5 covering
  connection, rendering, large output, interruption, raw fallback, rotation, backgrounding,
  persistence, maximum font scale, and manual TalkBack navigation.
- **Basic onboarding:** a versioned one-screen introduction, contextual connection/security
  guidance, a Help reopen path, blank production connection defaults, and status-bar-safe custom
  headers.

Their evidence is recorded in the
[accessibility and error investigation](investigations/2026-07-31-phase5-accessibility-error-ux.md),
[large-output performance investigation](investigations/2026-07-31-phase5-large-output-performance.md),
[Samsung physical-device investigation](investigations/2026-08-01-phase5-samsung-physical-validation.md),
and [basic-onboarding investigation](investigations/2026-08-01-phase5-basic-onboarding.md).

Alpha packaging preparation is also implemented: permanent release and separate
debug application IDs, explicit alpha versioning, off-repository interactive
key creation, local align/sign/verify/checksum tooling, a tester checklist, and
a non-secret feedback form. The owner-created `0.1.0-alpha.1` candidate now has
a recorded checksum and public signing-certificate fingerprint and has passed
exact-APK installation and cold launch on the API 35 emulator. This is packaging
evidence rather than physical release acceptance; see the
[alpha-packaging investigation](investigations/2026-08-02-phase5-alpha-packaging.md).

## Remaining Phase 5 boundaries

- Confirm restorable, separately held backups of the permanent signing key, then
  prove the signed candidate through the physical-device release checklist.
- Build a higher-versioned candidate with the same key and prove an in-place
  update preserves release app data.
- Choose the alpha distribution boundary deliberately: direct owner sharing for
  invited testers, or a public GitHub prerelease with checksum, certificate
  fingerprint, release notes, and known limitations. This repository is public,
  so a published prerelease is public too.
- Technical-alpha use sufficient to evaluate the Phase 5 exit criterion.

Additional physical-device and OEM coverage, including Pixel, should be collected opportunistically
during technical alpha. The completed Samsung pass satisfies the dedicated physical-device boundary;
no specific handset brand is a separate Phase 5 gate.

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
