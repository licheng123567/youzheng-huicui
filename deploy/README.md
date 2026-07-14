# 有证慧催 · 部署手册

## 设计前提：fail-closed

后端**不带任何可用于生产的默认凭据**。少配一个必填项，它会在启动时被 `ProdEnvironmentGuard` 拒绝，
并打印可操作的中文理由，而不是悄悄用 dev 口径跑起来。

> 这条是踩过坑才立的。改造前：默认 profile 是 `dev`，忘设环境变量就会用 dev 的 JWT 密钥 + DevSeeder 种子数据跑生产；
> 而 `application-prod.yml` 把 Flyway 指向 `filesystem:../db/migration` 且 `fail-on-missing-locations: false`，
> **打 jar 部署时那个相对路径不存在，核心 schema（V1-V7 / V917-V926）会被静默跳过**——库是空的，应用却"正常"启动。

现在：

| 场景 | profile | 迁移来源 | 结果 |
|---|---|---|---|
| `mvn spring-boot:run`（本地/CI） | `dev`（由 spring-boot-maven-plugin 注入） | `classpath:db/migration` + `classpath:db/seed` | 起得来，带种子数据 |
| `java -jar app.jar`（生产） | `prod`（基础 yml 默认值） | `classpath:db/migration`（不含种子） | **配不全就起不来** |

所有 35 个迁移都打进了 jar，不再依赖任何相对路径。

---

## 一、首次部署

> **生产库是空的**：没有公开注册、没有种子账号（`db/seed` 不进 prod，`DevSeeder` 只在 dev 跑）。
> 不做第 3 步的**初始管理员引导**，迁移会全绿、健康检查会全绿，然后**没有任何人能登录**。

### 前置：域名与证书

TLS 不是可选项——过网线的是业主真实姓名/电话/住址和通话录音。

```bash
# 1. 把域名 A 记录解析到本机公网 IP（Let's Encrypt 签证书要能回访）
# 2. 首次签发证书（webroot 模式，需要 80 端口临时可达）
mkdir -p deploy/certbot/conf deploy/certbot/www
docker run --rm \
  -v "$PWD/deploy/certbot/conf:/etc/letsencrypt" \
  -v "$PWD/deploy/certbot/www:/var/www/certbot" \
  -p 80:80 certbot/certbot certonly --standalone \
  -d your-domain.example.com --agree-tos -m you@example.com --non-interactive

# 3. 续证（加进 crontab，证书 90 天到期）
#    0 3 * * 1 cd /path/to/repo && docker run --rm -v "$PWD/deploy/certbot/conf:/etc/letsencrypt" \
#      -v "$PWD/deploy/certbot/www:/var/www/certbot" certbot/certbot renew --webroot -w /var/www/certbot \
#      && docker compose -f deploy/docker-compose.prod.yml exec web nginx -s reload
```

### 部署

```bash
# 1. 环境变量
cp deploy/.env.example deploy/.env
vi deploy/.env
#    必填：HUICUI_IMAGE_TAG / POSTGRES_PASSWORD / HUICUI_JWT_SECRET / HUICUI_CRYPTO_KEY / HUICUI_DOMAIN
#    生成密钥：openssl rand -base64 48   （CRYPTO_KEY 用 openssl rand -base64 32）
#
#    ⚠️ HUICUI_CRYPTO_KEY 一旦启用就不能再换：换了之后已落库的三方密钥密文全部解不开。

# 2. 前端产物（nginx 静态站从这里读）
npm --prefix frontend ci && npm --prefix frontend run build
mkdir -p deploy/frontend-dist && cp -r frontend/dist/* deploy/frontend-dist/

# 3. 初始管理员引导 —— 只在首次部署填这三项
#    引导只在 account 表为空时执行；已有账号则自动跳过（重复启动安全）。
#    口令要求 ≥12 位，太短会拒绝启动（宁可起不来，也不要一个弱口令超管）。
cat >> deploy/.env <<EOF
HUICUI_BOOTSTRAP_ADMIN_USERNAME=admin
HUICUI_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 18)
HUICUI_BOOTSTRAP_ADMIN_PHONE=13800000000
EOF
grep HUICUI_BOOTSTRAP_ADMIN_PASSWORD deploy/.env    # 记下它，马上要用来首次登录

# 4. 起全栈（db + backend + web/nginx）
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d

# 5. 验证
curl -fsS https://your-domain.example.com/actuator/health      # {"status":"UP"}
curl -o /dev/null -w '%{http_code}\n' https://your-domain.example.com/v1/me   # 401 = 鉴权链路正常
```

### 首次登录（必做，且要立刻做完）

用第 3 步那个口令登录网页端。后端会**强制改密**（`must_change_password=TRUE`，
未改密前除了 `GET /me` 和 `POST /me/password` 之外一切业务端点都是 403）。

改完密码后：

```bash
# .env 里那个引导口令此刻已经作废，把三行删掉，别让它留在 .env 和 shell history 里
sed -i '/HUICUI_BOOTSTRAP_ADMIN_/d' deploy/.env
```

启动日志里应能看到：

```
==================== 初始管理员已创建 ====================
[Bootstrap] 平台组织「有证平台」+ 超管账号「admin」（id=1）已建好。
Successfully applied 35 migrations
[ProdGuard] ✅ 生产启动自检通过（数据源/JWT 已注入；短信=…，存证=…）
```

若短信/存证未启用，会看到显著 WARN——那是刻意的，见下节。

---

## 二、必填与可选

### 必填（缺则拒绝启动）

| 变量 | 说明 |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | 数据库；口令不得为 `test` |
| `HUICUI_JWT_SECRET` | ≥32 字节（HS256），且不得等于 dev 内置串。`openssl rand -base64 48` |
| `HUICUI_CRYPTO_KEY` | 三方密钥（存证/短信/ASR/LLM）AES-256-GCM 落库的主密钥。`openssl rand -base64 32`。**不配则后台存不了任何三方密钥（恒 409），真 AI 永远不可用**。一旦启用不可更换——换了已落库的密文全解不开 |
| `HUICUI_DOMAIN` | nginx 的 `server_name` 与证书路径。必须是已解析到本机的真实域名 |

### 仅首次部署（引导完就删）

| 变量 | 说明 |
|---|---|
| `HUICUI_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` / `_PHONE` | 空库时创建第一个平台超管。**不填 = 部署完没人能登录**。口令 ≥12 位；建出来的账号 `must_change_password=TRUE`，首登强制改密后这个口令即作废。只在 `account` 表为空时生效（重复启动幂等，不会覆盖已有账号） |

### 可选，但**不配就等于功能不可用**

| 变量组 | 不配的后果 |
|---|---|
| `HUICUI_SMS_*` + `HUICUI_PUBLIC_BASE` | 验证码与缴费链接触达不了业主；`/auth/sms-code` 返回 502 |
| `HUICUI_EBQ_*` | 发起存证只落占位记录，**出的证没有法律效力** |

启用时会被额外校验（都会拒绝启动）：

- 启用短信 → `HUICUI_PUBLIC_BASE` 不能是 `localhost`（否则业主收到打不开的缴费链接）
- 启用短信 → `SECRET_NAME` / `SECRET_KEY` / `SMS_BASE` 三项必填
- 启用存证 → `APPKEY` / `SECRET` 必填，且 `HUICUI_EBQ_URL` 不能还指向 `sandbox`

---

## 三、升级与回退

升级 = 改一行 `.env`，回退也是。**发布之前先备份**，否则 [ROLLBACK.md](./ROLLBACK.md) 的第 3 节对你不存在。

```bash
# 1. 先备份（文件名会自动带上当前正在跑的镜像 tag）
deploy/backup.sh

# 2. 换镜像 tag
vi deploy/.env          # HUICUI_IMAGE_TAG=v1.10.0

# 3. 只重启后端，数据库不动
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d backend

# 4. 验证跑的确实是那个构建
curl -s localhost:9091/v1/actuator/info       # {"app":{"version":"v1.10.0","revision":"..."}}
```

**回退就是把第 2 步的 tag 改回上一个版本再跑第 3 步。** 完整决策树（什么时候必须连数据库一起回、
为什么不能用 `flyway repair`、扩展-收缩纪律）见 **[ROLLBACK.md](./ROLLBACK.md)**。

Flyway 只会应用新增迁移。`validate-on-migrate: true` 已开启：若已应用脚本的校验和被改动，启动会失败——
这是好事，说明有人改了历史迁移，**不要**用 `flyway repair` 绕过，先搞清楚为什么。

`out-of-order: false` 也已开启：新迁移的版本号必须大于库里最大的。

> 回退到旧镜像时，启动日志会出现一条 `Schema "public" has a version (…) that is newer than the
> latest available migration (…)` 的 WARN。**那是正常的**：Flyway 把版本更高的迁移当作 future
> migration 并忽略（已实测）。详见 ROLLBACK.md 第 2 节。

---

## 四、备份

```bash
deploy/backup.sh                       # 立即备份一次，落到 deploy/backup/
# 每天 03:00 自动备份：
crontab -e
0 3 * * *  cd /opt/huicui && deploy/backup.sh >> /var/log/huicui-backup.log 2>&1
```

脚本会校验 dump 完整性（大小 + `dump complete` 标记），残缺的备份直接报错——
比"以为有备份"安全。默认保留 14 天（`BACKUP_RETAIN_DAYS`）。

> ⚠️ 录音与附件目前以 `bytea` 存在库里（V921/V923），dump 体积会随使用快速增长。
> 迁到对象存储之前，备份窗口与恢复时长都要按这个前提规划。

**恢复演练务必在非生产库上做**：

```bash
zcat deploy/backup/huicui-XXXX.sql.gz | docker compose -f deploy/docker-compose.prod.yml \
  --env-file deploy/.env exec -T db psql -U "$POSTGRES_USER" -d restore_test
```

---

## 五、常见启动失败

| 报错 | 原因 | 处置 |
|---|---|---|
| `[ProdGuard] SPRING_DATASOURCE_URL 未配置` | 没加载到 `.env` | compose 命令加 `--env-file deploy/.env` |
| `[ProdGuard] HUICUI_JWT_SECRET 强度不足` | 密钥 <32 字节 | `openssl rand -base64 48` |
| `[ProdGuard] 数据源仍指向 dev 默认库` | 复制了 dev 配置 | 改 `SPRING_DATASOURCE_URL` |
| `[ProdGuard] 启用短信但 HUICUI_PUBLIC_BASE 仍是 localhost` | 忘改域名 | 填真实可达域名 |
| `[ProdGuard] HUICUI_CRYPTO_KEY 未配置` | 少了加密主密钥 | `openssl rand -base64 32`，填进 `.env` |
| `[Bootstrap] HUICUI_BOOTSTRAP_ADMIN_PASSWORD 太短` | 引导口令 <12 位 | `openssl rand -base64 18` |
| 登录页没有任何凭据能进 | 空库未做引导 | 见「一、首次部署」第 3 步；或已有账号但忘了口令，走成员管理重置 |
| 录音上传 422 `MaxUploadSizeExceededException` | multipart 限额被调小了 | 检查 `HUICUI_MAX_FILE_SIZE` 与 `nginx.conf` 的 `client_max_body_size` 是否一致 |
| Flyway `Validate failed: checksum mismatch` | 有人改了已应用的迁移 | 查清楚原因，勿直接 repair |

---

## 六、App 安装包的托管位置

网页端每个角色的顶栏都有「App」入口（催收员看到的是安装指引，管理角色看到的是转发提示）。
它默认去同源的 **`/app/huicui.apk`** 取安装包 —— 也就是说：

**把 APK 放到前端静态资源目录下的 `app/huicui.apk`。**

```
/var/www/huicui/
├── index.html          # 前端构建产物
├── assets/
└── app/
    └── huicui.apk      # ← 放这里
```

APK 从 CI 的 `app-android` workflow 产出（artifact `huicui-app-debug`）。
**注意 CI 构建的 APK 里烧的后端地址是 `10.0.2.2:9091`（Android 模拟器专用）**，
真机用的包必须在构建时通过 `app-android/local.properties` 的 `huicui.devHost`
或 release buildType 指定真实域名。

需要放到别处（对象存储、内网文件服务器）时，前端构建期设环境变量覆盖：

```bash
VITE_APP_DOWNLOAD_URL=https://cdn.example.com/huicui-v1.0.apk npm run build
```

App **不上应用商店**（`MANAGE_EXTERNAL_STORAGE` + `READ_CALL_LOG` 在通话录音场景基本必被拒审，
见 PRD 11-移动App §4.2），只走企业侧载，所以必须自己托管这个文件。

---

## 七、尚未纳入（已知缺口）

- **对象存储**：录音/附件仍是 PG `bytea`。
- **监控**：仅有 `/actuator/health`，无指标采集与告警。容器 `unhealthy` 不会自动重启。
- **备份未加密、未异地**：`deploy/backup/` 与 PG 数据在同一块盘上。而 dump 里是全量业主 PII + 录音。
- **幂等键与登录票据仍是单机内存实现**：多实例部署前必须换 Redis。
- **停权/离职账号的 JWT 最长 24h 后才失效**：`JwtAuthFilter` 不复核 `account.status`。
- **数据无留存期限、无删除/注销路径**：录音、转写文本、手机号进库即永久。
- **前端与 App 尚未镜像化**：本次只把后端做成了不可变镜像。前端是静态资源（重建代价低），
  App 走侧载分发。真要做「整站回退」，前端也得有对应的产物版本。
