#!/usr/bin/env bash
# 有证慧催 · 本地冷备
#
#   HUICUI_BACKUP_DIR=/Volumes/移动硬盘/huicui-backup  scripts/backup-local.sh
#
# 防的是什么：**GitHub 连不上、或账号出问题。** 目前代码只存在两处 —— 本机 .git 与 GitHub。
# 这个脚本把「完整历史 + git 管不到的东西」再落一份到你指定的目录。
#
# 防不了什么：**硬盘坏。** 备份目录若和仓库在同一块盘上，盘坏了两份一起没。
# 把 HUICUI_BACKUP_DIR 指向移动硬盘 / NAS / 另一台机器。
#
# 幂等：可以随时重复跑。首次 clone --mirror，之后 push --mirror 增量同步。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# ── 目录必须显式指定：不猜路径，猜错了备份到哪都不知道 ──
if [ -z "${HUICUI_BACKUP_DIR:-}" ]; then
  cat >&2 <<'EOF'
错误：未设置 HUICUI_BACKUP_DIR。

  export HUICUI_BACKUP_DIR=/Volumes/你的移动硬盘/huicui-backup
  scripts/backup-local.sh

指向移动硬盘或 NAS —— 放在本机同一块盘上只能防 GitHub 不可达，防不住硬盘坏。
EOF
  exit 1
fi

BACKUP_DIR="$HUICUI_BACKUP_DIR"
MIRROR="$BACKUP_DIR/youzheng-huicui.git"
BUNDLE_DIR="$BACKUP_DIR/bundles"
SECRET_DIR="$BACKUP_DIR/secrets"
RETAIN_BUNDLES="${HUICUI_RETAIN_BUNDLES:-5}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$BACKUP_DIR" "$BUNDLE_DIR" "$SECRET_DIR"
chmod 700 "$SECRET_DIR"

# ── 0. 先把 GitHub 上的东西同步到本地 ─────────────────────────────────
# 备份的是本地仓库。若别人往 GitHub 推了一个本地没有的分支/tag，不先 fetch 就会漏掉它。
# best-effort：离线时（这正是备份要防的场景之一）继续用本地已有的历史。
if git remote | grep -qx origin; then
  echo "先从 origin 同步（离线则跳过）…"
  git fetch --prune --tags origin 2>/dev/null || echo "  origin 不可达，用本地已有历史继续"
fi

# ── 1. bare 镜像仓库：所有分支 + 所有 tag ──────────────────────────────
# --mirror 而不是普通 clone：它同步 refs/* 全量（含 tag 与远端跟踪分支），
# 而普通 clone 只带一个 HEAD 分支。
if [ ! -d "$MIRROR" ]; then
  echo "首次建立镜像仓库 → $MIRROR"
  git clone --mirror "$REPO_ROOT" "$MIRROR"
else
  echo "同步镜像仓库 → $MIRROR"
  git --git-dir="$MIRROR" remote set-url origin "$REPO_ROOT"
  git --git-dir="$MIRROR" fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
fi

# 让日常也能一条命令推备份：git push backup
if ! git remote | grep -qx backup; then
  git remote add backup "$MIRROR"
  echo "已添加 git remote 'backup' → $MIRROR"
fi

# ── 2. 冷备 bundle：单文件、含完整历史、可直接 git clone ────────────────
# 镜像仓库是一堆松散对象，拷贝易漏；bundle 是一个文件，扔 U 盘/邮件都行。
BUNDLE="$BUNDLE_DIR/huicui-$STAMP.bundle"
git bundle create "$BUNDLE" --all
git bundle verify "$BUNDLE" >/dev/null   # 校验：坏 bundle 比没有 bundle 更危险
echo "bundle → $BUNDLE ($(du -h "$BUNDLE" | cut -f1))"

# 保留最近 N 份
ls -1t "$BUNDLE_DIR"/huicui-*.bundle 2>/dev/null | tail -n +"$((RETAIN_BUNDLES + 1))" | while read -r old; do
  echo "清理旧 bundle：$(basename "$old")"
  rm -f "$old"
done

# ── 3. git 管不到、但丢了会疼的东西 ───────────────────────────────────
# 光备份代码没意义：生产凭据只有一份，且按设计不入库。
backup_secret() {
  local src="$1" name="$2"
  [ -f "$src" ] || return 0   # 文件不存在就没什么可备的

  if ! command -v gpg >/dev/null 2>&1; then
    echo "⚠️  未装 gpg，跳过 $name。生产凭据不能明文落盘：brew install gnupg" >&2
    return 0
  fi
  # --batch 模式下 gpg 不会交互要口令，必须显式给。缺口令就跳过，
  # 绝不退化成明文拷贝 —— 那比不备份更糟。
  if [ -z "${HUICUI_BACKUP_PASSPHRASE:-}" ]; then
    echo "⚠️  未设 HUICUI_BACKUP_PASSPHRASE，跳过 $name（不做明文备份）" >&2
    return 0
  fi

  # 对称加密。解密：gpg -d xxx.gpg
  printf '%s' "$HUICUI_BACKUP_PASSPHRASE" \
    | gpg --batch --yes --symmetric --cipher-algo AES256 \
          --passphrase-fd 0 --pinentry-mode loopback \
          --output "$SECRET_DIR/$name.gpg" "$src"
  chmod 600 "$SECRET_DIR/$name.gpg"
  echo "已加密备份 $name → $SECRET_DIR/$name.gpg"
}

backup_secret "deploy/.env" "deploy.env"
backup_secret "app-android/local.properties" "app-android.local.properties"

# ── 4. 自检：备份必须是能用的 ──────────────────────────────────────────
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git clone -q "$MIRROR" "$TMP/from-mirror"
MIRROR_HEAD="$(git -C "$TMP/from-mirror" rev-parse --short HEAD)"
LOCAL_HEAD="$(git rev-parse --short HEAD)"
[ "$MIRROR_HEAD" = "$LOCAL_HEAD" ] || {
  echo "❌ 镜像仓库的 HEAD ($MIRROR_HEAD) 与本地 ($LOCAL_HEAD) 不一致" >&2
  exit 1
}
MIRROR_TAGS="$(git -C "$TMP/from-mirror" tag | wc -l | tr -d ' ')"
LOCAL_TAGS="$(git tag | wc -l | tr -d ' ')"
[ "$MIRROR_TAGS" = "$LOCAL_TAGS" ] || {
  echo "❌ tag 数不一致：镜像 $MIRROR_TAGS / 本地 $LOCAL_TAGS" >&2
  exit 1
}

echo
echo "✅ 备份完成"
echo "   镜像仓库  $MIRROR      （HEAD=$MIRROR_HEAD, $MIRROR_TAGS 个 tag）"
echo "   冷备 bundle $BUNDLE"
echo "   加密凭据  $SECRET_DIR"
echo
echo "还原演练："
echo "   git clone $MIRROR             # 从镜像"
echo "   git clone $BUNDLE huicui      # 从 bundle（单文件，可离线）"
