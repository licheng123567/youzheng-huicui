-- V929 双线占用拆分：repay_line 的占用/结清标记按资金双线（IN 收佣/OUT 付佣）各自独立。
-- 背景缺陷：payment_request_id + settled 单列不分线 → 同一笔回款进了收佣(IN)单就无法再进付佣(OUT)单；
--          且任一线 PAID 置 settled 后，另一线的 rollup 也误显示"已结"。
-- 业务口径：每笔回款既是向物业收佣(IN)的基数，又是向服务商付佣(OUT)的基数，双线独立组单、独立结清。

ALTER TABLE repay_line ADD COLUMN pr_id_in    BIGINT;
ALTER TABLE repay_line ADD COLUMN settled_in  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE repay_line ADD COLUMN pr_id_out   BIGINT;
ALTER TABLE repay_line ADD COLUMN settled_out BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE repay_line
    ADD CONSTRAINT fk_repay_line_pr_in
        FOREIGN KEY (pr_id_in)  REFERENCES payment_request(id) ON DELETE SET NULL;
ALTER TABLE repay_line
    ADD CONSTRAINT fk_repay_line_pr_out
        FOREIGN KEY (pr_id_out) REFERENCES payment_request(id) ON DELETE SET NULL;

-- 不变式：已结必有单（settled_× 只能由本线支付申请单 complete 置位）
ALTER TABLE repay_line ADD CONSTRAINT chk_settled_in_has_pr  CHECK (NOT settled_in  OR pr_id_in  IS NOT NULL);
ALTER TABLE repay_line ADD CONSTRAINT chk_settled_out_has_pr CHECK (NOT settled_out OR pr_id_out IS NOT NULL);

CREATE INDEX idx_repay_line_pr_in  ON repay_line (pr_id_in)  WHERE pr_id_in  IS NOT NULL;
CREATE INDEX idx_repay_line_pr_out ON repay_line (pr_id_out) WHERE pr_id_out IS NOT NULL;

-- 存量回填：按活跃占用单的 side 归线。VOIDED 单在 revoke 时已把 payment_request_id 置 NULL，
-- 故 join 只命中 PENDING/PAID 的活跃占用。settled=TRUE 而 payment_request_id IS NULL 的孤儿行
-- 理论不存在（complete/revoke 均同步维护两列），若有属历史脏数据，保持双线 FALSE 不猜线别。
UPDATE repay_line rl
SET pr_id_in = rl.payment_request_id, settled_in = rl.settled
FROM payment_request pr
WHERE pr.id = rl.payment_request_id AND pr.side = 'IN';

UPDATE repay_line rl
SET pr_id_out = rl.payment_request_id, settled_out = rl.settled
FROM payment_request pr
WHERE pr.id = rl.payment_request_id AND pr.side = 'OUT';

COMMENT ON COLUMN repay_line.pr_id_in    IS '纳入哪张收佣(IN)支付申请单（未组单为 NULL）';
COMMENT ON COLUMN repay_line.settled_in  IS '收佣线已结（IN 单 PAID 后=true）';
COMMENT ON COLUMN repay_line.pr_id_out   IS '纳入哪张付佣(OUT)支付申请单（未组单为 NULL）';
COMMENT ON COLUMN repay_line.settled_out IS '付佣线已结（OUT 单 PAID 后=true）';
COMMENT ON COLUMN repay_line.payment_request_id IS '已废弃(V929 双线拆分)：读写一律走 pr_id_in/pr_id_out';
COMMENT ON COLUMN repay_line.settled            IS '已废弃(V929 双线拆分)：读写一律走 settled_in/settled_out';
