| 项 | 结论 | 依据 |
|---|---|---|
| `[BLOCKER] V913 迁移回填越权` | 已闭合 | V913 回填只继承已归属服务商的案件，排除了 `PLATFORM_SEA` 与 `OPEN_POOL`：[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:21)、[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:24) |
| `[HIGH] 成员停用自动释放` | 已闭合 | `releasePrivateCases` 已复用 `resolveReleaseTarget`，S4 清 `case.provider_id`，S2 保留 provider 并重置 T2：[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:397)、[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:402)；口径与 [HolderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/HolderM3Controller.java:143) / [ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:50) 一致 |
| `[LOW] /dispatch/provider-metrics 按 batch.provider_id 聚合` | 未闭合，仍残留，可接受 | `active_cases/repay30/due_total` 仍按 `b.provider_id`：[WorkbenchDispatchController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/WorkbenchDispatchController.java:137)、[WorkbenchDispatchController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/WorkbenchDispatchController.java:140) |

**本轮新发现**

| 级别 | 问题 | 最小修复建议 |
|---|---|---|
| HIGH | `/reports/operation` 是案件经营聚合，但服务商 scope 仍用 `b.provider_id`：[ReportsM10Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ReportsM10Controller.java:103)、[ReportsM10Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ReportsM10Controller.java:164)。退回 S0 / 开放 S4 后 `case.provider_id` 已清空，但 `batch.provider_id` 保留，旧服务商仍能看到这些案件的应收、回款、案件数聚合。 | 将 `ReportsM10Controller.appendRangeScope` 的 PROVIDER 分支从 `b.provider_id = ?` 改为 `c.provider_id = ?`，并同步修正文档注释；付佣、对账、批次列表仍保持 `b.provider_id`。 |
| LOW | V913 注释仍写“统一改 COALESCE(c.provider_id, b.provider_id)”：[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:10)。实现未使用 COALESCE，属注释漂移。 | 改注释，避免后续按旧口径回退。 |

**整体模型判断**

核心转移点已基本自洽：dispatch/redispatch 设置 `case.provider_id`，accept 保留，reject/return/expireT2 清空，claim/assign 写入持有人组织，release/expireTC/成员停用按 `origin_pool` 分 S2/S4。案件列表、详情、公海、风控、录音、证据、跟进等主要案件可见性已改为 `c.provider_id`。批次、付佣、对账继续使用 `b.provider_id`，这个方向正确。

未跑测试、未改文件。静态复审未发现新的 SQL 注入或明显 N+1；5xx 方面有全局兜底为 422，但本轮未做运行验证。

**评分：84/100**，相对 `82 → 76 → 78` 有明显修复进展，但因新增 `HIGH` 访问控制残留，**不建议上线**。
tokens used
161,905
**复审结论：未达到可上线门槛。**  
上轮的 `BLOCKER` 和 `HIGH` 已闭合，但本轮新增 1 个 `HIGH`：`/reports/operation` 仍按 `b.provider_id` 做服务商案件聚合，会把已退回平台公海/开放池、`c.provider_id=NULL` 的旧批次案件继续计入旧服务商报表。

**上轮闭合确认**

| 项 | 结论 | 依据 |
|---|---|---|
| `[BLOCKER] V913 迁移回填越权` | 已闭合 | V913 回填只继承已归属服务商的案件，排除了 `PLATFORM_SEA` 与 `OPEN_POOL`：[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:21)、[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:24) |
| `[HIGH] 成员停用自动释放` | 已闭合 | `releasePrivateCases` 已复用 `resolveReleaseTarget`，S4 清 `case.provider_id`，S2 保留 provider 并重置 T2：[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:397)、[MemberM1Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/MemberM1Controller.java:402)；口径与 [HolderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/HolderM3Controller.java:143) / [ExpiryService.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/dispatch/ExpiryService.java:50) 一致 |
| `[LOW] /dispatch/provider-metrics 按 batch.provider_id 聚合` | 未闭合，仍残留，可接受 | `active_cases/repay30/due_total` 仍按 `b.provider_id`：[WorkbenchDispatchController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/WorkbenchDispatchController.java:137)、[WorkbenchDispatchController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/WorkbenchDispatchController.java:140) |

**本轮新发现**

| 级别 | 问题 | 最小修复建议 |
|---|---|---|
| HIGH | `/reports/operation` 是案件经营聚合，但服务商 scope 仍用 `b.provider_id`：[ReportsM10Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ReportsM10Controller.java:103)、[ReportsM10Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ReportsM10Controller.java:164)。退回 S0 / 开放 S4 后 `case.provider_id` 已清空，但 `batch.provider_id` 保留，旧服务商仍能看到这些案件的应收、回款、案件数聚合。 | 将 `ReportsM10Controller.appendRangeScope` 的 PROVIDER 分支从 `b.provider_id = ?` 改为 `c.provider_id = ?`，并同步修正文档注释；付佣、对账、批次列表仍保持 `b.provider_id`。 |
| LOW | V913 注释仍写“统一改 COALESCE(c.provider_id, b.provider_id)”：[V913__case_provider.sql](/Users/shuo/AI/cuishou/backend/app/src/main/resources/db/migration/V913__case_provider.sql:10)。实现未使用 COALESCE，属注释漂移。 | 改注释，避免后续按旧口径回退。 |

**整体模型判断**

核心转移点已基本自洽：dispatch/redispatch 设置 `case.provider_id`，accept 保留，reject/return/expireT2 清空，claim/assign 写入持有人组织，release/expireTC/成员停用按 `origin_pool` 分 S2/S4。案件列表、详情、公海、风控、录音、证据、跟进等主要案件可见性已改为 `c.provider_id`。批次、付佣、对账继续使用 `b.provider_id`，这个方向正确。

未跑测试、未改文件。静态复审未发现新的 SQL 注入或明显 N+1；5xx 方面有全局兜底为 422，但本轮未做运行验证。

**评分：84/100**，相对 `82 → 76 → 78` 有明显修复进展，但因新增 `HIGH` 访问控制残留，**不建议上线**。
