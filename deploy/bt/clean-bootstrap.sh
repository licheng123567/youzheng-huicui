#!/bin/bash
# 首登改密后，从 .env 删掉初始管理员引导三行（口令此刻已作废）。
#   用法：bash deploy/bt/clean-bootstrap.sh  然后重启 backend 使其不再带引导变量
set -euo pipefail
cd "$(dirname "$0")/.."           # 到 deploy/
sed -i '/HUICUI_BOOTSTRAP_ADMIN_/d' .env
echo "已删除 .env 里的引导三行。现在重启后端："
echo "  docker compose -p huicui --env-file .env -f bt/docker-compose.bt.yml up -d backend"
