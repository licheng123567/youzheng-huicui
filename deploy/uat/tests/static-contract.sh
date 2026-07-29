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

require_executable() {
  [ -x "$1" ] || fail "file is not executable: ${1#$REPO_ROOT/}"
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
  "$UAT_DIR/verify.sh" \
  "$UAT_DIR/reset.sh" \
  "$UAT_DIR/deploy.sh" \
  "$UAT_DIR/worker.sh" \
  "$UAT_DIR/post-receive" \
  "$UAT_DIR/install-hook.sh" \
  "$REPO_ROOT/frontend/e2e/uat-smoke.spec.ts" \
  "$REPO_ROOT/frontend/playwright.uat.config.ts"
do
  require_file "$required"
done

require_executable "$UAT_DIR/verify.sh"
require_executable "$UAT_DIR/reset.sh"
require_executable "$UAT_DIR/deploy.sh"
require_executable "$UAT_DIR/worker.sh"
require_executable "$UAT_DIR/post-receive"
require_executable "$UAT_DIR/install-hook.sh"

require_grep 'refs/heads/main' "$UAT_DIR/post-receive"
require_grep 'flock' "$UAT_DIR/worker.sh"
require_grep 'pending-sha' "$UAT_DIR/worker.sh"
require_grep 'active-sha' "$UAT_DIR/deploy.sh"
require_grep 'failed-sha' "$UAT_DIR/deploy.sh"
require_grep 'HUICUI_REVISION' "$UAT_DIR/deploy.sh"
require_grep 'verify\.sh' "$UAT_DIR/deploy.sh"
require_grep '--profile[[:space:]]+smoke[[:space:]]+run[[:space:]]+--rm[[:space:]]+smoke' "$UAT_DIR/deploy.sh"
require_grep 'up[[:space:]]+-d[[:space:]]+--wait' "$UAT_DIR/deploy.sh"
require_grep 'UAT_ARTIFACT_DIR=\$ARTIFACT_DIR' "$UAT_DIR/deploy.sh"
require_grep 'mv "\$PENDING_SHA" "\$claimed"' "$UAT_DIR/worker.sh"
require_grep 'processing-sha' "$UAT_DIR/worker.sh"
require_grep 'checkout[[:space:]]+-f[[:space:]]+"\$sha"' "$UAT_DIR/worker.sh"
require_grep 'flock' "$UAT_DIR/post-receive"
require_grep 'rev-parse.*refs/heads/main' "$UAT_DIR/post-receive"
require_grep 'nohup.*</dev/null.*>/dev/null[[:space:]]+2>&1[[:space:]]+&' "$UAT_DIR/post-receive"
require_grep 'receive\.denyNonFastForwards[[:space:]]+true' "$UAT_DIR/install-hook.sh"
require_grep 'huicui-uat-hook\.conf' "$UAT_DIR/install-hook.sh"
require_grep 'rollback-failed-sha' "$UAT_DIR/deploy.sh"
require_grep 'git.*--git-dir=.*show' "$UAT_DIR/deploy.sh"
require_grep 'docker-compose\.uat\.yml' "$UAT_DIR/deploy.sh"
require_grep 'rollback.*verify|verify.*rollback' "$UAT_DIR/deploy.sh"
require_grep 'stop.*web.*backend|stop.*backend.*web' "$UAT_DIR/deploy.sh"
reject_grep 'volume[[:space:]]+rm' "$UAT_DIR/deploy.sh"
reject_grep 'down[[:space:]]+-v' "$UAT_DIR/deploy.sh"
reject_grep 'docker[[:space:]]+run' "$UAT_DIR/deploy.sh"

require_grep '^name:[[:space:]]+huicui-uat$' "$COMPOSE_FILE"
require_grep '127\.0\.0\.1:9092:9091' "$COMPOSE_FILE"
require_grep '6090:80' "$COMPOSE_FILE"
require_grep 'huicui-uat-pgdata:' "$COMPOSE_FILE"
require_grep 'SPRING_PROFILES_ACTIVE:[[:space:]]+dev' "$COMPOSE_FILE"
require_grep 'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED:[[:space:]]+"true"' "$COMPOSE_FILE"
require_grep 'HUICUI_DEV_PASSWORD:[[:space:]]+\$\{UAT_DEV_PASSWORD:\?' "$COMPOSE_FILE"
require_grep 'UAT_WEB_BIND:-127\.0\.0\.1' "$COMPOSE_FILE"
require_grep 'memory:[[:space:]]+256M' "$COMPOSE_FILE"
require_grep 'memory:[[:space:]]+600M' "$COMPOSE_FILE"
require_grep 'memory:[[:space:]]+128M' "$COMPOSE_FILE"
require_grep 'name:[[:space:]]+huicui-uat-network$' "$COMPOSE_FILE"
reject_grep 'huicui-pgdata:' "$COMPOSE_FILE"
reject_grep '5432:5432' "$COMPOSE_FILE"

require_grep '^[[:space:]]*proxy_pass[[:space:]]+http://backend:9091;' "$UAT_DIR/nginx.conf"
require_grep '^FROM[[:space:]]+mcr\.microsoft\.com/playwright:v1\.61\.1-noble$' "$UAT_DIR/Dockerfile.smoke"
require_grep '^ENV[[:space:]]+HUICUI_REVISION=' "$UAT_DIR/Dockerfile.web"
require_grep 'process\.env\.HUICUI_REVISION' "$REPO_ROOT/frontend/vite.config.ts"
require_grep 'PLAYWRIGHT_BASE_URL' "$REPO_ROOT/frontend/playwright.uat.config.ts"
require_grep 'PLAYWRIGHT_ARTIFACT_DIR' "$REPO_ROOT/frontend/playwright.uat.config.ts"

for account in admin plat_se cuihu_pl cuihu_pc jx_vl jx_co1
do
  require_grep "$account" "$UAT_DIR/verify.sh"
done
require_grep '13900000001' "$UAT_DIR/verify.sh"
require_grep '13800000000' "$UAT_DIR/verify.sh"
require_grep '/v1/actuator/health' "$UAT_DIR/verify.sh"
require_grep 'unauth.*401|401.*unauth' "$UAT_DIR/verify.sh"
require_grep 'members\?orgId=' "$UAT_DIR/verify.sh"
require_grep '\{"SA", "SE"\}' "$UAT_DIR/verify.sh"
require_grep '^umask 077$' "$UAT_DIR/verify.sh"
require_grep 'curl-auth\.XXXXXX' "$UAT_DIR/verify.sh"
reject_grep 'Authorization: Bearer \$sa_token' "$UAT_DIR/verify.sh"
reject_grep 'Admin@123' "$UAT_DIR/verify.sh"
require_grep '--confirm' "$UAT_DIR/reset.sh"
require_grep 'huicui-uat-pgdata' "$UAT_DIR/reset.sh"
require_grep '^docker volume rm huicui-uat-pgdata$' "$UAT_DIR/reset.sh"
require_grep 'UAT_IMAGE_TAG=\$tag docker compose' "$UAT_DIR/reset.sh"
require_grep 'verify\.sh' "$UAT_DIR/reset.sh"
reject_grep 'huicui-pgdata' "$UAT_DIR/reset.sh"

rendered=$(mktemp)
scratch=$(mktemp -d)
trap 'rm -f "$rendered"; rm -rf "$scratch"' EXIT HUP INT TERM

docker compose \
  --project-name huicui-uat \
  --env-file "$ENV_FILE" \
  -f "$COMPOSE_FILE" \
  --profile smoke \
  config --format json >"$rendered"

python3 -I - "$rendered" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
assert config["name"] == "huicui-uat"
assert config["networks"]["uat"]["name"] == "huicui-uat-network"
assert config["volumes"]["huicui-uat-pgdata"]["name"] == "huicui-uat-pgdata"

services = config["services"]
db, backend, web, smoke = (services[name] for name in ("db", "backend", "web", "smoke"))
assert not db.get("ports"), "db service must not publish any host port"
assert db["volumes"] == [{
    "type": "volume",
    "source": "huicui-uat-pgdata",
    "target": "/var/lib/postgresql/data",
    "volume": {},
}]

def only_port(service, host_ip, published, target):
    ports = service.get("ports", [])
    assert len(ports) == 1, ports
    port = ports[0]
    assert port.get("host_ip") == host_ip, port
    assert str(port["published"]) == str(published), port
    assert int(port["target"]) == target, port

only_port(backend, "127.0.0.1", 9092, 9091)
only_port(web, "127.0.0.1", 6090, 80)

assert backend["depends_on"]["db"]["condition"] == "service_healthy"
assert web["depends_on"]["backend"]["condition"] == "service_healthy"
assert smoke["depends_on"]["web"]["condition"] == "service_healthy"
env = backend["environment"]
assert env["SPRING_PROFILES_ACTIVE"] == "dev"
assert env["SPRING_DATASOURCE_URL"].startswith("jdbc:postgresql://db:5432/")
assert env["MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED"] == "true"
assert env["HUICUI_DEV_PASSWORD"] != "Admin@123"
assert env["JAVA_OPTS"].startswith("-XX:MaxRAMPercentage=55 ")
assert smoke["environment"]["UAT_DEV_PASSWORD"] == env["HUICUI_DEV_PASSWORD"]
assert smoke["environment"]["PLAYWRIGHT_BASE_URL"] == "http://web"
assert smoke["environment"]["PLAYWRIGHT_ARTIFACT_DIR"] == "/artifacts"
assert any(
    volume["type"] == "bind" and volume["target"] == "/artifacts"
    for volume in smoke.get("volumes", [])
), smoke.get("volumes")

limits = {
    "db": 256 * 1024 * 1024,
    "backend": 600 * 1024 * 1024,
    "web": 128 * 1024 * 1024,
    "smoke": 512 * 1024 * 1024,
}
for name, expected in limits.items():
    service = services[name]
    assert int(service["mem_limit"]) == expected, (name, service["mem_limit"])
    assert int(service["deploy"]["resources"]["limits"]["memory"]) == expected
    assert set(service["networks"]) == {"uat"}
PY

mkdir -p "$scratch/bin" "$scratch/state" "$scratch/artifacts"
spy="$scratch/docker.calls"
cat >"$scratch/bin/docker" <<'SH'
#!/bin/sh
printf '%s|%s\n' "${UAT_IMAGE_TAG:-}" "$*" >>"$UAT_DOCKER_SPY"
SH
chmod +x "$scratch/bin/docker"
cat >"$scratch/bin/flock" <<'SH'
#!/bin/sh
exit 0
SH
chmod +x "$scratch/bin/flock"

# 错误确认词必须在任何 Docker 调用前返回 64。
: >"$spy"
set +e
UAT_DOCKER_SPY="$spy" UAT_DOCKER_BIN="$scratch/bin/docker" \
  "$UAT_DIR/reset.sh" --confirm wrong-target >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -eq 64 ] || fail "reset wrong-target returned $rc instead of 64"
[ ! -s "$spy" ] || fail 'reset called Docker for an invalid confirmation target'

# 非法 active-sha 必须在 down/rm 前失败。
printf '%s\n' '-invalid-tag' >"$scratch/state/active-sha"
: >"$spy"
set +e
UAT_DOCKER_SPY="$spy" UAT_DOCKER_BIN="$scratch/bin/docker" \
  UAT_ENV_FILE="$ENV_FILE" UAT_STATE_DIR="$scratch/state" \
  "$UAT_DIR/reset.sh" --confirm huicui-uat >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'reset accepted an invalid active SHA'
[ ! -s "$spy" ] || fail 'reset called Docker before rejecting an invalid active SHA'

# 合法 SHA 只允许既定 project/volume/down/up；随后 verify 因假地址失败是预期行为。
sha=0123456789abcdef0123456789abcdef01234567
printf '%s\n' "$sha" >"$scratch/state/active-sha"
: >"$spy"
set +e
UAT_DOCKER_SPY="$spy" UAT_DOCKER_BIN="$scratch/bin/docker" \
  UAT_ENV_FILE="$ENV_FILE" UAT_STATE_DIR="$scratch/state" \
  UAT_ARTIFACT_DIR="$scratch/artifacts" UAT_BASE_URL=http://127.0.0.1:1 \
  "$UAT_DIR/reset.sh" --confirm huicui-uat >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'reset unexpectedly skipped the final live verification'
require_grep '^|compose --project-name huicui-uat .* down$' "$spy"
require_grep '^|volume rm huicui-uat-pgdata$' "$spy"
require_grep "^$sha|compose --project-name huicui-uat .* up -d$" "$spy"
[ "$(wc -l <"$spy" | tr -d ' ')" -eq 3 ] || fail 'reset issued unexpected Docker calls'

# 部署脚本必须在任何 Docker 调用前拒绝不完整或非十六进制 SHA。
: >"$spy"
set +e
UAT_DOCKER_SPY="$spy" UAT_DOCKER_BIN="$scratch/bin/docker" \
  "$UAT_DIR/deploy.sh" invalid-sha >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'deploy accepted an invalid commit SHA'
[ ! -s "$spy" ] || fail 'deploy called Docker before rejecting an invalid commit SHA'

# 首次部署验收失败时必须停止未验证的 web/backend，且不得写 active-sha。
deploy_repo=$(git rev-parse --path-format=absolute --git-common-dir)
candidate_sha=$(git rev-parse HEAD)
first_state="$scratch/first-state"
first_logs="$scratch/first-logs"
mkdir -p "$first_state" "$first_logs"
: >"$spy"
set +e
UAT_DOCKER_SPY="$spy" \
  UAT_DOCKER_BIN="$scratch/bin/docker" \
  UAT_REPO="$deploy_repo" \
  UAT_ENV_FILE="$ENV_FILE" \
  UAT_STATE_DIR="$first_state" \
  UAT_LOG_DIR="$first_logs" \
  UAT_VERIFY_SCRIPT=/usr/bin/false \
  "$UAT_DIR/deploy.sh" "$candidate_sha" >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'deploy accepted a failed first verification'
[ ! -e "$first_state/active-sha" ] || fail 'failed first deploy wrote active-sha'
[ "$(sed -n '1p' "$first_state/failed-sha")" = "$candidate_sha" ] || \
  fail 'failed first deploy did not record failed-sha'
require_grep "^$candidate_sha|compose .* stop web backend$" "$spy"

# 有 previous 时必须使用 previous 提交中的配置，等待并验收回滚。
rollback_state="$scratch/rollback-state"
rollback_logs="$scratch/rollback-logs"
previous_sha=$(git rev-parse HEAD^)
mkdir -p "$rollback_state" "$rollback_logs"
printf '%s\n' "$previous_sha" >"$rollback_state/active-sha"
: >"$spy"
set +e
UAT_DOCKER_SPY="$spy" \
  UAT_DOCKER_BIN="$scratch/bin/docker" \
  UAT_REPO="$deploy_repo" \
  UAT_ENV_FILE="$ENV_FILE" \
  UAT_STATE_DIR="$rollback_state" \
  UAT_LOG_DIR="$rollback_logs" \
  UAT_VERIFY_SCRIPT=/usr/bin/false \
  UAT_ROLLBACK_VERIFY_SCRIPT=/usr/bin/true \
  "$UAT_DIR/deploy.sh" "$candidate_sha" >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'deploy reported success after candidate verification failed'
[ "$(sed -n '1p' "$rollback_state/active-sha")" = "$previous_sha" ] || \
  fail 'successful rollback did not preserve the previous active SHA'
[ ! -e "$rollback_state/rollback-failed-sha" ] || \
  fail 'successful rollback left rollback-failed-sha behind'
require_grep "^$previous_sha|compose .*previous-docker-compose\.uat\.yml up -d --no-deps --wait --wait-timeout 240 backend$" "$spy"
require_grep "^$previous_sha|compose .*previous-docker-compose\.uat\.yml up -d --no-deps --wait --wait-timeout 60 web$" "$spy"

# 回滚自身验收失败时，active-sha 必须清空并显式保留最后已知良好值。
rollback_fail_state="$scratch/rollback-fail-state"
rollback_fail_logs="$scratch/rollback-fail-logs"
mkdir -p "$rollback_fail_state" "$rollback_fail_logs"
printf '%s\n' "$previous_sha" >"$rollback_fail_state/active-sha"
set +e
UAT_DOCKER_SPY="$spy" \
  UAT_DOCKER_BIN="$scratch/bin/docker" \
  UAT_REPO="$deploy_repo" \
  UAT_ENV_FILE="$ENV_FILE" \
  UAT_STATE_DIR="$rollback_fail_state" \
  UAT_LOG_DIR="$rollback_fail_logs" \
  UAT_VERIFY_SCRIPT=/usr/bin/false \
  UAT_ROLLBACK_VERIFY_SCRIPT=/usr/bin/false \
  "$UAT_DIR/deploy.sh" "$candidate_sha" >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'deploy reported success after rollback verification failed'
[ ! -e "$rollback_fail_state/active-sha" ] || fail 'failed rollback left a misleading active-sha'
[ "$(sed -n '1p' "$rollback_fail_state/rollback-failed-sha")" = "$candidate_sha" ] || \
  fail 'failed rollback did not record rollback-failed-sha'
[ "$(sed -n '1p' "$rollback_fail_state/last-known-good-sha")" = "$previous_sha" ] || \
  fail 'failed rollback did not preserve last-known-good-sha'

# post-receive 必须忽略非 main 和 main 删除，只原子排队合法 main SHA。
hook_state="$scratch/hook-state"
hook_repo=$(git rev-parse --path-format=absolute --git-common-dir)
hook_tip=$(git --git-dir="$hook_repo" rev-parse refs/heads/main)
mkdir -p "$hook_state"
rm -f "$hook_state/pending-sha"
printf '%s %s %s\n' "$sha" "$sha" refs/heads/dev | \
  PATH="$scratch/bin:$PATH" UAT_STATE_DIR="$hook_state" UAT_RUNNER=/bin/true "$UAT_DIR/post-receive"
[ ! -e "$hook_state/pending-sha" ] || fail 'post-receive queued a non-main ref'
printf '%s %s %s\n' "$sha" 0000000000000000000000000000000000000000 refs/heads/main | \
  PATH="$scratch/bin:$PATH" UAT_STATE_DIR="$hook_state" UAT_RUNNER=/bin/true "$UAT_DIR/post-receive"
[ ! -e "$hook_state/pending-sha" ] || fail 'post-receive queued a deleted main ref'
printf '%s %s %s\n' 0000000000000000000000000000000000000000 "$hook_tip" refs/heads/main | \
  PATH="$scratch/bin:$PATH" UAT_REPO="$hook_repo" UAT_STATE_DIR="$hook_state" UAT_RUNNER=/bin/true "$UAT_DIR/post-receive"
[ "$(sed -n '1p' "$hook_state/pending-sha")" = "$hook_tip" ] || \
  fail 'post-receive did not queue the latest main SHA'

# 安装脚本必须把 hook/worker 安装到指定隔离目录并禁止非快进。
bare_repo="$scratch/repo.git"
git init --bare -q "$bare_repo"
git push -q "$bare_repo" HEAD:refs/heads/main
installed_tip=$(git --git-dir="$bare_repo" rev-parse refs/heads/main)
UAT_REPO="$bare_repo" \
  UAT_INSTALL_DIR="$scratch/install" \
  UAT_STATE_DIR="$scratch/install-state" \
  UAT_LOG_DIR="$scratch/install-logs" \
  UAT_SRC="$scratch/source" \
  "$UAT_DIR/install-hook.sh" >/dev/null
[ -x "$scratch/install/worker.sh" ] || fail 'worker was not installed executable'
[ -x "$bare_repo/hooks/post-receive" ] || fail 'post-receive was not installed executable'
[ -f "$bare_repo/hooks/huicui-uat-hook.conf" ] || fail 'hook runtime config was not installed'
[ "$(git --git-dir="$bare_repo" config --get receive.denyNonFastForwards)" = true ] || \
  fail 'install-hook did not deny non-fast-forward pushes'

# 已安装 hook 必须在没有安装期环境变量时仍使用自定义 state/repo/runner 路径。
chmod -x "$scratch/install/worker.sh"
rm -f "$scratch/install-state/pending-sha"
printf '%s %s %s\n' "$installed_tip" "$sha" refs/heads/main | \
  PATH="$scratch/bin:$PATH" "$bare_repo/hooks/post-receive"
[ "$(sed -n '1p' "$scratch/install-state/pending-sha")" = "$installed_tip" ] || \
  fail 'installed hook did not preserve its configured runtime paths'

# worker 崩溃后遗留的 processing claim 必须在下一次启动时恢复并处理。
recovery_state="$scratch/recovery-state"
recovery_logs="$scratch/recovery-logs"
mkdir -p "$recovery_state" "$recovery_logs"
printf '%s\n' "$installed_tip" >"$recovery_state/processing-sha"
PATH="$scratch/bin:$PATH" \
  UAT_REPO="$bare_repo" \
  UAT_SRC="$scratch/recovery-source" \
  UAT_STATE_DIR="$recovery_state" \
  UAT_LOG_DIR="$recovery_logs" \
  UAT_ENV_FILE="$scratch/missing.env" \
  "$UAT_DIR/worker.sh"
[ ! -e "$recovery_state/processing-sha" ] || fail 'worker left a recovered claim stuck'
[ ! -e "$recovery_state/pending-sha" ] || fail 'worker did not consume a recovered claim'
[ "$(sed -n '1p' "$recovery_state/failed-sha")" = "$installed_tip" ] || \
  fail 'worker lost the recovered SHA instead of recording its failed attempt'

echo 'uat static contract: PASS'
