# Alpha.2 raw-terminal shrinker crash (2026-08-02)

## Status

`0.1.0-alpha.2` is rejected and must not be distributed. It fixed alpha.1's
Connect-time crash and proved the permanent update path, preserved app data,
password authentication, and a structured command on the Galaxy S25 Ultra.
Opening the same live session's raw terminal then exposed a second release-only
native field-name failure. The corrected source is `0.1.0-alpha.3` (`10003`). It
has passed a disposable-key minified release proof on the API 35 emulator; the
permanent-key build and physical update remain open.

## Physical evidence

Android accepted the permanent-key alpha.2 artifact as an update over alpha.1.
The owner reported that retained app data survived, the current fixture password
authenticated, and transcript `pwd` completed successfully. Tapping **Terminal**
then terminated the application. This narrowed the incident to a lazy raw-view
path after SSH, PTY, shell, transcript collection, and persistence had already
worked.

## Reproduction and root cause

Installing the exact immutable alpha.2 APK on API 35 reproduced the failure.
The app connected to the disposable OpenSSH fixture and reached **Transcript
ready**. Opening **Terminal** aborted in `TerminalNative.nativeResize` with a
`NoSuchFieldError` for `org.connectbot.terminal.ScreenCell.char`. The native
backtrace passed through the scrollback-pop resize path.

ConnectBot termlib's native library constructs `ScreenCell` values by resolving
and assigning private Java fields by name. R8 had renamed all 14 fields in the
minified alpha.2 DEX. This contract is reached when the terminal viewport opens
or changes and scrollback is restored, so alpha.2's successful Connect,
authentication, PTY startup, and structured command did not exercise it.

The alpha.1 correction preserved and verified `CellRun`, which native
initialization resolves before authentication. That gate was exact for the
observed failure but incomplete for the library's later native operations. The
second incident is the same class of interoperability defect at a different
lazy execution boundary, not a session, credential, transcript, or Compose
failure.

## Resolution

Threadline now preserves every field of both native-resolved classes:

- `CellRun`: `fgRed`, `fgGreen`, `fgBlue`, `bgRed`, `bgGreen`, `bgBlue`,
  `bold`, `underline`, `italic`, `blink`, `reverse`, `strike`, `font`, `dwl`,
  `dhl`, `chars`, and `runLength`.
- `ScreenCell`: `char`, `combiningChars`, `fgRed`, `fgGreen`, `fgBlue`,
  `bgRed`, `bgGreen`, `bgBlue`, `bold`, `italic`, `underline`, `reverse`,
  `strike`, and `width`.

The release verifier now treats the class and field names as one JNI contract.
For all 31 fields it checks the R8 mapping for renaming and the assembled release
DEX for the exact original name. Removal of either keep rule or disappearance of
a required field therefore fails the release gate.

Artifact names remain immutable. Alpha.2 and its recorded checksum continue to
identify the rejected bytes; the correction advances the version to alpha.3.

## Acceptance evidence

The alpha.3 source passed unit tests, lint, debug assembly, minified release
assembly, and the expanded JNI verifier. APK inspection found all 31 field names
under the exact unrenamed classes.

A disposable off-repository key then exercised the minified alpha.3 APK on API
35 against the OpenSSH fixture:

1. install, cold launch, host verification, and password SSH connection;
2. raw-terminal open on the already-live PTY without a process restart;
3. raw `pwd` input returning `/home/threadline`;
4. portrait-to-landscape-to-portrait resize while the terminal remained alive;
5. background, launcher restore, and continued use of the same app process;
6. return to the transcript; and
7. a structured `pwd` returning `/home/threadline`, exit 0.

The process ID remained unchanged through the stress path, and Logcat contained
no fatal signal, `NoSuchFieldError`, or Android runtime exception. This proves
the corrected minified path locally; it does not substitute for installing the
permanent-key artifact on the physical device.

## Remaining boundary

Build immutable permanent-key alpha.3, confirm its certificate matches the
established update lineage, and install it over physical alpha.2. The physical
release checklist must repeat password and imported-key authentication,
structured execution, raw-terminal open/input/resize/return, lifecycle, retained
data, and sanitized diagnostics before any tester distribution decision.
