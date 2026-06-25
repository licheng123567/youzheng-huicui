-- ============================================================================
-- V910__ai_moat_seed.sql 设计（话术飞轮护城河种子 + ai_config 存储位补齐）
-- 前置 DDL 调整（V910 顶部）：settings 表为刚性 domain CHECK + 固定 JSONB 列，
--   无通用 key/value，故 ai_config 需补存储位：
--   (1) 扩 CHECK 允许 'AI' 域：
--       ALTER TABLE settings DROP CONSTRAINT chk_settings_domain;
--       ALTER TABLE settings ADD CONSTRAINT chk_settings_domain
--         CHECK (domain IN ('TIMERS','ROTATION','MARK_CODES','CLOSE_REASONS','SMS','AI'));
--   (2) 加通用 value JSONB 列承载 AiConfig（现有 5 个域专列不适配 AI）：
--       ALTER TABLE settings ADD COLUMN IF NOT EXISTS value JSONB;
--       COMMENT ON COLUMN settings.value IS '通用 JSONB 配置体（AI 域 key=ai_config 存 {llm,asr,prompts,flywheel}）';
-- 说明：task 表述 key=ai_config 即以 domain='AI' 作逻辑 key，配置体落 value jsonb。
-- 幂等：全程 IF NOT EXISTS / WHERE NOT EXISTS 包裹，可重复执行。

-- (前置 DDL 调整：扩 CHECK 允许 'AI' 域 + 加通用 value JSONB 列) -----------------
ALTER TABLE settings DROP CONSTRAINT chk_settings_domain;
ALTER TABLE settings ADD CONSTRAINT chk_settings_domain
  CHECK (domain IN ('TIMERS','ROTATION','MARK_CODES','CLOSE_REASONS','SMS','AI'));
ALTER TABLE settings ADD COLUMN IF NOT EXISTS value JSONB;
COMMENT ON COLUMN settings.value IS '通用 JSONB 配置体（AI 域 key=ai_config 存 {llm,asr,prompts,flywheel}）';

DO $$
DECLARE
  pid    BIGINT;   -- 翠湖一期 project_id
  prop   BIGINT;   -- 物业 org_id
  plid   BIGINT;   -- 物业负责人 account_id（采纳人/updated_by）
  said   BIGINT;   -- 平台超管 account_id（settings.updated_by 兜底）
BEGIN
  -- 平台超管（V900 已建）取出，作 settings.updated_by 兜底
  SELECT a.id INTO said FROM account a JOIN org o ON o.id=a.org_id
    WHERE o.type='PLATFORM' AND a.role_template='SA' LIMIT 1;

  -- 物业组织 + 物业负责人（翠湖一期归属）；若无则建
  SELECT id INTO prop FROM org WHERE type='PROPERTY' AND name='翠湖物业' LIMIT 1;
  IF prop IS NULL THEN
    INSERT INTO org(type,name,status) VALUES('PROPERTY','翠湖物业','ACTIVE') RETURNING id INTO prop;
    INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)
      VALUES(prop,'cuihu_pl','翠湖负责人','13900000001','PL','ACTIVE',TRUE) RETURNING id INTO plid;
    UPDATE org SET owner_account_id=plid WHERE id=prop;
  ELSE
    SELECT id INTO plid FROM account WHERE org_id=prop AND role_template='PL' LIMIT 1;
  END IF;

  -- 项目 翠湖一期（comm_in_rate 必填 BR-M9-01a；Rate=分数 0-1 口径 v1.0.3，0.0800=8%，非百分比）。
  -- 注：V910(Flyway) 在 DevSeeder 前跑，此处先建则 DevSeeder 跳过——故必须用分数，否则 GET /projects 违反 Rate(max 1.0) 契约。
  SELECT id INTO pid FROM project WHERE org_id=prop AND name='翠湖一期' LIMIT 1;
  IF pid IS NULL THEN
    INSERT INTO project(org_id,name,org_name,area,comm_in_rate,status)
      VALUES(prop,'翠湖一期','翠湖物业','华东',0.0800,'ACTIVE') RETURNING id INTO pid;
  END IF;

  -- (A) script_lib 若干条（飞轮全链路样本：scene/intent/source/status/uses/promise_rate/repay_rate/wilson/variant）
  --     注：promise_rate/repay_rate/wilson 存 NUMERIC 百分比，应用层 GET 时 /100 转契约 Rate 分数。
  IF NOT EXISTS (SELECT 1 FROM script_lib) THEN
    -- 1) 专家话术·已晋升现行（EFFECTIVE，高 Wilson，飞轮第六环排名靠前）
    INSERT INTO script_lib(scene,intent,cohort,source,uses,promise_rate,repay_rate,wilson,status,variant)
      VALUES('首催开场','建立信任','长期欠费','EXPERT',1200,52.0000,38.0000,0.341200,'EFFECTIVE',NULL);
    -- 2) AI 挖掘话术·现行（EFFECTIVE，带已晋升变体快照可回滚）
    INSERT INTO script_lib(scene,intent,cohort,source,uses,promise_rate,repay_rate,wilson,status,variant)
      VALUES('催缴施压','促成承诺','高额欠费','AI_MINED',860,48.5000,40.1000,0.318700,'EFFECTIVE',
        '{"text":"……(晋升后现行变体文案)","uplift":5.2000,"state":"PROMOTED"}'::jsonb);
    -- 3) AI 挖掘话术·候选+待晋升胜出变体（CANDIDATE，variant.state=WINNER → 可走 promote 端点）
    INSERT INTO script_lib(scene,intent,cohort,source,uses,promise_rate,repay_rate,wilson,status,variant)
      VALUES('分期引导','分期承诺','一般欠费','AI_MINED',410,44.0000,33.0000,0.276500,'CANDIDATE',
        '{"text":"……(实验胜出变体文案)","uplift":7.8000,"state":"WINNER"}'::jsonb);
    -- 4) 专家话术·候选（CANDIDATE，刚录入 uses=0，飞轮第一环）
    INSERT INTO script_lib(scene,intent,cohort,source,uses,promise_rate,repay_rate,wilson,status,variant)
      VALUES('失联破冰','建立联系','失联户','EXPERT',0,NULL,NULL,NULL,'CANDIDATE',NULL);
    -- 5) 退役话术（RETIRED，低效淘汰，飞轮负反馈样本）
    INSERT INTO script_lib(scene,intent,cohort,source,uses,promise_rate,repay_rate,wilson,status,variant)
      VALUES('强硬催收','促成缴费','钉子户','AI_MINED',300,12.0000,6.0000,0.041000,'RETIRED',NULL);
  END IF;

  -- (B) playbook 1 条（挂 翠湖一期 project_id；现行已发布版，物业已采纳）
  IF NOT EXISTS (SELECT 1 FROM playbook WHERE project_id=pid) THEN
    INSERT INTO playbook(project_id,version,content,status,adopt_mode,adopted_by,adopted_at)
      VALUES(pid,'v1.0',
        '翠湖一期作战手册：首催以信任开场→施压促承诺→分期引导兜底；失联户先破冰。',
        'PUBLISHED','FORCE_MANUAL',plid,now());
  END IF;

  -- (C) ai_config 存 settings（domain='AI' 作 key=ai_config，value jsonb {llm,asr,prompts,flywheel}）
  IF NOT EXISTS (SELECT 1 FROM settings WHERE domain='AI') THEN
    INSERT INTO settings(domain,version,value,updated_by)
      VALUES('AI',1,
        '{"llm":{"provider":"deepseek","model":"deepseek-chat","temperature":0.3,"maxTokens":2048},'
        '"asr":{"provider":"bailian","model":"paraformer-8k-v2","hotwords":["物业费","滞纳金","分期"]},'
        '"prompts":{"preCall":"……(通话前策略提示词)","postReview":"……(通话后复盘提示词)","riskRules":"……(风险检测规则)"},'
        '"flywheel":{"autoIterate":true,"trigger":"uses>=300 AND wilson_uplift>=0.02","adoptMode":"FORCE_MANUAL","liveHint":false}}'::jsonb,
        said);
  END IF;
END $$;
