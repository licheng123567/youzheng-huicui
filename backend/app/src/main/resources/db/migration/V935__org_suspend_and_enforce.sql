-- V935 停权口径落地（v1.22.0）。用户拍板的两条：
--   ① 个人停权 = **组织自己停**，平台保留强制兜底（组织逾期不回执才由平台强停）。
--      此前 QcM5Controller 复核选 DEACTIVATE 会当场 `UPDATE account SET status='DISABLED'`——
--      平台越过组织直接停别人家的人，与 BR-M5-07「谁的员工谁处置」互相打架。改为：下决定→建处置任务→
--      归属方负责人在自己的成员管理执行停用并回执；逾期未回执，平台才可 enforce 强停（留痕）。
--   ② 组织级停权 = **平台的闸**，且是「停新单不断存量」。
--      org.status 一直有、派单也一直在校验 `type='PROVIDER' AND status='ACTIVE'`（DispatchM3Controller），
--      但**没有任何端点能改它**——商业上最该有的那把闸只能手工改库。本迁移不改表结构（status 已存在），
--      靠新端点 POST /orgs/{id}/disable|enable 把它接上，并补 org.disabled_* 留痕列。

-- ── 1) 处置任务：平台强制执行留痕（谁强停的/为什么/什么时候）──
ALTER TABLE dispose_task
    ADD COLUMN enforced_at     TIMESTAMPTZ,
    ADD COLUMN enforced_by     BIGINT,
    ADD COLUMN enforce_reason  TEXT,
    ADD CONSTRAINT fk_dt_enforced_by FOREIGN KEY (enforced_by) REFERENCES account(id) ON DELETE SET NULL;
COMMENT ON COLUMN dispose_task.enforced_at IS '平台强制停用当事人账号的时刻（仅 decision=DEACTIVATE 且归属方逾期未回执时可为非空）';

-- ── 2) 组织停用留痕（status 列已存在，只补「谁停的/为什么/何时」）──
ALTER TABLE org
    ADD COLUMN disabled_at     TIMESTAMPTZ,
    ADD COLUMN disabled_by     BIGINT,
    ADD COLUMN disabled_reason TEXT,
    ADD CONSTRAINT fk_org_disabled_by FOREIGN KEY (disabled_by) REFERENCES account(id) ON DELETE SET NULL;
COMMENT ON COLUMN org.status IS 'ACTIVE/DISABLED。DISABLED=停新单不断存量：不能被派新单(派单已校验)、不能新建项目/导入批次；但成员照常登录、在催案件照常作业、结算照常——一按就把在催案件变死账会伤到物业的回款。要清场另走「批次结项」。';
