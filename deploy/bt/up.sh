#!/bin/bash
# 拉镜像 + 起 db/backend（宝塔共存形态）。
#   用法：bash deploy/bt/up.sh
set -euo pipefail
cd "$(dirname "$0")/.."           # 到 deploy/
[ -f .env ] || { echo "缺 .env，先跑 gen-env.sh"; exit 1; }

docker compose -p huicui --env-file .env -f bt/docker-compose.bt.yml up -d

echo "--- 等后端就绪（Flyway 迁移 + 启动，最多 ~90s）---"
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:9091/actuator/health >/dev/null 2>&1; then
    echo "✅ 后端健康：$(curl -s http://127.0.0.1:9091/actuator/health)"
    break
  fi
  sleep 5
  [ "$i" = "30" ] && echo "⚠️ 90s 内未就绪，看日志：docker logs huicui-backend-1 --tail 60"
done
docker compose -p huicui ls 2>/dev/null || docker ps --format '{{.Names}}\t{{.Status}}'
