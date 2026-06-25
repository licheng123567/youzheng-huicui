-- =============================================================================
-- V5: sms_record — 短信发送流水（契约 SmsSendRecord 落库）
--   M9-B 短信通道：发送/送达/失败流水，range scope 按 org_id 裁剪。
--   契约 caseId/projectId nullable（群发/批量发可不挂具体案件）。
--   BR-M9-08：失败不退条数（FAILED 仍占用已扣额度，failure_reason 留痕）。
-- V2 无此表——base 不归管，本 migration 新建（非 DevSeeder shadow 范畴）。
-- =============================================================================
CREATE TABLE sms_record (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id         BIGINT      NOT NULL,                 -- range scope 裁剪锚点
    case_id        BIGINT,                               -- 可空（契约 caseId nullable）
    project_id     BIGINT,                               -- 可空（契约 projectId nullable）
    template       TEXT,                                 -- 模板名/id
    status         TEXT        NOT NULL,                 -- SENT/FAILED/DELIVERED
    failure_reason TEXT,                                 -- FAILED 时填
    sent_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_sms_org     FOREIGN KEY (org_id)     REFERENCES org(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_sms_case    FOREIGN KEY (case_id)    REFERENCES "case"(id)  ON DELETE SET NULL,
    CONSTRAINT fk_sms_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE SET NULL,
    CONSTRAINT chk_sms_status CHECK (status IN ('SENT','FAILED','DELIVERED'))
);

COMMENT ON TABLE  sms_record                IS '短信发送流水（M9-B·契约 SmsSendRecord）。BR-M9-08：失败不退条数';
COMMENT ON COLUMN sms_record.failure_reason IS 'FAILED 时填写失败原因（如号码空号）';

CREATE INDEX idx_sms_org     ON sms_record (org_id);
CREATE INDEX idx_sms_project ON sms_record (project_id);
CREATE INDEX idx_sms_case    ON sms_record (case_id);
CREATE INDEX idx_sms_sent_at ON sms_record (sent_at);
