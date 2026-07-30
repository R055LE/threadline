# OpenSSH test fixture

The fixture exposes an OpenSSH server on localhost only. It has one user:
`threadline`. Its password comes from the ignored `.env` file, and `start.sh`
generates an unencrypted Ed25519 client key under the ignored `.state`
directory.

```bash
cd fixtures/openssh
cp .env.example .env
# Edit .env and choose a local-only password.
./start.sh
```

From the development host:

```bash
ssh -p 2222 threadline@127.0.0.1
ssh -p 2222 -i .state/client_ed25519 threadline@127.0.0.1
docker compose exec openssh \
  ssh-keygen -lf /var/lib/threadline-ssh/ssh_host_ed25519_key.pub
```

From the standard Android emulator, use host `10.0.2.2` and port `2222`.
The password is the value in `.env`. For key auth, copy
`.state/client_ed25519` to the emulator and choose it through Android's file
picker.

To exercise the exact production SSH adapter on the development JVM:

```bash
./test-adapter.sh
```

The smoke test verifies the fixture fingerprint, authenticates once by
password and once with the generated Ed25519 key, opens a PTY-backed shell,
resizes it to 41 rows by 101 columns, exchanges raw bytes, and proves the
structured Bash command lifecycle in one persistent shell. It is skipped during
ordinary `./gradlew test` runs unless its fixture environment variables are
present.

With the standard Android emulator running, exercise the production Android
adapter and `SessionManager` together:

```bash
./test-android-structured.sh
```

The script reads the disposable password from the running container, holds it
only in memory, installs the app and test APKs, and passes it directly to the
instrumentation runner. The test proves bootstrap, one-active-command
enforcement, persistent `cd` and `export`, success and failure exit statuses,
multiline input, ANSI/progress/Unicode transcript rendering, exact bounded-tail
retention under 20,000 lines of output, and Ctrl-C completion as an interrupted
turn. The script treats runner failures or an unrecognized result as a nonzero
exit even though `adb shell am instrument` itself can return zero after a test
failure. The test is skipped during ordinary connected-test runs because no
fixture password argument is supplied.

Run `threadline-test-output` in the remote shell for mixed raw output. It also
accepts `ansi`, `progress`, `unicode`, and `volume`.

`volume` emits 100,000 lines. It is an intentional stress probe, not a routine
smoke test. The selected termlib version drains it without dropping the
session; keep the terminal host above the software keyboard so the visible
viewport follows the PTY's actual rows.

To stop the fixture:

```bash
docker compose down
```

To intentionally rotate the server host key for changed-key testing, remove
the fixture volume and start it again:

```bash
docker compose down --volumes
./start.sh
```

For an Android changed-key proof that keeps the accepted baseline available for
restoration, use two Compose project names and an otherwise unused loopback
port:

```bash
THREADLINE_TEST_PORT=2223 docker compose -p threadline-baseline \
  up --build --detach --wait
# Verify and accept the baseline key in an isolated app install.

THREADLINE_TEST_PORT=2223 docker compose -p threadline-baseline down
THREADLINE_TEST_PORT=2223 docker compose -p threadline-changed \
  up --build --detach --wait
# Reconnect to the same host and port; Threadline must block the changed key.

THREADLINE_TEST_PORT=2223 docker compose -p threadline-changed down --volumes
THREADLINE_TEST_PORT=2223 docker compose -p threadline-baseline \
  up --detach --wait
```

`down` without `--volumes` preserves the baseline key. The second project gets
its own host-key volume, so the endpoint changes identity without deleting the
trusted baseline. Compare the public fingerprint at every acceptance step; do
not accept a key merely because a container was expected to own the port.
