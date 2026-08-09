# Threadline investigation index

These files are dated acceptance records. Each is a snapshot of what was known and what remained at
that milestone; later work may have completed an earlier document's open boundary. Use
[the current status page](../STATUS.md) to choose new work.

Do not rewrite an accepted investigation merely to make its old status language current. Add a new
investigation for a new milestone, or a clearly dated correction when later evidence disproves a
technical claim.

## Chronology

| Date | Phase | Investigation | Boundary recorded |
|---|---:|---|---|
| 2026-07-27 | 0 | [Android SSH connection](2026-07-27-android-ssh-connection.md) | Android-only SSH failure, dependency isolation, provider fix, and live PTY proof |
| 2026-07-29 | 1 | [Structured shell integration](2026-07-29-structured-shell-integration.md) | nonce-scoped markers, quoting, parsing, persistent state, and raw fallback |
| 2026-07-30 | 3 | [Seamless raw fallback](2026-07-30-seamless-raw-fallback.md) | interactive detection, same-session terminal switching, and mobile keys |
| 2026-07-30 | 4 | [Room known-host persistence](2026-07-30-room-known-host-persistence.md) | authoritative trust records and legacy migration |
| 2026-07-31 | 4 | [Encrypted imported private keys](2026-07-31-encrypted-imported-private-keys.md) | Keystore-backed encryption, authenticated metadata, and live auth after reopen |
| 2026-07-31 | 4 | [Saved-key management](2026-07-31-saved-key-management.md) | label-only rename, exact deletion, and credential cleanup |
| 2026-07-31 | 4 | [Host-profile persistence](2026-07-31-host-profile-persistence.md) | explicit non-credential profiles and independent ownership |
| 2026-07-31 | 4 | [Known-host management](2026-07-31-known-host-management.md) | fingerprinted trust listing, exact forgetting, and changed-key ceremony |
| 2026-07-31 | 4 | [Bounded transcript persistence](2026-07-31-bounded-transcript-persistence.md) | retention, no-write ephemeral mode, deletion, and crash boundary |
| 2026-07-31 | 4 | [Sanitized diagnostics](2026-07-31-sanitized-diagnostics.md) | bounded diagnostic schema, opt-in fields, and preview-to-share identity |
| 2026-07-31 | 5 | [Accessibility and connection-error UX](2026-07-31-phase5-accessibility-error-ux.md) | typed recovery, semantics, focus, and large-font action reachability |
| 2026-07-31 | 5 | [Large-output performance](2026-07-31-phase5-large-output-performance.md) | production-path load, bounded memory, recovery latency, and builtin-loop interruption |
| 2026-08-01 | 5 | [Samsung physical-device validation](2026-08-01-phase5-samsung-physical-validation.md) | Android 16/One UI 8.5 lifecycle, rendering, raw fallback, large font, and TalkBack |
| 2026-08-01 | 5 | [Basic onboarding](2026-08-01-phase5-basic-onboarding.md) | one-screen orientation, contextual guidance, persistent completion, and Help reopen |
| 2026-08-02 | 5 | [Alpha packaging and first signed candidate](2026-08-02-phase5-alpha-packaging.md) | permanent app identity, off-repository signing boundary, verified alpha.1 artifact, update proof, and tester evidence design |
| 2026-08-02 | 5 | [Alpha.1 release-shrinker crash](2026-08-02-alpha1-release-shrinker-crash.md) | physical release failure, native field-name contract, alpha.1 rejection, and disposable-key alpha.2 SSH proof |
| 2026-08-02 | 5 | [Alpha.2 raw-terminal shrinker crash](2026-08-02-alpha2-raw-terminal-shrinker-crash.md) | second lazy JNI contract, alpha.2 rejection, expanded release verification, and disposable-key alpha.3 same-session proof |
| 2026-08-03 | 5 | [Permanent alpha.3 physical acceptance](2026-08-03-alpha3-permanent-physical-acceptance.md) | permanent artifact verification, physical same-session stress, release/JNI closure, and landscape UX evidence |
| 2026-08-08 | 5 | [Alpha.3 imported-key and diagnostic acceptance](2026-08-08-alpha3-imported-key-diagnostics-acceptance.md) | permanent-release imported-key auth after force-stop/reopen and exact default diagnostic privacy proof |
