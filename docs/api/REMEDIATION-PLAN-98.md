# 有证慧催 — 前后端契合度修复 主方案 (13 模块汇总)

> 校正结论：旧审计给的 70 分偏保守。**实读核验后真实现状 = 74 分**。后端与契约的核心闭环（结算双线、存证三方隔离、资金字段省略、主数据写端点、审计/录音/计费读端点）**绝大多数已落地且正确**，缺口高度集中在**前端未消费已就绪能力**与**少量净增端点**。全部落地后预计 **96 分**。

## 一、校正后现状表

| 维度 | 旧审计印象 | 实读后真实状态 | 缺口性质 |
|---|---|---|---|
| 结算 M9 | 部分缺 | 收佣/付佣双线+内催佣金链后端齐全 | FE 菜单误挡 PL/PC + 对账三列字段名漂移(batchCode/rate/commCents) + 内催缺穿透勾选 |
| 存证 M6 | 部分缺 | 3 端点+三方隔离正确 | FE 菜单挡只读 PL + 发起存证漏传 refIds(RECORDING/DELIVERY 必 422) |
| 资金双线脱敏 | 风险项 | 字段级整字段省略已正确(record 物理无字段) | 纯 FE 三视图用占位串泄露列名(应整列不渲染) |
| 公海 M3 | 部分缺 | 读端点+抢单/承接/指派已实装 | 缺单案再派/批量分配/释放记录(净增端点) |
| 案件作业台 | 部分缺 | 作业闭环+后端齐备 | 结案脱敏视图/QUOTA_BLOCKED补解析/标记码SSOT/AI对话分离/联系人主号 五处 FE 缺口 |
| 项目维护 M2-A | 缺界面 | 四写端点+schema 全就绪 | 纯 FE 只读, 缺新建/编辑/协调员/减免阶梯 UI |
| 通话记录列表 | 缺 | listRecordings 全就绪 | 纯净增 FE 页(无菜单/路由/列表View) |
| 案件筛选 | 缺 | 仅 batchId+status 后端支持 | 缺 projectId/关键字 q(契约+后端扩参)+FE 筛选栏 |
| 计费短信 | 缺 | /sms-records+/billing/usage 后端就绪 | 纯 FE 净增(未消费 sms-records, 无月→日下钻) |
| 审计日志 | 缺 | listAuditLog 就绪 | 纯净增 FE 页 + M-07b 被代方可见 scope 语义缺口 |
| 设置全域 | 部分缺 | 5 域读写后端就绪 | FE 仅 ROTATION 可编辑, 缺 TIMERS/MARK_CODES/CLOSE_REASONS/SMS |
| 成员组织 | 基本通 | 功能闭环+scope 正确 | 仅角色文案漂移(PC/CO 错标)纯 S 工 |
| 批次协调员/同步 | 部分缺 | 写端点+source 推导就绪 | FE 三块 UI 缺 + BC-05 INHERIT 硬编码 + **BC-04 覆盖同步 0 实现(契约+后端皆无)** |

**核验证据(实读)**: AppLayout.vue:29/32 菜单门控、SettlementView.vue:135(`batchCode` vs ReconRollup schema `batch`/`dueCents`/`commRate` @line2167)、ProjectsView:34/BatchesView:119/BatchDetailView:14 占位串、CaseDetailView QUOTA_BLOCKED 仅 :250 FAILED→reprocess、MARK_CODES:29 硬编码、CallRecordsView/AuditLogView 文件不存在、router 无 audit/recordings 列表路由、BatchesM2Controller:163-164 `INHERIT` 占位 — **全部 stillLive=true**。

## 二、分模块改动(摘要)

- **纯 FE 零后端改动**(最低风险): 资金脱敏(整列条件渲染替占位)、通话记录列表(新建 CallRecordsView)、审计日志(新建 AuditLogView)、计费短信(消费 /sms-records+月日下钻)、项目维护(新建/编辑/协调员/减免 UI)、成员文案修正、设置全域(4 域编辑器)。
- **FE+契约扩参**: 案件筛选(projectId/q)、结算对账字段名修正(M-10 是纯 FE 改 `batchCode→batch`/`commCents→dueCents`)。
- **FE+净增端点**: 结算内催穿透(/co-commissions/{id}/batches)、公海(redispatch/assign-batch/release-records)、CaseDetail 标记码(CaseDetail.markCodes)。
- **最高风险净增**: BatchCoord BC-04 覆盖同步(drift 字段+sync 端点+reduce_tier 表基线), 单独成批。

## 三、契约改动集(去重合并)

见结构化 `contractChangeSet`。核心三类：
1. **读端点范式固化**(无 codegen 影响): 结算/存证/审计/通话/案件读端点保持无 x-permission、靠 x-data-scope=range 裁剪；菜单门控不得用写权限。驱动 AppLayout 多处放宽。
2. **字段级 redaction 已落地**: 资金双线整字段省略 + Case.redacted + 列表脱敏均正确，仅补约定注释；前端据字段缺失整列不渲染。
3. **新增 schema/端点**(须 gen:api): CaseDetail.markCodes、co-commissions 下钻、cases 扩参(projectId/q)、M3 三端点、BatchCoord drift+sync、PaymentRequest/CoPayDoc documentUrl/sealed 占位。

**codegen 纪律**: schema.d.ts 是 openapi-typescript 单点产物，所有契约改动合并进 openapi-core.yaml 后**由整合者跑一次 gen:api**，禁止各模块各自 gen。契约无 CI 断言保证 x-permission ↔ 后端 @RequirePermission 一致，新端点须手动核对注解。

## 四、实施批次顺序(按共享文件抢占)

- **批1 (parallelSafe)**: 资金脱敏 / 通话记录列表 / 审计日志 / 计费短信 / 成员文案 / 项目维护。各改独占视图或纯新建文件；MENU/router 追加行交由整合者集中插入。后端零改动。
- **批2 (串行)**: 结算 / 存证 / 案件作业台 / 案件筛选。抢占点 **CaseDetailView.vue**(CaseDetail+Evidence refIds)、**AppLayout.vue**(H-01+H-02)、**openapi-core.yaml**(Settlement/CaseDetail/CasesFilter 共改后统一 gen:api 一次)。
- **批3 (串行)**: 公海(三新端点) / BatchCoord 批1(BC-01/02/03/05, 对接已有端点+reduceMode 真值化)。再次触碰 openapi-core.yaml+schema.d.ts，须晚于批2；BatchDetailView 已被批1(H-03)改过故必须晚于批1。
- **批4 (parallelSafe)**: 设置全域(独占 SettingsView) / BatchCoord 批2(BC-04 覆盖同步净增, 最高风险, 需产品确认 BC-06) / M-07b(可选) / 全部 e2e spec 编写。

**e2e 前置**: V900__dev_seed.sql 仅有 SA 账号，须先补 PL/PC/VL/CO 种子账号，否则批4 大量 spec 无法登录。

## 五、Playwright E2E 计划(按 spec 文件)

| spec | 关键场景 | 验收锚 |
|---|---|---|
| funds-redaction | VL/CO 不见收佣列、PL/PC 不见付佣列(整列不渲染非占位)、SA 双线全含 | BR-M1-06/BR-M9-11 |
| settlement-readonly + recon-fields | PL/PC 见结算菜单只读 IN 线、对账三列有值非"—"; SA IN/OUT 切换字段正确 | US-M9-06/BR-M9-12a/M-10 |
| co-commission-drilldown/self | VL 人→批次→明细勾选生成佣金单+详情快照; CO 仅"我的佣金"只读 | US-M9-09/10/M-05 |
| evidence-menu-gating/create-refids/verify/failed | PL 只读+下载、PC 发起须选 READY 录音/SIGNED 文书、public 核验不泄越权、FAILED danger | 矩阵§7/BR-M6/M6-FE-REFIDS |
| case-detail-redaction/recording-parse/mark-result/ai-review-dialogue/contacts-followup | 结案脱敏统计视图、QUOTA_BLOCKED 补解析、标记码来自服务端、AI 对话气泡+segmentTs、主号设置 | US-M8/M5/M4 |
| project-maint | 新建/编辑/减免阶梯(元↔分)/协调员/必填校验/CO 无入口 | US-M2-01/02 |
| call-records / cases-filter | 通话列表过滤跳详情; 项目/批次/状态/关键字筛选+脱敏不被关键字命中 | US-M4-12/P-DATA-08/BR-M8-09 |
| sea-redispatch/assign-batch/release-records | 单案再派护栏①、批量分配超额度被拒明细、释放记录可见 | US-M3-02/05/BR-M3-27 |
| audit-log / billing-sms / settings / members | 代操作标签+快照、短信失败汇总+月日下钻、5 域编辑+矩阵导出、角色文案 | P-ORG-08/US-M9-04/US-M3-11/US-M1-04 |
| batch-coordinators/reduce-override/playbook-adopt/sync-drift | 批次协调员、减免覆盖恢复继承、手册采纳、drift 一键同步(依赖 BC-04) | US-M2-02/BR-M2-18a/18b |

## 六、打分与到 98% 的差距

- **当前 74 / 100**: 后端+契约骨架健全，扣分全在 FE 未接线 + 少量净增端点。
- **本方案全落地后 96 / 100**。
- **到 98% 仍差的 2 分**(本轮刻意不做/需外部确认):
  1. documentUrl/sealed 单据下载+电子签章为**占位 TBD**，无真实文件通道/CA 签章(BR-M9-12d/16)。
  2. BC-04 覆盖同步的 drift 基线表语义 + BC-06 减免读权限口径需**产品确认**，未确认前不冻结。
  3. 证据包打包下载、短信/权限矩阵服务端导出为**评审后可选**，未纳入硬验收。
  4. M-07b 被代方可见(BR-M1-15)涉及 audit_log SQL 改 OR proxy_for，建议拆单独确认，未纳入主线。
- 补齐上述 4 项(主要是产品确认+占位转真实现)可达 98%+。