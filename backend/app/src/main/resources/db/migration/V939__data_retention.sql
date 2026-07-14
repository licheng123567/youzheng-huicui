-- V939 数据留存与去标识化（个人信息保护）
--
-- 这个库里存的是业主的**真实姓名、手机号、身份证号、通讯地址、房号、欠费金额、
-- 通话录音字节、转写文本** —— 进库即永久，既没有留存期限，也没有任何删除路径。
-- 《个人信息保护法》要求「保存期限为实现处理目的所必需的最短时间」。这一版把期限落地。
--
-- ── 分级（为什么不是一刀切）────────────────────────────────────────────────────
--   · 业主 PII（姓名/手机/身份证/住址/房号）→ 结案后 60 天**去标识化**；
--   · 通话录音 + 转写                       → 结案后 6 个月**删除**；
--   · 回款/佣金/支付申请单                   → **不动**（会计凭证，法定保管期远长于此）。
--
-- **录音无法"去标识化"，只能删除**：声音本身就是个人信息（声纹属敏感个人信息），
-- 转写文本里满是姓名/住址/家庭状况。把姓名字段抹掉、录音还留着，等于什么都没做。
--
-- **为什么录音是 6 个月而不是 60 天**：催收业务最大的法律风险是业主投诉「暴力催收/骚扰」，
-- 那时通话录音是**平台自证清白的唯一证据**。60 天就删，等投诉来了手里什么都没有。
-- 6 个月是隐私与举证之间的折中。

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 法律保留（legal hold）
--
-- 正在投诉 / 诉讼 / 监管检查的案件，清理必须能**暂停** ——
-- 否则定时任务会在你最需要证据的那一刻，把证据删掉。
-- 由**物业**发起（他们是纠纷的第一线，也是数据的责任主体）。
ALTER TABLE "case" ADD COLUMN IF NOT EXISTS legal_hold        BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE "case" ADD COLUMN IF NOT EXISTS legal_hold_reason TEXT;
ALTER TABLE "case" ADD COLUMN IF NOT EXISTS legal_hold_by     BIGINT;
ALTER TABLE "case" ADD COLUMN IF NOT EXISTS legal_hold_at     TIMESTAMPTZ;

ALTER TABLE "case" ADD CONSTRAINT fk_case_legal_hold_by
    FOREIGN KEY (legal_hold_by) REFERENCES account(id) ON DELETE RESTRICT;

COMMENT ON COLUMN "case".legal_hold IS
    '法律保留：置 true 后，该案件的留存清理（PII 去标识化 / 录音删除）一律跳过。用于投诉、诉讼、监管检查期间保全证据。';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) 清理台账（做过什么，什么时候做的 —— 合规检查时要拿得出来）
ALTER TABLE "case" ADD COLUMN IF NOT EXISTS anonymized_at        TIMESTAMPTZ;
ALTER TABLE "case" ADD COLUMN IF NOT EXISTS recordings_purged_at TIMESTAMPTZ;

COMMENT ON COLUMN "case".anonymized_at        IS 'PII 去标识化完成时刻（姓名/手机/身份证/住址/房号）。非空 = 已处理，不再重复处理。';
COMMENT ON COLUMN "case".recordings_purged_at IS '通话录音与转写删除完成时刻。非空 = 已处理。';

-- 清理任务按「留存锚点」扫描，给它一个索引（否则每天全表扫）
CREATE INDEX IF NOT EXISTS ix_case_retention
    ON "case" (closed_at)
    WHERE closed_at IS NOT NULL AND legal_hold = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) 留存锚点视图
--
-- 时钟起点**不能只用 closed_at**：
--   · 「结案」包含**坏账(BAD_DEBT)**，而坏账案件将来可能重启诉讼；
--   · 佣金结算争议常在结案数月后才爆发（那时还需要回款明细对应的业主身份去核对）。
-- 所以锚点 = max(结案时间, 最后一笔回款, 最后一次存证, 最后一份法律文书)。
CREATE OR REPLACE VIEW case_retention_anchor AS
SELECT
    c.id AS case_id,
    c.legal_hold,
    c.anonymized_at,
    c.recordings_purged_at,
    GREATEST(
        c.closed_at,
        COALESCE((SELECT max(rl.created_at) FROM repay_line rl WHERE rl.case_id = c.id), c.closed_at),
        COALESCE((SELECT max(e.created_at)  FROM evidence e   WHERE e.case_id  = c.id), c.closed_at),
        COALESCE((SELECT max(l.created_at)  FROM legal_doc l  WHERE l.case_id  = c.id), c.closed_at)
    ) AS anchor_at
FROM "case" c
WHERE c.closed_at IS NOT NULL;

COMMENT ON VIEW case_retention_anchor IS
    '留存清理的时钟起点。不只看 closed_at：坏账案件可能重启诉讼、佣金争议常在结案数月后爆发，故取「结案 / 末笔回款 / 末次存证 / 末份法务文书」中的最晚者。';
