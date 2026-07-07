-- V919: 缴费链接支持「同一 token 指定渠道重发」——短信发送作为预览后的独立动作，不必创建时二选一

ALTER TABLE pay_link ADD COLUMN IF NOT EXISTS last_channel TEXT;
ALTER TABLE pay_link ADD COLUMN IF NOT EXISTS last_sent_at TIMESTAMPTZ;
COMMENT ON COLUMN pay_link.last_channel IS '最近一次实际发送渠道(重发可指定覆盖创建渠道)；NULL=沿用 channel';
COMMENT ON COLUMN pay_link.last_sent_at IS '最近一次发送时间；NULL=沿用 created_at(用于短信冷却判定 BR-M4-14a)';
