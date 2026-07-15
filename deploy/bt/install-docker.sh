#!/bin/bash
# 在宝塔面板机（Alibaba Cloud Linux 3）上装 Docker + compose 插件。
# 只装 Docker，不碰现有 nginx / 站点。日志写 /tmp/docker-install.log。
# 用法：bash deploy/bt/install-docker.sh
set -x
exec > /tmp/docker-install.log 2>&1
echo "[start] $(date)"

# 阿里云 docker-ce 源（alinux3 = el8，$releasever 要改成 8）
dnf -y install dnf-plugins-core || dnf -y install yum-utils
dnf config-manager --add-repo \
  https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
sed -i 's|$releasever|8|g' /etc/yum.repos.d/docker-ce.repo

# docker.io 拉取加速（ghcr 直连；pgvector/nginx 基础镜像在 docker.io）
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'JSON'
{
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://hub-mirror.c.163.com"
  ],
  "log-driver": "json-file",
  "log-opts": {"max-size": "50m", "max-file": "3"}
}
JSON

dnf -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable --now docker

echo "[versions]"
docker --version
docker compose version
if docker run --rm hello-world >/dev/null 2>&1; then
  echo "HELLO_OK"
else
  echo "HELLO_FAIL"
fi
echo "[done] $(date)"
