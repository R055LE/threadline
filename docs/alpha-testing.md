# Threadline technical-alpha testing

Threadline's technical alpha tests whether the signed release is useful and
trustworthy during ordinary remote work. It is not a request to attack a
production server, teach SSH to a beginner, or polish every visual detail.

The Phase 5 exit criterion remains ten real users performing small remote tasks
for two weeks and providing useful product feedback. Start with the owner as
tester zero, prove the signed update path, then invite technical testers in a
controlled sequence.

## Before installing

- Obtain the APK from the named public prerelease or directly from the owner
  through the agreed private channel.
- Compare its SHA-256 checksum with the separately published value.
- Confirm the release identifies itself as `io.github.r055le.threadline`.
- Do not accept a repackaged APK, an unexpected signing-certificate fingerprint,
  or installation instructions that ask for an SSH credential.
- Keep the previous alpha APK until the update test is complete.

Debug builds install as `Threadline Debug` under
`io.github.r055le.threadline.debug` and do not share alpha data. The older
pre-alpha `dev.threadline` debug install is also separate and may be removed
after any disposable test data is no longer needed.

## First-use checklist

Record the app version, Android version, device model, and whether installation
was fresh or an update. Do not record a server address, username, fingerprint,
command, output, password, passphrase, or private key.

Exercise only systems and accounts you are authorized to use:

1. Read and acknowledge onboarding.
2. Create a non-credential host profile.
3. Independently verify an unknown host fingerprint before accepting it.
4. Connect with password authentication.
5. Run ordinary commands that demonstrate success, failure, Unicode, ANSI
   color, progress replacement, and a changed working directory.
6. Interrupt a long-running command and immediately run a follow-up command.
7. Enter the raw terminal, type input, and return to the transcript without
   reconnecting.
8. Rotate once, background or lock the device, return, and confirm session state.
9. Disconnect explicitly, reopen saved history, and inspect the retention result.
10. Generate a sanitized diagnostic preview and confirm its default form contains
    no host fields, commands, output, or credentials.

For imported-key authentication, use a disposable or dedicated test key rather
than a high-value production identity during the first alpha pass.

## Update checklist

Install the next alpha over the prior signed release. It must update rather than
create another application. Before uninstalling either version, confirm that
the update preserves:

- saved host profiles without credentials;
- trusted-host records;
- encrypted saved private keys and their labels;
- transcript-retention settings and saved bounded history; and
- onboarding completion.

A signature mismatch, downgrade error, data reset, unusable encrypted key, or
second release icon is an alpha blocker.

## Feedback

Use the repository's public **Technical alpha feedback** issue form only when the
feedback itself can be public. Otherwise send the same answers through an agreed
private channel. Describe the task and friction in general terms, not by pasting
the command or server output.

Attach Threadline's sanitized diagnostic report only after previewing the exact
text. Leave **Include host fields, directories, and recent commands** disabled
unless those fields are essential and safe to disclose.

Useful feedback answers four questions:

1. What kind of remote task were you attempting?
2. Did transcript mode complete it, or did you need the raw terminal or another app?
3. What slowed, confused, or blocked you, and could you recover?
4. Would you reach for Threadline for a similar task again?

## Evidence and metrics boundary

The initial alpha uses explicit feedback and user-triggered sanitized
diagnostics. Threadline does not upload analytics, crash reports, commands,
output, host data, or stable user identifiers.

If manual evidence is insufficient, an optional local-only aggregate summary
may later count connection outcomes, completed or interrupted turns, raw-mode
switches, unexpected disconnects, transcript truncations, and lifecycle
returns. That requires a separate field, retention, consent, export, and reset
design before implementation.
