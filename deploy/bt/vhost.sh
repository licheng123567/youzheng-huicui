#!/bin/bash
# 写 cuiai.doorai.cn 的正式 vhost：80 跳 443；443 反代到本地后端 + 托管前端静态。
# 放进宝塔 nginx 的 vhost 加载目录，reload。不碰现有三个站。
#   用法：bash deploy/bt/vhost.sh
set -euo pipefail

DOMAIN=cuiai.doorai.cn
WEBROOT=/www/wwwroot/$DOMAIN
CERTDIR=/www/server/panel/vhost/cert/$DOMAIN
NGINX=/www/server/nginx/sbin/nginx
VHOST=/www/server/panel/vhost/nginx/$DOMAIN.conf

if [ ! -f "$CERTDIR/fullchain.pem" ]; then
  echo "证书还没签好（$CERTDIR/fullchain.pem 不存在），先跑 cert.sh。"; exit 1
fi

cat > "$VHOST" <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    location ^~ /.well-known/acme-challenge/ { root $WEBROOT; }
    location / { return 301 https://\$host\$request_uri; }
}
server {
    listen 443 ssl;
    http2 on;
    server_name $DOMAIN;

    ssl_certificate     $CERTDIR/fullchain.pem;
    ssl_certificate_key $CERTDIR/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:SSLcuiai:10m;
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options nosniff always;

    # 录音是 multipart 大文件：与后端 multipart 上限对齐
    client_max_body_size 220M;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
    proxy_request_buffering off;

    root $WEBROOT;
    index index.html;

    location /v1/ {
        proxy_pass http://127.0.0.1:9091/v1/;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    location = /actuator/health {
        proxy_pass http://127.0.0.1:9091/actuator/health;
    }
    location / {
        try_files \$uri \$uri/ /index.html;
    }
}
EOF

$NGINX -t && systemctl reload nginx
echo "vhost 已生效：https://$DOMAIN"
