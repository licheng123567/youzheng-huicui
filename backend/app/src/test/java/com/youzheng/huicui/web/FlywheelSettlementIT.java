package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.audit.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 话术飞轮定时结算（{@link FlywheelScheduler} + {@link FlywheelService}）。
 *
 * <p>上了定时器之后，这段代码就会<b>在没人看着的时候自己改话术库</b> —— 每天凌晨 04:00，
 * 无人值守地重算统计、并把达标的 AI 变体升成"现行话术"推给所有催收员去照着说。
 * 所以真正要守的不是"它跑没跑"，而是<b>它自己跑的时候会不会越界</b>：
 *
 * <ul>
 *   <li><b>不许抹平专家录入的种子</b> —— 只重算有归因（promise.script_id）的话术。
 *       一旦把无归因的话术也 UPDATE 一遍，专家精心录入的待积累条目会被清零成 uses=0。</li>
 *   <li><b>专家话术永不自动晋升</b> —— 无人值守的任务只准动 AI 挖掘出来的候选变体。
 *       这是"人始终在环上"的边界：机器可以自己提拔机器写的话术，不能自己提拔人写的。</li>
 *   <li><b>幂等</b> —— 定时器会日复一日地重复跑，跑两遍和跑一遍必须完全一致。</li>
 *   <li><b>开关真的关得掉</b> —— auto=false 时定时器一个字节都不许改。</li>
 * </ul>
 */
class FlywheelSettlementIT {

    private static final String URL = System.getenv().getOrDefault("HUICUI_IT_JDBC_URL", "");
    private static final String USER = System.getenv().getOrDefault("HUICUI_IT_JDBC_USER", "huicui");
    private static final String PASS = System.getenv().getOrDefault("HUICUI_IT_JDBC_PASSWORD", "Str0ngProdPw");

    private static JdbcTemplate jdbc;
    private static FlywheelService svc;
    private static long caseId;
    private static long accId;

    @BeforeAll
    static void setup() {
        assumeTrue(!URL.isBlank(), "未设置 HUICUI_IT_JDBC_URL，跳过飞轮结算 IT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(URL);
        pg.setUser(USER);
        pg.setPassword(PASS);
        DataSource ds = pg;
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
        svc = new FlywheelService(jdbc, new AuditService(jdbc, new ObjectMapper()));

        long provOrg = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROVIDER','飞轮测试商','ACTIVE') RETURNING id", Long.class);
        accId = jdbc.queryForObject(
                "INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)"
                        + " VALUES (?,?,'催收员','13900000009','CO','ACTIVE',false) RETURNING id",
                Long.class, provOrg, "co_fw_" + System.nanoTime());
        long propOrg = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROPERTY','飞轮物业','ACTIVE') RETURNING id", Long.class);
        String pname = "飞轮小区" + System.nanoTime();
        long projectId = jdbc.queryForObject(
                "INSERT INTO project(org_id,name,org_name,status,area,comm_in_rate)"
                        + " VALUES (?,?,'飞轮物业','ACTIVE','区',0.30) RETURNING id",
                Long.class, propOrg, pname);
        long batchId = jdbc.queryForObject(
                "INSERT INTO batch(project_id,no,status,comm_in_rate) VALUES (?,?,'DISPATCHED',0.30) RETURNING id",
                Long.class, projectId, "BFW-" + System.nanoTime());
        caseId = jdbc.queryForObject(
                "INSERT INTO \"case\"(batch_id,project_id,project_name,acct_no,owner_name,room,due_cents,status,pool,holder_id)"
                        + " VALUES (?,?,?,?,'张三','1-101',500000,'IN_PROGRESS','PROVIDER_SEA',?) RETURNING id",
                Long.class, batchId, projectId, pname, "AFW-" + System.nanoTime(), accId);

        // 把晋升阈值调到测试量级（默认 uses≥300 得造 300 条承诺）。parseTrigger 读 domain='AI' 最高 version。
        // ON CONFLICT：settings(domain,version) 唯一。不写它，这个 IT 只能在全新库上跑一次
        // —— 而本地反复跑同一个库是常态（第二次就 DuplicateKey 挂在 setup 里，跟被测代码毫无关系）。
        jdbc.update("INSERT INTO settings(domain,version,value,updated_by)"
                        + " VALUES ('AI', 999, '{\"flywheel\":{\"trigger\":\"uses>=2 AND wilson_uplift>=0.02\"}}'::jsonb, ?)"
                        + " ON CONFLICT (domain, version) DO UPDATE SET value = EXCLUDED.value",
                accId);
    }

    /** 有归因的话术按真实战果回填；<b>无归因的专家种子一个字段都不许动</b>。 */
    @Test
    void 只重算有归因的话术_不抹平专家种子() {
        long attributed = insertScript("EXPERT", "CANDIDATE", null, 0);
        // 专家录入、尚未在战场上用过（无 promise 归因），但录入时预置了统计
        long untouched = insertScript("EXPERT", "CANDIDATE", null, 77);

        insertPromise(attributed, "FULFILLED");
        insertPromise(attributed, "BROKEN");

        svc.recomputeAll(null);

        assertThat(uses(attributed)).as("2 条承诺归因 → uses=2").isEqualTo(2);
        assertThat(promiseRate(attributed)).as("1/2 兑现 → 0.5000").isEqualTo(0.5);
        assertThat(uses(untouched))
                .as("无归因的专家话术必须原样保留 —— 否则每天凌晨的定时器会把专家录入的条目清零")
                .isEqualTo(77);
    }

    /** 定时器天天跑：跑两遍必须和跑一遍一模一样。 */
    @Test
    void 重复结算幂等() {
        long sid = insertScript("EXPERT", "CANDIDATE", null, 0);
        insertPromise(sid, "FULFILLED");
        insertPromise(sid, "FULFILLED");
        insertPromise(sid, "BROKEN");

        svc.recomputeAll(null);
        int uses1 = uses(sid);
        double w1 = wilson(sid);

        svc.recomputeAll(null);

        assertThat(uses(sid)).as("重复跑不许把使用量翻倍").isEqualTo(uses1);
        assertThat(wilson(sid)).isEqualTo(w1);
    }

    /** 达标的 AI 变体（uses≥2 且 uplift≥0.02、A/B 已跑赢）自动升为现行，旧文本保留可回滚。 */
    @Test
    void 达标的AI变体自动晋升() {
        long sid = insertScript("AI_MINED", "CANDIDATE", "{\"text\":\"新话术\",\"uplift\":0.08,\"state\":\"WINNER\"}", 0);
        insertPromise(sid, "FULFILLED");
        insertPromise(sid, "FULFILLED");

        FlywheelService.Result r = svc.recomputeAll(null);

        assertThat(r.promoted()).isGreaterThanOrEqualTo(1);
        assertThat(status(sid)).isEqualTo("EFFECTIVE");
        assertThat(variantState(sid)).isEqualTo("PROMOTED");
        assertThat(variantText(sid)).as("旧文本必须保留，晋升要可回滚").isEqualTo("新话术");
    }

    /**
     * <b>专家话术即使各项都达标，无人值守的定时器也不许自动晋升它。</b>
     * 机器可以提拔机器挖出来的话术，不能自己提拔人写的 —— 那必须由平台运营手点。
     */
    @Test
    void 专家话术永不自动晋升() {
        long sid = insertScript("EXPERT", "CANDIDATE", "{\"text\":\"专家变体\",\"uplift\":0.50,\"state\":\"WINNER\"}", 0);
        insertPromise(sid, "FULFILLED");
        insertPromise(sid, "FULFILLED");

        svc.recomputeAll(null);

        assertThat(status(sid)).as("EXPERT 话术必须仍是候选 —— 自动晋升只准动 AI_MINED").isEqualTo("CANDIDATE");
        assertThat(variantState(sid)).isEqualTo("WINNER");
    }

    /** 跑赢了但样本还不够（uses < 阈值）→ 不晋升。小样本神话术不许上位。 */
    @Test
    void 样本不足不晋升() {
        long sid = insertScript("AI_MINED", "CANDIDATE", "{\"text\":\"三次两中\",\"uplift\":0.90,\"state\":\"WINNER\"}", 0);
        insertPromise(sid, "FULFILLED");   // uses=1 < 阈值 2

        svc.recomputeAll(null);

        assertThat(status(sid)).as("样本不足时不许晋升").isEqualTo("CANDIDATE");
    }

    /** 开关必须真的关得掉：auto=false 时定时器一个字节都不许改。 */
    @Test
    void 关掉开关后定时器不动任何数据() {
        long sid = insertScript("EXPERT", "CANDIDATE", null, 0);
        insertPromise(sid, "FULFILLED");

        new FlywheelScheduler(svc, false).tick();
        assertThat(uses(sid)).as("auto=false 时定时器必须原地不动").isZero();

        new FlywheelScheduler(svc, true).tick();
        assertThat(uses(sid)).as("auto=true 时定时器要真的结算（否则加了个不干活的定时器）").isEqualTo(1);
    }

    /**
     * 自己造的承诺自己收走。CI 里所有 IT 共用一个库，而 promise → case 是 ON DELETE RESTRICT：
     * 留在库里的承诺会让别的用例（清场时 DELETE FROM "case"）炸在外键上，报错还指向人家的 setup。
     */
    @AfterAll
    static void cleanup() {
        if (jdbc == null) return;
        jdbc.update("DELETE FROM promise WHERE case_id = ?", caseId);
        jdbc.update("DELETE FROM \"case\" WHERE id = ?", caseId);
    }

    // ── helpers ──
    private static long insertScript(String source, String status, String variantJson, int uses) {
        return jdbc.queryForObject(
                "INSERT INTO script_lib(scene,intent,source,status,uses,variant)"
                        + " VALUES ('催缴','还款',?,?,?,?::jsonb) RETURNING id",
                Long.class, source, status, uses, variantJson);
    }

    private static void insertPromise(long scriptId, String state) {
        jdbc.update("INSERT INTO promise(case_id,date,amount_cents,state,created_by,script_id)"
                + " VALUES (?, now()::date, 100000, ?, ?, ?)", caseId, state, accId, scriptId);
    }

    private static int uses(long sid) {
        return jdbc.queryForObject("SELECT uses FROM script_lib WHERE id=?", Integer.class, sid);
    }

    private static double promiseRate(long sid) {
        return jdbc.queryForObject("SELECT promise_rate FROM script_lib WHERE id=?", Double.class, sid);
    }

    private static double wilson(long sid) {
        return jdbc.queryForObject("SELECT wilson FROM script_lib WHERE id=?", Double.class, sid);
    }

    private static String status(long sid) {
        return jdbc.queryForObject("SELECT status FROM script_lib WHERE id=?", String.class, sid);
    }

    private static String variantState(long sid) {
        return jdbc.queryForObject("SELECT variant->>'state' FROM script_lib WHERE id=?", String.class, sid);
    }

    private static String variantText(long sid) {
        return jdbc.queryForObject("SELECT variant->>'text' FROM script_lib WHERE id=?", String.class, sid);
    }
}
