**缺陷清单**

- **BLOCKER** 单案再派会改写整批 `batch.provider_id`，造成跨案越权/隐藏  
  文件：[DispatchM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/DispatchM3Controller.java:143)  
  原因：`/cases/{id}/redispatch` 是单案端点，但实现更新的是批次 provider。现有案件列表/公海 scope 又按 `batch.provider_id` 裁剪，导致同批其他案件也被转给新服务商可见，原服务商不可见；容量校验也只按 1 件计算。  
  修复建议：不要在单案再派里更新共享 batch provider。需要引入 case-level provider assignment，或再派时拆出新批次/子批次，并让所有 provider scope 统一使用单案归属。

- **HIGH** 再派禁止“退回原服务商”规则对 `case.return` 失效  
  文件：[DispatchM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/DispatchM3Controller.java:437)、[HolderM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/HolderM3Controller.java:178)  
  原因：`lastReturnedProvider` 只读 `audit_log.proxy_for`，但 `case.return` 审计没有写 `proxy_for`，导致 VL 退回后仍可再派给原服务商，违背契约 `BIZ_REDISPATCH_GUARD`。  
  修复建议：`case.return` 写入退回时 provider org，或更稳妥地从 `before_snap->>'providerId'` 推导 reject/return 的原 provider。

- **HIGH** 释放记录 own-org scope 使用当前批次 provider，历史记录会泄漏/丢失  
  文件：[ProvidersController.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/ProvidersController.java:78)  
  原因：`listReleaseRecords` 通过当前 `batch.provider_id = ?` 做 scope。批次被再派后，旧 provider 的释放记录会从旧 org 消失，并可能出现在新 provider 视图中。  
  修复建议：释放记录按事件发生时的 provider 过滤，例如 `audit_log.before_snap->>'providerId'`，或落一张不可变 release_record 表，保存 release-time provider/collector/case。

- **HIGH** 批量分配额度校验存在并发超额窗口  
  文件：[DispatchM3Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/DispatchM3Controller.java:174)  
  原因：`remaining` 在循环前只计算一次，循环内只逐案锁 case，没有锁目标 collector 或重新计算持有量。两个并发 batch assign 可各自通过额度判断，最终超过 hold cap。  
  修复建议：对目标 collector 加 `FOR UPDATE` 行锁或 advisory lock；在锁内逐案重新计算/扣减额度。超额案件继续进入 `rejected`，不要整批回滚。

- **HIGH** 新端点前端三方一致性不完整：批量分配、单案再派、释放记录无实际入口  
  文件：[SeaView.vue](/Users/shuo/AI/cuishou/frontend/src/views/SeaView.vue:98)、[sea-assign-batch.spec.ts](/Users/shuo/AI/cuishou/frontend/e2e/sea-assign-batch.spec.ts:25)、[sea-redispatch.spec.ts](/Users/shuo/AI/cuishou/frontend/e2e/sea-redispatch.spec.ts:17)、[sea-release-records.spec.ts](/Users/shuo/AI/cuishou/frontend/e2e/sea-release-records.spec.ts:11)  
  原因：OpenAPI/后端已新增端点，但 `SeaView` 仍只有单案认领/接收/拒绝/开放抢单/单案分配；e2e 期望的批量选择、`再派`、`释放记录` 按钮不存在。  
  修复建议：补齐前端入口和权限门控：`case.assign` 控制批量分配，`case.dispatch` 控制单案再派，释放记录按 VL/own-org 可见策略接入 `/providers/{orgId}/release-records`。

- **MEDIUM** `listCoCommissionBatches` 存在明显 N+1 查询  
  文件：[CoCommissionM9Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CoCommissionM9Controller.java:145)、[CoCommissionM9Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CoCommissionM9Controller.java:512)、[CoCommissionM9Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CoCommissionM9Controller.java:522)  
  原因：每个 batch 再查 active lines、batch name，每条 repay line 又查 settled 状态。大服务商/大批次下容易退化成大量 SQL。  
  修复建议：改成单条聚合 SQL，按 batch group，`LEFT JOIN co_pay_doc_line/co_pay_doc` 一次性计算 due/settled/unsettled/count。

**已核对未列缺陷**

`listCases q` 侧信道修复看起来成立：[CasesM2Controller.java](/Users/shuo/AI/cuishou/backend/app/src/main/java/com/youzheng/huicui/web/CasesM2Controller.java:275) 对 PROVIDER 脱敏主体排除了 `SETTLED/WITHDRAWN/BAD_DEBT/VOIDED`，`ILIKE` 使用参数绑定，scope 仍在 WHERE 末尾追加。

`retryEvidence`、`syncBatchReduceTiers`、`syncBatchPlaybook` 的契约权限与后端注解基本一致；`/settlement`、`/evidence` 菜单放宽后，后端读 scope 未见 PL/PC 空白页或明显越权问题。

**结论**

契约契合度：**82/100**。  
上线判断：**不可上线**。至少需要先修复单案再派批次污染、释放记录历史 scope、退回原服务商 guard 失效和批量分配并发额度问题。按要求未改文件、未跑测试。
CODEX_DONE exit=0
