# 宝塔共存部署 · cuiai.doorai.cn

这台机器（47.108.81.205）是宝塔面板机，已跑 screen/vr/yzb.doorai.cn 三个生产站。
本套脚本把「有证慧催」以**共存**方式部署上去：宝塔 nginx 继续做前置，我们只在 Docker 里跑
`db + backend`（后端仅监听 127.0.0.1:9091），前端静态交给宝塔 nginx 托管。**不碰现有三个站。**

## 顺序（都在 /root/huicui 下执行）

```bash
cd /root/huicui

# 1. 生成 .env（服务器本地生成密钥；会打印首登管理员口令，记下来）
bash deploy/bt/gen-env.sh

# 2. 签 TLS 证书（acme.sh + webroot，复用宝塔 nginx）
bash deploy/bt/cert.sh

# 3. 换成正式的 443 反代 vhost
bash deploy/bt/vhost.sh

# 4. 构建前端（node 容器）→ 宝塔站点根目录
bash deploy/bt/frontend.sh

# 5. 拉镜像 + 起 db/backend
bash deploy/bt/up.sh

# 6. 验证
curl -s https://cuiai.doorai.cn/actuator/health          # {"status":"UP"}
curl -o /dev/null -w '%{http_code}\n' https://cuiai.doorai.cn/v1/me   # 401=鉴权链路正常
```

## 首次登录（必做）

浏览器打开 https://cuiai.doorai.cn ，用 `admin` + gen-env.sh 打印的口令登录，
后端强制改密。改完后删除引导口令：

```bash
bash deploy/bt/clean-bootstrap.sh
docker compose -p huicui --env-file deploy/.env -f deploy/bt/docker-compose.bt.yml up -d backend
```

## 回退

改 `deploy/.env` 里的 `HUICUI_IMAGE_TAG` 到上一个 tag，再 `bash deploy/bt/up.sh`。

## 资源

内存 1.8G + 3G swap。PG 限 512m、后端限 900m（JVM 堆 55%≈495m）。OOM 只杀自己的容器。
