#!/bin/bash
# 生成 deploy/.env（在服务器上生成密钥，密钥永不离开服务器）。
# 幂等：.env 已存在则不覆盖 —— HUICUI_CRYPTO_KEY 一旦用于加密就不能换，绝不能被重新生成冲掉。
#   用法：bash deploy/bt/gen-env.sh
set -euo pipefail
cd "$(dirname "$0")/.."          # 进到 deploy/
ENV=.env

if [ -f "$ENV" ]; then
  echo "[跳过] $ENV 已存在，不覆盖（保护已有 CRYPTO_KEY/JWT）。"
  echo "如确需重来：先 docker compose -p huicui down -v 清库，再删 .env 重跑。"
  exit 0
fi

PGPW=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
JWT=$(openssl rand -base64 48)
CRYPTO=$(openssl rand -base64 32)
ADMPW=$(openssl rand -base64 18 | tr -d '/+=' | head -c 18)

cat > "$ENV" <<EOF
# 有证慧催 · 生产环境变量（本文件含密钥，勿外传、勿入库）
# 生成时间：由 gen-env.sh 在服务器本地生成
HUICUI_IMAGE_TAG=main
HUICUI_IMAGE=ghcr.io/licheng123567/huicui-backend

POSTGRES_DB=huicui
POSTGRES_USER=huicui
POSTGRES_PASSWORD=$PGPW

HUICUI_JWT_SECRET=$JWT
HUICUI_CRYPTO_KEY=$CRYPTO
HUICUI_PUBLIC_BASE=https://cuiai.doorai.cn

# 初始管理员引导（首登强制改密后，把这三行删掉）
HUICUI_BOOTSTRAP_ADMIN_USERNAME=admin
HUICUI_BOOTSTRAP_ADMIN_PASSWORD=$ADMPW
HUICUI_BOOTSTRAP_ADMIN_PHONE=13800000000
EOF
chmod 600 "$ENV"

echo "==================================================="
echo "[.env 已生成] 首登用的初始管理员口令（记下来）："
echo "    用户名：admin"
echo "    口令  ：$ADMPW"
echo "==================================================="
echo "（口令也在 $ENV 里；首登改密后按 clean-bootstrap.sh 删除引导三行）"
