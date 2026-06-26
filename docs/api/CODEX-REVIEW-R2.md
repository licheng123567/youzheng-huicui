结论：上轮 6 条里 5 条闭合、1 条部分闭合。但案件级 `provider_id` 重构仍有阻断级归属语义问题，本轮不可上线。未改文件，未跑测试。

**闭合确认**

| # | 结论 | 证据 |
|---|---|---|
| 1 单案再派污染整批 | 已闭合 | `redispatchCase` 只 `setCaseProvider(caseId, providerId)`，未改 `batch.provider_id`；holdcap 用 `existing + 1`：`backend/.../DispatchM3Controller.java:128-145` |
| 2 退回原服务商护栏 | 已闭合 | `lastReturnedProvider` 改读 `before_snap ->> 'providerId'`：`DispatchM3Controller.java:462-475`；`case.return` 审计 before 来自退回前 `snap`：`HolderM3Controller.java:164-182`；审计 JSON 写 `providerId`：`CaseStateService.java:306-317` |
| 3 释放记录历史 scope | 已闭合 | `listReleaseRecords` 按事件时 `before_snap->>'providerId'` 过滤，不再按当前 batch：`ProvidersController.java:78-88` |
| 4 批量分配并发超额 | 部分 | `assignCasesBatch` 对 collector `FOR UPDATE`、锁内算余量、超额进 rejected：`DispatchM3Controller.java:173-179,222-225,257-260`；但单案 `assignCase/claimCase` 未拿同一 collector 锁，仍可与批量并发穿透 holdcap：`HolderM3Controller.java:76-86`、`CaseStateService.java:224` |
| 5 前端三方一致 | 已闭合 | SeaView 接了再派、批量分配、释放记录；门控分别是 `case.dispatch`/`case.assign`：`frontend/src/views/SeaView.vue:64-120,154-191` |
| 6 listCoCommissionBatches N+1 | 已闭合 | 已改为单条聚合 SQL，逐笔 `round` 后 sum、未结 `NOT EXISTS SETTLED`、LEFT JOIN 保零明细出行：`CoCommissionM9Controller.java:145-179` |

**本轮新发现**

BLOCKER: `clearCaseProvider(NULL)` 与 `COALESCE(c.provider_id,b.provider_id)` 语义冲突。退回/自动退回把 `case.provider_id` 清空后，会回落到旧 `batch.provider_id`，S0 平台公海案仍可被旧服务商通过 `/cases` 等 provider scope 看见。证据：`CaseStateService.java:90-94,163-166`、`HolderM3Controller.java:178-182`、`ExpiryService.java:74-75`、`CasesM2Controller.java:291-296`。

BLOCKER: 单案拒接仍在改整批 `batch.provider_id`，且没有维护 `case.provider_id`。`rejectCase` 对单案 S1→S0 后执行 `UPDATE batch SET provider_id = NULL`，会污染同批批次级视图/付佣口径，并让拒接案残留旧 `case.provider_id`。证据：`ProviderM3Controller.java:119-124`。

HIGH: 释放/开放池路径 provider 维护不自洽。`releaseCase`、成员停用自动释放、TC 自动释放都没有清理案件级 provider；OPEN_POOL 回流后可能残留上一持有人服务商归属。证据：`HolderM3Controller.java:143-150`、`CaseStateService.java:260-267`、`ExpiryService.java:50-52`、`MemberM1Controller.java:390-399`。

HIGH: COALESCE 漏改仍存在。`CaseScopeM4Service`、`RiskQcService.visibleByRange`、`RecordingService.caseVisible` 仍按 `b.provider_id` 判 provider scope；再派后会出现列表可见但详情/动作 403/404，或旧服务商误通过。证据：`CaseScopeM4Service.java:87-98`、`RiskQcService.java:101-104`、`RecordingService.java:87-90`。

MEDIUM: 释放记录仍漏纯定时自动释放。`ProvidersController` 自注释说明只覆盖 `audit_log(case.release)`，而 `ExpiryService.expireTC` 只写 activity，不写 audit_log。证据：`ProvidersController.java:35-36`、`ExpiryService.java:52`。

LOW: 派单 provider metrics 仍按 `b.provider_id` 聚合，单案再派后 active/repay/due 指标会归到批次服务商而不是案件当前服务商。证据：`WorkbenchDispatchController.java:138-144`。

未发现新的直接 SQL 注入点；本轮新增/核查的关键 SQL 基本使用占位参数。`listCoCommissionBatches` 的 N+1 已闭合。

复审评分：76/100，较上轮 82 下降 6 分。原因是原 6 条多数修了，但 provider 归属模型的 NULL/COALESCE 设计和拒接路径仍会造成越权可见与批次级污染。结论：不可上线。
tokens used
171,466
结论：上轮 6 条里 5 条闭合、1 条部分闭合。但案件级 `provider_id` 重构仍有阻断级归属语义问题，本轮不可上线。未改文件，未跑测试。

**闭合确认**

| # | 结论 | 证据 |
|---|---|---|
| 1 单案再派污染整批 | 已闭合 | `redispatchCase` 只 `setCaseProvider(caseId, providerId)`，未改 `batch.provider_id`；holdcap 用 `existing + 1`：`backend/.../DispatchM3Controller.java:128-145` |
| 2 退回原服务商护栏 | 已闭合 | `lastReturnedProvider` 改读 `before_snap ->> 'providerId'`：`DispatchM3Controller.java:462-475`；`case.return` 审计 before 来自退回前 `snap`：`HolderM3Controller.java:164-182`；审计 JSON 写 `providerId`：`CaseStateService.java:306-317` |
| 3 释放记录历史 scope | 已闭合 | `listReleaseRecords` 按事件时 `before_snap->>'providerId'` 过滤，不再按当前 batch：`ProvidersController.java:78-88` |
| 4 批量分配并发超额 | 部分 | `assignCasesBatch` 对 collector `FOR UPDATE`、锁内算余量、超额进 rejected：`DispatchM3Controller.java:173-179,222-225,257-260`；但单案 `assignCase/claimCase` 未拿同一 collector 锁，仍可与批量并发穿透 holdcap：`HolderM3Controller.java:76-86`、`CaseStateService.java:224` |
| 5 前端三方一致 | 已闭合 | SeaView 接了再派、批量分配、释放记录；门控分别是 `case.dispatch`/`case.assign`：`frontend/src/views/SeaView.vue:64-120,154-191` |
| 6 listCoCommissionBatches N+1 | 已闭合 | 已改为单条聚合 SQL，逐笔 `round` 后 sum、未结 `NOT EXISTS SETTLED`、LEFT JOIN 保零明细出行：`CoCommissionM9Controller.java:145-179` |

**本轮新发现**

BLOCKER: `clearCaseProvider(NULL)` 与 `COALESCE(c.provider_id,b.provider_id)` 语义冲突。退回/自动退回把 `case.provider_id` 清空后，会回落到旧 `batch.provider_id`，S0 平台公海案仍可被旧服务商通过 `/cases` 等 provider scope 看见。证据：`CaseStateService.java:90-94,163-166`、`HolderM3Controller.java:178-182`、`ExpiryService.java:74-75`、`CasesM2Controller.java:291-296`。

BLOCKER: 单案拒接仍在改整批 `batch.provider_id`，且没有维护 `case.provider_id`。`rejectCase` 对单案 S1→S0 后执行 `UPDATE batch SET provider_id = NULL`，会污染同批批次级视图/付佣口径，并让拒接案残留旧 `case.provider_id`。证据：`ProviderM3Controller.java:119-124`。

HIGH: 释放/开放池路径 provider 维护不自洽。`releaseCase`、成员停用自动释放、TC 自动释放都没有清理案件级 provider；OPEN_POOL 回流后可能残留上一持有人服务商归属。证据：`HolderM3Controller.java:143-150`、`CaseStateService.java:260-267`、`ExpiryService.java:50-52`、`MemberM1Controller.java:390-399`。

HIGH: COALESCE 漏改仍存在。`CaseScopeM4Service`、`RiskQcService.visibleByRange`、`RecordingService.caseVisible` 仍按 `b.provider_id` 判 provider scope；再派后会出现列表可见但详情/动作 403/404，或旧服务商误通过。证据：`CaseScopeM4Service.java:87-98`、`RiskQcService.java:101-104`、`RecordingService.java:87-90`。

MEDIUM: 释放记录仍漏纯定时自动释放。`ProvidersController` 自注释说明只覆盖 `audit_log(case.release)`，而 `ExpiryService.expireTC` 只写 activity，不写 audit_log。证据：`ProvidersController.java:35-36`、`ExpiryService.java:52`。

LOW: 派单 provider metrics 仍按 `b.provider_id` 聚合，单案再派后 active/repay/due 指标会归到批次服务商而不是案件当前服务商。证据：`WorkbenchDispatchController.java:138-144`。

未发现新的直接 SQL 注入点；本轮新增/核查的关键 SQL 基本使用占位参数。`listCoCommissionBatches` 的 N+1 已闭合。

复审评分：76/100，较上轮 82 下降 6 分。原因是原 6 条多数修了，但 provider 归属模型的 NULL/COALESCE 设计和拒接路径仍会造成越权可见与批次级污染。结论：不可上线。
