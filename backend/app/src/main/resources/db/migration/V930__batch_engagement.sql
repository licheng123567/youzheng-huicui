-- V930 批次承接段（engagement）：批次整派服务商的「承接生命周期」实体。
-- 背景：批次派单后若服务商催收不力，平台可「结项」（终止承接·全部收回案件回平台公海）再重派。
--   此前 batch 只有 provider_id 单值，重派即覆盖——每任服务商催了多久/催回多少无从考核。
-- 每段 = (批次, 服务商, started_at, ended_at)。开段=dispatch/redispatch 成功时；收段=结项时。
-- 钱的归属不依赖本表：V914 快照 provider_id_at_repay 已在到账时点固化（结项/重派不漂移），
--   本表只承载「承接历史+每段考核统计」维度。

CREATE TABLE batch_engagement (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id     BIGINT NOT NULL,
    provider_id  BIGINT NOT NULL,
    seq          INT    NOT NULL,                    -- 批内承接序号 1..n
    pay_out_rate NUMERIC(6,4) NOT NULL,              -- 开段时点付佣快照（batch.pay_out_rate 会被后续重派覆盖）
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at     TIMESTAMPTZ,                        -- NULL=进行中
    end_reason   TEXT,                               -- 结项原因枚举
    end_note     TEXT,                               -- 结项备注（结项时必填，应用层校验）
    ended_by     BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_be_batch    FOREIGN KEY (batch_id)    REFERENCES batch(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_be_provider FOREIGN KEY (provider_id) REFERENCES org(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_be_ended_by FOREIGN KEY (ended_by)    REFERENCES account(id) ON DELETE SET NULL,
    CONSTRAINT chk_be_reason CHECK (end_reason IS NULL OR end_reason IN
        ('INCAPABLE','COOP_TERMINATED','PROPERTY_REQUEST','OTHER')),
    -- 不变式：收段必有原因（进行中段无原因）
    CONSTRAINT chk_be_closed CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    CONSTRAINT uq_be_seq UNIQUE (batch_id, seq)
);
-- 每批至多一个进行中段
CREATE UNIQUE INDEX uq_be_open ON batch_engagement (batch_id) WHERE ended_at IS NULL;
CREATE INDEX idx_be_provider ON batch_engagement (provider_id);

COMMENT ON TABLE  batch_engagement IS '批次承接段：批次×服务商的承接生命周期（开段=派单/重派，收段=结项）。每段考核统计按 V914 到账快照+时间窗聚合';
COMMENT ON COLUMN batch_engagement.end_reason IS 'INCAPABLE=无力催收/COOP_TERMINATED=合作终止/PROPERTY_REQUEST=物业要求/OTHER=其他';

-- 存量回填：当前已派批次（provider_id 非空）→ 开一个进行中段 seq=1。
-- started_at best-effort 从 audit_log 派单动作最早时间推导（audit_log 列 tm/target_id TEXT），
-- 兜底 batch.updated_at；回填段起点精度有限，UI 对 seq=1 回填段展示可标 "~"。
INSERT INTO batch_engagement (batch_id, provider_id, seq, pay_out_rate, started_at)
SELECT b.id, b.provider_id, 1, COALESCE(b.pay_out_rate, b.open_rate, 0),
       COALESCE(
           (SELECT min(a.tm) FROM audit_log a
             WHERE a.action IN ('case.dispatch', 'case.redispatch')
               AND a.target_type = 'case'
               AND a.target_id IN (SELECT c.id::text FROM "case" c WHERE c.batch_id = b.id)),
           b.updated_at, now())
FROM batch b
WHERE b.provider_id IS NOT NULL;

-- 批次列表聚合（poolDist/应收）与结项选案都按 (batch_id, status) 扫案件
CREATE INDEX IF NOT EXISTS idx_case_batch_status ON "case" (batch_id, status);
