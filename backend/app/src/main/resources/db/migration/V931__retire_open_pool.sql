-- V931 开放抢单池停用（v1.18.0·2026-07-11 用户拍板，系统未上线无存量兼容负担）。
-- 停用理由：开放池让催收员个人跨商抢单、却由其服务商承担佣金结算与质检责任——个体行为产生
--   组织义务，与全系统「组织对组织」商业模型断裂；「服务商执行不佳」已由 批次结项→重派
--   （V930 batch_engagement）闭环覆盖。催收员抢单仅保留本服务商公海 S2。
-- 本迁移：存量 S4 案件迁回平台公海 S0 等待派单；清 origin_pool=OPEN_POOL 残值（释放回流判据）；
--   open_rate 列保留（历史结构，无写入路径），CHECK 约束里的 OPEN_POOL 枚举值保留（避免结构性破坏）。

UPDATE "case"
SET status = 'PENDING_DISPATCH', pool = 'PLATFORM_SEA', source = 'RETURN',
    origin_pool = NULL, holder_id = NULL, provider_id = NULL,
    t2_deadline = NULL, t_collector_deadline = NULL, updated_at = now()
WHERE pool = 'OPEN_POOL';

-- 私海在催案件若 origin_pool 仍指向开放池（历史从 S4 抢入），清残值→释放时按 S2 回流。
UPDATE "case" SET origin_pool = NULL, updated_at = now()
WHERE origin_pool = 'OPEN_POOL';

COMMENT ON COLUMN batch.open_rate IS '已废弃(V931 开放抢单停用)：无写入路径，结算不再兜底此列';
