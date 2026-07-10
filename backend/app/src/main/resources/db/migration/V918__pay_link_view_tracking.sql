-- V918: 缴费链接已读追踪 — 支撑「我的缴费链接」列表按原型 1:1 展示状态(待查看/已读未缴/已缴费/已过期)

ALTER TABLE pay_link ADD COLUMN IF NOT EXISTS viewed_at TIMESTAMPTZ;
COMMENT ON COLUMN pay_link.viewed_at IS '业主首次打开H5账单页时间(GET /pay/{token})，驱动"已读未缴"展示口径 BR-M4-14/15';

CREATE INDEX IF NOT EXISTS idx_pay_link_created_by ON pay_link (created_by);
