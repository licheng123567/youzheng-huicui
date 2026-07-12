-- V933 法律文书计费停用（v1.20.0·用户决策：系统没有「法律文书余额」这个逻辑）。
-- 背景：v1.19.0 曾把 LEGAL 作为四类计费之一（后付费·按件，生成律师函/起诉状时记一笔）。
--   产品口径改为：**法律文书生成不计费**——不记用量、不扣额度、额度页不展示该类。
-- 处置：清存量 LEGAL 计费数据；类型枚举（billing_usage/recharge_log/org_balance 的 CHECK）保留
--   以免破坏兼容（删枚举=破坏性变更），但已无写入路径（BillingUnits.isBillable 排除 LEGAL）。
-- 注意：法律文书**业务功能本身不受影响**（legal_doc 表、生成/送达端点、legal.create 权限全保留），
--   本迁移只清计费侧数据。

DELETE FROM org_balance  WHERE type = 'LEGAL';
DELETE FROM billing_usage WHERE type = 'LEGAL';
DELETE FROM recharge_log  WHERE type = 'LEGAL';

COMMENT ON COLUMN billing_usage.type IS '计费类型：STT/SMS(预付) · EVIDENCE(后付·按次)。LEGAL 已停用(V933 法律文书不计费)，枚举保留仅为兼容';
