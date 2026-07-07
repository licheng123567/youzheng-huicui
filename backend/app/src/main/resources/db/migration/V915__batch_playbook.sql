-- V915 批次级作战手册存储（issue #2：批次能独立覆盖/恢复继承作战手册 BR-M2-18b/BR-M5-05a/b）
--
-- 背景：playbook 表原仅 project_id 维（无 batch_id），批次手册经 project 折叠 → 批次级 source 恒 INHERITED、
--   playbookDrift 恒 false。本迁移与 reduce_tier 同范式引入 batch_id 维，使批次能独立覆盖手册：
--     batch_id IS NULL → 项目级手册（原语义不变）；
--     batch_id 非空    → 批次级覆盖手册（CUSTOM）。
--   baseline_project_version / baseline_project_updated_at 列已由 V912 前向预留，本迁移起正式启用：
--   覆盖写入时快照项目级现行手册的 version/updated_at，供 getBatch 比对项目级是否已更新（playbookDrift）。
--
-- 安全：纯加列 + FK + 索引，IF NOT EXISTS 幂等；既有行 batch_id 默认 NULL（仍是项目级）。

-- ── playbook 加 batch_id（NULL=项目级，非空=批次级覆盖）──────────────────────────
ALTER TABLE playbook ADD COLUMN IF NOT EXISTS batch_id BIGINT;
COMMENT ON COLUMN playbook.batch_id IS
    '批次级覆盖归属批次（NULL=项目级手册，原语义；非空=该批次自定义覆盖手册 BR-M2-18b）。删批次级行=恢复继承。';

-- 可空外键：删批次级联删该批次的覆盖手册行（项目级行 batch_id IS NULL 不受影响）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_playbook_batch'
    ) THEN
        ALTER TABLE playbook
            ADD CONSTRAINT fk_playbook_batch FOREIGN KEY (batch_id)
            REFERENCES batch(id) ON DELETE CASCADE;
    END IF;
END $$;

-- 批次级覆盖检索索引（getBatchPlaybook 现行版按 batch_id 取）。
CREATE INDEX IF NOT EXISTS idx_playbook_batch_id ON playbook (batch_id);
