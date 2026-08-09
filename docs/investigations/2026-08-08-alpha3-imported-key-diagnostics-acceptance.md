# Alpha.3 imported-key and diagnostic acceptance (2026-08-08)

## Status

Accepted for the permanent-key `0.1.0-alpha.3` (`10003`) artifact on the
Galaxy S25 Ultra. The release build authenticated with a disposable imported
private key before and after a force-stop/reopen cycle, and its default
diagnostic preview reported the documented privacy boundary exactly.

This closes the previously unreported imported-key and diagnostic checks. It
does not yet prove that an encrypted saved key survives an installed version
update; that remains the final candidate-update check before distribution.

## Disposable fixture preparation

The existing ignored fixture-only Ed25519 identity was used rather than a
production credential. Before transfer:

- the local private/public pair was checked for consistency;
- the loopback-only OpenSSH fixture was started without rotating its retained
  host identity; and
- host-side authentication succeeded with password authentication disabled.

A mode-`0600` temporary copy of the disposable private key was staged outside
the repository for SFTP transfer. The transfer copy and its containing
temporary directory were absent when cleanup was checked, and the fixture was
already stopped. The ignored fixture source remains available for repeatable
local testing. No private key, passphrase, password, private endpoint, or
machine path is recorded here.

## Physical imported-key evidence

The owner selected **Private key**, chose the disposable OpenSSH key, enabled
**Save encrypted on this device**, and left the optional passphrase empty
because the fixture key itself is intentionally unencrypted. The permanent
alpha.3 release then connected and completed a structured `pwd` turn.

After an explicit disconnect and force-stop, the owner reopened Threadline,
selected the saved encrypted key, reconnected, and completed `pwd` again. This
is direct release-artifact evidence that import, Android Keystore-backed local
storage, reload, decrypt-for-authentication, and real SSH use agree on the
physical device. It complements the earlier automated close/reopen fixture
proof without replacing the still-needed installed-update check.

## Default diagnostic evidence

With **Include host fields, directories, and recent command text** left off,
the owner confirmed that the preview reported all six expected privacy lines:

```text
sensitive_session_details_included: false
host_profile: redacted
command_content: redacted
command_output: never_included
credentials_and_key_material: never_included
host_keys_and_fingerprints: never_included
```

The report itself was not copied into the repository or conversation. This
physical check proves that the permanent release presents the same
construction-time exclusion boundary covered by the diagnostic model tests.

## Remaining boundary

- Install the next immutable permanent-key alpha over alpha.3 while the saved
  encrypted fixture key exists. Confirm that the application updates in place,
  retains the key and other local records, and authenticates with the retained
  key after the update.
- Choose limited direct sharing or a public GitHub prerelease deliberately.
- Gather enough real technical-alpha use to evaluate the Phase 5 exit
  criterion.
