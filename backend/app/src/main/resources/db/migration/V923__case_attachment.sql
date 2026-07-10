-- 跟进记录附件：直接选文件上传 + 扫码上传（手机扫码→公开上传页→桌面轮询自动附上）。
-- 附件字节存 Postgres bytea（dev/demo 范式，同录音 audio_bytes V921）；生产可换对象存储。
CREATE TABLE case_attachment (
    id            BIGSERIAL PRIMARY KEY,
    case_id       BIGINT      NOT NULL REFERENCES "case"(id) ON DELETE RESTRICT,
    session_token TEXT,                 -- 扫码上传会话 token（桌面直传为 NULL）
    filename      TEXT        NOT NULL,
    content_type  TEXT,
    bytes         BYTEA       NOT NULL,
    uploaded_by   BIGINT,               -- 手机扫码上传(无鉴权)为 NULL；桌面直传为操作人
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_case_attachment_session ON case_attachment(session_token);

-- 扫码上传会话：token → 案件 + 过期时间；手机公开上传端点凭 token 定位案件。
CREATE TABLE upload_session (
    token      TEXT        PRIMARY KEY,
    case_id    BIGINT      NOT NULL REFERENCES "case"(id) ON DELETE RESTRICT,
    expires_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE case_attachment IS '跟进记录附件（图片/文件）；桌面直传或手机扫码上传。';
COMMENT ON TABLE upload_session  IS '扫码上传会话；桌面建 token 生成二维码，手机凭 token 公开上传。';
