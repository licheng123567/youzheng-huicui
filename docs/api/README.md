# 有证慧催 · API 契约（v1.0 已冻结） — 说明与治理

> 本目录 = 前后端的**单一事实源(SSOT)**。目标：让后端从契约出发实现、前端从契约生成客户端，用 CI 把"漂移"变成"提交即挂"。
> 由高保真原型 + PRD(BR/US) + `../ui/14-PRD-UI拉通评估报告.md` 口径抽取。**v1.0 已冻结（2026-06-24）**——四轮独立审计（codex×2 + 人工×2）+ openapi-generator Spring 桩 mvn compile + Flyway 真库迁移三重验证，0 阻断/0 高。破坏性变更须升 major + 双 approve。
>
> **v0.2 评审修复（2026-06-24）**：B-01 资金双线**字段级隔离**（ProjectForProvider / BatchForProperty / BatchForProvider，不靠说明文字）；B-02 完成支付申请单**必带凭证**（Voucher schema + CompletePayReqInput required + 审计字段 completedBy/At/version）；H-01 支付申请单**线别—生成方绑定**（IN→平台、OUT→服务商，generatedBy 服务端派生，错线 403 BIZ_WRONG_SETTLE_SIDE）；H-02 单据加**明细快照 lines + 固化 commRate + 审计时间线**；H-09 维持 **OpenAPI 3.1.0**（早期曾一度降 3.0.3，因 $ref 同级字段需求回到 3.1.0，nullable 改用 3.1 的 `type: [x,'null']` 写法）；H-10 UI 权限矩阵改支付申请单；M-01 录音 **failureCode/Message + /recordings/{id}/reprocess 重试**；M-02 枚举一致（CloseKind 去 SETTLED、外围 AI 枚举标注）；L-01/L-02 计数与 Rate 复用。
> **v0.3 端点扩面（2026-06-24，已落地）**：H-04 派单/公海动作闭环（claim/assign/release/return/open-for-claim/redispatch + Case `pool/source/holder/t2/tCollector` 字段 + PoolEnum）；H-05 批次导入逐行字段（CaseImportRow）+ 错误结构（ImportResult/ImportError）；H-06 跟进 method/attachments、承诺分期 PromiseInstallment、联系人维护（contacts CRUD）；H-07 缴费链接 resend/void + 回款 reverse（红冲联动）；M-03 列表分页统一（RepayLine/CoCommissionPerson/DisposeTask Page + size）；M-04 H5 欠费周期 arrearagePeriods；H-03 CO 作业端点 `case-holder` scope + 范围词表（own-org/range/platform/case-holder/public）；H-08 关键写操作补幂等键 + 错误响应。
> **CI 防漂移已配（`.spectral.yaml` + `.github/workflows/api-contract.yml` + `.github/CODEOWNERS`）**：Gate0 Spectral lint(写操作必带 x-permission/x-data-scope/summary、枚举大写) + oasdiff 破坏性检测；Gate1 schemathesis 打后端(命门，后端就绪后转硬红线)；mock-smoke Prism 自检。
> **v1.0.0-rc1 全模块收口（2026-06-24，已落地）**：外围模块进契约——存证 M6(发起/列表/二维码核验·三方隔离)、计费充值 M9-B(能力用量只量不金额下钻/充值流水/平台后台充值)、报表 M10(三角色经营报表+下钻+导出留痕)、AI M5(配置中心/话术库+专家录入+变体晋升/作战手册采纳)、组织成员 M1(建组织/成员平台只建平台员工/审计/权限矩阵)。Spectral 0 error。
> **v1.0.0-rc1 codex 审计 + 优化（2026-06-24，已落地）**：codex 独立只读审计 + opus 对抗校验，确认 22 条真问题（剔除 1 误述/2 降级；7 条既定设计无一被误判为缺陷）。已修**全部 阻断/高 + 关键中项**：① operationId 0→**101/101 唯一**（codegen/mock/契约测试治理前提；`.spectral.yaml` operationId 规则改 error+全局唯一）；② 补端点——手工录入 `POST /batches/{id}/cases`、批次/案件作废(`/void`+`VOIDED`+`case.void`)、协调员关联维护、Case 事实源字段(户号/欠费周期/诉讼要素/结案时间)、**法律文书申请-出PDF-送达签收链路**(`legal-docs`+`legal.create`)、开放抢单**改平台侧+付佣比例前置**(`BIZ_OPEN_RATE_REQUIRED`)、通话记录全量列表 `GET /recordings`、Promise/Ticket 生命周期(+`TicketStatusEnum`)、成员/组织生命周期(停用/启用/重置/改绑)、系统业务规则配置 `GET/PUT /settings`(`settings.manage`)；③ 合理性——**nullable 76→3.1 `type:[x,'null']`/oneOf**、3 处 oneOf 加 discriminator(viewRole/mode)、业务错误码补全(`BIZ_SMS_COOLDOWN`/`BIZ_QUOTA_EXHAUSTED`)、幂等覆盖 accept/reject/export、Voucher 输入防注入(VoucherInput)、Script 比率 string→Rate、AuditLog/权限矩阵/Contact/Activity/报表强类型化。**独立复验：87 路径/101 操作/139 schema/36 权限点，operationId 101 唯一、$ref 0 悬空、Spectral 0 error/0 warning。**
> **codegen 落地 + 编译验证（2026-06-24，已落地）**：用 openapi-generator 生成 Spring 桩（`backend/generated/spring/`：17 个按 tag 分组 `*Api` 接口 + 177 DTO，Spring Boot 3）+ `mvn compile` 真编译——**编译验证又逼出 2 个 Spectral 漏报的真契约 bug 并已修**：(a) 属性键 `no`（Batch/PaymentRequest 单号）被 **YAML 1.1（snakeyaml/PyYAML）解析为布尔 `false`** → 生成 `String false;` 编译失败 → 改名 `code`；(b) oneOf **discriminator 属性用内联 enum** 致父接口 getter(String) 与分支 getter(枚举类型)返回类型冲突 → 判别属性改普通 `string`。修复后 Spring 桩 **BUILD SUCCESS（236 .class）**、契约仍 Spectral 0 error。建库迁移见 `backend/db/migration/`。
> **v1.0.0-rc1 二轮审计优化（2026-06-24，已落地）**：B1 结算口径更正(支付申请单手动组单替代旧明细级增量描述)；B2 PaymentRequest 增 feeLines(计费明细下钻)；B3 新增 GET /payment-requests/{id} + POST /{id}/send；H1 voucher PAID 条件强约束(description+complete required兜底)；H2 CompletePayReqInput.version 必填 + revoke 加 version + 幂等键；H3 GET /payment-requests 加 x-settlement-side-rule + 403；H4 录音端点 scope own-org→case-holder；H5 uploadRecording requestBody required + 补全错误响应；H6 CallRecStatusEnum+QUOTA_BLOCKED + 新增 parse/batch-parse 端点；H7 PromiseStateEnum+PARTIAL_FULFILLED；H8 ERD 状态机补 PENDING_DISPATCH→VOIDED；H9 幂等键覆盖(dispatch/co-pay-doc/revoke/send)；H10 写端点错误响应补全；M1 ERD 枚举字典补齐；M3 MarkCodeEnum 改可配置 string；M4 新增 RESTful `PATCH /cases/{id}`（与聚合 GET 同 path item，合法不冲突）；M5 新增 GET /co-pay-docs + GET /co-pay-docs/{id}；M6 分页 Size 参数补全(9 个列表)；M7 Error.code 字典补中文释义；M8 CallRecording 增 collectorId+transcript；L1 版本号统一 v1.0.0-rc1；L2 ERD no→code；L3 权限矩阵历史伪码对齐契约实际码；L4 幂等键覆盖清单。**二轮结果（opus 独立复验）：92 路径/108 操作/143 schema，Spectral 0 error，mvn compile BUILD SUCCESS（240 .class），Flyway V1+V2 真库 pgvector 执行通过（34 表，含 QUOTA_BLOCKED/PARTIAL_FULFILLED CHECK）。** 一处 codegen 退路：H1 voucher 的 PAID 条件必填未用 JSON Schema if/then（openapi-generator Java 支持不稳），改由 complete 端点 `required:[voucher,version]` 强制兜底（PAID 唯一入口即 complete，等效保证）。
> **v1.0.0-rc1 三轮审计优化（2026-06-24，已落地）**：B1 批次级减免/作战手册覆盖（`GET/PUT /batches/{id}/reduce-tiers`、`GET/POST /batches/{id}/playbook` + reduceMode/playbookMode + effective source INHERIT/CUSTOM）；**B2 录音/通话 scope 校准——新增 `case-actor`（持有 CO + 关联 PL/PC + SA 代操作 BR-M4-01a），纠正二轮把录音从 own-org 过度收窄到 case-holder 而排除关联 PL/PC 的问题**；B3 删存证"法务前置校验"改场景校验；H1 claim 支持本商公海(默认)+开放池两类 + 并发/上限/归属规则；H2 release 按来源回流(服务商公海→PROVIDER_SEA/开放→OPEN_POOL)+改正 BR 引用；H3 承诺/缴费链接(create/resend/void)→case-actor；M1 公海列表 `SeaCase`(viewerCount/sourceBadge/competitionState/contactMasked/eventCursor/capacityHint)；M2 充值 `RechargeTypeEnum[STT,SMS]`(存证/法律不预充·仅对账)；M3 CoPayDoc 加 lines 穿透明细；L1 原型清结算语境"异议"残留(回款明细两表删异议列+文案改线下核对/撤销重生成，话术"异议处理"保留)，PRD/契约/UI 三方一致。**三轮结果（opus 独立复验）：94 路径/112 操作/146 schema，Spectral 0 error，mvn compile BUILD SUCCESS（259 .class）。** scope 双向校准要点：own-org(过宽)→case-holder(过窄)→**case-actor(持有人+关联物业+平台代)** 为正解。
> **v1.0.0 四轮审计 + 冻结（2026-06-24，已落地）**：第四轮 codex 冻结门审计=0 阻断/6 高，已全修：H1 短信验证码登录闭合(`POST /auth/sms-code`+`/auth/select-account`+loginTicket)；H2 标注配置 connected/effectiveFollowUp 语义(驱动 T_collector 重置)；H3 AI 策略结构化(`StrategyCard`+AiReview.suggestions+CaseDetail.preCallStrategy+采纳联动 sourceSuggestionId)；H4 GET ai-review scope→case-actor；H5 成员督导留痕(`POST /members/{id}/supervision-actions`+列表+SupervisionActionEnum)；H6 短信通道配置(签名/模板/阈值)+发送明细 `SmsSendRecord`+`GET /sms-records`。**独立复验：99 路径/117 操作/156 schema/36 权限点，Spectral 0 error，mvn BUILD SUCCESS（277 .class）。→ 0 阻断/0 高，已冻结 v1.0。**
> **v1.0.1 补丁（实现期 Gate1 暴露，非破坏，oasdiff 确认）**：M2 实现时 schemathesis 抓到**资金双线角色响应 oneOf 歧义**——平台 `Project`(含 commInRate)结构上同时匹配子集 `ProjectForProvider`，致 `oneOf` "恰好一个"失败。修：给 `ProjectForProvider` 加 `additionalProperties: false` 使分支互斥。Batch 三向(BatchForProperty/Provider)同类问题待 batch 数据落地时一并处理。**这是行走骨架/Gate1 的价值：实现期把契约的判别联合体歧义撞出来并以受控补丁修复。**
> **冻结后 backlog（不阻断冻结，待后端阶段）**：① schemathesis Gate1 需**后端可达**才转硬红线（在 Spring 桩上填实现后接）；② 附件/凭证/导出的**实际文件通道(预签名上传/下载)**与电子签章 = PRD 标 TBD，契约以 url/sealed 占位；③ 两个按设计暂缓的中项——结案脱敏 oneOf（服务端 `x-data-scope` 已兜底）、报表全角色化 schema（PRD BR-M10-08 字段 TBD/OQ-M10-3 未决）。

## 文件
| 文件 | 作用 |
|---|---|
| `ERD.md` | 数据模型：实体/字段/关系（Mermaid）+ **枚举字典**（单一事实源）+ 关键状态机 |
| `openapi-core.yaml` | **OpenAPI 3.1.0 契约 v1.0.0（已冻结）**：99 路径/117 操作/156 schema/36 权限点；全 11 模块；operationId 全覆盖唯一；含错误码字典、枚举、`x-permission`/`x-data-scope`/`x-settlement-side-rule`/`x-response-by-role`/oneOf discriminator |
| `.spectral.yaml` / `.github/workflows/api-contract.yml` / `.github/CODEOWNERS` | **CI 防漂移**：契约 lint + 破坏性检测 + schemathesis 契约测试 + 契约 Owner 门禁 |
| `README.md` | 本文：覆盖范围、用法（codegen/mock/契约测试）、治理、权限点清单、口径锚点 |

## 覆盖范围（核心闭环）
认证 → 项目/批次/案件主数据 → 派单/公海 → 催收作业(本机通话录音上传/AI复盘/跟进/承诺/工单/缴费链接/减免) → 结案(撤案/坏账，结清自动) → 结算(支付申请单：按明细组单/收款方生成/撤销重生成/凭证) → 催收员佣金(人→批次→明细→支付单) → 质检(处置/上报/复核/处置跟踪) → 业主H5账单。

**v1.0-rc 已含外围模块**：存证 M6、计费/充值 M9-B、报表 M10、AI 配置/话术库/作战手册 M5、组织/成员/审计 M1。全 11 模块覆盖。

## 怎么用（落地链条 · 不割裂的关键）
```
openapi-core.yaml  ←── 唯一事实源，进 Git、走评审、打版本
   │
   ├─ 后端  openapi-generator → Spring 接口桩 + DTO（RuoYi 兼容；签名不许手改）
   ├─ 前端  openapi-typescript / Orval → TS 类型 + 请求客户端（不再手写接口类型）
   ├─ Mock  Prism / Mockoon → 按契约起 mock 服务，前端不等后端即可联调
   └─ 防漂移 schemathesis / Spring Cloud Contract → CI 拿契约打后端，响应违约即构建失败
```
数据库：按 `ERD.md` 建 PG 表 + Flyway/Liquibase 迁移；pgvector 留 M5 话术库 RAG。

## 代码生成实操（已验证可跑 · 复制即用）
> 工具链：java 21 / node / openapi-generator-cli 7.x（npx 自动拉 jar）。所有命令在仓库根目录执行。

**① 后端 Spring 接口桩 + DTO**（产物已在 `backend/generated/spring/`，17 个按 tag 分组的 `*Api.java` 接口 + 177 个 model DTO，Spring Boot 3 / jakarta）：
```bash
npx -y @openapitools/openapi-generator-cli generate \
  -i docs/api/openapi-core.yaml -g spring -o backend/generated/spring \
  --additional-properties=interfaceOnly=true,useSpringBoot3=true,useTags=true,\
apiPackage=com.youzheng.huicui.api,modelPackage=com.youzheng.huicui.model,\
basePackage=com.youzheng.huicui,hideGenerationTimestamp=true,openApiNullable=false
# 验证编译：cd backend/generated/spring && mvn compile
```
- `interfaceOnly=true` 只出接口+DTO（**桩**，签名不许手改）；业务实现写在各 `*Api` 的实现类里（或加 `delegatePattern=true` 让 impl 独立）。
- 方法名 = 契约 `operationId`（如 `createPaymentRequest`/`voidCase`/`listRecordings`），所以契约里的 operationId 必须稳定唯一（CI 已 lint）。
- **RuoYi(Spring Boot 2/javax) 兼容**：去掉 `useSpringBoot3=true` 即生成 javax 版；包名按 RuoYi 模块改。

**② 前端 TS 类型 + 请求客户端**：
```bash
# 类型（轻量，零运行时）
npx -y openapi-typescript docs/api/openapi-core.yaml -o frontend/src/api/schema.d.ts
# 客户端二选一：
npx -y openapi-fetch        # 配 schema.d.ts 用，类型安全 fetch（推荐，极轻）
# 或 Orval：按 orval.config 生成 hooks/axios 客户端（重，带 react-query）
npx -y orval --input docs/api/openapi-core.yaml --output frontend/src/api/client.ts
```
- 前端只用 generated 类型，**用契约外字段 → 类型检查直接挂**（Gate2）。

**③ Mock 服务**（前端不等后端即可联调，与真后端跑同一契约）：
```bash
npx -y @stoplight/prism-cli mock docs/api/openapi-core.yaml -p 4010
# → http://127.0.0.1:4010/v1/...  按契约 example/schema 返回 mock
```

**④ 数据库迁移**（Flyway，产物在 `backend/db/migration/`）：
```bash
flyway -url=jdbc:postgresql://localhost:5432/huicui -user=... -locations=filesystem:backend/db/migration migrate
# 或 Spring Boot 集成 flyway-core，启动自动迁移；M5 话术库需先 CREATE EXTENSION vector
```

## CI 红线（防漂移命门）
- **Gate0 契约自身**：Spectral lint（命名/必填description/枚举完整/错误结构统一）+ oasdiff 破坏性变更检测（删字段/改类型/改必填 → 标 breaking + 强制升版本 + 双 approve）。
- **Gate1 后端符合契约（硬红线）**：schemathesis 拿本契约自动打后端，响应 schema/状态码/错误结构违约 → **构建失败、合不进 main**。← 没有这道，契约只是好看的文档。
- **Gate2 前端不越契约**：类型/客户端 generated，用契约外字段 → 类型检查失败；mock 也由契约生成（保证"对mock开发"="对真后端上线"同一契约）。
- **Gate3 防代码先行**：controller 签名变了但 yaml 没动 → 挂；改接口 PR 必须带契约 PR。

## 治理（组织问题 > 技术问题）
- **契约中立、有守门人**：设**契约 Owner**（架构师角色），对命名/一致性/版本负责；契约目录配 CODEOWNERS，改契约须 Owner approve。
- **铁律：先改契约，再改代码**。任何人动接口 → 提契约 PR → @前端+@后端+@产品 评审 → 合并。
- **消费者驱动**：前端(原型已定义需求)对"要什么字段/形状"有主导权；后端对"可行性/性能/数据来源"有否决权；契约 PR 里谈拢。
- **版本化**：破坏性变更升 major；非破坏新增（加可选字段/新端点）走评审即可。

## 约定（写在契约里、必须遵守）
- 金额 `amount_cents`(分, integer)，**不含税基数口径**（BR-M9-01b）；**比率 `rate`(分数 0-1, number, 如 0.30=30%, v1.0.3 统一；展示层 ×100)**；时间 ISO8601。
- 状态/类型一律**枚举码**（见 ERD 枚举字典，中文仅展示名）。
- 列表默认分页（page/size）+ 服务端按 `x-data-scope` 强制裁剪（own-org / range / platform / public），**非仅前端隐藏**。
- 动作型端点用 `/资源/{id}/动作`（如 `/cases/{id}/close`）；状态机非法流转返回 **409**；校验失败返回 **422 + details[]**。
- 聚合端点贴近页面（如 `GET /cases/{id}` 一次取齐三栏），避免前端多次拼接。

## 权限点清单（`x-permission` → 对齐 permMatrix / `../ui/02-角色操作权限矩阵.md`）
> 服务端鉴权 = 权限点 + 数据范围双重校验。36 个业务权限点（另 `none`=公共/`self`=自助为非业务点）：

核心闭环：`proj.edit` · `reduce.policy.edit` · `batch.import` · `case.dispatch` · `case.accept` · `case.assign` · `case.claim` · `case.release` · `case.return` · `case.void` · `case.follow` · `case.promise` · `case.ticket` · `ticket.handle` · `case.paylink` · `case.call` · `legal.create` · `case.reduce` · `reduce.approve` · `case.close` · `case.repay.mark` · `payreq.create` · `payreq.complete` · `cocomm.manage` · `cocomm.self.view` · `qc.dispose` · `qc.escalate` · `qc.review`
外围：`evidence.create` · `billing.recharge` · `report.export` · `ai.config` · `playbook.adopt` · `org.manage` · `member.manage` · `settings.manage`

平台限定（`x-data-scope: platform`）：`case.dispatch`/`payreq.complete`/`qc.review` + `/dispose-tasks`(风险处置跟踪仅平台 BR-M5-07b)。
> 结算模型已于 2026-06-24 改为**支付申请单**（删 settle.initiate/recon.out.confirm/repay.dispute.* 等旧权限点）。

## 口径锚点（契约里写死的、来自报告14的对齐结论）
- 结算：**支付申请单**——按案件明细**手动组单**(非按月自动)；**收款方生成**(收佣线平台/付佣线服务商)、付款方付款、生成方**完成前可撤销/撤回重生成**；完成留收款/支付凭证(BR-M9-12a/b/d)。**案件级在线异议已作废**(BR-M9-12c)，纠错走撤销重生成。
- 减免：阶梯·决定权(BR-M2-18a)——自决档`EFFECTIVE`/超自决`OFFLINE_TRACE`(系统不审批)/PL系统内核准；**无协调员特批**。
- 坏账/撤案：填原因留痕、不设审核流(BR-M2-17/M8)。
- 通话：系统不主动拨号·本机录音回填·获取最新·Web上传救济(BR-M4-01b/01c)。
- 质检：处置归属"谁的员工谁处理"(BR-M5-07a)，平台只复核(07c)，处置跟踪仅平台(07b)。
- 隔离：资金双线(收/付佣互不可见)、三方数据范围、结案脱敏(BR-M8-09)、平台只建平台员工(BR-M1-04a)。

## 坑（别踩）
1. **契约写了但 Gate1 没接 = 白搭**。契约测试是命门。
2. **原型是快乐路径**：分页/鉴权/校验/并发/幂等/错误码/空态，契约必须补（本草稿已含错误结构+409/422+分页，幂等键覆盖清单（支付/结算/派单类已覆盖，其余按需））。
3. **类型/单位/精度钉死**：金额用分、时间ISO、枚举码——别让"¥1,200"字符串回流。
4. **mock 与真后端跑同一契约测试**，否则前端对 mock 顺、上线对真后端崩。
5. **别上太重**：先 OpenAPI 契约优先把"不割裂"解决；GraphQL/BFF/tRPC 后期形态复杂再议。
