# Threadline current status

Updated: 2026-08-11

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
key creation, public CI packaging of short-lived unsigned candidates, local
align/sign/verify/checksum tooling, a tester checklist, and a non-secret
feedback form. CI candidates carry their source commit, release identity,
checksum, and R8 mapping, while the permanent key and signed artifacts remain
outside the public workflow. The first two permanent-key candidates are rejected.
Alpha.1 crashed during native terminal initialization on Connect. Alpha.2 fixed
that path, installed over alpha.1 with app data preserved, authenticated by
password, and completed a structured `pwd` turn on the Galaxy S25 Ultra. Opening
the raw terminal then exposed a second release-only R8/JNI field-renaming crash.
Alpha.3 preserves and verifies both native field contracts. Its permanent-key
APK matches the established certificate and passed checksum, v2/v3 signature,
alignment, identity, and version inspection. On the Galaxy S25 Ultra, the
critical physical path passed: password SSH, structured and raw views, repeated
switching while `ping` remained active, and rotation in each view all preserved
the live session without the alpha.2 crash. The release/JNI blocker is closed;
the landscape software-keyboard screenshot adds a deferred compact-height
layout issue because little or no terminal output remains visible. Two
portable AES-256-encrypted signing-key backups are held separately from their
decryption secret; independent download, extraction, key-entry, alias, and
certificate checks passed for both provider copies, closing restore
verification. See the
[permanent alpha.3 acceptance investigation](investigations/2026-08-03-alpha3-permanent-physical-acceptance.md),
[alpha.3 imported-key and diagnostic investigation](investigations/2026-08-08-alpha3-imported-key-diagnostics-acceptance.md),
[alpha-packaging investigation](investigations/2026-08-02-phase5-alpha-packaging.md),
[alpha.1 crash investigation](investigations/2026-08-02-alpha1-release-shrinker-crash.md),
and [alpha.2 raw-terminal crash investigation](investigations/2026-08-02-alpha2-raw-terminal-shrinker-crash.md).

Public CI now publishes short-lived unsigned candidates labeled with the exact
source commit; permanent signing remains local. The permanent-key alpha.4
artifact installed over alpha.3 as the same app on the Galaxy S25 Ultra. The
completed onboarding state, saved profile, trusted host, transcript history,
settings, and encrypted imported key all survived. The retained key then
authenticated without re-import and completed `pwd`. This closes the installed
update-preservation boundary. See the
[alpha.4 update-preservation investigation](investigations/2026-08-09-alpha4-update-preservation.md).

The alpha.5 source candidate is `0.1.0-alpha.5` (`10005`). Its pre-invite
hardening pins GitHub Actions to immutable commits, validates the Gradle wrapper
and distribution checksum, enforces dependency checksums, verifies 16 KiB APK
alignment after signing, upgrades ConnectBot `sshlib` to 0.4.2, and adds the
core API 35 instrumented suite to CI. The new local wrapper selected the exact
successful `main` candidate, signed it with the permanent key, and produced an
alpha.5 artifact whose checksum, identity, certificate, v2/v3 signatures, and
16 KiB alignment verify. Android accepted it as an in-place update over the
existing Galaxy S25 Ultra installation, but the installed release could not
connect. An isolated minified API 35 probe reproduced a pre-authentication
`NullPointerException`: sshlib 0.4.2 constructs its bundled Ed25519 provider,
while R8 had moved that provider into the default package and broken its runtime
package lookup. A narrow provider keep rule made the same minified probe pass
authentication, PTY creation, and shell startup. Alpha.5 is rejected and alpha.4
remains the latest accepted signed artifact. See the
[alpha.5 signing and release-shrinker investigation](investigations/2026-08-10-alpha5-signing-update-progress.md).

Alpha.6 became the accepted tester build after correcting the release-only
failure. It preserves the
three cbssh Ed25519 JCA classes whose binary names are part of the provider
contract, and the renamed release verifier now requires those exact names in
both the R8 mapping and assembled DEX alongside the existing termlib JNI field
checks. The gate rejects the pre-fix alpha.5 output and accepts alpha.6. An
isolated minified API 35 probe with the exact production rule completed password
authentication, PTY creation, and shell startup. The full local JVM, lint,
debug, release, connected Android, password fixture, and encrypted-key fixture
paths pass. The merged `main` gate produced the exact candidate signed with the
permanent key. Its package, version, certificate, v2/v3 signatures, checksum,
and 16 KiB alignment independently verify. On the Galaxy S25 Ultra, alpha.6
installed over alpha.5 with retained state intact and passed password and
retained imported-key authentication, Diagnostics, structured commands, and
same-session raw-terminal acceptance. Commands typed directly in the raw
terminal correctly remained terminal-only rather than creating transcript
cards. See the
[alpha.6 Ed25519 shrinker correction](investigations/2026-08-10-alpha6-ed25519-shrinker-correction.md).

Product work continues while invited alpha use is gathered. The current
accepted tester build and source version are both `0.1.0-alpha.7` (`10007`). Its
first slice keeps the structured
composer editable while a command runs, preserves that local draft through
same-session terminal switching and Android saved-state restoration, and keeps
Send disabled until the shell returns to ready. It does not queue or
automatically execute commands. Its second slice adds a Home route that retains
the one active SSH session, shows that session with explicit Return and
Disconnect actions, and prevents starting a second connection. Connected-screen
draft and mode state survive the round trip and are cleared when the session
actually ends.

The merged-main alpha.7 candidate passed CI, was signed and installed on the
Galaxy S25 Ultra, and passed the retained-session Home and Return path. While a
`sleep 10` command ran, the composer accepted a `pwd` draft and kept Send
disabled. Send enabled when the shell returned to ready, and the draft then ran
normally. This completes alpha.7 owner-device acceptance.

## Remaining Phase 5 boundaries

- Technical-alpha use sufficient to evaluate the Phase 5 exit criterion.

## Alpha distribution: direct invited sharing, decided 2026-08-09

The alpha goes to invited testers directly. **No public GitHub prerelease while
Phase 5 is open**, even though this repository is public and a release would be
straightforward to publish.

What decided it was not caution about the artifact, which is in good shape. It
was reach against contact. A public prerelease means anyone can install a build
whose own release notes carry a known-limitations list, and there is then no way
to reach those installs when the next alpha changes something. An invited list
is a list you can talk to, so "don't rely on X yet" gets said to a person rather
than left sitting next to a download button.

The exit criterion is what makes this easy. It asks for enough technical-alpha
use to evaluate, and a handful of testers who answer questions is worth more
toward that than downloads that don't. So a public prerelease is what happens
once the criterion is met, not the way it gets met. Sequencing, not a permanent
position.

Two things worth recording so this isn't relitigated from memory:

- **The signing question is separate and already settled.** The permanent update
  lineage was established at alpha.1 and verified again at alpha.6 against
  certificate SHA-256 `102893bc…`, with the release key never entering CI. That
  holds under either distribution choice, so it argues for neither.
- **The transparency half of the public option is already done.** Checksums, the
  certificate fingerprint, signature schemes and 16 KiB alignment are published
  in `investigations/2026-08-10-alpha6-ed25519-shrinker-correction.md`, in a public
  repository. Only the signed tester APK stays private.

The asymmetry closes it: private can become public later, published can't become
unpublished. GitHub will delete a release; anything already mirrored is out.

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
