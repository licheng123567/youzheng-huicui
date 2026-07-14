#!/usr/bin/env bash
# 有证慧催 · 告警发送
#
#   deploy/alert.sh CRIT "备份已 3 天没跑成功"
#   deploy/alert.sh WARN "磁盘 87%"
#   deploy/alert.sh OK   "备份恢复正常"
#
# 不绑定告警平台：按 HUICUI_ALERT_WEBHOOK 的域名自动识别钉钉/企业微信/飞书，
# 认不出来就发通用 JSON（{level,message,host,time}），Slack 和大多数自建接收端都能吃。
#
# 没配 webhook 时**不静默**：往 stderr 打一行，让它至少能进 cron 的日志。
# 「告警配了但发不出去」和「压根没配」，都必须是看得见的。
set -uo pipefail

LEVEL="${1:-WARN}"
MSG="${2:-}"
[ -n "$MSG" ] || { echo "用法: alert.sh <CRIT|WARN|OK> <消息>" >&2; exit 2; }

cd "$(dirname "$0")/.."
[ -f deploy/.env ] && { set -a; . deploy/.env; set +a; }

HOST="${HUICUI_DOMAIN:-$(hostname)}"
TS="$(date '+%Y-%m-%d %H:%M:%S %Z')"
ICON="⚠️"; [ "$LEVEL" = "CRIT" ] && ICON="🔴"; [ "$LEVEL" = "OK" ] && ICON="✅"
TEXT="$ICON [$LEVEL] 有证慧催 $HOST
$MSG
$TS"

if [ -z "${HUICUI_ALERT_WEBHOOK:-}" ]; then
  # 没配就打到 stderr —— cron 会把它收进 /var/log/huicui-monitor.log。
  # 绝不 exit 0 假装成功：那样「以为配了告警」就成了下一个静默失效。
  echo "[alert] 未配置 HUICUI_ALERT_WEBHOOK，告警只能落日志：$TEXT" >&2
  exit 0
fi

esc() { python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <<< "$1"; }
BODY_TEXT="$(esc "$TEXT")"

case "$HUICUI_ALERT_WEBHOOK" in
  *oapi.dingtalk.com*)                       # 钉钉
    PAYLOAD="{\"msgtype\":\"text\",\"text\":{\"content\":$BODY_TEXT}}" ;;
  *qyapi.weixin.qq.com*)                     # 企业微信
    PAYLOAD="{\"msgtype\":\"text\",\"text\":{\"content\":$BODY_TEXT}}" ;;
  *open.feishu.cn*)                          # 飞书
    PAYLOAD="{\"msg_type\":\"text\",\"content\":{\"text\":$BODY_TEXT}}" ;;
  *hooks.slack.com*)                         # Slack
    PAYLOAD="{\"text\":$BODY_TEXT}" ;;
  *)                                         # 通用
    PAYLOAD="{\"level\":\"$LEVEL\",\"message\":$BODY_TEXT,\"host\":\"$HOST\",\"time\":\"$TS\"}" ;;
esac

CODE=$(curl -s -o /tmp/alert-resp.$$ -w '%{http_code}' --max-time 10 \
  -H 'Content-Type: application/json' -d "$PAYLOAD" "$HUICUI_ALERT_WEBHOOK" || echo 000)

if [ "$CODE" != "200" ]; then
  # 告警发不出去，本身就是最该被看见的故障。
  echo "[alert] 发送失败 HTTP=$CODE resp=$(head -c 200 /tmp/alert-resp.$$ 2>/dev/null)" >&2
  rm -f /tmp/alert-resp.$$
  exit 1
fi
rm -f /tmp/alert-resp.$$
