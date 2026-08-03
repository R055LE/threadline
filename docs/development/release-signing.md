# Release signing and alpha packaging

Threadline's release application ID is `io.github.r055le.threadline`. Standard
debug builds use `io.github.r055le.threadline.debug`, so release and development
installs have separate Android data, Room databases, and Keystore entries. The
Kotlin namespace remains `dev.threadline`; it is an implementation detail rather
than the installed application identity.

The current alpha version is defined once in `gradle.properties`. Increase both
`threadline.versionCode` and `threadline.versionName` for every distributed
build. An Android update must have a greater version code and the same signing
certificate as the installed release.

## Current candidate certificate

The public SHA-256 fingerprint for the `0.1.0-alpha.1` candidate certificate is:

```text
102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc
```

Compare every future candidate against this value before installation or
publication. The fingerprint is public verification data, not a secret and not
a substitute for keystore backups. It may be deliberately replaced before the
first distribution, but distributing an APK establishes the update lineage for
that application ID.

## Signing-key boundary

The long-lived release keystore and its passwords must never enter this
repository, build output, terminal history, documentation, diagnostics, or
chat. The repository ignores common keystore extensions, `signing.properties`,
and `dist/` as defense in depth, but ignore rules are not a custody system.

Create an owner-only directory outside the repository, then run the interactive
helper from a trusted terminal:

```bash
install -d -m 0700 "$HOME/.local/share/threadline/signing"
./scripts/create-release-keystore.sh \
  "$HOME/.local/share/threadline/signing/threadline-release.p12"
```

The JDK `keytool` process prompts without putting the password in the command.
The PKCS12 key alias is `threadline-release`; use the same password for the
keystore and key when prompted. The certificate is valid for 10,000 days.

Before using the key:

1. Save the password in a password manager.
2. Make at least two encrypted backups of the keystore on separately owned
   storage.
3. Confirm one backup can be opened with `keytool -list`.
4. Never rename a different key to look like the original. A different signing
   certificate creates an incompatible application update lineage.

## Build a signed alpha APK

Set only the non-secret path and alias in the shell. The build helper prompts
for both passwords without echoing them:

```bash
export THREADLINE_RELEASE_STORE_FILE="$HOME/.local/share/threadline/signing/threadline-release.p12"
export THREADLINE_RELEASE_KEY_ALIAS=threadline-release
./scripts/build-signed-alpha.sh
unset THREADLINE_RELEASE_STORE_FILE THREADLINE_RELEASE_KEY_ALIAS
```

For non-interactive automation, the helper also accepts
`THREADLINE_RELEASE_STORE_PASSWORD` and `THREADLINE_RELEASE_KEY_PASSWORD` from
the process environment. Do not place those variables in a committed file or
ordinary shell profile.

The helper:

1. builds the minified release APK;
2. aligns it before signing;
3. signs it with Android SDK `apksigner` using environment-backed password
   inputs;
4. verifies the APK and prints its public signing-certificate fingerprints; and
5. writes the APK, a SHA-256 checksum, and an `apksigner` `.idsig` sidecar under
   ignored `dist/`.

The `.idsig` supports local incremental installation. Normal tester sideloading
and GitHub release distribution require the APK; publish the checksum alongside
it, but the sidecar is optional.

It refuses to replace an existing artifact with the same version. Increment the
version rather than silently publishing different bytes under one alpha name.

`./gradlew assembleRelease` alone produces an unsigned intermediate. It is not
the distributable alpha artifact.

## Acceptance before publishing

Do not publish an APK merely because its signature verifies. Install the actual
candidate and complete the [alpha test checklist](../alpha-testing.md), including
password and imported-key authentication against the disposable fixture. Then
produce the next version and prove that `adb install -r` updates in place while
preserving the release app's profiles, trusted-host records, encrypted keys, and
bounded transcript history.

Only after those checks should the verified APK, checksum, public certificate
fingerprint, release notes, and known limitations be distributed. Direct owner
sharing supports an invited alpha. A published GitHub prerelease is public
because the Threadline repository is public; a draft can stage assets before
publication, but it is not a private tester channel. See GitHub's
[release documentation](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository).
The keystore and passwords are never release assets.
