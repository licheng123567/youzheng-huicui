-- ============================================================================
-- V6__settings_ai_value.sql —— settings 表结构变更（从 V910 种子迁移抽出·审计 M-2）
-- ai-config 存储位：扩 chk_settings_domain 允许 'AI' 域 + 加通用 value JSONB 列。
-- 结构变更归正式迁移(本文件)，V910 仅 INSERT 种子数据，消除"种子迁移夹带 DDL"的顺序脆弱性。
-- 幂等：CHECK 重建 + ADD COLUMN IF NOT EXISTS，可安全重复。
-- ============================================================================
ALTER TABLE settings DROP CONSTRAINT IF EXISTS chk_settings_domain;
ALTER TABLE settings ADD CONSTRAINT chk_settings_domain
  CHECK (domain IN ('TIMERS','ROTATION','MARK_CODES','CLOSE_REASONS','SMS','AI'));
ALTER TABLE settings ADD COLUMN IF NOT EXISTS value JSONB;
COMMENT ON COLUMN settings.value IS '通用 JSONB 配置体（AI 域 key=ai_config 存 {llm,asr,prompts,flywheel}）';
