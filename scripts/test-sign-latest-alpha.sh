#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_dir=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/threadline-sign-test.XXXXXXXX")
trap 'rm -r -- "$test_root"' EXIT

test_repository=$test_root/repository
test_bin=$test_root/bin
test_home=$test_root/home
signer_log=$test_root/signer.log
mkdir -p "$test_repository/scripts" "$test_bin" "$test_home"
cp "$script_dir/sign-latest-alpha.sh" "$test_repository/scripts/"
cp "$script_dir/project-metadata.sh" "$test_repository/scripts/"

cat > "$test_repository/gradle.properties" <<'EOF'
threadline.releaseApplicationId=io.github.r055le.threadline
threadline.versionCode=10005
threadline.versionName=0.1.0-alpha.5
EOF

cat > "$test_repository/scripts/build-signed-alpha.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ -f $1 ]]
{
    printf 'candidate=%s\n' "$1"
    printf 'store=%s\n' "$THREADLINE_RELEASE_STORE_FILE"
    printf 'alias=%s\n' "$THREADLINE_RELEASE_KEY_ALIAS"
} > "$THREADLINE_TEST_SIGNER_LOG"
EOF
chmod +x "$test_repository/scripts/build-signed-alpha.sh"

cat > "$test_bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

case "${1:-} ${2:-}" in
    "auth status")
        exit 0
        ;;
    "api repos/{owner}/{repo}/commits/main")
        printf '%s\n' "${THREADLINE_TEST_MAIN_COMMIT:-$THREADLINE_TEST_CURRENT_COMMIT}"
        ;;
    "run list")
        [[ ${THREADLINE_TEST_NO_RUN:-0} == 1 ]] || printf '9001\n'
        ;;
    "run view")
        run_commit=${THREADLINE_TEST_RUN_COMMIT:-$THREADLINE_TEST_CURRENT_COMMIT}
        printf 'Android\tsuccess\tpush\tmain\t%s\thttps://example.invalid/run/9001\n' \
            "$run_commit"
        ;;
    "run download")
        shift 2
        artifact_name=
        destination=
        while (( $# > 0 )); do
            case "$1" in
                --name)
                    artifact_name=$2
                    shift 2
                    ;;
                --dir)
                    destination=$2
                    shift 2
                    ;;
                *)
                    shift
                    ;;
            esac
        done
        expected_name="threadline-0.1.0-alpha.5-UNSIGNED-${THREADLINE_TEST_CURRENT_COMMIT:0:12}"
        [[ $artifact_name == "$expected_name" ]]
        printf 'unsigned fixture\n' > "$destination/threadline-0.1.0-alpha.5-UNSIGNED.apk"
        ;;
    *)
        echo "Unexpected gh invocation: $*" >&2
        exit 1
        ;;
esac
EOF
chmod +x "$test_bin/gh"

git -C "$test_repository" init --quiet --initial-branch=main
git -C "$test_repository" config user.email threadline-test@example.invalid
git -C "$test_repository" config user.name "Threadline test"
git -C "$test_repository" add .
git -C "$test_repository" commit --quiet -m fixture
test_commit=$(git -C "$test_repository" rev-parse HEAD)

PATH="$test_bin:$PATH" \
HOME=$test_home \
THREADLINE_TEST_CURRENT_COMMIT=$test_commit \
THREADLINE_TEST_SIGNER_LOG=$signer_log \
    "$test_repository/scripts/sign-latest-alpha.sh"

grep -F "store=$test_home/.local/share/threadline/signing/threadline-release.p12" \
    "$signer_log" >/dev/null
grep -F "alias=threadline-release" "$signer_log" >/dev/null
candidate_path=$(sed -n 's/^candidate=//p' "$signer_log")
[[ -n $candidate_path && ! -e $(dirname -- "$candidate_path") ]]

rm -f "$signer_log"
if PATH="$test_bin:$PATH" \
    HOME=$test_home \
    THREADLINE_TEST_CURRENT_COMMIT=$test_commit \
    THREADLINE_TEST_MAIN_COMMIT=0000000000000000000000000000000000000000 \
    THREADLINE_TEST_SIGNER_LOG=$signer_log \
        "$test_repository/scripts/sign-latest-alpha.sh" 2>/dev/null; then
    echo "Stale checkout was accepted." >&2
    exit 1
fi
[[ ! -e $signer_log ]]

if PATH="$test_bin:$PATH" \
    HOME=$test_home \
    THREADLINE_TEST_CURRENT_COMMIT=$test_commit \
    THREADLINE_TEST_RUN_COMMIT=0000000000000000000000000000000000000000 \
    THREADLINE_TEST_SIGNER_LOG=$signer_log \
        "$test_repository/scripts/sign-latest-alpha.sh" 2>/dev/null; then
    echo "Mismatched run commit was accepted." >&2
    exit 1
fi
[[ ! -e $signer_log ]]

if PATH="$test_bin:$PATH" \
    HOME=$test_home \
    THREADLINE_TEST_CURRENT_COMMIT=$test_commit \
    THREADLINE_TEST_NO_RUN=1 \
    THREADLINE_TEST_SIGNER_LOG=$signer_log \
        "$test_repository/scripts/sign-latest-alpha.sh" 2>/dev/null; then
    echo "Missing successful run was accepted." >&2
    exit 1
fi
[[ ! -e $signer_log ]]

printf 'sign-latest-alpha tests passed\n'
