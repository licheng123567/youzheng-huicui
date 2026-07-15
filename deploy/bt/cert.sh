#!/bin/bash
# 用 acme.sh 给 cuiai.doorai.cn 签 Let's Encrypt 证书（webroot 模式，复用宿主宝塔 nginx）。
# acme.sh 从 gitee 装（github 在这台机器时通时断，gitee 稳）。
#   用法：bash deploy/bt/cert.sh
set -euo pipefail

DOMAIN=cuiai.doorai.cn
EMAIL=${HUICUI_ACME_EMAIL:-admin@doorai.cn}
WEBROOT=/www/wwwroot/$DOMAIN
CERTDIR=/www/server/panel/vhost/cert/$DOMAIN
NGINX=/www/server/nginx/sbin/nginx
VHOST=/www/server/panel/vhost/nginx/$DOMAIN.conf
ACME=/root/.acme.sh/acme.sh

echo "[1/6] 建站点根目录 + acme 验证目录"
mkdir -p "$WEBROOT/.well-known/acme-challenge" "$CERTDIR"

echo "[2/6] 装 acme.sh（gitee 源）"
if [ ! -x "$ACME" ]; then
  rm -rf /root/acme.sh-src
  git clone --depth 1 https://gitee.com/neilpang/acme.sh.git /root/acme.sh-src
  (cd /root/acme.sh-src && ./acme.sh --install -m "$EMAIL")
fi
"$ACME" --set-default-ca --server letsencrypt

echo "[3/6] 放一个临时 HTTP vhost（只为 acme 验证），reload nginx"
cat > "$VHOST" <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    location ^~ /.well-known/acme-challenge/ { root $WEBROOT; default_type text/plain; }
    location / { return 200 'acme-pending'; }
}
EOF
$NGINX -t && systemctl reload nginx

echo "[4/6] 签发证书（webroot）"
"$ACME" --issue -d "$DOMAIN" -w "$WEBROOT" --keylength ec-256

echo "[5/6] 安装证书到 $CERTDIR，并设置续期后自动 reload nginx"
"$ACME" --install-cert -d "$DOMAIN" --ecc \
  --key-file       "$CERTDIR/privkey.pem" \
  --fullchain-file "$CERTDIR/fullchain.pem" \
  --reloadcmd      "systemctl reload nginx"

echo "[6/6] 证书就位："
ls -l "$CERTDIR"
echo "完成。接着跑 deploy/bt/vhost.sh 换成正式的 443 反代 vhost。"
