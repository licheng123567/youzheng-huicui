# 有证慧催 · 03 案件 CRM 重构方案（前端高保真原型）

> 角色：首席交互架构师产出 / 执行人：sonnet（1:1 还原）
> 标的文件：`/Users/shuo/AI/cuishou/docs/ui/高保真/index.html`（Vue3 global，`../vendor/vue.global.prod.js`，无 Element Plus）、`/Users/shuo/AI/cuishou/docs/ui/高保真/assets/ds-admin.css`
> 依据：现行 PRD（`docs/prd/00~10` 共 11 模块）+ `01-页面清单与信息架构.md` + `02-角色操作权限矩阵.md` + 技术选型。
> 强约束：6 角色切换器保留（SA/SE/PL/PC/VL/CO）；菜单按角色显隐、操作按权限点显隐、字段按隔离/脱敏（结案收敛 BR-M8-09）；视觉沿用 ds-admin；内联 JS 必须过 `node --check`；不破坏现有可运行视图与角色切换；不照抄 uiv4。

---

## 0. 现状盘点（执行前必须理解）

现有 `index.html` 已是单文件 Vue3 应用，`setup()` 中关键结构如下（sonnet 改造时复用，勿重写）：

- `ic`：SVG path 字典（图标）。
- `navMeta`：`{viewKey:{label,icon}}` 视图元数据，菜单/面包屑/tagsView 共用。
- `roles`：6 角色对象，每个含 `{label,user,scope,scopeLabel,redactClosed,nav[],perms[]}`。`nav` 是字符串数组（夹 `{group:'…'}` 分组）。
- `OPS`：案件操作权限点数组 `{p,label,act,cls}`，`caseOps` 按 `can(o.p)` 过滤渲染。
- 数据 mock：`cases / projects / batches / reconIn / reconOut / cfgs / members / playbookLib / qcRows / evidenceRows / billingRows / smsRows`。
- 响应式 `st`：`role/view/caseMode/caseTab/selectedId/selProj/selBatch/importStep/inCall/showAfter/callMark/aiCards/tags/dispatchPool`。
- 计算/方法：`can / c / canSeeDetail / field（脱敏）/ visibleCases（scope 过滤）/ stageOf / kanbanCols / navItems / crumb / caseTabs / caseOps / kpis / todos / stTag / toast / pushTag / navTo / closeTag / openCase / onRole / grab / onOp / riskColor / startCall / hangup / adoptCard / dismissCard`。
- 已有视图：`workbench / projects(+档案) / batches(+详情) / import / cases|myCases|providerSea / dispatch|platformSea / caseDetail(CO三栏 + 其他角色Tab版) / legal / reconIn|reconOut / recharge / billing / aiPlaybook / playbookLib / qc / evidence / sms / settings / members`。
- 已有 CSS：`.case3`（三栏 grid `300px 1fr 350px`）、`.kanban/.kcol/.kcard`、`.drawer/.mask/.dialog`、`.tl`（时间线）、`.chat`（气泡）、`.aicard/.riskbar/.bgbox`、`.callbar/.transcript`、`.dtabs`、`.kpis/.kpi`、`.tag-pick`、`.steps`、`.desc` 等。

**重构哲学**：以"案件"为核心实体，把现有零散视图收敛为 CRM 三视图（列表/看板/详情）+ 活动时间线 + 管道阶段；案件详情页升级为"工作台"。最大化复用上面已有的结构与 CSS，只做**增量增强**，不推倒重做。

---

## A. CRM 信息架构总则（案件为核心实体）

### A.1 实体与三视图映射

| CRM 概念 | 本系统落地 | 现状/改动 |
|---|---|---|
| 实体（Entity） | **案件** = `cases[]` 每一项（业主×批次，BR-M2-10 同业主多案=多记录） | 已有 |
| 列表视图（List） | 案件列表（表格）/ 我的案件 / 公海 | 已有，需挂到「批次明细」之下 |
| 看板视图（Kanban） | 管道阶段泳道（`.kanban`），拖拽=阶段流转 | 已有 `kanbanCols`，需补「列表/看板/日历」段控与拖拽留痕提示 |
| 详情视图（Detail）=工作台 | `caseDetail` 三栏（画像 / 时间线·资料·手册 / 操作·AI） | 已有 CO 三栏 + 其他角色 Tab 版，**本次统一为全角色三栏** |
| 活动时间线（Activity Timeline） | 中栏「全时间段」Tab：通话/转写/手填记录/工单/状态变更/承诺 混排倒序 | 新增（最关键） |
| 管道阶段（Pipeline Stage） | `stageOf()` → `KSTAGES`：待派单→服务商公海→进行中→承诺缴费→已结清→已撤案/坏账 | 已有，复用 |

### A.2 五条 CRM 交互原则（融合调研结论，全局执行）

1. **最小化页面跳转**：相关录音/单据/证书/缴费单一律走**右侧抽屉**（`.drawer/.mask`），不新开视图、不离开案件详情。内联编辑优先于对话框。
2. **活动驱动流程**：时间线即检查点；任何写动作（标注/承诺/工单/状态变更）都**自动生成一条时间线记录**并可推进管道阶段。
3. **视觉分层**：优先级/超期/风险用 `.tag`（dan/war/pri/suc/inf）与左边框色（`riskColor`）突出，降低认知负荷。
4. **快捷操作前置**：常用动作（拨号/标注/承诺/缴费码）固定在详情右栏顶部或底部 `quickbar`，不进二级导航。
5. **导航主轴一致**：项目→批次→案件贯穿撮合/公海/报表（BR-M2-07）；案件入口=**批次优先**（见 B）。

---

## B. 各角色左侧主菜单树（依 PRD 逐角色，含显隐与导航链路）

> 实现方式：沿用 `roles[role].nav`（字符串数组 + `{group}`）。下表给出每个角色 `nav` 的**目标值**，sonnet 直接替换 `roles` 中对应 `nav`。新视图 key 需同时在 `navMeta` 注册 `{label,icon}`。

### B.1 导航链路总则：案件入口=批次优先（关键改动）

**现状问题**：`cases`（案件管理）菜单直接平铺全部案件，违背"项目→批次→案件"主轴与用户要求 5。

**目标链路**：
```
案件管理(菜单) → 批次列表(默认呈现，不再平铺案件) → 点某批次 → 批次明细(聚合指标+案件表) → 点某案件 → 案件详情(三栏工作台)
```

实现要点：
- `cases` 视图改造为**批次优先**：进入 `cases` 时若 `selBatch` 为空 → 渲染「批次列表卡片」（复用 `batches` 数据 + scope 过滤）；选中批次后 `selBatch=b` → 渲染「批次明细（聚合 KPI + 案件明细表）」；点案件 `openCase(id)` → `caseDetail`。
- **去重「批次管理」与「案件管理」**：二者数据同源。约定——
  - `projects`（项目数据/项目管理）：项目档案维护（合同/收费标准/收款/作战手册/减免规则），是"催收依据"的家。
  - `batches`（批次管理）：批次级聚合视图 + 导入入口 + 收付佣比例维护 + **批次级作战手册**（见 E）。SA/PL/PC 保留独立「批次管理」菜单用于批次维度运营。
  - `cases`（案件管理）：**批次优先**的案件钻取入口（批次列表→明细→案件）。
  - 三者通过 `selProj/selBatch/selectedId` 三级下钻状态串联，菜单不重复堆叠功能。
- CO/VL 无「批次管理」独立菜单（不做批次运营）；CO 案件入口=`myCases`(私海) + `providerSea`(公海)，VL=`cases`(本商，批次优先) + `providerSea`。

### B.2 平台超管 SA（scope=all，redactClosed=false）

```
[业务] 工作台 / 撮合派单 / 平台公海 / 项目数据 / 批次管理 / 案件管理
[能力] 平台话术库 / 质检风控 / 存证管理
[财务] 收佣对账 / 付佣对账 / 计费明细 / 充值中心 / 短信通道
[系统] 经营报表 / 成员管理 / 参数配置
```
nav 值：`[{group:'业务'},'workbench','dispatch','platformSea','projects','batches','cases',{group:'能力'},'playbookLib','qc','evidence',{group:'财务'},'reconIn','reconOut','billing','recharge','sms',{group:'系统'},'reports','members','settings']`
> 改动：移除独立 `aiPlaybook`（作战手册并入项目/批次，见 E）。新增 `reports`（已有视图占位，可保留现有 reports 占位或先复用 KPI）。

### B.3 平台员工 SE（scope=all + 数据范围▲，redactClosed=false）

```
工作台 / 撮合派单 / 项目数据 / 批次管理 / 案件管理 / 质检风控 / 经营报表
```
nav：`['workbench','dispatch','projects','batches','cases','qc','reports']`
> 菜单与 SA 同构但裁剪（无对账/系统/话术库本体编辑）；所有列表叠加"数据范围内"提示（沿用 `scopeLabel`）。

### B.4 物业负责人 PL（scope=property，redactClosed=false）

```
[业务] 工作台 / 项目管理 / 批次管理 / 案件管理 / 质检风控 / 存证管理
[财务] 收佣对账 / 计费明细 / 充值中心 / 短信通道
[管理] 经营报表 / 成员管理
```
nav：`[{group:'业务'},'workbench','projects','batches','cases','qc','evidence',{group:'财务'},'reconIn','billing','recharge','sms',{group:'管理'},'reports','members']`
> 改动：移除独立 `aiPlaybook`（并入项目档案，PL 在项目档案内维护手册）。

### B.5 物业协调员 PC（scope=property，redactClosed=false）

```
工作台 / 项目数据 / 批次管理 / 案件管理 / 法务工作区 / 存证管理
```
nav：`['workbench','projects','batches','cases','legal','evidence']`
> 改动：移除独立 `aiPlaybook`（项目档案中只读调阅）；新增 `legal` 入口（PC 主导法务，原仅从案件页进入，给个一级入口更顺）。PC 项目档案为只读（BR-M2-11 仅 PL 可维护）。

### B.6 服务商负责人 VL（scope=provider，redactClosed=true）

```
[业务] 工作台 / 服务商公海 / 案件管理 / 质检风控
[财务] 付佣对账 / 充值中心
[管理] 经营报表 / 成员管理
```
nav：`[{group:'业务'},'workbench','providerSea','cases',{group:'财务'},'reconOut','recharge',{group:'管理'},'reports','members']`
> 改动：移除独立 `aiPlaybook`（在案件详情中栏只读调阅作战手册）；`cases` 为批次优先（本商范围）。

### B.7 催收员 CO（scope=provider，redactClosed=true）

```
今日看板(工作台) / 我的案件 / 服务商公海 / 开放抢单
```
nav：`['workbench','myCases','providerSea','openSea']`
> 改动：移除独立 `aiPlaybook`（在案件详情中栏只读调阅）；新增 `openSea`（开放抢单，IA §FLOW 跨服务商开放案件，区别于本商公海）。CO 无批次/项目菜单——其作业核心是私海+公海+今日看板，案件详情中栏可调阅项目资料/手册。

### B.8 navMeta 需新增/确认的 key

| key | label | icon | 说明 |
|---|---|---|---|
| `reports` | 经营报表 | report | 已在 navMeta 缺失，需补（视图可先做 KPI 占位） |
| `openSea` | 开放抢单 | sea | CO 跨商开放案件（复用 providerSea 渲染 + 过滤 `cs.open===true`） |
| `legal` | 法务工作区 | stamp | 已有视图，已在 navMeta，确认 PC 一级入口可用 |

---

## C. 各角色「今日看板」规格

### C.1 放置方式决策（明确选择）

- **工作台首屏 = 今日看板主场**：工作台 `workbench` 顶部保留 KPI 卡（`kpis`），其下「待办」卡升级为**可点击的「今日清单」**（点条目直达案件详情，连续作业）。理由：每个角色登录默认落地工作台，首屏即今日应办，符合"任务驱动"。
- **案件详情左栏不放今日清单**（避免与画像争位）；改为在详情**顶部返回区**保留"上一条/下一条"连续作业按钮（从进入详情时的清单上下文取），实现"清单→详情→下一条"流。

### C.2 今日看板数据规格（按角色，扩展现有 `todos`）

现有 `todos` 仅返回 `{t,n,lv,tg}`（文案/数量/级别/标签），**不可点**。改造：每条增加 `cases:[id...]`（关联案件 id 列表）或 `jump:'view'`（跳转视图），点击：单案→`openCase(id)`；多案→`navTo` 到过滤后的列表。

| 角色 | 今日应办清单项（PRD 依据） |
|---|---|
| CO | 承诺今日到期(dan, BR-M4-13)→点击进承诺案件 / 临近自动释放(war, T_collector BR-M4-03)→进案件 / 工单待回执(war, BR-M4-17) / 新分配·抢到案件(pri, BR-M4-20) / 建议法务待跟进(pri, BR-M4-05a) |
| PC | 法务待办·待送达·待签收(dan, M6/BR-M4-18)→进法务工作区 / 线下回款待标(pri, BR-M4-07) / 工单待处理回执(war) / 缴费链接待发(inf, BR-M4-19) |
| VL | 公海滞留临近T2(dan, M3)→进公海 / 付佣对账待确认(war, M9) / 分钟低余额(pri, BR-M5/M9) / 待分配案件(war, M3) |
| PL | 减免/特批待批(dan, BR-M2-18)→进案件 / 收佣对账待确认(war, M9) / 最长催收周期提醒(pri, 两类:无回款/有回款未结清, BR-M8-08)→进案件 / 作战手册可更新提示(inf, BR-M5-05) |
| PC/PL 共 | — |
| SA | 待派单超时(dan, T1, M3)→进派单工作台 / 平台公海积压(war)→进平台公海 / 对账待出单(war, M9) / 代办请求(pri, BR-M1-15) |
| SE | 范围内待派单(war)→进派单(范围内) / 范围内质检待看(war) |

> 实现：`todos` 的 map 数组每项追加第 4 项 `[文案,数量,级别,跳转]`，`jump` 取 `view key` 或 `'case:1'` 形式；模板里 `@click="todoJump(td)"`，新增 `todoJump` 方法解析。`.tl .e` 加 `cursor:pointer` 态（已有 `.row-click` 可借）。

---

## D. ★案件详情三栏增强规格（最关键，全角色统一为三栏）

> 现状：CO 是三栏（画像/通话转写/AI），其他角色是 `.card+.dtabs` 平铺 Tab 版。**本次统一**：所有角色都用 `.case3` 三栏布局，差异仅在右栏操作区（权限）与脱敏（`field/canSeeDetail`）。结案收敛（VL/CO）仍走「结案统计视图」（保留现有 `!canSeeDetail` 分支，收敛为仅统计）。

### D.0 三栏总布局与通话态共存

- 复用 `.case3`（grid `300px 1fr 350px`，≤1280px 单列）。
- 顶部保留案件头条（业主·房号·批次·状态 tag + 返回 + 上一条/下一条）。
- **通话态（`inCall`）与平时共存策略**：
  - 中栏顶部固定 `.callbar`（拨打/通话中/挂断），**始终可见**。
  - 平时中栏主体 = 三 Tab（全时间段 / 项目资料 / 作战手册）；**接通后中栏主体自动切到"实时转写流"**（`.transcript`），但 Tab 头仍在，催收员可随时点回「作战手册」边打边看（用户要求"打电话时这些都要可见"）。
  - 右栏：平时=操作区（按权限）；通话态=操作区上方插入「AI 实时建议」（`ai-head/ai-body`，已有），底部 `quickbar` 始终在。
  - 挂断后中栏弹「通话小结/标注」（`.aftercall`，已有），保存后写入「全时间段」时间线。

### D.1 左栏 · 用户画像（保留打磨，字段清单）

复用 `.portrait-top/.ptags/.pstats/.arrears/.sec-title/.tl`。字段：

| 区块 | 字段 | 数据来源 |
|---|---|---|
| 身份头条 | 头像(首字, `riskColor` 按金额)、姓名、房号·手机(脱敏 `field`)、应收金额·欠费月数 | `cases` 项 |
| 画像标签 | 爽约历史 / 投诉型 / 可联系 / 首次触达 / 老人户 等 `.tag` | mock(后期 BR-M5 画像) |
| 关键统计 | 联系次数 / 承诺次数 / 工单数（`.pstats`） | 聚合时间线 |
| 欠费详情 | 欠费月份区间、物业费、滞纳金、合计（`.arrears`） | 案件应收构成 |
| 同业主提示 | "同业主另有 N 件在催"（BR-M4-21，受隔离：CO 仅本商范围，跨商不展示明细） | `alert warn` |
| 履约/承诺 | 最近承诺：6/20 ¥1200 待兑现（小卡） | 承诺数据 |

### D.2 中栏 · 三 Tab（核心增强）

中栏顶部用 `.dtabs`（已有）做三 Tab：`midTab ∈ {timeline, project, playbook}`。新增响应式 `midTab:'timeline'`。

#### Tab 1 ·「全时间段」活动时间线（midTab==='timeline'，默认）

**要求**：混排该案**全部记录**，按时间**倒序**，每条可点→右抽屉。复用 `.tl`，每条 `.e` 增加类型图标/色与 `@click="openDrawer(item)"`。

记录类型（`type`）与点击行为：

| type | 时间线展示 | 点击右抽屉内容 |
|---|---|---|
| `call`（通话录音） | 06-15 14:22 · 通话03:48 · 承诺还款 · 王五 | 抽屉：录音播放器(进度条/0.5x/1x/下载) + 说话人分离气泡转写全文(`.chat`) + 通话后 AI 策略建议(`alert info`) |
| `transcript`（AI转写） | 与 call 合并展示（call 条目里"查看转写"链接） | 同上转写气泡 |
| `note`（催收员手填沟通记录） | 06-14 · 手记：业主称月底发工资 · 王五 | 抽屉：备注全文 + 编辑（持有人可改） |
| `ticket`（工单） | 06-13 · 工单[上门核实] 待回执 · 王五→协调员 | 抽屉：工单详情(类型/描述/附件/回执结果) |
| `sms`（缴费单/短信） | 06-10 · 发送催费单(短信) · 成功 | 抽屉：缴费单详情(金额=减免后应收/有效期/送达方式/状态) |
| `promise`（承诺） | 06-15 · 登记承诺 6/20 全额¥1200 | 抽屉：承诺单(单笔/分期表 + 兑现状态) |
| `status`（状态变更） | 06-08 · 抢单获得→进行中 · 王五 | 抽屉：变更详情(前后状态/操作人/原因) |
| `legal`（法务/建议法务） | 06-12 · 建议法务标注(不移案) · 王五 | 抽屉：法务进度(文书/送达/签收/存证 steps) |
| `evidence`（存证） | 06-16 · 送达存证·律师函签收照 | 抽屉：存证证书(哈希/下载/核验二维码) |

时间线顶部加**类型筛选段控**（复用 `.segctrl` 或一排 `.tag-pick`）：全部 / 通话 / 跟进 / 工单 / 承诺 / 法务存证。数据：新增 `timeline` mock 数组（见 F 数据补充），`timelineFiltered` computed 按筛选 + 倒序。

数据示例（mock）：
```
[ {id:1,type:'status',tm:'2026-06-08 09:00',title:'抢单获得，分配给王五',who:'王五',from:'公海',to:'进行中'},
  {id:2,type:'sms',tm:'2026-06-10 10:01',title:'发送催费单(短信)',who:'王五',status:'成功',amt:1080,expire:'7天'},
  {id:3,type:'call',tm:'2026-06-15 14:22',title:'通话 03:48 · 承诺还款',who:'王五',dur:'03:48',result:'承诺缴费',hasTranscript:true},
  {id:4,type:'promise',tm:'2026-06-15 14:25',title:'登记承诺：6/20 全额 ¥1200',who:'王五',date:'2026-06-20',plan:'单笔',state:'待兑现'},
  {id:5,type:'note',tm:'2026-06-14 16:30',title:'手记：业主称月底发工资可缴',who:'王五'},
  {id:6,type:'ticket',tm:'2026-06-13 11:00',title:'工单[上门核实] 待回执',who:'王五',tkType:'上门核实',state:'待回执'},
  {id:7,type:'legal',tm:'2026-06-12 15:00',title:'建议法务标注(不移案)',who:'王五'} ]
```

#### Tab 2 ·「项目资料」（midTab==='project'，只读）

调阅该案所属**项目/批次**资料（用户要求：项目详情/合同/收费标准=收费依据）。复用 `.desc/.sec-title`。内容块：

| 区块 | 字段（来源 M2 §2.1） | 可见性 |
|---|---|---|
| 项目档案 | 小区名、区域、物业合同(HT编号) | 全角色只读 |
| 合同情况 | 物业服务合同、服务期限 | 只读 |
| 收费标准=收费依据 | 缴费周期 & 物业费标准（**文字描述，非金额计算源** BR-M2-12）、费项构成 | 只读(催收依据) |
| 收款信息 | 物业对公账户/微信收款码（业主缴费通道，BR-M4-19） | 只读(催收依据) |
| 减免规则 | 满3期减滞纳金/最多分3期（项目级底盘，批次可覆盖 BR-M2-18a） | 只读 |
| 批次信息 | 批次号、案件数、回款率、收/付佣比例（**按隔离显隐**：物业看收佣、服务商看付佣，CO 都不看） | ▲隔离 |

> 服务商（VL/CO）此 Tab **只见催收依据**，**佣金/对账区块不渲染**（BR-M1-06），与现有项目档案分支逻辑一致。

#### Tab 3 ·「作战手册」（midTab==='playbook'）

**两块结构**（用户核心要求）：

**① 物业静态资料（随项目/批次走，物业维护，此处只读调阅）** — 复用 `.note/.sec-title`：
- 现行作战手册版本（如 v3 现行）+ 来源标注"本手册随项目/批次维护，案件详情只读调阅"。
- 内容：开场白 / 异议处理 / 承诺锁定 / 老人户要点 / 本小区特点·历史难点（物业录入，BR-M5-05①）。

**② AI 动态「沟通策略与注意事项」（动态生成）** — 复用 `.aicard/.bgbox/.riskbar`：
- 来源标注"AI 据本案历史沟通+画像+话术库+作战手册实时生成（BR-M5-04），仅建议、不强制"。
- 内容卡片：
  - 背景摘要 `.bgbox`：欠费¥X(N月)·历史通话M次·上次接触日期·承诺兑现率。
  - 注意事项 `.riskbar`：如"该户投诉型，先共情再谈缴费""上次承诺违约，本次需锁定书面"。
  - 策略卡 `.aicard`：异议识别 / 话术推荐 / 下一步动作（带置信度 tag），可"采纳/忽略"（复用 `adoptCard/dismissCard`）。

> 静态 vs 动态分两个 `.sec-title` 区块上下排列。打电话时（通话态）此 Tab 仍可点回查看（D.0）。

### D.3 右栏 · 操作区（按权限）+ 通话态 AI + 右抽屉定义

右栏复用 `.case3 .col.right`，分三段（上→下）：

1. **通话态 AI 实时建议**（仅 `inCall` 时显示）：`ai-head`+`ai-body`（背景框+L1/L2风控条+策略卡），已有，保留。
2. **操作区（始终显示，按权限点 `can(p)` 渲染）**：用 `caseOps`（已有，`OPS.filter(can)`）。各角色按 §02 矩阵自然得出：
   - CO：拨号(case.call) / 标注跟进(case.mark) / 登记承诺(case.promise) / 发催费单(case.link) / 建议法务(case.suggestLegal) / 释放(case.release)。
   - PC：拨号 / 登记承诺 / 发催费单 / 标线下回款(case.repay) / 冲正(case.reverse) / 发起法务(case.legal.start→`go:legal`) / 存证(case.evidence) / 减免审批(case.reduce) / 撤案坏账(case.close) / 作废(case.void)。
   - PL：拨号 / 减免审批 / 撤案坏账。
   - VL：分配(case.assign) / 释放(case.release)。
   - SA：全部以"代操作"渲染（`act` 文案带"代"，toast"已记录操作日志" BR-M1-15）。
   - SE：分配/派单类（数据范围内）。
3. **快捷条 `quickbar`**（底部固定）：发缴费码 / 建工单 / 标记承诺 / 建议法务（CO 场景）；其他角色按权限替换为其高频动作。

**右抽屉（`.drawer/.mask`）统一定义**——所有"查看相关录音/单据"都走右抽屉，不跳页：
- 新增响应式 `drawer:{open:false, kind:'', data:null}`，方法 `openDrawer(item)`（按 `item.type` 设 `kind`）、`closeDrawer()`。
- 抽屉内容按 `kind` 分支渲染（v-show / v-if）：
  - `kind==='call'`：录音播放器条 + `.chat` 转写气泡全文 + 通话后 AI 策略 `alert info`（含"分钟余额不足暂停解析"提示 BR-M4-02）。
  - `kind==='ticket'`：工单详情（类型/描述/附件/回执）。
  - `kind==='sms'`：缴费单详情（金额=减免后应收/有效期/送达方式/状态/重发·作废 BR-M4-14）。
  - `kind==='evidence'`：存证证书（场景/对象/上链哈希/下载/核验二维码）。
  - `kind==='promise'`：承诺单（单笔/分期表 + 逐期兑现状态）。
  - `kind==='legal'`：法务进度 `.steps`（生成文书→送达→签收→存证）。
  - `kind==='status'/'note'`：变更/备注详情。

### D.4 结案收敛视图（VL/CO，`!canSeeDetail`）

保留并强化现有 `!canSeeDetail` 分支：进入即「结案统计视图」（P-CLOSE-02），三栏收敛为单卡——仅显示统计聚合（件数/回款额/回款率/佣金/绩效），业主信息/联系方式/跟进/录音/法务**全部不渲染**，顶部 `alert err` 提示 BR-M8-09。SA/SE/PL/PC 结案后仍见全明细三栏。

---

## E. 作战手册并入项目/批次（从独立菜单移除）

### E.1 移除独立菜单
- 所有角色 `nav` 中删除 `aiPlaybook`（见 B 各角色 nav 已删）。
- `view==='aiPlaybook'` 视图**保留为组件片段但不再由菜单进入**——改为在「项目档案」与「批次详情」内嵌调用（或将其内容直接搬入两处）。`navMeta.aiPlaybook` 可保留（被内嵌引用）。

### E.2 项目档案内新增「作战手册」块（PL 维护）
在 `view==='projects' && selProj` 分支，「催收依据」描述列表下方新增 `.sec-title 作战手册` 区块：
- 现行手册（静态文本，PL 可编辑/采纳；其他角色只读）。
- AI 新建议 diff（`+新增/~修改`，逐条采纳，复用现有 aiPlaybook 的 diff UI，PL `can('proj.edit')` 才显示采纳/忽略，BR-M5-05）。
- 版本标签 `v3 现行` + "增量重生成(diff)" 按钮（`can('proj.edit')`）。

### E.3 批次详情内新增「作战手册（批次级）」块
在 `view==='batches' && selBatch` 分支新增 `.sec-title 作战手册(批次级)`：
- 说明"批次级可覆盖项目级（BR-M2-18a 同理，手册随批次走）"。
- 展示该批次特定话术/注意事项（静态），PL/PC 可维护（按权限），VL/CO 只读。

### E.4 案件详情中栏调阅（见 D.2 Tab3）
案件三栏中栏「作战手册」Tab = 静态（项目/批次手册只读）+ AI 动态策略。**不在此编辑**，编辑入口在项目/批次。

---

## F. 落地改造清单（给 sonnet 执行，分三段）

> 全程：每段改完跑 `node --check`（提取 `<script>` 内联块校验）；不破坏角色切换与已有视图；新 CSS 加到 `ds-admin.css` 末尾。

### SEG-1 · IA / 今日看板 / 批次优先（先做，风险低）

**index.html · JS**
1. `navMeta`：新增 `openSea:{label:'开放抢单',icon:'sea'}`，确认 `reports:{label:'经营报表',icon:'report'}`（缺则补）。
2. `roles`：按 §B.2~B.7 替换 6 角色的 `nav` 数组（删 `aiPlaybook`；CO 加 `openSea`；PC 加 `legal`；SA/SE/PL/VL 调整分组）。**不动 perms**。
3. `todos`：每项数组追加跳转目标 `[文案,数量,级别,jump]`（`jump` = view key 或 `'case:1'`）；新增方法 `todoJump(td){ if(!td.jump)return; if(td.jump.startsWith('case:'))openCase(+td.jump.slice(5)); else navTo(td.jump);} `。
4. `cases` mock 增加字段：`open:false`（开放抢单标记，给 1~2 条 `open:true`）。
5. `visibleCases`：增加 `if(st.view==='openSea') l=l.filter(x=>x.open);`（开放抢单跨商可见，复用 providerSea 渲染）。
6. 案件入口批次优先：新增响应式无需（复用 `selBatch`）。改 `navTo`：进入 `cases` 时重置 `selBatch=null`（已有逻辑会重置）。

**index.html · 模板**
7. 工作台「待办」卡：`.tl .e` 改为可点 `@click="todoJump(td)"` + `cursor:pointer`，并显示 `›` 指示。
8. 案件管理 `view==='cases'`：包成**批次优先**——
   - `v-if="view==='cases' && !selBatch"`：渲染批次列表（复用 batches 表格，行 `@click="selBatch=b"`）。
   - `v-if="view==='cases' && selBatch"`：渲染批次明细（聚合 KPI + 案件明细表，复用现 `batches&&selBatch` 段的结构）+ 段控（列表/看板）。
   - 原 `['cases','myCases','providerSea']` 合并模板**拆分**：`myCases/providerSea/openSea` 仍直接平铺案件表（私海/公海无需批次层）；`cases` 走批次优先。
9. `openSea` 复用 `providerSea` 渲染分支：把 `v-if="['cases','myCases','providerSea'].includes(view)"` 改为含 `openSea`，并在抢单按钮条件里允许 openSea。

**ds-admin.css**
10. `.tl .e.clickable{cursor:pointer;} .tl .e.clickable:hover{color:var(--primary);}`（今日清单/时间线可点态）。

`node --check` 通过 → SEG-1 完成。

### SEG-2 · 三栏增强 / 作战手册并入（核心）

**index.html · JS**
11. 新增响应式：`midTab:'timeline'`、`tlFilter:'all'`、`drawer:reactive({open:false,kind:'',data:null})`。
12. 新增 mock：`timeline`（见 D.2 数组）；`projectInfo`（项目资料字段对象）；`playbookStatic`（手册静态文本数组）；AI 动态复用 `aiCards`。
13. 新增 computed：`tlFiltered`（按 `tlFilter` 过滤 + 时间倒序）。
14. 新增方法：`openDrawer(item){ st.drawer.kind=item.type; st.drawer.data=item; st.drawer.open=true; }`、`closeDrawer(){ st.drawer.open=false; }`。
15. 把"其他角色 Tab 版 caseDetail"**统一为三栏**：合并 `caseDetail` 两个分支为一个三栏布局（CO 与其他角色共用 `.case3`），差异由 `caseOps`(权限) 与 `field/canSeeDetail`(脱敏) 自然产生。`!canSeeDetail` 仍走结案统计单卡（D.4）。
    > 实现策略：保留现 CO 三栏作为基底，把左栏画像、中栏三 Tab、右栏操作区做成对所有 `canSeeDetail` 角色生效；右栏操作区用 `caseOps` 而非写死 CO 按钮（CO 自然得到其 6 个权限点）。

**index.html · 模板（案件详情三栏）**
16. 中栏：`.callbar`(保留) + `.dtabs`(三Tab: 全时间段/项目资料/作战手册) + 三个 `v-show` 面板：
    - timeline：筛选段控 + `.tl` 循环 `tlFiltered`，每条 `@click="openDrawer(item)"`，类型用 `.tag` 着色。
    - project：`.desc` 渲染 `projectInfo` + 批次收/付佣按隔离 `v-if`。
    - playbook：`.sec-title 物业静态资料`(playbookStatic) + `.sec-title AI动态策略`(bgbox+riskbar+aicards)。
    - 通话态：`inCall` 时中栏主体优先显示 `.transcript` 实时流（Tab 头仍在可切回）。
17. 右栏：`inCall` 时顶部插 `ai-head/ai-body`；操作区 `caseOps` 循环按钮（`@click="onOp(o)"`）；底部 `quickbar`。
18. 右抽屉：在 `#app` 末尾（Toast 同级）新增 `.mask` `:class="{on:drawer.open}"` + `.drawer`，内含 `.dh`(标题+×) / `.dbody`(按 `drawer.kind` 分支：call/ticket/sms/evidence/promise/legal/status/note) / `.df-footer`(下载/关闭等)。
19. 项目档案 `selProj` 分支：新增「作战手册」`.sec-title` 区块（静态文本 + AI diff + 采纳按钮 `can('proj.edit')` + 版本/重生成）。
20. 批次详情 `selBatch` 分支：新增「作战手册(批次级)」`.sec-title` 区块（静态 + 维护权限按角色）。
21. 移除/不再从菜单进入 `aiPlaybook`（菜单已在 SEG-1 删；视图片段可删或留作内嵌引用）。

**ds-admin.css**
22. 三栏全角色化微调（若 `.col.right` 在非通话态需调整间距）：`.case3 .col.right .opzone{padding:12px 14px;display:flex;flex-direction:column;gap:8px;}`。
23. 时间线类型徽标：`.tl .e .ty{font-size:11px;padding:1px 7px;border-radius:4px;margin-right:6px;}` + 各 type 配色（复用 tag 色变量）。
24. 抽屉内播放器条：`.player{display:flex;align-items:center;gap:10px;background:#f7f9fc;border:1px solid var(--bd);border-radius:6px;padding:10px;font-variant-numeric:tabular-nums;}`。
25. 中栏三 Tab 内容滚动区：`.midpanel{padding:14px;max-height:520px;overflow:auto;}`。

`node --check` 通过 + 6 角色逐一点开案件详情验证三栏渲染、抽屉开合、Tab 切换、脱敏正确 → SEG-2 完成。

### SEG-3 · 全局一致性（收尾）

26. **面包屑/tagsView**：`crumb` 在批次优先链路下显示 `案件管理 / 批次X / 案件Y`（按 `selBatch/selectedId` 拼接）；确认 `caseDetail/legal/import` 不进 tagsView（已有 pushTag 过滤）。
27. **权限点一致性自检**：逐角色核对 `caseOps` 渲染 = §02 矩阵 3.2（CO 6项 / PC 多项含撤案存证法务 / PL 减免撤案 / VL 分配释放 / SA 全代 / SE —）。修正任何不符的 `perms`。
28. **脱敏自检**：VL/CO 结案案件 → 结案统计视图、列表手机/姓名脱敏（`field`）、佣金区块不渲染（项目资料 Tab / 批次）。SE 全列表显示"数据范围内"提示。
29. **空态/降级**：时间线空→`.note 暂无记录`；AI 策略空→`建议已处理完毕`；分钟余额低→转写抽屉顶部 `alert warn`（BR-M4-02）。
30. **视觉一致性**：新块全部用 ds 变量与既有类（`.card/.sec-title/.tag/.btn/.desc/.tl/.chat/.steps/.drawer`），不引入新色值；左侧栏深色 `#304156`、政务蓝 `#2563EB`、KPI 卡风格不变。
31. **回归**：6 角色 × 全菜单逐项点击，确认无 JS 报错、无空白视图、角色切换 `onRole` 正常回落工作台、`node --check` 全过。

---

## G. 借鉴的 CRM 交互要点（已融合进 A~F）

| CRM 范式（调研） | 本方案落点 |
|---|---|
| 三层信息架构（头部状态+核心信息+右侧操作） | 详情顶部头条(金额/阶段/状态) + 左画像 + 右操作区（D.0/D.3） |
| 活动时间线多类型混排+筛选 | 中栏「全时间段」Tab，8 类记录混排倒序+类型筛选（D.2 Tab1） |
| 今日待办/工作台任务驱动 | 工作台首屏今日看板，可点直达（C） |
| 管道/看板与列表联动、下钻不跳页 | `.kanban` 阶段泳道 + 列表段控；点卡/行→详情（A.1） |
| 右侧固定操作区 + 可伸展侧面板 | 右栏 `quickbar` 固定 + 右抽屉看录音/单据（D.3） |
| 最小化跳转/内联编辑/侧滑替代新页 | 单据/录音/证书一律右抽屉；手记内联编辑（D.3） |

---

## 附：执行约束清单（sonnet 自检）

- [ ] 6 角色切换器保留，`onRole` 正常。
- [ ] 菜单按 `roles[role].nav` 显隐；新 key 已注册 `navMeta`。
- [ ] 操作按 `can(perm)`/`caseOps` 显隐；字段按 `field/canSeeDetail` 脱敏；结案收敛（BR-M8-09）。
- [ ] 作战手册无独立菜单，仅在项目/批次维护、案件中栏只读+AI 动态调阅。
- [ ] 案件入口=批次优先（cases→批次列表→明细→详情）。
- [ ] 录音/单据/证书一律右抽屉，不跳页。
- [ ] 仅用 ds-admin 设计系统类与变量；新样式入 `ds-admin.css`。
- [ ] 内联 `<script>` 过 `node --check`；不破坏现有视图。
- [ ] 不照抄 uiv4；功能依据现行 PRD/角色矩阵。
