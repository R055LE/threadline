#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_dir=$(CDPATH= cd -- "$script_dir/.." && pwd -P)

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /absolute/path/to/threadline-release.p12" >&2
    exit 64
fi

destination=$1
case "$destination" in
    /*) ;;
    *)
        echo "Choose an absolute destination outside the Threadline repository." >&2
        exit 64
        ;;
esac

destination_parent=$(dirname -- "$destination")
if [ ! -d "$destination_parent" ]; then
    echo "Create the destination directory first with permissions 0700." >&2
    exit 64
fi

canonical_parent=$(CDPATH= cd -- "$destination_parent" && pwd -P)
destination=$canonical_parent/$(basename -- "$destination")
case "$destination" in
    "$repository_dir"/*)
        echo "The release keystore must live outside the Threadline repository." >&2
        exit 64
        ;;
esac

if [ -e "$destination" ]; then
    echo "Refusing to replace existing file: $destination" >&2
    exit 64
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "JDK 17 keytool is required." >&2
    exit 1
fi

umask 077
keytool -genkeypair \
    -keystore "$destination" \
    -storetype PKCS12 \
    -alias threadline-release \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Threadline"

echo "Created release keystore: $destination"
echo "Alias: threadline-release"
echo "Back up the keystore and its password separately before building an alpha."
