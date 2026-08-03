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

cd "$repository_dir"
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest

"$adb_command" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$adb_command" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    >/dev/null

instrumentation_output=$("$adb_command" shell am instrument -w \
    -e class dev.threadline.core.session.AndroidLargeOutputPerformanceTest \
    -e threadlineFixturePassword "$fixture_password" \
    "$THREADLINE_DEBUG_TEST_APPLICATION_ID/androidx.test.runner.AndroidJUnitRunner")
printf '%s\n' "$instrumentation_output"

performance_output=$("$adb_command" logcat -d -t 500 | \
    sed -n '/ThreadlinePerf: THREADLINE_PERF/p' | tail -n 20)

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

printf '%s\n' "$performance_output"
if [ -z "$performance_output" ]; then
    echo "Instrumentation completed without performance metrics." >&2
    exit 1
fi
