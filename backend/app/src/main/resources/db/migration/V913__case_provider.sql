-- V913 案件级 provider_id（M3 派单 BLOCKER 修复：单案再派污染同批其他案件）
--
-- 背景：原 case 无 provider_id，provider 可见性全靠 case→batch→batch.provider_id 推导。
--   单案再派(redispatchCase)曾改写整批 batch.provider_id → 污染同批其他案件归属。
-- 方案：引入案件级 provider_id（可空），回填为当前 batch.provider_id（回填后与原推导等价，
--   现有 range/own-org 可见性行为不变）。仅再派案件由此分叉，不再污染同批其他案件。
--
-- 语义维护点（应用层）：派单/承接 → set case.provider_id=承接 org；
--   退回/释放/回平台公海/自动退回 → set case.provider_id=NULL。
-- 案件可见性查询统一改为直接 c.provider_id = ?（案件级唯一权威，不再回落 batch.provider_id；
--   退回/拒接/释放到平台公海或开放池后 c.provider_id=NULL→旧服务商不可见，杜绝越权）。
--   批次/付佣粒度（批次列表、OUT 付佣、内催佣金、对账、手册）仍保持 b.provider_id 不变。

ALTER TABLE "case" ADD COLUMN provider_id BIGINT;

COMMENT ON COLUMN "case".provider_id IS '案件级承接服务商 org（NULL=无承接归属·在平台公海/开放池，可见性按 c.provider_id 直判、不回落 batch.provider_id）；再派/退回按案件粒度维护，不再改写 batch.provider_id 污染同批';

-- 可空外键（org 可选）：删 org 时置空，不阻塞。
ALTER TABLE "case"
    ADD CONSTRAINT fk_case_provider FOREIGN KEY (provider_id)
    REFERENCES org(id) ON DELETE SET NULL;

-- 回填：仅给"已归属某服务商"的案件（已派待接 S1/服务商公海 S2/私海 S3）继承批次承接去向；
-- 平台公海(PLATFORM_SEA/S0)与开放抢单池(OPEN_POOL/S4)无归属，保持 NULL——否则旧服务商会通过
-- 案件级 c.provider_id scope 越权看到本应无归属的公海/开放池案件。
UPDATE "case" c SET provider_id = b.provider_id
FROM batch b
WHERE c.batch_id = b.id
  AND b.provider_id IS NOT NULL
  AND c.pool NOT IN ('PLATFORM_SEA', 'OPEN_POOL');

CREATE INDEX idx_case_provider_id ON "case" (provider_id);
