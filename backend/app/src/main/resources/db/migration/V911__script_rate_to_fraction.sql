-- V911：消除 script_lib Rate SSOT 漂移。
-- 背景：promise_rate/repay_rate/variant.uplift 历史按百分比(45.0000)存储，应用层 /100 转契约 Rate 分数；
--       与全局 Rate=分数0-1 口径(v1.0.3)不一致。本迁移把存储统一到分数 0-1，应用层去掉 /100 shim。
-- 安全：仅转换“看起来仍是百分比”的历史行(>1)，对已是分数(≤1，含 V910 新种子)的行为 no-op，避免重复除。
-- 注：wilson 本即 0-1 分数，不动。

UPDATE script_lib SET promise_rate = promise_rate / 100 WHERE promise_rate IS NOT NULL AND promise_rate > 1;
UPDATE script_lib SET repay_rate   = repay_rate   / 100 WHERE repay_rate   IS NOT NULL AND repay_rate   > 1;

-- variant.uplift（jsonb）历史百分比 → 分数（仅 >1 的行）
UPDATE script_lib
   SET variant = jsonb_set(variant, '{uplift}', to_jsonb(round((variant->>'uplift')::numeric / 100, 4)))
 WHERE variant ? 'uplift'
   AND (variant->>'uplift') ~ '^[0-9.]+$'
   AND (variant->>'uplift')::numeric > 1;

-- 落库列注释，固化 SSOT 口径
COMMENT ON COLUMN script_lib.promise_rate IS '承诺率(分数 0-1，契约 Rate；如 0.4500=45%)';
COMMENT ON COLUMN script_lib.repay_rate   IS '回款率(分数 0-1，契约 Rate)';
