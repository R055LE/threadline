# Permanent alpha.3 physical acceptance (2026-08-03)

## Status

The release-only JNI blocker is closed. The permanent-key
`0.1.0-alpha.3` (`10003`) artifact verifies against the established update
lineage and the corrected raw-terminal path passes on the Galaxy S25 Ultra that
exposed alpha.2. Alpha.3 remains an unpublished technical-alpha candidate while
key-backup recovery, unreported checklist items, the distribution decision, and
the Phase 5 usage criterion remain open.

## Permanent artifact record

The ignored, owner-held alpha.3 APK was inspected without exposing signing
secrets:

- APK SHA-256:
  `694c5f9b1780bd279a3c14de971d822ee024ac1f706cceaeeb486191224d088e`
- signing-certificate SHA-256:
  `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signer: one 4096-bit RSA key with subject `CN=Threadline`
- signatures: APK schemes v2 and v3 verified
- identity: `io.github.r055le.threadline`
- version: `0.1.0-alpha.3` (`10003`)
- checksum sidecar: verified
- 16 KiB page-aware zip alignment: verified

The certificate matches alpha.1 and alpha.2, while the higher version preserves
the immutable artifact sequence. No keystore or signing password is present in
the repository.

## Physical acceptance

The owner reports that the critical alpha.3 path passes on the Galaxy S25 Ultra
running Android 16 / One UI 8.5. Password SSH connected, the structured thread
and raw terminal both functioned, and the alpha.2 terminal-opening crash did not
recur.

The owner extended the check beyond a single transition by leaving `ping`
running, switching repeatedly between transcript and terminal, and rotating in
each view. The command and live session survived those changes. This directly
exercises the product invariant that both views share one PTY while also
repeating the resize boundary that failed in alpha.2.

This is user-reported physical evidence rather than automated pixel or Logcat
instrumentation. It complements the disposable alpha.3 emulator proof, where
the same process survived raw input, both rotations, background/restore,
transcript return, and a follow-up structured command without a fatal log.

## Landscape UX observation

Rotation correctness and landscape usability are separate claims. Threadline
preserved state and remained functional through rotation, but the supplied
landscape screenshot with the software keyboard open leaves almost no readable
terminal viewport after the connection header and action row. A Termius
comparison retains a small terminal area by using denser landscape chrome.

That comparison is a constraint reference, not a request to duplicate Termius.
Threadline remains portrait-first and should keep ordinary Android rotation for
wide lines, tablets, foldables, and hardware keyboards. The later responsive
pass should establish a minimum visible raw-terminal viewport and evaluate a
compact header, overflow for secondary actions, or a focused/immersive terminal
presentation. The backlog owns that visual/interaction work; it does not reopen
the accepted session or JNI behavior.

The attached third-party and device screenshots are not copied into the public
repository. Their relevant observations are captured textually without
retaining device chrome or another application's interface as project assets.

## Remaining boundary

- Confirm restorable, separately held backups of the permanent signing key.
- Complete still-unreported candidate checklist items, especially imported-key
  authentication and the sanitized diagnostic preview.
- Choose limited direct sharing or a public GitHub prerelease deliberately.
- Gather enough real technical-alpha use to evaluate the Phase 5 exit criterion.
