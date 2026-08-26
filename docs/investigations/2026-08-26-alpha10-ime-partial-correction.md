# Alpha.10 IME partial correction

Date: 2026-08-26

## Result

The permanent-key alpha.10 build is rejected. It corrected the production
window resize behavior on the Galaxy S25 Ultra, and the SSH session remained
functional, but a completed card still did not present its useful content while
Gboard was open.

## Artifact evidence

The exact merged-main candidate passed public CI and local release verification.
The signed APK:

- identifies as `io.github.r055le.threadline`, version `0.1.0-alpha.10`
  (`10010`)
- has SHA-256
  `94f1add7585cacbac177455dffbb9e553f2dd5c7de0dfd4a8620e62bce86f04f`
- matches the permanent release certificate
- verifies with APK signature schemes v2 and v3
- is 16 KiB page aligned

Android installed it over alpha.9 with retained app data. The installed app
connected and completed a structured command.

## Physical evidence

The owner-device check repeated the empty-transcript sequence:

1. The connected header and action row stayed in place when Gboard opened.
2. The transcript viewport resized above the composer.
3. Submitting a short command completed normally.
4. The bottom of the new card, including its copy actions, was visible.
5. The command, status, and output remained above the visible window.
6. Closing Gboard immediately exposed the full successful card.

This confirms that alpha.10 fixed the Samsung window-pan mismatch found in
alpha.9. It did not satisfy the product acceptance boundary because the visible
part of the card was the least useful part.

## Cause

Transcript following still targeted the synthetic list-tail item. When a
completed card was taller than the constrained list viewport, aligning that
tail exposed the card's bottom actions and scrolled past its command, status,
and output.

The earlier real-Gboard regression asserted that the output node was displayed
on the Pixel emulator, but it did not independently constrain the transcript
viewport or require the completed card to remain at its start. It therefore did
not encode the presentation policy exposed by the Samsung result.

## Alpha.11 correction boundary

Completed turns now target the start of the newest card. Submitted, running,
and stopping turns retain bottom-tail following so streaming output stays
current. Deliberate user scrolling still disables automatic following.

A new constrained-height regression requires the first completed card to have
item index 0, scroll offset 0, and visible output. Before the policy change it
failed with a 218 px scroll offset. It passes after the correction, and a
separate regression confirms that active output still follows the tail.

The full API 35 emulator run finished 79 tests with three expected
fixture-dependent skips and no failures. JVM tests, lint, debug and minified
release assembly, the signing-helper tests, and the release shrinker contract
also pass in the alpha.11 worktree.

Exact merged-main CI, permanent signing, in-place installation, and the same
owner-device sequence remain required for alpha.11 acceptance.

## Separate findings

The same physical pass found no explicit way to request the raw-terminal
keyboard after dismissing it and confirmed that the far-left mode switch is
awkward for right-handed reach. Those belong to the next terminal-control
slice and are tracked in `docs/BACKLOG.md`; they are outside alpha.11.
