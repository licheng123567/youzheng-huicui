#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
RUNNER="$ROOT/deploy/uat/full-scan.sh"
RESET="$ROOT/deploy/uat/reset.sh"
COMPOSE="$ROOT/deploy/uat/docker-compose.uat.yml"
GATE="$ROOT/deploy/uat/Dockerfile.full-scan-gate"
PLAYWRIGHT_CONFIG="$ROOT/frontend/playwright.full-scan.config.ts"
DOCKERIGNORE="$ROOT/.dockerignore"
FIXTURE="$ROOT/frontend/e2e/fixtures/test.ts"

fail() {
  echo "full-scan contract: FAIL: $*" >&2
  exit 1
}

grep -Eq 'up[[:space:]]+-d[[:space:]]+--wait([[:space:]]|$)' "$RESET" ||
  fail 'reset must wait for service health before verify'
[ -x "$RUNNER" ] || fail 'runner missing or not executable'
[ -f "$GATE" ] || fail 'gate Dockerfile missing'
[ -f "$FIXTURE" ] || fail 'automatic diagnostic fixture missing'
grep -Eq -- '--confirm.*huicui-uat' "$RUNNER" || fail 'explicit confirmation missing'
grep -Eq 'RESET=.*reset\.sh' "$RUNNER" || fail 'guarded reset default missing'
grep -Eq '"\$RESET"[[:space:]]+--confirm[[:space:]]+huicui-uat' "$RUNNER" ||
  fail 'guarded reset invocation missing'
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
grep -Eq 'npm run test:unit' "$GATE" || fail 'frontend unit gate missing'
grep -Eq "trace:[[:space:]]*'off'" "$PLAYWRIGHT_CONFIG" ||
  fail 'full-scan trace must stay off because raw traces can contain credentials'
grep -Eq 'status[[:space:]]+--porcelain' "$RUNNER" ||
  fail 'runner must reject a dirty source tree'
grep -Eq 'UAT_REPO.*youzheng-huicui\.git' "$RUNNER" ||
  fail 'runner must use the deployment bare repository'
grep -Eq -- '--git-dir=.*BARE_REPO' "$RUNNER" ||
  fail 'runner Git reads must name the bare repository'
grep -Eq -- '--work-tree=.*ROOT' "$RUNNER" ||
  fail 'runner dirty check must name the checked-out source tree'
grep -Eq 'rm[[:space:]]+-f.*PASS_FILE' "$RUNNER" ||
  fail 'runner must invalidate a stale pass marker before gates'
grep -Eq 'mv.*PASS_FILE' "$RUNNER" ||
  fail 'runner must publish the pass marker atomically'
for secret_pattern in '.env' '*.pem' '*.key' '*.p12' '*.pfx' '*.keystore' '*.jks'; do
  grep -Fq "$secret_pattern" "$DOCKERIGNORE" ||
    fail "docker context does not exclude $secret_pattern"
done
if grep -R "from '@playwright/test'" "$ROOT/frontend/e2e"/*.spec.ts >/dev/null 2>&1; then
  fail 'every spec must import the automatic diagnostic fixture'
fi
if grep -Eq 'UAT_DEV_PASSWORD=.*(docker|playwright)|Admin@123' "$RUNNER"; then
  fail 'password entered argv/source'
fi

# 行为契约：脏源码拒绝；开始新扫描即清旧标记；只有全绿才能原子发布新标记。
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
sha=0123456789abcdef0123456789abcdef01234567
fixture_root="$tmp/source"
fixture_runner="$fixture_root/deploy/uat/full-scan.sh"
mkdir -p "$tmp/state" "$tmp/artifacts" "$tmp/bare/objects" "$(dirname "$fixture_runner")"
cp "$RUNNER" "$fixture_runner"
[ ! -e "$fixture_root/.git" ] || fail 'fixture source must model a checkout without .git'
printf '%s\n' "$sha" >"$tmp/state/active-sha"
printf 'UAT_DEV_PASSWORD=test-only\n' >"$tmp/uat.env"

cat >"$tmp/git" <<'EOF'
#!/bin/sh
[ "$1" = "--git-dir=$FAKE_REPO" ] || exit 3
shift
case "$*" in
  'rev-parse --verify refs/heads/main^{commit}') printf '%s\n' "$FAKE_SHA" ;;
  "--work-tree=$FAKE_ROOT status --porcelain --untracked-files=all")
    [ "${FAKE_GIT_DIRTY:-0}" -eq 0 ] || printf ' M dirty-source\n'
    ;;
  *) exit 2 ;;
esac
EOF
cat >"$tmp/docker" <<'EOF'
#!/bin/sh
printf 'docker %s\n' "$*" >>"$FAKE_LOG"
case "$*" in
  *'--grep-invert @lifecycle'*) [ "${FAKE_FAIL_REGRESSION:-0}" -eq 0 ] ;;
  *) exit 0 ;;
esac
EOF
cat >"$tmp/reset" <<'EOF'
#!/bin/sh
printf 'reset\n' >>"$FAKE_LOG"
EOF
cat >"$tmp/pass" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "$tmp/git" "$tmp/docker" "$tmp/reset" "$tmp/pass"

run_fixture() {
  FAKE_SHA=$sha FAKE_LOG="$tmp/actions.log" FAKE_REPO="$tmp/bare" FAKE_ROOT="$fixture_root" \
  UAT_ENV_FILE="$tmp/uat.env" UAT_STATE_DIR="$tmp/state" \
  UAT_ARTIFACT_ROOT="$tmp/artifacts" UAT_REPO="$tmp/bare" UAT_GIT_BIN="$tmp/git" \
  UAT_DOCKER_BIN="$tmp/docker" UAT_RESET_BIN="$tmp/reset" \
  UAT_VERIFY_BIN="$tmp/pass" UAT_STATIC_GATE_BIN="$tmp/pass" \
    "$fixture_runner" --confirm huicui-uat >/dev/null 2>&1
}

printf 'old-pass\n' >"$tmp/state/full-scan-pass-sha"
: >"$tmp/actions.log"
FAKE_GIT_DIRTY=1
export FAKE_GIT_DIRTY
if run_fixture; then
  fail 'dirty source unexpectedly received an attestation'
fi
unset FAKE_GIT_DIRTY
[ "$(sed -n '1p' "$tmp/state/full-scan-pass-sha")" = 'old-pass' ] ||
  fail 'preflight rejection must not invalidate the previous attestation'
[ ! -s "$tmp/actions.log" ] || fail 'dirty source reached Docker/reset actions'

: >"$tmp/actions.log"
run_fixture || fail 'clean all-green fixture failed'
[ "$(sed -n '1p' "$tmp/state/full-scan-pass-sha")" = "$sha" ] ||
  fail 'all-green run did not publish the active SHA'

printf 'stale-pass\n' >"$tmp/state/full-scan-pass-sha"
: >"$tmp/actions.log"
FAKE_FAIL_REGRESSION=1
export FAKE_FAIL_REGRESSION
if run_fixture; then
  fail 'failed regression unexpectedly passed'
fi
unset FAKE_FAIL_REGRESSION
[ ! -e "$tmp/state/full-scan-pass-sha" ] ||
  fail 'failed rerun left a stale pass marker'
[ "$(grep -c '^reset$' "$tmp/actions.log")" -eq 3 ] ||
  fail 'failed regression did not execute every isolation reset'

echo 'full-scan contract: PASS'
