#!/bin/bash
# 一条命令把云端同步到最新。
#   服务器上：  bash deploy/bt/update.sh
#   本机一条命令：ssh root@47.108.81.205 'cd /root/huicui && bash deploy/bt/update.sh'
#
# 做四件事：拉代码 → 拉最新后端镜像 → 滚动重启后端 → 重建前端 → 健康检查。
#
# 前提：.env 里 HUICUI_IMAGE_TAG=main（跟随移动 tag，CI 每次合并 main 都会更新 :main 镜像）。
#   · 前端/脚本/配置改动 → git pull 即时生效。
#   · 后端代码改动 → 要先等 CI 构建好 :main 镜像（约 8~10 分钟）再跑本脚本。
#
# 回退（真要回退某次后端时）：把 .env 的 HUICUI_IMAGE_TAG 改成某个旧 sha（如 sha-8686ab9），
#   再跑本脚本；确认无误后改回 main 恢复自动更新。旧镜像列表见 GHCR。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

echo "[1/4] 拉最新代码（前端 + 脚本）"
git pull --ff-only 2>&1 | tail -3

cd "$ROOT/deploy"
CMP="docker compose -p huicui --env-file .env -f bt/docker-compose.bt.yml"

echo "[2/4] 拉最新后端镜像 + 滚动重启"
$CMP pull backend
$CMP up -d

echo "[3/4] 等后端就绪"
ok=0
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:9091/v1/actuator/health >/dev/null 2>&1; then
    echo "✅ 后端 UP"; ok=1; break
  fi
  sleep 5
done
[ "$ok" = 1 ] || echo "⚠️ 后端 150s 未就绪 —— docker logs huicui-backend-1 --tail 60"

echo "[4/4] 重建前端"
bash bt/frontend.sh

echo
echo "✅ 同步完成：https://cuiai.doorai.cn"
