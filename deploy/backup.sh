#!/usr/bin/env bash
# 有证慧催 · PG 逻辑备份（compose 形态）
#
#   deploy/backup.sh                    # 立即备份一次
#   crontab: 0 3 * * *  cd /opt/huicui && deploy/backup.sh >> /var/log/huicui-backup.log 2>&1
#
# 落盘到 deploy/backup/（已 gitignore），保留 RETAIN_DAYS 天。
# 注意：录音/附件当前以 bytea 存在库里（V921/V923），所以 dump 会比较大——
# 迁到对象存储前，备份体积与恢复时长都要按这个前提规划。
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE="deploy/.env"
[ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE"; exit 1; }
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

RETAIN_DAYS="${BACKUP_RETAIN_DAYS:-14}"
OUT_DIR="deploy/backup"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$OUT_DIR/huicui-$STAMP.sql.gz"

mkdir -p "$OUT_DIR"

# -Fc 自定义格式便于 pg_restore 选择性恢复；这里用 plain+gzip 以便 zcat 直接检查
docker compose -f deploy/docker-compose.prod.yml --env-file "$ENV_FILE" \
  exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges \
  | gzip -9 > "$OUT"

# 备份必须可验证：空文件/截断的 dump 比没有备份更危险
SIZE=$(wc -c < "$OUT")
[ "$SIZE" -gt 1024 ] || { echo "备份异常：$OUT 仅 $SIZE 字节"; rm -f "$OUT"; exit 1; }
zcat "$OUT" | tail -5 | grep -q "PostgreSQL database dump complete" \
  || { echo "备份不完整（缺 dump complete 标记）：$OUT"; exit 1; }

echo "✅ 备份完成 $OUT ($((SIZE/1024)) KB)"

# 清理过期
find "$OUT_DIR" -name 'huicui-*.sql.gz' -mtime +"$RETAIN_DAYS" -print -delete

# 恢复演练（务必在非生产库上做）：
#   zcat deploy/backup/huicui-XXXX.sql.gz | docker compose ... exec -T db psql -U "$POSTGRES_USER" -d restore_test
