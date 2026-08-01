# Phase 5 basic onboarding (2026-08-01)

## Boundary

This slice prepares Threadline for a technical user who did not participate in its development. It
adds enough product and security orientation to make the first connection self-explanatory. It does
not add accounts, server discovery, a general SSH tutorial, saved passwords, or alpha distribution.

## Product decision

Onboarding is one compact, scrollable introduction with one explicit **Continue to connections**
action. It is not a carousel or a setup wizard. The introduction covers four concepts:

- ordinary commands appear as transcript cards;
- interactive programs use the real terminal on the same SSH session;
- connections go to the endpoint supplied by the user, with unknown fingerprints requiring
  verification and changed host keys blocked; and
- profiles never retain passwords or passphrases, while ephemeral sessions suppress transcript
  retention.

The connection screen exposes **Help** so the same introduction can be reopened. Contextual text at
the profile controls repeats the credential boundary, the existing ephemeral-session copy states
the local-history behavior, the unknown-host dialog directs the user to verify the fingerprint
through a trusted channel, and interactive suggestions now state that Terminal keeps using the
live session.

## Persistence and production defaults

Completion is represented only by an integer introduction version in an app-private
`SharedPreferences` file. No profile, credential, host, transcript, or Room state is involved. The
version lets a future materially different introduction be shown once without turning every copy
change into a recurring prompt.

The production connection draft now begins blank except for standard SSH port `22`. The emulator
fixture values remain available only through a test helper; a new alpha tester no longer opens the
app to a connection form that appears to describe a real saved server.

## Accessibility and edge-to-edge behavior

The welcome title and each concept title are semantic headings. The body scrolls independently,
while the single continue action stays in a fixed bottom surface and remains reachable at 200%
font scale.

A cold-start emulator inspection found that the custom welcome header was drawing underneath the
Android status bar. The correction applies status-bar padding to the welcome, connection, and
connected-session custom headers. This was a real edge-to-edge defect visible in the activity,
not a failure in the isolated composable tests.

## Acceptance evidence

Focused API 35 emulator tests cover:

- the four required concepts, explicit continue action, and heading semantics;
- scrollability and action reachability at 200% font scale;
- completion persistence through a newly constructed preference owner; and
- the connection-screen Help action and profile credential guidance.

The actual `MainActivity` was also cold-started after clearing only the emulator app data. The
first launch showed the introduction with corrected system-bar spacing. Continuing opened a blank
connection form with port `22`; force-stopping and cold-starting the app did not repeat onboarding;
and Help reopened it. The emulator accessibility tree exposed the expected labels and actions.

The final repository gate passed `test`, `lint`, `assembleDebug`, and `assembleRelease` across 104
Gradle tasks. The complete connected API 35 suite reported 65 tests, zero failures or errors, and
the expected three credential-gated production SSH/performance skips. Those fixture-backed cases
were not rerun because this slice did not change SSH, shell, persistence, or terminal data paths.

## Remaining boundary

Basic onboarding is complete. It has not received separate physical-device visual review; the
layout and font-scale behavior are backed by Compose tests and the API 35 emulator. Phase 5 still
requires a physical Pixel pass when hardware becomes available, a signed internal APK, and enough
technical-alpha use to evaluate the real-user exit criterion.
