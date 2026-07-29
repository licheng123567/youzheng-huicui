#!/bin/sh
set -eu

usage() {
  echo "usage: $0 --confirm huicui-uat" >&2
}

fail() {
  echo "uat reset: FAIL: $*" >&2
  exit 1
}

if [ "$#" -ne 2 ] || [ "$1" != '--confirm' ] || [ "$2" != 'huicui-uat' ]; then
  usage
  exit 64
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}
STATE_DIR=${UAT_STATE_DIR:-/var/lib/huicui-uat}
COMPOSE_FILE="$REPO_ROOT/deploy/uat/docker-compose.uat.yml"
ACTIVE_SHA="$STATE_DIR/active-sha"
BASE=${UAT_BASE_URL:-http://127.0.0.1:6090}
UAT_DOCKER_BIN=${UAT_DOCKER_BIN:-docker}

docker() {
  command "$UAT_DOCKER_BIN" "$@"
}

[ -f "$ENV_FILE" ] || fail "environment file not found: $ENV_FILE"
[ -f "$COMPOSE_FILE" ] || fail "compose file not found: $COMPOSE_FILE"
[ -f "$ACTIVE_SHA" ] || fail "active SHA not found: $ACTIVE_SHA"

tag=$(sed -n '1p' "$ACTIVE_SHA")
[ "${#tag}" -eq 40 ] || fail "active SHA must be a full 40-character Git SHA: $ACTIVE_SHA"
case "$tag" in
  *[!0-9a-f]*) fail "active SHA must contain only lowercase hexadecimal characters: $ACTIVE_SHA" ;;
esac

docker compose \
  --project-name huicui-uat \
  --env-file "$ENV_FILE" \
  -f "$COMPOSE_FILE" \
  down

docker volume rm huicui-uat-pgdata

UAT_IMAGE_TAG=$tag docker compose \
  --project-name huicui-uat \
  --env-file "$ENV_FILE" \
  -f "$COMPOSE_FILE" \
  up -d

UAT_ENV_FILE=$ENV_FILE UAT_BASE_URL=$BASE "$SCRIPT_DIR/verify.sh"
