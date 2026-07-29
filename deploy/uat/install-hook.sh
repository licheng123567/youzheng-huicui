#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BARE_REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
INSTALL_DIR=${UAT_INSTALL_DIR:-/opt/huicui-uat/bin}
STATE_DIR=${UAT_STATE_DIR:-/var/lib/huicui-uat}
LOG_DIR=${UAT_LOG_DIR:-/var/log/huicui-uat}
SOURCE_DIR=${UAT_SRC:-/root/huicui-uat-src}

[ -d "$BARE_REPO/objects" ] || {
  echo "uat hook install: bare repository not found: $BARE_REPO" >&2
  exit 1
}

install -d -m 0750 "$INSTALL_DIR" "$STATE_DIR" "$LOG_DIR" "$SOURCE_DIR"
install -m 0755 "$SCRIPT_DIR/worker.sh" "$INSTALL_DIR/worker.sh"
install -m 0755 "$SCRIPT_DIR/post-receive" "$BARE_REPO/hooks/post-receive"
git --git-dir="$BARE_REPO" config receive.denyNonFastForwards true

echo "installed UAT hook: $BARE_REPO/hooks/post-receive"
