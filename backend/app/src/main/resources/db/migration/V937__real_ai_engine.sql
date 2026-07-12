-- V937 真 AI 接入（v1.24.0）：百炼 ASR 转写 + DeepSeek 复盘/风险检测。
--
-- 用户质疑得对：「LLM/ASR 用阿里百炼和 DeepSeek，为什么不能后台配置 key？」
-- 上一版的理由（客户端没写、填了没人读）是在陈述现状，不是辩护。现在把引擎补上——
-- 周边其实早就铺好了，一直在等引擎：
--   · STT 按分钟计费 + 余额不足落 QUOTA_BLOCKED + 前端「补解析」按钮（v1.19.0）
--   · 录音回听 / AI 复盘面板 / 话术飞轮的采纳-晋升闭环
--   · 质检风控的 risk_record → 复核 → 处置任务闭环（v1.15.0/v1.22.0）
-- 唯独 RecordingService 写着「地基期不真正跑 ASR：一律落 PARSING」，READY 的转写文本全是种子。
--
-- 【为什么要公开音频 URL】百炼「录音文件识别」是异步任务：提交 file_urls → 阿里侧**主动来拉音频** →
--   轮询任务状态 → 取转写 JSON。而我们的录音是 bytea 存在库里的，没有可拉取的地址。
--   两条路：① 开一个带签名 token 的公开音频端点（本迁移选它）；② 走 WebSocket 实时协议推音频帧（不暴露
--   任何公开 URL，但协议复杂、失败重试难做）。选 ① 的前提是生产本就有公网域名（缴费链接 HUICUI_PUBLIC_BASE
--   一直在用）。安全靠三条：token 32 字节随机不可猜、**TTL 30 分钟**、过期即废且只能取到音频字节本身。

-- ── 1) 三方通道扩容：百炼(ASR) + DeepSeek(LLM) ──
-- V936 的 CHECK 只允许 EBAOQUAN/SMS（当时故意不放 LLM/ASR：客户端没写，填了没人读=空壳页）。
-- 现在引擎有了，把它们放进来——key 走同一套 AES-256-GCM 加密落库 + 掩码回显 + 审计不记值。
ALTER TABLE integration_config DROP CONSTRAINT chk_ic_provider;
ALTER TABLE integration_config ADD CONSTRAINT chk_ic_provider
    CHECK (provider IN ('EBAOQUAN', 'SMS', 'BAILIAN', 'DEEPSEEK'));

-- ── 2) 录音：ASR 异步任务的落脚点 ──
ALTER TABLE call_recording
    ADD COLUMN asr_task_id      TEXT,                  -- 百炼异步任务 id（提交后回填，轮询用）
    ADD COLUMN asr_submitted_at TIMESTAMPTZ,           -- 提交时刻（用于超时判定：卡住的任务不能永远 PARSING）
    ADD COLUMN ai_reviewed_at   TIMESTAMPTZ;           -- LLM 复盘完成时刻（幂等：非空则不重复复盘）
CREATE INDEX idx_call_recording_asr_task ON call_recording (asr_task_id) WHERE asr_task_id IS NOT NULL;
COMMENT ON COLUMN call_recording.asr_task_id IS '百炼录音文件识别的异步任务 id。PARSING + 非空 → 轮询器负责推进到 READY/FAILED';

-- ── 3) 转写句段（带说话人与时间轴）──
-- transcript 那列是整段纯文本（既有，前端已在用）；句段单独存，AI 复盘面板要按时间轴高亮风险片段。
CREATE TABLE transcript_segment (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recording_id BIGINT      NOT NULL,
    seq          INT         NOT NULL,                 -- 句序（0 起）
    speaker      TEXT,                                 -- 说话人（百炼 diarization 给的 channel/speaker_id）
    begin_ms     INT,
    end_ms       INT,
    text         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ts_rec FOREIGN KEY (recording_id) REFERENCES call_recording(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_ts_rec_seq ON transcript_segment (recording_id, seq);
COMMENT ON TABLE transcript_segment IS '转写句段（说话人+时间轴）。重跑转写前先按 recording_id 清空再写，避免 seq 冲突';

-- ── 4) 音频拉取签名 token（只给 ASR 用）──
-- 单表即够：token 是主键，过期行由轮询器顺手清（不建定时清理任务，省一个运维面）。
CREATE TABLE recording_pub_token (
    token        TEXT        PRIMARY KEY,
    recording_id BIGINT      NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_rpt_rec FOREIGN KEY (recording_id) REFERENCES call_recording(id) ON DELETE CASCADE
);
CREATE INDEX idx_rpt_expires ON recording_pub_token (expires_at);
COMMENT ON TABLE recording_pub_token IS '录音音频的一次性公开拉取凭据（供百炼异步拉取）。TTL 30min，过期即废；除音频字节外不暴露任何信息';

-- ── 5) AI 复盘：记下是哪个模型出的，便于事后追责/回归 ──
ALTER TABLE ai_review
    ADD COLUMN model        TEXT,                      -- 如 deepseek-chat；NULL=种子/占位数据
    ADD COLUMN generated_at TIMESTAMPTZ;
COMMENT ON COLUMN ai_review.model IS '出这份复盘的模型；NULL 表示占位/种子数据（不是真模型产出）';
