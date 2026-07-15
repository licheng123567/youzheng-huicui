#!/bin/bash
# 在 node 容器里构建前端（宿主不装 node），产物拷到宝塔站点根目录。
# 前端用相对路径 /v1，同源走宝塔 nginx 反代 —— 不需要按域名区分构建。
#   用法：bash deploy/bt/frontend.sh
set -euo pipefail
cd "$(dirname "$0")/../.."        # 到仓库根 /root/huicui
DOMAIN=cuiai.doorai.cn
WEBROOT=/www/wwwroot/$DOMAIN

echo "[1/3] node 容器构建（npm 走淘宝镜像）"
docker run --rm -v "$PWD/frontend:/app" -w /app node:20 sh -c "\
  npm config set registry https://registry.npmmirror.com && \
  (npm ci || npm install) && npm run build"

echo "[2/3] 拷贝产物到 $WEBROOT"
mkdir -p "$WEBROOT"
rm -rf "${WEBROOT:?}/assets" "$WEBROOT/index.html"
cp -r frontend/dist/* "$WEBROOT/"

echo "[3/3] 完成，静态文件："
ls -1 "$WEBROOT" | head
