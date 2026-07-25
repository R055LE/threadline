# Contributing

Threadline is in a dependency-spike phase. Please keep changes within the
current milestone in `PROJECT_SPEC.md` and read `AGENTS.md` before editing.

For a change:

1. Start the OpenSSH fixture when the behavior touches SSH or terminal I/O.
2. Add or update focused tests.
3. Run `./gradlew test`, `./gradlew lint`, and `./gradlew assembleDebug`.
4. State which emulator or device, Android version, and fixture auth method you
   exercised.
5. Do not include passwords, private keys, `.env`, host data, or raw packet
   logs in commits or bug reports.

Phase 0 does not accept transcript cards, SFTP, port forwarding, cloud
services, analytics, AI features, or multi-session work.
