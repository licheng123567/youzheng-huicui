-- V7：消息中心（BR-M4-23 催收员↔协调员互推闭环）。
-- 通知归属 recipient_account_id；互推动作（工单转出/回执）写入 + 案件时间线(activity)三处同步。
CREATE TABLE notification (
    id                   BIGSERIAL PRIMARY KEY,
    recipient_account_id BIGINT      NOT NULL,
    type                 TEXT        NOT NULL,   -- TICKET_NEW(待处理工单) / TICKET_RECEIPT(工单回执) 等
    title                TEXT        NOT NULL,
    body                 TEXT,
    ref_type             TEXT,                   -- 关联对象类型(ticket/case)，前端跳转
    ref_id               BIGINT,
    read                 BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_account_id) REFERENCES account(id) ON DELETE CASCADE
);
CREATE INDEX idx_notification_recipient ON notification (recipient_account_id, read, created_at DESC);

COMMENT ON TABLE notification IS '消息中心通知(BR-M4-23 互推闭环)。归属 recipient_account_id';
