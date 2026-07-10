-- 通话录音音频字节存储（BR-M4-01b）：地基期录音上传仅存元数据、音频被丢弃，导致协调员/催收员无法「回听」。
-- 补音频原始字节 + Content-Type，供 GET /recordings/{id}/audio 流式回放（case-actor 行级裁剪，case.call）。
ALTER TABLE call_recording ADD COLUMN IF NOT EXISTS audio_bytes        BYTEA;
ALTER TABLE call_recording ADD COLUMN IF NOT EXISTS audio_content_type TEXT;
COMMENT ON COLUMN call_recording.audio_bytes IS '上传的通话录音音频原始字节，供协调员/催收员回听（BR-M4-01b）';
