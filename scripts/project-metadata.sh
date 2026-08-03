#!/bin/sh

if [ -n "${THREADLINE_METADATA_REPOSITORY_DIR:-}" ]; then
    metadata_repository_dir=$THREADLINE_METADATA_REPOSITORY_DIR
else
    metadata_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
    metadata_repository_dir=$(CDPATH= cd -- "$metadata_script_dir/.." && pwd)
fi
metadata_properties=$metadata_repository_dir/gradle.properties

threadline_property() {
    property_name=$1
    sed -n "s/^${property_name}=//p" "$metadata_properties"
}

THREADLINE_RELEASE_APPLICATION_ID=$(threadline_property threadline.releaseApplicationId)
THREADLINE_DEBUG_APPLICATION_ID=${THREADLINE_RELEASE_APPLICATION_ID}.debug
THREADLINE_DEBUG_TEST_APPLICATION_ID=${THREADLINE_DEBUG_APPLICATION_ID}.test
THREADLINE_VERSION_CODE=$(threadline_property threadline.versionCode)
THREADLINE_VERSION_NAME=$(threadline_property threadline.versionName)

if [ -z "$THREADLINE_RELEASE_APPLICATION_ID" ] ||
    [ -z "$THREADLINE_VERSION_CODE" ] ||
    [ -z "$THREADLINE_VERSION_NAME" ]; then
    echo "Threadline release metadata is incomplete in gradle.properties." >&2
    exit 1
fi
