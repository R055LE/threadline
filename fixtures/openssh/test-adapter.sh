#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)

cd "$script_dir"

fixture_password=$(docker compose exec -T openssh printenv THREADLINE_TEST_PASSWORD)
fixture_fingerprint=$(
    docker compose exec -T openssh \
        ssh-keygen -lf /var/lib/threadline-ssh/ssh_host_ed25519_key.pub |
        awk '{print $2}'
)
published_endpoint=$(docker compose port openssh 22)
fixture_port=${published_endpoint##*:}

cd "$repository_dir"

THREADLINE_FIXTURE_HOST=127.0.0.1 \
THREADLINE_FIXTURE_PORT="$fixture_port" \
THREADLINE_FIXTURE_USER=threadline \
THREADLINE_FIXTURE_PASSWORD="$fixture_password" \
THREADLINE_FIXTURE_PRIVATE_KEY="$script_dir/.state/client_ed25519" \
THREADLINE_FIXTURE_FINGERPRINT="$fixture_fingerprint" \
    ./gradlew --no-daemon --rerun-tasks :ssh-integration:test
