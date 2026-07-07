-- 收佣比例「物业提案 → 平台确认」态：物业负责人在批次提「建议收佣比例」(confirmed=false)，
-- 平台在撮合处确认/调整双佣后置 true（最终决定权在平台）。
ALTER TABLE batch ADD COLUMN IF NOT EXISTS comm_in_confirmed BOOLEAN NOT NULL DEFAULT false;
-- 存量：已派单批次(pay_out_rate 非空)=平台已撮合确认，避免历史数据全挂「待确认」。
UPDATE batch SET comm_in_confirmed = true WHERE pay_out_rate IS NOT NULL;
COMMENT ON COLUMN batch.comm_in_confirmed IS '收佣比例是否经平台最终确认（物业提案 false→平台确认 true）';
