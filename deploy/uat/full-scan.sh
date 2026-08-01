#!/bin/sh
set -eu
umask 077

fail() {
  echo "uat full-scan: FAIL: $*" >&2
  exit 1
}

[ "$#" -eq 2 ] && [ "$1" = '--confirm' ] && [ "$2" = 'huicui-uat' ] ||
  fail 'usage: full-scan.sh --confirm huicui-uat'

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}
STATE_DIR=${UAT_STATE_DIR:-/var/lib/huicui-uat}
ARTIFACT_ROOT=${UAT_ARTIFACT_ROOT:-/var/log/huicui-uat/full-scan}
BARE_REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
COMPOSE="$SCRIPT_DIR/docker-compose.uat.yml"
DOCKER=${UAT_DOCKER_BIN:-docker}
GIT=${UAT_GIT_BIN:-git}
RESET=${UAT_RESET_BIN:-$SCRIPT_DIR/reset.sh}
VERIFY=${UAT_VERIFY_BIN:-$SCRIPT_DIR/verify.sh}
STATIC_GATE=${UAT_STATIC_GATE_BIN:-$SCRIPT_DIR/tests/static-contract.sh}
PASS_FILE="$STATE_DIR/full-scan-pass-sha"

[ -f "$ENV_FILE" ] || fail "environment file missing: $ENV_FILE"
[ -d "$BARE_REPO/objects" ] || fail "bare repository missing: $BARE_REPO"
[ -f "$STATE_DIR/active-sha" ] || fail 'active SHA missing'
sha=$(sed -n '1p' "$STATE_DIR/active-sha")
[ "${#sha}" -eq 40 ] || fail 'active SHA must be 40 characters'
case "$sha" in
  *[!0-9a-f]*) fail 'active SHA must be lowercase hex' ;;
esac
source_sha=$("$GIT" --git-dir="$BARE_REPO" rev-parse --verify 'refs/heads/main^{commit}')
[ "$source_sha" = "$sha" ] ||
  fail "source SHA $source_sha does not match active SHA $sha"
dirty=$("$GIT" --git-dir="$BARE_REPO" --work-tree="$ROOT" status --porcelain --untracked-files=all)
[ -z "$dirty" ] || fail 'source tree must be clean before attestation'

rm -f "$PASS_FILE"

run_dir="$ARTIFACT_ROOT/$(date -u +%Y%m%dT%H%M%SZ)-$sha"
mkdir -p "$run_dir/gate" "$run_dir/lifecycle" "$run_dir/regression"

compose_run() {
  artifact=$1
  shift
  UAT_IMAGE_TAG=$sha UAT_ARTIFACT_DIR="$artifact" "$DOCKER" compose --project-name huicui-uat --env-file "$ENV_FILE" -f "$COMPOSE" --profile full-scan run --rm full-scan "$@"
}

reset_uat() {
  UAT_ENV_FILE=$ENV_FILE UAT_STATE_DIR=$STATE_DIR \
    "$RESET" --confirm huicui-uat
}

cleanup_needed=0
pass_tmp=''
cleanup() {
  cleanup_status=$?
  trap - EXIT HUP INT TERM
  if [ -n "$pass_tmp" ]; then
    rm -f "$pass_tmp"
  fi
  if [ "$cleanup_needed" -eq 1 ] && ! reset_uat; then
    echo "uat full-scan: FAIL: cleanup reset failed; artifacts: $run_dir" >&2
    cleanup_status=1
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

"$STATIC_GATE"
"$DOCKER" build -f "$SCRIPT_DIR/Dockerfile.full-scan-gate" -t "huicui-uat-gate:$sha" "$ROOT"
"$DOCKER" run --rm "huicui-uat-gate:$sha" >"$run_dir/gate/result.txt"

cleanup_needed=1
reset_uat
set +e
compose_run "$run_dir/lifecycle" e2e/business-lifecycle.spec.ts
lifecycle_status=$?
set -e
reset_uat
cleanup_needed=0
[ "$lifecycle_status" -eq 0 ] ||
  fail "lifecycle failed; artifacts: $run_dir/lifecycle"

cleanup_needed=1
set +e
compose_run "$run_dir/regression" --grep-invert @lifecycle
regression_status=$?
set -e
reset_uat
cleanup_needed=0
[ "$regression_status" -eq 0 ] ||
  fail "regression failed; artifacts: $run_dir/regression"

UAT_ENV_FILE=$ENV_FILE UAT_ARTIFACT_DIR="$run_dir/final-verify" \
  "$VERIFY"
pass_tmp="$STATE_DIR/.full-scan-pass-sha.$$"
printf '%s\n' "$sha" >"$pass_tmp"
mv "$pass_tmp" "$PASS_FILE"
pass_tmp=''
printf 'uat full-scan: PASS %s artifacts=%s\n' "$sha" "$run_dir"
