# Alpha.12 signed-install checkpoint

Date: 2026-09-08

## Result

The permanent-key alpha.12 artifact is signed and mechanically verified. The
owner reports that it installed successfully. This is a checkpoint, not
completed owner-device acceptance: the functional alpha checklist has not been
run, and alpha.11 remains the current accepted tester build.

## Artifact evidence

The APK embeds exact merged-main source commit
`ad120ce4a9527960dbf85cac01139266b358e76e`. The public Android push workflow
for that commit completed successfully in run `34269747959`. The signed
artifact:

- identifies as `io.github.r055le.threadline`, version `0.1.0-alpha.12`
  (`10012`)
- has SHA-256
  `fb72a15f26ddb5b267e52d5c151125c0ddc8f73d2837f0f6eeae27462dae3386`
- matches the established permanent release certificate
- verifies with APK signature schemes v2 and v3
- is 16 KiB page aligned

The checksum file verifies against the APK. No signing key or password entered
the repository, documentation, command arguments, or chat.

## Source included in this checkpoint

Alpha.12 includes the explicit raw-terminal keyboard recovery and reachable
Transcript/Terminal switch already assigned to this version. It also batches
the later UX audit changes rather than producing a signed build for each issue:
visible connection validation, practical host-key review, reduced action
density, task-specific Home routes, compact onboarding, a usable landscape raw
terminal viewport, and the temporary dark-and-green launcher icon.

## Acceptance still pending

- Run the owner-device alpha checklist, including password and retained-key SSH,
  transcript and raw-terminal behavior, rotation, background return, and
  diagnostics.
- Exercise keyboard recovery and the compact landscape terminal behavior on the
  installed release build.
- Review the temporary launcher icon at its actual launcher and app-switcher
  dimensions and under the device's active mask. The owner's current visual
  judgment is that it is good enough for this checkpoint, not that final icon
  sizing is accepted.
- Keep alpha.12 out of tester distribution until those checks decide whether it
  replaces alpha.11 as the accepted build.
