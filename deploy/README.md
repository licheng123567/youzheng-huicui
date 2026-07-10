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

所有 24 个迁移都打进了 jar，不再依赖任何相对路径。

---

## 一、首次部署

```bash
# 1. 准备环境变量
cp deploy/.env.example deploy/.env
vi deploy/.env          # 至少填 HUICUI_IMAGE_TAG、POSTGRES_PASSWORD、HUICUI_JWT_SECRET

# 生成强密钥
openssl rand -base64 48

# 可用的镜像 tag：
#   https://github.com/licheng123567/youzheng-huicui/pkgs/container/huicui-backend

# 2. 起库 + 起后端（后端会自动跑 Flyway 建表）
#    注意是 up -d，不带 --build：生产用 GHCR 上的不可变镜像，不现场构建。
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d

# 3. 验证
curl -fsS http://localhost:9091/v1/actuator/health/readiness    # {"status":"UP"}
curl -o /dev/null -w '%{http_code}\n' http://localhost:9091/v1/me   # 401 = 鉴权链路正常
```

启动日志里应能看到：

```
Successfully applied 24 migrations
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
| Flyway `Validate failed: checksum mismatch` | 有人改了已应用的迁移 | 查清楚原因，勿直接 repair |

---

## 六、尚未纳入（已知缺口）

- **反向代理 / TLS**：当前 compose 直接暴露 9091。生产应在前面放 nginx/Caddy 终结 TLS，
  并把前端静态资源与 `/v1` 反代到同源（后端未配置 CORS，隐含同源部署假设）。
- **对象存储**：录音/附件仍是 PG `bytea`。
- **监控**：仅有 `/actuator/health`，无指标采集与告警。
- **幂等键与登录票据仍是单机内存实现**：多实例部署前必须换 Redis。
- **前端与 App 尚未镜像化**：本次只把后端做成了不可变镜像。前端是静态资源（重建代价低），
  App 走侧载分发。真要做「整站回退」，前端也得有对应的产物版本。
