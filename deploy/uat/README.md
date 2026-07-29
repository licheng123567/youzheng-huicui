# 独立 UAT 环境

这套 Compose 只用于合成数据验收。它有独立的项目名、网络、镜像标签和 PostgreSQL 卷；禁止导入生产或其他真实数据，禁止把真实姓名、电话、录音、密钥复制进 UAT。短信、存证、ASR、LLM、支付等第三方通道保持关闭，不配置真实凭据。

当前 Web 端口只绑定服务器 loopback。反馈人员先建立 SSH 隧道：

```sh
ssh -N -L 6090:127.0.0.1:6090 root@47.108.81.205
```

然后在本机打开 `http://127.0.0.1:6090`。不要把 6090 直接开放到公网。正式对外前再切换到带证书和访问控制的 HTTPS 入口，并同步更新 `UAT_PUBLIC_BASE`、健康检查和 Playwright base URL。

## 首次安装

服务器只保存一个权限为 0600 的环境文件。下面四个敏感值均现场随机生成并直接写入文件，命令不会把数据库口令、JWT 密钥、加密主密钥或 dev 登录口令显示到终端：

```sh
set -eu
umask 077
test ! -e /root/huicui-uat.env || { echo 'refusing to replace existing /root/huicui-uat.env' >&2; exit 1; }
db_secret=$(openssl rand -hex 24)
jwt_secret=$(openssl rand -hex 48)
crypto_secret=$(openssl rand -hex 32)
dev_password=$(openssl rand -base64 24)
test -n "$db_secret" && test -n "$jwt_secret" && test -n "$crypto_secret" && test -n "$dev_password"
env_tmp=$(mktemp /root/huicui-uat.env.XXXXXX)
trap 'test -z "${env_tmp:-}" || rm -f "$env_tmp"' 0 1 2 15
{
  printf '%s\n' 'UAT_IMAGE_TAG=bootstrap'
  printf '%s\n' 'UAT_POSTGRES_DB=huicui_uat'
  printf '%s\n' 'UAT_POSTGRES_USER=huicui_uat'
  printf 'UAT_POSTGRES_PASSWORD=%s\n' "$db_secret"
  printf 'UAT_JWT_SECRET=%s\n' "$jwt_secret"
  printf 'UAT_CRYPTO_KEY=%s\n' "$crypto_secret"
  printf 'UAT_DEV_PASSWORD=%s\n' "$dev_password"
  printf '%s\n' 'UAT_WEB_BIND=127.0.0.1'
  printf '%s\n' 'UAT_PUBLIC_BASE=http://127.0.0.1:6090'
  printf '%s\n' 'UAT_ARTIFACT_DIR=/var/log/huicui-uat/playwright'
} >"$env_tmp"
chmod 0600 "$env_tmp"
test ! -e /root/huicui-uat.env || { echo 'refusing to replace existing /root/huicui-uat.env' >&2; exit 1; }
mv "$env_tmp" /root/huicui-uat.env
env_tmp=
trap - 0 1 2 15
unset db_secret jwt_secret crypto_secret dev_password
install -d -m 700 /var/lib/huicui-uat /var/log/huicui-uat /var/log/huicui-uat/playwright
```

不要在工单、聊天或 shell 参数中粘贴 `UAT_POSTGRES_PASSWORD`、`UAT_JWT_SECRET`、`UAT_CRYPTO_KEY`、`UAT_DEV_PASSWORD` 的值。UAT 的 dev password 也必须保持随机，不能回退为仓库里的本地开发默认口令。

先在受控工作站把 main 推送到内网备份仓库：

```sh
git push backup main:main
```

然后在服务器准备受控 checkout 并安装接收 hook。安装器无位置参数，默认把 hook 写到 `/root/repos/youzheng-huicui.git`，把 worker 写到 `/opt/huicui-uat/bin/worker.sh`：

```sh
sudo -i
checkout_sha=$(git --git-dir=/root/repos/youzheng-huicui.git rev-parse refs/heads/main)
install -d -m 700 /root/huicui-uat-src
git --git-dir=/root/repos/youzheng-huicui.git --work-tree=/root/huicui-uat-src checkout -f "$checkout_sha"
cd /root/huicui-uat-src
./deploy/uat/install-hook.sh
```

安装 hook 不会自动重放已经存在的 main，因此首次安装用 state 目录内的临时文件和原子 `mv` 排队，再以前台方式运行 worker，直接观察首次部署结果：

```sh
exec 8>/var/lib/huicui-uat/queue.lock
flock 8
sha=$(git --git-dir=/root/repos/youzheng-huicui.git rev-parse --verify 'refs/heads/main^{commit}')
pending_tmp=$(mktemp /var/lib/huicui-uat/pending-sha.XXXXXX)
printf '%s\n' "$sha" >"$pending_tmp"
mv "$pending_tmp" /var/lib/huicui-uat/pending-sha
flock -u 8
exec 8>&-
/opt/huicui-uat/bin/worker.sh
exit
```

hook 只处理 main 的非删除更新，其他 ref 和删除更新直接忽略。worker 通过 `pending-sha` 和 flock 串行处理，连续 push 时以最新 SHA 为准。

## 手工构建与验收

自动部署会用同一个 40 位提交 SHA 构建三张镜像。排障时可在该提交的仓库根目录复现完整命令：

```sh
sha=$(git rev-parse HEAD)
test "${#sha}" -eq 40
docker build -f deploy/Dockerfile --build-arg "HUICUI_VERSION=sha-$sha" --build-arg HUICUI_REVISION="$sha" -t "huicui-uat-backend:$sha" .
docker build -f deploy/uat/Dockerfile.web --build-arg HUICUI_REVISION="$sha" -t "huicui-uat-web:$sha" .
docker build -f deploy/uat/Dockerfile.smoke -t "huicui-uat-smoke:$sha" .
UAT_IMAGE_TAG="$sha" docker compose --project-name huicui-uat --env-file /root/huicui-uat.env -f deploy/uat/docker-compose.uat.yml --profile smoke config --quiet
UAT_IMAGE_TAG="$sha" docker compose --project-name huicui-uat --env-file /root/huicui-uat.env -f deploy/uat/docker-compose.uat.yml up -d --wait --wait-timeout 240
sudo UAT_ENV_FILE=/root/huicui-uat.env UAT_ARTIFACT_DIR=/var/log/huicui-uat ./deploy/uat/verify.sh
UAT_IMAGE_TAG="$sha" docker compose --project-name huicui-uat --env-file /root/huicui-uat.env -f deploy/uat/docker-compose.uat.yml --profile smoke run --rm smoke
```

独立复现源码检查可运行：

```sh
mvn -f backend/app/pom.xml test
python3 backend/scripts/route_coverage.py
npm --prefix frontend ci
npm --prefix frontend run build
(cd frontend && npm run gen:api)
git diff --exit-code -- frontend/src/api/schema.d.ts
deploy/uat/tests/static-contract.sh
(
set -eu
UAT_DEV_PASSWORD=$(awk 'index($0,"UAT_DEV_PASSWORD=")==1 { sub(/^UAT_DEV_PASSWORD=/,""); print; exit }' /root/huicui-uat.env) || { echo 'UAT_DEV_PASSWORD is missing or empty' >&2; exit 1; }
test -n "$UAT_DEV_PASSWORD" || { echo 'UAT_DEV_PASSWORD is missing or empty' >&2; exit 1; }
export UAT_DEV_PASSWORD
cd frontend
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test e2e/members.spec.ts
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test
)
```

最后一组命令从环境文件导出 `UAT_DEV_PASSWORD`，密码值不会出现在 Playwright 的 argv 中。远程执行全量 Playwright 前先建立前述 SSH 隧道；自动 smoke 则由 Compose 的 `--env-file` 注入口令。

## 状态、日志与恢复

部署状态位于 `/var/lib/huicui-uat`：

- `active-sha`：当前通过 verify 与 smoke 的提交。
- `last-known-good-sha`：仅在自动回滚失败时保留此前最后已验证的提交。
- `failed-sha`：最近一次部署失败的目标提交。
- `rollback-failed-sha`：自动回滚自身失败的目标提交；此时 `active-sha` 会被清除，必须立即人工处理。
- `pending-sha`：等待 worker 部署的最新 main 提交。

日志和 Playwright artifacts 位于 `/var/log/huicui-uat`。每次失败应先保留 `docker compose ps`、backend/web 日志和测试产物，再检查状态文件。失败部署只回滚 backend/web 镜像；数据库容器和 `huicui-uat-pgdata` 卷必须原地保留，不能 down、删除或用生产数据重建。

```sh
for state in active-sha failed-sha rollback-failed-sha last-known-good-sha pending-sha processing-sha; do
  test ! -f "/var/lib/huicui-uat/$state" || { printf '%s: ' "$state"; cat "/var/lib/huicui-uat/$state"; }
done
docker compose --project-name huicui-uat --env-file /root/huicui-uat.env -f /root/huicui-uat-src/deploy/uat/docker-compose.uat.yml ps
tail -n 200 /var/log/huicui-uat/*.log
df -h /
docker system df
```

手工健康与种子数据门禁：

```sh
sudo UAT_ENV_FILE=/root/huicui-uat.env UAT_ARTIFACT_DIR=/var/log/huicui-uat ./deploy/uat/verify.sh
```

只有确认要销毁整套 UAT 合成数据时才能运行 reset：

```sh
sudo UAT_ENV_FILE=/root/huicui-uat.env UAT_STATE_DIR=/var/lib/huicui-uat ./deploy/uat/reset.sh --confirm huicui-uat
```

`reset.sh` 是唯一允许删除 `huicui-uat-pgdata` 的流程；verify、常规部署、失败回滚都不得删除任何卷。reset 不可恢复，执行前确认 active SHA 的三张历史镜像仍然存在。

目前没有 registry 保留策略和日志告警自动化。运维必须人工监控磁盘、worker/deploy 日志、`failed-sha` 与 `rollback-failed-sha`，并保留足够的历史 backend/web/smoke 镜像供回滚。若镜像或日志已被人工清理，自动回滚不能替代人工恢复。
