#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
THREADLINE_METADATA_REPOSITORY_DIR=$repository_dir
. "$repository_dir/scripts/project-metadata.sh"
unset THREADLINE_METADATA_REPOSITORY_DIR

if [ -n "${ADB:-}" ]; then
    adb_command=$ADB
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    adb_command=$ANDROID_SDK_ROOT/platform-tools/adb
elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    adb_command=$ANDROID_HOME/platform-tools/adb
elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
    adb_command=$HOME/Android/Sdk/platform-tools/adb
else
    adb_command=$(command -v adb || true)
fi

if [ -z "$adb_command" ]; then
    echo "Set ADB, ANDROID_SDK_ROOT, or ANDROID_HOME to an Android SDK with adb." >&2
    exit 1
fi

cd "$script_dir"
fixture_password=$(docker compose exec -T openssh printenv THREADLINE_TEST_PASSWORD)
fixture_key_fingerprint=$(ssh-keygen -lf .state/client_ed25519.pub | awk '{print $2}')

cd "$repository_dir"
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest

"$adb_command" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$adb_command" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    >/dev/null

fixture_key_file=files/threadline-fixture-private-key
cleanup_fixture_key() {
    "$adb_command" shell run-as "$THREADLINE_DEBUG_APPLICATION_ID" rm -f "$fixture_key_file" \
        >/dev/null 2>&1 || true
}
trap cleanup_fixture_key EXIT INT TERM
"$adb_command" shell run-as "$THREADLINE_DEBUG_APPLICATION_ID" mkdir -p files
"$adb_command" exec-in run-as "$THREADLINE_DEBUG_APPLICATION_ID" sh -c \
    "cat > $fixture_key_file" \
    < "$script_dir/.state/client_ed25519"

instrumentation_output=$("$adb_command" shell am instrument -w \
    -e class dev.threadline.core.session.AndroidStructuredShellIntegrationTest \
    -e threadlineFixturePassword "$fixture_password" \
    -e threadlineFixtureKeyFingerprint "$fixture_key_fingerprint" \
    "$THREADLINE_DEBUG_TEST_APPLICATION_ID/androidx.test.runner.AndroidJUnitRunner")
printf '%s\n' "$instrumentation_output"

case "$instrumentation_output" in
    *"FAILURES!!!"*|*"INSTRUMENTATION_FAILED:"*)
        exit 1
        ;;
    *"OK ("*)
        ;;
    *)
        echo "Instrumentation ended without a recognized success result." >&2
        exit 1
        ;;
esac
