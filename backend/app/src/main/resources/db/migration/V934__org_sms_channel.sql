-- V934 短信通道按组织管理（v1.21.0）。
-- 用户诉求：「短信通道按组织进行管理，主要是物业公司组织。看每个物业公司的短信配置和短信模板。」
-- 决策（用户拍板）：**签名与模板由平台统一配置，物业不能编辑**；模板由平台代向运营商报备。
--   网关账号=平台一份（ZhixunyunSmsClient 的 secret 仍是全局）；短信费用继续走 SMS 预付额度（物业充值）。
--
-- 此前现状：短信配置只有 application.yml 的**全局单例**（一个签名、一套模板ID）——无组织维度；
--   settings.sms JSONB 是「只写不读的黑洞」（SA 能在参数配置页填签名/模板，后端从不读）；
--   且 UI 写 cooldownMinutes 而后端读 cooldownSeconds（键名不一致 → 改了永不生效，真 bug）。
-- 本迁移建立组织维权威配置；解析链收敛为：org_sms_config → settings.sms(平台默认) → yml → 常量。

-- ── 1) 组织短信配置（一物业一行，org_id 作 PK → upsert 天然幂等）──
CREATE TABLE org_sms_config (
    org_id               BIGINT      PRIMARY KEY,
    sign_name            TEXT        NOT NULL,                       -- 已向运营商报备的签名，如【翠湖物业】
    cooldown_seconds     INT         NOT NULL DEFAULT 21600,         -- 同案短信冷却（秒）。对齐后端既有兜底 6h
    pay_link_ttl_seconds INT         NOT NULL DEFAULT 604800,        -- 缴费链接有效期（秒）7d
    warn_threshold       INT,                                        -- 短信余额条数预警（NULL=不预警）
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,          -- 平台级 kill-switch：停某物业发信
    updated_by           BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_osc_org        FOREIGN KEY (org_id)     REFERENCES org(id)     ON DELETE CASCADE,
    CONSTRAINT fk_osc_updated_by FOREIGN KEY (updated_by) REFERENCES account(id) ON DELETE SET NULL,
    CONSTRAINT chk_osc_cooldown  CHECK (cooldown_seconds >= 0),
    CONSTRAINT chk_osc_ttl       CHECK (pay_link_ttl_seconds > 0),
    CONSTRAINT chk_osc_warn      CHECK (warn_threshold IS NULL OR warn_threshold >= 0)
);
COMMENT ON TABLE  org_sms_config IS '组织短信配置（权威）。平台统一配置、物业只读；缺行时发送侧回落 settings.sms → yml';

-- ── 2) 组织短信模板（平台代报备：DRAFT → 报备 → ACTIVE/REJECTED）──
CREATE TABLE org_sms_template (
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id              BIGINT      NOT NULL,
    kind                TEXT        NOT NULL,                        -- 用途：缴费链接/通知/视频通知
    name                TEXT        NOT NULL,
    content             TEXT        NOT NULL,                        -- 报备正文，占位 {0} {1}（与 var_order 下标同构）
    gateway_template_id TEXT,                                        -- 运营商报备回填；ACTIVE 时非空
    status              TEXT        NOT NULL DEFAULT 'DRAFT',
    var_order           JSONB       NOT NULL DEFAULT '[]'::jsonb,    -- 变量顺序（防报备错位：["payUrl"] / ["ownerName","amount","payUrl"]）
    reject_reason       TEXT,
    updated_by          BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ost_org        FOREIGN KEY (org_id)     REFERENCES org(id)     ON DELETE CASCADE,
    CONSTRAINT fk_ost_updated_by FOREIGN KEY (updated_by) REFERENCES account(id) ON DELETE SET NULL,
    -- VERIFY_CODE 不入本表：验证码是平台级短信（发送时 orgId=null，登录前无组织上下文），恒走 yml 全局模板
    CONSTRAINT chk_ost_kind   CHECK (kind   IN ('PAY_LINK','NOTIFY','VIDEO_NOTIFY')),
    CONSTRAINT chk_ost_status CHECK (status IN ('DRAFT','ACTIVE','REJECTED','ARCHIVED')),
    -- 不变式：生效模板必有报备号
    CONSTRAINT chk_ost_active_has_gw CHECK (status <> 'ACTIVE' OR gateway_template_id IS NOT NULL)
);
-- 一个组织的每种用途**只能有一个生效模板**（发送侧无歧义）；但允许并存多个 DRAFT（改版待报备）
-- 与历史 ARCHIVED（流水可追溯）——故用 partial unique 而非 UNIQUE(org_id,kind)。
CREATE UNIQUE INDEX uq_org_sms_tpl_active ON org_sms_template (org_id, kind) WHERE status = 'ACTIVE';
CREATE INDEX idx_ost_org_status ON org_sms_template (org_id, status);
COMMENT ON TABLE org_sms_template IS '组织短信模板。平台代向运营商报备：DRAFT→register→ACTIVE(回填模板ID)/REJECTED；同 org×kind 只一个 ACTIVE';

-- ── 3) 回填：给每个物业组织建配置行（平台 UI 的组织列表要能直接展示"这个物业的签名"）──
-- 取值优先级：settings.sms 的既有平台默认 → 硬编码兜底。
-- **cooldown 两个键都吃**：修 bug——UI/契约写 cooldownMinutes(分)，后端却读 cooldownSeconds(秒)，
--   导致 SA 改了永远不生效、恒走 6h 兜底。此处 cooldownSeconds 优先，缺则 cooldownMinutes×60。
INSERT INTO org_sms_config (org_id, sign_name, cooldown_seconds, pay_link_ttl_seconds, warn_threshold)
SELECT o.id,
       COALESCE(NULLIF(s.sms ->> 'signature', ''), '【有证慧催】'),
       COALESCE((s.sms ->> 'cooldownSeconds')::int,
                (s.sms ->> 'cooldownMinutes')::int * 60,
                21600),
       COALESCE((s.sms ->> 'payLinkTtlSeconds')::int, 604800),
       NULLIF(s.sms ->> 'warnThreshold', '')::int
FROM org o
LEFT JOIN LATERAL (
    SELECT sms FROM settings WHERE domain = 'SMS' ORDER BY version DESC LIMIT 1
) s ON TRUE
WHERE o.type = 'PROPERTY';

-- 模板**不回填**：yml 的 templates.pay-link 目前为空串，造一条假的 ACTIVE 会变成
-- 「以为报备了其实没有」——真实模板ID 必须由平台报备后经端点回填。dev/e2e 数据走 DevSeeder。

COMMENT ON COLUMN settings.sms IS '短信平台默认值（v1.21.0 降级）：signature/cooldownSeconds|cooldownMinutes/payLinkTtlSeconds/warnThreshold 作为组织无配置时的兜底；templates 已由 org_sms_template 接管，服务端忽略';
