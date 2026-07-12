-- V932 权威余额表（v1.19.0「额度管理」）。
-- 背景：此前无余额表——余额靠 recharge_log「最新一行 balance 快照」推导，且两处读法排序不一致
--   （BillingM9Controller.latestBalance 用 tm DESC,id DESC；BillingController.sttBalance 用 id DESC）。
--   充值/扣减靠 pg_advisory_xact_lock((int)orgId, type.hashCode()) 串行化（int 截断+hash 碰撞隐患）。
-- 本迁移：org_balance 成为唯一权威源；recharge_log 降为流水（balance 列仍写"操作后快照"供对账可读）。

CREATE TABLE org_balance (
    org_id     BIGINT        NOT NULL,
    type       TEXT          NOT NULL,
    balance    NUMERIC(14,3) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, type),
    CONSTRAINT fk_org_balance_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT,
    CONSTRAINT chk_org_balance_type CHECK (type IN ('STT','SMS','EVIDENCE','LEGAL'))
);
-- 【故意不加 CHECK(balance >= 0)】：EVIDENCE/LEGAL 是后付费（RechargeTypeEnum 只有 STT/SMS，
--   BR-M9-10「按次计入对账·不预充」），负余额=欠用记账（DevSeeder 种子即 EVIDENCE -2.000 / LEGAL -1.000）。
--   只有 STT/SMS 是预付项，余额不足由应用层 BalanceService 拒绝（BIZ_QUOTA_EXHAUSTED 409）。
COMMENT ON TABLE  org_balance         IS '组织能力额度余额（权威源）。STT/SMS 预付(不足拒绝)；EVIDENCE/LEGAL 后付(允许负余额=欠用记账)';
COMMENT ON COLUMN org_balance.balance IS '当前余额；负值合法(后付费类型欠用)';

-- 存量回填：每 org×type 取流水最新一行 balance（口径对齐 latestBalance 的 tm DESC,id DESC）。
INSERT INTO org_balance (org_id, type, balance, updated_at)
SELECT DISTINCT ON (org_id, type) org_id, type, balance, tm
FROM recharge_log
ORDER BY org_id, type, tm DESC, id DESC;

-- 聚合索引：按组织×时间分月/分日 group by（现只有 (org_id)/(org_id,type)/(occurred_at) 单列）。
CREATE INDEX IF NOT EXISTS ix_billing_usage_org_occurred ON billing_usage (org_id, occurred_at);
-- 流水按组织过滤/排序（现只有 (org_id)、(tm) 两个单列索引）。
CREATE INDEX IF NOT EXISTS ix_recharge_log_org_type_tm ON recharge_log (org_id, type, tm DESC);

-- unit 归一：写入口径此前不一致（seed 写 'min'/'count'，BillingController 写 '分钟'）。
-- unit 是 type 的纯函数 → 按 type 重算，取值对齐契约 BillingUsage.unit 描述「分钟/条/次/件」
-- 与既有 ReportsM10Controller.unitOf()。此后由 common/BillingUnits.of(type) 统一保证不再漂移。
UPDATE billing_usage SET unit = CASE type
    WHEN 'STT'   THEN '分钟'
    WHEN 'SMS'   THEN '条'
    WHEN 'LEGAL' THEN '件'
    ELSE '次'
END;
