# Codex starting prompt

Use the following as the first implementation request:

---

Read `AGENTS.md` and `PROJECT_SPEC.md` completely.

Implement **Phase 0 — Repository and dependency spike** only.

Goals:

1. Create a native Android Kotlin project using Jetpack Compose and Material 3.
2. Add a simple host form suitable for connecting to a local test server.
3. Add a Docker Compose OpenSSH fixture with a documented test user.
4. Evaluate ConnectBot `sshlib` and `termlib` first.
5. Establish a strict host-key verification flow. Do not use an accept-all verifier.
6. Connect, authenticate, request a PTY, start a shell, send bytes, receive bytes, and render the live session in the terminal component.
7. Prove PTY resize.
8. Put the live connection behind a lifecycle-aware session controller and foreground service.
9. Add password authentication for the fixture and key authentication if the selected library makes it practical in this slice.
10. Add tests for state transitions and host-key decisions.
11. Write `docs/adr/0001-ssh-and-terminal-libraries.md` recording the dependency decision, licenses, limitations, and fallback options.

Constraints:

- Do not implement transcript cards yet.
- Do not add Room unless the spike needs it for known-host persistence.
- Do not add SFTP, port forwarding, cloud sync, analytics, AI, or multiple sessions.
- Keep one Gradle application module.
- Keep all secrets out of source control.
- Make the fixture reproducible.
- Run `./gradlew test`, `./gradlew lint`, and `./gradlew assembleDebug`.
- Report any part that could not be proven on the available environment.

At completion, summarize:

- Architecture created
- Dependencies chosen
- How to start the SSH fixture
- How to configure the Android app to reach it from an emulator
- Tests run
- Known gaps
- Exact next step for Phase 1

---
