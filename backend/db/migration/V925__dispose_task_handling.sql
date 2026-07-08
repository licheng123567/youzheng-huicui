-- 质检处置闭环（BR-M5-07 扩展）：dispose_task 补平台处理决定 + 当事人 + 整改回执 + IN_PROGRESS 态。
-- 平台复核 CONFIRMED 时给分档处理决定（约谈/警告/限期整改/限制/停用）；归属方(VL/PL)提交整改回执闭环。
ALTER TABLE dispose_task ADD COLUMN IF NOT EXISTS decision          TEXT;          -- INTERVIEW/WARNING/RECTIFY/RESTRICT/DEACTIVATE
ALTER TABLE dispose_task ADD COLUMN IF NOT EXISTS target_account_id BIGINT;        -- 当事人（违规人=risk.collector_id）
ALTER TABLE dispose_task ADD COLUMN IF NOT EXISTS decision_note     TEXT;          -- 平台沟通/处理说明
ALTER TABLE dispose_task ADD COLUMN IF NOT EXISTS receipt_note      TEXT;          -- 归属方整改回执
ALTER TABLE dispose_task ADD COLUMN IF NOT EXISTS receipted_at      TIMESTAMPTZ;   -- 回执时间

COMMENT ON COLUMN dispose_task.decision IS '平台处理决定：INTERVIEW约谈/WARNING警告/RECTIFY限期整改/RESTRICT限制/DEACTIVATE停用';

-- 状态放宽增 IN_PROGRESS（整改中）。先删旧 CHECK 再建新（Postgres 无 ALTER CONSTRAINT 改定义）。
ALTER TABLE dispose_task DROP CONSTRAINT IF EXISTS chk_dispose_status;
ALTER TABLE dispose_task ADD  CONSTRAINT chk_dispose_status CHECK (status IN ('PENDING','IN_PROGRESS','DONE'));
