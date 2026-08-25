# Alpha.9 IME physical rejection

Date: 2026-08-24

## Result

The permanent-key alpha.9 build is rejected. It installed successfully and the
structured shell remained functional, but it did not keep the newest transcript
card visible while Gboard was open on the Galaxy S25 Ultra.

## Physical evidence

The owner-device check started with an empty transcript:

1. Focusing the composer opened Gboard.
2. Submitting a short command completed the turn and enabled command history.
3. The completed card remained above the visible window while the keyboard was
   open.
4. Closing the keyboard immediately revealed the successful card.

This isolates the failure to window and transcript presentation. The command,
captured output, SSH session, and recovery path remained intact.

## Cause

Alpha.9 made tail-following react when the `LazyColumn` viewport height changed.
The production `MainActivity` also enables edge-to-edge layout, but its manifest
left `android:windowSoftInputMode` unspecified. The Pixel emulator resized the
generic activity used by the regression. The Samsung device instead panned the
production window to expose the focused composer, so the list viewport did not
provide the resize signal the alpha.9 effect expected.

Android's current
[edge-to-edge setup guidance](https://developer.android.com/develop/ui/compose/system/setup-e2e)
requires an activity using the software keyboard to declare `adjustResize` so
the app receives IME insets for layout adjustment.

## Correction boundary

Alpha.10 declares `adjustResize` on production `MainActivity`. An instrumented
test checks that exact manifest contract. The real-Gboard UI regression now
starts with an empty transcript and submits the first command, matching the
physical failure sequence.

Before the manifest change, the contract failed with adjustment mode `0`
instead of `16`. After the change, the manifest contract and first-command IME
regression pass on the Pixel 6 API 35 emulator. The full emulator run completed
78 tests with three expected fixture-dependent skips. JVM tests, lint, debug and
minified release assembly, the signing-helper tests, and the release shrinker
contract also pass.

## Still required

- Pass the merged-main release gate.
- Sign the exact merged-main alpha.10 candidate with the permanent key.
- Install alpha.10 over alpha.9 and preserve existing app data.
- Repeat the owner-device keyboard sequence, including running output,
  completion, keyboard dismissal, rotation, large font, and deliberate manual
  scrolling.
