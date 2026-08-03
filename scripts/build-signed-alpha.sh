#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_dir=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
# shellcheck source=project-metadata.sh
THREADLINE_METADATA_REPOSITORY_DIR=$repository_dir
. "$script_dir/project-metadata.sh"
unset THREADLINE_METADATA_REPOSITORY_DIR

if [[ -z ${THREADLINE_RELEASE_STORE_FILE:-} ]]; then
    echo "Set THREADLINE_RELEASE_STORE_FILE to the release keystore path." >&2
    exit 64
fi
if [[ ! -f $THREADLINE_RELEASE_STORE_FILE ]]; then
    echo "Release keystore not found: $THREADLINE_RELEASE_STORE_FILE" >&2
    exit 64
fi
store_parent=$(CDPATH= cd -- "$(dirname -- "$THREADLINE_RELEASE_STORE_FILE")" && pwd -P)
THREADLINE_RELEASE_STORE_FILE=$store_parent/$(basename -- "$THREADLINE_RELEASE_STORE_FILE")
case "$THREADLINE_RELEASE_STORE_FILE" in
    "$repository_dir"/*)
        echo "The release keystore must live outside the Threadline repository." >&2
        exit 64
        ;;
esac

THREADLINE_RELEASE_KEY_ALIAS=${THREADLINE_RELEASE_KEY_ALIAS:-threadline-release}

find_sdk_tool() {
    tool_name=$1
    if command -v "$tool_name" >/dev/null 2>&1; then
        command -v "$tool_name"
        return
    fi

    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        [[ -n $sdk_root && -d $sdk_root/build-tools ]] || continue
        tool_path=$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 \
            -type f -name "$tool_name" -print | sort -V | tail -n 1)
        if [[ -n $tool_path ]]; then
            printf '%s\n' "$tool_path"
            return
        fi
    done

    echo "Could not find Android SDK tool: $tool_name" >&2
    exit 1
}

apksigner_command=$(find_sdk_tool apksigner)
zipalign_command=$(find_sdk_tool zipalign)
output_dir=${THREADLINE_ALPHA_OUTPUT_DIR:-$repository_dir/dist}
output_name=threadline-${THREADLINE_VERSION_NAME}.apk
output_apk=$output_dir/$output_name
aligned_apk=$output_dir/.threadline-${THREADLINE_VERSION_NAME}-aligned.apk
unsigned_apk=$repository_dir/app/build/outputs/apk/release/app-release-unsigned.apk

umask 077
install -d "$output_dir"
if [[ -e $output_apk || -e $output_apk.sha256 ]]; then
    echo "Refusing to replace an existing alpha artifact; increment the version first." >&2
    exit 64
fi
cd "$repository_dir"
./gradlew --no-daemon :app:assembleRelease

if [[ ! -f $unsigned_apk ]]; then
    echo "Unsigned release APK was not produced at $unsigned_apk" >&2
    exit 1
fi

if [[ -z ${THREADLINE_RELEASE_STORE_PASSWORD:-} ]]; then
    read -r -s -p "Keystore password: " THREADLINE_RELEASE_STORE_PASSWORD
    printf '\n'
fi
if [[ -z ${THREADLINE_RELEASE_KEY_PASSWORD:-} ]]; then
    read -r -s -p "Key password (usually the same): " THREADLINE_RELEASE_KEY_PASSWORD
    printf '\n'
fi
export THREADLINE_RELEASE_STORE_PASSWORD THREADLINE_RELEASE_KEY_PASSWORD
trap 'unset THREADLINE_RELEASE_STORE_PASSWORD THREADLINE_RELEASE_KEY_PASSWORD; rm -f "$aligned_apk"' EXIT
"$zipalign_command" -f -p 4 "$unsigned_apk" "$aligned_apk"
"$apksigner_command" sign \
    --ks "$THREADLINE_RELEASE_STORE_FILE" \
    --ks-key-alias "$THREADLINE_RELEASE_KEY_ALIAS" \
    --ks-pass env:THREADLINE_RELEASE_STORE_PASSWORD \
    --key-pass env:THREADLINE_RELEASE_KEY_PASSWORD \
    --out "$output_apk" \
    "$aligned_apk"
"$apksigner_command" verify --verbose --print-certs "$output_apk"

(
    cd "$output_dir"
    sha256sum "$output_name" > "$output_name.sha256"
)

echo "Signed alpha: $output_apk"
echo "Checksum: $output_apk.sha256"
