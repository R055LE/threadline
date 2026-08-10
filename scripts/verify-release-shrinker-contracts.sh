#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_dir=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
mapping_file=${1:-$repository_dir/app/build/outputs/mapping/release/mapping.txt}
release_apk=${2:-$repository_dir/app/build/outputs/apk/release/app-release-unsigned.apk}

if [ ! -f "$mapping_file" ]; then
    echo "Release mapping not found: $mapping_file" >&2
    exit 1
fi
if [ ! -f "$release_apk" ]; then
    echo "Release APK not found: $release_apk" >&2
    exit 1
fi

find_apkanalyzer() {
    if command -v apkanalyzer >/dev/null 2>&1; then
        command -v apkanalyzer
        return
    fi

    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
        [ -n "$sdk_root" ] || continue
        candidate=$(find "$sdk_root/cmdline-tools" -type f -name apkanalyzer -print 2>/dev/null |
            sort -V | tail -n 1)
        if [ -n "$candidate" ]; then
            printf '%s\n' "$candidate"
            return
        fi
    done

    echo "Could not find Android SDK tool: apkanalyzer" >&2
    exit 1
}

verify_class_fields() {
    class_name=$1
    shift
    class_mapping=$(awk -v expected_header="$class_name -> $class_name:" '
        $0 == expected_header {
            found = 1
            in_class = 1
            print
            next
        }
        in_class && $0 !~ /^ / {
            exit
        }
        in_class {
            print
        }
        END {
            if (!found) exit 2
        }
    ' "$mapping_file") || {
        echo "$class_name is missing or renamed in the release mapping." >&2
        exit 1
    }

    for field_name do
        if printf '%s\n' "$class_mapping" | awk -v expected="$field_name" '
            $2 == expected && $3 == "->" && $4 != expected { found = 1 }
            END { exit found ? 0 : 1 }
        '
        then
            echo "R8 renamed JNI field $class_name.$field_name." >&2
            exit 1
        fi
        if ! awk -v expected_class="$class_name" -v expected_field="$field_name" '
            $1 == "F" &&
            $6 == expected_class &&
            $8 == expected_field { found = 1 }
            END { exit found ? 0 : 1 }
        ' "$dex_listing"
        then
            echo "Release DEX is missing JNI field $class_name.$field_name." >&2
            exit 1
        fi
    done
}

verify_class_identity() {
    class_name=$1
    if ! grep -Fqx "$class_name -> $class_name:" "$mapping_file"; then
        echo "$class_name is missing or renamed in the release mapping." >&2
        exit 1
    fi
    if ! awk -v expected_class="$class_name" '
        $1 == "C" && $6 == expected_class { found = 1 }
        END { exit found ? 0 : 1 }
    ' "$dex_listing"
    then
        echo "Release DEX is missing required runtime class $class_name." >&2
        exit 1
    fi
}

apkanalyzer_command=$(find_apkanalyzer)
dex_listing=$(mktemp)
trap 'rm -f "$dex_listing"' EXIT
"$apkanalyzer_command" dex packages --defined-only "$release_apk" > "$dex_listing"

verify_class_identity org.connectbot.sshlib.crypto.ed25519.Ed25519Provider
verify_class_identity org.connectbot.sshlib.crypto.ed25519.Ed25519KeyFactory
verify_class_identity org.connectbot.sshlib.crypto.ed25519.Ed25519KeyPairGenerator

verify_class_fields org.connectbot.terminal.CellRun \
    fgRed fgGreen fgBlue bgRed bgGreen bgBlue bold underline italic blink \
    reverse strike font dwl dhl chars runLength
verify_class_fields org.connectbot.terminal.ScreenCell \
    char combiningChars fgRed fgGreen fgBlue bgRed bgGreen bgBlue bold italic \
    underline reverse strike width

echo "Release shrinker contracts verified."
