# 有证慧催 · v1.9.0 全景

> 物业费催收 SaaS 撮合平台。契约优先（OpenAPI 为单一事实源），后端 Spring Boot + 前端 Vue3，三轮安全审计 + 四方适配审计闭环，两层防护网（接口契约 + 真屏 E2E）。
> 契约 **v1.3.1 · 126 op**；前端 **21 屏**；后端 **38 控制器**；里程碑 **v1.0.0 → v1.9.0**。

---

## 1. 架构

```
┌─ 前端 Vue3 + Vite + ElementPlus + Pinia ───────────────┐   ┌─ H5 业主页(免登录) ─┐
│  openapi-fetch 强类型客户端(类型由契约生成)            │   │  /pay/:token        │
│  21 屏 + 路由守卫 + 角色化菜单/按钮门控                 │   └─────────────────────┘
└───────────────────────┬────────────────────────────────┘
                vite 代理 /v1 → :9091
┌───────────────────────┴────────────────────────────────────────────────────────┐
│ 后端 Spring Boot 3.3 / Java 21 / JdbcTemplate                                    │
│ 横切层: JwtAuthFilter(public放行) · @RequirePermission+PermissionInterceptor      │
│         GlobalExceptionHandler(统一 Error{code,message,traceId}) · DataScope      │
│         IdempotencyInterceptor(2xx-only) · TraceIdFilter · Permissions.of(role)    │
│ 业务: M1组织权限 M2主数据 M3公海流转(+自动到期调度器) M4催收作业 M5录音AI话术       │
│       M6存证 M7业主H5 M8结案 M9结算对账 M10报表 + 工作台/消息/搜索/定时器           │
│ ExpiryScheduler @Scheduled: 公海自动到期(CFG-T2/TC)                               │
└───────────────────────┬────────────────────────────────────────────────────────┘
              PostgreSQL 16 + pgvector (Flyway V1-V7 + V900-V911)
```

**契约优先范式（贯穿全程）**：`docs/api/openapi-core.yaml` 是 SSOT → 前端类型 `npm run gen:api` 生成 → 后端实现 → schemathesis 拿契约打后端 → 真屏 Playwright。任何漂移在 CI 红。

---

## 2. 契约（126 op · v1.3.1）

- SSOT：`docs/api/openapi-core.yaml`（OpenAPI 3.1）；ERD：`docs/api/ERD.md`；PRD：`docs/prd/00-10`。
- 口径：金额 `*_cents`(分·integer)；比率 `Rate` 分数 0-1(展示 ×100)；角色 `SA/SE/PL/PC/VL/CO`。
- 扩展字段：`x-permission`(权限点) · `x-data-scope`(own-org/range/platform/case-holder/case-actor/public) · `x-settlement-side-rule`。
- 模块映射：auth/org-member(M1) · projects/batches/cases(M2) · dispatch(M3) · collection/legal(M4) · ai(M5) · evidence(M6) · owner-h5(M7) · settlement/commission/billing(M9) · qc(M5) · reports(M10) · workbench/notification(覆盖补点)。

契约从冻结的 117 op 经 9 个里程碑扩展到 126 op（全部 additive 非破坏）。

---

## 3. 运行（本地）

```bash
# 1) PostgreSQL(pgvector)·端口 5455
docker run -d --name huicui-dev -e POSTGRES_PASSWORD=test -e POSTGRES_DB=huicui \
  -p 5455:5432 pgvector/pgvector:pg16

# 2) 后端·端口 9091(/v1)·Flyway 自动迁移 + DevSeeder 种子
cd backend/app && mvn spring-boot:run        # 就绪标志: GET /v1/me → 401

# 3) 前端·端口 5173·代理 /v1 → 9091
cd frontend && npm install && npm run dev     # http://localhost:5173

# 验证
node frontend/scripts/smoke.mjs               # 全端点·全模块 E2E(需先起 PG+后端)
cd frontend && npm run e2e                     # Playwright 真屏(自启 vite)
```

**dev 账号**（口令均 `Admin@123`）：

| 账号 | 角色 | 组织 | 说明 |
|---|---|---|---|
| admin | SA | 平台 | 平台超管 |
| cuihu_pl / cuihu_pc | PL / PC | 翠湖物业 | 物业负责人 / 协调员 |
| yang_pl | PL | 阳光物业 | |
| jx_vl | VL | 捷信催收 | 服务商负责人 |
| jx_co1 / jx_co2 | CO | 捷信催收 | 催收员（jx_co1 持有 M3-S3-01）|
| duo_pc / duo_co | PC / CO | 翠湖 / 捷信 | **一号多账号**：同手机 `13900009000` |

短信登录 dev 验证码固定 `000000`（生产换真实通道随机码）。

---

## 4. 演示动线（角色化）

1. **登录（M1）**：admin 口令直登；`13900009000` 短信(000000) → 一号多账号选择；duo_pc 口令 → 多账号选择。
2. **工作台（BR-M4-20）**：CO/PC 登录 → 今日驾驶舱（承诺到期/临近释放/工单回执待办 + KPI 可点筛）；VL/平台 → 仪表盘（含 T2/T1 预警）。
3. **主数据（M2）**：项目/批次列表（读入口按数据范围）；批次导入（成功/跳过/错误明细）。
4. **派单（M3）**：批次派单对话框 → 服务商指标决策辅助（在催/人均持仓/近 30 天回款率）+ 整批/勾选案件/件数拆派；公海抢单/承接/指派（催收员余量条 + 推荐）；公海竞争态/来源徽标/**距退回 T2 倒计时** + 事件日志。
5. **催收作业台（M4）**：进案件 → 概览/通话(录音上传·AI 复盘·结果标记)/承诺工单(分期·工单转出→**消息推协调员**)/回款减免/法务存证/时间线；缴费链接重发作废；建议法务轻标。
6. **结算（M9）**：对账汇总 → 勾选明细生成支付申请单 → 发送 → 完成(凭证) → 撤销；side×role×generatorOrg 门控；内催佣金设比例/生成单/确认支付。
7. **质检/报表/存证/计费/设置/成员（M5/M10/M6/M9/M1）**：风险处置升级复核；报表维度钻取(项目/批次/月)+月趋势；存证验真；计费用量充值；权限矩阵+AI 配置+话术库；成员授权(角色按组织过滤+权限子集+数据范围)。
8. **消息中心 / 全局搜索 / 个人中心**：互推通知红点；案件/业主搜索（数据范围裁剪+脱敏）；自助改密。
9. **业主 H5（M7）**：`/pay/:token` 免登录看缴费单（金额/减免/期数/线下渠道）。
10. **定时器（自动）**：私海无跟进超 CFG-TC 自动释放、服务商公海滞留超 CFG-T2 自动退回平台公海（调度器执行 + 留痕 + 临近预警）。

---

## 5. 质量门（5 门 · 两层防护网）

CI 两个 workflow（`.github/workflows/`）：

| 门 | 工具 | 把守 |
|---|---|---|
| Gate0a contract-lint | Spectral | 契约自身规范（每端点 summary/x-permission/x-data-scope）|
| Gate0b breaking-change | oasdiff | PR 破坏性变更检测（删字段/改类型）|
| Gate0c route-coverage | route_coverage.py | 契约每 op 必有后端 handler（126/127）|
| Gate1 | schemathesis | **后端 honor 契约**：拿契约打全部 op，响应违约即红 |
| e2e | Playwright | **前端真屏可用**：6 spec / 24 用例，真实浏览器跑用户故事 |

**双网**：gate1 保接口层正确性（~6600 用例 0fail）+ e2e 保前端真屏（登录/多账号/11 屏导航/作业台/结算/工作台/消息/搜索/个人中心）。本地另有 `smoke.mjs` 全端点 E2E。

---

## 6. 里程碑轨迹

| 阶段 | tag | 内容 |
|---|---|---|
| 地基 | v1.0.x | 契约冻结 + 横切层 + M1-M10 行走骨架 |
| 模块 | v1.1-v1.2.x | 各模块切片实现，schemathesis 117/117 |
| 审计整改 | v1.2.6-v1.3.9 | 外部审计 P0-P4 + codex 三轮(case-actor越权/sms重放/ticket.handle/枚举/SSOT) |
| 四方适配 | v1.4.0 | UI/US/PRD/契约 H-01..H-08/M/L 适配缺陷 |
| 真屏 E2E | v1.5.0 | Playwright + CI |
| 覆盖扩展 | v1.6-v1.9 | 契约扩展 P0(工作台/派单决策) P1(消息/竞争态/事件) P2(个人中心/搜索) + 定时器闭合 |

审计提出的全部 BLOCKER/HIGH/MEDIUM + 8 类覆盖缺口（工作台/派单容量/推荐/消息中心/公海竞争态/事件日志/个人中心/全局搜索/T1-T2 预警）**已全部闭环**。

---

## 7. 剩余集成项（部署期 · 需外部供应商凭据，非代码缺口）

| 项 | 现状 | 落地条件 |
|---|---|---|
| 短信通道 | sms-code dev 固定码骨架（TTL+一次性已做）| 接真实短信服务商随机码下发 |
| 文件/电子签章 | documentUrl/sealed 字段占位 | 接签章/文件存储服务 |
| 线上支付 | H5 缴费单只读（PRD 标后续）| 接支付通道 |
| 实时 presence | viewerCount「正在查看N人」降级返 0 | 接 WebSocket/SSE 通道 |
| 事件日志实时推送 | /sea/events 轮询近期 | 同上(SSE) |

这些均需真实供应商凭据/契约，属部署期集成；代码侧已留字段/降级位。

---

## 附：关键路径

- 契约：`docs/api/openapi-core.yaml` · 草案：`docs/api/CONTRACT-EXPANSION-P0/P1/P2-DRAFT.md` · `CFG-TIMERS-DRAFT.md`
- 后端：`backend/app/src/main/java/com/youzheng/huicui/`（web/ 控制器 · dispatch/ 状态机+调度器 · security/ common/ error/ 横切）
- 迁移：`backend/db/migration/V1-V7`(filesystem) + `resources/db/migration/V900-V911`(classpath·种子)
- 前端：`frontend/src/`（views/ 21 屏 · stores/auth · api/ 契约客户端）· E2E：`frontend/e2e/*.spec.ts`
- 质量门：`.github/workflows/api-contract.yml`(Gate0/1) · `e2e.yml`(Playwright) · `backend/scripts/route_coverage.py`
