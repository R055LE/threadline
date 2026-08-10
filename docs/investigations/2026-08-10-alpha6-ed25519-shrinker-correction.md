# Alpha.6 Ed25519 shrinker correction

**Date:** 2026-08-10
**Phase:** 5, alpha polish
**Status:** Accepted

## Boundary

Alpha.5 was rejected after its installed release failed before authentication.
The [alpha.5 investigation](2026-08-10-alpha5-signing-update-progress.md)
records the physical report, isolated minified reproduction, retraced stack,
and cbssh Ed25519 provider root cause. Alpha.6 must preserve that provider's
name-loaded JCA classes and make the release gate reject the broken R8 output.

## Correction

The source advances to `0.1.0-alpha.6` (`10006`). Its R8 rules preserve only
the three cbssh classes required by this contract:

- `Ed25519Provider`, whose runtime package supplies the registered class-name
  prefix;
- `Ed25519KeyFactory`, which JCA loads by that registered name; and
- `Ed25519KeyPairGenerator`, which JCA also loads by name.

The release verifier is now named `verify-release-shrinker-contracts.sh`
because it owns both the existing termlib JNI field-name checks and this JCA
class-identity check. For all three provider classes, it requires the R8
mapping to retain the exact binary name and the assembled release DEX to contain
that exact class.

The new verifier rejected the pre-fix alpha.5 release output because
`Ed25519Provider` was renamed. After the narrow keep rule and version bump, the
alpha.6 release mapping and DEX passed the same gate.

## Release-shaped evidence

An isolated diagnostic clone added a manifest-declared probe directly to a
separately identified, debug-key-signed minified release. The probe used the
production adapter and the local OpenSSH fixture. No diagnostic activity or
alternate application ID enters the product source.

With the exact three-class production rule, the minified probe completed
Ed25519 host-signature verification, password authentication, PTY creation, and
shell startup on API 35. The temporary emulator was stopped without saving a
snapshot, and the fixture password was passed only through process memory.

Repository verification then passed:

- JVM tests, Android lint, debug assembly, and minified release assembly;
- all 69 connected Android tests, with the three credential-gated profiles
  skipped by the ordinary runner as designed;
- the production JVM fixture adapter;
- both explicit Android password and encrypted imported-key fixture tests; and
- the new release shrinker-contract verifier.

The first explicit Android fixture run immediately after the heavier connected
suite reached SSH but timed out on its later Ctrl-C completion case. An
immediate isolated rerun passed both credential-gated tests. No product change
was made for that one load-sensitive timeout.

## Permanent artifact

Pull request 7 merged the correction as `b96cda3bfd2c60b006adf17281174dddfbe48af5`.
The independent Android push run for that exact `main` commit passed its build,
release-shrinker, and instrumented gates and uploaded the unsigned alpha.6
candidate. The local signing wrapper selected that run and signed it with the
permanent key.

The finished artifact independently verifies as:

- package: `io.github.r055le.threadline`
- version: `0.1.0-alpha.6` (`10006`)
- APK SHA-256: `67b20d8b6c149c06e23fb063dff9b3e84ad988f0ff16fcaea25964fa62ede674`
- signing certificate SHA-256: `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signature schemes: APK v2 and v3 verified
- alignment: 16 KiB page-aware zip alignment verified

## Physical acceptance

The owner installed alpha.6 over rejected alpha.5 on the Galaxy S25 Ultra.
Android accepted it as an in-place update, and the retained application state
remained usable. The acceptance path passed fixture connection, password and
retained imported-key authentication, Diagnostics, structured commands, and
same-session raw-terminal switching. This closes the alpha.5 release regression
and makes alpha.6 the accepted tester build.

Typing `echo` directly in raw mode displayed its output in the terminal without
adding a transcript card. This matches the current product contract: raw input
writes directly to the shared PTY, while structured transcript turns are created
only by composer submissions and collect output between their lifecycle
markers. Raw-terminal activity is therefore not persisted as structured
transcript history.

Broader release-path and device acceptance automation remains a deferred test-
infrastructure decision in the backlog. No password, passphrase, private key,
private endpoint, or fixture identity is recorded here.
