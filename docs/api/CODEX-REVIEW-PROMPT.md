你是资深全栈审计。对「有证慧催」物业费催收 SaaS 做**第二轮只读静态复审**（上一轮你给 72/100、列了 P0/P1）。**只读，不改任何文件，不跑测试。**

仓库根：/Users/shuo/AI/cuishou
- 契约 SSOT：docs/api/openapi-core.yaml（OpenAPI 3.1，v1.0.4，117 操作）
- 后端：backend/app/src/main/java/com/youzheng/huicui/（Spring Boot 3.3/Java21，控制器在 web/，横切层 security/ common/ error/，登录 web/AuthController.java，权限 common/Permissions.java）
- 前端：frontend/src/views/（Vue3+ElementPlus+TS），路由 frontend/src/router/index.ts，导航 frontend/src/layouts/AppLayout.vue，鉴权 store frontend/src/stores/auth.ts
- 角色码：SA/SE/PL/PC/VL/CO；金额 *_cents(分)；比率 Rate=分数 0-1(v1.0.3 起)；前端展示 ×100
- 质量门（仅参考，勿运行）：Spectral + oasdiff + route_coverage.py(契约↔handler对账) + schemathesis 整体硬红线

## A. 首先逐条确认上轮问题是否已修（回归确认）
请打开对应文件核对，对每条给「已修/未修/部分修/新引入回归」结论 + 证据(文件:行)：
1. [阻断] M3 拒接 reject 是否传 reason（SeaView.vue rejectCase → ReasonInput）
2. [阻断] 录音 FAILED 重试是否改调 /recordings/{id}/reprocess（CaseDetailView reprocessRec，不再用 /parse）
3. [阻断] 内催 CoPayDoc 状态是否用 PENDING_PAY/SETTLED（不再用 PAID）；是否补了设比例 PUT /co-commissions/.../rate 与生成单 POST /co-pay-docs（SettlementView setRate/genCoPayDoc）
4. [高] CO 是否仍能看组织级 OUT 支付申请单（后端 PaymentRequestM9Controller.appendOrgScope 是否对 role=CO 裁剪 1=0；前端 SettlementView canViewPayReq 是否排除 CO）——核对是否真满足 US-M9-09 本人佣金只读
5. [高] billing 只读菜单是否对 range 内 PL/VL/PC 放开（AppLayout.vue 菜单项 /billing 是否去掉 billing.recharge 门控）
6. [中] Rate 注释漂移是否修正（BatchPlatformView/BatchPropertyView 注释 0-1）

## B. 审计本轮新增/改动代码是否引入新问题（重点）
新增/改动：多账号登录(AuthController login 双模 Map/select-account/sms-code + Permissions ticket.handle)、caseIds 勾选拆派(BatchesView)、AI 写界面(SettingsView ai-config PUT/script-lib/promote)、playbook 采纳(ProjectDetailView)、缴费链接重发作废(CaseDetailView resendLink/voidLink)、通话记录页(CallRecordView)、建议法务轻标(CaseDetailView suggestLegal)、批次详情(BatchDetailView)、Dashboard 工作台。
重点查：
- 前后端契合：每个前端 POST/PUT/PATCH 的 body 字段/枚举值/必填是否与契约 schema 一致（尤其新加的 ai-config/script-lib/co-pay-docs/playbook/select-account 请求体；CoPayDocStatus、ChannelEnum、RoleTemplate、ReconSide、MarkCode、LegalDocType、EvidenceScene、CloseKind、CaseStatus、PaymentRequestStatus 等枚举有无前端硬编码漂移）
- 权限门控：前端按钮 v-if 的权限码是否与后端 x-permission 一致（尤其新加的 ai.config/playbook.adopt/ticket.handle/billing；以及导航 AppLayout 的 perms 映射是否过宽/过严）
- 数据范围/越权：新端点是否有 CO/物业/服务商越权读写（多账号 select-account 票据是否会签发越权 token、payment-requests/co-commissions/billing 的 scope 是否按角色正确裁剪）
- 登录：select-account 票据校验（过期/不在范围/重放/一次性）、sms-code 骨架是否泄露 code、单账号是否会被误判多账号
- 5xx/健壮性：新前端 fetch（如 CallRecord、Dashboard 聚合多端点 403 容错、OwnerH5 plain fetch）是否优雅；后端新逻辑有无未捕获异常
- 主流程闭环回归：M3 派单/公海(含拆派 caseIds)、M4 作业台、M9 支付申请单+内催佣金——是否端到端可走通无断链

## C. 输出（中文，结构化）
1. 上轮问题回归确认表（6 条逐条结论 + 证据）
2. 本轮新发现问题：按 BLOCKER/HIGH/MEDIUM 分级，每条 文件:行 + 问题 + 为什么 + 修复建议；明确区分「正确性/安全/越权/枚举/5xx」与「功能覆盖深度」
3. 主流程端点链闭环复核（M3/M4/M9 是否仍闭合）
4. 三方差距（金额/Rate/角色码/枚举 是否仍一致）
5. **复审总评分(0-100) + 是否可上线**，并与上轮 72 分对比说明改进/遗留
请只报真问题，不挑风格；已知待定（短信真实通道、文件签章 documentUrl/sealed、线上支付）不计入缺陷。
