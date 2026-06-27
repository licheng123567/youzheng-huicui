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
 *
 * M4 扩充：给 S3 私海案件（acct_no=M3-S3-01，holder=jx_co1）挂外围实体各 1 条——
 * 录音(READY)/AI复盘/承诺(分期)/联系人/工单（+缴费链接/减免/回款明细 与 翠湖物业 PC 协调员），
 * 使 M4 GET 端点（latest/recordings/{id}/listRecordings/ai-review/promises/contacts/tickets）种子返 200。
 * 见 {@link #seedM4Collection}。全部幂等（先 SELECT count 判存在）。
 *
 * M9 扩充：复用 M2 演示批次 B-CH-2026-01（pay_out_rate=0.20）与三案件 C-1001/1002/1003，
 * 种 结算·支付申请单数据——三笔未结回款明细（settled=FALSE）、jx_co1 本批 co_commission(rate=0.15)、
 * 1 张 PENDING 付佣单(side=OUT, comm_rate=0.20) + 1 张 PAID 单(+PAYMENT 凭证) + 1 张 PENDING_PAY 催收员佣金单，
 * 供 M9 list/get/revoke/send/complete/co-commissions/co-pay-docs 端点有目标 id。
 * 见 {@link #seedM9Settlement}，物理隔离 M2/M3/M4 种子。
 *
 * M6/M7 扩充：给 S3 私海案件（M3-S3-01）种 存证 evidence 2 条（RECORDING+DELIVERY，ISSUED，
 * 形成 id 升序派生哈希链·不落库不改 schema，供 GET /evidence 与 verify 返 200）、
 * 法律文书 legal_doc 1 条（LAWYER_LETTER/SIGNED，关联第 2 条存证，供 GET /cases/{id}/legal-docs 与 deliver）；
 * M7 缴费复用 M4 已种 pay_link（token=demo-paylink-{caseS3Id}），仅补 case.reduce_after_cents/arrearags_periods
 * 与 project.pay_info 使 OwnerBill 字段非空；M10 报表走聚合查询无需专门种子。
 * 见 {@link #seedM6Evidence} / {@link #seedM7Bill}。全部幂等，物理隔离。
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

        // M10 监管动作：VL(jx_vl) 对本组织成员 CO(jx_co1) 种 1 条 TRAINING（own-org 裁剪目标）
        seedM10Supervision(provider, "jx_vl", "jx_co1");

        // 一号多账号(BR-M1-11)：同一手机 13900009000 关联 翠湖PC + 捷信CO 两个账号，演示多账号登录选择
        seedMultiAccount(cuihu, provider, hash);

        // 4) ROTATION 配置（CFG-HOLDCAP）：holdCap=50
        ensureRotationSettings(50);
        // 4b) TIMERS 配置（CFG-T1/T2/TC/MAXCYCLE + 预警提前量·已定稿值）
        ensureTimersSettings();
        // 4c) MARK_CODES 配置（CFG-MARK-CODES 内置五码）：使「通话结果标记」下拉首项=合法码(PROMISED)。
        //     无此种子时 MARK_CODES 域空，settings.spec 增的 E2E_OK 会成唯一/首项；
        //     而 RecordingService.markCodes() 读 mark_codes->>'markCodes'(数组无此键)→空→回退仅认内置五码 → E2E_OK 被 422 拒，
        //     拖垮 case-mark-result.spec。种内置五码后首项恒 PROMISED(合法)，E2E_OK 仅追加在尾，互不影响。
        ensureMarkCodesSettings();

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
            // M9 结算·支付申请单种子（复用 M2 演示批次 B-CH-2026-01 与三案件 C-1001/1002/1003）
            if (batch != null) {
                seedM9Settlement(batch, proj, provider, co1);
            }

            // M9-B 计费流水种子：充值/扣减流水（recharge_log）+ 短信发送流水（sms_record）。
            // 复用 base 既有 org/account/project/case（不重建 base 已管实体——避 V910 顺序 shadow 坑）。
            // 物业 org=翠湖物业（cuihu），服务商 org=捷信（provider），案件=S3 私海案件（M3-S3-01）。
            Long caseS3Id = jdbc.query(
                    "SELECT id FROM \"case\" WHERE project_id = ? AND acct_no = 'M3-S3-01'",
                    rs -> rs.next() ? rs.getLong(1) : null, proj);
            // 平台操作员（recharge_log.operated_by NOT NULL）：取任一 SA 账号（平台后台操作）。
            Long saAcct = jdbc.query("SELECT id FROM account WHERE role_template = 'SA' ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (saAcct != null) {
                seedM9Billing(cuihu, provider, saAcct);
            }
            seedSmsRecords(cuihu, proj, caseS3Id);

            // e2e 转真断言种子（物理隔离、幂等）：
            //  · audit-log.spec :21「代操作」→ 一条 proxy_for 非空审计（SA 代翠湖物业操作，含 before/after 快照）
            //  · sea-redispatch.spec :19/:37 护栏①→ 平台公海一件"被退回"案 + 其 case.return 审计(before_snap.providerId=捷信)
            //  · batch-sync-drift.spec :18/:29 reduceDrift→ 翠湖首批次(id 最大)挂批次级减免覆盖+过去基线，项目级更晚
            seedProxyAuditLog(cuihu);
            seedSeaReturnedCase(proj, provider);
            seedBatchReduceDrift(proj);
        }

        // 案件级 provider_id 回填（V913）：DevSeeder 经 SQL 直插案件、绕过 dispatch/accept 控制器，
        // 故 case.provider_id 留空；而 V913 回填在 Flyway 期(种子前)执行、看不到这些行。
        // 可见性 scope 已改为直接 c.provider_id 权威，须在此按池语义补齐，否则服务商/催收员看不到本商案件。
        // 语义：已归属某服务商的案件(已派待接 S1/服务商公海 S2/私海 S3)→ provider_id=batch.provider_id；
        //   平台公海(S0)与开放抢单池(S4)→ 无归属，保持 NULL。
        jdbc.update(
                "UPDATE \"case\" c SET provider_id = b.provider_id FROM batch b "
                        + "WHERE c.batch_id = b.id AND b.provider_id IS NOT NULL "
                        + "AND c.provider_id IS NULL AND c.pool NOT IN ('PLATFORM_SEA', 'OPEN_POOL')");
    }

    // ── M9-B 计费流水种子（recharge_log 充值/扣减流水）─────────────────────────
    //
    // 表 recharge_log 已在 V2__peripheral_and_audit.sql 建表（仅插数据，不建表）。
    //   recharge_log(org_id, type∈STT/SMS/EVIDENCE/LEGAL, delta（+充值/-扣减）, balance（操作后快照）,
    //                ref（关联单据号）, note, operated_by（平台操作员）, tm)
    // 复用 base 既有 org（翠湖物业 / 捷信服务商），operated_by=平台 SA。BR-M9-06a 对账演示。
    // 注：EVIDENCE/LEGAL 不预充（RechargeTypeEnum 仅 STT/SMS），但流水里可有 EVIDENCE/LEGAL 扣减
    //     （chk_recharge_type 允许四值）。
    // 全部幂等：本物业 org 已有 ref='RC-2025-001' 流水则整体跳过（首笔充值即哨兵）。

    /**
     * @param propertyOrg      物业组织 id（翠湖物业）——STT/SMS 充值与扣减归属
     * @param providerOrg      服务商组织 id（捷信催收）——STT 充值与扣减归属
     * @param operatorAccountId 平台操作员 account id（SA）——recharge_log.operated_by
     */
    private void seedM9Billing(Long propertyOrg, Long providerOrg, Long operatorAccountId) {
        if (propertyOrg == null || providerOrg == null || operatorAccountId == null) return;
        // 幂等哨兵：物业首笔 STT 充值 ref（RC-2025-001）已存在则整体跳过。
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM recharge_log WHERE org_id = ? AND ref = 'RC-2025-001'",
                Integer.class, propertyOrg);
        if (exists != null && exists > 0) return;

        // 1) 物业 STT 充值 +600.000（balance 快照 600.000）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'STT', 600.000, 600.000, 'RC-2025-001', '(演示)STT转写充值', ?)",
                propertyOrg, operatorAccountId);
        // 2) 物业 SMS 充值 +1000.000（balance 1000.000）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'SMS', 1000.000, 1000.000, 'RC-2025-002', '(演示)短信条数充值', ?)",
                propertyOrg, operatorAccountId);
        // 3) 服务商 STT 充值 +300.000（balance 300.000）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'STT', 300.000, 300.000, 'RC-2025-003', '(演示)服务商STT充值', ?)",
                providerOrg, operatorAccountId);
        // 4) 扣减各 1 笔（delta<0，对账演示 BR-M9-06a）：
        //    物业 STT 扣减 -120.000（用量 → balance 480.000）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'STT', -120.000, 480.000, NULL, '(演示)STT转写用量扣减', ?)",
                propertyOrg, operatorAccountId);
        //    物业 SMS 扣减 -3.000（发 3 条 → balance 997.000）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'SMS', -3.000, 997.000, NULL, '(演示)短信发送用量扣减', ?)",
                propertyOrg, operatorAccountId);
        //    物业 EVIDENCE 扣减 -2.000（出证 2 次 → 无预充, balance -2.000 表欠用记账）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'EVIDENCE', -2.000, -2.000, NULL, '(演示)存证出证用量扣减(不预充)', ?)",
                propertyOrg, operatorAccountId);
        //    物业 LEGAL 扣减 -1.000（律师函 1 件 → balance -1.000）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'LEGAL', -1.000, -1.000, NULL, '(演示)法律文书用量扣减(不预充)', ?)",
                propertyOrg, operatorAccountId);
        //    服务商 STT 扣减 -50.000（→ balance 250.000）
        jdbc.update("INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by) "
                        + "VALUES (?, 'STT', -50.000, 250.000, NULL, '(演示)服务商STT用量扣减', ?)",
                providerOrg, operatorAccountId);
    }

    // ── M9-B 短信发送流水种子（sms_record，挂 base 物业 org/project/case）────────
    //
    // 表 sms_record 由 V5__sms_record.sql 新建（契约 SmsSendRecord 落库）。
    //   sms_record(org_id（range 裁剪锚点）, case_id, project_id, template, status∈SENT/FAILED/DELIVERED,
    //              failure_reason, sent_at)
    // 复用 base 物业 org/project/case（不重建）。BR-M9-08：失败不退条数（FAILED 留 failure_reason）。
    // 全部幂等：本物业 org 已有任一 sms_record 则整体跳过。

    /**
     * @param propertyOrg 物业组织 id（翠湖物业）——sms_record.org_id（range 裁剪锚点）
     * @param projId      项目 id（翠湖一期）——sms_record.project_id
     * @param caseS3Id    案件 id（M3-S3-01，可空）——sms_record.case_id
     */
    private void seedSmsRecords(Long propertyOrg, Long projId, Long caseS3Id) {
        if (propertyOrg == null) return;
        // 幂等：本物业 org 已有任一短信流水则整体跳过。
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM sms_record WHERE org_id = ?", Integer.class, propertyOrg);
        if (exists != null && exists > 0) return;

        // 1) SENT 1 条（催缴提醒，2 天前发出）
        jdbc.update("INSERT INTO sms_record(org_id, case_id, project_id, template, status, sent_at) "
                        + "VALUES (?, ?, ?, '催缴提醒', 'SENT', now() - interval '2 days')",
                propertyOrg, caseS3Id, projId);
        // 2) DELIVERED 1 条（催缴提醒，1 天前送达）
        jdbc.update("INSERT INTO sms_record(org_id, case_id, project_id, template, status, sent_at) "
                        + "VALUES (?, ?, ?, '催缴提醒', 'DELIVERED', now() - interval '1 day')",
                propertyOrg, caseS3Id, projId);
        // 3) FAILED 1 条（号码空号，失败不退条数 BR-M9-08）
        jdbc.update("INSERT INTO sms_record(org_id, case_id, project_id, template, status, failure_reason, sent_at) "
                        + "VALUES (?, ?, ?, '催缴提醒', 'FAILED', '号码空号 BR-M9-08·失败不退条数', now())",
                propertyOrg, caseS3Id, projId);
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

        // M4 外围实体：给 S3 私海案件挂 录音/AI复盘/承诺/联系人/工单（+pay_link/reduction/repay_line）
        Long caseS3Id = jdbc.query(
                "SELECT id FROM \"case\" WHERE batch_id = ? AND acct_no = 'M3-S3-01'",
                rs -> rs.next() ? rs.getLong(1) : null, b2);
        if (caseS3Id != null) {
            seedM4Collection(caseS3Id, projId, b2, coHolder, providerOrg);
        }

        // M5 余额不足补解析（BR-M5-02）：另起 3 件 co1 私海案 M5-QB-0{1,2,3}，其最新录音均为 QUOTA_BLOCKED，
        // 供「补解析/批量补解析」按钮渲染（与 M3-S3-01 的 READY 录音物理隔离，避免污染 AI 复盘/标记用例）。
        // parse 桩实现会把 QUOTA_BLOCKED→PARSING（不可逆），e2e 三用例各自触发一次补解析故需各占一件，互不串味。
        for (int i = 1; i <= 3; i++) {
            String acct = "M5-QB-0" + i;
            ensureSeaCase(b2, projId, "翠湖一期", acct, "郑余额" + i, "QB-10" + i, 250000L,
                    "IN_PROGRESS", "PRIVATE", coHolder, "CLAIM", "PROVIDER_SEA", false, true /*tc*/);
            Long caseQbId = jdbc.query(
                    "SELECT id FROM \"case\" WHERE batch_id = ? AND acct_no = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, b2, acct);
            if (caseQbId != null) {
                seedQuotaBlockedRecording(caseQbId, coHolder);
            }
        }

        // M8 结案脱敏（BR-M8-09）：一件 co1 持有、已撤案(WITHDRAWN)的私海案 M8-RD-01。
        // 对非平台/非物业主体（VL/CO）→ redacted=true（业主名/电话脱敏、逐行明细收敛为统计卡）；
        // 平台(SA)同案仍见完整明细。挂 1 条联系人供脱敏前/后对照。provider_id 由收尾回填(pool=PRIVATE)。
        ensureSeaCase(b2, projId, "翠湖一期", "M8-RD-01", "韩结案", "RD-101", 200000L,
                "WITHDRAWN", "PRIVATE", coHolder, "CLAIM", "PROVIDER_SEA", false, false);
        Long caseRdId = jdbc.query(
                "SELECT id FROM \"case\" WHERE batch_id = ? AND acct_no = 'M8-RD-01'",
                rs -> rs.next() ? rs.getLong(1) : null, b2);
        if (caseRdId != null) {
            Integer rdContact = jdbc.queryForObject(
                    "SELECT count(*) FROM contact WHERE case_id = ?", Integer.class, caseRdId);
            if (rdContact == null || rdContact == 0) {
                jdbc.update(
                        "INSERT INTO contact(case_id, phone, label, is_primary, invalid) "
                                + "VALUES (?, '13900000077', '本人', TRUE, FALSE)",
                        caseRdId);
            }
        }
    }

    // ── M5 余额不足补解析录音种子（QUOTA_BLOCKED，供「补解析」按钮渲染）──────────────
    //
    // 给 co1 私海案 M5-QB-01 挂一条 QUOTA_BLOCKED 录音（无转写、无 AI 复盘——余额不足暂停态）。
    // latest 取 ORDER BY created_at DESC,id DESC → 本案唯一录音即 latest，按钮 v-if 命中。
    // 幂等：(case_id, source=APP_AUTO) 已存在则跳过。
    private void seedQuotaBlockedRecording(Long caseId, Long coHolder) {
        Long recId = jdbc.query(
                "SELECT id FROM call_recording WHERE case_id = ? AND source = 'APP_AUTO'",
                rs -> rs.next() ? rs.getLong(1) : null, caseId);
        if (recId != null) return;
        jdbc.update(
                "INSERT INTO call_recording(case_id, collector_id, source, status, recorded_at, "
                        + "duration_sec, phone) "
                        + "VALUES (?, ?, 'APP_AUTO', 'QUOTA_BLOCKED', now() - interval '2 hours', 95, '13900000088')",
                caseId, coHolder);
    }

    // ── M4 外围实体种子（私海案件 M3-S3-01 各挂 1 条，供 M4 GET 端点返 200）──────
    //
    // 全部幂等：先 SELECT count 判存在再插。覆盖：
    //   1) call_recording（READY，供 latest / recordings/{id} / listRecordings / ai-review）
    //   2) ai_review（result_mark=PROMISED，供 GET ai-review 200）
    //   3) promise + 2 promise_installment（供 GET /cases/{id}/promises）
    //   4) contact（供 详情 contacts / PATCH /contacts/{id}）
    //   5) ticket（PC 协调员一并种，使 listTickets range 裁剪命中）
    //   6) pay_link / reduction / repay_line（供 resend/void/reverse/approve 端点有目标 id）

    /**
     * @param caseS3Id 私海案件 id（M3-S3-01）
     * @param projId   案件所属项目 id（翠湖一期）
     * @param batchId  案件所属批次 id（B-CH-M3-S2）
     * @param coHolder 持有催收员 account id（jx_co1）
     * @param providerOrg 承接服务商 org id（捷信催收），用于 M5 质检风险归属
     */
    private void seedM4Collection(Long caseS3Id, Long projId, Long batchId, Long coHolder, Long providerOrg) {
        // 0) 翠湖物业 PC 协调员（关联本项目，使 listTickets 的 range 裁剪能命中物业侧）
        ensureCoordinator(projId, "cuihu_pc", "翠湖协调员", "13900000006");

        // 1) 录音 1 条（READY）。幂等键：(case_id, source=APP_AUTO)——本演示每案至多一条自动录音。
        Long recId = jdbc.query(
                "SELECT id FROM call_recording WHERE case_id = ? AND source = 'APP_AUTO'",
                rs -> rs.next() ? rs.getLong(1) : null, caseS3Id);
        if (recId == null) {
            recId = jdbc.queryForObject(
                    "INSERT INTO call_recording(case_id, collector_id, source, status, recorded_at, "
                            + "duration_sec, phone, transcript) "
                            + "VALUES (?, ?, 'APP_AUTO', 'READY', now() - interval '1 day', 180, '13900000099', ?) "
                            + "RETURNING id",
                    Long.class, caseS3Id, coHolder, "(演示)业主称下月缴清");
        }

        // 2) AI 复盘 1 条（uq_ai_review_call 唯一，幂等）
        Integer reviewExists = jdbc.queryForObject(
                "SELECT count(*) FROM ai_review WHERE call_id = ?", Integer.class, recId);
        if (reviewExists == null || reviewExists == 0) {
            jdbc.update(
                    "INSERT INTO ai_review(call_id, summary, dialogue, risks, suggestions, result_mark) "
                            + "VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, 'PROMISED')",
                    recId,
                    "业主有还款意愿，承诺下月",
                    "[{\"speaker\":\"催收员\",\"text\":\"您好，关于物业费的事...\"},"
                            + "{\"speaker\":\"业主\",\"text\":\"我下月一定缴清\"}]",
                    "[{\"level\":\"LOW\",\"desc\":\"无违规话术\",\"segmentTs\":\"00:30\"}]",
                    "[{\"id\":\"s1\",\"type\":\"SUGGEST\",\"title\":\"登记承诺\","
                            + "\"body\":\"建议登记下月承诺\",\"actionRef\":\"PROMISE\"}]");
        }

        // 3) 承诺 1 条（分期）+ 2 期明细
        Long promiseId = jdbc.query(
                "SELECT id FROM promise WHERE case_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, caseS3Id);
        if (promiseId == null) {
            promiseId = jdbc.queryForObject(
                    "INSERT INTO promise(case_id, date, amount_cents, state, created_by) "
                            + "VALUES (?, (now() + interval '30 days')::date, 260000, 'PENDING', ?) RETURNING id",
                    Long.class, caseS3Id, coHolder);
            jdbc.update(
                    "INSERT INTO promise_installment(promise_id, seq, due_date, amount_cents, state) "
                            + "VALUES (?, 1, (now() + interval '30 days')::date, 130000, 'PENDING')",
                    promiseId);
            jdbc.update(
                    "INSERT INTO promise_installment(promise_id, seq, due_date, amount_cents, state) "
                            + "VALUES (?, 2, (now() + interval '60 days')::date, 130000, 'PENDING')",
                    promiseId);
        }

        // 4) 联系人 1 条（本人，主要）
        Integer contactExists = jdbc.queryForObject(
                "SELECT count(*) FROM contact WHERE case_id = ?", Integer.class, caseS3Id);
        if (contactExists == null || contactExists == 0) {
            jdbc.update(
                    "INSERT INTO contact(case_id, phone, label, is_primary, invalid) "
                            + "VALUES (?, '13900000099', '本人', TRUE, FALSE)",
                    caseS3Id);
        }

        // 5) 工单 1 条（CO→PC，待处理）
        Integer ticketExists = jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE case_id = ?", Integer.class, caseS3Id);
        if (ticketExists == null || ticketExists == 0) {
            jdbc.update(
                    "INSERT INTO ticket(case_id, type, note, from_role, to_role, status, created_by) "
                            + "VALUES (?, '上门核实', '核实房屋是否出租', 'CO', 'PC', 'PENDING', ?)",
                    caseS3Id, coHolder);
        }

        // 6a) 缴费链接 1 条（ACTIVE，供 resend/void 端点有目标 id）
        Integer payLinkExists = jdbc.queryForObject(
                "SELECT count(*) FROM pay_link WHERE case_id = ?", Integer.class, caseS3Id);
        if (payLinkExists == null || payLinkExists == 0) {
            jdbc.update(
                    "INSERT INTO pay_link(case_id, token, amount_cents, expires_at, status, channel, created_by) "
                            + "VALUES (?, ?, 260000, now() + interval '7 days', 'ACTIVE', 'WECHAT_COPY', ?)",
                    caseS3Id, "demo-paylink-" + caseS3Id, coHolder);
        }

        // 6b) 减免 1 条（催收员自决，生效，供减免相关端点有目标 id）
        Integer reductionExists = jdbc.queryForObject(
                "SELECT count(*) FROM reduction WHERE case_id = ?", Integer.class, caseS3Id);
        if (reductionExists == null || reductionExists == 0) {
            jdbc.update(
                    "INSERT INTO reduction(case_id, tier_ref, discount, amount_cents, decide, state, applied_by, note) "
                            + "VALUES (?, 0, '9折', 26000, 'COLLECTOR_SELF', 'EFFECTIVE', ?, '(演示)自决档减免')",
                    caseS3Id, coHolder);
        }

        // 6c) 回款明细 1 条（微信收款，未结算，供 reverse/approve 端点有目标 id）
        Integer repayExists = jdbc.queryForObject(
                "SELECT count(*) FROM repay_line WHERE case_id = ?", Integer.class, caseS3Id);
        if (repayExists == null || repayExists == 0) {
            // B-03 到账归属快照（与 PayReduceRepayM4 登记口径一致）：
            //   provider_id_at_repay=COALESCE(case.provider_id,batch.provider_id)、collector_id_at_repay=持有催收员。
            jdbc.update(
                    "INSERT INTO repay_line(case_id, batch_id, amount_cents, channel, paid_at, marked_by, settled,"
                            + " provider_id_at_repay, collector_id_at_repay) "
                            + "VALUES (?, ?, 130000, 'WECHAT_QR', now()::date, ?, FALSE,"
                            + " (SELECT COALESCE(c.provider_id, b.provider_id) FROM \"case\" c"
                            + "   JOIN batch b ON b.id = c.batch_id WHERE c.id = ?), ?)",
                    caseS3Id, batchId, coHolder, caseS3Id, coHolder);
        }

        // 时间线：录音上传一条 activity（CALL，ref→call_recording），对齐 ERD ACTIVITY.ref 跳转
        Integer actExists = jdbc.queryForObject(
                "SELECT count(*) FROM activity WHERE case_id = ? AND type = 'CALL' AND ref_type = 'call_recording'",
                Integer.class, caseS3Id);
        if (actExists == null || actExists == 0) {
            jdbc.update(
                    "INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id, method) "
                            + "VALUES (?, 'CALL', ?, '通话录音上传', 'call_recording', ?, 'CALL')",
                    caseS3Id, coHolder, recId);
        }

        // 7) M5 质检风险 + 处置任务（违规人=jx_co1 催收员、责任组织=捷信服务商）
        seedM5Qc(caseS3Id, projId, recId, coHolder, providerOrg);

        // 8) M6 存证（evidence 2 条·哈希链派生不落库）+ 法律文书（legal_doc 1 条）
        seedM6Evidence(caseS3Id, projId, recId);

        // 9) M7 业主缴费视图字段：减免后应收 / 欠费周期 / 项目收款 JSON（pay_link 已于 6a 种，无需新种）
        seedM7Bill(caseS3Id, projId);
    }

    // ── M6 存证/法律文书种子（私海案件 M3-S3-01，供 GET /evidence·verify·legal-docs·deliver 返 200）──
    //
    // 表（V2__peripheral_and_audit.sql 冻结列）：
    //   evidence(org_id=物业组织, case_id, scene, ref_ids JSONB, status, cert_no, cert_url, issued_at, note, created_by)
    //   legal_doc(case_id, type, template_id, status, pdf_url, delivered_at, signed_photo_url, evidence_id, note, created_by)
    // 哈希链：evidence 表无 hash/prev_hash 列（schema 冻结）——哈希为派生值
    //   SHA-256(id|case_id|scene|cert_no|issued_at)，verify 端点读时实时计算并与前一条（id 升序前驱）
    //   派生哈希链接，不落库不改 schema。
    // 全部幂等：先 SELECT count(*) WHERE case_id=? 为 0 才插。

    /**
     * @param caseS3Id 私海案件 id（M3-S3-01）
     * @param projId   案件所属项目 id（翠湖一期）——派生物业 org（evidence.org_id 三方隔离）
     * @param recId    关联录音 id（第 1 条 RECORDING 存证 ref_ids=[recId]）
     */
    private void seedM6Evidence(Long caseS3Id, Long projId, Long recId) {
        // 物业组织（evidence.org_id）：案件项目所属物业组织（翠湖物业）。
        Long propertyOrg = jdbc.query("SELECT org_id FROM project WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, projId);
        if (propertyOrg == null) return;
        // 物业侧创建人（cuihu_pc 协调员，M4 已种）。
        Long cuihuPc = jdbc.query("SELECT id FROM account WHERE username = 'cuihu_pc'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (cuihuPc == null) return;

        // 1) evidence 2 条形成哈希链（按 id 升序前驱链接，派生哈希实时计算）。
        //    幂等：本案已有任一存证则整体跳过。
        Integer evidExists = jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE case_id = ?", Integer.class, caseS3Id);
        if (evidExists != null && evidExists == 0) {
            // 第 1 条：录音场景，ref_ids=[recId]，已出证。
            String ref1 = recId != null ? "[" + recId + "]" : "[]";
            jdbc.update(
                    "INSERT INTO evidence(org_id, case_id, scene, ref_ids, status, cert_no, cert_url, issued_at, created_by) "
                            + "VALUES (?, ?, 'RECORDING', ?::jsonb, 'ISSUED', ?, ?, now(), ?)",
                    propertyOrg, caseS3Id, ref1,
                    "YZ-EVID-" + caseS3Id + "-1",
                    "https://example.com/cert-" + caseS3Id + "-1.pdf",
                    cuihuPc);
            // 第 2 条：送达场景，已出证（供 legal_doc.evidence_id 关联）。
            jdbc.update(
                    "INSERT INTO evidence(org_id, case_id, scene, status, cert_no, cert_url, issued_at, created_by) "
                            + "VALUES (?, ?, 'DELIVERY', 'ISSUED', ?, ?, now(), ?)",
                    propertyOrg, caseS3Id,
                    "YZ-EVID-" + caseS3Id + "-2",
                    "https://example.com/cert-" + caseS3Id + "-2.pdf",
                    cuihuPc);
        }

        // 2) legal_doc 1 条（律师函·已签收，关联第 2 条 DELIVERY 存证）。
        //    幂等：本案已有任一法律文书则跳过。
        Integer ldExists = jdbc.queryForObject(
                "SELECT count(*) FROM legal_doc WHERE case_id = ?", Integer.class, caseS3Id);
        if (ldExists != null && ldExists == 0) {
            Long evid2 = jdbc.query(
                    "SELECT id FROM evidence WHERE case_id = ? AND scene = 'DELIVERY' ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null, caseS3Id);
            jdbc.update(
                    "INSERT INTO legal_doc(case_id, type, status, pdf_url, delivered_at, signed_photo_url, evidence_id, created_by) "
                            + "VALUES (?, 'LAWYER_LETTER', 'SIGNED', ?, now() - interval '1 day', ?, ?, ?)",
                    caseS3Id,
                    "https://example.com/legal-" + caseS3Id + ".pdf",
                    "https://example.com/sign-" + caseS3Id + ".jpg",
                    evid2, cuihuPc);
        }
    }

    // ── M7 业主缴费视图字段补种（私海案件 M3-S3-01）──────────────────────────────
    //
    // M7 缴费链接复用 M4 已种 pay_link（token='demo-paylink-{caseS3Id}'，ACTIVE，amount_cents=260000，
    // channel=WECHAT_COPY），无需新种——验证 GET /pay/demo-paylink-{caseS3Id} 返 200。
    // 此处仅补使 OwnerBill 字段非空：
    //   case.reduce_after_cents=234000（due 260000 − 减免 26000）、arrearags_periods=["2025-01","2025-02"]；
    //   project.pay_info（收款渠道 JSON {wechatQr,bankAccount}）若 V900 未种则补。
    // 幂等：仅在字段为 NULL/空 时 UPDATE。

    /**
     * @param caseS3Id 私海案件 id（M3-S3-01）
     * @param projId   案件所属项目 id（翠湖一期）
     */
    private void seedM7Bill(Long caseS3Id, Long projId) {
        // 减免后应收（due 260000 − 减免 26000 = 234000）。幂等：仅当 NULL 时写。
        jdbc.update(
                "UPDATE \"case\" SET reduce_after_cents = 234000 WHERE id = ? AND reduce_after_cents IS NULL",
                caseS3Id);
        // 欠费周期（arrearags_periods 默认 '[]'，空时补两期）。
        jdbc.update(
                "UPDATE \"case\" SET arrearags_periods = '[\"2025-01\",\"2025-02\"]'::jsonb "
                        + "WHERE id = ? AND (arrearags_periods IS NULL OR arrearags_periods = '[]'::jsonb)",
                caseS3Id);
        // 项目收款渠道 JSON（pay_info TEXT 存 JSON 串）。幂等：仅当 NULL/空 时写。
        jdbc.update(
                "UPDATE project SET pay_info = ? "
                        + "WHERE id = ? AND (pay_info IS NULL OR pay_info = '')",
                "{\"wechatQr\":\"https://example.com/wechat-qr.png\","
                        + "\"bankAccount\":\"6222 0000 0000 0000（翠湖物业·演示）\"}",
                projId);
    }

    // ── M5 质检/风控种子（私海案件 M3-S3-01 的录音挂风险，供组织侧读/处置/复核 + 平台监管视图）──
    //
    // 表 risk_record / dispose_task 已在 V1__core_schema.sql 建表（actual 列：
    //   risk_record(case_id, call_id, collector_id, provider_id=服务商org, property_id=物业org, type, level,
    //               segment_ts, reviewed, reviewed_by, reviewed_at)
    //   dispose_task(risk_id, provider=责任org, task_type, status, tm)）。
    // 违规人 jx_co1 是捷信服务商催收员 → provider_id=捷信、property_id=案件项目所属物业（翠湖物业）。
    // 全部幂等：risk_record 先 SELECT count(case_id)；dispose_task 先 SELECT id(risk_id)。

    /**
     * @param caseS3Id    私海案件 id（M3-S3-01）
     * @param projId      案件所属项目 id（翠湖一期）——用于派生物业 org（三方隔离 property_id）
     * @param recId       关联录音 id（call_id，BR-M5-11 回放取证）
     * @param coHolder    违规催收员 account id（jx_co1，契约 collector 展示名取此人）
     * @param providerOrg 责任服务商 org id（捷信催收）
     */
    private void seedM5Qc(Long caseS3Id, Long projId, Long recId, Long coHolder, Long providerOrg) {
        if (recId == null || providerOrg == null) return;
        // 物业 org（三方隔离 property_id）：案件项目所属物业组织（翠湖物业）。
        Long propertyOrg = jdbc.query("SELECT org_id FROM project WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, projId);
        if (propertyOrg == null) return;

        // A) risk_record 3 条（覆盖 HIGH/MID/LOW、不同 type/segment_ts、reviewed=NULL 待复核）
        //    幂等：本案已有任一风险则整体跳过。
        Integer riskExists = jdbc.queryForObject(
                "SELECT count(*) FROM risk_record WHERE case_id = ?", Integer.class, caseS3Id);
        if (riskExists != null && riskExists == 0) {
            jdbc.update(
                    "INSERT INTO risk_record(case_id, call_id, collector_id, provider_id, property_id, "
                            + "type, level, segment_ts, reviewed) "
                            + "VALUES (?, ?, ?, ?, ?, '辱骂威胁', 'HIGH', '01:12', NULL)",
                    caseS3Id, recId, coHolder, providerOrg, propertyOrg);
            jdbc.update(
                    "INSERT INTO risk_record(case_id, call_id, collector_id, provider_id, property_id, "
                            + "type, level, segment_ts, reviewed) "
                            + "VALUES (?, ?, ?, ?, ?, '违规承诺', 'MID', '02:05', NULL)",
                    caseS3Id, recId, coHolder, providerOrg, propertyOrg);
            jdbc.update(
                    "INSERT INTO risk_record(case_id, call_id, collector_id, provider_id, property_id, "
                            + "type, level, segment_ts, reviewed) "
                            + "VALUES (?, ?, ?, ?, ?, '用语不规范', 'LOW', '00:30', NULL)",
                    caseS3Id, recId, coHolder, providerOrg, propertyOrg);
        }

        // B) dispose_task 1 条（挂 HIGH 风险，供平台监管视图 listDisposeTasks 返 200；两侧不可见）。
        //    幂等：dispose_task.risk_id 无唯一约束，按 risk_id 先查后插兜底。
        Long highRiskId = jdbc.query(
                "SELECT id FROM risk_record WHERE case_id = ? AND level = 'HIGH' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, caseS3Id);
        if (highRiskId != null) {
            // 演示「平台已复核 → 生成处置任务」闭环：HIGH 置 CONFIRMED + reviewed_by=平台 SA。
            Long saAcct = jdbc.query(
                    "SELECT id FROM account WHERE role_template = 'SA' ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null);
            jdbc.update("UPDATE risk_record SET reviewed = 'CONFIRMED', reviewed_by = ?, reviewed_at = now() "
                            + "WHERE id = ? AND reviewed IS NULL",
                    saAcct, highRiskId);

            Long taskId = jdbc.query("SELECT id FROM dispose_task WHERE risk_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, highRiskId);
            if (taskId == null) {
                jdbc.update(
                        "INSERT INTO dispose_task(risk_id, provider, task_type, status) "
                                + "VALUES (?, ?, '整改培训', 'PENDING')",
                        highRiskId, providerOrg);
            }
        }
    }

    // ── M9 结算·支付申请单种子（资金双线 OUT·催收员佣金）──────────────────────
    //
    // 复用 M2 演示批次 B-CH-2026-01（comm_in_rate=0.30 / pay_out_rate=0.20，已设防倒挂可校验）
    // 与三案件 C-1001/1002/1003。全部幂等（先 SELECT count 判存在再插）。物理隔离 M2/M3/M4 种子。
    //
    // 种：
    //   1) 未结回款明细 repay_line（settled=FALSE, payment_request_id=NULL, reversed=FALSE）：
    //      C-1001 一笔 360000、C-1002 一笔 480000、C-1003 一笔 120000（对齐 due_cents），
    //      marked_by=协调员（cuihu_pc），供 listBatchRepayLines/createPaymentRequest 有可勾选目标。
    //   2) co_commission：catch coHolder(jx_co1) 在本批 rate=0.15（≤ pay_out_rate 0.20·合法不倒挂）。
    //      （触发 BIZ_PAYOUT_INVERT 走 PUT rate=0.25 端点动态触发，不种非法数据。）
    //   3) PENDING 支付申请单（side=OUT, generated_by=jx_vl, comm_rate=0.20 固化 pay_out_rate），
    //      勾选 C-1001 的明细→lines JSONB 快照 [{lineId,caseId,ownerName,room,repayCents,commCents}]，
    //      base_cents=360000、comm_cents=360000×0.20=72000、status=PENDING、version=1，
    //      并 UPDATE 该 repay_line.payment_request_id=新单 id（settled 保持 FALSE，PENDING 占位锁定）。
    //   4) 一张已 PAID 单 + voucher（type=PAYMENT 对应 OUT 线）：勾选 C-1003 明细，展示终态，
    //      该 repay_line.settled=TRUE、payment_request_id=该 PAID 单。
    //   5) co_pay_doc（PENDING_PAY, collector=jx_co1, line_ids=[C-1002 明细], amount=480000×0.15=72000）
    //      + co_pay_doc_line 关联行，供 confirm-pay 端点有目标 id。

    /**
     * @param batchId     M2 演示批次 id（B-CH-2026-01，pay_out_rate=0.20）
     * @param projId      批次所属项目 id（翠湖一期）
     * @param providerOrg 承接服务商 org id（捷信催收）
     * @param coHolder    催收员 account id（jx_co1）
     */
    private void seedM9Settlement(Long batchId, Long projId, Long providerOrg, Long coHolder) {
        // 资金口径：pay_out_rate=0.20（OUT 线固化比率）；催收员内部 rate=0.15。
        final java.math.BigDecimal payOutRate = new java.math.BigDecimal("0.2000");
        final java.math.BigDecimal coRate = new java.math.BigDecimal("0.1500");

        // OUT 付佣单可见性按 batch.provider_id(承接服务商)裁剪 → 本批须挂承接服务商，否则服务商看不到自己的付佣单。
        jdbc.update("UPDATE batch SET provider_id = ? WHERE id = ? AND provider_id IS NULL", providerOrg, batchId);

        // 协调员（marked_by）。M4 已为 S3 案件种过 cuihu_pc，幂等返回既有账号。
        Long pc = ensureCoordinator(projId, "cuihu_pc", "翠湖协调员", "13900000006");
        if (pc == null) return;

        // 三案件 id（M2 演示批次下 C-1001/1002/1003）
        Long c1 = caseIdByAcct(batchId, "C-1001");
        Long c2 = caseIdByAcct(batchId, "C-1002");
        Long c3 = caseIdByAcct(batchId, "C-1003");
        if (c1 == null || c2 == null || c3 == null) return;

        // 1) 未结回款明细各一笔（幂等键：同案在本批已有任一明细则跳过）
        Long line1 = ensureRepayLine(c1, batchId, 360000L, pc); // 入 PENDING 单
        Long line2 = ensureRepayLine(c2, batchId, 480000L, pc); // 入 co_pay_doc
        Long line3 = ensureRepayLine(c3, batchId, 120000L, pc); // 入 PAID 单
        if (line1 == null || line2 == null || line3 == null) return;

        // 2) 催收员佣金比例 0.15（≤ 0.20 合法）。幂等：uq_co_comm_coll_batch。
        Integer ccExists = jdbc.queryForObject(
                "SELECT count(*) FROM co_commission WHERE collector_id = ? AND batch_id = ?",
                Integer.class, coHolder, batchId);
        if (ccExists == null || ccExists == 0) {
            jdbc.update("INSERT INTO co_commission(collector_id, batch_id, rate) VALUES (?, ?, ?::numeric)",
                    coHolder, batchId, coRate.toPlainString());
        }

        // OUT 线生成方=服务商负责人（jx_vl）。绝不接受前端传入，此处种子等价服务端派生。
        Long vlAcct = jdbc.query("SELECT id FROM account WHERE username = 'jx_vl'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (vlAcct == null) return;

        // 3) PENDING 支付申请单（side=OUT），勾选 line1（C-1001 / 360000）
        String prPendingNo = "PR-OUT-" + batchId + "-1";
        Long prPending = jdbc.query("SELECT id FROM payment_request WHERE no = ?",
                rs -> rs.next() ? rs.getLong(1) : null, prPendingNo);
        if (prPending == null) {
            long comm1 = com.youzheng.huicui.common.Commission.lineCommissionCents(360000L, payOutRate); // 72000
            String lines1 = "[{\"lineId\":" + line1 + ",\"caseId\":" + c1
                    + ",\"ownerName\":\"张三\",\"room\":\"1-101\",\"repayCents\":360000,\"commCents\":" + comm1 + "}]";
            prPending = jdbc.queryForObject(
                    "INSERT INTO payment_request(no, side, batch_id, generated_by, comm_rate, lines, "
                            + "base_cents, comm_cents, status, version) "
                            + "VALUES (?, 'OUT', ?, ?, ?::numeric, ?::jsonb, 360000, ?, 'PENDING', 1) RETURNING id",
                    Long.class, prPendingNo, batchId, vlAcct, payOutRate.toPlainString(), lines1, comm1);
            // 占位锁定：settled 保持 FALSE，仅写 payment_request_id（PAID 才置 settled=TRUE）
            jdbc.update("UPDATE repay_line SET payment_request_id = ? WHERE id = ? AND payment_request_id IS NULL",
                    prPending, line1);
            jdbc.update("INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id) "
                            + "VALUES (?, 'OPLOG', ?, '生成付佣支付申请单(PENDING)', 'payment_request', ?)",
                    c1, vlAcct, prPending);
        }

        // 4) 已 PAID 支付申请单（side=OUT）+ voucher（type=PAYMENT），勾选 line3（C-1003 / 120000）
        String prPaidNo = "PR-OUT-" + batchId + "-2";
        Long prPaid = jdbc.query("SELECT id FROM payment_request WHERE no = ?",
                rs -> rs.next() ? rs.getLong(1) : null, prPaidNo);
        if (prPaid == null) {
            long comm3 = com.youzheng.huicui.common.Commission.lineCommissionCents(120000L, payOutRate); // 24000
            String lines3 = "[{\"lineId\":" + line3 + ",\"caseId\":" + c3
                    + ",\"ownerName\":\"王五\",\"room\":\"3-303\",\"repayCents\":120000,\"commCents\":" + comm3 + "}]";
            prPaid = jdbc.queryForObject(
                    "INSERT INTO payment_request(no, side, batch_id, generated_by, comm_rate, lines, "
                            + "base_cents, comm_cents, status, completed_by, completed_at, version) "
                            + "VALUES (?, 'OUT', ?, ?, ?::numeric, ?::jsonb, 120000, ?, 'PAID', ?, now(), 2) RETURNING id",
                    Long.class, prPaidNo, batchId, vlAcct, payOutRate.toPlainString(), lines3, comm3, vlAcct);
            // 锁定明细：PAID → settled=TRUE + 绑定单
            jdbc.update("UPDATE repay_line SET payment_request_id = ?, settled = TRUE WHERE id = ?", prPaid, line3);
            // 凭证（OUT 线=PAYMENT 支付凭证）。uq_voucher_payment_request 每单一张，幂等。
            jdbc.update("INSERT INTO voucher(payment_request_id, type, file_url, uploaded_by) "
                            + "VALUES (?, 'PAYMENT', ?, ?)",
                    prPaid, "https://example.com/placeholder-voucher.pdf", vlAcct);
            jdbc.update("INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id) "
                            + "VALUES (?, 'OPLOG', ?, '完成付佣支付申请单(PAID·留痕凭证)', 'payment_request', ?)",
                    c3, vlAcct, prPaid);
        }

        // 5) co_pay_doc（PENDING_PAY）：催收员内部结算占位，勾选 line2（C-1002 / 480000）
        //    amount = 480000 × 0.15 = 72000。内部已结以 co_pay_doc.status=SETTLED 判定，不污染平台 settled。
        Integer cpdExists = jdbc.queryForObject(
                "SELECT count(*) FROM co_pay_doc WHERE collector_id = ? AND status = 'PENDING_PAY' "
                        + "AND line_ids @> ?::jsonb", Integer.class, coHolder, "[" + line2 + "]");
        if (cpdExists == null || cpdExists == 0) {
            long coAmount = com.youzheng.huicui.common.Commission.lineCommissionCents(480000L, coRate); // 72000
            Long cpd = jdbc.queryForObject(
                    "INSERT INTO co_pay_doc(collector_id, line_ids, count, amount_cents, status) "
                            + "VALUES (?, ?::jsonb, 1, ?, 'PENDING_PAY') RETURNING id",
                    Long.class, coHolder, "[" + line2 + "]", coAmount);
            // B-05 组单时点明细快照（与 CoCommissionM9.createCoPayDoc 落快照口径一致）。
            jdbc.update(
                    "INSERT INTO co_pay_doc_line(co_pay_doc_id, repay_line_id,"
                            + " case_id, room, owner_name, repay_cents, rate, comm_cents)"
                            + " SELECT ?, rl.id, rl.case_id, c.room, c.owner_name, rl.amount_cents,"
                            + "        ?::numeric, ?"
                            + " FROM repay_line rl JOIN \"case\" c ON c.id = rl.case_id WHERE rl.id = ?",
                    cpd, coRate.toPlainString(), coAmount, line2);
        }
    }

    // ── M10 组织监管动作种子（VL→CO，供 own-org 监管视图与处置端点有目标）──────────
    //
    // 表 supervision_action（V4__supervision_action.sql，物理隔离）。
    // 给服务商负责人（操作人）对本组织催收员（被督导成员）种 1 条 TRAINING：
    //   org_id   = 服务商组织（own-org 裁剪键）
    //   member_id= 被督导催收员 account.id
    //   operator_id = 负责人 account.id
    // → GET /members/supervision（VL/own-org）返 ≥1 条；POST /members/{co}/supervision-actions 有目标成员。
    // 幂等：本组织对该成员已有任一监管动作则跳过。

    /**
     * @param org              被督导成员所属组织 id（捷信催收）
     * @param operatorUsername 操作人=组织负责人用户名（jx_vl）
     * @param memberUsername   被督导成员用户名（jx_co1）
     */
    private void seedM10Supervision(Long org, String operatorUsername, String memberUsername) {
        if (org == null) return;
        Long operatorId = accountIdByUsername(operatorUsername);
        Long memberId = accountIdByUsername(memberUsername);
        if (operatorId == null || memberId == null) return;
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM supervision_action WHERE org_id = ? AND member_id = ?",
                Integer.class, org, memberId);
        if (exists != null && exists > 0) return;
        jdbc.update(
                "INSERT INTO supervision_action(org_id, member_id, action, note, operator_id) "
                        + "VALUES (?, ?, 'TRAINING', '(演示)安排话术规范培训', ?)",
                org, memberId, operatorId);
    }

    // ── e2e 转真断言种子（audit 代操作 / 公海退回案 / 批次减免 drift）─────────────

    /**
     * 代操作审计（audit-log.spec.ts:21）：种 1 条 proxy_for 非空记录——平台 SA 代某物业组织操作，
     * 含 before/after 快照 → 前端「代操作」标签(row.proxyFor 非空)+展开行(before/after)可断言。
     * actor=平台 SA（actor_id 指向 SA 账号、scope=PLATFORM、SA 全量可见）；proxy_for=被代操作物业组织名。
     * 幂等：本组织已有任一 proxy 审计（action='case.follow' 且 proxy_for 非空）则跳过。
     *
     * @param propertyOrg 被代操作物业组织 id（翠湖物业）——proxy_for 取其组织名
     */
    private void seedProxyAuditLog(Long propertyOrg) {
        if (propertyOrg == null) return;
        Long saAcct = jdbc.query("SELECT id FROM account WHERE role_template = 'SA' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        String saName = jdbc.query("SELECT name FROM account WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, saAcct);
        String orgName = jdbc.query("SELECT name FROM org WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, propertyOrg);
        if (saAcct == null || orgName == null) return;
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_id = ? AND proxy_for IS NOT NULL "
                        + "AND action = 'case.follow'",
                Integer.class, saAcct);
        if (exists != null && exists > 0) return;
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope, "
                        + "proxy_for, before_snap, after_snap, reason) "
                        + "VALUES (?, ?, 'case.follow', '案件跟进(代操作)', 'case', '1', 'PLATFORM', ?, "
                        + "?::jsonb, ?::jsonb, '(演示)平台代物业登记跟进')",
                saAcct, saName != null ? saName : "平台超管", orgName,
                "{\"followState\":\"NONE\",\"note\":null}",
                "{\"followState\":\"CONTACTED\",\"note\":\"业主承诺下月缴清\"}");
    }

    /**
     * 平台公海"被退回"案 + case.return 审计（sea-redispatch.spec.ts:19/:37 护栏①）。
     * 在 S0 批次(B-CH-M3-S0)下另起一件 S0 案(M3-RET-01，pool=PLATFORM_SEA,status=PENDING_DISPATCH)，
     * 并补一条 action='case.return' 审计、before_snap->>'providerId'=捷信 org id——
     * 使再派护栏 lastReturnedProvider() 命中：再选捷信被 409 拒(:37)、选其它服务商成功(:19)。
     * 幂等：案件经 ensureSeaCase(按 batch+acct 唯一)；审计按 (target_type,target_id,action) 先查后插。
     *
     * @param projId      项目 id（翠湖一期）
     * @param providerOrg 原退回服务商 org id（捷信催收）——写入 before_snap.providerId
     */
    private void seedSeaReturnedCase(Long projId, Long providerOrg) {
        if (projId == null || providerOrg == null) return;
        Long b0 = jdbc.query("SELECT id FROM batch WHERE project_id = ? AND no = 'B-CH-M3-S0'",
                rs -> rs.next() ? rs.getLong(1) : null, projId);
        if (b0 == null) return;
        ensureSeaCase(b0, projId, "翠湖一期", "M3-RET-01", "退回案", "RET-101", 280000L,
                "PENDING_DISPATCH", "PLATFORM_SEA", null, "DISPATCH", "PROVIDER_SEA", false, false);
        Long caseId = jdbc.query(
                "SELECT id FROM \"case\" WHERE batch_id = ? AND acct_no = 'M3-RET-01'",
                rs -> rs.next() ? rs.getLong(1) : null, b0);
        if (caseId == null) return;
        // case.return 审计：before_snap.providerId=原退回服务商（捷信）→ 再派护栏据此禁原退回方。
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_type = 'case' AND target_id = ? "
                        + "AND action = 'case.return'",
                Integer.class, String.valueOf(caseId));
        if (exists != null && exists > 0) return;
        Long vlAcct = jdbc.query("SELECT id FROM account WHERE username = 'jx_vl'",
                rs -> rs.next() ? rs.getLong(1) : null);
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope, "
                        + "before_snap, after_snap, reason) "
                        + "VALUES (?, ?, 'case.return', '案件退回平台公海', 'case', ?, 'PROVIDER', "
                        + "?::jsonb, ?::jsonb, '(演示)服务商退回—护栏①禁原退回方')",
                vlAcct, "捷信负责人", String.valueOf(caseId),
                "{\"providerId\":\"" + providerOrg + "\",\"pool\":\"PROVIDER_SEA\"}",
                "{\"providerId\":null,\"pool\":\"PLATFORM_SEA\"}");
    }

    /**
     * 批次级减免覆盖 drift（batch-sync-drift.spec.ts:18/:29）。
     * e2e 进 /batches 取首行(ORDER BY b.id DESC → 翠湖 id 最大批次)，需该批 getBatch.reduceDrift=true：
     *  (a) 项目级减免阶梯(batch_id IS NULL)至少一行——比对基线来源；
     *  (b) 该批次级覆盖行(batch_id=首批次)，baseline_project_updated_at=过去时刻；
     *  (c) 项目级行 updated_at 更晚 → 项目级 max(updated_at) > 基线 → reduceDrift=true。
     * 幂等：首批次已有任一覆盖行则整体跳过（:29 一键同步会删覆盖行，重启后重新种回）。
     *
     * @param projId 项目 id（翠湖一期）
     */
    private void seedBatchReduceDrift(Long projId) {
        if (projId == null) return;
        // e2e 首批次：翠湖项目下 id 最大批次（与 BatchesM2Controller ORDER BY b.id DESC 对齐）。
        Long firstBatch = jdbc.query(
                "SELECT id FROM batch WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, projId);
        if (firstBatch == null) return;
        // 幂等：首批次已有覆盖行则跳过。
        Integer ovExists = jdbc.queryForObject(
                "SELECT count(*) FROM reduce_tier WHERE batch_id = ?", Integer.class, firstBatch);
        if (ovExists != null && ovExists > 0) return;

        // (a) 项目级减免阶梯：若无则补一行（项目级 max(updated_at) 作 drift 比对源）。
        Integer projTierExists = jdbc.queryForObject(
                "SELECT count(*) FROM reduce_tier WHERE project_id = ? AND batch_id IS NULL",
                Integer.class, projId);
        if (projTierExists == null || projTierExists == 0) {
            jdbc.update(
                    "INSERT INTO reduce_tier(project_id, batch_id, discount, cap_cents, waive_penalty, decide) "
                            + "VALUES (?, NULL, '9折', 50000, FALSE, 'COLLECTOR_SELF')",
                    projId);
        }
        // (c) 确保项目级行 updated_at 为"现在"（晚于下面覆盖行写入的过去基线）。
        jdbc.update(
                "UPDATE reduce_tier SET updated_at = now() WHERE project_id = ? AND batch_id IS NULL",
                projId);

        // (b) 批次级覆盖行：baseline_project_updated_at=1 天前（早于项目级当前 max(updated_at)→ drift）。
        jdbc.update(
                "INSERT INTO reduce_tier(project_id, batch_id, discount, cap_cents, waive_penalty, decide, "
                        + "baseline_project_updated_at) "
                        + "VALUES (?, ?, '8折', 30000, FALSE, 'COLLECTOR_SELF', now() - interval '1 day')",
                projId, firstBatch);
    }

    /** 按 username 取 account.id（不存在返 null）。 */
    private Long accountIdByUsername(String username) {
        return jdbc.query("SELECT id FROM account WHERE username = ?",
                rs -> rs.next() ? rs.getLong(1) : null, username);
    }

    /** 取 M2 演示批次下某户号案件 id。 */
    private Long caseIdByAcct(Long batchId, String acctNo) {
        return jdbc.query("SELECT id FROM \"case\" WHERE batch_id = ? AND acct_no = ?",
                rs -> rs.next() ? rs.getLong(1) : null, batchId, acctNo);
    }

    /**
     * 未结回款明细幂等：同案在本批已有任一明细则返回既有 id（避免重复种）。
     * 新种：settled=FALSE, payment_request_id=NULL（默认）, reversed=FALSE（默认）, channel=WECHAT_QR。
     */
    private Long ensureRepayLine(Long caseId, Long batchId, long amountCents, Long markedBy) {
        Long existing = jdbc.query(
                "SELECT id FROM repay_line WHERE case_id = ? AND batch_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, caseId, batchId);
        if (existing != null) return existing;
        // B-03 到账归属快照（与 PayReduceRepayM4 登记口径一致）：
        //   provider_id_at_repay=COALESCE(case.provider_id,batch.provider_id)、collector_id_at_repay=case.holder_id。
        return jdbc.queryForObject(
                "INSERT INTO repay_line(case_id, batch_id, amount_cents, channel, paid_at, marked_by, settled,"
                        + " provider_id_at_repay, collector_id_at_repay) "
                        + "VALUES (?, ?, ?, 'WECHAT_QR', now()::date, ?, FALSE,"
                        + " (SELECT COALESCE(c.provider_id, b.provider_id) FROM \"case\" c"
                        + "   JOIN batch b ON b.id = c.batch_id WHERE c.id = ?),"
                        + " (SELECT holder_id FROM \"case\" WHERE id = ?)) RETURNING id",
                Long.class, caseId, batchId, amountCents, markedBy, caseId, caseId);
    }

    /** 物业协调员账号（role_template=PC，非负责人）。绑到指定项目的物业组织下。返回 account.id。 */
    private Long ensureCoordinator(Long projId, String username, String name, String phone) {
        Long aid = jdbc.query("SELECT id FROM account WHERE username = ?",
                rs -> rs.next() ? rs.getLong(1) : null, username);
        if (aid == null) {
            Long propOrgId = jdbc.query("SELECT org_id FROM project WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, projId);
            if (propOrgId == null) return null;
            String hash = bcrypt.encode(devPassword);
            aid = jdbc.queryForObject(
                    "INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner, password_hash) "
                            + "VALUES (?, ?, ?, ?, 'PC', 'ACTIVE', FALSE, ?) RETURNING id",
                    Long.class, propOrgId, username, name, phone, hash);
        }
        // B-02 行级隔离：PC 仅见协调的项目/批次。把协调员挂到本项目（幂等），否则收紧后看不到测试案件。
        ensureProjectCoordinator(projId, aid);
        return aid;
    }

    /** 幂等挂载 项目↔协调员（project_coordinators，PK=(project_id,coordinator_id)）。供 B-02 PC 行级可见。 */
    private void ensureProjectCoordinator(Long projId, Long coordinatorId) {
        if (projId == null || coordinatorId == null) return;
        jdbc.update(
                "INSERT INTO project_coordinators(project_id, coordinator_id) VALUES (?, ?)"
                        + " ON CONFLICT (project_id, coordinator_id) DO NOTHING",
                projId, coordinatorId);
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
    /** 一号多账号(BR-M1-11)：同手机 13900009000 下 翠湖物业PC + 捷信催收CO 两账号。口令均 dev。幂等。 */
    private void seedMultiAccount(Long cuihuOrg, Long providerOrg, String hash) {
        String phone = "13900009000";
        ensureAccountWithRole(cuihuOrg, "duo_pc", "多账号·翠湖协调员", phone, "PC", hash);
        ensureAccountWithRole(providerOrg, "duo_co", "多账号·捷信催收员", phone, "CO", hash);
    }
    private void ensureAccountWithRole(Long orgId, String username, String name, String phone, String role, String hash) {
        Long aid = jdbc.query("SELECT id FROM account WHERE username = ?", rs -> rs.next() ? rs.getLong(1) : null, username);
        if (aid != null) return;
        jdbc.update("INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner, password_hash)"
                + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', FALSE, ?)", orgId, username, name, phone, role, hash);
    }

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
    /** TIMERS 配置（已定稿）：T1=48h / T2=7天 / TC=7天 / MAXCYCLE=90天 + 预警提前量 6h/24h/24h/7天。幂等。 */
    private void ensureTimersSettings() {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM settings WHERE domain = 'TIMERS'", Integer.class);
        if (exists != null && exists > 0) return;
        Long sa = jdbc.query("SELECT id FROM account WHERE role_template = 'SA' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (sa == null) return;
        String timers = "{\"t1Seconds\":172800,\"t2Seconds\":604800,\"tcSeconds\":604800,\"maxCycleSeconds\":7776000,"
                + "\"t1WarnSeconds\":21600,\"t2WarnSeconds\":86400,\"tcWarnSeconds\":86400,\"maxCycleWarnSeconds\":604800}";
        jdbc.update("INSERT INTO settings(domain, version, timers, updated_by) VALUES ('TIMERS', 1, ?::jsonb, ?)", timers, sa);
    }

    /**
     * MARK_CODES 配置（CFG-MARK-CODES 内置五码，结构对齐契约 Settings.markCodes，列存裸数组）。
     * 顺序与 RecordingService 回退集一致：前四接通有效(effectiveFollowUp=true)、NO_ANSWER 未接通无效。
     * 幂等：MARK_CODES 域已有任一版本则跳过（settings.spec 增版后不回退覆盖，仅保证首版有内置五码）。
     */
    private void ensureMarkCodesSettings() {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM settings WHERE domain = 'MARK_CODES'", Integer.class);
        if (exists != null && exists > 0) return;
        Long sa = jdbc.query("SELECT id FROM account WHERE role_template = 'SA' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (sa == null) return;
        String markCodes = "["
                + "{\"code\":\"PROMISED\",\"label\":\"已承诺\",\"enabled\":true,\"connected\":true,\"effectiveFollowUp\":true},"
                + "{\"code\":\"REFUSED\",\"label\":\"明确拒缴\",\"enabled\":true,\"connected\":true,\"effectiveFollowUp\":true},"
                + "{\"code\":\"NEED_TICKET\",\"label\":\"需转工单\",\"enabled\":true,\"connected\":true,\"effectiveFollowUp\":true},"
                + "{\"code\":\"FOLLOW_UP\",\"label\":\"待跟进\",\"enabled\":true,\"connected\":true,\"effectiveFollowUp\":true},"
                + "{\"code\":\"NO_ANSWER\",\"label\":\"未接通\",\"enabled\":true,\"connected\":false,\"effectiveFollowUp\":false}"
                + "]";
        jdbc.update("INSERT INTO settings(domain, version, mark_codes, updated_by) VALUES ('MARK_CODES', 1, ?::jsonb, ?)",
                markCodes, sa);
    }

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
