#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BARE_REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
INSTALL_DIR=${UAT_INSTALL_DIR:-/opt/huicui-uat/bin}
STATE_DIR=${UAT_STATE_DIR:-/var/lib/huicui-uat}
LOG_DIR=${UAT_LOG_DIR:-/var/log/huicui-uat}
SOURCE_DIR=${UAT_SRC:-/root/huicui-uat-src}
ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}
HOOK_CONFIG="$BARE_REPO/hooks/huicui-uat-hook.conf"

for configured_path in \
  "$BARE_REPO" "$INSTALL_DIR" "$STATE_DIR" "$LOG_DIR" "$SOURCE_DIR" "$ENV_FILE"
do
  case "$configured_path" in
    /*) ;;
    *) echo "uat hook install: path must be absolute: $configured_path" >&2; exit 1 ;;
  esac
  case "$configured_path" in
    *[!A-Za-z0-9_./:-]*)
      echo "uat hook install: unsupported character in path: $configured_path" >&2
      exit 1
      ;;
  esac
done

[ -d "$BARE_REPO/objects" ] || {
  echo "uat hook install: bare repository not found: $BARE_REPO" >&2
  exit 1
}

install -d -m 0750 "$INSTALL_DIR" "$STATE_DIR" "$LOG_DIR" "$SOURCE_DIR"
install -m 0755 "$SCRIPT_DIR/worker.sh" "$INSTALL_DIR/worker.sh"

config_temporary=$(mktemp "$BARE_REPO/hooks/huicui-uat-hook.conf.XXXXXX")
{
  printf 'HOOK_UAT_REPO=%s\n' "$BARE_REPO"
  printf 'HOOK_UAT_RUNNER=%s/worker.sh\n' "$INSTALL_DIR"
  printf 'HOOK_UAT_STATE_DIR=%s\n' "$STATE_DIR"
  printf 'HOOK_UAT_LOG_DIR=%s\n' "$LOG_DIR"
  printf 'HOOK_UAT_SRC=%s\n' "$SOURCE_DIR"
  printf 'HOOK_UAT_ENV_FILE=%s\n' "$ENV_FILE"
} >"$config_temporary"
chmod 0600 "$config_temporary"
mv "$config_temporary" "$HOOK_CONFIG"
install -m 0755 "$SCRIPT_DIR/post-receive" "$BARE_REPO/hooks/post-receive"

git --git-dir="$BARE_REPO" config receive.denyNonFastForwards true

echo "installed UAT hook: $BARE_REPO/hooks/post-receive"
