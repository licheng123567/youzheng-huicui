-- V916 B-04 方案A：一次性凭据交付令牌（credential_setup_token）
-- 解决新 owner/成员凭据不可交付、核心开通闭环断裂(US-M1-01/02)
-- 安全：仅存 SHA-256 哈希，TTL 24h，一次性（used_at 非空=已消费）。
-- ============================================================================

-- 1. account 增首登强制改密标志（BR-B04：新建/重置后首次登录必须改密）
ALTER TABLE account ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN account.must_change_password IS
    '首登改密标志：createOrg/reset-password 后置 TRUE；/auth/setup-password 成功后置 FALSE。BR-B04';

-- 2. 一次性凭据交付令牌表（仅存 token_hash，明文只出现在响应体一次）
CREATE TABLE IF NOT EXISTS credential_setup_token (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL,
    token_hash  TEXT        NOT NULL,           -- SHA-256(token明文) hex，绝不存明文
    expires_at  TIMESTAMPTZ NOT NULL,           -- now() + interval '24 hours'
    used_at     TIMESTAMPTZ,                    -- NULL=未使用；非空=已消费（一次性）
    created_by  BIGINT      NOT NULL,           -- 操作人 account.id
    org_id      BIGINT      NOT NULL,           -- 归属组织（审计用）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cst_account   FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT fk_cst_org       FOREIGN KEY (org_id)     REFERENCES org(id)     ON DELETE CASCADE,
    CONSTRAINT fk_cst_creator   FOREIGN KEY (created_by) REFERENCES account(id) ON DELETE RESTRICT
);

COMMENT ON TABLE  credential_setup_token IS
    '一次性凭据交付令牌；仅存 SHA-256 哈希，TTL 24h，used_at 非空=已消费。BR-B04';
COMMENT ON COLUMN credential_setup_token.token_hash IS
    'SHA-256(token明文) hex，绝不存明文；明文仅在 201 响应体出现一次，带外转交。';

CREATE INDEX IF NOT EXISTS idx_cst_account_id  ON credential_setup_token (account_id);
CREATE INDEX IF NOT EXISTS idx_cst_token_hash  ON credential_setup_token (token_hash);
