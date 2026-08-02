# Codex starting prompt

Use the following as the first implementation request:

---

Read `AGENTS.md`, `PROJECT_SPEC.md`, and `docs/STATUS.md` completely.

Treat `docs/STATUS.md` as the canonical execution boundary. Inspect the current
implementation, tests, working tree, and recent history before proposing work.
Do not restart a completed phase because an older ADR, investigation, or prompt
describes it as unfinished.

Discuss the smallest unfinished boundary with the user before implementation.
Keep deliberately deferred work in `docs/BACKLOG.md` unless the user promotes
it into the active milestone.

For the selected slice:

1. Preserve the security, lifecycle, and same-session invariants in `AGENTS.md`.
2. Use the reproducible OpenSSH fixture when behavior touches SSH or terminal I/O.
3. Add or update focused tests.
4. Run the narrow relevant checks, then `./gradlew test`, `./gradlew lint`, and
   `./gradlew assembleDebug` when practical.
5. Keep credentials, private endpoints, host data, and raw packet logs out of
   source control and documentation.
6. Update the canonical status, supporting investigation, and backlog as the
   result requires.
7. Report what changed, what was proven, and what remains unproven.

---
