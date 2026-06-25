# 有证慧催 UI · 06 — PRD 对齐复评报告（综合 + 对抗校验）

> 评审对象：`/Users/shuo/AI/cuishou/docs/ui/高保真/`（index.html PC 后台 / app.html / h5.html / login.html）
> 基线对照：旧报告 `05-PRD对齐评估报告.md`（改造前总分 67.9）
> 权威口径：`docs/ui/02-角色操作权限矩阵.md`（UI 显隐口径）+ `docs/prd/00~10`（220 条 US/BR）
> 方法：先采纳两份审计（Q1 覆盖 / Q2 角色一致性），再由综合者**亲自 Read/grep 原型对应行号对抗校验**所有 high 项与可疑 over-permission/missing，修正误报/漏报后纳入。
> 复评日期：2026-06-19

---

## 一、执行摘要

"四批差距补齐改造"后，原型在组织管理、操作日志、结算单据、三角色报表、公海实时态、多联系方式、释放/退回/作废原因弹窗、业主 H5 token 门控、无障碍等方面**大幅增强**。核心撮合闭环（建组织→建项目批次→导入案件→派单/公海/抢单→催收作业→法务存证→结案→对账）在 UI 层均可走通，资金双线物理隔离、结案脱敏、token 门控三大护城河约束**实现质量高**。

主要短板集中在**外围与配置类能力**：报表导出（完全缺失）、对账单计费明细聚合、平台员工(SE)/物业协调员(PC) 的若干 nav 入口缺口、若干配置类界面仅 toast 占位、报表聚合表未按数据范围二次过滤。

### 总体评分（百分制）

| 维度 | 本次复评 | 旧基线(05) | 变化 |
|---|---|---|---|
| **Q1 总体 PRD 实现度** | **75** | 67.9 | ▲ +7.1 |
| **Q2 总体角色一致性** | **86** | （旧报告未单列） | — |

> Q1 总体实现度 = 各模块覆盖率按故事数加权（详见第二节模块表，加权后约 74~76，取 75）。
> Q2 角色一致性 = 6 角色一致性得分均值（SA95 / SE82 / PL90 / PC76 / VL86 / CO94 ≈ 87，因报表数据范围二次过滤缺口等系统性 scope 问题统一下调，取 86）。

---

## 二、Q1 逐模块覆盖（已对抗校验后）

| 模块 | 故事数 | 已实现 | 部分 | 缺失 | 覆盖率 | 关键短板 |
|---|---|---|---|---|---|---|
| M0 总纲(CFG+全局约束) | 6 | 5 | 1 | 0 | 82% | 抢单并发为前端 demo（后端原子锁占位） |
| M1 组织与权限 | 11 | 7 | 3 | 1 | 77% | 权限子集勾选/数据范围授权/角色模板配置界面占位；跨组织兼职(BR-M1-13)无视图 |
| M2 项目批次案件主数据 | 12 | 8 | 4 | 0 | 79% | 新建项目表单/减免特批/批次级减免规则/要素化起诉字段集占位 |
| M3 案件流转与公海 | 11 | 4 | 7 | 0 | 73% | 服务商负荷面板指标不全；开放抢单前置无阻断 UI；批量分配/退回无多选 |
| M4 催收作业 | 12 | 7 | 4 | 0 | 77% | 分期承诺录入 UI、逐期兑现/违约标记、上传回款凭证表单缺失 |
| M5 录音转写/AI/质检 | 11 | 5 | 6 | 0 | 66% | 高风险准实时告警推送、处置待办落地、片段时间点跳播、飞轮可视化缺失 |
| M6 存证 | 11 | 7 | 4 | 0 | 79% | 证据材料打包专用 UI（是否存证 toggle/文件清单）缺失；CFG-EVIDENCE 配置项缺 |
| M7 业主缴费 H5 | 3 | 3 | 0 | 0 | 94% | 费用明细未显式标注"减免后应收"差额标签 |
| M8 结案与生命周期 | 6 | 3 | 3 | 0 | 67% | **撤案/坏账结案无受控原因下拉**；自动结清状态机占位；代结案 proxyFor 示例缺 |
| M9 结算与对账 | 8 | 4 | 4 | 0 | 75% | 对账单未聚合计费明细/回款明细；开放抢单付佣前置无强制流程 |
| M10 经营报表 | 4 | 0 | 3 | 1 | 38% | **报表导出完全缺失(BR-M10-08)**；报表聚合表未按数据范围过滤；服务商表现对比缺 |

> 加权覆盖率（按故事数）≈ **75%**。原型占位项（CFG 数值 TBD、STT/存证单价、并发锁、定时任务、区块链上链等后端机制）按"部分实现"计分，不降为缺失。

### Q1 真实缺口清单（按 severity 排序，已校验）

| # | severity | US/BR | 缺口 | UI 证据 | 校验结论 |
|---|---|---|---|---|---|
| 1 | **high** | BR-M10-08 / US-M10-04 | 报表导出功能**完全缺失**，无导出按钮、无导出留痕 | `grep 导出/export` 全文件零命中；reports 视图(L896-1090)无导出入口 | **确认成立**（亲自 grep 验证） |
| 2 | **medium** | BR-M8-03/05 / US-M8-02·03 | 撤案/坏账结案走通用 `askConfirm`，**无受控原因下拉+"其他须填备注"**；对比释放/退回/作废均有真实 `<select>` | `onOp` L1921：`case.close` 仅 askConfirm 文案"请确认已选定结案原因"，无 select；releaseDialog L1415/voidDialog L1569 有真实 select | **确认成立**（读 onOp 源码核实） |
| 3 | **medium** | BR-M9-12 / US-M9-06 | 收佣对账单仅列佣金行，未聚合分钟/短信/存证/法律服务计费明细与回款明细 | reconIn thead 仅"批次/回款基数/比例/应结/状态"(L728)；reconIn 数据 L1693 无计费子项 | 确认成立 |
| 4 | **medium** | BR-M10-06 / US-M10-01·03 | 报表批次汇总/项目下钻表直接迭代全量 `batches`/`projects`，未按 dataRange/provider 二次过滤 | reports 表 `v-for="b in batches"`(注释"所有角色可见" L1048)；reportData 为全局静态对象 L1701，无 computed 过滤 | **确认成立**（读 reports 表源码核实），影响 SE/VL 范围隔离 |
| 5 | medium | BR-M5-07 / US-M5-06 | 高风险准实时告警无推送/红点；处置待办仅 toast 未落地；片段点击未跳播到录音时间点 | qc 看板 L847-856 处置仅 toast；无 bell/独立待办记录 | 确认成立 |
| 6 | medium | BR-M6-08 / US-M6-05 | 证据材料打包无专用对话框（是否存证 toggle、ZIP 文件清单预览） | evidenceRows obj 字段示意"时间线PDF+录音ZIP"(L1766)，无打包 UI | 确认成立 |
| 7 | medium | BR-M4-04/13 | 分期承诺录入 UI（多期金额+到期日）缺失；逐期已兑现/部分兑现/违约标记无可交互 UI | promise 抽屉 L1300-1313 仅 1 条期次展示数据 | 确认成立 |
| 8 | low | BR-M8-01 | 自动结清状态机为占位：case.repay 仅 toast，未联动 status→已结清 | onOp 默认分支 `toast(o.act)` L1921 | 确认成立（原型态可接受） |
| 9 | low | BR-M7-02 | H5 费用明细未显式标注"减免后应收/原应收差额"标签（演示值已是减免后值） | h5.html L277-322 仅本金+滞纳金两行 | 确认成立（轻微） |

> 其余 partial 项（服务商负荷面板指标、批量分配/退回多选、CFG-MARK-CODES/CFG-EVIDENCE 配置入口、要素化起诉字段集、上传凭证表单等）见模块表"关键短板"列，均为可接受的原型占位或局部缺口，归并到对应模块覆盖率扣分。

---

## 三、Q2 角色一致性（6 角色 × 对抗校验后）

| 角色 | 一致性得分 | 高/中危不一致数 | 总体判定 |
|---|---|---|---|
| SA 平台超管 | 95 | 0 | nav/perms 全量覆盖，双线隔离、代操作留痕、scope=all 均正确 |
| SE 平台员工 | 82 | 0 high / 3 medium | perms 与矩阵**单元格**完全一致（非越权）；缺 playbookLib/members/evidence 入口；报表未按 dataRange 过滤 |
| PL 物业负责人 | 90 | 0 high / 1 medium | 资金双线、存证只读、法务只读、作战手册采纳均正确；qc 未按 property 过滤 |
| PC 物业协调员 | 76 | 1 high / 2 medium | perms 与矩阵一致（**非越权，见校验修正**）；缺 qc/reconIn/billing/audit 入口 |
| VL 服务商负责人 | 86 | 0 high / 1 medium | 双线/结案脱敏/coCommission 正确；缺 billing 入口；抢单靠 assign 变通 |
| CO 催收员 | 94 | 0 | 最小权限、结案脱敏双端一致、释放强制原因，全部对齐矩阵 |
| OW 业主 H5 | 96 | 0 | token 门控、隐私最小化、分期渲染、过期不泄露全部正确 |

### Q2 不一致清单（已校验，含类型/PRD 依据/UI 现状/severity/建议）

| # | 角色 | 类型 | PRD/矩阵依据 | UI 现状 | severity | 建议 |
|---|---|---|---|---|---|---|
| Q2-1 | PC | missing-access | 矩阵§7 质检看板 PC=◐本物业；BR-M5-07 PC 是风险处置角色 | PC.nav 仅 6 项(L1653)，无 qc | **high** | 补 qc 入口，按 scope=property 脱敏过滤，闭合风险处置环 |
| Q2-2 | PC | missing-access | 矩阵§6 收佣对账 PC=▲ | PC.nav 无 reconIn(L1653) | medium | 补 reconIn 只读入口（▲ 本物业受限） |
| Q2-3 | PC | missing-access | 矩阵§6 计费明细 PC=○ | PC.nav 无 billing | medium | 补 billing 只读入口 |
| Q2-4 | PC | missing-access | 矩阵§3.1 操作日志 PC=○▲；BR-M1-15 被代方可见 | PC.nav 无 audit | medium | 补 audit 入口，保证代操作透明对 PC 生效 |
| Q2-5 | SE | missing-access | 矩阵§7 平台话术库 SE=●▲ | SE.nav 无 playbookLib(L1648) | medium | 补 playbookLib 入口（范围内） |
| Q2-6 | SE | missing-access | 矩阵§8 成员管理 SE=●▲(若授权) | SE.nav 无 members | medium | 授权态补 members 入口 |
| Q2-7 | SE | missing-access | 矩阵§7 存证证书下载/核验 SE=●▲ | SE.nav 无 evidence | medium | 补 evidence 只读/核验入口 |
| Q2-8 | VL | missing-access | 矩阵§6 计费明细 VL=○本商(仅分钟) | VL.nav 无 billing(L1656)，recharge 仅余额无逐笔 STT 明细 | medium | 补 billing 只读（仅分钟维度） |
| Q2-9 | PL | scope-mismatch | 矩阵§7 质检 PL=◐本物业；BR-M5-07 | qcVisible(L1767) **无 scope='property' 分支**，默认 return 全量 qcRows | medium | 给 qcVisible 增加 property 过滤分支（多物业场景将越界） |
| Q2-10 | SE/VL | scope-mismatch | BR-M10-06 报表按数据范围；BR-M10-03 服务商只见本商 | reports 聚合表迭代全量 batches/projects，无 dataRange/provider 过滤(L1048+) | medium | 报表表用 computed 按 scope 过滤（与 Q1#4 同源） |
| Q2-11 | SE | matrix-divergence | 矩阵§3 摘要"SE 权限同 SA"为**prose 概述**；§3.2 单元格实为 ●▲(assign/dispatch)、余 — | SE.perms=['case.dispatch','case.assign'] **与单元格完全吻合** | low | 无需改 UI；建议修订矩阵摘要措辞避免歧义（见校验修正） |
| Q2-12 | VL | matrix-divergence | 矩阵§5 抢单 VL=●(可) | VL.perms 无 case.grab，靠 case.assign 对无归属案件变通(L347) | low | 语义接近，可接受；如需一键抢可补 case.grab |
| Q2-13 | PC | over-permission?(误报) | 矩阵§3.2 作废 PC=●▲、批次导入 PC=●▲ | PC.perms 含 case.void/batch.import(L1654) | — | **驳回**（见校验修正第①②条） |

### 专项核对结论（按要求逐项）

- **资金双线物理隔离** ✔ 正确：PL 有 reconIn 无 reconOut；VL 有 reconOut/coCommission 无 reconIn；批次详情收佣比例仅 PL/PC/SA/SE 可见、付佣比例仅 VL/SA/SE 可见(L233-234)；短信充值 `role==='PL'||'SA'`(L794)；存证只向物业。无跨线泄露路径。
- **结案脱敏** ✔ 正确：`canSeeDetail`(L1816) + `field()`(L1818) 对 VL/CO(redactClosed=true) 在已结清/撤案/坏账态整页收敛为统计视图并脱敏 phone/name/owner/reduce；app.html(L292)CO 端一致；与矩阵 BR-M8-09 吻合。
- **SE 数据范围** �◑ 部分：visibleCases/kanbanCols/qcVisible/auditVisible 四处 scope='range' 过滤正确(L1823/1835/1767/1768)；**但报表聚合表未二次过滤**(Q2-10)，存在范围越界展示风险。
- **PC/PL 权限边界** ✔ 基本正确：PL=proj.edit/batch.import/case.call/case.reduce/case.close/case.void；PC 含法务/存证/标回款/冲正/撤案/作废/减免/导入——**均与矩阵§3.2/§4 单元格一致**（PC 的 void/import 为 ●▲，非越权）。边界问题仅在 PC 的 4 个只读 nav 入口缺失。
- **CO/VL 私海公海可见性** ✔ 正确：CO myCases 过滤 owner==='王五'&&进行中(L1824)；providerSea 过滤本商进行中无归属(L1822/1825)，open tab 放开 provider 过滤(L1820)；VL 同 scope=provider。grab 校验 CFG-HOLDCAP(L1898)。
- **业主 H5 最小可见** ✔ 正确：bill-view `display:none` 默认隐藏，token 校验通过才填充(h5.html L212)；过期/无效 token 显示 expired-view 且不含任何业主数据(L248+)；底部仅"有证慧催·业主无需登录"，无服务商/催收/跨案信息；姓名脱敏 李\*\*。

---

## 四、校验修正记录（被对抗校验推翻 / 修正的项）

> 综合者亲自 Read 矩阵 §3.2/§4/§5/§6/§7/§8 与 index.html L1642-1661、L1767-1836、L1921，对原审计的高危/可疑项逐条复核。

| # | 原审计结论 | 校验取证 | 修正后结论 |
|---|---|---|---|
| ① | **【推翻】** Q1 notes 标 "P0 高危：PC 持有 case.void = over-permission（BR-M2-17 PL 专属）" | 矩阵§3.2"作废（仅待派单）" 行 PC=**●▲**（非 —）；矩阵是 UI 显隐权威口径 | **误报，驳回**。PC 持 case.void 符合矩阵。PRD 02 正文 BR-M2-17 仅写"负责人操作"系措辞未含▲条件态，属 PRD↔矩阵口径差，应以矩阵为准。降级为"建议同步 PRD 措辞"。 |
| ② | **【推翻】** Q1 notes 标 "P0 高危：PC 持有 batch.import = over-permission（BR-M2-11 PL 专属）" | 矩阵§4"批次导入向导" 行 PC=**●▲** | **误报，驳回**。同①。BR-M2-11 限定的是"项目档案创建/编辑"(proj.edit)，PC 确无 proj.edit；批次导入 PC 有 ●▲ 权。 |
| ③ | **【修正/降级】** Q2 标 "SE perms 仅 2 项 vs 矩阵'同 SA' = matrix-divergence(high)" | 矩阵§3.2 SE 列逐格为 ●▲(assign/dispatch)、余皆 —；"同 SA"出现在 §3 散文导语 | **降为 low**。UI 与矩阵**单元格**完全一致，非真实分歧；问题在矩阵导语措辞宽松。建议修订矩阵导语，不需改 UI。 |
| ④ | **【维持/确认】** Q1 标 M10-04 报表导出缺失(high) | `grep -n 导出/export` 全 index.html 零命中 | **成立**，列为头号 high。 |
| ⑤ | **【维持/确认】** Q1 标 M8 结案无受控原因下拉(medium) | 读 `onOp` 源码：case.close 仅 askConfirm，无 select；对照 release/void 有真实 select | **成立**。 |
| ⑥ | **【维持/确认】** Q2/Q1 标 PL qcVisible 无 property 分支、报表无 dataRange 过滤(scope-mismatch) | 读 L1767 qcVisible（仅 provider/range 分支）、L1048 reports 表（迭代全量） | **成立**，合并为 Q2-9 / Q2-10。 |
| ⑦ | **【维持】** Q2 标 PC 缺 qc/reconIn/billing/audit、SE 缺 playbookLib/members/evidence、VL 缺 billing | 逐一比对矩阵§6/§7/§8 单元格 vs 各 role.nav(L1648/1653/1656) | **全部成立**（Q2-1~Q2-8）。 |
| ⑧ | **【确认无误报】** 结案脱敏 / 资金双线 / H5 token 门控 | 读 canSeeDetail/field/visibleCases/h5 display:none | 三大护城河约束实现正确，无遗漏、无误判。 |

**误报净化结论**：原审计 2 条 P0 over-permission（PC void/import）为**误报**已驳回；1 条 high matrix-divergence（SE）**降级为 low**。这三项修正避免了对 RBAC 实现的错误问责。其余 high/medium 经源码取证后**全部确认成立**，无漏报新增。

---

## 五、结论与后续建议

### 结论
原型经"四批补齐"后 PRD 实现度由 67.9 提升至约 **75**，角色一致性约 **86**。核心闭环与三大隔离护城河（资金双线 / 结案脱敏 / H5 token）实现质量高，无真实越权路径。缺口集中在**报表导出、对账单计费明细聚合、若干 nav 只读入口、配置类界面占位、报表/质检的数据范围二次过滤**——多为外围与可视化层，非主干逻辑缺陷。

### 后续建议（按优先级）
1. **P0** 补报表导出(BR-M10-08)：导出按钮 + 隔离/权限约束 + 导出留痕（操作人/时间/范围）。
2. **P1** 撤案/坏账结案补受控原因 `<select>`+"其他须填备注"（复用 releaseDialog 模式）。
3. **P1** 补 nav 入口：PC(qc/reconIn/billing/audit)、SE(playbookLib/members/evidence)、VL(billing)。
4. **P1** qcVisible 增 scope='property' 过滤分支；reports 聚合表改 computed 按 dataRange/provider 过滤（消除 SE/PL/VL 范围越界）。
5. **P2** 对账单聚合计费明细+回款明细；证据材料打包专用 UI（是否存证 toggle/文件清单）；分期承诺录入与逐期兑现/违约标记 UI；高风险告警推送+处置待办落地。
6. **文档** 同步修订：BR-M2-17 措辞补充 PC ●▲；矩阵§3 "SE 同 SA" 导语改为"SE 列见各单元格、默认仅 assign/dispatch+数据范围"，消除歧义。

---
*本报告以当前文件实际为准重新评估，未照搬旧基线；所有 high 项与可疑 over-permission/missing 均经源码行号取证。*
