#!/usr/bin/env bash
# 有证慧催 · PG 逻辑备份（加密 + 异地）
#
#   deploy/backup.sh                    # 立即备份一次
#   crontab: 0 3 * * *  cd /opt/huicui && deploy/backup.sh >> /var/log/huicui-backup.log 2>&1
#
# ── 为什么必须加密，而且必须是**非对称**加密 ──────────────────────────────────
# 这个库里是业主的**真实姓名、电话、住址、欠费金额、通话录音字节、转写文本** —— 高度敏感个人信息。
# 而备份此前是**明文 .sql.gz，落在 deploy/backup/，与 PG 数据同机同盘**：
# 服务器被拖库，攻击者连备份一起端走；磁盘损坏，备份跟着一起没。
#
# 加密**不能用口令**（对称）：口令只能放在这台机器的 .env 里，攻击者拿到机器就同时拿到备份和口令 ——
# 等于没加密。所以用非对称：**公钥放服务器（只能加密），私钥离线保管**（密码管理器/另一台机器）。
# 服务器被攻破，也解不开已经备份出去的数据。
#
# 生成密钥对（**在你自己的电脑上做，不要在服务器上做**）：
#   openssl genrsa -out huicui-backup-private.pem 4096      # ← 离线保管，绝不上服务器
#   openssl rsa -in huicui-backup-private.pem -pubout -out huicui-backup-public.pem
#   scp huicui-backup-public.pem <服务器>:/opt/huicui/deploy/backup-public.pem
#
# 恢复见 deploy/RESTORE.md。没有私钥就恢复不了 —— 这正是它该有的样子，
# 所以**务必先演练一次恢复**，再开始依赖这套备份。
set -euo pipefail

cd "$(dirname "$0")/.."

# 备份失败必须**告警**，不能只往日志写一行 —— 没人会读那行日志，
# 等你真要恢复的那天，才发现「三个月没有备份成功过」。
# 备份的失效方式从来不是"报错了没人管"，而是"以为它一直在跑"。
fail() {
  echo "❌ $1" >&2
  [ -x deploy/alert.sh ] && deploy/alert.sh CRIT "备份失败：$1" || true
  exit 1
}
trap 'rc=$?; [ $rc -ne 0 ] && [ $rc -ne 200 ] && deploy/alert.sh CRIT "备份脚本异常退出（exit=$rc），本次没有产出备份。" >/dev/null 2>&1; exit $rc' ERR

ENV_FILE="deploy/.env"
[ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE"; exit 1; }
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

RETAIN_DAYS="${BACKUP_RETAIN_DAYS:-14}"
OUT_DIR="deploy/backup"
PUBKEY="${BACKUP_PUBLIC_KEY:-deploy/backup-public.pem}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

umask 077        # 备份文件不给同机其他用户读

# 文件名带上「当时跑的镜像 tag」。回退时（ROLLBACK.md 第 3 节）第一件事就是找
# 「升级到新版本之前」的那份备份 —— 只有时间戳的话，你得靠记忆去猜。
TAG_SUFFIX=""
[ -n "${HUICUI_IMAGE_TAG:-}" ] && TAG_SUFFIX="-${HUICUI_IMAGE_TAG//\//_}"
BASE="$OUT_DIR/huicui-$STAMP$TAG_SUFFIX"

mkdir -p "$OUT_DIR"

# ── 加密前置检查：默认**拒绝**产出明文备份 ────────────────────────────────────
# 「忘配公钥 → 静默写出一份明文的全量业主 PII」是这类脚本最常见也最致命的失效方式。
# 宁可备份失败并大声报错，也不要悄悄留下一个谁都能读的 dump。
if [ ! -f "$PUBKEY" ]; then
  if [ "${BACKUP_ALLOW_PLAINTEXT:-false}" != "true" ]; then
    cat >&2 <<EOF
❌ 找不到备份公钥：$PUBKEY

库里是业主的真实姓名/电话/住址/欠费金额/通话录音。**拒绝产出明文备份。**

生成密钥对（在你自己的电脑上，不要在服务器上）：
  openssl genrsa -out huicui-backup-private.pem 4096      # 离线保管，绝不上服务器
  openssl rsa -in huicui-backup-private.pem -pubout -out huicui-backup-public.pem
  scp huicui-backup-public.pem <服务器>:\$(pwd)/$PUBKEY

明知风险仍要明文备份（**仅限本地临时排查**）：
  BACKUP_ALLOW_PLAINTEXT=true deploy/backup.sh
EOF
    exit 1
  fi
  echo "⚠️  BACKUP_ALLOW_PLAINTEXT=true：本次产出**明文**备份（含全量业主 PII），仅供临时排查。" >&2
fi

# ── 1. dump（先落临时明文，验证完整性后再加密）────────────────────────────────
# 为什么要先落一次临时明文：完整性校验（"dump complete" 标记）必须读内容，
# 而加密之后本机**没有私钥、读不回来** —— 那样就只能"以为有备份"。
# 临时文件 umask 077，用完立刻抹掉（trap 保证异常退出也抹）。
TMP="$(mktemp "$OUT_DIR/.tmp-XXXXXX")"
cleanup() {
  [ -f "$TMP" ] && { command -v shred >/dev/null 2>&1 && shred -u "$TMP" 2>/dev/null || rm -f "$TMP"; }
  return 0
}
trap cleanup EXIT

docker compose -f deploy/docker-compose.prod.yml --env-file "$ENV_FILE" \
  exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges \
  | gzip -9 > "$TMP"

# 备份必须可验证：空文件/截断的 dump 比没有备份更危险
SIZE=$(wc -c < "$TMP")
[ "$SIZE" -gt 1024 ] || fail "dump 异常：仅 $SIZE 字节（pg_dump 失败了？）"
zcat "$TMP" | tail -5 | grep -q "PostgreSQL database dump complete" \
  || fail "dump 不完整（缺 dump complete 标记）——截断的备份比没有备份更危险"

# ── 2. 加密：混合加密（AES 流式加数据，RSA 封住那把一次性 AES 密钥）──────────────
# 不用 `openssl smime -encrypt`：它会把整份 dump 读进内存，而录音以 bytea 存库、
# dump 可能长到几十 GB。混合加密边读边写，内存恒定。
KEYOUT=""
if [ -f "$PUBKEY" ]; then
  OUT="$BASE.sql.gz.enc"
  KEYOUT="$BASE.key.enc"

  DEK="$(openssl rand -base64 32)"                       # 一次性数据密钥（每份备份都不同）
  printf '%s' "$DEK" | openssl pkeyutl -encrypt -pubin -inkey "$PUBKEY" \
    -pkeyopt rsa_padding_mode:oaep -out "$KEYOUT"        # 用公钥封住 → 只有离线私钥能拆
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass "env:DEK" -in "$TMP" -out "$OUT"
  export -n DEK; unset DEK

  ENC_SIZE=$(wc -c < "$OUT")
  echo "✅ 备份完成（已加密）$OUT ($((ENC_SIZE/1024)) KB) + 密钥信封 $KEYOUT"
  echo "   解开它需要**离线保管的私钥**；本机没有私钥 —— 这正是它该有的样子。"
else
  OUT="$BASE.sql.gz"
  cp "$TMP" "$OUT"
  echo "⚠️  备份完成（**明文**）$OUT ($((SIZE/1024)) KB)"
fi

# ── 3. 异地 ──────────────────────────────────────────────────────────────────
# 备份与数据库同一块盘 = 磁盘坏了两个一起没、机器被拖库两个一起走。
# 不绑定具体云厂商：给一条命令模板，{} 会被替换成文件路径。
#   BACKUP_UPLOAD_CMD='aws s3 cp {} s3://my-bucket/huicui/'
#   BACKUP_UPLOAD_CMD='ossutil cp {} oss://my-bucket/huicui/'
#   BACKUP_UPLOAD_CMD='rclone copy {} remote:huicui/'
if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
  for f in "$OUT" $KEYOUT; do
    [ -f "$f" ] || continue
    echo "→ 异地上传 $f"
    eval "${BACKUP_UPLOAD_CMD//\{\}/$f}"
  done
  echo "✅ 异地上传完成"
else
  echo "⚠️  未配置 BACKUP_UPLOAD_CMD：备份只存在于**本机**，与数据库同一块盘。"
  echo "   磁盘损坏或服务器被入侵时，备份会和数据一起消失。生产强烈建议配置异地。"
fi

# ── 4. 清理过期 ──────────────────────────────────────────────────────────────
find "$OUT_DIR" -name 'huicui-*.sql.gz*' -mtime +"$RETAIN_DAYS" -print -delete
find "$OUT_DIR" -name 'huicui-*.key.enc'  -mtime +"$RETAIN_DAYS" -print -delete
