# 回退手册

> 出事的时候没人有心情读长文档。这一页只回答一个问题：**现在能不能只回退应用，还是必须连数据库一起回？**

---

## 0. 先看清楚现在跑的是哪个构建

```bash
curl -s http://<host>:9091/v1/actuator/info
# {"app":{"version":"v1.10.0","revision":"f2d55cf..."}}
```

`version` 显示 `unknown` → **这个镜像不是走发布流水线出来的**（多半是谁在服务器上现场 `--build` 了）。
那意味着你手上没有可回退的锚点，先把这件事解决。

---

## 1. 决策树

```
新版本有没有往数据库里写「旧版本读不懂的数据」？
（新增枚举值、新列里的必需数据、改了旧列的语义）
│
├─ 没有 ──→ 【只回退应用】走第 2 节。数据库不动。三十秒的事。
│
└─ 有 ────→ 【连数据库一起回】走第 3 节。**会丢数据**，先想清楚丢的是什么。
```

判断不了的时候，**默认按「没有」处理，先只回退应用**。因为：

- 只回退应用是**可逆的**（不行再切回来）；
- 恢复数据库是**不可逆的**（备份点之后的数据全没）。

先做可逆的那个。

---

## 2. 只回退应用（常规路径）

```bash
cd deploy
# 1) 找到上一个可用的 tag
#    https://github.com/licheng123567/youzheng-huicui/pkgs/container/huicui-backend
vi .env                       # HUICUI_IMAGE_TAG=v1.9.0

# 2) 拉起旧镜像（数据库容器不动）
docker compose -f docker-compose.prod.yml up -d backend

# 3) 验证跑的确实是你要的那个
curl -s localhost:9091/v1/actuator/health/readiness      # {"status":"UP"}
curl -s localhost:9091/v1/actuator/info                  # revision 应等于该 tag 的 commit sha
```

### 启动日志里会有一条 WARN，那是**正常的**

```
WARN  o.f.core.internal.command.DbMigrate :
  Schema "public" has a version (927) that is newer than the latest available migration (926) !
```

旧 jar 里没有新版本引入的迁移，Flyway 把它们当作 **future migration** 并忽略
（`ignoreFutureMigrations` 默认 true）。**这是实测验证过的行为，不是碰运气**：

| 库里有、jar 里没有的迁移 | Flyway 判定 | 结果 |
|---|---|---|
| 版本号**高于** jar 最高版（回退时的情况） | future | ✅ 启动成功，仅 WARN |
| 版本号**低于** jar 最高版（迁移文件被误删） | missing | ❌ `Detected applied migration not resolved locally` 拒绝启动 |

所以：**回退不会被 Flyway 挡住。** 也正因为如此，**不要**为了「让回退更顺」去加
`spring.flyway.ignore-migration-patterns: "*:missing"` —— 它对回退毫无帮助，
却会把「有人删了迁移文件」这种真事故变成静默通过。

### 如果旧镜像起不来

看它是不是死在**代码读不懂新 schema** 上（比如新版本加了 `NOT NULL` 列而旧代码 INSERT 时不填）。
那说明这次发布违反了下面的「扩展-收缩」纪律，只能走第 3 节。

---

## 3. 连数据库一起回（最后手段，会丢数据）

**丢的是备份点之后的一切**：新收的回款、新登记的承诺、新上传的录音、业主刚点开的缴费链接。
执行前先想清楚这些数据能不能重建。

```bash
cd deploy
# 1) 停应用（不停数据库）
docker compose -f docker-compose.prod.yml stop backend

# 2) 找到发布前那次自动备份（文件名带着当时跑的镜像 tag）
ls -lh backup/
#   huicui-20260710T031500Z-v1.9.0.sql.gz     ← 发布 v1.10.0 之前的状态

# 3) 恢复（会覆盖当前库！先把当前库也 dump 一份留底）
./backup.sh                                   # 留底当前状态
docker compose -f docker-compose.prod.yml exec -T db \
  psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE $POSTGRES_DB;" -c "CREATE DATABASE $POSTGRES_DB;"
zcat backup/huicui-...-v1.9.0.sql.gz | \
  docker compose -f docker-compose.prod.yml exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"

# 4) 起旧镜像
vi .env                                       # HUICUI_IMAGE_TAG=v1.9.0
docker compose -f docker-compose.prod.yml up -d backend
```

> **不要用 `flyway repair` 绕过校验。** 它会改写 `flyway_schema_history` 的校验和，
> 让「schema 和代码到底对不对得上」这个问题永远失去答案。

---

## 4. 怎样让第 3 节永远不必发生：扩展-收缩

**每一次迁移都要让「旧代码跑在新 schema 上」成立。** 这样回退永远只是第 2 节的三十秒。

| 要做的事 | 这次发布 | 下下次发布（确认稳定后） |
|---|---|---|
| 加列 | `ADD COLUMN x TEXT` —— **可空，或带 DEFAULT** | 需要的话再 `SET NOT NULL` |
| 删列 | 什么都不做，代码先停止读写它 | `DROP COLUMN` |
| 改列名 | 加新列 + 双写，代码读新列 | 删旧列 |
| 改列类型 | 加新列 + 回填 + 双写 | 删旧列 |
| 加枚举值 | 后端能写，但**旧代码遇到新值必须不崩**（默认分支兜底） | — |

一次发布里**同时**加列和加 `NOT NULL`、或者删列，就等于放弃了回退能力。
Flyway 社区版**不支持 undo**（那是付费特性），schema 层面没有第二条退路。

---

## 5. 每次发布之前

```bash
cd deploy && ./backup.sh          # 备份文件名会带上当前运行的镜像 tag
```

这一步不是走过场：**没有那个备份，第 3 节就不存在。**

---

## 6. 关于历史 git tag

仓库里 `v1.0.0`–`v1.9.0` 这批 tag **全部创建于 2026-06-25 同一天**，是一次性补打的，
指向的 commit 未必对应当时真实部署的状态，**且它们没有对应的镜像**。

**它们只是历史里程碑，不是可回退的锚点。** 真正的发布点从 `release.yml` 上线之后的第一个 tag 算起 ——
判据很简单：**GHCR 里有没有那个 tag 的镜像。**
