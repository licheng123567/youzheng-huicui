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
VERIFY_SCRIPT=${UAT_VERIFY_SCRIPT:-$SCRIPT_DIR/verify.sh}
ROLLBACK_VERIFY_SCRIPT=${UAT_ROLLBACK_VERIFY_SCRIPT:-}
ACTIVE_SHA="$STATE_DIR/active-sha"
FAILED_SHA="$STATE_DIR/failed-sha"
ROLLBACK_FAILED_SHA="$STATE_DIR/rollback-failed-sha"
LAST_KNOWN_GOOD_SHA="$STATE_DIR/last-known-good-sha"
ARTIFACT_DIR="$LOG_DIR/$SHA"
STARTUP_ATTEMPTED=0

atomic_sha_write() {
  write_value=$1
  write_destination=$2
  write_temporary=$(mktemp "$STATE_DIR/sha.XXXXXX") || return
  printf '%s\n' "$write_value" >"$write_temporary" || return
  mv "$write_temporary" "$write_destination" || return
}

compose_file() {
  compose_file_tag=$1
  compose_file_path=$2
  shift 2
  UAT_IMAGE_TAG=$compose_file_tag UAT_ARTIFACT_DIR=$ARTIFACT_DIR \
    "$DOCKER_BIN" compose \
      --project-name huicui-uat \
      --env-file "$ENV_FILE" \
      -f "$compose_file_path" \
      "$@"
}

compose() {
  compose_tag=$1
  shift
  compose_file "$compose_tag" "$COMPOSE_FILE" "$@"
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
  STARTUP_ATTEMPTED=1
  compose "$SHA" up -d --wait --wait-timeout 240 || return

  UAT_ENV_FILE=$ENV_FILE \
    UAT_ARTIFACT_DIR=$ARTIFACT_DIR \
    UAT_BASE_URL=http://127.0.0.1:6090 \
    "$VERIFY_SCRIPT" || return

  compose "$SHA" --profile smoke run --rm smoke || return
}

rollback_to_previous() {
  previous_compose="$ARTIFACT_DIR/previous-docker-compose.uat.yml"
  previous_verify="$ARTIFACT_DIR/previous-verify.sh"
  rollback_artifacts="$ARTIFACT_DIR/rollback-verify"

  git --git-dir="$BARE_REPO" show \
    "$previous:deploy/uat/docker-compose.uat.yml" >"$previous_compose" || return
  git --git-dir="$BARE_REPO" show \
    "$previous:deploy/uat/verify.sh" >"$previous_verify" || return
  chmod 0700 "$previous_verify" || return
  mkdir -p "$rollback_artifacts" || return
  rollback_verify_command=$previous_verify
  if [ -n "$ROLLBACK_VERIFY_SCRIPT" ]; then
    rollback_verify_command=$ROLLBACK_VERIFY_SCRIPT
  fi

  compose_file "$previous" "$previous_compose" --profile smoke config >/dev/null || return
  compose_file "$previous" "$previous_compose" \
    up -d --no-deps --wait --wait-timeout 240 backend || return
  compose_file "$previous" "$previous_compose" \
    up -d --no-deps --wait --wait-timeout 60 web || return

  UAT_ENV_FILE=$ENV_FILE \
    UAT_ARTIFACT_DIR=$rollback_artifacts \
    UAT_BASE_URL=http://127.0.0.1:6090 \
    "$rollback_verify_command" || return
}

record_rollback_failure() {
  atomic_sha_write "$SHA" "$ROLLBACK_FAILED_SHA" || true
  if [ -n "$previous" ]; then
    atomic_sha_write "$previous" "$LAST_KNOWN_GOOD_SHA" || true
  fi
  rm -f "$ACTIVE_SHA" || true
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
previous_state_file=$ACTIVE_SHA
if [ ! -f "$previous_state_file" ] && [ -f "$LAST_KNOWN_GOOD_SHA" ]; then
  previous_state_file=$LAST_KNOWN_GOOD_SHA
fi
if [ -f "$previous_state_file" ]; then
  previous=$(sed -n '1p' "$previous_state_file")
  [ "${#previous}" -eq 40 ] || fail "previous SHA is invalid: $previous_state_file"
  case "$previous" in
    *[!0-9a-f]*) fail "previous SHA is invalid: $previous_state_file" ;;
  esac
  resolved_previous=$(git --git-dir="$BARE_REPO" rev-parse --verify "$previous^{commit}") || \
    fail "previous SHA is not available in bare repository: $previous"
  [ "$resolved_previous" = "$previous" ] || fail "previous SHA did not resolve exactly: $previous"
fi

if attempt_deploy; then
  if atomic_sha_write "$SHA" "$ACTIVE_SHA"; then
    rm -f "$FAILED_SHA" "$ROLLBACK_FAILED_SHA" "$LAST_KNOWN_GOOD_SHA" || true
    echo "deploy PASS $SHA"
    exit 0
  fi
  echo "uat deploy: could not record active SHA; treating deployment as failed" >&2
fi

atomic_sha_write "$SHA" "$FAILED_SHA" || \
  echo "uat deploy: could not record failed SHA" >&2
echo "deploy FAIL $SHA" >&2

# Capture the failed deployment before attempting any rollback.
compose "$SHA" ps >"$ARTIFACT_DIR/compose-ps-failed.txt" 2>&1 || true
compose "$SHA" logs --tail=300 db backend web >"$ARTIFACT_DIR/containers-failed.log" 2>&1 || true

if [ "$STARTUP_ATTEMPTED" -eq 1 ] && [ -n "$previous" ]; then
  echo "uat deploy: rolling backend and web back to $previous" >&2
  if rollback_to_previous >"$ARTIFACT_DIR/rollback.log" 2>&1; then
    if atomic_sha_write "$previous" "$ACTIVE_SHA"; then
      rm -f "$ROLLBACK_FAILED_SHA" "$LAST_KNOWN_GOOD_SHA" || true
      compose_file "$previous" "$previous_compose" ps \
        >"$ARTIFACT_DIR/compose-ps-after-rollback.txt" 2>&1 || true
      echo "uat deploy: rollback PASS $previous" >&2
    else
      record_rollback_failure
      echo "uat deploy: rollback verification passed but active state write failed" >&2
    fi
  else
    record_rollback_failure
    echo "uat deploy: rollback FAIL $previous; active SHA cleared" >&2
  fi
elif [ "$STARTUP_ATTEMPTED" -eq 1 ]; then
  echo 'uat deploy: stopping unverified first deployment' >&2
  if compose "$SHA" stop web backend >"$ARTIFACT_DIR/first-deploy-stop.log" 2>&1; then
    echo 'uat deploy: unverified first deployment stopped' >&2
  else
    record_rollback_failure
    echo 'uat deploy: failed to stop unverified first deployment' >&2
  fi
fi

exit 1
