-- V914 结算资金正确性快照（issue #3 B-03 回款归属快照 / B-05 内催打款单明细快照）
--
-- 背景（B-03·阻断）：PR #1 单案再派(redispatchCase)只改 case.provider_id、不动 batch.provider_id。
--   若 OUT 付佣按 batch.provider_id、内催分佣按当前 case.holder_id 结算，则再派/换持有人后
--   到账时点的归属被"当前归属"覆盖 → 付佣结给错误服务商、佣金算给错误催收员。
--   修复：回款登记(insert repay_line)时固化到账时点归属快照（provider/collector），结算一律按快照。
--
-- 背景（B-05·阻断）：内催打款单明细原靠 co_pay_doc.line_ids 实时重查 co_commission.rate +
--   case.owner/room，比率/归属事后变更会令历史单据失真。修复：co_pay_doc_line 落明细快照，
--   详情只读快照列。
--
-- 接 V913 之后。金额口径 *_cents；rate 为 NUMERIC(6,4) 分数(0-1)；basenum=repay_line.amount_cents。

-- =============================================================================
-- B-03：repay_line 增到账时点归属快照列
-- =============================================================================
ALTER TABLE repay_line ADD COLUMN provider_id_at_repay  BIGINT;
ALTER TABLE repay_line ADD COLUMN collector_id_at_repay BIGINT;

COMMENT ON COLUMN repay_line.provider_id_at_repay  IS '到账时点承接服务商 org 快照=COALESCE(case.provider_id,batch.provider_id)（OUT 付佣 scope/聚合按此，不随再派漂移）；冲正不动';
COMMENT ON COLUMN repay_line.collector_id_at_repay IS '到账时点持有催收员 account 快照=case.holder_id（内催分佣按此，不随换持有人漂移）；冲正不动';

-- 可空外键：删 org/account 时置空，不阻塞历史回款明细。
ALTER TABLE repay_line
    ADD CONSTRAINT fk_repay_line_provider_at_repay
    FOREIGN KEY (provider_id_at_repay)  REFERENCES org(id)     ON DELETE SET NULL;
ALTER TABLE repay_line
    ADD CONSTRAINT fk_repay_line_collector_at_repay
    FOREIGN KEY (collector_id_at_repay) REFERENCES account(id) ON DELETE SET NULL;

-- 回填现有行：从案件/批次当前归属取快照（历史回款无时点记录，以现状为最优近似）。
--   provider_id_at_repay = COALESCE(case.provider_id, batch.provider_id)；
--   collector_id_at_repay = case.holder_id。
UPDATE repay_line rl
SET provider_id_at_repay  = COALESCE(c.provider_id, b.provider_id),
    collector_id_at_repay = c.holder_id
FROM "case" c
JOIN batch b ON b.id = c.batch_id
WHERE rl.case_id = c.id;

CREATE INDEX idx_repay_line_provider_at_repay  ON repay_line (provider_id_at_repay);
CREATE INDEX idx_repay_line_collector_at_repay ON repay_line (collector_id_at_repay);

-- =============================================================================
-- B-05：co_pay_doc_line 增明细快照列
-- =============================================================================
ALTER TABLE co_pay_doc_line ADD COLUMN case_id     BIGINT;
ALTER TABLE co_pay_doc_line ADD COLUMN room        TEXT;
ALTER TABLE co_pay_doc_line ADD COLUMN owner_name  TEXT;
ALTER TABLE co_pay_doc_line ADD COLUMN repay_cents BIGINT;
ALTER TABLE co_pay_doc_line ADD COLUMN rate        NUMERIC(6,4);
ALTER TABLE co_pay_doc_line ADD COLUMN comm_cents  BIGINT;

COMMENT ON COLUMN co_pay_doc_line.case_id     IS '组单时点案件 id 快照';
COMMENT ON COLUMN co_pay_doc_line.room        IS '组单时点房号快照';
COMMENT ON COLUMN co_pay_doc_line.owner_name  IS '组单时点业主名快照';
COMMENT ON COLUMN co_pay_doc_line.repay_cents IS '组单时点该笔回款基数快照（=repay_line.amount_cents）';
COMMENT ON COLUMN co_pay_doc_line.rate        IS '组单时点催收员佣金比率快照（co_commission.rate·分数 0-1）';
COMMENT ON COLUMN co_pay_doc_line.comm_cents  IS '组单时点佣金快照=round(repay_cents × rate)·HALF_UP';

-- 回填现有行：从 repay_line + case + co_commission JOIN 取值。
--   rate 取该明细所属批次×该单催收员的当前比率；comm_cents 逐笔 round(amount × rate)。
-- 注：PostgreSQL UPDATE...FROM 中目标表别名(cpl)不可出现在 FROM 的 JOIN 条件里。
--   故用子查询 src 先把 (co_pay_doc_id, repay_line_id)→各快照值 完整 JOIN 物化，
--   UPDATE 仅以两键在 WHERE 与目标关联。src 包含 co_pay_doc_line 自身以串起 doc↔line。
UPDATE co_pay_doc_line cpl
SET case_id     = src.case_id,
    room        = src.room,
    owner_name  = src.owner_name,
    repay_cents = src.repay_cents,
    rate        = src.rate,
    comm_cents  = src.comm_cents
FROM (
    SELECT cpl0.co_pay_doc_id,
           cpl0.repay_line_id,
           rl.case_id,
           c.room,
           c.owner_name,
           rl.amount_cents AS repay_cents,
           cc.rate,
           CASE WHEN cc.rate IS NULL THEN NULL
                ELSE round(rl.amount_cents * cc.rate)::bigint END AS comm_cents
    FROM co_pay_doc_line cpl0
    JOIN co_pay_doc d  ON d.id = cpl0.co_pay_doc_id
    JOIN repay_line rl ON rl.id = cpl0.repay_line_id
    JOIN "case" c      ON c.id = rl.case_id
    LEFT JOIN co_commission cc ON cc.collector_id = d.collector_id AND cc.batch_id = rl.batch_id
) src
WHERE src.co_pay_doc_id = cpl.co_pay_doc_id
  AND src.repay_line_id = cpl.repay_line_id;
