#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)
UAT_DIR="$REPO_ROOT/deploy/uat"
COMPOSE_FILE="$UAT_DIR/docker-compose.uat.yml"
ENV_FILE="$UAT_DIR/.env.example"

fail() {
  echo "uat static contract: FAIL: $*" >&2
  exit 1
}

require_file() {
  [ -f "$1" ] || fail "missing file: ${1#$REPO_ROOT/}"
}

require_grep() {
  pattern=$1
  file=$2
  grep -Eq -- "$pattern" "$file" || fail "missing pattern '$pattern' in ${file#$REPO_ROOT/}"
}

reject_grep() {
  pattern=$1
  file=$2
  if grep -Eq -- "$pattern" "$file"; then
    fail "forbidden pattern '$pattern' in ${file#$REPO_ROOT/}"
  fi
}

for required in \
  "$UAT_DIR/tests/static-contract.sh" \
  "$COMPOSE_FILE" \
  "$UAT_DIR/Dockerfile.web" \
  "$UAT_DIR/Dockerfile.smoke" \
  "$UAT_DIR/nginx.conf" \
  "$ENV_FILE" \
  "$REPO_ROOT/frontend/e2e/uat-smoke.spec.ts" \
  "$REPO_ROOT/frontend/playwright.uat.config.ts"
do
  require_file "$required"
done

require_grep '^name:[[:space:]]+huicui-uat$' "$COMPOSE_FILE"
require_grep '127\.0\.0\.1:9092:9091' "$COMPOSE_FILE"
require_grep '6090:80' "$COMPOSE_FILE"
require_grep 'huicui-uat-pgdata:' "$COMPOSE_FILE"
require_grep 'SPRING_PROFILES_ACTIVE:[[:space:]]+dev' "$COMPOSE_FILE"
require_grep 'memory:[[:space:]]+256M' "$COMPOSE_FILE"
require_grep 'memory:[[:space:]]+600M' "$COMPOSE_FILE"
require_grep 'memory:[[:space:]]+128M' "$COMPOSE_FILE"
require_grep 'name:[[:space:]]+huicui-uat-network$' "$COMPOSE_FILE"
reject_grep 'huicui-pgdata:' "$COMPOSE_FILE"
reject_grep '5432:5432' "$COMPOSE_FILE"

require_grep 'proxy_pass[[:space:]]+http://backend:9091;' "$UAT_DIR/nginx.conf"
require_grep '^FROM[[:space:]]+mcr\.microsoft\.com/playwright:v1\.61\.1-noble$' "$UAT_DIR/Dockerfile.smoke"
require_grep 'PLAYWRIGHT_BASE_URL' "$REPO_ROOT/frontend/playwright.uat.config.ts"
require_grep 'PLAYWRIGHT_ARTIFACT_DIR' "$REPO_ROOT/frontend/playwright.uat.config.ts"

docker compose \
  --project-name huicui-uat \
  --env-file "$ENV_FILE" \
  -f "$COMPOSE_FILE" \
  config >/dev/null

echo 'uat static contract: PASS'
