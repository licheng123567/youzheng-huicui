-- =============================================================================
-- 迁移版本  : V1
-- 依据      : ERD.md v0.1 + openapi-core.yaml v1.0.0-rc1
-- PostgreSQL: ≥ 14（使用 GENERATED ALWAYS AS IDENTITY, timestamptz, JSONB）
-- pgvector  : 需安装扩展（M5 话术库 RAG 用）；维度 vector(1024) 可按实际模型调整
-- 主键策略  : 统一 bigint GENERATED ALWAYS AS IDENTITY
--             对外暴露的业务 ID 建议应用层转 base62/snowflake string，此处存整型
-- 金额约定  : *_cents BIGINT（分），不含税基数口径（BR-M9-01b）
-- 比率约定  : NUMERIC(6,4) 存百分比小数（如 8.0000 = 8%），COMMENT 标注
--             ERD 用 numeric(5,2)，契约 Rate 为 number；统一用 NUMERIC(6,4) 精度更足
--             【ERD/契约出入】ERD rate(decimal(5,2)) vs 此处 NUMERIC(6,4)：取更高精度，注释标注
-- 外键策略  : 明细→主表默认 RESTRICT；CASCADE 仅限明确说明的子明细
-- 枚举策略  : TEXT + CHECK(...IN(...))，不用 PG ENUM 类型（便于迁移演进）
-- =============================================================================

-- pgvector 扩展（M5 话术库 RAG；如未安装可注释，V2 不再重复 CREATE）
CREATE EXTENSION IF NOT EXISTS vector;

-- =============================================================================
-- 1. ORG 组织
--    BR-M1-01：平台建组织；多租户根节点
-- =============================================================================
CREATE TABLE org (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type            TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    -- owner_account_id 在 ACCOUNT 建完后加 FK，此处先放裸列
    owner_account_id BIGINT,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    credit_code     TEXT,
    legal           TEXT,        -- 法人
    phone           TEXT,
    addr            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_org_type   CHECK (type   IN ('PLATFORM','PROPERTY','PROVIDER')),
    CONSTRAINT chk_org_status CHECK (status IN ('ACTIVE','DISABLED'))
);

COMMENT ON TABLE  org                  IS '组织（平台/物业/服务商）；多租户根节点。BR-M1-01';
COMMENT ON COLUMN org.type             IS 'OrgType: PLATFORM=平台 PROPERTY=物业 PROVIDER=服务商';
COMMENT ON COLUMN org.owner_account_id IS '唯一负责人账号（FK 在 ACCOUNT 建完后添加）';
COMMENT ON COLUMN org.credit_code      IS '统一社会信用代码（可选）';

CREATE INDEX idx_org_type ON org (type);

-- =============================================================================
-- 2. ACCOUNT 账号（一号多账号；phone 可关联多 account）
--    BR-M1-11：一号多账号
-- =============================================================================
CREATE TABLE account (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id          BIGINT      NOT NULL,
    username        TEXT        NOT NULL,   -- 稳定唯一登录标识
    name            TEXT        NOT NULL,
    phone           TEXT        NOT NULL,
    role_template   TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    is_owner        BOOLEAN     NOT NULL DEFAULT FALSE,
    -- SE 三维数据范围: {areas[], properties[], providers[]}
    data_range      JSONB,
    -- 权限子集（负责人授予的权限点，JSON array of string）
    permissions     JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_account_org          FOREIGN KEY (org_id)   REFERENCES org(id)     ON DELETE RESTRICT,
    CONSTRAINT chk_account_role        CHECK (role_template   IN ('SA','SE','PL','PC','VL','CO')),
    CONSTRAINT chk_account_status      CHECK (status          IN ('ACTIVE','DISABLED')),
    CONSTRAINT uq_account_username     UNIQUE (username)
);

COMMENT ON TABLE  account              IS '账号（一号多账号；phone 可关联多 account）。BR-M1-11';
COMMENT ON COLUMN account.role_template IS 'RoleTemplate: SA=平台超管 SE=平台员工 PL=物业负责人 PC=物业协调员 VL=服务商负责人 CO=催收员';
COMMENT ON COLUMN account.data_range   IS 'SE 三维数据范围 {areas[],properties[],providers[]}，非 SE 为 null。BR-M1-14';
COMMENT ON COLUMN account.permissions  IS '权限点子集（JSON array of string）。负责人建员工时授予的权限子集';

CREATE INDEX idx_account_org_id       ON account (org_id);
CREATE INDEX idx_account_phone        ON account (phone);
CREATE INDEX idx_account_role_template ON account (role_template);

-- 补全 org.owner_account_id FK（循环引用：org→account→org，应用层保证先建账号再绑）
ALTER TABLE org
    ADD CONSTRAINT fk_org_owner_account
        FOREIGN KEY (owner_account_id) REFERENCES account(id) ON DELETE RESTRICT;

-- =============================================================================
-- 3. PROJECT 项目/小区（物业拥有）
--    BR-M9-01a：comm_in_rate 必填
-- =============================================================================
CREATE TABLE project (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id          BIGINT      NOT NULL,   -- 归属物业组织
    name            TEXT        NOT NULL,
    org_name        TEXT        NOT NULL,   -- 冗余物业名，列表高频展示
    area            TEXT        NOT NULL,
    province        TEXT,
    city            TEXT,
    district        TEXT,
    prop_company    TEXT,
    credit_code     TEXT,
    legal           TEXT,
    addr            TEXT,
    contract_type   TEXT,
    -- fee_rows: [{biz:string, std:string}]
    fee_rows        JSONB,
    fee_cycle       TEXT,
    penalty         TEXT,
    pay_info        TEXT,
    -- 资金双线·收佣线（平台↔物业）。NUMERIC(6,4) = 百分比，如 8.0000 = 8%
    -- 【双线归属】comm_in_rate 属于收佣线（IN），隔离在应用层 response schema
    comm_in_rate    NUMERIC(6,4) NOT NULL,  -- 收佣比例%·必填 BR-M9-01a
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    -- 诉讼要素（项目级，供起诉状套模板 BR-M4-18a）
    litigation      JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_project_org   FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT,
    CONSTRAINT chk_project_status CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);

COMMENT ON TABLE  project             IS '项目/小区（物业拥有）。BR-M9-01a：comm_in_rate 必填';
COMMENT ON COLUMN project.comm_in_rate IS '收佣比例%（百分比，如 8.0000=8%）。收佣线归属。BR-M9-01a';
COMMENT ON COLUMN project.fee_rows    IS '[{biz:string,std:string}] 收费标准行';
COMMENT ON COLUMN project.litigation  IS '诉讼要素 {creditCode,legal,addr}，可选，供起诉状套模板 BR-M4-18a';

CREATE INDEX idx_project_org_id  ON project (org_id);
CREATE INDEX idx_project_status  ON project (status);
CREATE INDEX idx_project_area    ON project (area);

-- =============================================================================
-- 4. REDUCE_TIER 减免阶梯（项目级，批次可覆盖）
--    BR-M2-18a：决定权/自决档/线下留痕
-- =============================================================================
CREATE TABLE reduce_tier (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT      NOT NULL,
    -- batch_id 为 NULL 表示项目级；不为 NULL 表示批次覆盖
    batch_id    BIGINT,
    -- discount 存文本如 "9折"，含义由应用层解析
    discount    TEXT        NOT NULL,
    cap_cents   BIGINT,          -- 减免上限（分）；NULL=无上限
    waive_penalty BOOLEAN   NOT NULL DEFAULT FALSE,
    decide      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_reduce_tier_project  FOREIGN KEY (project_id) REFERENCES project(id)  ON DELETE CASCADE,
    -- fk_reduce_tier_batch 在 batch 表建完后通过 ALTER TABLE 添加（循环顺序依赖）
    CONSTRAINT chk_reduce_tier_decide  CHECK (decide IN ('COLLECTOR_SELF','OFFLINE_INTERNAL','PL_APPROVE'))
);

COMMENT ON TABLE  reduce_tier          IS '减免阶梯（项目级；batch_id 非空=批次覆盖）。BR-M2-18a';
COMMENT ON COLUMN reduce_tier.cap_cents IS '减免上限（分，NULL=无上限）';
COMMENT ON COLUMN reduce_tier.decide    IS 'ReduceDecide: COLLECTOR_SELF=催收员自决 OFFLINE_INTERNAL=线下内部流程 PL_APPROVE=物业负责人核准';

CREATE INDEX idx_reduce_tier_project  ON reduce_tier (project_id);
CREATE INDEX idx_reduce_tier_batch    ON reduce_tier (batch_id);

-- =============================================================================
-- 5. BATCH 批次
--    资金双线：comm_in_rate（收佣线）+ pay_out_rate（付佣线）同表，隔离在应用层
--    BR-M9-01a/14/18
-- =============================================================================
CREATE TABLE batch (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id          BIGINT      NOT NULL,
    no                  TEXT        NOT NULL,   -- 批次编号
    -- provider_id：承接服务商，派单后才有
    provider_id         BIGINT,
    -- 【收佣线】生效收佣比例（批次覆盖否则继承项目 D5）
    comm_in_rate        NUMERIC(6,4) NOT NULL,
    comm_in_inherited   BOOLEAN     NOT NULL DEFAULT TRUE,
    -- 【付佣线】付佣比例（≤收佣比例 BR-M9-14）
    pay_out_rate        NUMERIC(6,4),
    -- 开放抢单付佣比例（平台预设，开放前必填 BR-M9-18）
    open_rate           NUMERIC(6,4),
    status              TEXT        NOT NULL DEFAULT 'PENDING',
    import_meta         JSONB,      -- 导入元信息（文件名/行数/导入时间等）
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_batch_project    FOREIGN KEY (project_id)  REFERENCES project(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_batch_provider   FOREIGN KEY (provider_id) REFERENCES org(id)      ON DELETE RESTRICT,
    CONSTRAINT chk_batch_status    CHECK (status IN ('PENDING','DISPATCHED','IN_PROGRESS','CLOSED')),
    CONSTRAINT uq_batch_project_no UNIQUE (project_id, no)
);

COMMENT ON TABLE  batch               IS '批次。comm_in_rate=收佣线，pay_out_rate=付佣线，双线同表隔离在应用层。BR-M9-14';
COMMENT ON COLUMN batch.comm_in_rate  IS '生效收佣比例%（百分比）。收佣线归属。批次覆盖否则继承项目 D5';
COMMENT ON COLUMN batch.comm_in_inherited IS 'true=继承项目收佣比例，false=本批次覆盖';
COMMENT ON COLUMN batch.pay_out_rate  IS '付佣比例%（百分比）。付佣线归属。须 ≤ comm_in_rate BR-M9-14';
COMMENT ON COLUMN batch.open_rate     IS '开放抢单付佣比例（平台预设，开放前必填 BR-M9-18）';

CREATE INDEX idx_batch_project_id   ON batch (project_id);
CREATE INDEX idx_batch_provider_id  ON batch (provider_id);
CREATE INDEX idx_batch_status       ON batch (status);

-- 补全 reduce_tier.batch_id FK（reduce_tier 在 batch 之前建立，需延迟添加 FK）
ALTER TABLE reduce_tier
    ADD CONSTRAINT fk_reduce_tier_batch
        FOREIGN KEY (batch_id) REFERENCES batch(id) ON DELETE CASCADE;

-- =============================================================================
-- 6. CASE 案件
--    ERD/契约出入（均已纳入）：
--      - acct_no, arrearags_periods, litigation_fields, closed_at 来自契约审计 v0.3/v1.0-rc1
--      - pool, source 派单公海字段来自契约 H-04
--      - VOIDED 状态来自契约 codex 审计
--      - t_collector_deadline 对应契约 tCollectorDeadlineAt
-- =============================================================================
CREATE TABLE "case" (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id                BIGINT      NOT NULL,
    project_id              BIGINT      NOT NULL,
    project_name            TEXT        NOT NULL,   -- 冗余高频展示字段
    -- 户号（同批唯一 BR-M2-14）
    acct_no                 TEXT        NOT NULL,
    owner_name              TEXT        NOT NULL,
    room                    TEXT        NOT NULL,
    due_cents               BIGINT      NOT NULL,   -- 应收（分）
    reduce_after_cents      BIGINT,                  -- 减免后应收（分）
    -- 欠费周期 JSON array，如 ["2025-01","2025-02"]。必填 BR-M2-14/15
    arrearags_periods       JSONB       NOT NULL DEFAULT '[]',
    -- 诉讼要素（可后补 BR-M4-18a）：{idCard, buildingArea, mailingAddr, contractNo}
    litigation_fields       JSONB,
    status                  TEXT        NOT NULL DEFAULT 'PENDING_DISPATCH',
    -- 法务阶段（与 status 正交，边催边诉 D2）
    legal_stage             TEXT        NOT NULL DEFAULT 'NONE',
    -- 公海/私海归属
    pool                    TEXT        NOT NULL DEFAULT 'PLATFORM_SEA',
    -- 案件来源：DISPATCH=派单 CLAIM=抢单 ASSIGN=分配 RETURN=退回
    source                  TEXT,
    -- 持有催收员（私海）
    holder_id               BIGINT,
    t_collector_deadline    TIMESTAMPTZ,    -- 催收员无跟进释放时限 CFG-TC
    t2_deadline             TIMESTAMPTZ,    -- 服务商处置时限 BR-M3 T2
    closed_kind             TEXT,
    closed_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_case_batch     FOREIGN KEY (batch_id)   REFERENCES batch(id)    ON DELETE RESTRICT,
    CONSTRAINT fk_case_project   FOREIGN KEY (project_id) REFERENCES project(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_case_holder    FOREIGN KEY (holder_id)  REFERENCES account(id)  ON DELETE SET NULL,
    CONSTRAINT chk_case_status   CHECK (status IN (
        'PENDING_DISPATCH','PROVIDER_SEA','IN_PROGRESS','PROMISED',
        'SETTLED','WITHDRAWN','BAD_DEBT','VOIDED'
    )),
    CONSTRAINT chk_case_legal_stage CHECK (legal_stage IN (
        'NONE','FUNCTION_LETTER','LAWYER_LETTER','LITIGATION','DELIVERED'
    )),
    CONSTRAINT chk_case_pool     CHECK (pool IN (
        'PLATFORM_SEA','PROVIDER_SEA','OPEN_POOL','PRIVATE'
    )),
    CONSTRAINT chk_case_close_kind CHECK (closed_kind IS NULL OR closed_kind IN (
        'WITHDRAWN','BAD_DEBT'
    )),
    CONSTRAINT uq_case_batch_acct UNIQUE (batch_id, acct_no)   -- 同批户号唯一 BR-M2-14
);

COMMENT ON TABLE  "case"                    IS '案件。VOIDED=误传纠错作废（仅PENDING_DISPATCH可作废 BR-M2-17）。BIZ_DUP_ACCT防重';
COMMENT ON COLUMN "case".acct_no            IS '户号（同批唯一·导入/手工新增均校验 BR-M2-14）';
COMMENT ON COLUMN "case".arrearags_periods  IS '欠费周期 JSON array，如["2025-01","2025-02"]。必填 BR-M2-14/15；契约 arrearagePeriods';
COMMENT ON COLUMN "case".litigation_fields  IS '诉讼要素（可后补）{idCard,buildingArea,mailingAddr,contractNo}。BR-M4-18a';
COMMENT ON COLUMN "case".legal_stage        IS 'LegalStage：与status正交（边催边诉）。看板/报表按 ≠NONE 过滤 D2';
COMMENT ON COLUMN "case".pool               IS 'PoolEnum: PLATFORM_SEA/PROVIDER_SEA/OPEN_POOL/PRIVATE';
COMMENT ON COLUMN "case".closed_at          IS '结案时间（SETTLED/WITHDRAWN/BAD_DEBT 时填充）';
COMMENT ON COLUMN "case".reduce_after_cents IS '减免后应收（分）；初始=due_cents，提交生效减免后更新';

CREATE INDEX idx_case_batch_id      ON "case" (batch_id);
CREATE INDEX idx_case_project_id    ON "case" (project_id);
CREATE INDEX idx_case_holder_id     ON "case" (holder_id);
CREATE INDEX idx_case_status        ON "case" (status);
CREATE INDEX idx_case_legal_stage   ON "case" (legal_stage);
CREATE INDEX idx_case_pool          ON "case" (pool);

-- =============================================================================
-- 7. CONTACT 联系方式
-- =============================================================================
CREATE TABLE contact (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id     BIGINT      NOT NULL,
    phone       TEXT        NOT NULL,
    label       TEXT,            -- 如"业主本人"、"紧急联系人"
    is_primary  BOOLEAN     NOT NULL DEFAULT FALSE,
    invalid     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_contact_case FOREIGN KEY (case_id) REFERENCES "case"(id) ON DELETE CASCADE
);

COMMENT ON TABLE contact            IS '联系方式（案件多联系人 BR-M4-11）';
COMMENT ON COLUMN contact.label     IS '联系人标签（可选）';
COMMENT ON COLUMN contact.is_primary IS '是否主要联系方式';
COMMENT ON COLUMN contact.invalid   IS '是否已失效';

CREATE INDEX idx_contact_case_id  ON contact (case_id);
CREATE INDEX idx_contact_phone    ON contact (phone);

-- =============================================================================
-- 8. ACTIVITY 时间线（统一活动流）
-- =============================================================================
CREATE TABLE activity (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id     BIGINT      NOT NULL,
    type        TEXT        NOT NULL,
    actor_id    BIGINT,          -- 操作人（系统触发为 NULL）
    -- 契约 actor(string 展示名) + actorId；actor 由应用层 JOIN 填充，DB 存 actor_id
    content     TEXT        NOT NULL,
    -- 关联对象（录音/工单/存证/支付链接 ERD ACTIVITY.ref）
    ref_type    TEXT,            -- 关联类型
    ref_id      BIGINT,          -- 关联 ID
    -- 跟进附加：method/attachments（H-06 扩展字段）
    method      TEXT,            -- CALL/SMS/VISIT/WECHAT/OTHER
    attachments JSONB,           -- [{name,url}]
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_activity_case   FOREIGN KEY (case_id)  REFERENCES "case"(id)    ON DELETE CASCADE,
    CONSTRAINT fk_activity_actor  FOREIGN KEY (actor_id) REFERENCES account(id)   ON DELETE SET NULL,
    CONSTRAINT chk_activity_type  CHECK (type IN (
        'CALL','NOTE','TICKET','SMS','EVIDENCE','PROMISE','LEGAL','STATUS','OPLOG'
    ))
);

COMMENT ON TABLE  activity          IS '时间线（统一活动流）。BR-M4-03a';
COMMENT ON COLUMN activity.type     IS 'ActivityType: CALL/NOTE/TICKET/SMS/EVIDENCE/PROMISE/LEGAL/STATUS/OPLOG';
COMMENT ON COLUMN activity.ref_type IS '关联对象类型（录音/工单/存证/支付链接），用于时间线跳转';
COMMENT ON COLUMN activity.method   IS '跟进方式（H-06）: CALL/SMS/VISIT/WECHAT/OTHER';

CREATE INDEX idx_activity_case_id ON activity (case_id);
CREATE INDEX idx_activity_type    ON activity (case_id, type);

-- =============================================================================
-- 9. CALL_RECORDING 通话录音
--    BR-M4-01b：App 本机通话结束自动检测上传/手动上传
-- =============================================================================
CREATE TABLE call_recording (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id         BIGINT      NOT NULL,
    collector_id    BIGINT      NOT NULL,
    source          TEXT        NOT NULL DEFAULT 'MANUAL',
    status          TEXT        NOT NULL DEFAULT 'NO_FILE',
    recorded_at     TIMESTAMPTZ,
    duration_sec    INTEGER,
    phone           TEXT,
    transcript      TEXT,        -- ASR 转写文本（解析完成后填充）
    failure_code    TEXT,        -- FAILED 时（音质差/格式/无声道）
    failure_message TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_call_recording_case      FOREIGN KEY (case_id)      REFERENCES "case"(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_call_recording_collector FOREIGN KEY (collector_id) REFERENCES account(id)    ON DELETE RESTRICT,
    CONSTRAINT chk_call_recording_source   CHECK (source IN ('APP_AUTO','MANUAL')),
    CONSTRAINT chk_call_recording_status   CHECK (status IN ('NO_FILE','UPLOADING','PARSING','READY','FAILED','QUOTA_BLOCKED'))
);

COMMENT ON TABLE  call_recording          IS '通话录音。BR-M4-01b：App自动上传/手动上传同一解析链路';
COMMENT ON COLUMN call_recording.source   IS 'APP_AUTO=App通话结束自动检测上传 / MANUAL=手动上传';
COMMENT ON COLUMN call_recording.status   IS 'CallRecStatus: NO_FILE→UPLOADING→PARSING→READY|FAILED';
COMMENT ON COLUMN call_recording.failure_code IS 'FAILED 时错误码（音质差/格式/无声道等）';

CREATE INDEX idx_call_recording_case_id  ON call_recording (case_id);
CREATE INDEX idx_call_recording_collector ON call_recording (collector_id);
CREATE INDEX idx_call_recording_status   ON call_recording (status);

-- =============================================================================
-- 10. AI_REVIEW AI复盘（解析后 BR-M5-04a）
-- =============================================================================
CREATE TABLE ai_review (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    call_id     BIGINT      NOT NULL,
    summary     TEXT        NOT NULL,
    -- 对话记录 [{speaker, text}]
    dialogue    JSONB       NOT NULL DEFAULT '[]',
    -- 风险标注 [{level, desc, segmentTs}]
    risks       JSONB       NOT NULL DEFAULT '[]',
    -- 下一步建议 [string]
    suggestions JSONB       NOT NULL DEFAULT '[]',
    result_mark TEXT,           -- MarkCode（通话结果标记 BR-M4-03a）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ai_review_call   FOREIGN KEY (call_id) REFERENCES call_recording(id) ON DELETE CASCADE,
    CONSTRAINT uq_ai_review_call   UNIQUE (call_id),
    CONSTRAINT chk_ai_review_mark  CHECK (result_mark IS NULL OR result_mark IN (
        'PROMISED','REFUSED','NEED_TICKET','FOLLOW_UP','NO_ANSWER'
    ))
);

COMMENT ON TABLE  ai_review            IS 'AI复盘（通话解析后自动生成）。BR-M5-04a';
COMMENT ON COLUMN ai_review.result_mark IS 'MarkCode：通话结果标记（接通有效则重置 T_collector）';

CREATE INDEX idx_ai_review_call_id ON ai_review (call_id);

-- =============================================================================
-- 11. PROMISE 承诺
--    BR-M4-13：承诺/分期履约
-- =============================================================================
CREATE TABLE promise (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id     BIGINT      NOT NULL,
    date        DATE        NOT NULL,    -- 单笔承诺日期（分期时为首期）
    amount_cents BIGINT     NOT NULL,
    state       TEXT        NOT NULL DEFAULT 'PENDING',
    created_by  BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_promise_case      FOREIGN KEY (case_id)    REFERENCES "case"(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_promise_created_by FOREIGN KEY (created_by) REFERENCES account(id)  ON DELETE RESTRICT,
    CONSTRAINT chk_promise_state    CHECK (state IN ('PENDING','FULFILLED','PARTIAL_FULFILLED','BROKEN'))
);

COMMENT ON TABLE  promise            IS '承诺（支持分期）。BR-M4-13';
COMMENT ON COLUMN promise.state      IS 'PromiseState: PENDING=待履约 FULFILLED=已兑现 BROKEN=已爽约';

CREATE INDEX idx_promise_case_id ON promise (case_id);

-- =============================================================================
-- 12. PROMISE_INSTALLMENT 承诺分期明细
--    BR-M4-13
-- =============================================================================
CREATE TABLE promise_installment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    promise_id  BIGINT      NOT NULL,
    seq         INTEGER     NOT NULL,   -- 期数序号（1-based）
    due_date    DATE        NOT NULL,
    amount_cents BIGINT     NOT NULL,
    state       TEXT        NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_installment_promise FOREIGN KEY (promise_id) REFERENCES promise(id) ON DELETE CASCADE,
    CONSTRAINT chk_installment_state  CHECK (state IN ('PENDING','FULFILLED','BROKEN')),
    CONSTRAINT uq_installment_seq     UNIQUE (promise_id, seq)
);

COMMENT ON TABLE promise_installment IS '承诺分期明细。BR-M4-13';

CREATE INDEX idx_installment_promise ON promise_installment (promise_id);

-- =============================================================================
-- 13. TICKET 工单
--    BR-M4-17/23：催收员→协调员互推闭环
-- =============================================================================
CREATE TABLE ticket (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id     BIGINT      NOT NULL,
    type        TEXT        NOT NULL,   -- 工单类型（如"上门核实"，可配 CFG）
    note        TEXT,
    from_role   TEXT,                   -- 发起角色
    to_role     TEXT,                   -- 目标角色
    status      TEXT        NOT NULL DEFAULT 'PENDING',
    result      TEXT,                   -- 处理结论（HANDLED 时）
    receipt     TEXT,                   -- 回执
    created_by  BIGINT      NOT NULL,
    handled_by  BIGINT,
    handled_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ticket_case        FOREIGN KEY (case_id)    REFERENCES "case"(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_created_by  FOREIGN KEY (created_by) REFERENCES account(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_handled_by  FOREIGN KEY (handled_by) REFERENCES account(id)  ON DELETE SET NULL,
    CONSTRAINT chk_ticket_status     CHECK (status IN ('PENDING','HANDLED'))
);

COMMENT ON TABLE  ticket            IS '工单（催收员→协调员互推闭环）。BR-M4-17/23';
COMMENT ON COLUMN ticket.status     IS 'TicketStatus: PENDING=待处理 HANDLED=已回执';

CREATE INDEX idx_ticket_case_id  ON ticket (case_id);
CREATE INDEX idx_ticket_status   ON ticket (status);

-- =============================================================================
-- 14. PAY_LINK 缴费链接
--    BR-M4-04/14/15：缴费链接发送/冷却/重发
-- =============================================================================
CREATE TABLE pay_link (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id     BIGINT      NOT NULL,
    token       TEXT        NOT NULL,
    amount_cents BIGINT     NOT NULL,   -- 减免后应收
    expires_at  TIMESTAMPTZ NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    channel     TEXT,                   -- SMS/WECHAT_COPY
    created_by  BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_pay_link_case        FOREIGN KEY (case_id)    REFERENCES "case"(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_pay_link_created_by  FOREIGN KEY (created_by) REFERENCES account(id)  ON DELETE RESTRICT,
    CONSTRAINT chk_pay_link_status     CHECK (status IN ('ACTIVE','EXPIRED')),
    CONSTRAINT uq_pay_link_token       UNIQUE (token)
);

COMMENT ON TABLE  pay_link            IS '缴费链接（业主H5凭token只读）。BR-M4-04/14/15，BR-M7-01';
COMMENT ON COLUMN pay_link.token      IS '唯一token（业主H5无登录访问）';
COMMENT ON COLUMN pay_link.amount_cents IS '金额=减免后应收（分）';

CREATE INDEX idx_pay_link_case_id ON pay_link (case_id);
CREATE INDEX idx_pay_link_token   ON pay_link (token);

-- =============================================================================
-- 15. REDUCTION 减免记录
--    BR-M2-18a：自决档/线下留痕/PL核准；无系统审批
-- =============================================================================
CREATE TABLE reduction (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id     BIGINT      NOT NULL,
    tier_ref    INTEGER,            -- 对应 reduce_tier 阶梯索引（0-based）
    discount    TEXT        NOT NULL,
    amount_cents BIGINT     NOT NULL,   -- 减免金额（分）
    decide      TEXT        NOT NULL,
    state       TEXT        NOT NULL DEFAULT 'EFFECTIVE',
    applied_by  BIGINT      NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_reduction_case       FOREIGN KEY (case_id)    REFERENCES "case"(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_reduction_applied_by FOREIGN KEY (applied_by) REFERENCES account(id)  ON DELETE RESTRICT,
    CONSTRAINT chk_reduction_decide    CHECK (decide IN ('COLLECTOR_SELF','OFFLINE_INTERNAL','PL_APPROVE')),
    CONSTRAINT chk_reduction_state     CHECK (state  IN ('EFFECTIVE','OFFLINE_TRACE'))
);

COMMENT ON TABLE  reduction          IS '减免记录。自决档→EFFECTIVE；超自决档→OFFLINE_TRACE（系统不审批）。BR-M2-18a';
COMMENT ON COLUMN reduction.decide   IS 'ReduceDecide: COLLECTOR_SELF/OFFLINE_INTERNAL/PL_APPROVE';
COMMENT ON COLUMN reduction.state    IS 'ReduceState: EFFECTIVE=生效 OFFLINE_TRACE=线下留痕（系统不进审批）';

CREATE INDEX idx_reduction_case_id ON reduction (case_id);

-- =============================================================================
-- 16. REPAY_LINE 回款明细（结算单元 BR-M9-12a）
-- =============================================================================
CREATE TABLE repay_line (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id             BIGINT      NOT NULL,
    batch_id            BIGINT      NOT NULL,
    amount_cents        BIGINT      NOT NULL,
    channel             TEXT        NOT NULL,
    paid_at             DATE        NOT NULL,
    note                TEXT,
    marked_by           BIGINT      NOT NULL,   -- 协调员标注 US-M4-08
    settled             BOOLEAN     NOT NULL DEFAULT FALSE,
    payment_request_id  BIGINT,                  -- 纳入哪张支付申请单（未结为 NULL）
    -- 冲正标记（红冲联动 BR-M4-07）
    reversed            BOOLEAN     NOT NULL DEFAULT FALSE,
    reverse_reason      TEXT,
    reversed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_repay_line_case    FOREIGN KEY (case_id)    REFERENCES "case"(id)            ON DELETE RESTRICT,
    CONSTRAINT fk_repay_line_batch   FOREIGN KEY (batch_id)   REFERENCES batch(id)             ON DELETE RESTRICT,
    CONSTRAINT fk_repay_line_marked  FOREIGN KEY (marked_by)  REFERENCES account(id)           ON DELETE RESTRICT,
    -- payment_request_id FK 在 PAYMENT_REQUEST 建完后添加（见文件末）
    CONSTRAINT chk_repay_line_channel CHECK (channel IN ('WECHAT_QR','BANK_TRANSFER','CASH'))
);

COMMENT ON TABLE  repay_line                  IS '回款明细（结算单元）。取消 dispute_id（案件级在线异议作废 BR-M9-12c）';
COMMENT ON COLUMN repay_line.settled          IS '是否已结算（纳入 PAID 支付申请单后=true）';
COMMENT ON COLUMN repay_line.payment_request_id IS '纳入哪张支付申请单（FK 在 PAYMENT_REQUEST 建完后添加）';

CREATE INDEX idx_repay_line_case_id          ON repay_line (case_id);
CREATE INDEX idx_repay_line_batch_id         ON repay_line (batch_id);
CREATE INDEX idx_repay_line_settled          ON repay_line (settled);
CREATE INDEX idx_repay_line_payment_request  ON repay_line (payment_request_id);

-- =============================================================================
-- 17. PAYMENT_REQUEST 支付申请单
--    BR-M9-12a/b/d：收款方生成/撤销重生成/完成必留凭证
--    资金双线：IN=收佣（平台↔物业）OUT=付佣（平台↔服务商）
-- =============================================================================
CREATE TABLE payment_request (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no              TEXT        NOT NULL,        -- 单号 PR-IN/OUT-batch-seq
    side            TEXT        NOT NULL,        -- IN/OUT
    batch_id        BIGINT      NOT NULL,
    generated_by    BIGINT      NOT NULL,        -- 服务端派生：IN=平台/OUT=服务商
    -- 本单固化的生效比率（避免批次比率变更后历史单失真 H-02）
    comm_rate       NUMERIC(6,4) NOT NULL,
    -- 明细快照（JSON array；追溯用）
    lines           JSONB       NOT NULL DEFAULT '[]',
    base_cents      BIGINT      NOT NULL,
    comm_cents      BIGINT      NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',
    completed_by    BIGINT,
    completed_at    TIMESTAMPTZ,
    voided_at       TIMESTAMPTZ,
    void_reason     TEXT,
    -- 乐观锁（并发完成/撤销 H-02）
    version         INTEGER     NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_payment_request_batch   FOREIGN KEY (batch_id)     REFERENCES batch(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_payment_request_gen_by  FOREIGN KEY (generated_by) REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_request_comp_by FOREIGN KEY (completed_by) REFERENCES account(id) ON DELETE SET NULL,
    CONSTRAINT chk_payment_request_side   CHECK (side   IN ('IN','OUT')),
    CONSTRAINT chk_payment_request_status CHECK (status IN ('PENDING','PAID','VOIDED')),
    CONSTRAINT uq_payment_request_no      UNIQUE (no)
);

COMMENT ON TABLE  payment_request              IS '支付申请单（替代对账单/结算单/在线异议）。IN=收佣线 OUT=付佣线。BR-M9-12a/b/d';
COMMENT ON COLUMN payment_request.side         IS 'ReconSide: IN=收佣（平台↔物业·平台生成）OUT=付佣（平台↔服务商·服务商生成）';
COMMENT ON COLUMN payment_request.comm_rate    IS '本单固化生效比率%（防止批次比率变更后历史单失真 H-02）';
COMMENT ON COLUMN payment_request.lines        IS '明细快照 JSON [{lineId,caseId,ownerName,room,repayCents,commCents}]';
COMMENT ON COLUMN payment_request.version      IS '乐观锁版本号（并发完成/撤销）';

CREATE INDEX idx_payment_request_batch   ON payment_request (batch_id);
CREATE INDEX idx_payment_request_status  ON payment_request (status);
CREATE INDEX idx_payment_request_side    ON payment_request (side, status);

-- 补全 repay_line.payment_request_id FK
ALTER TABLE repay_line
    ADD CONSTRAINT fk_repay_line_payment_request
        FOREIGN KEY (payment_request_id) REFERENCES payment_request(id) ON DELETE SET NULL;

-- =============================================================================
-- 18. VOUCHER 收款/支付凭证
--    BR-M9-12d：完成支付申请单必留凭证
-- =============================================================================
CREATE TABLE voucher (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_request_id  BIGINT      NOT NULL,
    type                TEXT        NOT NULL,   -- RECEIPT=收款凭证 PAYMENT=支付凭证
    file_url            TEXT        NOT NULL,
    uploaded_by         BIGINT      NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_voucher_payment_request FOREIGN KEY (payment_request_id) REFERENCES payment_request(id) ON DELETE CASCADE,
    CONSTRAINT fk_voucher_uploaded_by     FOREIGN KEY (uploaded_by)        REFERENCES account(id)          ON DELETE RESTRICT,
    CONSTRAINT uq_voucher_payment_request UNIQUE (payment_request_id),  -- 每单最多一张凭证
    CONSTRAINT chk_voucher_type           CHECK (type IN ('RECEIPT','PAYMENT'))
);

COMMENT ON TABLE  voucher            IS '收款/支付凭证。PAID 必有（BR-M9-12d）';
COMMENT ON COLUMN voucher.type       IS 'RECEIPT=收款凭证（收佣线）/ PAYMENT=支付凭证（付佣线）';

-- =============================================================================
-- 19. CO_COMMISSION 催收员佣金（服务商内部·人×批次 BR-M9-19）
-- =============================================================================
CREATE TABLE co_commission (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collector_id    BIGINT      NOT NULL,
    batch_id        BIGINT      NOT NULL,
    -- 催收员佣金比例%（≤ batch.pay_out_rate·防倒挂 US-M9-02）
    rate            NUMERIC(6,4) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_co_comm_collector FOREIGN KEY (collector_id) REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT fk_co_comm_batch     FOREIGN KEY (batch_id)     REFERENCES batch(id)   ON DELETE RESTRICT,
    CONSTRAINT uq_co_comm_coll_batch UNIQUE (collector_id, batch_id)
);

COMMENT ON TABLE  co_commission      IS '催收员佣金（人×批次）。应得/已结/未结由 REPAY_LINE×rate 汇总（非存储）。BR-M9-19';
COMMENT ON COLUMN co_commission.rate IS '催收员佣金比例%（须 ≤ batch.pay_out_rate 防倒挂 US-M9-02）';

CREATE INDEX idx_co_commission_collector ON co_commission (collector_id);
CREATE INDEX idx_co_commission_batch     ON co_commission (batch_id);

-- =============================================================================
-- 20. CO_PAY_DOC 佣金支付单据
--    BR-M9-19：人→批次→明细→生成支付单→确认支付=结算
-- =============================================================================
CREATE TABLE co_pay_doc (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collector_id    BIGINT      NOT NULL,
    -- line_ids 存为 JSONB array（应用层维护与 repay_line 的关联）
    line_ids        JSONB       NOT NULL DEFAULT '[]',
    count           INTEGER     NOT NULL,   -- 明细笔数
    amount_cents    BIGINT      NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING_PAY',
    tm              TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 生成时间
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_co_pay_doc_collector FOREIGN KEY (collector_id) REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT chk_co_pay_doc_status   CHECK (status IN ('PENDING_PAY','SETTLED'))
);

COMMENT ON TABLE  co_pay_doc          IS '佣金支付单据。BR-M9-19：勾选未结明细→PENDING_PAY→确认支付→SETTLED+锁定line.settled';
COMMENT ON COLUMN co_pay_doc.line_ids IS 'JSONB array of repay_line.id（应用层维护 line.settled 锁定）';

CREATE INDEX idx_co_pay_doc_collector ON co_pay_doc (collector_id);
CREATE INDEX idx_co_pay_doc_status    ON co_pay_doc (status);

-- co_pay_doc ↔ repay_line 多对多关联表（每笔明细按笔锁定 ERD CO_PAY_DOC）
-- 【出入说明】ERD 用 line_ids[] 字段，此处额外建关联表确保可查、可对账
CREATE TABLE co_pay_doc_line (
    co_pay_doc_id   BIGINT  NOT NULL,
    repay_line_id   BIGINT  NOT NULL,
    PRIMARY KEY (co_pay_doc_id, repay_line_id),
    CONSTRAINT fk_copayline_doc  FOREIGN KEY (co_pay_doc_id)  REFERENCES co_pay_doc(id)  ON DELETE CASCADE,
    CONSTRAINT fk_copayline_line FOREIGN KEY (repay_line_id)  REFERENCES repay_line(id)  ON DELETE RESTRICT
);

COMMENT ON TABLE co_pay_doc_line IS '佣金支付单据↔回款明细关联（按笔锁定）。BR-M9-19';

-- =============================================================================
-- 21. RISK_RECORD 质检风险
--    BR-M5-07：全量检测；三方隔离（物业/服务商/平台）
-- =============================================================================
CREATE TABLE risk_record (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id         BIGINT      NOT NULL,
    call_id         BIGINT      NOT NULL,
    collector_id    BIGINT      NOT NULL,
    provider_id     BIGINT      NOT NULL,   -- 服务商 org_id
    property_id     BIGINT      NOT NULL,   -- 物业 org_id（三方隔离查询）
    -- type: 可配风险类型 CFG-RISK-TYPES
    type            TEXT        NOT NULL,
    level           TEXT        NOT NULL,
    segment_ts      TEXT,                   -- 音频片段时间戳
    reviewed        TEXT,                   -- RiskReviewVerdict（NULL=未复核）
    reviewed_by     BIGINT,
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_risk_case        FOREIGN KEY (case_id)      REFERENCES "case"(id)          ON DELETE RESTRICT,
    CONSTRAINT fk_risk_call        FOREIGN KEY (call_id)      REFERENCES call_recording(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_risk_collector   FOREIGN KEY (collector_id) REFERENCES account(id)         ON DELETE RESTRICT,
    CONSTRAINT fk_risk_provider    FOREIGN KEY (provider_id)  REFERENCES org(id)             ON DELETE RESTRICT,
    CONSTRAINT fk_risk_property    FOREIGN KEY (property_id)  REFERENCES org(id)             ON DELETE RESTRICT,
    CONSTRAINT fk_risk_reviewed_by FOREIGN KEY (reviewed_by)  REFERENCES account(id)         ON DELETE SET NULL,
    CONSTRAINT chk_risk_level      CHECK (level    IN ('HIGH','MID','LOW')),
    CONSTRAINT chk_risk_reviewed   CHECK (reviewed IS NULL OR reviewed IN (
        'CONFIRMED','FALSE_POSITIVE','ESCALATED'
    ))
);

COMMENT ON TABLE  risk_record          IS '质检风险（全量检测）。三方隔离：物业看本物业/服务商看本商/平台全局 BR-M5-07';
COMMENT ON COLUMN risk_record.reviewed IS 'RiskReviewVerdict: CONFIRMED=确认 FALSE_POSITIVE=误报撤销 ESCALATED=升级平台';

CREATE INDEX idx_risk_case_id       ON risk_record (case_id);
CREATE INDEX idx_risk_call_id       ON risk_record (call_id);
CREATE INDEX idx_risk_provider_id   ON risk_record (provider_id);
CREATE INDEX idx_risk_property_id   ON risk_record (property_id);
CREATE INDEX idx_risk_level         ON risk_record (level);

-- =============================================================================
-- 22. DISPOSE_TASK 风险处置任务
--    BR-M5-07b：仅平台监管视图；实质处置归所属组织负责人
-- =============================================================================
CREATE TABLE dispose_task (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    risk_id     BIGINT      NOT NULL,
    -- provider 字段存组织 ID（org_id）；展示名由 JOIN 填充
    provider    BIGINT      NOT NULL,   -- 服务商 org_id
    task_type   TEXT        NOT NULL,   -- 整改/培训
    status      TEXT        NOT NULL DEFAULT 'PENDING',
    tm          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_dispose_risk     FOREIGN KEY (risk_id)  REFERENCES risk_record(id) ON DELETE RESTRICT,
    CONSTRAINT fk_dispose_provider FOREIGN KEY (provider) REFERENCES org(id)         ON DELETE RESTRICT,
    CONSTRAINT chk_dispose_status  CHECK (status IN ('PENDING','DONE'))
);

COMMENT ON TABLE  dispose_task      IS '风险处置任务（仅平台监管视图）。实质处置归所属组织负责人。BR-M5-07b';

CREATE INDEX idx_dispose_risk_id ON dispose_task (risk_id);
CREATE INDEX idx_dispose_status  ON dispose_task (status);

-- =============================================================================
-- 23. PLAYBOOK 作战手册
--    BR-M5-05a：采纳人=PL或PC；BR-M5-05b：分级采纳闸
-- =============================================================================
CREATE TABLE playbook (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT      NOT NULL,
    version     TEXT        NOT NULL,
    content     TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'DRAFT',
    adopt_mode  TEXT        NOT NULL DEFAULT 'FORCE_MANUAL',
    adopted_by  BIGINT,
    adopted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_playbook_project   FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_playbook_adopted   FOREIGN KEY (adopted_by) REFERENCES account(id) ON DELETE SET NULL,
    CONSTRAINT chk_playbook_status   CHECK (status     IN ('DRAFT','PUBLISHED','ARCHIVED')),
    CONSTRAINT chk_playbook_mode     CHECK (adopt_mode IN ('FORCE_MANUAL','LOW_RISK_AUTO'))
);

COMMENT ON TABLE  playbook            IS '作战手册（平台护城河）。BR-M5-05a：PL或PC采纳；BR-M5-05b：分级采纳闸';
COMMENT ON COLUMN playbook.adopt_mode IS 'PlaybookAdoptMode: FORCE_MANUAL=强制人工采纳 LOW_RISK_AUTO=低风险自动采纳（可回滚）';

CREATE INDEX idx_playbook_project_id ON playbook (project_id);

-- =============================================================================
-- 24. SCRIPT_LIB 话术库（平台护城河 BR-M5-06/06a）
--    M5 RAG：embedding vector(1024) 用于语义检索
--    【pgvector】需安装 vector 扩展（文件头已 CREATE EXTENSION）
-- =============================================================================
CREATE TABLE script_lib (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scene           TEXT        NOT NULL,
    intent          TEXT,
    cohort          TEXT,
    source          TEXT        NOT NULL DEFAULT 'EXPERT',
    uses            INTEGER     NOT NULL DEFAULT 0,
    promise_rate    NUMERIC(6,4),   -- 承诺率%（百分比，如 45.0000=45%）
    repay_rate      NUMERIC(6,4),   -- 回款率%
    wilson          NUMERIC(8,6),   -- Wilson 置信下界（效果排名 BR-M5-12a）
    status          TEXT        NOT NULL DEFAULT 'CANDIDATE',
    -- 优化变体 {text, uplift, state}（自我迭代 BR-M5-12a）
    variant         JSONB,
    -- RAG 向量（1024维，可按实际模型调整；pgvector 安装后生效）
    embedding       vector(1024),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_script_source CHECK (source IN ('AI_MINED','EXPERT')),
    CONSTRAINT chk_script_status CHECK (status IN ('EFFECTIVE','CANDIDATE','RETIRED'))
);

COMMENT ON TABLE  script_lib          IS '话术库（仅平台可见可管，平台护城河）。BR-M5-06/06a；embedding=RAG语义检索（pgvector，维度可调）';
COMMENT ON COLUMN script_lib.embedding IS 'RAG 向量 vector(1024)；维度按实际 embedding 模型调整（如 text-embedding-ada-002=1536）';
COMMENT ON COLUMN script_lib.wilson    IS 'Wilson 置信下界（效果排名，BR-M5-12a 自我迭代依据）';

CREATE INDEX idx_script_lib_status  ON script_lib (status);
CREATE INDEX idx_script_lib_source  ON script_lib (source);
CREATE INDEX idx_script_lib_scene   ON script_lib (scene);
-- 向量近邻索引（HNSW，生产环境建议开启；需 pgvector 0.5+）
-- CREATE INDEX idx_script_lib_emb ON script_lib USING hnsw (embedding vector_cosine_ops);

-- =============================================================================
-- 25. BILLING_USAGE 能力用量（只用量不金额 US-M10-02）
-- =============================================================================
CREATE TABLE billing_usage (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id      BIGINT      NOT NULL,
    type        TEXT        NOT NULL,
    qty         NUMERIC(12,3) NOT NULL,   -- 用量（分钟/条/次/件）
    unit        TEXT        NOT NULL,     -- 分钟/条/次/件
    case_id     BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_billing_org  FOREIGN KEY (org_id)  REFERENCES org(id)    ON DELETE RESTRICT,
    CONSTRAINT fk_billing_case FOREIGN KEY (case_id) REFERENCES "case"(id) ON DELETE SET NULL,
    CONSTRAINT chk_billing_type CHECK (type IN ('STT','SMS','EVIDENCE','LEGAL'))
);

COMMENT ON TABLE  billing_usage      IS '能力用量（只用量不金额 US-M10-02）。按月/日/明细下钻 BR-M9-06b';
COMMENT ON COLUMN billing_usage.type IS 'BillingType: STT=语音转写(分钟) SMS=短信(条) EVIDENCE=存证(次) LEGAL=法律服务(件)';

CREATE INDEX idx_billing_org_id     ON billing_usage (org_id);
CREATE INDEX idx_billing_type       ON billing_usage (org_id, type);
CREATE INDEX idx_billing_occurred   ON billing_usage (occurred_at);
