-- V920: 案件应收拆分滞纳金 — H5 账单页按原型展示「物业费 + 滞纳金」明细（导入模型同步加可选列）

ALTER TABLE "case" ADD COLUMN IF NOT EXISTS penalty_cents BIGINT;
COMMENT ON COLUMN "case".penalty_cents IS '应收合计(due_cents)中的滞纳金拆分(分)；本金=due_cents-penalty_cents；NULL=导入未拆分(历史数据)';
