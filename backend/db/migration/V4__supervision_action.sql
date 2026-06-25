-- =============================================================================
-- V4 · M10 组织监管：supervision_action（负责人对本组织成员的督导动作留痕）
--    BR-M10：组织负责人（PL/VL）对本组织成员（PC/CO）的提醒/约谈/培训/备注，
--    own-org 裁剪只看本组织（org_id = 被督导成员所属组织）。平台监管视图见全量。
--    物理隔离：仅本模块新表，不触碰 V1/V2/V3 既有 schema。
-- =============================================================================
CREATE TABLE supervision_action (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id      BIGINT      NOT NULL,   -- 被督导成员所属组织（own-org 裁剪键）
    member_id   BIGINT      NOT NULL,   -- 被督导成员 account.id
    action      TEXT        NOT NULL,   -- REMIND/TALK/TRAINING/NOTE
    note        TEXT,
    operator_id BIGINT      NOT NULL,   -- 操作人=组织负责人 account.id
    trace_id    TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_supervision_action
        CHECK (action IN ('REMIND','TALK','TRAINING','NOTE')),
    CONSTRAINT fk_supervision_org
        FOREIGN KEY (org_id)      REFERENCES org(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_supervision_member
        FOREIGN KEY (member_id)   REFERENCES account(id) ON DELETE RESTRICT,
    CONSTRAINT fk_supervision_operator
        FOREIGN KEY (operator_id) REFERENCES account(id) ON DELETE SET NULL
);

COMMENT ON TABLE  supervision_action            IS 'M10 组织监管动作（负责人对本组织成员督导留痕）。BR-M10';
COMMENT ON COLUMN supervision_action.org_id     IS '被督导成员所属组织（own-org 裁剪键）';
COMMENT ON COLUMN supervision_action.member_id  IS '被督导成员 account.id';
COMMENT ON COLUMN supervision_action.action     IS 'REMIND=提醒 TALK=约谈 TRAINING=培训 NOTE=备注';
COMMENT ON COLUMN supervision_action.operator_id IS '操作人=组织负责人 account.id';

CREATE INDEX idx_supervision_org_member  ON supervision_action (org_id, member_id);
CREATE INDEX idx_supervision_org_created ON supervision_action (org_id, created_at);
