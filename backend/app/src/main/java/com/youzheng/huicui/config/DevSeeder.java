package com.youzheng.huicui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 【仅 dev】启动种子：给账号设 BCrypt 口令；种两个物业组织/PL/项目，
 * 用于演示 x-data-scope 跨租户隔离（SA 见全量、PL 仅见本组织项目）。生产 profile 应禁用。
 */
@Component
public class DevSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final String devPassword;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public DevSeeder(JdbcTemplate jdbc, @Value("${huicui.dev-password}") String devPassword) {
        this.jdbc = jdbc;
        this.devPassword = devPassword;
    }

    @Override
    public void run(String... args) {
        String hash = bcrypt.encode(devPassword);
        // 1) 给所有缺口令的账号设 dev 口令哈希
        jdbc.update("UPDATE account SET password_hash = ? WHERE password_hash IS NULL", hash);

        // 2) 两个物业组织 + PL + 项目（幂等）
        Long cuihu = ensureProperty("翠湖物业", "cuihu_pl", "翠湖负责人", "13900000001", hash);
        ensureProject(cuihu, "翠湖物业", "翠湖一期", "A区", "0.3000");
        ensureProject(cuihu, "翠湖物业", "翠湖二期", "B区", "0.2800");
        Long yang = ensureProperty("阳光物业", "yang_pl", "阳光负责人", "13900000002", hash);
        ensureProject(yang, "阳光物业", "阳光花园", "C区", "0.3200");

        // 3) 批次 + 案件（供前端 M2 批次/案件视图演示，并让 schemathesis 测 getBatch/getCase 的 200）
        Long proj = jdbc.query("SELECT id FROM project WHERE name = '翠湖一期'", rs -> rs.next() ? rs.getLong(1) : null);
        if (proj != null) {
            Long batch = jdbc.query("SELECT id FROM batch WHERE no = 'B-CH-2026-01'", rs -> rs.next() ? rs.getLong(1) : null);
            if (batch == null) {
                // 平台视角需双线均含→pay_out_rate 必须非空（契约 Batch 必填 payOutRate）
                batch = jdbc.queryForObject("INSERT INTO batch(project_id, no, comm_in_rate, comm_in_inherited, pay_out_rate, status) " +
                        "VALUES (?, 'B-CH-2026-01', 0.3000, TRUE, 0.2000, 'IN_PROGRESS') RETURNING id", Long.class, proj);
                ensureCase(batch, proj, "翠湖一期", "C-1001", "张三", "1-101", 360000L);
                ensureCase(batch, proj, "翠湖一期", "C-1002", "李四", "2-202", 480000L);
                ensureCase(batch, proj, "翠湖一期", "C-1003", "王五", "3-303", 120000L);
            }
        }
    }

    private void ensureCase(Long batchId, Long projectId, String projectName, String acctNo, String owner, String room, long dueCents) {
        jdbc.update("INSERT INTO \"case\"(batch_id, project_id, project_name, acct_no, owner_name, room, due_cents, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS')", batchId, projectId, projectName, acctNo, owner, room, dueCents);
    }

    private Long ensureProperty(String orgName, String username, String name, String phone, String hash) {
        Long oid = jdbc.query("SELECT id FROM org WHERE name = ? AND type = 'PROPERTY'",
                rs -> rs.next() ? rs.getLong(1) : null, orgName);
        if (oid == null) {
            oid = jdbc.queryForObject(
                    "INSERT INTO org(type, name, status) VALUES ('PROPERTY', ?, 'ACTIVE') RETURNING id",
                    Long.class, orgName);
        }
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM account WHERE username = ?", Integer.class, username);
        if (exists == null || exists == 0) {
            Long aid = jdbc.queryForObject(
                    "INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner, password_hash) " +
                    "VALUES (?, ?, ?, ?, 'PL', 'ACTIVE', TRUE, ?) RETURNING id",
                    Long.class, oid, username, name, phone, hash);
            jdbc.update("UPDATE org SET owner_account_id = ? WHERE id = ? AND owner_account_id IS NULL", aid, oid);
        }
        return oid;
    }

    private void ensureProject(Long orgId, String orgName, String name, String area, String rate) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM project WHERE org_id = ? AND name = ?", Integer.class, orgId, name);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO project(org_id, name, org_name, area, comm_in_rate, status) " +
                    "VALUES (?, ?, ?, ?, ?::numeric, 'ACTIVE')", orgId, name, orgName, area, rate);
        }
    }
}
