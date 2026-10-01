#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
THREADLINE_METADATA_REPOSITORY_DIR=$repository_dir
. "$repository_dir/scripts/project-metadata.sh"
unset THREADLINE_METADATA_REPOSITORY_DIR

adb_command=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
if [ ! -x "$adb_command" ]; then
    echo "Set ADB or ANDROID_HOME to an Android SDK with adb." >&2
    exit 1
fi
if [ "$("$adb_command" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
    echo "Run this fixture test on the Android emulator." >&2
    exit 1
fi

cd "$script_dir"
host_fingerprint=$(docker compose exec -T openssh \
    ssh-keygen -lf /var/lib/threadline-ssh/ssh_host_ed25519_key.pub | awk '{print $2}')

cd "$repository_dir"
./gradlew assembleDebug assembleDebugAndroidTest
"$adb_command" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$adb_command" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    >/dev/null

fixture_key_file=files/threadline-fixture-private-key
preparation_output=$(mktemp)
preparation_pid=
cleanup() {
    if [ -n "$preparation_pid" ]; then
        "$adb_command" shell am force-stop "$THREADLINE_DEBUG_APPLICATION_ID" >/dev/null 2>&1 || true
        wait "$preparation_pid" 2>/dev/null || true
    fi
    "$adb_command" shell run-as "$THREADLINE_DEBUG_APPLICATION_ID" rm -f "$fixture_key_file" \
        >/dev/null 2>&1 || true
    rm -f "$preparation_output"
}
trap cleanup EXIT INT TERM

test_class=dev.threadline.core.session.AndroidTranscriptCheckpointProcessDeathTest
test_runner=$THREADLINE_DEBUG_TEST_APPLICATION_ID/androidx.test.runner.AndroidJUnitRunner
for ephemeral in false true; do
    "$adb_command" shell run-as "$THREADLINE_DEBUG_APPLICATION_ID" mkdir -p files
    "$adb_command" exec-in run-as "$THREADLINE_DEBUG_APPLICATION_ID" sh -c \
        "cat > $fixture_key_file" < "$script_dir/.state/client_ed25519"

    : > "$preparation_output"
    "$adb_command" shell am instrument -w \
        -e class "$test_class" \
        -e threadlineCheckpointPhase prepare \
        -e threadlineEphemeral "$ephemeral" \
        -e threadlineFixtureHostKeyFingerprint "$host_fingerprint" \
        "$test_runner" > "$preparation_output" 2>&1 &
    preparation_pid=$!

    ready=false
    attempts=0
    while [ "$attempts" -lt 60 ]; do
        case "$(cat "$preparation_output")" in
            *THREADLINE_CHECKPOINT_READY*) ready=true; break ;;
        esac
        if ! kill -0 "$preparation_pid" 2>/dev/null; then break; fi
        attempts=$((attempts + 1))
        sleep 1
    done
    if [ "$ready" != true ]; then
        cat "$preparation_output"
        echo "Process-death preparation did not reach its live checkpoint." >&2
        exit 1
    fi
    app_pid=$("$adb_command" shell pidof "$THREADLINE_DEBUG_APPLICATION_ID")
    [ -n "$app_pid" ]
    "$adb_command" shell am force-stop "$THREADLINE_DEBUG_APPLICATION_ID"
    wait "$preparation_pid" || true
    preparation_pid=
    if "$adb_command" shell pidof "$THREADLINE_DEBUG_APPLICATION_ID" >/dev/null; then
        echo "The preparing application process is still alive." >&2
        exit 1
    fi
    echo "Killed live app process $app_pid (ephemeral=$ephemeral)."

    verification_output=$("$adb_command" shell am instrument -w \
        -e class "$test_class" \
        -e threadlineCheckpointPhase verify \
        -e threadlineEphemeral "$ephemeral" \
        "$test_runner")
    printf '%s\n' "$verification_output"
    case "$verification_output" in
        *"FAILURES!!!"*|*"INSTRUMENTATION_FAILED:"*) exit 1 ;;
        *"OK ("*) ;;
        *) echo "Process-death verification did not report success." >&2; exit 1 ;;
    esac
done
