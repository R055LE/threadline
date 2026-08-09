# Alpha.4 installed-update preservation

**Date:** 2026-08-09
**Phase:** 5, alpha polish
**Status:** Accepted

## Boundary

Alpha.3 had already proved that an encrypted imported key could authenticate
after force-stop and reopen. That was a process-persistence check. This
milestone had to prove the separate Android update boundary: install a newer
permanent-key artifact over the existing release app, retain its local state,
and authenticate with the previously saved encrypted key without re-importing
it.

## Candidate and signing record

GitHub Actions produced the unsigned alpha.4 candidate from source commit
`cb49c4fde762288ba48be16870999102aa87c4ab`. The downloaded candidate passed its
published checksum, build-metadata, package, version, unsigned-state, and secret
scan checks before local signing. The permanent release key and passwords did
not enter CI.

The locally signed artifact verified as:

- package: `io.github.r055le.threadline`
- version: `0.1.0-alpha.4` (`10004`)
- APK SHA-256: `e010f7c4c7b9e78e5fa07a9382db8b08aa423dd2525bd25c2336ebe28d3de396`
- signing certificate SHA-256: `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signer subject: `CN=Threadline`
- signature schemes: APK v2 and v3 verified
- alignment: 16 KiB page-aware zip alignment verified

The certificate matches the permanent update lineage established by alpha.1.

## Physical acceptance

The owner installed alpha.4 over alpha.3 on the Galaxy S25 Ultra running
Android 16 / One UI 8.5. Android treated it as an update to the existing app;
there was no second launcher entry.

After the update, the owner confirmed all of the following:

- onboarding remained completed;
- the saved connection profile remained;
- the trusted host record remained;
- transcript history and settings remained;
- the encrypted imported-key label remained;
- Diagnostics reported `0.1.0-alpha.4` and version code `10004`; and
- the retained imported key authenticated without re-import and completed
  `pwd` through the structured transcript.

This closes the installed-update preservation boundary for the current Room,
Android Keystore, signing, and application-identity design.

## Remaining Phase 5 work

- Choose direct invited sharing or a public GitHub prerelease as the alpha
  distribution boundary.
- Gather enough technical-alpha use to evaluate the Phase 5 exit criterion.

Additional OEM coverage remains useful evidence, not a separate release gate.
Future changes to persistence, encryption, application identity, signing, or
Android update behavior should repeat the update-preservation check.

No password, passphrase, private key, private endpoint, or fixture identity is
recorded here.
