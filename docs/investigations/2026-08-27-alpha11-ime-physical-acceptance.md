# Alpha.11 IME physical acceptance

Date: 2026-08-27

## Result

The permanent-key alpha.11 build passes the owner-device transcript and
software-keyboard boundary. It is the current accepted tester build.

## Artifact evidence

The APK embeds exact merged-main source commit
`8085deafd107f4b80445869677f1bda8b59930dd`, which passed public CI. The signed
artifact:

- identifies as `io.github.r055le.threadline`, version `0.1.0-alpha.11`
  (`10011`)
- has SHA-256
  `60e8df47b8438584c18d559a246c2cdd462645e855c1ac77afb2c5e98b5c06fa`
- matches the permanent release certificate
- verifies with APK signature schemes v2 and v3
- is 16 KiB page aligned

Android installed it on the Galaxy S25 Ultra and the app connected normally.

## Physical evidence

The owner repeated the same four-shot sequence used for alpha.9 and alpha.10:

1. The connected screen opened with an empty transcript.
2. Focusing the composer opened Gboard while the header stayed in place and the
   transcript viewport resized above it.
3. A short command completed while Gboard remained open. The visible card
   showed its command, success metadata, working directory, timing, exit code,
   and output without manual scrolling.
4. Dismissing Gboard exposed the full successful card and its actions.

The SSH session and command path remained functional throughout. This is the
useful-content behavior alpha.9 and alpha.10 lacked, so the transcript IME
viewport boundary is closed.

## What remains separate

Alpha.11 did not change the raw terminal. Explicit keyboard recovery after IME
dismissal and a more reachable Transcript/Terminal switch remain planned in
`docs/BACKLOG.md` for the next terminal-control slice.
