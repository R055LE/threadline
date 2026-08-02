# Contributing

Threadline is in alpha-polish development. Read `AGENTS.md`, `PROJECT_SPEC.md`,
and the canonical current boundary in `docs/STATUS.md` before editing. Keep
changes within the milestone and scope chosen with the user; do not restart a
completed phase from an older prompt or investigation.

For a change:

1. Start the OpenSSH fixture when the behavior touches SSH or terminal I/O.
2. Add or update focused tests.
3. Run `./gradlew test`, `./gradlew lint`, and `./gradlew assembleDebug`.
4. State which emulator or device, Android version, and fixture auth method you
   exercised.
5. Do not include passwords, private keys, `.env`, host data, or raw packet
   logs in commits or bug reports.

SFTP, port forwarding, cloud services, analytics, AI features, and
multi-session work remain out of the current MVP unless the user explicitly
changes the product scope. Deliberately deferred work belongs in
`docs/BACKLOG.md`, not the active milestone.
