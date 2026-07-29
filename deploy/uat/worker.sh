#!/bin/sh
set -eu
umask 077

BARE_REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
SOURCE_DIR=${UAT_SRC:-/root/huicui-uat-src}
STATE_DIR=${UAT_STATE_DIR:-/var/lib/huicui-uat}
LOG_DIR=${UAT_LOG_DIR:-/var/log/huicui-uat}
PENDING_SHA="$STATE_DIR/pending-sha"
LOCK_FILE="$STATE_DIR/deploy.lock"

process_sha() {
  echo "started $(date -u +%FT%TZ) $sha"
  resolved_sha=$(git --git-dir="$BARE_REPO" rev-parse --verify "$sha^{commit}") || return
  [ "$resolved_sha" = "$sha" ] || return
  git --git-dir="$BARE_REPO" --work-tree="$SOURCE_DIR" checkout -f "$sha" || return
  (cd "$SOURCE_DIR" && ./deploy/uat/deploy.sh "$sha") || return
  echo "finished $(date -u +%FT%TZ) $sha"
}

mkdir -p "$SOURCE_DIR" "$STATE_DIR" "$LOG_DIR"
[ -d "$BARE_REPO/objects" ] || {
  echo "uat worker: bare repository not found: $BARE_REPO" >&2
  exit 1
}

exec 9>"$LOCK_FILE"
flock 9

while :; do
  claimed="$STATE_DIR/processing-sha.$$"
  if ! mv "$PENDING_SHA" "$claimed" 2>/dev/null; then
    break
  fi

  sha=$(sed -n '1p' "$claimed")
  rm -f "$claimed"
  if [ "${#sha}" -ne 40 ]; then
    echo "uat worker: rejected invalid pending SHA" >>"$LOG_DIR/worker.log"
    continue
  fi
  case "$sha" in
    *[!0-9a-f]*)
      echo "uat worker: rejected invalid pending SHA" >>"$LOG_DIR/worker.log"
      continue
      ;;
  esac

  log="$LOG_DIR/$sha.log"
  if process_sha >>"$log" 2>&1; then
    :
  else
    temporary=$(mktemp "$STATE_DIR/failed-sha.XXXXXX")
    printf '%s\n' "$sha" >"$temporary"
    mv "$temporary" "$STATE_DIR/failed-sha"
    echo "failed $(date -u +%FT%TZ) $sha" >>"$log"
  fi
done
