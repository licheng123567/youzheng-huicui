# 契约扩展草案 · P1 片（待确认）

> 原则同 P0：落地已存在 PRD 规则，契约纯新增（additive），v1.1.0 → **1.2.0**。
> 诚实边界：凡依赖**未定义配置（CFG-T1/T2=TBD）**或**实时 presence 通道**的部分，明确推迟/降级，不杜撰。

---

## 子片 1：消息中心（BR-M4-23 催收员↔协调员互推闭环）— **新增**
互推动作（工单转出/回执、协调员处理结果）双向推送，落对方消息中心（未读）+ 案件时间线（activity 已有）三处同步可见。

### 新表 `notification`
`id, recipient_account_id, type, title, body, ref_type, ref_id, read(bool), created_at`（按 recipient 索引）

### 新端点（additive）
- `GET /notifications?unreadOnly=&page=&size=` → `NotificationPage{items,meta}`，x-data-scope：本人(recipient=accountId)
- `POST /notifications/{id}/read` → 标已读（仅本人；越权 403）
- `GET /notifications/unread-count` → `{count}`（导航红点）

### 触发点（后端在既有动作内写 notification + activity）
- CO 转工单（createTicket, to_role=PC）→ 通知该物业协调员「待处理工单」
- 协调员回执（handleTicket）→ 通知**持有催收员**「工单已回执」
- （后续可扩：法务进展/线下回款标记 → 通知持有催收员）

### 前端
- AppLayout 顶部消息红点（unread-count）；新「消息中心」页或抽屉：未读列表 + 标已读 + 点跳案件

---

## 子片 2：公海竞争态 + 入池徽标 + 事件日志（BR-M3-20/21/22）— **后端填充已有字段 + 1 新端点**
SeaCase 字段**已在契约**（sourceBadge/competitionState/contactMasked/capacityHint/viewerCount/eventCursor），当前后端未填 → 填充 + 前端展示。

### 后端填充（无契约改动）
- `sourceBadge` = 入池来源（origin_pool/pool 推导：T1超时入池 / T2退回 / 开放抢单）
- `competitionState` = `CLAIMED`(已有 holder) | `AVAILABLE`(待抢)；**`VIEWING` 需实时 presence → 降级不产出**
- `contactMasked` = 公海池且未持有 → true（呼应 BR-M3-21a，已有脱敏逻辑则对齐）
- `capacityHint` = 当前催收员余量（复用 P0 collector-capacity 口径）
- `viewerCount` = **降级返 0**（"正在查看N人"需 WebSocket/SSE presence，地基期无实时通道）

### 新端点 `GET /sea/events?pool=&page=&size=`（BR-M3-22 事件日志面板）— additive
- 近期公海流转事件流：入池(ENTER)/抢单(CLAIM)/释放(RELEASE)/退回(RETURN)/再派(REDISPATCH)/开放(OPEN)
- 来源：activity 表 type=STATUS/OPLOG 的流转事件（+ case 流转留痕）
- x-permission：无；x-data-scope：range（平台全量 / 服务商本商池）
- **降级**：轮询拉取近期事件，**非实时 SSE 推送**（eventCursor 字段保留供未来 SSE，地基期前端定时刷新）

### 前端
- SeaView 每行加：入池来源徽标 + 竞争态标签(待抢/已抢) + 余量提示 + 脱敏标记
- SeaView 加「实时事件日志」面板（GET /sea/events，定时刷新）

---

## 子片 3：T1/T2 SLA 预警 — **本片推迟（依赖未定义配置）**
- **释放预警（BR-M3-26·T_collector）**：✅ 已在 v1.6.0 workbench `RELEASE_WARN`（基于 case.t_collector_deadline）落地。
- **T2 退回预警（BR-M3-13a·向 VL）**：⛔ 依赖 `CFG-T2`（PRD 标 **TBD**，定时器值未定义）+ 缺「进入服务商公海时间」字段 → **无法算倒计时**。
- **T1 派单时限预警（向平台）**：⛔ 依赖 `CFG-T1`（PRD 标 **TBD**）→ 同上推迟。
- 结论：T1/T2 预警须先在 PRD 定义 CFG-T1/CFG-T2 具体值 + 补 case 计时基准列（如 `entered_pool_at`），属**定时器配置阶段**，不在本片硬塞。

---

## 待你拍板（影响实现范围）
1. **范围**：P1 = 子片1（消息中心）+ 子片2（公海竞争态/徽标/事件日志），子片3（T1/T2）推迟到定时器配置阶段——是否认可？
2. **viewerCount 降级**：「正在查看N人」返 0 + 前端不显该项（需实时通道，后续）——接受？还是本片就要接 SSE/WebSocket（重型，单独立项）？
3. **事件日志范围**：`GET /sea/events` 做**全局近期池事件流**（公海面板用）；案件级流转已在 CaseDetail.timeline——确认不再单做 per-case claim-events？
4. **消息触发点**：本片先接「工单转出/回执」两类（最核心的 BR-M4-23 闭环）；法务/回款通知留后续——OK？
