# Phase 5 alpha-packaging preparation (2026-08-02)

## Status

Infrastructure and the permanent update lineage are implemented and locally
verified. Alpha.1 is rejected for a Connect-time shrinker/JNI crash. Alpha.2
fixed Connect and proved same-key physical update, retained app data, password
SSH, and structured execution, but opening its raw terminal exposed a second
lazy native field-name contract. Alpha.2 is also rejected. Permanent-key alpha.3
preserves and verifies both contracts, matches the established certificate, and
has passed the corrected physical Galaxy S25 Ultra path. The release/JNI blocker
is closed. Key-backup recovery, unreported checklist items, distribution, and
technical-alpha use remain open. No APK is published for testers.

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
crashes the app. After retrieving the current fixture credential locally, the
owner confirmed password authentication, a successful structured `pwd` turn,
and preservation of the installed app data. Opening the raw terminal then
crashed the app. Alpha.2 is therefore rejected despite proving signing
compatibility and the update/data boundary.

## Correction after the alpha.2 raw-terminal attempt

Exact alpha.2 reproduction on API 35 failed in `TerminalNative.nativeResize`
when native termlib resolved `ScreenCell.char`. R8 had renamed the 14 private
`ScreenCell` fields because the alpha.2 rule and verifier covered only the 17
`CellRun` fields reached during earlier native initialization.

The corrected source is `0.1.0-alpha.3` (`10003`). Its release shrinker rule
preserves both classes, and the release gate verifies all 31 exact field names
in the R8 mapping and assembled DEX. A disposable-key minified alpha.3
completed password SSH, raw-terminal open and `pwd` input, portrait/landscape resize,
background/restore, transcript return, and a subsequent structured `pwd` in one
unchanged app process with no fatal runtime log. See the
[alpha.2 raw-terminal crash investigation](2026-08-02-alpha2-raw-terminal-shrinker-crash.md).

## Permanent alpha.3 verification and physical acceptance

The owner built immutable `0.1.0-alpha.3` with the established permanent key.
Its public verification record is:

- SHA-256: `694c5f9b1780bd279a3c14de971d822ee024ac1f706cceaeeb486191224d088e`
- signing-certificate SHA-256:
  `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signer: one 4096-bit RSA key with certificate subject `CN=Threadline`
- Android APK signature schemes: v2 and v3 verified
- manifest identity: `io.github.r055le.threadline`, version
  `0.1.0-alpha.3` (`10003`)

The checksum file verifies against the artifact, 16 KiB page-aware zip
alignment passes, and the certificate matches the alpha.1/alpha.2 update
lineage.

On the Galaxy S25 Ultra, the owner reports that the critical corrected path
passes. Password SSH and both session views remained functional. For a stronger
same-session stress check, a continuous `ping` remained active while switching
repeatedly between transcript and terminal and rotating in each view. No crash
or session loss was reported. This closes the physical release/JNI boundary
that rejected alpha.2; see the
[permanent alpha.3 acceptance investigation](2026-08-03-alpha3-permanent-physical-acceptance.md).

The landscape comparison also supplied separate UX evidence. Threadline keeps
the session alive across rotation, but its connected header and action row leave
almost no visible terminal above the software keyboard. That compact-height
layout is recorded in the backlog without turning a third-party application's
toolbar into Threadline's design specification.

## Acceptance still required

1. Confirm password-manager custody plus at least two separately held encrypted
   keystore backups, including one successful `keytool -list` restore check.
2. Complete any still-unreported permanent-alpha.3 checklist items, especially
   imported-key authentication and the sanitized diagnostic preview.
3. Choose distribution deliberately. Share directly for a limited invited alpha,
   or publish a public GitHub prerelease with the tester guide, release notes,
   checksum, certificate fingerprint, and known limitations. Threadline's GitHub
   repository is public, so a published prerelease is not private.

## Infrastructure validation

The repository gate passed for unit tests, lint, debug assembly, and minified
unsigned release assembly. Manifest inspection confirmed release identity
`io.github.r055le.threadline`, debug identity
`io.github.r055le.threadline.debug`. The initial build used version name
`0.1.0-alpha.1` and version code `10001`; the current corrected source is
`0.1.0-alpha.3` and `10003`.

GitHub verification now assembles the unsigned minified release on every push
and pull request in addition to its existing test, lint, and debug-build gate.
Signing remains local, so CI never needs the permanent release key merely to
prove that release shrinking and packaging compile.

After the physical alpha.1 and alpha.2 incidents, that gate verifies ConnectBot
termlib's native-resolved `CellRun` and `ScreenCell` field names in both the R8
mapping and the assembled release DEX.

A disposable `/tmp` key exercised alignment, environment-backed signing,
signature verification, checksum output, cold installation and launch of the
minified release, and exact uninstall. The temporary key, APK, and emulator app
were deleted; this proves tooling rather than permanent-key custody.

The renamed debug and instrumentation identities then passed the complete API
35 suite, the opt-in production password and encrypted-key SSH tests, and the
large-output performance runner against the disposable OpenSSH fixture. The
temporary plaintext fixture key was absent after the runner completed.
