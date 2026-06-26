# 契约契合度复审报告（对抗性·只读）— remediation/contract-98

## 一、结论与打分

**复审打分：95 / 100**（规划轮基线 74 → 目标 98）。

本轮修复**质量扎实、对账严谨**：19 条 finding + 多条净增尾巴中，**28 条已行级闭合**，仅 **1 条核心实活缺陷（BC-04 drift 断链）** + 4 条方案刻意保留/拆单项未闭。route_coverage 实跑全绿（契约 135 操作 · handler 136 · 缺 0）。契约 x-permission ↔ 后端 @RequirePermission ↔ 前端门控三方在所有新端点上一致。距 96（方案自评全落地目标）差 1 分，主因 BC-04 的 drift 字段未落 BatchView record 导致前端同步告警 banner 成死代码。

## 二、三方对账（契约 ↔ 后端注解 ↔ 前端门控）

逐一核验重点新端点，**全部一致**：

| 端点 | 契约 x-permission/scope | 后端注解 | 前端门控 | 判定 |
|---|---|---|---|---|
| POST /cases/{id}/redispatch | case.dispatch / platform | @RequirePermission(case.dispatch)+requirePlatform (DispatchM3:100) | 平台公海单案再派入口 | ✅ |
| POST /cases/assign-batch | case.assign / own-org | @RequirePermission(case.assign) (DispatchM3:160) | VL 批量指派 | ✅ |
| GET /providers/{id}/release-records | 无 perm / own-org | 无注解+org 自校 403 (ProvidersController) | VL 本商可见 | ✅ |
| GET /co-commissions/{collectorId}/batches | cocomm.manage / own-org | @RequirePermission(cocomm.manage) (CoCommissionM9:136) | showCoComm(cocomm.manage) | ✅ |
| PUT /batches/{id}/coordinators | batch.import / own-org | @RequirePermission(batch.import) (MasterWrite:265) | auth.has(batch.import) (BatchDetail:151) | ✅ |
| POST /batches/{id}/reduce-tiers:sync | reduce.policy.edit | @RequirePermission(reduce.policy.edit) (MasterWrite:336) | auth.has(reduce.policy.edit) | ✅ |
| POST /batches/{id}/playbook:sync | playbook.adopt | @RequirePermission(playbook.adopt) (MasterWrite:356) | auth.has(playbook.adopt) | ✅ |
| POST /evidence/{id}/retry | evidence.create | @RequirePermission(evidence.create) (EvidenceM6) | EvidenceView FAILED 行 | ✅ |

**route_coverage**：实跑 `python3 backend/scripts/route_coverage.py` = `契约 135 · handler 136 · 缺 0`，已绿。schema.d.ts 已 gen:api 重生（listCoCommissionBatches/assignCasesBatch/redispatchCase/listReleaseRecords/syncBatch* 均在）。

## 三、四个专项审计

**1) M-04 q 防脱敏侧信道 — 正确闭合。** CasesM2Controller.appendKeyword 对 `!isPlatform && PROVIDER`（会触发脱敏的主体）在 q 子句前先 `AND c.status NOT IN ('SETTLED','WITHDRAWN','BAD_DEBT','VOIDED')`，使脱敏案件无法被业主名/手机号 ILIKE 探测；scope 裁剪恒在 WHERE 末尾追加不可绕过。防侧信道逻辑成立（BR-M8-09）。

**2) BC-04 真值化是否引回归 — reduceMode 无回归，但 drift 断链。** deriveOverride 由 `reduce_tier(batch_id)` 存在性判 CUSTOM/INHERIT，与 GET reduce-tiers source 同源，无矛盾；V912 baseline 列回填取"当前项目级 max(updated_at)"=同步态，避免历史覆盖一上线即误报 drift，迁移幂等加列安全。**但 reduceDrift/playbookDrift 计算后未落 BatchPlatformView record，toView 不 emit**，前端 BatchDetailView:166/188 的同步告警 banner 恒不显（详见开口清单 BC-04）。

**3) 菜单放宽后 PL/PC 读是否空白页 — 不会。** /settlement /evidence 去写权限门控后：SettlementView load() 仅打无 x-permission 的 /recon/rollup + /payment-requests（scope=range，PL/PC 读 IN 线返 200）；loadCoComm 按 `auth.has(cocomm.manage)` / `cocomm.self.view` 分支调用，PL/PC 两者皆无→不触发 cocomm 端点→不 403；isReadonlyProperty 出只读提示，写按钮全 v-if 隐藏。BatchDetailView 对 reduce-tiers GET 403 捕获为 tiersPermDenied 出提示而非空白（BC-06 优雅降级）。CO 见结算菜单但 canViewPayReq=false 仅显"我的佣金"。**无空白页风险。**

**4) 铁律③（前端防白屏）— 合规。** 弹窗数组字段初始即初始化（CaseDetailView refIds:[] 初始化、SettlementView cSel/cLines/gLines 初值 []）。ES2020 可选链/空值合并在 .vue `<script>` 中是项目既有普遍风格（经 Vite/esbuild 编译，非老 WebView 直载），铁律③的 ES2020 禁令针对 HTML 原型，此处跟随周边代码正确。

## 四、未闭合清单（按严重度）

- **[实活·主缺口] BC-04 drift 断链**：后端算出 reduceDrift 但 BatchView record 不含该字段→前端同步 banner 死代码，BR-M2-18b"有差异标记"用户面入口不可达。契约字段为 optional 故 Gate1 不报错。**这是 95→96 的唯一硬开口。**
- [降级] playbook 批次级 drift 恒 false（DDL 无 batch 级手册存储，V912 baseline_* 前向预留）。
- [拆单] M-07b 被代方可见（audit_log range scope 下平台代操作物业/服务商不可见）— 方案明确本轮不做。
- [占位] documentUrl/sealed（PaymentRequest+CoPayDoc）为 TBD，无真实文件通道/电子签章 — 方案六.1 刻意保留。
- [一致性] e2e 种子双轨：helpers.ts ACCOUNTS 用 DevSeeder 用户名（cuihu_pl/jx_vl…），与 V900 新增 plowner/vlowner 不一致，注释"等价账号"误导；依赖 DevSeeder 可登录，纯 Flyway 环境会失败。

## 五、到 98% 残差

BC-04 drift 三步落地（record 加字段+toView emit+e2e 验证，约 +2）→ 达 96-97；再补 M-07b proxy_for 可见性 + documentUrl/sealed 真实现 + e2e 种子统一 → 98+。详见 residualTo98。

## 六、运行活测

见 liveTestCommands：Gate1 = route_coverage（静态）+ schemathesis 25 例/全端点带鉴权（checks 含 response_schema_conformance/ignored_auth，排 status_code_conformance 因负向 fuzz artifact）；Gate2 = Playwright 真屏（需 DevSeeder dev profile + 后端 9091）。重点回归 batch-sync-drift（预期暴露 BC-04 banner 不显）、settlement-readonly、sea-{redispatch,assign-batch,release-records}、funds-redaction、co-commission-drilldown。改契约后须 `npm run gen:api && git diff --exit-code src/api/schema.d.ts` 防漂移。