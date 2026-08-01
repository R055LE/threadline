# Phase 5 large-output performance and recovery (2026-07-31)

## Scope

This slice measures the production Android path under sustained output rather
than treating eventual completion as sufficient evidence. It covers the real
ConnectBot SSH adapter, one persistent PTY, `SessionManager`, the structured
marker parser, bounded transcript collector, and libvterm-backed
`TerminalBridge` on the API 35 emulator against the Docker OpenSSH fixture.

No production telemetry, analytics, or persistent profiling log was added. The
credential-gated instrumentation test reports non-secret measurements to
Logcat only while the test is running. The CLI runner retrieves the disposable
fixture password from the container, passes it to the targeted runner, and
prints only the resulting performance measurements.

## Acceptance contract

The profile must prove all of the following:

- 100,000 ANSI-styled Unicode lines complete within 30 seconds;
- a one-megabyte unbroken line completes within 15 seconds;
- 50,000 carriage-return progress rewrites complete within 15 seconds;
- each transcript tail remains exactly bounded at 131,072 rendered characters
  when truncation applies;
- transcript publication remains near the existing 50 ms cadence rather than
  following SSH chunk frequency;
- an infinite output stream completes as interrupted within five seconds of
  Ctrl-C;
- a follow-up structured command completes within five seconds;
- no individual terminal receive call stalls for two seconds;
- retained heap growth stays below 96 MiB while the bounded turns remain in
  the session; and
- after a new session resets the transcript, used heap remains within 48 MiB
  of the connected baseline.

The separate Compose regression expands the complete bounded output and
switches to the raw terminal and back, with a five-second ceiling for each
interaction.

## First live result: a semantic failure

The finite workloads completed, but the first live run timed out after 45
seconds waiting for the infinite stream to finish after Ctrl-C. The existing
integration proof had interrupted `sleep 30`, so the wrapper appeared to have
correct cancellation behavior.

The difference was the command shape. `sleep` is an external foreground
process: SIGINT terminates it, control returns to the wrapper, and the wrapper
can emit its lifecycle marker. The performance probe used a Bash loop composed
only of builtins. The temporary INT trap merely set an `__tl_interrupted` flag
and returned. Bash then resumed the still-infinite loop, so execution never
reached the code that inspected the flag or emitted the end marker.

Output backlog and terminal rendering were investigated first because the
symptom appeared under volume. The corrected runs made that explanation
unnecessary: terminal delivery never stalled longer than 8 ms, while the fixed
INT handler completed the same infinite stream in well under one second.

## Correction

The temporary INT handler now performs the complete interrupted exit path:

1. Set exit status 130.
2. Restore the shell's previous INT trap.
3. Emit the ordinary nonce- and command-scoped end marker with the current
   directory.
4. Return directly from the Threadline wrapper.

The normal completion path is unchanged. The wrapper still temporarily owns
the INT trap during one structured command, and raw mode remains the
compatibility fallback for commands that inspect or replace that trap.

The broader credential-gated regression suite then exposed a narrower startup
race. The wrapper originally emitted its start marker before installing that
temporary trap. Because `SessionManager` makes the command stoppable when it
receives the marker, an immediate Ctrl-C could occasionally arrive in the gap
and leave the app without an end marker. The handler is now installed before
the start marker is published. A generated-shell ordering assertion protects
that invariant, while the live external-process and builtin-loop interruption
tests cover both sides of it.

## Repeatable runner

From `fixtures/openssh/` with the fixture and emulator running:

```bash
./profile-android-large-output.sh
```

The script builds and installs the app and test APK, runs only
`AndroidLargeOutputPerformanceTest`, rejects unrecognized or failed
instrumentation output, and prints the latest `THREADLINE_PERF` records.

## Accepted measurements

Three production-path runs after the builtin-loop correction passed. Times are
milliseconds.

| Measurement | Run 1 | Run 2 | Run 3 | Limit |
|---|---:|---:|---:|---:|
| 100,000 styled Unicode lines | 17,883 | 19,340 | 18,301 | 30,000 |
| One-megabyte line | 1,722 | 1,607 | 1,645 | 15,000 |
| 50,000 progress rewrites | 3,567 | 4,924 | 5,578 | 15,000 |
| Infinite-stream Ctrl-C completion | 438 | 361 | 345 | 5,000 |
| Follow-up command | 12 | 9 | 13 | 5,000 |
| Maximum terminal receive call | 7 | 8 | 8 | 2,000 |

The mixed workload produced 3,700,535 terminal bytes in every run. The test
observed 16,926, 21,338, and 18,143 terminal chunks, but only 258, 278, and 262
transcript publications. This is consistent with the intended bounded update
cadence rather than one state update per SSH chunk.

All three samples reported 4 MiB used heap at the connected baseline, 19 MiB
while the bounded large-output turns remained retained, and 10 MiB after the
next connection reset the transcript. Observed process PSS moved from roughly
132 MiB to 142–143 MiB. The test gates used heap because PSS includes emulator,
runtime, and native allocation behavior that is not stable enough for a
portable pass/fail threshold.

The focused Compose large-output expansion and transcript/raw/transcript switch
also passed its five-second interaction limits.

After closing the start-marker race, the final profile rerun also passed: the
mixed workload completed in 19,746 ms, the long line in 1,667 ms, progress in
3,657 ms, interruption in 419 ms, and the follow-up in 6 ms. Maximum terminal
receive time was 18 ms; retained and reset heap remained 19 MiB and 10 MiB.
The separate production SSH regression then passed both password/session
lifecycle and encrypted-key reload cases.

## What remains unproven

- These are debug-build measurements on one API 35 x86_64 emulator and a local
  Docker fixture, not Samsung or Pixel hardware results.
- Wall time includes remote command generation, SSH transport, terminal input,
  structured parsing, transcript collection, and lifecycle observation. It is
  an end-to-end acceptance measurement, not a microbenchmark of one class.
- The test proves ordered delivery through the production terminal sink and
  responsive view switching, but it does not compare terminal pixels or frame
  timing on physical displays.
- Manual TalkBack, OEM background policy, thermal throttling, and long-running
  alpha usage remain separate Phase 5 boundaries.
