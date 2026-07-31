#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
RUNNER="$ROOT/deploy/uat/full-scan.sh"
RESET="$ROOT/deploy/uat/reset.sh"
COMPOSE="$ROOT/deploy/uat/docker-compose.uat.yml"
GATE="$ROOT/deploy/uat/Dockerfile.full-scan-gate"
PLAYWRIGHT_CONFIG="$ROOT/frontend/playwright.full-scan.config.ts"

fail() {
  echo "full-scan contract: FAIL: $*" >&2
  exit 1
}

grep -Eq 'up[[:space:]]+-d[[:space:]]+--wait([[:space:]]|$)' "$RESET" ||
  fail 'reset must wait for service health before verify'
[ -x "$RUNNER" ] || fail 'runner missing or not executable'
[ -f "$GATE" ] || fail 'gate Dockerfile missing'
grep -Eq -- '--confirm.*huicui-uat' "$RUNNER" || fail 'explicit confirmation missing'
grep -Eq 'reset\.sh.*--confirm[[:space:]]+huicui-uat' "$RUNNER" || fail 'guarded reset missing'
grep -Eq -- '--profile[[:space:]]+full-scan.*run[[:space:]]+--rm[[:space:]]+full-scan' "$RUNNER" ||
  fail 'compose run missing'
grep -Eq 'business-lifecycle\.spec\.ts' "$RUNNER" || fail 'lifecycle phase missing'
grep -Eq -- '--grep-invert[[:space:]]+@lifecycle' "$RUNNER" || fail 'regression isolation missing'
grep -Eq 'trap.*cleanup' "$RUNNER" || fail 'interrupt cleanup trap missing'
first_cleanup_arm=$(grep -n '^cleanup_needed=1$' "$RUNNER" | sed -n '1s/:.*//p')
first_reset=$(grep -n '^reset_uat$' "$RUNNER" | sed -n '1s/:.*//p')
[ -n "$first_cleanup_arm" ] && [ -n "$first_reset" ] &&
  [ "$first_cleanup_arm" -lt "$first_reset" ] ||
  fail 'cleanup must be armed before the first destructive reset'
grep -Eq 'profiles:[[:space:]]*\[full-scan\]' "$COMPOSE" || fail 'profile missing'
grep -Eq '^FROM[[:space:]]+maven:3\.9-eclipse-temurin-21' "$GATE" || fail 'Java 21 gate missing'
grep -Eq '^FROM[[:space:]]+node:22' "$GATE" || fail 'Node 22 gate missing'
grep -Eq 'route_coverage\.py' "$GATE" || fail 'route gate missing'
grep -Eq "trace:[[:space:]]*'off'" "$PLAYWRIGHT_CONFIG" ||
  fail 'full-scan trace must stay off because raw traces can contain credentials'
if grep -Eq 'UAT_DEV_PASSWORD=.*(docker|playwright)|Admin@123' "$RUNNER"; then
  fail 'password entered argv/source'
fi

echo 'full-scan contract: PASS'
