-- V936 三方通道后台配置（v1.23.0）。用户诉求：「易保全、LLM、ASR 这些三方接口的 key，后台没有配置界面。」
-- 拍板：密钥**存库加密**，平台超管可在后台填写；主密钥走环境变量（HUICUI_CRYPTO_KEY）。
--
-- 【只做真接得通的通道】易保全(存证) + 智讯云(短信)——这两个客户端真实存在，填了就真能用。
--   LLM/ASR 的客户端**还没写**（integration/ 下没有 DeepSeek/百炼 client，yml 里也没有 api-key 配置项，
--   真 AI 是 Phase 3），此刻给它们做 key 输入框 = 填了没人读的空壳页——本项目已反复踩过这种坑，不再造。
--   故本表的 provider 只允许 EBAOQUAN/SMS；AI 区域在前端显式标注「待接入」。
--
-- 【解析优先级】DB(本表) → application.yml(环境变量) → 默认值。DB 留空的字段自动回落 yml，
--   于是「已经用环境变量部署好的实例」升级后行为完全不变，不需要先去后台补一遍。
--
-- 【密文格式】secrets = {"appKey": {"cipher": "<base64(iv||ciphertext||tag)>", "last4": "…abc"}}
--   AES-256-GCM，密钥 = SHA-256(HUICUI_CRYPTO_KEY)。**明文永不出接口**：读接口只回 last4 掩码。
--   主密钥未配置时写密钥端点直接 409（宁可存不进去，也不要把 key 明文躺在库里）。

CREATE TABLE integration_config (
    provider   TEXT        PRIMARY KEY,
    enabled    BOOLEAN     NOT NULL DEFAULT FALSE,
    settings   JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- 非密字段：baseUrl / signName / smsBaseUrl 等
    secrets    JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- 密文字段：{key: {cipher, last4}}；明文永不落库
    updated_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ic_updated_by FOREIGN KEY (updated_by) REFERENCES account(id) ON DELETE SET NULL,
    -- LLM/ASR 不入表：客户端未实现，写进来只会造出「配了但不生效」的假象
    CONSTRAINT chk_ic_provider CHECK (provider IN ('EBAOQUAN', 'SMS'))
);
COMMENT ON TABLE  integration_config IS '三方通道配置（平台级·SA 可在后台维护）。DB 缺字段→回落 application.yml，故环境变量部署的实例升级后行为不变';
COMMENT ON COLUMN integration_config.secrets IS 'AES-256-GCM 密文 {key:{cipher,last4}}；主密钥=SHA-256(HUICUI_CRYPTO_KEY)。明文永不出接口，读接口只回 last4';

-- 不预置行：没有行 = 完全走 yml（现状），语义最干净。首次在后台保存时 upsert 建行。
