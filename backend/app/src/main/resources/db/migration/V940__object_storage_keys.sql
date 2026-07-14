-- V940 对象存储：给录音与附件加 object key
--
-- 录音（8k 通话音频）与送达附件此前直接以 bytea 存在 Postgres 里。一个批次几千通电话就能把库撑到
-- 几十 GB，而它拖累的是一整串东西：
--   · 备份 —— pg_dump 体积随录音线性增长，而这个系统的备份还要加密、要传异地；
--   · 连接池 —— 一次回听要把整段音频读进 JVM 再吐出去，大对象长时间占住一条连接（池子只有 10 条）；
--   · 迁移 —— 以后想换库、做只读副本，都得先把这几十 GB 挪走。
--
-- **迁移策略是「双读」，不是「一刀切」**：老数据留在 bytea，新数据进对象存储；
-- 读的时候先看 key，没有就回落 bytea。一次重构把已有录音读丢，是绝不能接受的。
-- 因此这里**只加列、不删列**，bytea 列保留（存量数据还在里面）。

ALTER TABLE call_recording ADD COLUMN IF NOT EXISTS audio_key TEXT;
COMMENT ON COLUMN call_recording.audio_key IS
    '对象存储 key（启用 HUICUI_STORAGE_TYPE=s3 后写入）。为空 → 回落读 audio_bytes（存量录音）。';

ALTER TABLE case_attachment ADD COLUMN IF NOT EXISTS storage_key TEXT;
COMMENT ON COLUMN case_attachment.storage_key IS
    '对象存储 key。为空 → 回落读 bytes（存量附件）。';

-- bytes 是 NOT NULL 的：切到对象存储后新附件不再往库里塞字节，得允许它为空。
-- 空字节数组（''::bytea）也能满足 NOT NULL，但那样每行仍要存一个空对象、且语义含糊 ——
-- 直接放开约束更诚实：字节要么在库里、要么在对象存储里，二者必居其一（由 storage_key 区分）。
ALTER TABLE case_attachment ALTER COLUMN bytes DROP NOT NULL;

-- 留存清理要按 key 找对象来删（不能只把库里的 key 抹掉、把对象留在桶里）
CREATE INDEX IF NOT EXISTS ix_call_recording_audio_key ON call_recording (audio_key) WHERE audio_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_case_attachment_storage_key ON case_attachment (storage_key) WHERE storage_key IS NOT NULL;
