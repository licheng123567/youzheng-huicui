# 契约扩展草案 · P0 两片（待确认）

> 原则：以下端点**落地已存在的 PRD 业务规则**（非新产品设计），契约为**纯新增（additive）**，oasdiff 非破坏 → 版本 1.0.4 → **1.1.0**（minor）。
> 确认后流程：OpenAPI 增量 → 后端实现 → 前端消费 → schemathesis + smoke + Playwright 用例。

---

## 切片 A：角色工作台 / 待办聚合
**依据（已存在）**：BR-M4-20（工作台聚合待办+提醒，按紧急度排序）、BR-M4-20a（一线 CO/PC=今日驾驶舱，按角色待办分类；管理角色 PL/SA/SE/VL=仪表盘）、BR-M4-13（承诺到期待办）、BR-M3-26（释放预警）。

### 端点 `GET /workbench`
- x-permission：无（任何登录态）；x-data-scope：`case-actor`/`own-org`（按角色取本人/本组织待办，服务端裁剪）
- 响应 `WorkbenchData`：
```
{
  role: string,                         // 当前主体角色
  layout: "cockpit" | "dashboard",      // CO/PC=cockpit；PL/SA/SE/VL=dashboard（BR-M4-20a）
  kpis: [{ label, value, filterKey }],  // 顶部 KPI，可点即筛（filterKey 传回 todos 过滤）
  todos: [{
    category: TodoCategoryEnum,         // 见下
    urgency: "HIGH" | "MED" | "LOW",    // 紧急度（左侧色条）
    caseId?: string, title: string,
    deadline?: date-time,               // 承诺到期/临近释放/T2 等
    ref?: { type, id }                  // 跳转锚点（案件/工单/法务）
  }]
}
```
- `TodoCategoryEnum`（按 BR-M4-20a 角色分类）：
  - CO：`PROMISE_DUE`(承诺到期/临近) `RELEASE_WARN`(临近自动释放) `TICKET_RECEIPT`(工单待回执) `NEW_ASSIGNED`(新分配/抢到)
  - PC：`LEGAL_DELIVERY`(法务待送达) `TICKET_RECEIPT` `REPAY_MARK`(线下回款待标) `PAYLINK_SEND`(缴费链接待发)
  - PL/SA/SE/VL：仅 kpis（dashboard），todos 可空
- 排序：HIGH→MED→LOW，同级按 deadline 升序（承诺/释放预警优先，BR-M4-20）

---

## 切片 B：派单容量 + 推荐指派
**依据（已存在）**：BR-M3-24（派单决策辅助：平台撮合时看各服务商**客观经营指标**——在催案件数/催收员数/人均持仓/近期回款；明确**不做评价/权重算法**）、BR-M3-23（服务商内海池：各催收员持仓余量条 + 按余量智能推荐分配）。

### 端点 1 `GET /dispatch/provider-metrics`（平台派单决策辅助 · BR-M3-24）
- x-permission：`case.dispatch`；x-data-scope：`platform`
- 响应：`{ items: [ProviderMetric] }`，`ProviderMetric`：
```
{ providerId, providerName,
  activeCases: int,        // 在催案件数
  collectorCount: int,     // 催收员数
  avgHolding: number,      // 人均持仓
  recentRepayRate: Rate }  // 近期回款表现(分数0-1，纯客观，不参与权重)
```
- 明确：仅客观指标陈列，**不返回评分/推荐排序**（守 BR-M3-24「不做评价算法」）

### 端点 2 `GET /providers/{id}/collector-capacity`（服务商内推荐指派 · BR-M3-23）
- x-permission：`case.assign`；x-data-scope：`own-org`（仅本服务商）
- 响应：`{ holdCap: int, items: [CollectorCapacity] }`，`CollectorCapacity`：
```
{ collectorId, name,
  holding: int,            // 当前持仓
  remaining: int,          // 余量 = holdCap - holding
  recommended: boolean }   // 按余量智能推荐(余量最大者)；纯余量规则，无加权
```
- 用于 SeaView 指派对话框：展示余量条 + 推荐催收员（BR-M3-23）

---

## 前端落地（确认后）
- 切片 A → DashboardView 升级为角色工作台（cockpit/dashboard 二态 + 待办列表 + KPI 可点筛）
- 切片 B → BatchesView 派单对话框加「服务商指标」面板；SeaView 指派对话框加「催收员余量+推荐」

## 待你确认/调整点
1. 端点路径命名是否 OK（`/workbench`、`/dispatch/provider-metrics`、`/providers/{id}/collector-capacity`）
2. 切片 A todos 是否需含 PC 的「减免申请待批」（BR-M4-24 协作项）——当前未列，按需加
3. holdCap 来源：复用现有 ROTATION 设置（CFG-HOLDCAP=50）；确认无误
4. 「近期回款表现」时间窗（近 30 天？）——需定一个具体窗口写进 BR/契约 description
