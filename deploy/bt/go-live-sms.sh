#!/bin/bash
# 一条命令上线短信 + 手机验证码开户：
#   换含短信修复的新镜像 → 幂等写入短信配置 → 拉镜像重启后端 → 重建前端。
# 密钥不入库：SecretName / SecretKey 作为参数传入（不写进仓库）。
#
#   用法：bash deploy/bt/go-live-sms.sh <SecretName> <SecretKey>
#   例：  bash deploy/bt/go-live-sms.sh CDZXY00037249 'zxy@1234'
set -euo pipefail
cd "$(dirname "$0")/.."            # 到 deploy/

SN="${1:?用法: bash deploy/bt/go-live-sms.sh <SecretName> <SecretKey>}"
SK="${2:?缺 SecretKey}"
[ -f .env ] || { echo "缺 .env，先跑 gen-env.sh"; exit 1; }

echo "[1/4] 镜像换成含短信修复的新版 sha-4c5e9ae"
sed -i 's/^HUICUI_IMAGE_TAG=.*/HUICUI_IMAGE_TAG=sha-4c5e9ae/' .env

echo "[2/4] 幂等写入短信配置（智讯云普通短信·验证码模板 18964）"
sed -i '/^HUICUI_SMS_/d' .env      # 先删旧的，避免重复
cat >> .env <<EOF
HUICUI_SMS_ENABLED=true
HUICUI_SMS_SECRET_NAME=$SN
HUICUI_SMS_SECRET_KEY=$SK
HUICUI_SMS_BASE=https://api.028lk.com
HUICUI_SMS_SIGN=【捷信驰科技】
HUICUI_SMS_TPL_VERIFY=18964
EOF

echo "[3/4] 拉新镜像 + 重启后端（短信生效）"
bash bt/up.sh

echo "[4/4] 重建前端（手机验证码开户 UX）"
bash bt/frontend.sh

echo
echo "==================================================="
echo "✅ 完成：短信已开启、前端已更新。"
echo "自测发码： curl -s -X POST https://cuiai.doorai.cn/v1/auth/sms-code \\"
echo "             -H 'Content-Type: application/json' -d '{\"phone\":\"你的手机号\"}'"
echo "  返回 {\"sent\":true,...} 且手机收到验证码 = 通。"
echo "==================================================="
