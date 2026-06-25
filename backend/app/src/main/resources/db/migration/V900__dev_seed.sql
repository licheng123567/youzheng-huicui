-- 【仅地基/dev 演示种子】平台组织 + 超管账号，供 /v1/me 从真 PG 读取一个合规主体。
-- 生产 profile 应通过 spring.flyway.locations 排除 classpath 下本目录。
DO $$
DECLARE oid BIGINT; aid BIGINT;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM org WHERE type = 'PLATFORM') THEN
    INSERT INTO org(type, name, status) VALUES ('PLATFORM', '有证平台', 'ACTIVE') RETURNING id INTO oid;
    INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner)
      VALUES (oid, 'admin', '平台超管', '13800000000', 'SA', 'ACTIVE', TRUE) RETURNING id INTO aid;
    UPDATE org SET owner_account_id = aid WHERE id = oid;
  END IF;
END $$;
