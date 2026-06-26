-- V912：BR-M2-18b 批次级覆盖同步 —— 覆盖基线列（reduceDrift/playbookDrift 来源）。
-- 背景：批次自定义减免阶梯(reduce_tier.batch_id 非空)与（前向预留的）批次级作战手册覆盖，
--       需记录“覆盖发生时的项目级基线”，以便后续比对项目级是否已更新而该批次有差异
--       （“项目级已更新·有差异”+“一键同步为项目最新” BR-M2-18b）。
-- 安全：纯加列 + 回填，ADD COLUMN IF NOT EXISTS 幂等，对既有行无破坏。
--       reduce_tier 项目级（batch_id IS NULL）行基线列恒留 NULL（无意义，仅批次覆盖行使用）。
-- 注：playbook DDL 仅 project_id 无 batch_id（批次手册经 project 折叠，见 PlaybookController），
--    故 baseline_* 为前向兼容预留——当前批次级手册恒 INHERITED、playbookDrift 恒 false；
--    待批次级手册落地存储后启用，避免后续二次迁移。

-- ── reduce_tier：批次覆盖行的“覆盖时项目级基线”──────────────────────────────
-- baseline_project_updated_at = 覆盖写入时刻项目级减免阶梯(同 project_id, batch_id IS NULL)的 max(updated_at)。
-- 项目级当前 max(updated_at) > 本列 → 该批次 reduceDrift=true（项目已更新而批次仍持旧自定义）。
ALTER TABLE reduce_tier ADD COLUMN IF NOT EXISTS baseline_project_updated_at TIMESTAMPTZ;
COMMENT ON COLUMN reduce_tier.baseline_project_updated_at IS
    '批次覆盖行(batch_id 非空)写入时刻的项目级减免阶梯 max(updated_at) 快照；项目级当前 max(updated_at) 更新即 reduceDrift。项目级行(batch_id IS NULL)恒 NULL。BR-M2-18b';

-- 回填既有批次覆盖行：以“当前项目级 max(updated_at)”作基线（视为同步态，避免历史覆盖一上线即误报 drift）。
UPDATE reduce_tier rt
   SET baseline_project_updated_at = (
        SELECT max(p.updated_at)
          FROM reduce_tier p
         WHERE p.project_id = rt.project_id
           AND p.batch_id IS NULL
   )
 WHERE rt.batch_id IS NOT NULL
   AND rt.baseline_project_updated_at IS NULL;

-- ── playbook：前向预留批次级覆盖基线（当前未启用，见文件头注释）────────────────
ALTER TABLE playbook ADD COLUMN IF NOT EXISTS baseline_project_version TEXT;
ALTER TABLE playbook ADD COLUMN IF NOT EXISTS baseline_project_updated_at TIMESTAMPTZ;
COMMENT ON COLUMN playbook.baseline_project_version IS
    '【前向预留】批次级手册覆盖时的项目现行手册 version 快照；DDL 暂无批次级手册存储，当前未写入。BR-M2-18b';
COMMENT ON COLUMN playbook.baseline_project_updated_at IS
    '【前向预留】批次级手册覆盖时的项目现行手册 updated_at 快照；当前未写入，playbookDrift 恒 false。BR-M2-18b';
