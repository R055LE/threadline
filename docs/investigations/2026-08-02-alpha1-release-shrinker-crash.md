# Alpha.1 release-shrinker crash (2026-08-02)

## Status

`0.1.0-alpha.1` is rejected and must not be distributed. Its first physical
connection attempt exposed a release-only native interoperability failure. The
fix is implemented as `0.1.0-alpha.2` (`10002`) and has completed a disposable-key
minified release proof on the API 35 emulator. A permanent-key alpha.2 build,
in-place physical update, and physical SSH rerun remain open.

## Incident

The permanent-key alpha.1 APK installed and launched on the Galaxy S25 Ultra,
but tapping Connect terminated the process. Reproducing with the exact alpha.1
APK on the emulator produced `NoSuchFieldError` while
`org.connectbot.terminal.TerminalNative.nativeInit` initialized the terminal.
ConnectBot termlib's native library looks up `CellRun` backing fields such as
`fgRed` and `fgGreen` by their Java names. R8 had renamed those private fields in
the minified release.

Debug builds did not shrink, so the earlier emulator, integration, and physical
debug passes could not expose this boundary. The failure happened before SSH
authentication and was unrelated to the entered endpoint or credentials.

## Resolution

Threadline now keeps every `org.connectbot.terminal.CellRun` field name for the
JNI contract. The release gate also checks both the R8 mapping and the assembled
release DEX for all 17 native-resolved fields. This makes removal of the keep
rule or disappearance of a required field a CI failure instead of a physical
release crash.

Alpha artifact names remain immutable. The broken alpha.1 bytes and version are
not overwritten; the corrected source advances to alpha.2.

## Acceptance evidence

The corrected source passed unit tests, lint, debug assembly, release assembly,
and the JNI field-name verifier. APK inspection confirmed the alpha.2 release
identity and all expected `CellRun` field names.

A disposable off-repository key then exercised the full minified alpha.2 path on
the API 35 emulator:

1. install and cold launch;
2. native terminal initialization without a process abort;
3. independently matched unknown-host fingerprint and saved trust;
4. password authentication to the Docker OpenSSH fixture;
5. PTY and persistent shell startup at `/home/threadline`; and
6. a structured `pwd` turn returning `/home/threadline`, exit 0, while the app
   remained alive.

After the temporary diagnostic probe was removed, the clean alpha.2 source was
rebuilt, signed with the same disposable key, and installed in place over the
probe build. Onboarding completion, the earlier transcript, and the accepted
host record survived. The clean build reconnected without another host prompt
and completed another `pwd` turn with exit 0 and no crash. This is useful local
update evidence, but it does not replace the required higher-version,
permanent-key update on the physical device.

The production JVM adapter also passed against the same fixture. An apparent
authentication failure during diagnosis was traced to coordinate-based test
automation entering a value in the wrong field, not to the release code. The
automation was replaced with focus navigation before the successful proof.

A temporary diagnostic build logged exception class names and method locations
only; that probe was removed before the final source build. When the disposable
fixture password was accidentally placed in a visible test field during that
false lead, it was rotated immediately. The fixture environment file remains
ignored and no credential belongs in the repository.

## Remaining boundary

Build alpha.2 with the established permanent key, verify its public certificate
fingerprint matches the recorded update lineage, and install it over the
physical alpha.1 app. That run must prove preserved app data plus the physical
connect, shell, transcript, raw-terminal, lifecycle, and diagnostic checklist.
