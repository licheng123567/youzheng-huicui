#!/usr/bin/env bash
# 有证慧催 · 健康巡检与告警
#
#   crontab: */5 * * * *  cd /opt/huicui && deploy/monitor.sh >> /var/log/huicui-monitor.log 2>&1
#
# ── 为什么需要它 ────────────────────────────────────────────────────────────────
# 部署里只有一个容器级 HEALTHCHECK，而 `restart: unless-stopped` **不会因为 unhealthy 而重启** ——
# 容器只是被标成 unhealthy，然后一直不健康，没有任何人会知道。
# 除此之外没有任何监控、任何告警：服务挂了、证书过期了、备份三天没跑成功了、磁盘满了 ——
# 你都只能等客户打电话来告诉你。
#
# ── 检查项按「什么会先咬人」排序 ────────────────────────────────────────────────
#   1. 站点可达            —— 最直接
#   2. 容器状态            —— unhealthy 不会自愈（autoheal 负责重启，这里负责告诉你它重启过）
#   3. **TLS 证书剩余天数** —— 静默杀手：certbot 续证失败不会有任何动静，
#                              然后在第 90 天，整站在毫无预兆的情况下全挂
#   4. **备份新鲜度**      —— 静默杀手：备份脚本失败只往日志写一行，
#                              等你真要恢复的那天才发现「三个月没有备份了」
#   5. 磁盘水位            —— 备份与 PG 数据同一块盘，撑爆就一起完蛋
#   6. 数据库可连
#
# ── 告警去重 ────────────────────────────────────────────────────────────────────
# 每 5 分钟一次，故障时若每次都发，一晚上能刷 288 条 —— 那等于没有告警（没人会看）。
# 只在**状态翻转**时发（OK→FAIL 报警、FAIL→OK 报恢复），持续故障每 REPEAT_HOURS 复读一次。
set -uo pipefail

cd "$(dirname "$0")/.."
[ -f deploy/.env ] && { set -a; . deploy/.env; set +a; }

STATE_FILE="deploy/.monitor-state"
REPEAT_HOURS="${HUICUI_ALERT_REPEAT_HOURS:-6}"
DISK_WARN="${HUICUI_DISK_WARN_PCT:-80}"
DISK_CRIT="${HUICUI_DISK_CRIT_PCT:-90}"
BACKUP_MAX_AGE_H="${HUICUI_BACKUP_MAX_AGE_HOURS:-26}"   # 日备 + 2h 宽限
CERT_WARN_DAYS="${HUICUI_CERT_WARN_DAYS:-20}"
CERT_CRIT_DAYS="${HUICUI_CERT_CRIT_DAYS:-7}"

touch "$STATE_FILE"
NOW=$(date +%s)

# report <key> <OK|WARN|CRIT> <消息>
report() {
  local key="$1" status="$2" msg="$3"
  local prev prev_status prev_ts
  prev="$(grep "^$key=" "$STATE_FILE" 2>/dev/null | tail -1)"
  prev_status="${prev#*=}"; prev_status="${prev_status%%:*}"
  prev_ts="${prev##*:}"; [ -n "$prev_ts" ] && [ "$prev_ts" -eq "$prev_ts" ] 2>/dev/null || prev_ts=0

  local should_alert=0
  if [ "$status" != "${prev_status:-OK}" ]; then
    should_alert=1                                    # 状态翻转：报警或报恢复
  elif [ "$status" != "OK" ] && [ $((NOW - prev_ts)) -ge $((REPEAT_HOURS * 3600)) ]; then
    should_alert=1                                    # 持续故障：每 N 小时复读一次
  fi

  if [ "$should_alert" = "1" ]; then
    if [ "$status" = "OK" ]; then
      [ -n "${prev_status:-}" ] && [ "${prev_status:-OK}" != "OK" ] && deploy/alert.sh OK "$msg" || true
    else
      deploy/alert.sh "$status" "$msg"
    fi
    grep -v "^$key=" "$STATE_FILE" > "$STATE_FILE.tmp" 2>/dev/null || true
    echo "$key=$status:$NOW" >> "$STATE_FILE.tmp"
    mv "$STATE_FILE.tmp" "$STATE_FILE"
  elif [ -z "$prev" ]; then
    echo "$key=$status:$NOW" >> "$STATE_FILE"
  fi
  echo "[$status] $key — $msg"
}

COMPOSE="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"

# ── 1. 站点可达（走 nginx，即真实用户路径）──────────────────────────────────────
if [ -n "${HUICUI_DOMAIN:-}" ]; then
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://$HUICUI_DOMAIN/actuator/health" || echo 000)
  if [ "$CODE" = "200" ]; then
    report site OK "站点可达"
  else
    report site CRIT "站点不可达：https://$HUICUI_DOMAIN/actuator/health 返回 $CODE"
  fi
fi

# ── 2. 容器状态 ────────────────────────────────────────────────────────────────
BAD=$($COMPOSE ps --format '{{.Name}} {{.State}} {{.Status}}' 2>/dev/null \
        | grep -viE 'running|healthy' | grep -vE '^\s*$' || true)
if [ -z "$BAD" ]; then
  report containers OK "容器全部正常"
else
  report containers CRIT "容器异常：
$BAD"
fi

# ── 3. TLS 证书剩余天数（静默杀手：续证失败没有任何动静，第 90 天整站全挂）────────
CERT="deploy/certbot/conf/live/${HUICUI_DOMAIN:-none}/fullchain.pem"
if [ -f "$CERT" ]; then
  END=$(openssl x509 -enddate -noout -in "$CERT" 2>/dev/null | cut -d= -f2)
  END_TS=$(date -d "$END" +%s 2>/dev/null || date -j -f "%b %d %T %Y %Z" "$END" +%s 2>/dev/null || echo 0)
  if [ "$END_TS" -gt 0 ]; then
    DAYS=$(( (END_TS - NOW) / 86400 ))
    if   [ "$DAYS" -le "$CERT_CRIT_DAYS" ]; then report cert CRIT "TLS 证书 $DAYS 天后过期！续证已经失败了，再不管整站会全挂。检查 certbot renew 的 cron。"
    elif [ "$DAYS" -le "$CERT_WARN_DAYS" ]; then report cert WARN "TLS 证书还有 $DAYS 天过期（正常应在 30 天前自动续上，说明续证可能没在跑）"
    else report cert OK "TLS 证书剩余 $DAYS 天"
    fi
  fi
fi

# ── 4. 备份新鲜度（静默杀手：等你要恢复那天才发现「三个月没备份了」）───────────────
LAST=$(ls -t deploy/backup/huicui-*.sql.gz.enc deploy/backup/huicui-*.sql.gz 2>/dev/null | head -1)
if [ -z "$LAST" ]; then
  report backup CRIT "**一份备份都没有**。deploy/backup.sh 从没成功跑过，或公钥未配置（脚本会拒绝产出明文备份）。"
else
  MT=$(stat -f %m "$LAST" 2>/dev/null || stat -c %Y "$LAST" 2>/dev/null || echo 0)
  AGE_H=$(( (NOW - MT) / 3600 ))
  if [ "$AGE_H" -gt "$BACKUP_MAX_AGE_H" ]; then
    report backup CRIT "最近一次备份是 $AGE_H 小时前（$(basename "$LAST")）。日备已经断了 —— 现在出事就没有可恢复的点。"
  else
    report backup OK "最近备份 $AGE_H 小时前"
  fi
fi

# ── 5. 磁盘水位（备份与 PG 数据同一块盘）────────────────────────────────────────
PCT=$(df -P . | awk 'NR==2{gsub(/%/,"",$5); print $5}')
if   [ "$PCT" -ge "$DISK_CRIT" ]; then report disk CRIT "磁盘 ${PCT}%。PG 数据与备份在同一块盘上，写满会同时搞死数据库和备份。"
elif [ "$PCT" -ge "$DISK_WARN" ]; then report disk WARN "磁盘 ${PCT}%（录音以 bytea 存库，会持续变大）"
else report disk OK "磁盘 ${PCT}%"
fi

# ── 6. 数据库可连 ──────────────────────────────────────────────────────────────
if $COMPOSE exec -T db pg_isready -U "${POSTGRES_USER:-huicui}" -d "${POSTGRES_DB:-huicui}" >/dev/null 2>&1; then
  report db OK "数据库可连"
else
  report db CRIT "数据库连不上（pg_isready 失败）"
fi
