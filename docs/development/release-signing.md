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

## Permanent update-line certificate

The public SHA-256 fingerprint established by the permanent-key alpha.1 build is:

```text
102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc
```

Compare every future candidate against this value before installation or
publication. The fingerprint is public verification data, not a secret and not
a substitute for keystore backups. It may be deliberately replaced before the
first distribution, but distributing an APK establishes the update lineage for
that application ID.

Alpha.1 is rejected because of its release-only shrinker/JNI crash, but its
certificate remains the intended update lineage. Permanent-key alpha.2 matches
this fingerprint and Android accepted it as an update over the physical alpha.1
installation with app data preserved. Its verified APK SHA-256 is:

```text
320cd5021973326226d5842a98a36965af221b05d35db611e4dac33663e901b8
```

Alpha.2 is also rejected: password SSH and the structured transcript worked,
but opening the raw terminal exposed a second release-only JNI field-renaming
crash. Permanent-key alpha.3 uses the same certificate and passed the corrected
physical terminal path. Its verified APK SHA-256 is:

```text
694c5f9b1780bd279a3c14de971d822ee024ac1f706cceaeeb486191224d088e
```

The artifact identifies as `io.github.r055le.threadline`, version
`0.1.0-alpha.3` (`10003`), and verifies with APK signature schemes v2 and v3.
Keep alpha.1 and alpha.2 as immutable rejected evidence; do not overwrite them.

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
3. Restore-test each archive: extract a downloaded copy with the archive tool,
   then confirm the recovered `.p12` can be read with `keytool -list`.
4. Never rename a different key to look like the original. A different signing
   certificate creates an incompatible application update lineage.

### Current custody status

As of 2026-08-03, the owner has two AES-256-encrypted 7z archives held by
separate storage providers. The archive decryption secret is stored separately
in a password manager. Each provider copy has been downloaded independently,
extracted, and confirmed to contain the expected readable `PrivateKeyEntry`
under the `threadline-release` alias with the established certificate SHA-256.
Earlier application-specific encrypted copies were retired only after these
replacement restores passed. Provider names and recovery details are
intentionally omitted from this public repository.

This closes the technical-alpha restore-verification boundary. Future backup
replacements still need the same independent restore test: existence is not
restore proof. `keytool` does not create, encrypt, or decrypt the backup
archive. The archive tool and its password protect the backup; `keytool` is
used only after extraction to prove that the recovered PKCS12 keystore is
readable and belongs to Threadline's established update lineage.

Test a downloaded copy from each storage provider independently:

1. Create a temporary directory and download the encrypted archive into it. Do
   not extract over the working keystore or modify the provider's original.
2. Use the archive program that created the backup to decrypt and extract it
   with the archive password. If extraction fails, stop: that backup has not
   been proven restorable.
3. Locate the recovered `.p12` file and run:

```bash
keytool -list -v \
  -keystore /path/to/recovered-threadline-release.p12 \
  -alias threadline-release
```

4. At the prompt, enter the **keystore password**. This is separate from the
   archive password unless the owner deliberately made them identical.
5. Confirm the alias is `threadline-release`, the entry type is
   `PrivateKeyEntry`, and the certificate SHA-256 matches the public fingerprint
   above. `keytool` may display the same fingerprint in uppercase with colons.
6. Remove the temporary extracted `.p12`, leaving the encrypted archive intact.
7. Repeat the complete process with a fresh download from the second provider.

Record only that both restores succeeded—never the archive password, keystore
password, or private key.

Both current backups are online. A later offline encrypted copy on removable
media would reduce their shared cloud/account/synchronization failure modes and
better approximate the 3-2-1 backup model. It is recommended hardening, not a
technical-alpha blocker once both existing restores are proven. See the
[NCSC guidance on logically separate, offline, and tested backups](https://www.ncsc.gov.uk/blog-post/offline-backups-in-an-online-world).

The separately stored decryption secret also needs a recovery path. Confirm that
loss of one device or second-factor method would not permanently lock the owner
out of the password manager; keep recovery material or emergency-access
instructions somewhere that does not depend on the same two cloud accounts.

## Build a signed alpha APK

Every successful push to `main` publishes a seven-day GitHub Actions artifact
named `threadline-<version>-UNSIGNED-<commit>`. It contains the unsigned release
APK, its SHA-256 checksum, the R8 mapping, and build metadata. The artifact is
public because this repository is public. It contains no signing key or
password, cannot update the official app, and is not a distributable Threadline
release.

To sign the exact candidate produced by CI, first identify the successful
`Android` run for the intended commit:

```bash
git pull --ff-only
git rev-parse HEAD
gh run list --workflow Android --branch main --limit 5
gh run view RUN_ID --json conclusion,headSha,url
```

Confirm that `conclusion` is `success` and `headSha` equals the local `HEAD`.
Then download and verify the artifact:

```bash
threadline_candidate_dir=$(mktemp -d)
gh run download RUN_ID \
  --pattern 'threadline-*-UNSIGNED-*' \
  --dir "$threadline_candidate_dir"
(
  cd "$threadline_candidate_dir"
  sha256sum -c threadline-*-UNSIGNED.apk.sha256
)
threadline_candidate_apk=$(find "$threadline_candidate_dir" -maxdepth 1 \
  -type f -name 'threadline-*-UNSIGNED.apk' -print -quit)
```

Set only the non-secret path and alias in the shell. The build helper prompts
for both passwords without echoing them:

```bash
export THREADLINE_RELEASE_STORE_FILE="$HOME/.local/share/threadline/signing/threadline-release.p12"
export THREADLINE_RELEASE_KEY_ALIAS=threadline-release
./scripts/build-signed-alpha.sh "$threadline_candidate_apk"
unset THREADLINE_RELEASE_STORE_FILE THREADLINE_RELEASE_KEY_ALIAS
rm -r -- "$threadline_candidate_dir"
unset threadline_candidate_apk threadline_candidate_dir
```

The helper verifies the candidate checksum, source commit, application ID, and
version before asking for signing passwords. Omitting the APK argument keeps the
original local-build path and runs `assembleRelease` before signing.

For non-interactive automation, the helper also accepts
`THREADLINE_RELEASE_STORE_PASSWORD` and `THREADLINE_RELEASE_KEY_PASSWORD` from
the process environment. Do not place those variables in a committed file or
ordinary shell profile.

The helper then:

1. uses the verified CI candidate, or builds the minified release APK when no
   candidate is supplied;
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
