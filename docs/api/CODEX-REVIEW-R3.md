| 上轮缺陷 | 结论 | 证据 |
|---|---:|---|
| 1. COALESCE 语义冲突 | **部分闭合** | 运行时代码已基本改为直接 `c.provider_id`： [CasesM2Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CasesM2Controller.java:291)、[SeaM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/SeaM3Controller.java:132)、[CaseScopeM4Service.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CaseScopeM4Service.java:87)。`reject/return` 后 `case.provider_id=NULL` 的 S0 案，旧服务商按这些 scope 不可见。但迁移仍无条件从 `batch.provider_id` 回填所有案件，历史 S0/S4 会被打上旧商： [V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:22)。 |
| 2. 3 处 service 漏改 | **已闭合** | `CaseScopeM4Service` 读 `c.provider_id` 并按它判断：[CaseScopeM4Service.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CaseScopeM4Service.java:77)；`RiskQcService.visibleByRange` 用 `c.provider_id`：[RiskQcService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/RiskQcService.java:101)；`RecordingService.caseVisible` 用 `c.provider_id`：[RecordingService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/RecordingService.java:87)。 |
| 3. 单案拒接 rejectCase 改整批 | **已闭合** | `rejectCase` 成功转 S0 后只 `clearCaseProvider(caseId)`，未再 `UPDATE batch.provider_id`： [ProviderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ProviderM3Controller.java:119)。 |
| 4. 释放/开放池/自动释放 provider 维护 | **部分闭合** | 手动释放回 S2 保留、回 S4 清空：[HolderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/HolderM3Controller.java:149)；退回 S0 清空：[HolderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/HolderM3Controller.java:183)；T2 清空：[ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:82)；TC 按 S2/S4 维护：[ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:50)。但成员停用自动释放仍无视 `origin_pool`，一律回 S2 且不清 provider：[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:390)。 |
| 5. expireTC 释放记录 | **已闭合** | `expireTC` 现在写 `audit_log action='case.release'`：[ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:57)；释放记录按 `before_snap.providerId` 取事件时归属：[ProvidersController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ProvidersController.java:78)。 |

**本轮新发现**

- **BLOCKER**：`V913` 迁移回填仍按旧 COALESCE 思路，把同批所有历史案件都设为 `b.provider_id`，没有排除 S0 平台公海和 S4 开放池。升级后旧服务商会通过当前已改好的 `c.provider_id` scope 直接看到本应无归属的历史公海案。证据：[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:21)、[CasesM2Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CasesM2Controller.java:291)。

- **HIGH**：成员停用自动释放没有复用 `resolveReleaseTarget`。从开放池抢来的私海案，停用释放应回 S4 并清 `provider_id`；当前强制回 S2 并保留 provider，且未重置 T2，可能把开放池案沉淀为本商公海案。证据：[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:390)、[CaseStateService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/CaseStateService.java:261)。

- **LOW/既有残留**：`/dispatch/provider-metrics` 仍按 `b.provider_id` 聚合 active/repay/due，单案再派后指标会归到批次服务商而不是案件当前服务商：[WorkbenchDispatchController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/WorkbenchDispatchController.java:137)。

批次级口径检查：`BatchesM2`、`Reports`、`PaymentRequest OUT`、`CoCommission OUT`、`Playbook` 仍保持 `b.provider_id`，这与本轮口径一致，未发现误改。未见新的 SQL 注入点；未见会对外暴露 5xx 的新增路径；未做测试执行验证。

**总评**

本轮比上一轮 76 有明显收敛，但迁移层仍有阻断级越权风险，成员停用释放也未完全自洽。评分 **78/100**。结论：**未达到可上线门槛，不建议上线**。
tokens used
178,576
只读复审完成，未改文件，未跑测试。

**闭合确认表**

| 上轮缺陷 | 结论 | 证据 |
|---|---:|---|
| 1. COALESCE 语义冲突 | **部分闭合** | 运行时代码已基本改为直接 `c.provider_id`： [CasesM2Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CasesM2Controller.java:291)、[SeaM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/SeaM3Controller.java:132)、[CaseScopeM4Service.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CaseScopeM4Service.java:87)。`reject/return` 后 `case.provider_id=NULL` 的 S0 案，旧服务商按这些 scope 不可见。但迁移仍无条件从 `batch.provider_id` 回填所有案件，历史 S0/S4 会被打上旧商： [V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:22)。 |
| 2. 3 处 service 漏改 | **已闭合** | `CaseScopeM4Service` 读 `c.provider_id` 并按它判断：[CaseScopeM4Service.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CaseScopeM4Service.java:77)；`RiskQcService.visibleByRange` 用 `c.provider_id`：[RiskQcService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/RiskQcService.java:101)；`RecordingService.caseVisible` 用 `c.provider_id`：[RecordingService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/RecordingService.java:87)。 |
| 3. 单案拒接 rejectCase 改整批 | **已闭合** | `rejectCase` 成功转 S0 后只 `clearCaseProvider(caseId)`，未再 `UPDATE batch.provider_id`： [ProviderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ProviderM3Controller.java:119)。 |
| 4. 释放/开放池/自动释放 provider 维护 | **部分闭合** | 手动释放回 S2 保留、回 S4 清空：[HolderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/HolderM3Controller.java:149)；退回 S0 清空：[HolderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/HolderM3Controller.java:183)；T2 清空：[ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:82)；TC 按 S2/S4 维护：[ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:50)。但成员停用自动释放仍无视 `origin_pool`，一律回 S2 且不清 provider：[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:390)。 |
| 5. expireTC 释放记录 | **已闭合** | `expireTC` 现在写 `audit_log action='case.release'`：[ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:57)；释放记录按 `before_snap.providerId` 取事件时归属：[ProvidersController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ProvidersController.java:78)。 |

**本轮新发现**

- **BLOCKER**：`V913` 迁移回填仍按旧 COALESCE 思路，把同批所有历史案件都设为 `b.provider_id`，没有排除 S0 平台公海和 S4 开放池。升级后旧服务商会通过当前已改好的 `c.provider_id` scope 直接看到本应无归属的历史公海案。证据：[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:21)、[CasesM2Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CasesM2Controller.java:291)。

- **HIGH**：成员停用自动释放没有复用 `resolveReleaseTarget`。从开放池抢来的私海案，停用释放应回 S4 并清 `provider_id`；当前强制回 S2 并保留 provider，且未重置 T2，可能把开放池案沉淀为本商公海案。证据：[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:390)、[CaseStateService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/CaseStateService.java:261)。

- **LOW/既有残留**：`/dispatch/provider-metrics` 仍按 `b.provider_id` 聚合 active/repay/due，单案再派后指标会归到批次服务商而不是案件当前服务商：[WorkbenchDispatchController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/WorkbenchDispatchController.java:137)。

批次级口径检查：`BatchesM2`、`Reports`、`PaymentRequest OUT`、`CoCommission OUT`、`Playbook` 仍保持 `b.provider_id`，这与本轮口径一致，未发现误改。未见新的 SQL 注入点；未见会对外暴露 5xx 的新增路径；未做测试执行验证。

**总评**

本轮比上一轮 76 有明显收敛，但迁移层仍有阻断级越权风险，成员停用释放也未完全自洽。评分 **78/100**。结论：**未达到可上线门槛，不建议上线**。
