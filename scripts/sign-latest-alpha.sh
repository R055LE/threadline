#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_dir=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
# shellcheck source=project-metadata.sh
THREADLINE_METADATA_REPOSITORY_DIR=$repository_dir
. "$script_dir/project-metadata.sh"
unset THREADLINE_METADATA_REPOSITORY_DIR

if (( $# != 0 )); then
    echo "Usage: $0" >&2
    exit 64
fi

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI is required. Install gh and authenticate before signing." >&2
    exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI is not authenticated. Run: gh auth login" >&2
    exit 1
fi
if ! git -C "$repository_dir" diff --quiet ||
    ! git -C "$repository_dir" diff --cached --quiet; then
    echo "Refusing to sign from a checkout with tracked changes." >&2
    exit 1
fi

current_commit=$(git -C "$repository_dir" rev-parse HEAD)
main_commit=$(gh api 'repos/{owner}/{repo}/commits/main' --jq .sha)
if [[ $current_commit != "$main_commit" ]]; then
    echo "This checkout is not current main." >&2
    echo "Switch to main and pull with --ff-only before signing." >&2
    exit 1
fi

run_id=$(
    gh run list \
        --workflow Android \
        --branch main \
        --commit "$current_commit" \
        --event push \
        --status success \
        --limit 1 \
        --json databaseId \
        --jq '.[0].databaseId // empty'
)
if [[ -z $run_id ]]; then
    echo "No successful main-branch Android push run exists for $current_commit." >&2
    echo "Wait for the required Android workflow to pass before signing." >&2
    exit 1
fi

IFS=$'\t' read -r \
    run_workflow run_conclusion run_event run_branch run_commit run_url < <(
        gh run view "$run_id" \
            --json workflowName,conclusion,event,headBranch,headSha,url \
            --jq '[.workflowName, .conclusion, .event, .headBranch, .headSha, .url] | @tsv'
    )
if [[ $run_workflow != Android ]] ||
    [[ $run_conclusion != success ]] ||
    [[ $run_event != push ]] ||
    [[ $run_branch != main ]] ||
    [[ $run_commit != "$current_commit" ]]; then
    echo "GitHub run $run_id does not match this checkout's successful main build." >&2
    exit 1
fi

candidate_dir=$(mktemp -d "${TMPDIR:-/tmp}/threadline-alpha.XXXXXXXX")
cleanup() {
    rm -r -- "$candidate_dir"
}
trap cleanup EXIT

candidate_name="threadline-${THREADLINE_VERSION_NAME}-UNSIGNED-${current_commit:0:12}"
candidate_apk="$candidate_dir/threadline-${THREADLINE_VERSION_NAME}-UNSIGNED.apk"

echo "Downloading verified candidate from: $run_url"
gh run download "$run_id" \
    --name "$candidate_name" \
    --dir "$candidate_dir"

if [[ ! -f $candidate_apk ]]; then
    echo "Downloaded artifact did not contain: $(basename -- "$candidate_apk")" >&2
    exit 1
fi

THREADLINE_RELEASE_STORE_FILE=${THREADLINE_RELEASE_STORE_FILE:-${XDG_DATA_HOME:-$HOME/.local/share}/threadline/signing/threadline-release.p12}
THREADLINE_RELEASE_KEY_ALIAS=${THREADLINE_RELEASE_KEY_ALIAS:-threadline-release}
export THREADLINE_RELEASE_STORE_FILE THREADLINE_RELEASE_KEY_ALIAS

"$script_dir/build-signed-alpha.sh" "$candidate_apk"
