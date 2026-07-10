-- =============================================================================
-- V927: sms_record.org_id 放宽为可空 —— 让「验证码短信」第一次进入流水与计费口径
--
-- 背景：SmsService.recordSms 里有一句 `if (orgId == null) return;`，因为 V5 建表时
--   org_id NOT NULL。验证码短信发生在登录之前，压根没有 org —— 于是**验证码短信的发送量
--   与费用在 /sms-records 里完全不可见**，也就无从发现短信轰炸。
--
-- 语义（已核实 common/DataScope.ownOrg）：
--   平台主体   → Fragment.NONE，不加过滤 ⇒ 看得见 org_id IS NULL 的行；
--   非平台主体 → `AND org_id = ?`        ⇒ NULL 永不匹配，看不见。
--   即：验证码短信仅平台可见。正是我们要的口径，无需额外权限代码。
--
-- 契约无影响：SmsSendRecord 不含 orgId 字段（SmsRecordsController.SMS_MAPPER 亦不映射它）。
-- 本迁移只放宽约束，不改任何既有行；FK 仍在（PG 的 FK 允许 NULL）。
-- =============================================================================

ALTER TABLE sms_record ALTER COLUMN org_id DROP NOT NULL;

COMMENT ON COLUMN sms_record.org_id IS
    '归属组织；NULL = 无组织上下文的平台级短信（如登录验证码），仅平台可见（DataScope.ownOrg）';

-- org_id IS NULL 的行会走全表扫（idx_sms_org 是普通 btree，NULL 也入索引，实际可用），
-- 但平台查验证码量最常见的过滤是 template + 时间窗，补一个偏索引更直接。
CREATE INDEX IF NOT EXISTS idx_sms_platform_verify
    ON sms_record (sent_at DESC)
    WHERE org_id IS NULL;
