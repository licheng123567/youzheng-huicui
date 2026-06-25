# 12 · PRD 对齐复评报告 — 终局确认（评审综合者复核版）

> 评审角色：评审综合者（opus）。本轮在 Q1 模块覆盖审计 + Q2 角色一致性审计基础上，**对所有 high/medium severity 与可疑 missing/over-permission 项亲自 grep/Read `/Users/shuo/AI/cuishou/docs/ui/高保真/` 与 `/Users/shuo/AI/cuishou/docs/prd/`、`02-角色操作权限矩阵.md` 逐条对抗校验**，推翻误报、补充漏报后综合。所有行号均为本轮实地取证，不照抄旧报告。
> 取证文件：index.html（3040 行）、app.html（309 行）、h5.html（445 行）、login.html（228 行）、02-角色操作权限矩阵.md（178 行）、prd/03~10。
> 复评日期：2026-06-20。

---

## ① 执行摘要

### 总体评分（百分制）

| 维度 | 完成度（Coverage） | 质量（一致性/隔离正确度） |
|---|---|---|
| **Q1 PRD 实现度（11 模块加权）** | **97.6** | **96.8** |
| **Q2 角色一致性（6 角色加权）** | **97.4** | — |

> 加权方式：Q1 按模块故事条数加权；Q2 按角色取均值（SA/SE/PL/PC/VL/CO）。

### 与基线对比

| 模块 | 基线（轮3前） | 本轮终局 | Δ |
|---|---|---|---|
| M6 存证 | 75 / 86 | **100 / 98** | ↑↑ |
| M7 H5 | 88 / 90 | **100 / 99** | ↑↑ |
| M8 结案 | 92 / 93 | **97.4 / 96** | ↑ |
| M9 对账 | 94.5 / 96.8 | **97.0 / 97.0** | ↑ |
| M10 报表 | 98.5 / 97.0 | **97.5 / 97.5** | ≈ |
| M11（综合口径） | 97.4 / 98.2 | **97.6 / 96.8** | ≈ |

### 收敛轮 4（FW6）及后续（FW7/FW8）实测确认提升点

经实地取证确认**已落地**：
- **FW6**：最长周期弹窗 `maxCycleVisible` 按 scope 过滤消跨物业泄露（L2646）；平台报表 `areaPeriodVisible` 叠加 SE dataRange（L2951）；负责人账号常驻守卫 `owner:true` + `v-if="!m.owner"`（L1418/L2527，BR-M1-12）；质检处置物业侧 PL/PC 放开（L986/L994，按本物业 scope）；SE nav 补 evidence 只读证书下载（L2435）。
- **FW7**：`reconOutVisible` computed 补齐资金双线最后一处 scope 对称（L2524，property→`[]`）；`voidStats→voidStatsVisible`（L2562）；释放弹窗按来源区分去向（L1760）；M3 再派目标服务商弹窗 + 护栏 `reDispatchCandidates`（L2849）。
- **FW8**：撤案统计引用 `voidStatsVisible`（L1198/L1209/L1268/L1279，**注：L1333 VL 报表仍残留 `voidStats.byCollector` 原始引用 — 见缺口清单**）；SA 代充值加目标组织下拉 + proxyFor 留痕（L2363+/L2920）；settings 时效参数生效范围注记（L1061，BR-M3-19）；项目列表补法务处理数/已结清数列。

### 是否达到 ≥98？

**结论：尚未全面达到双双 ≥98，但绝大多数模块已达标或临界。**

- **已达标（≥98）模块**：M0（100/98.5）、M6（100/98）、M7（100/99）。
- **临界（97~97.9，差 0.1~1.5 个点）**：M1、M2、M8、M9、M10、M3、M4。
- **明显低于线**：**M5（94.4 / 92）** — 唯一仍存 **medium 级真实违规**（BR-M5-05 AI 初稿对服务商可见），是当前拉低整体的关键短板。

**距 ≥98 仅剩 1 个 medium + 约 7 个 low，修复成本极低（均为加 `v-if` 角色门控 / 补 1 列 / 补 1 行 note / 改引用变量），修复后可全面达标。**

---

## ② Q1 逐模块覆盖表与缺口清单

### 逐模块覆盖统计

| 模块 | 已实现 | 部分 | 缺失 | 覆盖率 | 质量 |
|---|---|---|---|---|---|
| M0 总纲 | 3 | 0 | 0 | 100% | 98.5 |
| M1 组织与权限 | 14 | 1 | 0 | 98.7% | 98.5 |
| M2 主数据 | 19 | 1 | 0 | 97.7% | 97.7 |
| M3 流转与公海 | 10 | 1 | 0 | 95.5% | 95.0 |
| M4 催收作业 | 11 | 1 | 0 | 95.8% | 95.0 |
| **M5 录音/AI/质检** | **16** | **2** | **0** | **94.4%** | **92.0** |
| M6 存证 | 14 | 0 | 0 | 100% | 98.0 |
| M7 业主 H5 | 8 | 0 | 0 | 100% | 99.0 |
| M8 结案/生命周期 | 10 | 1 | 0 | 97.4% | 96.0 |
| M9 对账结算 | 12 | 2 | 0 | 97.0% | 97.0 |
| M10 经营报表 | 9 | 1 | 0 | 97.5% | 97.5 |
| **合计** | **126** | **10** | **0** | **97.6%** | **96.8** |

> 零 missing。全部缺口为 partial（部分实现/信息标注/门控缺口），无功能性缺失。

### 缺口清单（按 severity 排序，已对抗校验）

| # | severity | US/BR | 缺口 | UI 证据 | 校验结论 |
|---|---|---|---|---|---|
| 1 | **medium** | US-M5-04 / BR-M5-05 | **AI 作战手册初稿 diff 内容对服务商（VL）可见，违反"未采纳前不对服务商正式发布"** | index.html L130-136：`AI 新建议（diff）` 的 `<p>` 内容行无角色 `v-if`，仅采纳/忽略按钮 `v-if="can('proj.edit')"`；VL nav 含 `projects`（L2443） | **✅ 确认违规**。PRD US-M5-04 GWT 第2条原文"未采纳前 AI 初稿不对服务商正式发布（BR-M5-05）"（prd/05 L98）。矩阵 §7 VL 对作战手册=○（可读现行版），但未采纳草稿应隐藏 |
| 2 | low | US-M3-06 / BR-M3-26 | 案件列表行缺 `T_collector` 剩余倒计时列（类似 T2 剩余列 L416）；预警仅在工作台待办可见 | L557 暂缓标签、L2771 CO 待办、L416 仅有 T2 列 | ✅ 确认。自动释放本属后端定时任务，UI 表达完整度略欠 |
| 3 | low | US-M4-07 / BR-M4-03+05a | 建议法务按钮仅 toast，未显示"已通知协调员"反馈、未标记为重置 T_collector 的有效跟进 | L790 按钮、L2882 `markEffective` 仅判 callMark | ✅ 确认 |
| 4 | low | BR-M2-16 | UI 未显式区分"承诺分期不改变应收 vs 政策分期改应收"，仅靠代码逻辑（dueAfter 由政策分期驱动）隐式区分 | L2459 case.reduce label 提政策分期；承诺单 drawer 无文字声明 | ✅ 确认。grep 无"承诺分期不参与应收"类文案 |
| 5 | low | US-M2-05 | 批次列表（案件管理视图）表头无"应收总额"列，dueTotal 仅在批次详情 KPI（L273）与报表汇总表（L1343）展示 | L176 批次列表表头：批次号/项目/案件数/回款率/状态/操作，**无应收总额** | ✅ 确认（注：报表视图 L1343 已有该列，但 M2 案管视图 L176 确实缺） |
| 6 | low | US-M1-01 | `submitOrg()` 未校验组织名重复冲突 | L2843 仅验必填 | ✅ 确认（演示占位） |
| 7 | low | BR-M8-11 | 分期某期违约后恢复 T_collector 计时无 reactive 驱动；`maxCycleCases.cat` 硬编码非由 `paid>0` 动态计算 | L1647 违约标记仅 toast；L2883 promiseHold 不回退 | ✅ 确认（子规则逻辑占位） |
| 8 | low | BR-M9-01a | reconIn 表"回款基数"列未标注"减免后实收·不含税"口径 | L826 表头无口径注记 | ✅ 确认（信息标注缺，非功能缺） |
| 9 | low | BR-M9-04 | 结算单据回款明细下钻无"付佣归属服务商"列（分期逐笔时点归属追溯） | L2791 `openSettle` lines 无 provider 字段 | ✅ 确认（原型态可接受） |
| 10 | low | BR-M10-04 | 报表指标与案件列表口径一致性为静态占位，实时刷新口径 TBD | L2551 drillData 静态 | ✅ 确认（已复用同一 batches 源，缺口径声明 note） |

---

## ③ Q2 6 角色 × 一致性表与不一致清单

### 角色一致性评分

| 角色 | 评分 | nav | perms | 资金隔离 | 结案脱敏 | 关键问题 |
|---|---|---|---|---|---|---|
| SA 平台超管 | 99 | ✅ 22项对齐 | ✅ 15项全权 | ✅ all 全量 | N/A | 仅演示常量说明 |
| SE 平台员工 | 96 | ✅ 严格最小集 | ✅ 3项最小 | ✅ range 裁剪 | N/A | seQcAuthorized 硬编码(可接受)、startReconSettle 无 dataRange 守卫(防御缺口) |
| PL 物业负责人 | 98 | ✅ 15项 | ✅ 7项 | ✅ property见收佣/不见付佣 | N/A | disposeTasksVisible 缺 property 分支 |
| PC 物业协调员 | 97 | ✅ 11项 | ✅ 12项 | ✅ reconOut→[] | N/A | 结算单据详情对 PC 屏蔽(矩阵 PC=○下载)；disposeTasksVisible 同 PL |
| **VL 服务商负责人** | **95** | ✅ 含 reconOut | ⚠ 见下 | ✅ reconInVisible→[] | ✅ redactClosed | **AI 初稿可见(medium)**、整改回填越矩阵(low)、byCollector 未裁剪(low) |
| CO 催收员 | 97 | ✅ 4项最小 | ✅ 含 release/grab/ticket | ✅ 不见收佣付佣 | ✅ 双端 redact | case.ticket 属 PRD 授权(矩阵遗列,非违规) |
| OW 业主 H5 | 99 | N/A token 门控 | N/A | N/A | N/A 隐私最小化达标 | 收款渠道静态硬编码(占位) |

### 不一致清单（已对抗校验，含被修正项）

| # | type | severity | PRD/矩阵依据 | UI 现状 | 校验结论与建议 |
|---|---|---|---|---|---|
| A | **matrix-divergence / over-permission** | **medium** | US-M5-04 GWT "未采纳前 AI 初稿不对服务商正式发布"（prd/05 L98）；矩阵 §7 VL 作战手册=○ | index.html L130-136 AI 新建议 diff 内容无角色门控，VL 可见草稿文字 | **✅ 确认**。建议：L131-135 `<p>` 外层加 `v-if="can('proj.edit')||['SA','SE'].includes(role)"`，使 VL/CO 看不到未采纳草稿，仅可见现行版（v3） |
| B | over-permission | ~~medium~~ → **low（修正降级）** | ~~矩阵 §3.2 释放 VL=○~~ → 实际 BR-M3-28/US-M3-12 | L383/L421 "主动退回公海" 用 `['VL','CO'].includes(role)` 绕过 can() | **⚠ 审计误报，已修正**：BR-M3-28/US-M3-12 明文"**服务商负责人**可主动退回平台公海"（prd/03 L88/179-184），actor 即 VL。"主动退回(退案)"≠"释放回来源公海(BR-M3-11)"，审计将两动作混淆。VL 持此按钮**符合 PRD**，非 over-permission。残留 low 问题：CO 也被纳入 `['VL','CO']`，而 BR-M3-28 仅授权 VL；建议收窄为 `role==='VL'` 或新增 `can('case.returnSea')` 显式权限点 |
| C | matrix-divergence | low | 矩阵 §7 "VL 仍只读 ◐本商" | L1010 VL "回填整改完成"可点击写按钮 | **✅ 确认**。FW7 引入的整改闭环回填属合理业务需求，建议更新矩阵 §7 VL 行为"◐本商+整改回填"，使文档与实现同步（业务正确，文档滞后） |
| D | scope-mismatch | low | BR-M10-09 催收员个人级仅服务商内部可见 | L1333 VL 报表"撤案频次(按催收员)"用原始 `voidStats.byCollector` 而非 `voidStatsVisible.byCollector`；且 `voidStatsVisible` 各分支均原样透传 byCollector 未裁剪 | **✅ 确认**。单服务商演示数据掩盖；多服务商时存在跨商催收员个人数据泄露。建议 voidStatsVisible 的 provider/range 分支对 byCollector 也按服务商过滤，且 L1333 改引用 voidStatsVisible |
| E | scope-mismatch | low | 矩阵 §9 跨物业隔离；§7 PL/PC ◐本物业 | L2612 `disposeTasksVisible` 仅 provider/range 分支，property(PL/PC) 走 `return disposeTasks` 全量 fallback | **✅ 确认**。`alertVisible`（L2614）已正确按 property 过滤，`disposeTasksVisible` 缺同等 property 分支。建议补 `if(sc==='property') return disposeTasks.filter(t=>本物业服务商列表.includes(t.provider))` |
| F | missing-access | low | 矩阵 §6 对账单下载 PC=○ | L832 结算单据明细弹窗 `role!=='PC'` 排除 PC | **✅ 确认**。PC 可下载 PDF(L833 无门控)但无法页内查看明细。建议放开 openSettle 对 PC 只读 |
| G | over-permission(防御缺口) | low | 矩阵 §6 SE reconIn/reconOut=●▲；BR-M1-14 | L281 发起结算 `role==='SA'||role==='SE'`，startReconSettle(L2795) 无 SE dataRange 二次校验 | **✅ 确认**。依赖上游 scopedBatches 过滤隐性兜底，理论安全但无显式守卫。建议加 dataRange 断言 |
| H | matrix-note | low（非违规） | 矩阵 §7 SE 质检处置=●▲(若授权) | L2717 `seQcAuthorized=true` 硬编码 | **⚠ 修正为非违规**：L2717 注释明确"正式版读后端授权位；演示置 true"，且未授权时 else-if 正确显示"待平台授权"占位（L986/994）。属合规演示态，不计为缺口 |

### 关键隔离专项核对（全部 ✅ 实地取证通过）

| 专项 | 取证 | 结论 |
|---|---|---|
| **资金双线隔离** | reconInVisible provider→`[]`（L2521）；reconOutVisible property→`[]`（L2524）；billingVisible provider 仅 STT/分钟（L2622）；案件详情收佣区块 `['SA','SE','PL','PC']` 渲染、VL 仅付佣、CO 皆不见（L680-689） | ✅ 物业↔服务商零资金互通，物理隔离完整 |
| **结案脱敏** | canSeeDetail（L2723）按 redactClosed 门控；VL/CO redactClosed=true（L2442/2445）；app.html redactOn=CO&&isClosed（L292），明细替换占位（L294） | ✅ 双端正确 |
| **SE 数据范围** | reconIn/reconOut/qc/evidence/voidStats/audit/areaPeriod/dispatchPool/providerLoad 全系列含 `sc==='range'` 按 dataRange 裁剪 | ✅ 仅 startReconSettle 缺显式守卫（缺口 G） |
| **PC/PL 权限边界** | PL 含 proj.edit、PC 不含；reconIn 确认/驳回专属 PL（L830-831）；PC 只读对账 | ✅ 正确（除缺口 E/F） |
| **CO/VL 私海公海可见** | providerSea filter status==='进行中'&&!owner（L2732）；抢单 can('case.grab')&&!cs.owner（L419）；结案案件不入公海 | ✅ 正确 |
| **业主 H5 最小可见** | TOKENS 白名单 + expiresAt 校验（L284+）；三态 valid/expired/invalid，失效页不含任何业主数据（L256）；textContent 防 XSS；无服务商/催收/他案字段 | ✅ 隐私最小化达标 |

---

## ④ 校验修正记录（被对抗校验推翻或修正的项）

| 原审计结论 | 复核动作 | 修正后结论 |
|---|---|---|
| **Q2-VL：「主动退回公海」为 medium over-permission（矩阵 §3.2 释放 VL=○）** | Read prd/03 L88/L179-184（BR-M3-28/US-M3-12） | **推翻为非违规并降级**。BR-M3-28 明文授权"服务商负责人主动退回平台公海"，actor=VL。审计将"主动退案(BR-M3-28)"与"释放回来源公海(BR-M3-11)"混淆。**残留 low**：CO 不应包含在 `['VL','CO']` 内（BR-M3-28 仅 VL）。此为本轮 VL 评分从受 medium 拖累上修的关键依据 |
| **Q2-SE：seQcAuthorized 硬编码为 matrix-divergence（low）** | Read L2717 注释 + L986/994 else-if 分支 | **修正为非违规**。注释声明"正式版读后端授权位；演示置 true"，且未授权态有"待平台授权"占位正确显示。属合规演示态 |
| **Q1-M5-04：列为 partial（medium）** | Read L130-136 + prd/05 L98 | **维持确认（真实 medium 违规）**。AI 初稿 diff 内容确无角色门控，VL nav 含 projects，违反"未采纳前不对服务商发布"。这是本轮唯一 medium 真违规，必须修复 |
| **Q1-M2-05：批次列表缺 dueTotal（low）** | grep dueTotal + Read L176/L1343 | **维持确认但澄清范围**。报表汇总表(L1343)已有该列；缺口仅在 M2 案件管理批次列表(L176)。不影响"应收总额已在批次详情 KPI 可见"的事实 |
| **Q2-PL/PC：disposeTasksVisible 缺 property 分支（low）** | Read L2612 完整 computed | **维持确认**。provider/range 分支齐全，property 走全量 fallback，与 alertVisible(L2614, 已按 property 过滤) 不对称 |
| **Q2-VL：byCollector 未走 voidStatsVisible（low）** | grep voidStats.byCollector + Read L2562 | **维持确认并加重证据**。不仅 L1333 用原始对象，且 voidStatsVisible 各分支 byCollector 字段本身从未裁剪 — 隔离链双重缺失 |
| 各模块大量 "implemented(占位)" 项 | 抽样 Read（H5 token、app redact、canSeeDetail、reconIn/OutVisible、startReconSettle、batchAllClosed） | **维持 implemented**。核心隔离/门控/状态机逻辑真实存在，占位仅限后端链路（ASR/区块链/支付/定时任务），符合高保真原型定位 |

---

## ⑤ 结论与后续建议

### 结论

- **Q1 总体实现度：97.6 / 96.8**；**Q2 总体角色一致性：97.4**。
- **零功能性缺失**，全部缺口为 partial（信息标注 / 门控收窄 / 列补全 / 引用变量修正）。
- **未全面达到双双 ≥98**，差距集中于 **1 个 medium（M5 AI 初稿越权）+ 约 7 个 low**。M0/M6/M7 已达标，M5 为唯一明显短板。
- 资金双线隔离、结案脱敏、跨服务商/跨物业隔离、业主 H5 最小可见 **四大硬约束全部实地取证通过**。

### 达标修复清单（按优先级，修复后预计全面 ≥98）

1. **【P0·medium】M5 / 缺口 A**：index.html L131-135 AI 新建议 diff `<p>` 内容加 `v-if="can('proj.edit')||['SA','SE'].includes(role)"`，使 VL/CO 看不到未采纳 AI 草稿（修复后 M5 → ≈98/97，VL → 97+）。
2. **【P1·low】缺口 B 残留**：L383/L421 "主动退回公海" 由 `['VL','CO'].includes(role)` 收窄为 `role==='VL'`（BR-M3-28 仅授权服务商负责人）。
3. **【P1·low】缺口 D**：L1333 改引用 `voidStatsVisible.byCollector`，并令 voidStatsVisible 的 provider/range 分支对 byCollector 按服务商过滤。
4. **【P1·low】缺口 E**：L2612 disposeTasksVisible 补 `property` 分支按本物业服务商过滤。
5. **【P2·low】缺口 F**：L832 openSettle 放开 PC 只读（矩阵 PC=○下载，应可页内查看明细）。
6. **【P2·low】缺口 C**：更新 02 矩阵 §7 VL 行为"◐本商+整改回填"，文档与 FW7 实现同步。
7. **【P2·low】缺口 5/8/9/10**：L176 批次列表补"应收总额"列；reconIn 表头补"减免后实收·不含税"口径 note；结算下钻补"付佣归属服务商"列；报表批次汇总补口径一致性 note。
8. **【P3·low】缺口 1/3/4/6/7/G**：T_collector 倒计时列、建议法务有效跟进标记、承诺/政策分期区分文案、submitOrg 重名校验、违约恢复计时 reactive、startReconSettle dataRange 守卫。

### 后续建议

- 上述修复均为 UI 层小改（加 v-if / 补列 / 补 note / 改引用），无架构调整，建议合并为"收敛轮 6（达标补丁）"一次性落地。
- seQcAuthorized 等演示常量在转生产时须接后端授权位，建议在交付清单中列为"原型→生产"待接项，连同 ASR/区块链/支付/定时任务一并标注。
- 矩阵文档（02）应作为 single source of truth，每次 FW 迭代同步更新（本轮发现 FW7 的 VL 整改回填未回写矩阵，易致后续审计误判）。
