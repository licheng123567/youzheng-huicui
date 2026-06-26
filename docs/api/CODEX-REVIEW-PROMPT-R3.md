你是资深全栈安全审计。对「有证慧催」物业费催收 SaaS 做**第三轮只读静态复审**。前两轮你给 72→78/100。本轮核心：**确认上轮 2 个上线级 HIGH + 5 个 MED 是否真闭合**，并扫有无新引入回归。**只读，不改文件，不跑测试。**

仓库根：/Users/shuo/AI/cuishou
- 契约 SSOT：docs/api/openapi-core.yaml（OpenAPI 3.1，v1.0.4，117 操作）
- 后端：backend/app/src/main/java/com/youzheng/huicui/（控制器 web/，权限 common/Permissions.java，登录 web/AuthController.java，case scope web/CaseScopeM4Service.java）
- DB 迁移：backend/db/migration/V1-V6（filesystem）+ backend/app/src/main/resources/db/migration/V900/V901/V910/V911（classpath）
- 前端：frontend/src/views/、layouts/AppLayout.vue、stores/auth.ts
- 口径：角色 SA/SE/PL/PC/VL/CO；金额 *_cents；Rate=分数 0-1；前端展示 ×100

## A. 逐条确认上轮 HIGH/MED 是否闭合（给 已闭合/未闭合/部分/新回归 + 证据 文件:行）
1. [HIGH 越权] case-actor 行级：`CaseScopeM4Service.requireCaseActor` 是否已从“组织级兜底”改为：CO 仅持有本人(holder_id=accountId) / PL-PC 本物业(project.org_id) / SA-SE 平台 / VL 及其余非相关方 403。核对是否仍有路径让同服务商 org 非持有 CO 读/写他人案件录音(RecordingsM4)、AI review、缴费链接、跟进(FollowUpM4)、减免/回款(PayReduceRepayM4)。确认 requireCaseActor 的所有调用方都走这套裁剪、无旁路。
2. [HIGH 登录] SMS code 重放：`AuthController` 是否 (a) 验证码带 TTL、(b) 登录成功后一次性删除(防重放)、(c) 过期/错误清除、(d) 前端 LoginView 不再回显 dev 码。是否仍存在固定码无 TTL/可无限重放/前端泄露。
3. [MED 权限] `ticket.handle`：Permissions 是否已从 VL/CO 收回、仅 PL/PC 持有；与后端 handleTicket own-org scope 是否一致；前端处理按钮门控是否对齐(CO/VL 不应见或见后不 404)。
4. [MED 枚举] PayLink 状态：前端 CaseDetailView 是否已用契约 `PayLinkStatusEnum`(ACTIVE/EXPIRED)、不再硬编码 VOIDED；与后端 void 写入值一致。
5. [MED 5xx] `GET /cases?batchId=`：CasesM2Controller 非数字入参是否安全解析(不抛 NumberFormatException→不 5xx)。
6. [MED 健壮] SettingsView 编辑 AI 配置：首配 llm/asr=null 时前端是否 normalize、不再访问 null 嵌套崩。
7. [MED SSOT] script_lib Rate：DB 存储(V910 种子/V911 迁移)+列注释+后端 ScriptAiController 是否统一为分数 0-1、已去掉 /100 转换；promise_rate/repay_rate/variant.uplift 三处是否都对齐；wilson 是否被误改。检查 V911 的 guarded 转换会不会对已是分数的行重复除。

## B. 扫新回归（重点这些改动文件）
CaseScopeM4Service、AuthController、Permissions、CasesM2Controller、ScriptAiController、V910/V911 迁移、CaseDetailView、SettingsView、LoginView、SettlementView。
重点：
- 行级裁剪改动有无误伤合法访问（如 PL/PC 正常跟进/减免、SA 平台代操作、持有 CO 本案动作是否仍 2xx）
- ticket.handle 收回后，是否有端点/前端按钮依赖 VL/CO 持有该权限而功能缺失（确认工单链路对 PL/PC 仍闭合）
- V911 迁移与 V910 顺序、幂等性、Flyway 校验（编辑已存在的 V1/V910 是否会破坏校验——本项目 CI/dev 用全新 DB）
- 多账号 select-account 票据(过期/越范围/一次性/重放)是否仍安全
- 有无新 5xx / 枚举漂移 / 越权

## C. 输出（中文，结构化）
1. 上轮 7 项闭合确认表（逐条结论 + 证据 文件:行）
2. 本轮新发现问题(若有)：BLOCKER/HIGH/MEDIUM 分级 + 文件:行 + 原因 + 修复建议；区分“正确性/安全/越权/枚举/5xx”与“功能覆盖深度”
3. 主流程 M3/M4/M9 闭环复核(尤其 case-actor 行级裁剪后 M4 是否仍闭合)
4. 三方一致性(金额/Rate/角色码/枚举)
5. **复审总评分(0-100) + 是否可上线**，与 72→78 对比，明确两个 HIGH 是否已消除、是否达到“可上线”门槛
已知待定（短信真实通道随机码、文件电子签章 documentUrl/sealed、线上支付、caseIds 拆派前端筛可派态[纯体验]）不计入缺陷。只报真问题。
