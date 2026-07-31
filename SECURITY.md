# Security policy

Threadline is an exploratory proof of concept and has no supported production
release. Do not use it for privileged or sensitive systems.

Please report a suspected vulnerability privately to the repository owner
rather than opening a public issue. Include a minimal reproduction and redact
hostnames, usernames, commands, terminal output, fingerprints, credentials,
and private-key material.

The project will never ask for a password, private key, passphrase, or decrypted
credential in an issue or pull request.

Saved transcript history is local but unencrypted. Commands and remote output
can contain sensitive values even though Threadline never places authentication
credentials in the transcript schema. Device backup and transfer are disabled,
history is bounded, and an ephemeral-session option prevents archive writes.
Per-session delete and clear-all are logical SQLite deletion and are not a
guarantee of forensic erasure from underlying storage. An abrupt process kill
can lose the current unfinished session before its final archive write.
