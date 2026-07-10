-- 送达类型：把「送达凭证」从普通跟进附件里区分出来，供协调员「送达管理」列表(GET /deliveries)聚合。
-- case_attachment.delivery_type 空=普通跟进附件（不进送达管理）；非空=送达凭证。
--   取值 LAWYER_LETTER(律师函)/COLLECTION_NOTICE(催收单)/COURT_DOC(诉讼文书)/OTHER(其他)。
-- upload_session.delivery_type：扫码上传会话携带，手机上传件(publicSessionUpload)继承写入附件。
ALTER TABLE case_attachment ADD COLUMN IF NOT EXISTS delivery_type TEXT;
ALTER TABLE upload_session  ADD COLUMN IF NOT EXISTS delivery_type TEXT;

COMMENT ON COLUMN case_attachment.delivery_type IS '送达类型：空=普通跟进附件；非空=送达凭证(LAWYER_LETTER/COLLECTION_NOTICE/COURT_DOC/OTHER)。';
COMMENT ON COLUMN upload_session.delivery_type  IS '扫码会话携带的送达类型；手机上传件继承为 case_attachment.delivery_type。';

-- 送达管理列表只扫 delivery_type 非空的送达凭证 → 部分索引。
CREATE INDEX IF NOT EXISTS ix_case_attachment_delivery ON case_attachment(delivery_type) WHERE delivery_type IS NOT NULL;
