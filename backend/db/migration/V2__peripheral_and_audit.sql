-- =============================================================================
-- 迁移版本  : V2（依赖 V1 先行执行完毕）
-- 依据      : ERD.md v0.1 + openapi-core.yaml v1.0.0-rc1（外围模块 v1.0 收口）
-- PostgreSQL: ≥ 14
-- 覆盖      :
--   1. project_coordinators / batch_coordinators（协调员↔项目/批次多对多）
--   2. EVIDENCE / LEGAL_DOC（存证 M6 / 法律文书 M4）
--   3. RECHARGE_LOG（充值/扣减流水 M9-B）
--   4. SETTINGS（业务规则配置 M3/M4）
--   5. AUDIT_LOG（审计日志 M1）
--   6. PERMISSION（权限矩阵条目 M1）
--   7. 契约审计补充字段（V1 建表时已全部纳入；此文件仅补外围实体）
-- =============================================================================

-- =============================================================================
-- 1. project_coordinators — 项目↔协调员多对多
--    BR-M2-13/US-M2-02：物业负责人挂载多个PC，决定其可见案件范围
-- =============================================================================
CREATE TABLE project_coordinators (
    project_id      BIGINT NOT NULL,
    coordinator_id  BIGINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, coordinator_id),
    CONSTRAINT fk_proj_coord_project FOREIGN KEY (project_id)     REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_proj_coord_account FOREIGN KEY (coordinator_id) REFERENCES account(id) ON DELETE CASCADE
);

COMMENT ON TABLE project_coordinators IS '项目↔协调员多对多关联（PC可见案件范围 BR-M2-13/US-M2-02）';

CREATE INDEX idx_proj_coord_coordinator ON project_coordinators (coordinator_id);

-- =============================================================================
-- 2. batch_coordinators — 批次↔协调员多对多
--    BR-M2-13：PC↔批次多对多·变更写审计
-- =============================================================================
CREATE TABLE batch_coordinators (
    batch_id        BIGINT NOT NULL,
    coordinator_id  BIGINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch_id, coordinator_id),
    CONSTRAINT fk_batch_coord_batch   FOREIGN KEY (batch_id)       REFERENCES batch(id)   ON DELETE CASCADE,
    CONSTRAINT fk_batch_coord_account FOREIGN KEY (coordinator_id) REFERENCES account(id) ON DELETE CASCADE
);

COMMENT ON TABLE batch_coordinators IS '批次↔协调员多对多关联（PC↔批次多对多·变更写审计 BR-M2-13）';

CREATE INDEX idx_batch_coord_coordinator ON batch_coordinators (coordinator_id);
CREATE INDEX idx_batch_coord_batch       ON batch_coordinators (batch_id);

-- =============================================================================
-- 3. EVIDENCE 存证
--    M6：三方隔离（只向物业计费）；送达/录音/材料打包三场景·按次计费
-- =============================================================================
CREATE TABLE evidence (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id      BIGINT      NOT NULL,   -- 归属物业组织（三方隔离查询）
    case_id     BIGINT      NOT NULL,
    scene       TEXT        NOT NULL,
    -- 关联录音/工单/材料 id（JSON array of bigint）
    ref_ids     JSONB       NOT NULL DEFAULT '[]',
    status      TEXT        NOT NULL DEFAULT 'ISSUING',
    cert_no     TEXT,                   -- 存证证书编号（成功后填充）
    cert_url    TEXT,                   -- 证书文件地址
    issued_at   TIMESTAMPTZ,
    note        TEXT,
    created_by  BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_evidence_org        FOREIGN KEY (org_id)     REFERENCES org(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_case       FOREIGN KEY (case_id)    REFERENCES "case"(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_created_by FOREIGN KEY (created_by) REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT chk_evidence_scene     CHECK (scene  IN ('DELIVERY','RECORDING','MATERIAL_PACK')),
    CONSTRAINT chk_evidence_status    CHECK (status IN ('ISSUING','ISSUED','FAILED'))
);

COMMENT ON TABLE  evidence         IS '存证（M6）。三方隔离：物业本物业/平台全局；服务商不可见。按次计费 BR-M6';
COMMENT ON COLUMN evidence.scene   IS 'EvidenceScene: DELIVERY=送达 RECORDING=录音 MATERIAL_PACK=材料打包';
COMMENT ON COLUMN evidence.status  IS 'EvidenceStatus: ISSUING=出证中 ISSUED=已出证 FAILED=失败';
COMMENT ON COLUMN evidence.ref_ids IS '关联录音/工单/材料 id（JSON array）';

CREATE INDEX idx_evidence_org_id   ON evidence (org_id);
CREATE INDEX idx_evidence_case_id  ON evidence (case_id);
CREATE INDEX idx_evidence_status   ON evidence (status);

-- =============================================================================
-- 4. LEGAL_DOC 法律文书
--    BR-M4-18：申请→生成PDF→线下送达→签收拍照→存证
--    legal_stage 由送达/文书动作派生（非独立推进）
-- =============================================================================
CREATE TABLE legal_doc (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id         BIGINT      NOT NULL,
    type            TEXT        NOT NULL,
    template_id     TEXT,                   -- 指定模板（NULL=按类型默认）
    status          TEXT        NOT NULL DEFAULT 'GENERATING',
    pdf_url         TEXT,                   -- 生成的 PDF（文件通道TBD）
    delivered_at    TIMESTAMPTZ,
    signed_photo_url TEXT,                  -- 签收拍照（送达凭证）
    -- 关联存证（签收后存证）
    evidence_id     BIGINT,
    note            TEXT,
    created_by      BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_legal_doc_case        FOREIGN KEY (case_id)    REFERENCES "case"(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_legal_doc_created_by  FOREIGN KEY (created_by) REFERENCES account(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_legal_doc_evidence    FOREIGN KEY (evidence_id) REFERENCES evidence(id) ON DELETE SET NULL,
    CONSTRAINT chk_legal_doc_type   CHECK (type   IN ('COLLECTION_LETTER','LAWYER_LETTER','LITIGATION')),
    CONSTRAINT chk_legal_doc_status CHECK (status IN ('GENERATING','GENERATED','DELIVERED','SIGNED','ARCHIVED'))
);

COMMENT ON TABLE  legal_doc             IS '法律文书（催收函/律师函/起诉状）。BR-M4-18：申请→PDF→送达→签收→存证';
COMMENT ON COLUMN legal_doc.type        IS 'LegalDocType: COLLECTION_LETTER=催收函 LAWYER_LETTER=律师函 LITIGATION=起诉状';
COMMENT ON COLUMN legal_doc.status      IS 'LegalDocStatus: GENERATING/GENERATED/DELIVERED/SIGNED/ARCHIVED';
COMMENT ON COLUMN legal_doc.evidence_id IS '签收后关联存证（可选 BR-M4-18）';

CREATE INDEX idx_legal_doc_case_id ON legal_doc (case_id);
CREATE INDEX idx_legal_doc_status  ON legal_doc (status);

-- =============================================================================
-- 5. RECHARGE_LOG 充值/扣减流水
--    BR-M9-06a：平台后台给物业/服务商充值能力额度（不开放自助）
-- =============================================================================
CREATE TABLE recharge_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id      BIGINT      NOT NULL,
    type        TEXT        NOT NULL,
    -- delta > 0 = 充值，delta < 0 = 扣减
    delta       NUMERIC(12,3) NOT NULL,
    balance     NUMERIC(14,3) NOT NULL,   -- 操作后余额（快照）
    -- ref：关联单据号（支付申请单 no / 存证单号等）
    ref         TEXT,
    note        TEXT,
    operated_by BIGINT      NOT NULL,    -- 平台操作员
    tm          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_recharge_org        FOREIGN KEY (org_id)      REFERENCES org(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_recharge_operated   FOREIGN KEY (operated_by) REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT chk_recharge_type      CHECK (type IN ('STT','SMS','EVIDENCE','LEGAL'))
);

COMMENT ON TABLE  recharge_log       IS '充值/扣减流水（M9-B）。delta>0=充值/delta<0=扣减。BR-M9-06a';
COMMENT ON COLUMN recharge_log.delta IS '+充值/-扣减（用量单位：分钟/条/次/件）';

CREATE INDEX idx_recharge_org_id  ON recharge_log (org_id);
CREATE INDEX idx_recharge_tm      ON recharge_log (tm);

-- =============================================================================
-- 6. SETTINGS 业务规则配置
--    BR-M3-19/M4-12/14a：计时器/轮转/标记码/关闭原因/短信冷却
--    带版本/生效时间：参数变更只对新计时案件生效
-- =============================================================================
CREATE TABLE settings (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    domain          TEXT        NOT NULL,
    version         INTEGER     NOT NULL DEFAULT 1,
    effective_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 各域配置存 JSONB（结构见契约 Settings schema）
    timers          JSONB,
    rotation        JSONB,
    mark_codes      JSONB,
    close_reasons   JSONB,
    sms             JSONB,
    updated_by      BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_settings_updated_by FOREIGN KEY (updated_by) REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT chk_settings_domain    CHECK (domain IN ('TIMERS','ROTATION','MARK_CODES','CLOSE_REASONS','SMS')),
    -- 同 domain 同 version 唯一（乐观锁防并发覆盖）
    CONSTRAINT uq_settings_domain_ver UNIQUE (domain, version)
);

COMMENT ON TABLE  settings            IS '业务规则配置（带版本/生效时间）。变更只对新计时案件生效。BR-M3-19';
COMMENT ON COLUMN settings.domain     IS 'SettingsDomain: TIMERS/ROTATION/MARK_CODES/CLOSE_REASONS/SMS';
COMMENT ON COLUMN settings.version    IS '配置版本（乐观锁）；每次 PUT 递增';
COMMENT ON COLUMN settings.effective_at IS '生效时间（NULL/now=立即生效，对新计时案件生效）';

CREATE INDEX idx_settings_domain ON settings (domain);

-- =============================================================================
-- 7. AUDIT_LOG 操作审计日志
--    BR-M1-08/15：授权变更/启停/登录/交接/代操作全程留痕
-- =============================================================================
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    BIGINT,          -- 操作人账号（系统触发为 NULL）
    actor       TEXT        NOT NULL,
    action      TEXT        NOT NULL,   -- 动作（如 case.void / member.disable）
    target      TEXT        NOT NULL,   -- 目标描述
    target_type TEXT,                   -- 目标类型（case/account/org 等）
    target_id   TEXT,                   -- 目标 ID（存 string，兼容多类型）
    scope       TEXT,                   -- 操作时的数据范围
    -- 代操作（平台代物业/服务商 BR-M1-15）
    proxy_for   TEXT,
    -- 变更前后快照
    before_snap JSONB,
    after_snap  JSONB,
    reason      TEXT,
    ip          TEXT,
    trace_id    TEXT,
    tm          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES account(id) ON DELETE SET NULL
);

COMMENT ON TABLE  audit_log           IS '操作审计日志（全程留痕）。BR-M1-08/15：授权变更/启停/登录/交接/代操作';
COMMENT ON COLUMN audit_log.proxy_for IS '代操作：代谁（平台代物业/服务商 BR-M1-15）';
COMMENT ON COLUMN audit_log.before_snap IS '变更前快照（JSONB）';
COMMENT ON COLUMN audit_log.after_snap  IS '变更后快照（JSONB）';

CREATE INDEX idx_audit_actor_id   ON audit_log (actor_id);
CREATE INDEX idx_audit_action     ON audit_log (action);
CREATE INDEX idx_audit_target_type ON audit_log (target_type, target_id);
CREATE INDEX idx_audit_tm         ON audit_log (tm);

-- =============================================================================
-- 8. PERMISSION 权限矩阵条目
--    BR-M1-04c：功能点×角色·可导出
--    【说明】权限矩阵是配置性数据，通常随发布固化；此表支持平台动态查询/导出
-- =============================================================================
CREATE TABLE permission (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feature     TEXT    NOT NULL,   -- 功能/模块
    role        TEXT    NOT NULL,
    permission  TEXT    NOT NULL,   -- 权限码（对齐 permMatrix）
    data_scope  TEXT    NOT NULL,   -- own-org|range|platform|case-holder|public
    allowed     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_permission_role       CHECK (role       IN ('SA','SE','PL','PC','VL','CO')),
    CONSTRAINT chk_permission_data_scope CHECK (data_scope IN (
        'own-org','range','platform','case-holder','public'
    )),
    CONSTRAINT uq_permission_role_perm UNIQUE (role, permission, feature)
);

COMMENT ON TABLE permission IS '权限矩阵条目（功能点×角色·可导出 BR-M1-04c）';

CREATE INDEX idx_permission_role ON permission (role);

-- =============================================================================
-- 自检注解（SQL 层静态自查，无可用 psql 则跳过实际执行）
-- =============================================================================
-- 建表清单（V1+V2 合计）：
--   V1: org, account, project, reduce_tier, batch, case, contact,
--       activity, call_recording, ai_review, promise, promise_installment,
--       ticket, pay_link, reduction, repay_line, payment_request, voucher,
--       co_commission, co_pay_doc, co_pay_doc_line,
--       risk_record, dispose_task, playbook, script_lib, billing_usage
--       (26 张表)
--   V2: project_coordinators, batch_coordinators, evidence, legal_doc,
--       recharge_log, settings, audit_log, permission
--       (8 张表)
--   总计 34 张表
-- =============================================================================
