-- 【仅地基/dev 演示种子】平台组织 + 超管账号，供 /v1/me 从真 PG 读取一个合规主体。
-- 生产 profile 应通过 spring.flyway.locations 排除 classpath 下本目录。
-- 口令：password_hash 列由 V901 之后才存在，此处不写；DevSeeder 启动时按 huicui.dev-password(Admin@123)
--       回填 password_hash IS NULL 的账号——故本文件新种账号留 NULL 即可，口令与 e2e helpers 一致。
DO $$
DECLARE oid BIGINT; aid BIGINT;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM org WHERE type = 'PLATFORM') THEN
    INSERT INTO org(type, name, status) VALUES ('PLATFORM', '有证平台', 'ACTIVE') RETURNING id INTO oid;
    INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner)
      VALUES (oid, 'admin', '平台超管', '13800000000', 'SA', 'ACTIVE', TRUE) RETURNING id INTO aid;
    UPDATE org SET owner_account_id = aid WHERE id = oid;
  END IF;
  -- 物业/服务商组织及 PL/PC/VL/CO 演示账号由 DevSeeder（dev profile）统一种子，此处不重复
  -- （单一来源避免与 e2e helpers ACCOUNTS 双轨漂移）。
END $$;
