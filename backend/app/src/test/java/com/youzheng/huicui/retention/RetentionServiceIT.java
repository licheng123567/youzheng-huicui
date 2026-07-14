package com.youzheng.huicui.retention;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 数据留存与去标识化。
 *
 * <p>这组用例要守住的，是一条**很容易被悄悄改坏、而改坏了不会有人发现**的逻辑：
 * 清理任务少写一个 WHERE、或者 legal_hold 判断写反，后果分别是
 * <b>该删的没删（个人信息永久留存）</b>和<b>不该删的删了（投诉时证据没了）</b>——
 * 两种都要等到出事那天才知道。
 */
class RetentionServiceIT {

    private static final String URL = System.getenv().getOrDefault("HUICUI_IT_JDBC_URL", "");
    private static final String USER = System.getenv().getOrDefault("HUICUI_IT_JDBC_USER", "huicui");
    private static final String PASS = System.getenv().getOrDefault("HUICUI_IT_JDBC_PASSWORD", "Str0ngProdPw");

    private static JdbcTemplate jdbc;
    private RetentionService svc;
    private long orgId;
    private long projectId;
    private long batchId;
    private long actorId;

    @BeforeAll
    static void migrate() {
        assumeTrue(!URL.isBlank(), "未设置 HUICUI_IT_JDBC_URL，跳过留存 IT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(URL);
        pg.setUser(USER);
        pg.setPassword(PASS);
        DataSource ds = pg;
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
    }

    @BeforeEach
    void setup() {
        assumeTrue(!URL.isBlank());
        jdbc.update("DELETE FROM transcript_segment");
        jdbc.update("DELETE FROM call_recording");
        jdbc.update("DELETE FROM contact");
        jdbc.update("DELETE FROM co_pay_doc_line");
        jdbc.update("DELETE FROM co_pay_doc");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM repay_line");
        jdbc.update("DELETE FROM \"case\"");

        svc = new RetentionService(jdbc, new com.youzheng.huicui.storage.PgBlobStore(), true, 60, 180);

        orgId = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROPERTY','测试物业','ACTIVE') RETURNING id", Long.class);
        actorId = jdbc.queryForObject(
                "INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)"
                        + " VALUES (?,?,'负责人','13900000002','PL','ACTIVE',true) RETURNING id",
                Long.class, orgId, "pl_" + System.nanoTime());
        String orgName = jdbc.queryForObject("SELECT name FROM org WHERE id=?", String.class, orgId);
        String pname = "翠湖一期" + System.nanoTime();
        projectId = jdbc.queryForObject(
                "INSERT INTO project(org_id,name,org_name,status,area,comm_in_rate)"
                        + " VALUES (?,?,?,'ACTIVE','测试区',0.30) RETURNING id",
                Long.class, orgId, pname, orgName);
        batchId = jdbc.queryForObject(
                "INSERT INTO batch(project_id,no,status,comm_in_rate) VALUES (?,?,'DISPATCHED',0.30) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime());
    }

    /** 结案 61 天：业主身份必须消失，而录音（举证证据）必须还在。 */
    @Test
    void 结案61天_PII去标识化但录音仍保留() {
        long caseId = insertClosedCase("张三", "3-1-502", 61);
        insertContact(caseId, "13900001111");
        long recId = insertRecording(caseId, "喂，张三先生，您在翠湖一期3-1-502的物业费…");

        svc.anonymizeExpiredPii();
        svc.purgeExpiredRecordings();

        // 身份没了
        assertThat(str("SELECT owner_name FROM \"case\" WHERE id=" + caseId))
                .as("姓名必须被不可逆占位替换").isEqualTo("业主(案件" + caseId + ")");
        assertThat(str("SELECT room FROM \"case\" WHERE id=" + caseId))
                .as("房号必须抹掉——「小区+房号」足以定位到具体一户，抹了姓名不抹房号等于没匿名")
                .isEqualTo("—");
        assertThat(cnt("SELECT count(*) FROM contact WHERE case_id=" + caseId))
                .as("手机号不能哈希（11 位可穷举反查），只能整行删除").isZero();
        assertThat(str("SELECT litigation_fields->>'idCard' FROM \"case\" WHERE id=" + caseId))
                .as("身份证号必须删除").isNull();

        // 小区留着：报表按它聚合，且单凭小区定位不到个人
        assertThat(str("SELECT project_name FROM \"case\" WHERE id=" + caseId))
                .as("小区应保留（报表聚合维度）").isNotBlank();

        // 录音还在：60 天就删掉，等业主投诉「暴力催收」时，平台自证清白的唯一证据就没了
        assertThat(str("SELECT transcript FROM call_recording WHERE id=" + recId))
                .as("结案 61 天时录音与转写必须仍在（举证窗口 180 天）").isNotNull();
        assertThat(cnt("SELECT count(*) FROM call_recording WHERE id=" + recId + " AND audio_bytes IS NOT NULL"))
                .isEqualTo(1);
    }

    /** 结案 181 天：录音音频、转写全文、逐句句段一并删除。 */
    @Test
    void 结案181天_录音与转写被删除() {
        long caseId = insertClosedCase("李四", "5-2-101", 181);
        long recId = insertRecording(caseId, "喂，李四女士…");
        jdbc.update("INSERT INTO transcript_segment(recording_id,seq,speaker,text) VALUES (?,0,'CO','李四女士您好')",
                recId);

        svc.purgeExpiredRecordings();

        assertThat(cnt("SELECT count(*) FROM call_recording WHERE id=" + recId + " AND audio_bytes IS NOT NULL"))
                .as("音频字节必须删除（声纹属敏感个人信息，录音无法「去标识化」，只能删）").isZero();
        assertThat(str("SELECT transcript FROM call_recording WHERE id=" + recId))
                .as("转写全文必须删除").isNull();
        assertThat(cnt("SELECT count(*) FROM transcript_segment WHERE recording_id=" + recId))
                .as("逐句句段是 PII 密度最高的地方，留一句都白抹").isZero();

        // 录音行本身保留：谁在什么时候打了多久，是质检与计费的骨架，且不含 PII
        assertThat(cnt("SELECT count(*) FROM call_recording WHERE id=" + recId)).isEqualTo(1);
    }

    /**
     * <b>法律保留：一律跳过。</b>
     *
     * <p>这条是整组里最要紧的：正在投诉/诉讼的案件，定时任务若照删不误，
     * 就会在你最需要证据的那一刻把证据删掉 —— 而且是自动的、静默的、不可逆的。
     */
    @Test
    void 法律保留的案件_即使早已过期也一律不动() {
        long caseId = insertClosedCase("王五", "9-1-303", 999);   // 早就该清了
        insertContact(caseId, "13900002222");
        long recId = insertRecording(caseId, "王五先生，关于您的欠费…");
        jdbc.update("UPDATE \"case\" SET legal_hold=TRUE, legal_hold_reason='业主已投诉至住建局',"
                + " legal_hold_by=?, legal_hold_at=now() WHERE id=?", actorId, caseId);

        svc.anonymizeExpiredPii();
        svc.purgeExpiredRecordings();

        assertThat(str("SELECT owner_name FROM \"case\" WHERE id=" + caseId))
                .as("法律保留期间，姓名不得被抹").isEqualTo("王五");
        assertThat(cnt("SELECT count(*) FROM contact WHERE case_id=" + caseId))
                .as("法律保留期间，联系方式不得删除").isEqualTo(1);
        assertThat(str("SELECT transcript FROM call_recording WHERE id=" + recId))
                .as("法律保留期间，录音是自证清白的证据，绝不能删").isNotNull();
    }

    /** 未结案的案件（还在催）：一动不动。 */
    @Test
    void 未结案的案件不受影响() {
        long caseId = jdbc.queryForObject(
                "INSERT INTO \"case\"(batch_id,project_id,project_name,acct_no,owner_name,room,due_cents,status,pool)"
                        + " VALUES (?,?, (SELECT name FROM project WHERE id=?), ?, '赵六','1-1-101',500000,"
                        + " 'IN_PROGRESS','PROVIDER_SEA') RETURNING id",
                Long.class, batchId, projectId, projectId, "A-" + System.nanoTime());
        insertContact(caseId, "13900003333");

        svc.anonymizeExpiredPii();

        assertThat(str("SELECT owner_name FROM \"case\" WHERE id=" + caseId))
                .as("还在催的案件不该被清理").isEqualTo("赵六");
        assertThat(cnt("SELECT count(*) FROM contact WHERE case_id=" + caseId)).isEqualTo(1);
    }

    /**
     * 时钟起点不是 closed_at，而是 max(结案, 末笔回款, …)。
     *
     * <p>坏账案件可能重启诉讼、佣金争议常在结案数月后爆发 —— 那时还要靠业主身份去核对回款。
     * 只看 closed_at 会把这些案件提前清掉。
     */
    @Test
    void 结案已久但近期仍有回款_锚点后移不清理() {
        long caseId = insertClosedCase("孙七", "2-2-202", 200);
        // 结案 200 天前，但 10 天前还收到一笔钱（坏账后又还款 / 诉讼执行回款）
        jdbc.update("INSERT INTO repay_line(case_id,batch_id,amount_cents,channel,paid_at,marked_by,settled,created_at)"
                        + " VALUES (?,?,100000,'CASH',now()::date,?,false, now() - interval '10 days')",
                caseId, batchId, actorId);

        svc.anonymizeExpiredPii();

        assertThat(str("SELECT owner_name FROM \"case\" WHERE id=" + caseId))
                .as("末笔回款才 10 天，锚点后移 → 不该清理（否则佣金争议时对不上人）")
                .isEqualTo("孙七");
    }

    // ── 脚手架 ────────────────────────────────────────────────────────────────

    private long insertClosedCase(String owner, String room, int closedDaysAgo) {
        return jdbc.queryForObject(
                "INSERT INTO \"case\"(batch_id,project_id,project_name,acct_no,owner_name,room,due_cents,"
                        + " status,pool,closed_at,litigation_fields)"
                        + " VALUES (?,?, (SELECT name FROM project WHERE id=?), ?, ?, ?, 500000,"
                        + " 'SETTLED','PROVIDER_SEA', now() - (? || ' days')::interval,"
                        + " '{\"idCard\":\"110101199001011234\",\"mailingAddr\":\"北京市…\",\"buildingArea\":\"89\"}'::jsonb)"
                        + " RETURNING id",
                Long.class, batchId, projectId, projectId, "A-" + System.nanoTime(), owner, room, closedDaysAgo);
    }

    private void insertContact(long caseId, String phone) {
        jdbc.update("INSERT INTO contact(case_id,phone,label,is_primary) VALUES (?,?,'业主本人',true)",
                caseId, phone);
    }

    private long insertRecording(long caseId, String transcript) {
        return jdbc.queryForObject(
                "INSERT INTO call_recording(case_id,collector_id,source,status,transcript,audio_bytes,duration_sec)"
                        + " VALUES (?,?,'APP_AUTO','READY',?, decode('01020304','hex'), 95) RETURNING id",
                Long.class, caseId, actorId, transcript);
    }

    private String str(String sql) {
        return jdbc.query(sql, rs -> rs.next() ? rs.getString(1) : null);
    }

    private long cnt(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
