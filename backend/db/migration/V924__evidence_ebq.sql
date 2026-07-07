-- 易保全（ebaoquan）真实存证对接：逐文件哈希保全的进度态 + evidence 记录归属的第三方提供方。
-- 一条 evidence（scene=RECORDING/DELIVERY）可含多份文件；哈希保全按文件逐件送易保全，逐件回填备案态。
ALTER TABLE evidence ADD COLUMN IF NOT EXISTS provider TEXT;   -- 'EBAOQUAN'（占位存证为 NULL）
COMMENT ON COLUMN evidence.provider IS '存证服务提供方：EBAOQUAN=易保全；NULL=占位/未对接';

CREATE TABLE evidence_ebq_item (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    evidence_id          BIGINT      NOT NULL,
    ref_type             TEXT        NOT NULL,          -- RECORDING=录音 / ATTACHMENT=送达凭证附件
    ref_id               BIGINT      NOT NULL,          -- 录音 id 或 case_attachment id
    file_hash            TEXT        NOT NULL,          -- 文件 SHA-512 hex（送易保全的哈希）
    provider_evidence_id BIGINT,                        -- 易保全平台证据 id（createEvidenceHash 返回）
    preservation_id      BIGINT,                        -- 保全备案号（保全成功后回填，就绪前 NULL）
    chain_tx_hash        TEXT,                          -- 易保全保全链交易 hash（ebqChainTransHash）
    gznet_id             TEXT,                          -- 广州互联网法院证据 id
    ant_id               TEXT,                          -- 杭州互联网法院证据 id
    status               TEXT        NOT NULL DEFAULT 'SUBMITTED',  -- SUBMITTED/PRESERVED/FAILED
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ebq_item_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(id) ON DELETE CASCADE,
    CONSTRAINT chk_ebq_item_status  CHECK (status IN ('SUBMITTED','PRESERVED','FAILED'))
);
CREATE INDEX idx_ebq_item_evidence ON evidence_ebq_item (evidence_id);
-- 轮询回填用：仍在保全中（有平台证据 id、尚无备案号）的项。
CREATE INDEX idx_ebq_item_pending  ON evidence_ebq_item (preservation_id) WHERE preservation_id IS NULL;

COMMENT ON TABLE  evidence_ebq_item IS '易保全逐文件哈希保全进度态；轮询 queryEvidenceDetail 回填 preservation_id 与链上信息。';
