#!/bin/sh
set -eu
umask 077

fail() {
  echo "uat deploy: FAIL: $*" >&2
  exit 1
}

if [ "$#" -ne 1 ]; then
  echo "usage: $0 COMMIT_SHA" >&2
  exit 64
fi

SHA=$1
[ "${#SHA}" -eq 40 ] || fail 'commit SHA must be 40 characters'
case "$SHA" in
  *[!0-9a-f]*) fail 'commit SHA must contain only lowercase hexadecimal characters' ;;
esac

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
STATE_DIR=${UAT_STATE_DIR:-/var/lib/huicui-uat}
LOG_DIR=${UAT_LOG_DIR:-/var/log/huicui-uat}
ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}
BARE_REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
DOCKER_BIN=${UAT_DOCKER_BIN:-docker}
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.uat.yml"
VERIFY_SCRIPT="$SCRIPT_DIR/verify.sh"
ACTIVE_SHA="$STATE_DIR/active-sha"
FAILED_SHA="$STATE_DIR/failed-sha"
ARTIFACT_DIR="$LOG_DIR/$SHA"

atomic_sha_write() {
  destination=$1
  temporary=$(mktemp "$STATE_DIR/sha.XXXXXX")
  printf '%s\n' "$SHA" >"$temporary"
  mv "$temporary" "$destination"
}

compose() {
  compose_tag=$1
  shift
  UAT_IMAGE_TAG=$compose_tag UAT_ARTIFACT_DIR=$ARTIFACT_DIR \
    "$DOCKER_BIN" compose \
      --project-name huicui-uat \
      --env-file "$ENV_FILE" \
      -f "$COMPOSE_FILE" \
      "$@"
}

attempt_deploy() {
  echo "uat deploy: building backend $SHA"
  command "$DOCKER_BIN" build \
    --build-arg "HUICUI_VERSION=sha-$SHA" \
    --build-arg "HUICUI_REVISION=$SHA" \
    -f "$REPO_ROOT/deploy/Dockerfile" \
    -t "huicui-uat-backend:$SHA" \
    "$REPO_ROOT" || return

  echo "uat deploy: building web $SHA"
  command "$DOCKER_BIN" build \
    --build-arg "HUICUI_REVISION=$SHA" \
    -f "$SCRIPT_DIR/Dockerfile.web" \
    -t "huicui-uat-web:$SHA" \
    "$REPO_ROOT" || return

  echo "uat deploy: building smoke $SHA"
  command "$DOCKER_BIN" build \
    -f "$SCRIPT_DIR/Dockerfile.smoke" \
    -t "huicui-uat-smoke:$SHA" \
    "$REPO_ROOT" || return

  compose "$SHA" --profile smoke config >/dev/null || return
  compose "$SHA" up -d --wait --wait-timeout 240 || return

  UAT_ENV_FILE=$ENV_FILE \
    UAT_ARTIFACT_DIR=$ARTIFACT_DIR \
    UAT_BASE_URL=http://127.0.0.1:6090 \
    "$VERIFY_SCRIPT" || return

  compose "$SHA" --profile smoke run --rm smoke || return
}

mkdir -p "$STATE_DIR" "$LOG_DIR" "$ARTIFACT_DIR"
[ -f "$ENV_FILE" ] || fail "environment file not found: $ENV_FILE"
[ -d "$BARE_REPO/objects" ] || fail "bare repository not found: $BARE_REPO"
[ -f "$COMPOSE_FILE" ] || fail "compose file not found: $COMPOSE_FILE"
[ -x "$VERIFY_SCRIPT" ] || fail "verify script is not executable: $VERIFY_SCRIPT"

resolved_sha=$(git --git-dir="$BARE_REPO" rev-parse --verify "$SHA^{commit}") || \
  fail "commit is not available in bare repository: $SHA"
[ "$resolved_sha" = "$SHA" ] || fail "commit did not resolve to the requested SHA: $SHA"

previous=
if [ -f "$ACTIVE_SHA" ]; then
  previous=$(sed -n '1p' "$ACTIVE_SHA")
  [ "${#previous}" -eq 40 ] || fail "active SHA is invalid: $ACTIVE_SHA"
  case "$previous" in
    *[!0-9a-f]*) fail "active SHA is invalid: $ACTIVE_SHA" ;;
  esac
fi

if attempt_deploy; then
  atomic_sha_write "$ACTIVE_SHA"
  rm -f "$FAILED_SHA"
  echo "deploy PASS $SHA"
  exit 0
fi

atomic_sha_write "$FAILED_SHA"
echo "deploy FAIL $SHA" >&2

# Capture the failed deployment before attempting any rollback.
compose "$SHA" ps >"$ARTIFACT_DIR/compose-ps-failed.txt" 2>&1 || true
compose "$SHA" logs --tail=300 db backend web >"$ARTIFACT_DIR/containers-failed.log" 2>&1 || true

if [ -n "$previous" ]; then
  echo "uat deploy: rolling backend and web back to $previous" >&2
  compose "$previous" up -d --no-deps backend >"$ARTIFACT_DIR/rollback.log" 2>&1 || true
  compose "$previous" up -d --no-deps web >>"$ARTIFACT_DIR/rollback.log" 2>&1 || true
  compose "$previous" ps >"$ARTIFACT_DIR/compose-ps-after-rollback.txt" 2>&1 || true
fi

exit 1
