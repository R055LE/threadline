#!/bin/sh
set -eu

fixture_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ ! -f "$fixture_dir/.env" ]; then
    echo "Copy .env.example to .env and set a local test password first." >&2
    exit 64
fi

install -d -m 0700 "$fixture_dir/.state"
if [ ! -f "$fixture_dir/.state/client_ed25519" ]; then
    ssh-keygen -q -t ed25519 -N '' \
        -C threadline-local-fixture \
        -f "$fixture_dir/.state/client_ed25519"
fi

docker compose --project-directory "$fixture_dir" \
    --env-file "$fixture_dir/.env" \
    up --build --detach --wait

echo "Fixture is ready."
echo "Private test key: $fixture_dir/.state/client_ed25519"
