# Phase 5 Samsung physical-device validation (2026-08-01)

## Status

Accepted. Direct user-observed validation completed on the Samsung hardware and
software described below.

Scope clarification recorded after the pass: this result satisfies Phase 5's
dedicated physical-device boundary. Pixel and other OEM coverage remain useful
opportunistic evidence during technical alpha, not separate handset-specific
release gates.

## Environment

- Samsung Galaxy S25 Ultra
- Android 16
- One UI 8.5
- Debug APK produced by the Phase 5 regression gate
- Private SSH transport to the disposable OpenSSH fixture through a local
  Termius forwarding rule

No fixture password, private network address, or other runtime credential is
recorded here.

## Completed evidence

### Installation, trust, and structured lifecycle

- The APK installed and opened on the physical device.
- Threadline connected through the private tunnel to the disposable fixture.
- The presented ED25519 fingerprint matched the independently obtained fixture
  fingerprint before trust was accepted.
- `printf 'physical-device-ok\n'` completed and rendered a functional success
  card.
- `cd /tmp` completed and updated the structured current directory.
- A following `pwd` rendered `/tmp`, proving persistent shell state.
- `false` rendered as a functional failed command with exit status 1 without
  disconnecting the session.

### Transcript rendering

A user-supplied physical-device image confirmed that one combined output turn:

- rendered ANSI `red` in red without exposing escape sequences;
- collapsed carriage-return progress from `step 1` to only `step 2`;
- preserved `π`, Japanese text, and the rocket emoji intact;
- reported success, `/tmp`, a 1.1-second duration, and exit 0; and
- wrapped the submitted command without clipping its text or the card actions.

The two action rows consume noticeable vertical space on the phone, but all
four actions remain visible and reachable. This is visual-polish input rather
than a functional failure in this validation slice.

### Bounded large output

- `threadline-test-output volume` completed without a meaningful freeze.
- The truncated/latest-output behavior and expansion/collapse controls were
  functional.
- Expanding the retained tail took roughly one to two seconds; collapsing was
  effectively immediate.
- The user identified three non-blocking polish opportunities: chunked or lazy
  expansion, a position indicator for unusually long output, and a collapse
  action that remains visible instead of moving below the expanded content.

These observations are captured in the backlog. They do not invalidate the
current bounded-output or responsiveness acceptance evidence.

### Interruption and recovery

- A tight builtin-only `while`/`printf` stream remained responsive enough to
  expose the Stop action.
- Stop completed the turn as interrupted with exit status 130 in approximately
  2.9 seconds.
- The session remained connected, and a follow-up `printf` command could be
  entered and completed within the user's normal reaction time.
- While the stream was active, the composer was disabled, so the follow-up
  could not be drafted in advance. Drafting during execution and a possible
  explicit command queue are recorded as distinct backlog ideas rather than
  being folded into this validation slice.

### Interactive raw-terminal fallback

- `less /etc/services` triggered the expected interactive-program suggestion.
- The action switched to the existing raw terminal and preserved the active
  PTY content.
- On the first transition, tapping the terminal surface did not summon the
  software keyboard, so `q` could not initially be sent.
- Without a code or configuration change, a later tap did summon the keyboard
  and input then behaved as expected.

The terminal input bridge is therefore functional, but the first physical
focus/IME miss remains an intermittent observation rather than being erased by
the retry. Subsequent input and rotation checks did not reproduce it; the
conclusion records the narrower monitored boundary.

### Rotation

- The active raw-terminal view rotated to landscape and returned to portrait
  without losing the session, terminal controls, or visible command state.
- The session actions and horizontally scrollable extra-key row remained
  reachable in landscape.
- The software keyboard opened during this cycle, so the earlier IME miss did
  not reproduce through rotation.
- Gboard occupied almost all remaining landscape height, leaving little useful
  terminal viewport. The user recognized this as the usual phone-keyboard
  landscape constraint, and portrait recovery showed no issue. It is recorded
  as context rather than a Threadline rotation failure.

### Background, lock, and resume

- A `sleep 20` command followed by a printable completion marker was submitted
  while connected.
- Threadline was sent to the background, the phone was locked past command
  completion, and the app was then resumed.
- The user reported that the sequence worked perfectly: the session and
  completed structured turn returned without a visible lifecycle, transcript,
  or output problem.

### Trust and history persistence

- An explicit disconnect followed by reconnection to the same endpoint reused
  the saved host-key record without presenting another trust prompt.
- The new connection created a fresh shell rather than pretending the previous
  shell state survived.
- After disconnect, transcript history retained the earlier physical-device
  turns, including Unicode, bounded volume, interruption, and background-resume
  evidence.
- The archive was functional but visually raw. Its later presentation needs are
  recorded as polish rather than treated as a persistence failure.

### Product observations from the physical flow

- Host profiles cannot persist password credentials. This is currently an
  intentional session-only security boundary, not merely unfinished
  encryption; an opt-in saved-password policy is recorded for reconsideration.
- Returning to host/history management requires disconnecting the active
  session. A single retained-session dashboard and true concurrent session
  management are recorded as separate future scopes.

### Maximum font scale

Two user-supplied images confirmed the connected transcript and raw terminal at
Samsung's maximum font-size setting:

- the title and structured current directory wrapped without overlapping the
  content below;
- the horizontally scrollable session-action row retained access to Terminal,
  Diagnostics, Ctrl-C, and Disconnect, although off-screen actions were only
  partially visible until scrolled;
- the command, metadata, output, composer, history navigation, and all four
  completed-turn actions remained reachable;
- the raw terminal remained legible and the horizontally scrollable extra-key
  row retained Control, Alt, Escape, Tab, and navigation access; and
- switching between transcript and terminal continued to work.

The result is intentionally low-density and visually awkward at the extreme
setting, but no control overlap, disappearance, or functional failure was
observed. This satisfies the large-font reachability boundary rather than a
visual-polish standard.

### Manual TalkBack

- Manual TalkBack traversal completed on the physical Samsung device.
- The user reported that Threadline's navigation and controls worked correctly,
  without identifying an unlabeled symbol, unreachable action, focus trap, or
  misleading Threadline announcement.
- TalkBack itself was extremely unpleasant to operate as a sighted developer.
  That platform/developer-experience reaction is explicitly distinguished from
  Threadline behavior and is not recorded as an app defect.

## Conclusion

Threadline passed the Samsung physical-device boundary for installation,
strict host trust, password authentication, structured command lifecycle,
Unicode/ANSI/progress rendering, bounded volume, interruption and recovery,
raw fallback, rotation, background/lock/resume, trust and history persistence,
maximum font scale, and manual TalkBack navigation.

One initial tap in the raw terminal did not summon the software keyboard. The
failure disappeared without a code or configuration change and did not recur
through later input and rotation checks. It remains a monitored backlog signal,
not a reproducible acceptance failure.
