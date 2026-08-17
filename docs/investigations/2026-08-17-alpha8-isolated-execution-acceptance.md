# Alpha.8 isolated execution acceptance (2026-08-17)

## Result

`0.1.0-alpha.8` (`10008`) is the current accepted tester build. Its permanent
APK passed artifact verification, installed on the Galaxy S25 Ultra, connected
to the disposable OpenSSH fixture, and completed the owner-device isolated
execution boundary.

An isolated strict-mode failure returned the child process's real exit status,
did not run the following statement, did not alter persistent shell state, and
left that shell ready for the next command. This closes the physical acceptance
left open when the source implementation merged.

## Candidate provenance and verification

The candidate came from merged `main` commit `3f9e27a`, built by the successful
public Android workflow run `32041513591`. GitHub returned a transient 502 while
the runner fetched a pinned action, then backed off and retried successfully.
The full job completed, including JVM tests, lint, debug and minified release
assembly, shrinker-contract checks, the API 35 instrumented suite, candidate
packaging, and artifact upload.

The local signing helper selected that exact source-identified CI candidate and
used the off-repository permanent key. Independent checks reported:

- package: `io.github.r055le.threadline`
- version: `0.1.0-alpha.8` (`10008`)
- APK SHA-256:
  `5389284c490e8323943f4cac324713928401ad2836f3cfee3b904827e5cafcec`
- signing-certificate SHA-256:
  `102893bcc2fa4b70fb451661579c717c6c2b917296a99baefa6d9e9d1d13e7fc`
- signer: one 4096-bit RSA key with certificate subject `CN=Threadline`
- APK signature schemes: v2 and v3 verified
- 16 KiB page alignment: verified

The certificate matches the permanent update lineage. The signing key,
passwords, fixture credential, private endpoint, and fixture identity did not
enter the repository or this record.

## Physical execution proof

The signed app connected from the Galaxy S25 Ultra running Android 16 / One UI
8.5 to the real fixture through a temporary private tunnel.

First, persistent Send established state:

```bash
cd /tmp && export THREADLINE_ALPHA8_MARKER=parent
```

The card succeeded with exit 0. The next multiline command used **Run
isolated**:

```bash
set -euo pipefail
export THREADLINE_ALPHA8_MARKER=child
cd /
printf 'isolated:%s:%s\n' "$THREADLINE_ALPHA8_MARKER" "$PWD"
false
printf 'unreachable\n'
```

The card printed `isolated:child:/`, failed with exit 1, and did not print
`unreachable`. A following persistent command then ran in the same connection:

```bash
printf 'persistent:%s:%s\n' "$THREADLINE_ALPHA8_MARKER" "$PWD"
```

It printed `persistent:parent:/tmp` and succeeded with exit 0. Together these
observations prove that the isolated child inherited the starting shell state,
contained its own changes and strict failure, and returned control to the
original persistent shell.

The saved transcript recovered all three commands, their output, and exit
statuses after the session. This adds physical evidence to the existing Room
migration and persistence tests.

## UX findings kept separate

Two findings do not invalidate the execution boundary:

1. With the software keyboard open, transcript submission can move the useful
   command cards and output above the visible viewport. The composer and
   execution remain functional, but the user must scroll back up after sending.
2. The saved transcript retains the isolated execution mode in Room, but its
   dialog formats only command status and exit code. The isolated card therefore
   appears as `Failed · exit 1` instead of visibly including `Isolated`.

Both are recorded in `docs/BACKLOG.md`. The first belongs to IME and transcript
scroll coordination. The second is a narrow history-presentation omission.
Neither should be confused with loss of output, a dropped session, or incorrect
shell semantics.
