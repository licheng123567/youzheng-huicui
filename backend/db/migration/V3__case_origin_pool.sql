-- =============================================================================
-- V3 · M3 派单/抢单状态机支撑：case.origin_pool
--    释放回流判据 BR-M3-09：私海释放须回到「来源池」（服务商公海 vs 开放抢单池），
--    靠 source 反推易回错池，故新增 origin_pool 痕迹列。
--    取值同 PoolEnum，但语义=该案进入私海前所处的公海池：
--      PROVIDER_SEA → 释放回 S2 服务商公海
--      OPEN_POOL    → 释放回 S4 开放抢单池
--    NULL = 尚未进过私海/不适用。
-- =============================================================================
ALTER TABLE "case"
    ADD COLUMN IF NOT EXISTS origin_pool TEXT;

ALTER TABLE "case"
    ADD CONSTRAINT chk_case_origin_pool
        CHECK (origin_pool IS NULL OR origin_pool IN (
            'PLATFORM_SEA','PROVIDER_SEA','OPEN_POOL','PRIVATE'
        ));

COMMENT ON COLUMN "case".origin_pool IS '进入私海前的来源池（释放回流判据 BR-M3-09）：PROVIDER_SEA→回服务商公海，OPEN_POOL→回开放抢单池';
