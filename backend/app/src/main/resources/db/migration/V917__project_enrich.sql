-- V8: 项目资料富化 — 案件详情"项目资料"Tab对齐高保真设计
-- 新增：物业合同、服务期限、对公账户、微信收款码、减免政策描述

ALTER TABLE project ADD COLUMN IF NOT EXISTS contract_name  TEXT;   -- 物业合同名称（如"阳光物业服务合同（HT-2025-08）"）
ALTER TABLE project ADD COLUMN IF NOT EXISTS service_period TEXT;   -- 服务期限（如"2025-01-01 至 2027-12-31"）
ALTER TABLE project ADD COLUMN IF NOT EXISTS corp_account   TEXT;   -- 对公账户（如"工商银行 ××××× 阳光物业"）
ALTER TABLE project ADD COLUMN IF NOT EXISTS wx_qr_url      TEXT;   -- 微信收款码（URL或路径，空则显示"查看收款码"文案）
ALTER TABLE project ADD COLUMN IF NOT EXISTS reduce_policy  TEXT;   -- 减免政策描述（如"满3期减免滞纳金；最多分3期缴纳"）

COMMENT ON COLUMN project.contract_name  IS '物业合同名称（高保真§项目档案）';
COMMENT ON COLUMN project.service_period IS '服务期限（高保真§项目档案）';
COMMENT ON COLUMN project.corp_account   IS '对公账户（高保真§收款信息）';
COMMENT ON COLUMN project.wx_qr_url      IS '微信收款码URL（高保真§收款信息）';
COMMENT ON COLUMN project.reduce_policy  IS '减免政策描述（高保真§减免规则）';
