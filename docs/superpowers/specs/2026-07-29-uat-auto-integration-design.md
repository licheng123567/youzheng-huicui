# UAT 自动联调环境设计

**日期：** 2026-07-29
**状态：** 已由用户批准
**基线版本：** `6a4e201f5c2a9d565ace6a57f7f944d2d2b7dca7`

## 1. 目标

在不改变当前已确认页面布局、不污染现有测试站数据的前提下，建立一套长期可访问、可重复生成数据、可自动部署和自动验收的 UAT 联调环境。

完成后应满足：

1. 当前代码完整备份在内网 Git：`root@47.108.81.205:/root/repos/youzheng-huicui.git`。
2. 内网 Git 的 `main` 更新后，UAT 自动检出、构建、部署和验证同一个 commit SHA。
3. UAT 使用独立的前端、后端、PostgreSQL 容器及独立数据卷，不连接现有 `huicui-db-1`。
4. UAT 自动生成平台、物业、服务商六种角色及完整合成业务数据，不复制真实业主、录音或生产数据。
5. 组织管理正确展示负责人账号名和完整手机号；成员管理正确展示账号名和完整手机号，并保持 BR-M1-04a 权限边界。
6. 部署默认保留验收过程中录入的数据；只有显式重置命令才重建 UAT 数据。
7. 每次部署执行快速硬门，完整 E2E 和契约测试可定时或按需执行；失败保留日志、截图与 trace。

## 2. 非目标

- 不把生产或当前测试站的个人信息复制到 UAT。
- 不改变现有 `https://cuiai.doorai.cn` 的容器、数据库、证书或业务数据。
- 不在本阶段接通真实短信、易保全、ASR、LLM、支付等外部供应商。
- 不重做已经确认的页面布局和导航。
- 不让自动部署脚本在失败时删除数据库或数据卷。

## 3. 总体架构

UAT 使用独立 Docker Compose project `huicui-uat`：

```text
浏览器 / Playwright
        |
        v
UAT Web :6090
  - 确认版 Vue 静态资源
  - /v1 反向代理
        |
        v
UAT Backend :9092（只绑定宿主回环或 Compose 内网）
        |
        v
UAT PostgreSQL（仅 Compose 内网）
  - 独立 volume: huicui-uat-pgdata
```

临时访问入口为 `http://47.108.81.205:6090`。当 `uat.cuiai.doorai.cn` 的 A 记录指向 `47.108.81.205` 后，由宝塔 nginx 终止 TLS 并反向代理到 UAT Web；UAT 内部端口和应用配置不改变。

服务器资源限制：

- UAT PostgreSQL：内存上限 256 MiB。
- UAT Backend：内存上限 600 MiB，JVM `MaxRAMPercentage` 约 55%。
- UAT Web：内存上限 128 MiB。
- 后端与前端按顺序构建，避免在 1.8 GiB 主机上同时制造内存峰值。

## 4. 数据模型与生命周期

### 4.1 固定合成数据

复用现有 `DevSeeder` 与 `db/seed` 作为唯一数据源，覆盖：

- 平台：SA 超管、SE 平台员工。
- 物业：PL 负责人、PC 协调员。
- 服务商：VL 负责人、CO 催收员。
- 多账号：同一合成手机号绑定 PC/CO 两个独立账号。
- 业务链：组织、项目、批次、案件、公海/私海、承诺、回款、结算、质检、存证占位、计费、审计和通知。

所有姓名、手机号、房号、金额和录音均为合成或占位数据。外部供应商功能保持关闭或使用现有 dev 降级路径。

### 4.2 部署时保留数据

普通部署只执行 Flyway 增量迁移。`DevSeeder` 必须保持幂等，只补齐缺少的固定场景，不删除验收人员新增的数据。

### 4.3 显式重置

`deploy/uat/reset.sh` 只允许操作 Compose project `huicui-uat` 和 volume `huicui-uat-pgdata`。脚本要求显式参数 `--confirm huicui-uat`，随后：

1. 停止 UAT 容器。
2. 删除 UAT 数据卷。
3. 重建数据库、执行迁移和 seed。
4. 运行 UAT 快速验证。

脚本中不得出现现有 project `huicui` 或 volume `huicui-pgdata` 的删除目标。

## 5. 组织与成员字段修正

### 5.1 组织接口

保留现有 `Org.ownerAccountId` 兼容字段，并新增：

- `ownerUsername`：负责人 `account.username`。
- `ownerPhone`：负责人 `account.phone` 的完整值。

`GET /orgs` 从 `org` 左连接负责人 `account`，由后端一次返回负责人 ID、账号名和手机号。页面不得再把 `ownerAccountId` 显示为“负责人账号”。

组织管理表格显示：组织类型、名称、负责人账号、负责人手机、状态和操作。

### 5.2 成员接口与页面

`GET /members` 已直接读取 `account.username` 和 `account.phone`，保持接口字段不变。新增自动化验证，确保页面展示的完整值与 API/固定 seed 一致。

权限边界保持不变：

- 平台成员管理只显示平台本组织的 SA/SE。
- 物业负责人只管理本物业的 PC。
- 服务商负责人只管理本服务商的 CO。
- 平台不得借列表入口跨组织创建、编辑或停用成员。

## 6. 自动部署

### 6.1 触发

内网裸仓库 `/root/repos/youzheng-huicui.git` 的 `post-receive` hook 仅监听 `refs/heads/main`。hook 不在 Git push 进程中执行长时间构建，而是记录最新 SHA 并启动异步部署 worker。

worker 使用 `flock` 串行化；连续 push 时只部署最新待处理 SHA，避免并发构建互相覆盖。

### 6.2 同 SHA 构建

部署目录建议：

- 源码检出：`/root/huicui-uat-src`
- 运行状态：`/var/lib/huicui-uat`
- 部署日志：`/var/log/huicui-uat`
- 服务器私密配置：`/root/huicui-uat.env`

worker 对指定 SHA 执行：

1. 从内网裸仓库强制检出该 SHA 到 UAT 源码目录。
2. 顺序构建 `huicui-uat-backend:<sha>` 和 `huicui-uat-web:<sha>`。
3. 运行静态配置校验和镜像自检。
4. 更新 UAT Compose 使用的镜像 SHA。
5. 启动或滚动更新 `db/backend/web`。
6. 运行快速硬门。
7. 成功后记录 `active-sha`；失败时记录日志和 `failed-sha`。

服务器 `.env` 生成独立的 UAT JWT/加密密钥，不复用现有站的生产密钥，并且不提交到 Git。

## 7. 失败处理与回退

- 检出或构建失败：不修改运行中的 Compose 配置，上一健康版本继续运行。
- 容器启动或健康检查失败：重新应用上一个 `active-sha` 的镜像并再次检查健康。
- 快速 Playwright 失败：部署状态标记为失败，保留页面、日志、截图和 trace 供排查，不宣称可验收。
- 数据库迁移不兼容：绝不自动删除数据库；由维护者决定修迁移或显式重置 UAT。
- 每次部署日志包含目标 SHA、开始/结束时间、构建结果、容器状态和验证结果。

## 8. 测试策略

### 8.1 每次部署快速硬门

必须验证：

1. PostgreSQL、Backend、Web 容器健康。
2. UAT 首页返回 200。
3. `/v1/actuator/health` 返回 UP。
4. 未登录 `/v1/me` 返回 401。
5. SA、SE、PL、PC、VL、CO 固定账号均能登录。
6. 组织管理的负责人账号和完整手机号与 seed/API 一致。
7. SA 成员管理只显示 SA/SE，且账号和完整手机号与 seed/API 一致。

### 8.2 完整测试

完整测试按需或定时运行：

- 现有 Playwright 全套用户故事。
- OpenAPI 契约 lint、路由覆盖和响应 schema 校验。
- 后端单元测试和集成测试。

失败产物保存在服务器日志目录或 CI artifact 中，至少包括 Playwright HTML 报告、截图、trace 和后端日志尾部。

## 9. 访问与安全

- UAT 只包含合成数据，禁止导入真实 PII、真实录音或现有数据库 dump。
- UAT PostgreSQL 不发布宿主端口；Backend 只供 UAT Web/宿主回环访问。
- 6090 为临时入口；具备 DNS 后优先切换到 HTTPS 子域名，并关闭或限制直接端口访问。
- 外部供应商密钥默认不配置；任何真实密钥接入需另行审批。
- UAT Compose project、容器名、网络和 volume 均使用 `huicui-uat` 前缀，脚本对目标名称进行 fail-closed 校验。

## 10. 计划新增或修改的文件

- `docs/api/openapi-core.yaml`：Org 新增 `ownerUsername`、`ownerPhone`。
- `backend/app/src/main/java/com/youzheng/huicui/web/dto/OrgSystemDtos.java`：扩展 Org DTO。
- `backend/app/src/main/java/com/youzheng/huicui/web/OrgSystemM1Controller.java`：负责人账号/手机号 join。
- `frontend/src/views/OrgMgmtView.vue`：正确列展示。
- `frontend/src/views/MembersView.vue`：保持布局，增加可测试的字段语义。
- `frontend/e2e/members.spec.ts`：组织/成员账号手机号端到端断言。
- `deploy/uat/docker-compose.uat.yml`：独立 UAT 栈。
- `deploy/uat/Dockerfile.web`、`deploy/uat/nginx.conf`：UAT 前端与 `/v1` 代理。
- `deploy/uat/deploy.sh`：同 SHA 构建、部署、验证和回退。
- `deploy/uat/verify.sh`：快速硬门。
- `deploy/uat/reset.sh`：显式重置。
- `deploy/uat/install-hook.sh`：安装内网 Git `post-receive` 触发器。
- `deploy/uat/.env.example`：无密钥的配置模板。
- `deploy/uat/README.md`：安装、访问、重置、日志和故障处理。

## 11. 验收标准

1. 内网 Git 的 `main` 与本地目标 SHA 一致，push 后能自动触发 UAT 部署。
2. UAT 三个服务与现有 `huicui` 栈的容器、网络和数据卷完全独立。
3. UAT 页面可通过临时端口访问；域名就绪后可无代码改动切换 HTTPS。
4. 数据库中存在固定角色和业务场景，页面能完成真实 API 联调。
5. 组织与成员页面的账号、完整手机号显示正确，并有自动化测试覆盖。
6. 普通部署不清空验收数据；显式 reset 后固定数据可重复恢复。
7. 快速硬门通过；失败路径能够保留上一健康版本或明确报告不可验收状态。
