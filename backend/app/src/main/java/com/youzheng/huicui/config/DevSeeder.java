package com.youzheng.huicui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 【仅 dev】启动种子：给账号设 BCrypt 口令；种两个物业组织/PL/项目，
 * 用于演示 x-data-scope 跨租户隔离（SA 见全量、PL 仅见本组织项目）。生产 profile 应禁用。
 *
 * M3 扩充：种 1 个服务商组织（VL 负责人 + 2 个 CO 催收员）、ROTATION.holdCap 配置，
 * 以及覆盖五稳态的案件（S0 待派单 / S1 待接单 / S2 服务商公海 / S3 私海进行中 / S4 开放抢单池），
 * 供 M3 派单/抢单端点联调与 schemathesis 跑通各前置态。所有 ensure 与状态种子均幂等。
 */
@Component
public class DevSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final String devPassword;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    // CFG-T2 / CFG-TC 仅用于种子 deadline 取值演示（真值读 settings TIMERS）。
    private static final String CFG_T2_INTERVAL = "interval '3 days'";
    private static final String CFG_TC_INTERVAL = "interval '7 days'";

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

        // 3) 服务商组织 + VL 负责人 + 2 个 CO 催收员（M3 承接/分配/抢单主体）
        Long provider = ensureProvider("捷信催收", "jx_vl", "捷信负责人", "13900000003", hash);
        Long co1 = ensureCollector(provider, "jx_co1", "催收员甲", "13900000004", hash);
        Long co2 = ensureCollector(provider, "jx_co2", "催收员乙", "13900000005", hash);

        // 4) ROTATION 配置（CFG-HOLDCAP）：holdCap=50
        ensureRotationSettings(50);

        // 5) 批次 + 案件（M2 读视图演示 + M3 五稳态联调；schemathesis 各前置态 200）
        Long proj = jdbc.query("SELECT id FROM project WHERE name = '翠湖一期'", rs -> rs.next() ? rs.getLong(1) : null);
        if (proj != null) {
            // ── M2 演示批次（IN_PROGRESS，含三件 IN_PROGRESS 私海占位案件，保留原样）──
            Long batch = jdbc.query("SELECT id FROM batch WHERE no = 'B-CH-2026-01'", rs -> rs.next() ? rs.getLong(1) : null);
            if (batch == null) {
                batch = jdbc.queryForObject("INSERT INTO batch(project_id, no, comm_in_rate, comm_in_inherited, pay_out_rate, status) " +
                        "VALUES (?, 'B-CH-2026-01', 0.3000, TRUE, 0.2000, 'IN_PROGRESS') RETURNING id", Long.class, proj);
                ensureCase(batch, proj, "翠湖一期", "C-1001", "张三", "1-101", 360000L);
                ensureCase(batch, proj, "翠湖一期", "C-1002", "李四", "2-202", 480000L);
                ensureCase(batch, proj, "翠湖一期", "C-1003", "王五", "3-303", 120000L);
            }
            seedM3States(proj, provider, co1);
        }
    }

    // ── M3 五稳态案件种子 ─────────────────────────────────────────────────────

    /**
     * 种覆盖五稳态的案件，挂在专用批次下（幂等：批次 no 不存在才建并种）。
     *   S0 待派单     批次 B-CH-M3-S0（平台公海未派）→ case PENDING_DISPATCH/PLATFORM_SEA
     *   S1 待接单     批次 B-CH-M3-S1（provider_id 已派）→ case PENDING_DISPATCH/PROVIDER_SEA source=DISPATCH t2
     *   S2 服务商公海 批次 B-CH-M3-S2（provider_id 已承接）→ case PROVIDER_SEA/PROVIDER_SEA source=ACCEPT t2
     *   S4 开放抢单池 批次 B-CH-M3-S4（open_rate 已设）→ case PENDING_DISPATCH/OPEN_POOL source=OPEN origin=OPEN_POOL
     *   S3 私海进行中 复用 S2 批次 → case IN_PROGRESS/PRIVATE holder=co1 source=CLAIM origin=PROVIDER_SEA tc
     */
    private void seedM3States(Long projId, Long providerOrg, Long coHolder) {
        // S0：平台公海待派单（无 provider）
        Long b0 = ensureBatch(projId, "B-CH-M3-S0", "0.3000", "0.2000", null, null, "PENDING");
        ensureSeaCase(b0, projId, "翠湖一期", "M3-S0-01", "赵待派", "S0-101", 300000L,
                "PENDING_DISPATCH", "PLATFORM_SEA", null, null, null, false, false);

        // S1：已派给服务商，待接/拒（provider_id 已设、status 仍 PENDING_DISPATCH、t2）
        Long b1 = ensureBatch(projId, "B-CH-M3-S1", "0.3000", "0.2000", providerOrg, null, "DISPATCHED");
        ensureSeaCase(b1, projId, "翠湖一期", "M3-S1-01", "钱待接", "S1-101", 320000L,
                "PENDING_DISPATCH", "PROVIDER_SEA", null, "DISPATCH", null, true /*t2*/, false);

        // S2：服务商已承接公海（status=PROVIDER_SEA、t2）
        Long b2 = ensureBatch(projId, "B-CH-M3-S2", "0.3000", "0.2000", providerOrg, null, "IN_PROGRESS");
        ensureSeaCase(b2, projId, "翠湖一期", "M3-S2-01", "孙公海", "S2-101", 280000L,
                "PROVIDER_SEA", "PROVIDER_SEA", null, "ACCEPT", null, true /*t2*/, false);

        // S3：私海进行中（复用 S2 批次的服务商归属；holder=co1、origin=PROVIDER_SEA、tc）
        ensureSeaCase(b2, projId, "翠湖一期", "M3-S3-01", "周私海", "S3-101", 260000L,
                "IN_PROGRESS", "PRIVATE", coHolder, "CLAIM", "PROVIDER_SEA", false, true /*tc*/);

        // S4：开放抢单池（批次 open_rate 已设；origin=OPEN_POOL，便于释放回流测）
        Long b4 = ensureBatch(projId, "B-CH-M3-S4", "0.3000", "0.2000", null, "0.1800", "PENDING");
        ensureSeaCase(b4, projId, "翠湖一期", "M3-S4-01", "吴抢单", "S4-101", 240000L,
                "PENDING_DISPATCH", "OPEN_POOL", null, "OPEN", "OPEN_POOL", false, false);
    }

    /** 批次幂等：按 (project_id, no) 唯一。可选 provider_id / open_rate。 */
    private Long ensureBatch(Long projId, String no, String commInRate, String payOutRate,
                             Long providerId, String openRate, String status) {
        Long id = jdbc.query("SELECT id FROM batch WHERE project_id = ? AND no = ?",
                rs -> rs.next() ? rs.getLong(1) : null, projId, no);
        if (id != null) return id;
        return jdbc.queryForObject(
                "INSERT INTO batch(project_id, no, comm_in_rate, comm_in_inherited, pay_out_rate, provider_id, open_rate, status) "
                        + "VALUES (?, ?, ?::numeric, TRUE, ?::numeric, ?, ?::numeric, ?) RETURNING id",
                Long.class, projId, no, commInRate, payOutRate, providerId, openRate, status);
    }

    /**
     * M3 稳态案件幂等：按 (batch_id, acct_no) 唯一（uq_case_batch_acct）。
     * t2 / tc 为 true 时按 CFG-T2 / CFG-TC 设 now()+interval；holder/source/origin 可空。
     */
    private void ensureSeaCase(Long batchId, Long projId, String projName, String acctNo, String owner,
                               String room, long dueCents, String status, String pool, Long holderId,
                               String source, String originPool, boolean t2, boolean tc) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM \"case\" WHERE batch_id = ? AND acct_no = ?",
                Integer.class, batchId, acctNo);
        if (exists != null && exists > 0) return;
        String t2Expr = t2 ? "now() + " + CFG_T2_INTERVAL : "NULL";
        String tcExpr = tc ? "now() + " + CFG_TC_INTERVAL : "NULL";
        jdbc.update(
                "INSERT INTO \"case\"(batch_id, project_id, project_name, acct_no, owner_name, room, due_cents,"
                        + " status, pool, holder_id, source, origin_pool, t2_deadline, t_collector_deadline) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + t2Expr + ", " + tcExpr + ")",
                batchId, projId, projName, acctNo, owner, room, dueCents,
                status, pool, holderId, source, originPool);
    }

    private void ensureCase(Long batchId, Long projectId, String projectName, String acctNo, String owner, String room, long dueCents) {
        jdbc.update("INSERT INTO \"case\"(batch_id, project_id, project_name, acct_no, owner_name, room, due_cents, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS')", batchId, projectId, projectName, acctNo, owner, room, dueCents);
    }

    // ── 组织/账号种子 ─────────────────────────────────────────────────────────

    private Long ensureProperty(String orgName, String username, String name, String phone, String hash) {
        return ensureOrgWithOwner("PROPERTY", orgName, username, name, phone, "PL", hash);
    }

    /** 服务商组织 + VL 负责人（M3 承接/退回主体）。 */
    private Long ensureProvider(String orgName, String username, String name, String phone, String hash) {
        return ensureOrgWithOwner("PROVIDER", orgName, username, name, phone, "VL", hash);
    }

    private Long ensureOrgWithOwner(String type, String orgName, String username, String name,
                                    String phone, String ownerRole, String hash) {
        Long oid = jdbc.query("SELECT id FROM org WHERE name = ? AND type = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgName, type);
        if (oid == null) {
            oid = jdbc.queryForObject(
                    "INSERT INTO org(type, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                    Long.class, type, orgName);
        }
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM account WHERE username = ?", Integer.class, username);
        if (exists == null || exists == 0) {
            Long aid = jdbc.queryForObject(
                    "INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner, password_hash) " +
                    "VALUES (?, ?, ?, ?, ?, 'ACTIVE', TRUE, ?) RETURNING id",
                    Long.class, oid, username, name, phone, ownerRole, hash);
            jdbc.update("UPDATE org SET owner_account_id = ? WHERE id = ? AND owner_account_id IS NULL", aid, oid);
        }
        return oid;
    }

    /** 催收员账号（role_template=CO，非负责人）。返回 account.id。 */
    private Long ensureCollector(Long orgId, String username, String name, String phone, String hash) {
        Long aid = jdbc.query("SELECT id FROM account WHERE username = ?",
                rs -> rs.next() ? rs.getLong(1) : null, username);
        if (aid != null) return aid;
        return jdbc.queryForObject(
                "INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner, password_hash) " +
                "VALUES (?, ?, ?, ?, 'CO', 'ACTIVE', FALSE, ?) RETURNING id",
                Long.class, orgId, username, name, phone, hash);
    }

    private void ensureProject(Long orgId, String orgName, String name, String area, String rate) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM project WHERE org_id = ? AND name = ?", Integer.class, orgId, name);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO project(org_id, name, org_name, area, comm_in_rate, status) " +
                    "VALUES (?, ?, ?, ?, ?::numeric, 'ACTIVE')", orgId, name, orgName, area, rate);
        }
    }

    /** ROTATION 配置（CFG-HOLDCAP）：rotation jsonb 含 holdCap。updated_by 取任一 SA 账号。 */
    private void ensureRotationSettings(int holdCap) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM settings WHERE domain = 'ROTATION'", Integer.class);
        if (exists != null && exists > 0) return;
        Long sa = jdbc.query("SELECT id FROM account WHERE role_template = 'SA' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (sa == null) return; // 无 SA 则跳过（V900 应已种平台 SA）
        jdbc.update(
                "INSERT INTO settings(domain, version, rotation, updated_by) "
                        + "VALUES ('ROTATION', 1, ?::jsonb, ?)",
                "{\"holdCap\":" + holdCap + "}", sa);
    }
}
