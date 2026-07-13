package com.youzheng.huicui.money;

import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.DataRange;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.CaseScopeM4Service;
import com.youzheng.huicui.web.PayReduceRepayM4Controller;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 资金正确性的**并发**回归。这是本仓最要紧的一组测试，理由很直白：
 * 出问题的地方是「回款入口」和「催收员佣金单」——钱能在那里被凭空造出来，
 * 而在那之前，**79 条业务规则里只有 1 条有测试，核心资金链路的覆盖是零**。
 *
 * <p><b>为什么必须用真 PG、真线程</b>：这里要证的每一条都是**数据库层的并发语义**——
 * 唯一索引裁决、{@code FOR UPDATE} 行锁串行化、CHECK 约束。Mock 一个 JdbcTemplate 什么都证明不了：
 * 它连"两个事务同时跑"这件事都表达不出来，而 bug 恰恰只在那一刻发生。
 *
 * <p><b>为什么不用 Testcontainers</b>：本仓已有的 {@code HoldCapConcurrencyIT} 用的是 Testcontainers，
 * 而它在 Docker 29 上直接跑不起来（{@code Could not find a valid Docker environment}，实测），
 * 且 CI 明确把 failsafe 排除在外 —— <b>全仓唯一的并发测试，从来没有在任何地方跑过一次</b>。
 * 所以这里改成连一个**外部 PG**（CI 用 service container，本地用你自己的库），
 * 没配就跳过而不是失败。让它能真的跑起来，比让它写得漂亮重要。
 *
 * <p>跑法：{@code HUICUI_IT_JDBC_URL=jdbc:postgresql://localhost:5470/huicui mvn verify}
 */
class RepayConcurrencyIT {

    private static final String URL = System.getenv().getOrDefault("HUICUI_IT_JDBC_URL", "");
    private static final String USER = System.getenv().getOrDefault("HUICUI_IT_JDBC_USER", "huicui");
    private static final String PASS = System.getenv().getOrDefault("HUICUI_IT_JDBC_PASSWORD", "Str0ngProdPw");

    private static DataSource ds;
    private static JdbcTemplate jdbc;
    /** 直接 new 出来的 controller 没有 Spring 代理，@Transactional 不生效 ——
     *  必须自己把调用包进事务，否则测的就不是生产语义（FOR UPDATE 行锁在自动提交下当场就释放了）。 */
    private static TransactionTemplate tx;

    private PayReduceRepayM4Controller controller;
    private long caseId;
    private long actorId;
    private long orgId;

    @BeforeAll
    static void migrate() {
        assumeTrue(!URL.isBlank(), "未设置 HUICUI_IT_JDBC_URL，跳过资金并发 IT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(URL);
        pg.setUser(USER);
        pg.setPassword(PASS);
        ds = pg;
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
    }

    @BeforeEach
    void setup() {
        assumeTrue(!URL.isBlank());

        // 每个用例一套干净数据（不删别人的表，只建自己的行）
        jdbc.update("DELETE FROM co_pay_doc_line");   // 先删子表：repay_line 被它外键引用
        jdbc.update("DELETE FROM co_pay_doc");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM repay_line");
        jdbc.update("DELETE FROM idempotency_record");

        orgId = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROPERTY','测试物业','ACTIVE') RETURNING id", Long.class);
        actorId = jdbc.queryForObject(
                "INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)"
                        + " VALUES (?,?,'物业负责人','13900000001','PL','ACTIVE',true) RETURNING id",
                Long.class, orgId, "pc_" + System.nanoTime());
        String orgName = jdbc.queryForObject("SELECT name FROM org WHERE id=?", String.class, orgId);
        String projectName = "测试项目" + System.nanoTime();
        long projectId = jdbc.queryForObject(
                "INSERT INTO project(org_id,name,org_name,status,area,comm_in_rate) VALUES (?,?,?,'ACTIVE','测试区',0.30) RETURNING id",
                Long.class, orgId, projectName, orgName);
        long batchId = jdbc.queryForObject(
                "INSERT INTO batch(project_id,no,status,comm_in_rate,pay_out_rate) VALUES (?,?,'DISPATCHED',0.30,0.20) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime());
        caseId = jdbc.queryForObject(
                "INSERT INTO \"case\"(batch_id,project_id,project_name,acct_no,owner_name,room,due_cents,status,pool)"
                        + " VALUES (?,?,?,?,'张三','1-101',1000000,'IN_PROGRESS','PROVIDER_SEA') RETURNING id",
                Long.class, batchId, projectId, projectName, "A-" + System.nanoTime());

        controller = new PayReduceRepayM4Controller(jdbc, new CaseScopeM4Service(jdbc), null, null);
    }

    /**
     * <b>核心用例：协调员双击「标注回款」。</b>
     *
     * <p>两个并发请求带同一个 Idempotency-Key。修复前 repay_line 除主键外**没有任何唯一约束**、
     * 创建是裸 INSERT、案件不加锁 —— 两条都会插进去：同一笔 5000 元变成两笔，案件被误判结清、
     * 物业多收一笔收佣、服务商多付一笔付佣、催收员多拿一笔提成。<b>钱真的出去了。</b>
     *
     * <p>修复后：数据库靠 {@code uq_repay_line_idem_key} 裁决，只落一笔；第二个请求拿回<b>第一笔</b>
     * （契约原话「同 key 重复请求返回首次结果」），而不是一个"重复了"的错误 ——
     * 界面因此始终知道这笔钱到底记上没有。
     */
    @Test
    void 同一个幂等键并发提交_只落一笔回款() throws Exception {
        String idemKey = "idem-" + System.nanoTime();
        int threads = 8;

        var results = runConcurrently(threads, () ->
                asActor(() -> controller.createRepayLine(String.valueOf(caseId), idemKey,
                        Map.of("amountCents", 500000, "channel", "CASH", "paidAt", "2026-07-13"))));

        long rows = jdbc.queryForObject(
                "SELECT count(*) FROM repay_line WHERE case_id = ?", Long.class, caseId);
        assertThat(rows)
                .as("并发同键提交 %d 次，只能落一笔回款（否则钱被凭空造出来）", threads)
                .isEqualTo(1);

        // **这条断言才是真正咬人的那一条。**
        // 只断言"只落一笔"是不够的：光靠 DB 唯一索引，重复的那 7 个请求会以**报错**收场——
        // 钱是安全了，可协调员看到的是 7 个失败，他无从知道这笔钱到底记上没有，多半会再点一次。
        // 契约要求的是「同 key 重复请求**返回首次结果**」：8 个请求必须**全部成功**、且拿回同一笔。
        // （实测：把控制器退回裸 INSERT，上面那条断言照样绿，只有这条会红。）
        assertThat(lastFailureCount)
                .as("同键重放必须成功返回首次那一笔，而不是报错")
                .isZero();

        // 每个成功返回的请求都必须指向**同一笔**——这才是"返回首次结果"
        Set<String> ids = results.stream().filter(r -> r != null)
                .map(r -> ((com.youzheng.huicui.web.dto.RepayLineDto) r).id())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(ids).as("所有并发请求必须拿回同一笔回款的 id").hasSize(1);

        long total = jdbc.queryForObject(
                "SELECT coalesce(sum(amount_cents),0) FROM repay_line WHERE case_id = ? AND NOT reversed",
                Long.class, caseId);
        assertThat(total).as("案件累计回款只能是 5000 元，不是 5000×N").isEqualTo(500000L);
    }

    /**
     * 不同幂等键 = 两笔**真实存在的**回款（同一天两笔等额现金是合法业务），必须都落。
     * 幂等不能把合法业务也一并挡掉 —— 这正是"不能用 UNIQUE(case_id, amount, paid_at, channel) 去重"的原因。
     */
    @Test
    void 不同幂等键并发提交_两笔都落且金额正确() throws Exception {
        runConcurrently(2, new Callable[]{
                () -> asActor(() -> controller.createRepayLine(String.valueOf(caseId), "k-a-" + System.nanoTime(),
                        Map.of("amountCents", 300000, "channel", "CASH", "paidAt", "2026-07-13"))),
                () -> asActor(() -> controller.createRepayLine(String.valueOf(caseId), "k-b-" + System.nanoTime(),
                        Map.of("amountCents", 300000, "channel", "CASH", "paidAt", "2026-07-13"))),
        });

        long rows = jdbc.queryForObject(
                "SELECT count(*) FROM repay_line WHERE case_id = ?", Long.class, caseId);
        assertThat(rows).as("两个不同的键 = 两笔真实回款，都要落").isEqualTo(2);

        long total = jdbc.queryForObject(
                "SELECT coalesce(sum(amount_cents),0) FROM repay_line WHERE case_id = ?", Long.class, caseId);
        assertThat(total).isEqualTo(600000L);
    }

    /** 佣金倒挂：收 < 付 = 平台每笔回款都亏钱。DB 层必须挡住（应用层的闸在派单路径上不生效）。 */
    @Test
    void 佣金倒挂被数据库拒绝() {
        long projectId = jdbc.queryForObject("SELECT project_id FROM \"case\" WHERE id=?", Long.class, caseId);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO batch(project_id,no,status,comm_in_rate,pay_out_rate)"
                        + " VALUES (?,?,'DISPATCHED',0.10,0.20)",
                projectId, "B-inv-" + System.nanoTime()))
                .as("收佣 10% < 付佣 20% 必须被 chk_batch_commission_not_inverted 拒绝")
                .isInstanceOf(DataAccessException.class);
    }

    /** 同一笔回款不得进两张催收员佣金单（佣金唯一被物化存储的地方，重复了无法自愈）。 */
    @Test
    void 同一笔回款不能进两张佣金单() {
        long batchId = jdbc.queryForObject("SELECT batch_id FROM \"case\" WHERE id=?", Long.class, caseId);
        long lineId = jdbc.queryForObject(
                "INSERT INTO repay_line(case_id,batch_id,amount_cents,channel,paid_at,marked_by,settled)"
                        + " VALUES (?,?,100000,'CASH','2026-07-13',?,false) RETURNING id",
                Long.class, caseId, batchId, actorId);
        long doc1 = insertCoPayDoc();
        long doc2 = insertCoPayDoc();

        jdbc.update("INSERT INTO co_pay_doc_line(co_pay_doc_id,repay_line_id,case_id,room,owner_name,"
                + "repay_cents,rate,comm_cents) VALUES (?,?,?,'1-101','张三',100000,0.15,15000)",
                doc1, lineId, caseId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO co_pay_doc_line(co_pay_doc_id,repay_line_id,case_id,room,owner_name,"
                        + "repay_cents,rate,comm_cents) VALUES (?,?,?,'1-101','张三',100000,0.15,15000)",
                doc2, lineId, caseId))
                .as("同一笔回款进第二张佣金单必须被 uq_co_pay_doc_line_repay 拒绝")
                .isInstanceOf(DataAccessException.class);
    }

    // ── 脚手架 ────────────────────────────────────────────────────────────────

    private long insertCoPayDoc() {
        return jdbc.queryForObject(
                "INSERT INTO co_pay_doc(collector_id,line_ids,count,amount_cents,status)"
                        + " VALUES (?,'[]'::jsonb,1,15000,'PENDING_PAY') RETURNING id",
                Long.class, actorId);
    }

    /** 以协调员身份执行（controller 从 SubjectContext 取主体）。 */
    private Object asActor(Callable<Object> body) throws Exception {
        // 用物业负责人(PL)：case-actor 只要求「本物业」。协调员(PC)还要求在该项目的协调员名单里，
        // 那是另一条正交的权限规则，不该混进资金并发的用例里。
        SubjectContext.set(new CurrentSubject(
                String.valueOf(actorId), "物业负责人", String.valueOf(orgId), "PROPERTY", "测试物业", "PL",
                Set.of("case.repay.mark"), DataRange.UNRESTRICTED));
        try {
            // 包进事务：复现 @Transactional 的边界（案件行锁必须在事务里才有意义）
            return tx.execute(status -> {
                try {
                    return body.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            SubjectContext.clear();
        }
    }

    /** 真并发：所有线程卡在同一个闸门上一起冲，才测得到"两个事务同时进来"。 */
    private java.util.List<Object> runConcurrently(int n, Callable<Object> task) throws Exception {
        Callable<Object>[] tasks = new Callable[n];
        java.util.Arrays.fill(tasks, task);
        return runConcurrently(n, tasks);
    }

    /** 上一次 runConcurrently 中抛异常的请求数。 */
    private int lastFailureCount;

    private java.util.List<Object> runConcurrently(int n, Callable<Object>[] tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Exception> lastError = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<Future<Object>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Object> t = tasks[i];
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    return t.call();
                } catch (Exception e) {
                    failures.incrementAndGet();
                    lastError.compareAndSet(null, e);
                    return null;   // 并发下部分请求失败是允许的；不允许的是"钱多了一笔"
                }
            }));
        }
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("并发任务应在 60s 内结束").isTrue();

        // 全军覆没 = 不是并发竞争，是代码/夹具坏了。必须把真实异常抛出来，
        // 否则测试会以"0 笔回款"这种**看起来像通过了**的方式骗人。
        if (failures.get() == n && lastError.get() != null) {
            throw new IllegalStateException("全部 " + n + " 个并发请求都失败了", lastError.get());
        }
        lastFailureCount = failures.get();
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Future<Object> f : futures) out.add(f.get());
        return out;
    }
}
