# Phase 5 alpha-packaging preparation (2026-08-02)

## Status

Infrastructure and the first permanent-key candidate were implemented and
locally verified. Its fresh physical installation succeeded, but its first
connection attempt exposed a release-only shrinker/JNI crash. Alpha.1 is
rejected. Permanent-key alpha.2 is verified, Android accepted it as an in-place
physical update over alpha.1, and the release crash is resolved. The remaining
physical SSH and data-preservation checks plus technical-alpha use remain open.
No APK is published for testers.

## Decision

The permanent release application ID is `io.github.r055le.threadline`.
Standard debug builds append `.debug`, allowing development and release installs
to coexist without sharing Room databases, Android Keystore entries, profiles,
trust records, or transcript history. The Kotlin namespace remains
`dev.threadline` because changing source packages is unrelated to installed app
identity.

The first artifact was version `0.1.0-alpha.1` with version code `10001`.
Both values live in `gradle.properties` beside the release application ID so
fixture scripts, artifact naming, diagnostics, and Gradle use one source of
truth.

## Signing boundary

The permanent release key is owner-held and created interactively outside the
repository. Its keystore and passwords do not belong in Git, ordinary shell
profiles, documentation, diagnostics, release assets, or chat. The helper uses
JDK `keytool` without password command-line arguments and refuses repository
destinations or replacement of an existing file.

The signed-alpha builder keeps signing secrets out of Gradle configuration and
its configuration cache. It builds the unsigned minified APK, aligns it, signs
with Android SDK `apksigner` using environment-backed password input, verifies
the result, prints only public certificate details, and writes a SHA-256
checksum under ignored `dist/`.

Common keystore extensions, `signing.properties`, and `dist/` are ignored as
defense in depth. Those patterns do not replace encrypted backups, password
manager custody, restore testing, or changed-content leak scanning.

The builder also refuses repository-local keystores and refuses to replace an
existing artifact with the same version. Alpha names identify immutable bytes;
a changed build requires an incremented version.

## Alpha evidence design

The tester guide separates first installation from the more important update
proof. A later alpha must install over the earlier signed release and preserve
profiles, trust, encrypted saved keys, transcript configuration and history,
and onboarding completion.

The feedback form requests version, device, task category, outcome, friction,
and reuse intent while explicitly prohibiting SSH identifiers, commands,
output, fingerprints, and credentials. Initial evidence remains manual plus the
existing user-previewed sanitized diagnostic export. No analytics, crash
reporter, background upload, or stable user identifier was added.

## Permanent candidate verification

The owner created the off-repository PKCS12 keystore and built the immutable
`0.1.0-alpha.1` candidate. Repository inspection found no keystore or other
private signing material. The APK's public verification record is:

- SHA-256: `feb824af68043879da2a54ecfe802b11f1fe4d0e2b722c8afceaea56ce9557e5`
- signing-certificate SHA-256:
  `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signer: one 4096-bit RSA key with certificate subject `CN=Threadline`
- Android APK signature schemes: v2 and v3 verified
- manifest identity: `io.github.r055le.threadline`, version
  `0.1.0-alpha.1` (`10001`), launcher label `Threadline`

The APK passed 16 KiB page-aware zip alignment and installed successfully on
the API 35 emulator. Android reported a 776 ms cold launch of
`dev.threadline.MainActivity`, and the app process remained alive afterward.
The `.idsig` sidecar enabled the local incremental install; ordinary tester
sideloading requires only the APK. This verifies the exact permanent-key
candidate's packaging and launch, not its physical SSH behavior or upgrade
lineage.

The same APK then installed successfully on the Galaxy S25 Ultra running
Android 16 / One UI 8.5 and presented the first-run onboarding flow. The owner
reported no installation or immediate functional failure. The captured
compact-height layout did expose deferred visual friction around the bottom
system-navigation area, card density, and scroll context; that observation is
recorded in the backlog without treating the remaining physical checklist as
complete.

## Correction after the first physical connection attempt

Alpha.1 is rejected rather than accepted for distribution. Its first physical
Connect action crashed because R8 renamed private `CellRun` fields that
ConnectBot termlib's native library resolves by Java name. The corrected source
is `0.1.0-alpha.2` (`10002`), keeps the complete JNI field contract, and verifies
the mapping plus assembled release DEX in CI. A disposable-key minified alpha.2
completed terminal initialization, host verification, password authentication,
PTY/shell startup, and a structured command round-trip on API 35. See the
[release-shrinker crash investigation](2026-08-02-alpha1-release-shrinker-crash.md)
for the failure, false lead, fix, and acceptance evidence.

## Permanent alpha.2 verification and physical update

The owner built the immutable `0.1.0-alpha.2` candidate with the established
permanent key. Its public verification record is:

- SHA-256: `320cd5021973326226d5842a98a36965af221b05d35db611e4dac33663e901b8`
- signing-certificate SHA-256:
  `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signer: one 4096-bit RSA key with certificate subject `CN=Threadline`
- Android APK signature schemes: v2 and v3 verified
- manifest identity: `io.github.r055le.threadline`, version
  `0.1.0-alpha.2` (`10002`)

The checksum file verifies against the artifact, and the certificate matches
the alpha.1 update lineage. Android accepted alpha.2 directly over alpha.1 on
the Galaxy S25 Ultra. The owner confirmed that the Connect action no longer
crashes the app. Authentication with the previously retained fixture password
was rejected because that disposable credential had been intentionally rotated
during diagnosis; this is not evidence of a release authentication regression.

## Acceptance still required

1. Confirm password-manager custody plus at least two separately held encrypted
   keystore backups, including one successful `keytool -list` restore check.
2. Retrieve the current fixture credential locally and finish the minified
   physical release run through password authentication, SSH, imported-key,
   structured, raw-terminal, lifecycle, persistence, and diagnostic paths.
3. Confirm that the successful same-key in-place update preserved onboarding,
   trust, profiles, transcript data, and settings.
4. Choose distribution deliberately. Share directly for a limited invited alpha,
   or publish a public GitHub prerelease with the tester guide, release notes,
   checksum, certificate fingerprint, and known limitations. Threadline's GitHub
   repository is public, so a published prerelease is not private.

## Infrastructure validation

The repository gate passed for unit tests, lint, debug assembly, and minified
unsigned release assembly. Manifest inspection confirmed release identity
`io.github.r055le.threadline`, debug identity
`io.github.r055le.threadline.debug`. The initial build used version name
`0.1.0-alpha.1` and version code `10001`; the corrected source is now
`0.1.0-alpha.2` and `10002`.

GitHub verification now assembles the unsigned minified release on every push
and pull request in addition to its existing test, lint, and debug-build gate.
Signing remains local, so CI never needs the permanent release key merely to
prove that release shrinking and packaging compile.

After the physical alpha.1 incident, that gate also verifies ConnectBot
termlib's native-resolved `CellRun` field names in both the R8 mapping and the
assembled release DEX.

A disposable `/tmp` key exercised alignment, environment-backed signing,
signature verification, checksum output, cold installation and launch of the
minified release, and exact uninstall. The temporary key, APK, and emulator app
were deleted; this proves tooling rather than permanent-key custody.

The renamed debug and instrumentation identities then passed the complete API
35 suite, the opt-in production password and encrypted-key SSH tests, and the
large-output performance runner against the disposable OpenSSH fixture. The
temporary plaintext fixture key was absent after the runner completed.
