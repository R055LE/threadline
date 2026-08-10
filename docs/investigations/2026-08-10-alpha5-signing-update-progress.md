# Alpha.5 signing and release-shrinker rejection

**Date:** 2026-08-10
**Phase:** 5, alpha polish
**Status:** Rejected

## Boundary

Alpha.5 is the first permanent-key candidate produced through the resumable
local signing path. The public candidate matched current `main`, the signed APK
belonged to the established update lineage, and Android accepted it over
alpha.4. Its first connection regression then exposed a release-only shrinker
failure introduced by the sshlib 0.4.2 path. Alpha.5 is rejected.

## Candidate and signing record

GitHub Actions produced the unsigned alpha.5 candidate from source commit
`66a743f6a89c4a8dce7846d6411e0febf77c1caa`. The local wrapper selected the
successful Android push run for that exact commit, downloaded its uniquely
named candidate, and passed it to the existing signing verifier. The permanent
release key and passwords remained local.

The locally signed artifact independently verifies as:

- package: `io.github.r055le.threadline`
- version: `0.1.0-alpha.5` (`10005`)
- APK SHA-256: `2c238b48c14c3933ab3dcd468352370b0fb1e0d5a20476c10e6fe39b7d38d8bf`
- signing certificate SHA-256: `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signer subject: `CN=Threadline`
- signature schemes: APK v2 and v3 verified
- alignment: 16 KiB page-aware zip alignment verified

The certificate matches the permanent update lineage established by alpha.1.

## Physical evidence so far

The owner installed alpha.5 over the existing Threadline release on the Galaxy
S25 Ultra. Android accepted it as an in-place update rather than a second app.
This proves the application ID, version progression, and signing lineage are
compatible on the physical device.

Google Play Protect offered to scan this APK. That prompt had not appeared for
the earlier Threadline alpha installs. This is recorded as a platform behavior
observation only. It is not evidence that Google approved, rejected, or
independently established the safety of the APK.

The owner then found that the installed alpha.5 could not connect. This stopped
the acceptance run before authentication, structured commands, or raw-terminal
behavior could be evaluated.

## Release-only reproduction and root cause

The production JVM adapter and the API 35 debug Android fixture suite continued
to pass with sshlib 0.4.2. An isolated, manifest-declared probe inside the exact
minified release path reproduced the physical failure on API 35 before
authentication. The adapter reported a generic connection failure caused by a
`NullPointerException` during Ed25519 host-signature verification.

Retracing the optimized stack located the failure in cbssh's bundled
`Ed25519Provider.setup()`. sshlib 0.4.2 now constructs this fallback provider
while converting the Ed25519 host key. The provider derives its implementation
package through `Ed25519Provider::class.java.package.name`; R8 had moved the
class into the default package, so that lookup returned null. Alpha.4 used
sshlib 0.4.1 and did not exercise this new path.

A narrow temporary keep rule for cbssh's Ed25519 provider package was tested in
the same minified probe. The corrected build reached authentication, created a
PTY, started the shell, and reported success. This proves the correction shape,
and the follow-on source correction advances as alpha.6. It is recorded in the
[alpha.6 Ed25519 shrinker investigation](2026-08-10-alpha6-ed25519-shrinker-correction.md).

## Required correction

Do not distribute alpha.5 or overwrite its immutable artifact. Alpha.6 now
preserves the cbssh Ed25519 provider classes needed by their JCA name-based
registration and adds a release gate that fails the broken mapping and DEX.
After merge and permanent signing, repeat:

- a minified password and imported-key fixture proof;
- permanent-key signing and installation over alpha.5;
- retained-state and Diagnostics checks; and
- the structured/raw same-session regression on the physical device.

Alpha.4 remains the latest accepted tester build. No password, passphrase,
private key, private endpoint, or fixture identity is recorded here.
